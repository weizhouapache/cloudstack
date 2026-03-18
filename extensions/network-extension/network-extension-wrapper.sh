#!/bin/bash
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

##############################################################################
# network-extension-wrapper.sh
#
# Network Extension wrapper script for Apache CloudStack.
# Runs on a KVM host; manages Linux network namespaces, VLAN bridges,
# and iptables rules to implement isolated guest networks.
#
# Architecture
# ============
#
# Guest (internal) side
# ---------------------
# Each CloudStack isolated network has a VLAN.  On the KVM host:
#
#   ethX.<vlan>         – VLAN sub-interface on the physical NIC (ethX)
#   br<ethX>-<vlan>     – Linux bridge: ethX.<vlan> + vh-<vlan>-<id>
#   vh-<vlan>-<id>      – host end of the veth pair → in the bridge
#   vn-<vlan>-<id>      – namespace end → assigned the network gateway IP
#
# ethX is read from guest.network.device in the physical-network extension
# details (defaults to eth1 when absent).
#
# Namespace
# ---------
#   Isolated network : cs-net-<networkId>   (--network-id)
#   VPC network      : cs-net-<vpcId>       (--vpc-id)
#
# Public (NAT) side
# -----------------
# For each public IP assigned to the network a veth pair is created:
#
#   vph-<pvlan>-<id>   – host end → added to br<pub_ethX>-<pvlan>
#   vpn-<pvlan>-<id>   – namespace end → assigned the public IP
#
# where <pvlan>  = public VLAN tag (from --public-vlan)
#       <id>     = vpc-id if present, else network-id
#       pub_ethX = read from public.network.device in extension details
#                  (defaults to eth1 when absent)
#
# Interface name lengths (Linux limit: 15 chars)
#   vh-<vlan>-<id>     max 15 (shorten_id applied when needed) ✓
#   vn-<vlan>-<id>     max 15 (shorten_id applied when needed) ✓
#   vph-<pvlan>-<id>   max 15 ✓
#   vpn-<pvlan>-<id>   max 15 ✓
#
# iptables chains (inside namespace)
# ------------------------------------
#   CS_EXTNET_<networkId>_PR   – PREROUTING  DNAT chain
#   CS_EXTNET_<networkId>_POST – POSTROUTING SNAT chain
#   CS_EXTNET_FWD_<networkId>  – FORWARD filter chain
#
# CLI arguments (forwarded by network-extension.sh):
#   --physical-network-extension-details <json>
#       kvmnetworklabel, public_kvmnetworklabel, hosts, username, …
#   --network-extension-details <json>
#       host, namespace, …
##############################################################################

set -e

LOG_FILE="/var/log/cloudstack/network-extension.log"
STATE_DIR="/var/lib/cloudstack/network-extension"

# ---------------------------------------------------------------------------
# JSON helpers (no jq dependency)
# ---------------------------------------------------------------------------

_json_get() {
    # _json_get <json> <key>  →  value (unquoted string) or empty
    printf '%s' "$1" | grep -o "\"$2\":\"[^\"]*\"" | cut -d'"' -f4 || true
}

# ---------------------------------------------------------------------------
# Pre-scan all arguments for the two JSON blobs.
# ---------------------------------------------------------------------------

PHYS_DETAILS="${CS_PHYSICAL_NETWORK_EXTENSION_DETAILS:-{}}"
EXTENSION_DETAILS="${CS_NETWORK_EXTENSION_DETAILS:-{}}"

_pre_scan_args() {
    local i=1
    local args=("$@")
    while [ $i -le $# ]; do
        case "${args[$i-1]}" in
            --physical-network-extension-details)
                PHYS_DETAILS="${args[$i]:-{}}"
                i=$((i+2)) ;;
            --network-extension-details)
                EXTENSION_DETAILS="${args[$i]:-{}}"
                i=$((i+2)) ;;
            *) i=$((i+1)) ;;
        esac
    done
}

_pre_scan_args "$@"

# ---------------------------------------------------------------------------
# Resolve host network interfaces from physical-network extension details.
#
# Register guest.network.device and public.network.device when attaching the
# extension to a physical network, e.g.:
#   details[N].key=guest.network.device  details[N].value=eth1
#   details[M].key=public.network.device details[M].value=eth1
#
# Both default to eth1 when absent.
# ---------------------------------------------------------------------------

GUEST_ETH=$(_json_get "${PHYS_DETAILS}" "guest.network.device")
GUEST_ETH="${GUEST_ETH:-eth1}"

PUB_ETH=$(_json_get "${PHYS_DETAILS}" "public.network.device")
PUB_ETH="${PUB_ETH:-eth1}"

# iptables chain prefix
CHAIN_PREFIX="CS_EXTNET"

##############################################################################
# Helpers
##############################################################################

log() {
    local ts
    ts=$(date '+%Y-%m-%d %H:%M:%S')
    echo "[${ts}] $*" >> "${LOG_FILE}" 2>/dev/null || true
}

die() {
    log "ERROR: $*"
    exit 1
}

ensure_dirs() {
    mkdir -p "${STATE_DIR}" "$(dirname "${LOG_FILE}")" 2>/dev/null || true
}

acquire_lock() {
    local network_id="$1"
    local lockfile="${STATE_DIR}/lock-${network_id}"
    mkdir -p "${STATE_DIR}"
    exec 200>"${lockfile}"
    flock -w 30 200 || die "Failed to acquire lock for network ${network_id}"
}

release_lock() {
    exec 200>&- 2>/dev/null || true
}

# ---------------------------------------------------------------------------
# Interface / bridge name helpers
# ---------------------------------------------------------------------------

# Host bridge for a VLAN on a given physical NIC:  br<eth>-<vlan>
host_bridge_name() {
    local eth="$1" vlan_raw="$2" vlan
    vlan=$(normalize_vlan "${vlan_raw}")
    echo "br${eth}-${vlan}"
}

# Internal guest veth pair (keyed on VLAN ID and network id):
#   vh-<vlan>-<id>  (host, in bridge)
#   vn-<vlan>-<id>  (namespace, gets gateway IP)

# shorten_id <id> -> short deterministic id portion suitable for interface names
shorten_id() {
    local id="$1"
    [ -z "${id}" ] && echo "" && return
    # If purely numeric, use hex to shorten (stable)
    if printf '%d' "${id}" >/dev/null 2>&1; then
        printf '%x' "${id}"
        return
    fi
    # For non-numeric, prefer an md5 prefix if available
    if command -v md5sum >/dev/null 2>&1; then
        echo -n "${id}" | md5sum | awk '{print $1}' | cut -c1-6
        return
    fi
    # Fallback: last 6 chars
    echo "${id}" | awk '{n=length($0); print substr($0, n-5)}'
}

# normalize_vlan <vlan_raw> -> prints the normalized vlan id (strip vlan:// prefix)
normalize_vlan() {
    local vlan_raw="$1"
    if [ -z "${vlan_raw}" ]; then
        printf '%s' ""
        return
    fi
    if printf '%s' "${vlan_raw}" | grep -q '^vlan://'; then
        printf '%s' "${vlan_raw#vlan://}"
    else
        printf '%s' "${vlan_raw}"
    fi
}

# Generate guest host veth name: vh-<vlan>-<id> (ensure <=15 chars)
veth_host_name() {
    local vlan_raw="$1" id="$2" name short
    vlan=$(normalize_vlan "${vlan_raw}")
    name="vh-${vlan}-${id}"
    if [ ${#name} -le 15 ]; then
        echo "${name}"
        return
    fi
    short=$(shorten_id "${id}")
    name="vh-${vlan}-${short}"
    if [ ${#name} -le 15 ]; then
        echo "${name}"
        return
    fi
    echo "${name:0:15}"
}

# Generate guest namespace veth name: vn-<vlan>-<id> (ensure <=15 chars)
veth_ns_name() {
    local vlan_raw="$1" id="$2" name short
    vlan=$(normalize_vlan "${vlan_raw}")
    name="vn-${vlan}-${id}"
    if [ ${#name} -le 15 ]; then
        echo "${name}"
        return
    fi
    short=$(shorten_id "${id}")
    name="vn-${vlan}-${short}"
    if [ ${#name} -le 15 ]; then
        echo "${name}"
        return
    fi
    echo "${name:0:15}"
}

# Public veth pair (keyed on public VLAN and network/vpc id):
#   vph-<pvlan>-<id>  (host, in public bridge)
#   vpn-<pvlan>-<id>  (namespace, gets public IP)
pub_veth_host_name() {
    local pvlan_raw="$1" id="$2" pvlan
    pvlan=$(normalize_vlan "${pvlan_raw}")
    echo "vph-${pvlan}-${id}"
}

pub_veth_ns_name() {
    local pvlan_raw="$1" id="$2" pvlan
    pvlan=$(normalize_vlan "${pvlan_raw}")
    echo "vpn-${pvlan}-${id}"
}

nat_chain()    { echo "${CHAIN_PREFIX}_${1}"; }
filter_chain() { echo "${CHAIN_PREFIX}_FWD_${1}"; }

# ---------------------------------------------------------------------------
# ensure_host_bridge <eth> <vlan>
# Idempotently creates br<eth>-<vlan> with <eth>.<vlan> as a member.
# Prints the bridge name.
# ---------------------------------------------------------------------------

ensure_host_bridge() {
    local eth="$1"
    local vlan_raw="$2"
    local vlan=$(normalize_vlan "${vlan_raw}")
    local br vif current_master

    br=$(host_bridge_name "${eth}" "${vlan}")
    vif="${eth}.${vlan}"

    # VLAN sub-interface
    if ! ip link show "${vif}" >/dev/null 2>&1; then
        ip link add link "${eth}" name "${vif}" type vlan id "${vlan}"
        log "Created VLAN interface ${vif}"
    fi
    ip link set "${vif}" up 2>/dev/null || true

    # Bridge
    if ! ip link show "${br}" >/dev/null 2>&1; then
        ip link add name "${br}" type bridge
        ip link set "${br}" up
        log "Created host bridge ${br}"
    fi

    # Attach VLAN interface to bridge (if not already there)
    current_master=$(ip link show "${vif}" 2>/dev/null | grep -o 'master [^ ]*' | awk '{print $2}' || true)
    if [ "${current_master}" != "${br}" ]; then
        ip link set "${vif}" master "${br}" 2>/dev/null || true
    fi

    echo "${br}"
}

ensure_chain() {
    local table="$1" chain="$2"
    ip netns exec "${NAMESPACE}" iptables -t "${table}" -n -L "${chain}" >/dev/null 2>&1 || \
        ip netns exec "${NAMESPACE}" iptables -t "${table}" -N "${chain}"
}

ensure_jump() {
    local table="$1" parent="$2" chain="$3"
    ip netns exec "${NAMESPACE}" iptables -t "${table}" \
        -C "${parent}" -j "${chain}" 2>/dev/null || \
    ip netns exec "${NAMESPACE}" iptables -t "${table}" \
        -I "${parent}" 1 -j "${chain}"
}

##############################################################################
# Parse common arguments
##############################################################################

parse_args() {
    NETWORK_ID=""
    NAMESPACE=""
    VPC_ID=""
    CHOSEN_ID=""
    VLAN=""
    GATEWAY=""
    CIDR=""
    PUBLIC_IP=""
    PRIVATE_IP=""
    PUBLIC_PORT=""
    PRIVATE_PORT=""
    PROTOCOL=""
    SOURCE_NAT="false"
    PUBLIC_GATEWAY=""
    PUBLIC_CIDR=""
    PUBLIC_VLAN=""
    # --- new fields ---
    MAC=""
    HOSTNAME=""
    DNS_SERVER=""
    NIC_ID=""
    DHCP_OPTIONS_JSON="{}"
    VM_IP=""
    USERDATA=""
    PASSWORD=""
    SSH_KEY=""
    HYPERVISOR_HOSTNAME=""
    LB_RULES_JSON="[]"

    while [ $# -gt 0 ]; do
        case "$1" in
            --network-id)          NETWORK_ID="$2";         shift 2 ;;
            --namespace)           NAMESPACE="$2";           shift 2 ;;
            --vpc-id)              VPC_ID="$2";              shift 2 ;;
            --vlan)                VLAN="$2";                shift 2 ;;
            --gateway)             GATEWAY="$2";             shift 2 ;;
            --cidr)                CIDR="$2";                shift 2 ;;
            --public-ip)           PUBLIC_IP="$2";           shift 2 ;;
            --private-ip)          PRIVATE_IP="$2";          shift 2 ;;
            --public-port)         PUBLIC_PORT="$2";         shift 2 ;;
            --private-port)        PRIVATE_PORT="$2";        shift 2 ;;
            --protocol)            PROTOCOL="$2";            shift 2 ;;
            --source-nat)          SOURCE_NAT="$2";          shift 2 ;;
            --public-gateway)      PUBLIC_GATEWAY="$2";      shift 2 ;;
            --public-cidr)         PUBLIC_CIDR="$2";         shift 2 ;;
            --public-vlan)         PUBLIC_VLAN="$2";         shift 2 ;;
            --mac)                 MAC="$2";                 shift 2 ;;
            --hostname)            HOSTNAME="$2";            shift 2 ;;
            --dns)                 DNS_SERVER="$2";          shift 2 ;;
            --nic-id)              NIC_ID="$2";              shift 2 ;;
            --options)             DHCP_OPTIONS_JSON="$2";   shift 2 ;;
            --ip)                  VM_IP="$2";               shift 2 ;;
            --userdata)            USERDATA="$2";            shift 2 ;;
            --password)            PASSWORD="$2";            shift 2 ;;
            --sshkey)              SSH_KEY="$2";             shift 2 ;;
            --hypervisor-hostname) HYPERVISOR_HOSTNAME="$2"; shift 2 ;;
            --lb-rules)            LB_RULES_JSON="$2";       shift 2 ;;
            # consumed by _pre_scan_args — skip silently
            --physical-network-extension-details|--network-extension-details)
                                   shift 2 ;;
            *)                     shift ;;
        esac
    done

    [ -z "${NETWORK_ID}" ] && die "Missing --network-id"

    # Namespace: VPC → cs-net-<vpcId>; standalone → cs-net-<networkId>
    if [ -z "${NAMESPACE}" ]; then
        if [ -n "${VPC_ID}" ]; then
            NAMESPACE="cs-net-${VPC_ID}"
        else
            local NS_FROM_DETAILS
            NS_FROM_DETAILS=$(_json_get "${EXTENSION_DETAILS}" "namespace")
            NAMESPACE="${NS_FROM_DETAILS:-cs-net-${NETWORK_ID}}"
        fi
    fi

    # CHOSEN_ID selects vpc-id when present, otherwise network-id
    CHOSEN_ID="${VPC_ID:-${NETWORK_ID}}"

    # Normalize VLAN if provided as 'vlan://<id>' (common in some callers)
    if [ -n "${VLAN}" ]; then
      VLAN=$(normalize_vlan "${VLAN}")
    fi
    # Normalize PUBLIC_VLAN as well
    if [ -n "${PUBLIC_VLAN}" ]; then
      PUBLIC_VLAN=$(normalize_vlan "${PUBLIC_VLAN}")
    fi
}

# Load persisted state (shutdown, destroy, IP operations)
_load_state() {
    if [ -z "${VLAN}" ] && [ -f "${STATE_DIR}/${NETWORK_ID}/vlan" ]; then
        VLAN=$(cat "${STATE_DIR}/${NETWORK_ID}/vlan")
    fi
    if [ -z "${CIDR}" ] && [ -f "${STATE_DIR}/${NETWORK_ID}/cidr" ]; then
        CIDR=$(cat "${STATE_DIR}/${NETWORK_ID}/cidr")
    fi
    if [ -z "${NAMESPACE}" ]; then
        if [ -f "${STATE_DIR}/${NETWORK_ID}/namespace" ]; then
            NAMESPACE=$(cat "${STATE_DIR}/${NETWORK_ID}/namespace")
        else
            local NS_FROM_DETAILS
            NS_FROM_DETAILS=$(_json_get "${EXTENSION_DETAILS}" "namespace")
            NAMESPACE="${NS_FROM_DETAILS:-cs-net-${NETWORK_ID}}"
        fi
    fi
    # CHOSEN_ID will be set by parse_args; do not read or persist legacy network_or_vpc_id
}

##############################################################################
# Command: implement
#
# 1. Create namespace cs-net-<id>
# 2. Create host bridge br<ethX>-<vlan> with ethX.<vlan> sub-interface
# 3. Create veth pair: veth-host-<vlan> (host, in bridge) / veth-ns-<vlan> (namespace)
# 4. Assign gateway IP to veth-ns-<vlan> inside namespace
# 5. Set up iptables chains inside namespace
##############################################################################

cmd_implement() {
    parse_args "$@"
    acquire_lock "${NETWORK_ID}"

    log "implement: network=${NETWORK_ID} ns=${NAMESPACE} vlan=${VLAN} gw=${GATEWAY} cidr=${CIDR}"

    local veth_h veth_n nchain_pr nchain_post fchain
    veth_h=$(veth_host_name "${VLAN}" "${CHOSEN_ID}")
    veth_n=$(veth_ns_name   "${VLAN}" "${CHOSEN_ID}")
    nchain_pr="${CHAIN_PREFIX}_${NETWORK_ID}_PR"
    nchain_post="${CHAIN_PREFIX}_${NETWORK_ID}_POST"
    fchain=$(filter_chain "${NETWORK_ID}")

    # ---- 1. Create namespace ----
    if ! ip netns list 2>/dev/null | grep -q "^${NAMESPACE}\b"; then
        ip netns add "${NAMESPACE}"
        log "Created namespace ${NAMESPACE}"
    fi
    ip netns exec "${NAMESPACE}" ip link set lo up 2>/dev/null || true

    # ---- 2. Host bridge + VLAN sub-interface ----
    if [ -n "${VLAN}" ]; then
        ensure_host_bridge "${GUEST_ETH}" "${VLAN}"
        local br
        br=$(host_bridge_name "${GUEST_ETH}" "${VLAN}")

        # ---- 3. Guest veth pair ----
        if ! ip link show "${veth_h}" >/dev/null 2>&1; then
            ip link add "${veth_h}" type veth peer name "${veth_n}"
            ip link set "${veth_n}" netns "${NAMESPACE}"
            ip link set "${veth_h}" master "${br}"
            ip link set "${veth_h}" up
            ip netns exec "${NAMESPACE}" ip link set "${veth_n}" up
            log "Created guest veth ${veth_h} (host→${br}) <-> ${veth_n} (namespace)"
        else
            ip link set "${veth_h}" up 2>/dev/null || true
            ip netns exec "${NAMESPACE}" ip link set "${veth_n}" up 2>/dev/null || true
        fi
    fi

    # ---- 4. Assign gateway IP to namespace veth ----
    if [ -n "${GATEWAY}" ] && [ -n "${CIDR}" ]; then
        local prefix
        prefix=$(echo "${CIDR}" | cut -d'/' -f2)
        ip netns exec "${NAMESPACE}" ip addr show "${veth_n}" 2>/dev/null | \
            grep -q "${GATEWAY}/${prefix}" || \
            ip netns exec "${NAMESPACE}" ip addr add "${GATEWAY}/${prefix}" dev "${veth_n}"
        log "Assigned ${GATEWAY}/${prefix} to ${veth_n} in ${NAMESPACE}"
    fi

    # ---- 5. IP forwarding ----
    ip netns exec "${NAMESPACE}" sysctl -w net.ipv4.ip_forward=1 >/dev/null 2>&1 || true

    # ---- 6. iptables chains ----
    ensure_chain nat    "${nchain_pr}"
    ensure_chain nat    "${nchain_post}"
    ensure_chain filter "${fchain}"
    ensure_jump  nat    PREROUTING  "${nchain_pr}"
    ensure_jump  nat    POSTROUTING "${nchain_post}"
    ensure_jump  filter FORWARD     "${fchain}"

    # Allow forwarding for guest traffic in/out of veth-ns-<vlan>
    ip netns exec "${NAMESPACE}" iptables -t filter \
        -C "${fchain}" -i "${veth_n}" -j ACCEPT 2>/dev/null || \
    ip netns exec "${NAMESPACE}" iptables -t filter \
        -A "${fchain}" -i "${veth_n}" -j ACCEPT

    ip netns exec "${NAMESPACE}" iptables -t filter \
        -C "${fchain}" -o "${veth_n}" -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || \
    ip netns exec "${NAMESPACE}" iptables -t filter \
        -A "${fchain}" -o "${veth_n}" -m state --state RELATED,ESTABLISHED -j ACCEPT

    # ---- 7. Persist state ----
    mkdir -p "${STATE_DIR}/${NETWORK_ID}"
    echo "${VLAN}"              > "${STATE_DIR}/${NETWORK_ID}/vlan"
    echo "${GATEWAY}"           > "${STATE_DIR}/${NETWORK_ID}/gateway"
    echo "${CIDR}"              > "${STATE_DIR}/${NETWORK_ID}/cidr"
    echo "${NAMESPACE}"         > "${STATE_DIR}/${NETWORK_ID}/namespace"

    release_lock
    log "implement: done network=${NETWORK_ID} namespace=${NAMESPACE}"
}

##############################################################################
# Command: shutdown
# Flush iptables chains and remove public veth pairs.
# Keep namespace and guest veth (bridge stays on host for VM traffic).
##############################################################################

cmd_shutdown() {
    parse_args "$@"
    _load_state
    acquire_lock "${NETWORK_ID}"

    log "shutdown: network=${NETWORK_ID} ns=${NAMESPACE}"

    local nchain_pr nchain_post fchain
    nchain_pr="${CHAIN_PREFIX}_${NETWORK_ID}_PR"
    nchain_post="${CHAIN_PREFIX}_${NETWORK_ID}_POST"
    fchain=$(filter_chain "${NETWORK_ID}")

    # Remove iptables chain jumps
    ip netns exec "${NAMESPACE}" iptables -t nat    -D PREROUTING  -j "${nchain_pr}"   2>/dev/null || true
    ip netns exec "${NAMESPACE}" iptables -t nat    -D POSTROUTING -j "${nchain_post}" 2>/dev/null || true
    ip netns exec "${NAMESPACE}" iptables -t filter -D FORWARD     -j "${fchain}"      2>/dev/null || true

    # Flush and delete chains
    ip netns exec "${NAMESPACE}" iptables -t nat    -F "${nchain_pr}"   2>/dev/null || true
    ip netns exec "${NAMESPACE}" iptables -t nat    -X "${nchain_pr}"   2>/dev/null || true
    ip netns exec "${NAMESPACE}" iptables -t nat    -F "${nchain_post}" 2>/dev/null || true
    ip netns exec "${NAMESPACE}" iptables -t nat    -X "${nchain_post}" 2>/dev/null || true
    ip netns exec "${NAMESPACE}" iptables -t filter -F "${fchain}"      2>/dev/null || true
    ip netns exec "${NAMESPACE}" iptables -t filter -X "${fchain}"      2>/dev/null || true

    # Remove public veth pairs (host-side; namespace-side disappears when IP is removed)
    if [ -d "${STATE_DIR}/${NETWORK_ID}/ips" ]; then
        for f in "${STATE_DIR}/${NETWORK_ID}/ips/"*.pvlan; do
            [ -f "${f}" ] || continue
            local pvlan pveth_h
            pvlan=$(cat "${f}")
            pveth_h=$(pub_veth_host_name "${pvlan}" "${CHOSEN_ID}")
            ip link del "${pveth_h}" 2>/dev/null || true
            log "shutdown: removed public veth ${pveth_h}"
        done
    fi

    # Clean transient state
    rm -rf "${STATE_DIR}/${NETWORK_ID}/ips" \
           "${STATE_DIR}/${NETWORK_ID}/static-nat" \
           "${STATE_DIR}/${NETWORK_ID}/port-forward"

    # Stop per-network services (dnsmasq, haproxy, apache2)
    _svc_stop_dnsmasq
    _svc_stop_haproxy
    _svc_stop_apache2

    release_lock
    log "shutdown: done network=${NETWORK_ID}"
}

##############################################################################
# Command: destroy
# Delete namespace entirely and all state.
##############################################################################

cmd_destroy() {
    parse_args "$@"
    _load_state
    acquire_lock "${NETWORK_ID}"

    log "destroy: network=${NETWORK_ID} ns=${NAMESPACE}"

    # Remove guest veth host-side
    local veth_h
    veth_h=$(veth_host_name "${VLAN}" "${CHOSEN_ID}")
    ip link del "${veth_h}" 2>/dev/null || true

    # Remove public veth pairs
    if [ -d "${STATE_DIR}/${NETWORK_ID}/ips" ]; then
        for f in "${STATE_DIR}/${NETWORK_ID}/ips/"*.pvlan; do
            [ -f "${f}" ] || continue
            local pvlan pveth_h
            pvlan=$(cat "${f}")
            pveth_h=$(pub_veth_host_name "${pvlan}" "${CHOSEN_ID}")
            ip link del "${pveth_h}" 2>/dev/null || true
        done
    fi

    # Delete namespace (removes all interfaces inside it)
    if ip netns list 2>/dev/null | grep -q "^${NAMESPACE}\b"; then
        ip netns del "${NAMESPACE}"
        log "Deleted namespace ${NAMESPACE}"
    fi

    # Stop per-network services before removing state
    _svc_stop_dnsmasq
    _svc_stop_haproxy
    _svc_stop_apache2

    rm -rf "${STATE_DIR}/${NETWORK_ID}"

    release_lock
    log "destroy: done network=${NETWORK_ID}"
}

##############################################################################
# Command: assign-ip
#
# Creates a public veth pair for the given public IP/VLAN, assigns the IP
# to the namespace end, and configures source NAT if requested.
##############################################################################

cmd_assign_ip() {
    parse_args "$@"
    _load_state
    acquire_lock "${NETWORK_ID}"

    log "assign-ip: network=${NETWORK_ID} ns=${NAMESPACE} ip=${PUBLIC_IP} source_nat=${SOURCE_NAT}"
    [ -z "${PUBLIC_IP}" ]   && die "Missing --public-ip"
    [ -z "${PUBLIC_VLAN}" ] && die "Missing --public-vlan"

    local pveth_h pveth_n nchain_post fchain
    pveth_h=$(pub_veth_host_name "${PUBLIC_VLAN}" "${CHOSEN_ID}")
    pveth_n=$(pub_veth_ns_name   "${PUBLIC_VLAN}" "${CHOSEN_ID}")
    nchain_post="${CHAIN_PREFIX}_${NETWORK_ID}_POST"
    fchain=$(filter_chain "${NETWORK_ID}")

    # ---- Ensure public host bridge ----
    ensure_host_bridge "${PUB_ETH}" "${PUBLIC_VLAN}"
    local pub_br
    pub_br=$(host_bridge_name "${PUB_ETH}" "${PUBLIC_VLAN}")

    # ---- Create public veth pair (idempotent) ----
    if ! ip link show "${pveth_h}" >/dev/null 2>&1; then
        ip link add "${pveth_h}" type veth peer name "${pveth_n}"
        ip link set "${pveth_n}" netns "${NAMESPACE}"
        ip link set "${pveth_h}" master "${pub_br}"
        ip link set "${pveth_h}" up
        ip netns exec "${NAMESPACE}" ip link set "${pveth_n}" up
        log "Created public veth ${pveth_h} (host→${pub_br}) <-> ${pveth_n} (namespace)"
    else
        ip link set "${pveth_h}" up 2>/dev/null || true
        ip netns exec "${NAMESPACE}" ip link set "${pveth_n}" up 2>/dev/null || true
    fi

    # ---- Assign public IP to namespace end ----
    local ADDR_SPEC
    if [ -n "${PUBLIC_CIDR}" ] && echo "${PUBLIC_CIDR}" | grep -q '/'; then
        local PREFIX
        PREFIX=$(echo "${PUBLIC_CIDR}" | cut -d'/' -f2)
        ADDR_SPEC="${PUBLIC_IP}/${PREFIX}"
    else
        ADDR_SPEC="${PUBLIC_IP}/32"
    fi
    ip netns exec "${NAMESPACE}" ip addr show "${pveth_n}" 2>/dev/null | \
        grep -q "${PUBLIC_IP}/" || \
        ip netns exec "${NAMESPACE}" ip addr add "${ADDR_SPEC}" dev "${pveth_n}"

    # ---- Host route for incoming traffic ----
    ip route show | grep -q "^${PUBLIC_IP}" || \
        ip route add "${PUBLIC_IP}/32" dev "${pveth_h}" 2>/dev/null || true

    # ---- Default route inside namespace toward upstream gateway ----
    if [ -n "${PUBLIC_GATEWAY}" ]; then
        ip netns exec "${NAMESPACE}" ip route replace default \
            via "${PUBLIC_GATEWAY}" dev "${pveth_n}" 2>/dev/null || \
        ip netns exec "${NAMESPACE}" ip route add default \
            via "${PUBLIC_GATEWAY}" dev "${pveth_n}" 2>/dev/null || true
        log "Default route in ${NAMESPACE}: via ${PUBLIC_GATEWAY} dev ${pveth_n}"
    fi

    # ---- Source NAT ----
    if [ "${SOURCE_NAT}" = "true" ] && [ -n "${CIDR}" ]; then
        ip netns exec "${NAMESPACE}" iptables -t nat \
            -C "${nchain_post}" -s "${CIDR}" -o "${pveth_n}" -j SNAT --to-source "${PUBLIC_IP}" 2>/dev/null || \
        ip netns exec "${NAMESPACE}" iptables -t nat \
            -A "${nchain_post}" -s "${CIDR}" -o "${pveth_n}" -j SNAT --to-source "${PUBLIC_IP}"
        ip netns exec "${NAMESPACE}" iptables -t filter \
            -C "${fchain}" -o "${pveth_n}" -s "${CIDR}" -j ACCEPT 2>/dev/null || \
        ip netns exec "${NAMESPACE}" iptables -t filter \
            -A "${fchain}" -o "${pveth_n}" -s "${CIDR}" -j ACCEPT
        log "Source NAT: ${CIDR} -> ${PUBLIC_IP} via ${pveth_n}"
    fi

    # ---- Persist state ----
    mkdir -p "${STATE_DIR}/${NETWORK_ID}/ips"
    echo "${SOURCE_NAT}"  > "${STATE_DIR}/${NETWORK_ID}/ips/${PUBLIC_IP}"
    # Save public VLAN so add-static-nat / add-port-forward can look it up
    echo "${PUBLIC_VLAN}" > "${STATE_DIR}/${NETWORK_ID}/ips/${PUBLIC_IP}.pvlan"

    release_lock
    log "assign-ip: done ${PUBLIC_IP} on network ${NETWORK_ID}"
}

##############################################################################
# Command: release-ip
##############################################################################

cmd_release_ip() {
    parse_args "$@"
    _load_state
    acquire_lock "${NETWORK_ID}"

    log "release-ip: network=${NETWORK_ID} ns=${NAMESPACE} ip=${PUBLIC_IP}"
    [ -z "${PUBLIC_IP}" ] && die "Missing --public-ip"

    # Restore PUBLIC_VLAN from state if not on CLI
    if [ -z "${PUBLIC_VLAN}" ] && [ -f "${STATE_DIR}/${NETWORK_ID}/ips/${PUBLIC_IP}.pvlan" ]; then
        PUBLIC_VLAN=$(cat "${STATE_DIR}/${NETWORK_ID}/ips/${PUBLIC_IP}.pvlan")
    fi
    [ -z "${PUBLIC_VLAN}" ] && die "release-ip: cannot determine public VLAN for ${PUBLIC_IP}"

    local pveth_h pveth_n nchain_post fchain
    pveth_h=$(pub_veth_host_name "${PUBLIC_VLAN}" "${CHOSEN_ID}")
    pveth_n=$(pub_veth_ns_name   "${PUBLIC_VLAN}" "${CHOSEN_ID}")
    nchain_post="${CHAIN_PREFIX}_${NETWORK_ID}_POST"
    fchain=$(filter_chain "${NETWORK_ID}")

    # Remove SNAT rule
    if [ -n "${CIDR}" ]; then
        ip netns exec "${NAMESPACE}" iptables -t nat \
            -D "${nchain_post}" -s "${CIDR}" -o "${pveth_n}" -j SNAT --to-source "${PUBLIC_IP}" 2>/dev/null || true
        ip netns exec "${NAMESPACE}" iptables -t filter \
            -D "${fchain}" -o "${pveth_n}" -s "${CIDR}" -j ACCEPT 2>/dev/null || true
    fi

    # Remove DNAT rules for this public IP
    local nchain_pr
    nchain_pr="${CHAIN_PREFIX}_${NETWORK_ID}_PR"
    ip netns exec "${NAMESPACE}" iptables -t nat -S "${nchain_pr}" 2>/dev/null | \
        grep -- "-d ${PUBLIC_IP}" | \
        while read -r rule; do
            ip netns exec "${NAMESPACE}" iptables -t nat \
                -D "${nchain_pr}" ${rule#-A ${nchain_pr}} 2>/dev/null || true
        done

    # Remove host route
    ip route del "${PUBLIC_IP}/32" 2>/dev/null || true

    # Remove IP from namespace veth
    if [ -n "${PUBLIC_CIDR}" ] && echo "${PUBLIC_CIDR}" | grep -q '/'; then
        local PREFIX
        PREFIX=$(echo "${PUBLIC_CIDR}" | cut -d'/' -f2)
        ip netns exec "${NAMESPACE}" ip addr del "${PUBLIC_IP}/${PREFIX}" dev "${pveth_n}" 2>/dev/null || true
    fi
    ip netns exec "${NAMESPACE}" ip addr del "${PUBLIC_IP}/32" dev "${pveth_n}" 2>/dev/null || true

    # Delete public veth if no other IPs share the same public VLAN + network id
    local remaining
    remaining=$(find "${STATE_DIR}/${NETWORK_ID}/ips/" -name "*.pvlan" \
        ! -name "${PUBLIC_IP}.pvlan" -exec grep -l "^${PUBLIC_VLAN}$" {} \; 2>/dev/null | wc -l)
    if [ "${remaining}" -eq 0 ]; then
        ip link del "${pveth_h}" 2>/dev/null || true
        log "release-ip: removed public veth ${pveth_h}"
    fi

    # Remove default route if no IPs remain
    if [ -n "${PUBLIC_GATEWAY}" ]; then
        local total_ips
        total_ips=$(find "${STATE_DIR}/${NETWORK_ID}/ips/" -maxdepth 1 \
            -not -name "*.pvlan" -type f 2>/dev/null | wc -l)
        if [ "${total_ips}" -le 1 ]; then
            ip netns exec "${NAMESPACE}" ip route del default \
                via "${PUBLIC_GATEWAY}" dev "${pveth_n}" 2>/dev/null || true
        fi
    fi

    rm -f "${STATE_DIR}/${NETWORK_ID}/ips/${PUBLIC_IP}" \
          "${STATE_DIR}/${NETWORK_ID}/ips/${PUBLIC_IP}.pvlan"

    release_lock
    log "release-ip: done ${PUBLIC_IP} on network ${NETWORK_ID}"
}

##############################################################################
# Command: add-static-nat
##############################################################################

cmd_add_static_nat() {
    parse_args "$@"
    _load_state
    acquire_lock "${NETWORK_ID}"

    log "add-static-nat: network=${NETWORK_ID} ns=${NAMESPACE} ${PUBLIC_IP} <-> ${PRIVATE_IP}"
    [ -z "${PUBLIC_IP}" ]  && die "Missing --public-ip"
    [ -z "${PRIVATE_IP}" ] && die "Missing --private-ip"

    # Restore PUBLIC_VLAN from state (written by assign-ip)
    if [ -z "${PUBLIC_VLAN}" ] && [ -f "${STATE_DIR}/${NETWORK_ID}/ips/${PUBLIC_IP}.pvlan" ]; then
        PUBLIC_VLAN=$(cat "${STATE_DIR}/${NETWORK_ID}/ips/${PUBLIC_IP}.pvlan")
    fi

    local pveth_h pveth_n veth_n nchain_pr nchain_post fchain
    pveth_h=$(pub_veth_host_name "${PUBLIC_VLAN:-0}" "${CHOSEN_ID}")
    pveth_n=$(pub_veth_ns_name   "${PUBLIC_VLAN:-0}" "${CHOSEN_ID}")
    veth_n=$(veth_ns_name "${VLAN}" "${CHOSEN_ID}")
    nchain_pr="${CHAIN_PREFIX}_${NETWORK_ID}_PR"
    nchain_post="${CHAIN_PREFIX}_${NETWORK_ID}_POST"
    fchain=$(filter_chain "${NETWORK_ID}")

    # Idempotent: ensure public IP is on the namespace veth and host route exists
    ip netns exec "${NAMESPACE}" ip addr show "${pveth_n}" 2>/dev/null | \
        grep -q "${PUBLIC_IP}/32" || \
        ip netns exec "${NAMESPACE}" ip addr add "${PUBLIC_IP}/32" dev "${pveth_n}" 2>/dev/null || true
    ip route show | grep -q "^${PUBLIC_IP}" || \
        ip route add "${PUBLIC_IP}/32" dev "${pveth_h}" 2>/dev/null || true

    # DNAT: inbound public IP → private IP (PREROUTING)
    ip netns exec "${NAMESPACE}" iptables -t nat \
        -C "${nchain_pr}" -d "${PUBLIC_IP}" -j DNAT --to-destination "${PRIVATE_IP}" 2>/dev/null || \
    ip netns exec "${NAMESPACE}" iptables -t nat \
        -A "${nchain_pr}" -d "${PUBLIC_IP}" -j DNAT --to-destination "${PRIVATE_IP}"

    # SNAT: outbound private IP → public IP (POSTROUTING, out public veth)
    ip netns exec "${NAMESPACE}" iptables -t nat \
        -C "${nchain_post}" -s "${PRIVATE_IP}" -o "${pveth_n}" -j SNAT --to-source "${PUBLIC_IP}" 2>/dev/null || \
    ip netns exec "${NAMESPACE}" iptables -t nat \
        -A "${nchain_post}" -s "${PRIVATE_IP}" -o "${pveth_n}" -j SNAT --to-source "${PUBLIC_IP}"

    # FORWARD: allow traffic to/from private IP via guest veth
    ip netns exec "${NAMESPACE}" iptables -t filter \
        -C "${fchain}" -d "${PRIVATE_IP}" -o "${veth_n}" -j ACCEPT 2>/dev/null || \
    ip netns exec "${NAMESPACE}" iptables -t filter \
        -A "${fchain}" -d "${PRIVATE_IP}" -o "${veth_n}" -j ACCEPT
    ip netns exec "${NAMESPACE}" iptables -t filter \
        -C "${fchain}" -s "${PRIVATE_IP}" -i "${veth_n}" -j ACCEPT 2>/dev/null || \
    ip netns exec "${NAMESPACE}" iptables -t filter \
        -A "${fchain}" -s "${PRIVATE_IP}" -i "${veth_n}" -j ACCEPT

    mkdir -p "${STATE_DIR}/${NETWORK_ID}/static-nat"
    echo "${PRIVATE_IP}" > "${STATE_DIR}/${NETWORK_ID}/static-nat/${PUBLIC_IP}"

    release_lock
    log "add-static-nat: done ${PUBLIC_IP} <-> ${PRIVATE_IP} in ${NAMESPACE}"
}

##############################################################################
# Command: delete-static-nat
##############################################################################

cmd_delete_static_nat() {
    parse_args "$@"
    _load_state
    acquire_lock "${NETWORK_ID}"

    log "delete-static-nat: network=${NETWORK_ID} ns=${NAMESPACE} ${PUBLIC_IP}"
    [ -z "${PUBLIC_IP}" ] && die "Missing --public-ip"

    if [ -z "${PRIVATE_IP}" ] && [ -f "${STATE_DIR}/${NETWORK_ID}/static-nat/${PUBLIC_IP}" ]; then
        PRIVATE_IP=$(cat "${STATE_DIR}/${NETWORK_ID}/static-nat/${PUBLIC_IP}")
    fi
    [ -z "${PRIVATE_IP}" ] && die "Missing --private-ip and no saved state"

    # Restore PUBLIC_VLAN from state
    if [ -z "${PUBLIC_VLAN}" ] && [ -f "${STATE_DIR}/${NETWORK_ID}/ips/${PUBLIC_IP}.pvlan" ]; then
        PUBLIC_VLAN=$(cat "${STATE_DIR}/${NETWORK_ID}/ips/${PUBLIC_IP}.pvlan")
    fi

    local pveth_n veth_n nchain_pr nchain_post fchain
    pveth_n=$(pub_veth_ns_name "${PUBLIC_VLAN:-0}" "${CHOSEN_ID}")
    veth_n=$(veth_ns_name "${VLAN}" "${CHOSEN_ID}")
    nchain_pr="${CHAIN_PREFIX}_${NETWORK_ID}_PR"
    nchain_post="${CHAIN_PREFIX}_${NETWORK_ID}_POST"
    fchain=$(filter_chain "${NETWORK_ID}")

    ip netns exec "${NAMESPACE}" iptables -t nat \
        -D "${nchain_pr}" -d "${PUBLIC_IP}" -j DNAT --to-destination "${PRIVATE_IP}" 2>/dev/null || true
    ip netns exec "${NAMESPACE}" iptables -t nat \
        -D "${nchain_post}" -s "${PRIVATE_IP}" -o "${pveth_n}" -j SNAT --to-source "${PUBLIC_IP}" 2>/dev/null || true
    ip netns exec "${NAMESPACE}" iptables -t filter \
        -D "${fchain}" -d "${PRIVATE_IP}" -o "${veth_n}" -j ACCEPT 2>/dev/null || true
    ip netns exec "${NAMESPACE}" iptables -t filter \
        -D "${fchain}" -s "${PRIVATE_IP}" -i "${veth_n}" -j ACCEPT 2>/dev/null || true

    rm -f "${STATE_DIR}/${NETWORK_ID}/static-nat/${PUBLIC_IP}"

    release_lock
    log "delete-static-nat: done ${PUBLIC_IP} <-> ${PRIVATE_IP}"
}

##############################################################################
# Command: add-port-forward
##############################################################################

cmd_add_port_forward() {
    parse_args "$@"
    _load_state
    acquire_lock "${NETWORK_ID}"

    log "add-port-forward: network=${NETWORK_ID} ns=${NAMESPACE} ${PUBLIC_IP}:${PUBLIC_PORT} -> ${PRIVATE_IP}:${PRIVATE_PORT} (${PROTOCOL})"
    [ -z "${PUBLIC_IP}" ]    && die "Missing --public-ip"
    [ -z "${PUBLIC_PORT}" ]  && die "Missing --public-port"
    [ -z "${PRIVATE_IP}" ]   && die "Missing --private-ip"
    [ -z "${PRIVATE_PORT}" ] && die "Missing --private-port"
    [ -z "${PROTOCOL}" ]     && PROTOCOL="tcp"

    # Restore PUBLIC_VLAN from state
    if [ -z "${PUBLIC_VLAN}" ] && [ -f "${STATE_DIR}/${NETWORK_ID}/ips/${PUBLIC_IP}.pvlan" ]; then
        PUBLIC_VLAN=$(cat "${STATE_DIR}/${NETWORK_ID}/ips/${PUBLIC_IP}.pvlan")
    fi

    local pveth_h pveth_n veth_n nchain_pr fchain
    pveth_h=$(pub_veth_host_name "${PUBLIC_VLAN:-0}" "${CHOSEN_ID}")
    pveth_n=$(pub_veth_ns_name   "${PUBLIC_VLAN:-0}" "${CHOSEN_ID}")
    veth_n=$(veth_ns_name "${VLAN}" "${CHOSEN_ID}")
    nchain_pr="${CHAIN_PREFIX}_${NETWORK_ID}_PR"
    fchain=$(filter_chain "${NETWORK_ID}")

    # Idempotent: ensure public IP and host route
    ip netns exec "${NAMESPACE}" ip addr show "${pveth_n}" 2>/dev/null | \
        grep -q "${PUBLIC_IP}/32" || \
        ip netns exec "${NAMESPACE}" ip addr add "${PUBLIC_IP}/32" dev "${pveth_n}" 2>/dev/null || true
    ip route show | grep -q "^${PUBLIC_IP}" || \
        ip route add "${PUBLIC_IP}/32" dev "${pveth_h}" 2>/dev/null || true

    # DNAT
    ip netns exec "${NAMESPACE}" iptables -t nat \
        -C "${nchain_pr}" -p "${PROTOCOL}" -d "${PUBLIC_IP}" --dport "${PUBLIC_PORT}" \
        -j DNAT --to-destination "${PRIVATE_IP}:${PRIVATE_PORT}" 2>/dev/null || \
    ip netns exec "${NAMESPACE}" iptables -t nat \
        -A "${nchain_pr}" -p "${PROTOCOL}" -d "${PUBLIC_IP}" --dport "${PUBLIC_PORT}" \
        -j DNAT --to-destination "${PRIVATE_IP}:${PRIVATE_PORT}"

    # Allow forwarding for the mapped private port via guest veth
    ip netns exec "${NAMESPACE}" iptables -t filter \
        -C "${fchain}" -p "${PROTOCOL}" -d "${PRIVATE_IP}" --dport "${PRIVATE_PORT}" \
        -o "${veth_n}" -j ACCEPT 2>/dev/null || \
    ip netns exec "${NAMESPACE}" iptables -t filter \
        -A "${fchain}" -p "${PROTOCOL}" -d "${PRIVATE_IP}" --dport "${PRIVATE_PORT}" \
        -o "${veth_n}" -j ACCEPT

    local safe_port
    safe_port=$(echo "${PUBLIC_PORT}" | tr ':' '-')
    mkdir -p "${STATE_DIR}/${NETWORK_ID}/port-forward"
    echo "${PROTOCOL} ${PUBLIC_IP} ${PUBLIC_PORT} ${PRIVATE_IP} ${PRIVATE_PORT}" > \
        "${STATE_DIR}/${NETWORK_ID}/port-forward/${PROTOCOL}_${PUBLIC_IP}_${safe_port}"

    release_lock
    log "add-port-forward: done ${PUBLIC_IP}:${PUBLIC_PORT} -> ${PRIVATE_IP}:${PRIVATE_PORT}"
}

##############################################################################
# Command: delete-port-forward
##############################################################################

cmd_delete_port_forward() {
    parse_args "$@"
    _load_state
    acquire_lock "${NETWORK_ID}"

    log "delete-port-forward: network=${NETWORK_ID} ns=${NAMESPACE} ${PUBLIC_IP}:${PUBLIC_PORT} -> ${PRIVATE_IP}:${PRIVATE_PORT}"
    [ -z "${PUBLIC_IP}" ]    && die "Missing --public-ip"
    [ -z "${PUBLIC_PORT}" ]  && die "Missing --public-port"
    [ -z "${PRIVATE_IP}" ]   && die "Missing --private-ip"
    [ -z "${PRIVATE_PORT}" ] && die "Missing --private-port"
    [ -z "${PROTOCOL}" ]     && PROTOCOL="tcp"

    local veth_n nchain_pr fchain
    veth_n=$(veth_ns_name "${VLAN}" "${CHOSEN_ID}")
    nchain_pr="${CHAIN_PREFIX}_${NETWORK_ID}_PR"
    fchain=$(filter_chain "${NETWORK_ID}")

    ip netns exec "${NAMESPACE}" iptables -t nat \
        -D "${nchain_pr}" -p "${PROTOCOL}" -d "${PUBLIC_IP}" --dport "${PUBLIC_PORT}" \
        -j DNAT --to-destination "${PRIVATE_IP}:${PRIVATE_PORT}" 2>/dev/null || true
    ip netns exec "${NAMESPACE}" iptables -t filter \
        -D "${fchain}" -p "${PROTOCOL}" -d "${PRIVATE_IP}" --dport "${PRIVATE_PORT}" \
        -o "${veth_n}" -j ACCEPT 2>/dev/null || true

    local safe_port
    safe_port=$(echo "${PUBLIC_PORT}" | tr ':' '-')
    rm -f "${STATE_DIR}/${NETWORK_ID}/port-forward/${PROTOCOL}_${PUBLIC_IP}_${safe_port}"

    release_lock
    log "delete-port-forward: done"
}

##############################################################################
# Helpers: path accessors  (require NETWORK_ID to be set)
##############################################################################

_dnsmasq_dir()        { echo "${STATE_DIR}/${NETWORK_ID}/dnsmasq"; }
_dnsmasq_conf()       { echo "${STATE_DIR}/${NETWORK_ID}/dnsmasq/dnsmasq.conf"; }
_dnsmasq_pid()        { echo "${STATE_DIR}/${NETWORK_ID}/dnsmasq/dnsmasq.pid"; }
_dnsmasq_hosts()      { echo "${STATE_DIR}/${NETWORK_ID}/dnsmasq/hosts"; }
_dnsmasq_dhcp_hosts() { echo "${STATE_DIR}/${NETWORK_ID}/dnsmasq/dhcp-hosts"; }
_dnsmasq_dhcp_opts()  { echo "${STATE_DIR}/${NETWORK_ID}/dnsmasq/dhcp-opts"; }

_haproxy_dir()  { echo "${STATE_DIR}/${NETWORK_ID}/haproxy"; }
_haproxy_conf() { echo "${STATE_DIR}/${NETWORK_ID}/haproxy/haproxy.cfg"; }
_haproxy_pid()  { echo "${STATE_DIR}/${NETWORK_ID}/haproxy/haproxy.pid"; }
_haproxy_sock() { echo "${STATE_DIR}/${NETWORK_ID}/haproxy/haproxy.sock"; }

_apache2_dir()  { echo "${STATE_DIR}/${NETWORK_ID}/apache2"; }
_apache2_conf() { echo "${STATE_DIR}/${NETWORK_ID}/apache2/apache2.conf"; }
_apache2_pid()  { echo "${STATE_DIR}/${NETWORK_ID}/apache2/apache2.pid"; }
_metadata_dir() { echo "${STATE_DIR}/${NETWORK_ID}/metadata"; }
_apache2_cgi()  { echo "${STATE_DIR}/${NETWORK_ID}/apache2/metadata.cgi"; }

##############################################################################
# Helpers: binary detection
##############################################################################

_find_apache2_bin() {
    for bin in apache2 httpd /usr/sbin/apache2 /usr/sbin/httpd; do
        command -v "${bin}" >/dev/null 2>&1 && echo "${bin}" && return
    done
    echo "apache2"
}

_find_apache2_modules_dir() {
    for d in /usr/lib/apache2/modules /usr/lib64/apache2/modules \
              /usr/libexec/apache2   /usr/lib/httpd/modules \
              /usr/libexec/httpd; do
        [ -d "${d}" ] && echo "${d}" && return
    done
    echo "/usr/lib/apache2/modules"
}

_apache2_user() {
    id www-data >/dev/null 2>&1 && echo "www-data" && return
    id apache    >/dev/null 2>&1 && echo "apache"   && return
    echo "nobody"
}

##############################################################################
# Helpers: dnsmasq  (DHCP + DNS via the same process)
##############################################################################

# _cidr_dhcp_range <cidr> <gateway>  → "<start>,<end>,<netmask>"
_cidr_dhcp_range() {
    python3 - "$1" "$2" 2>/dev/null << 'PYEOF'
import ipaddress, sys
try:
    net = ipaddress.ip_network(sys.argv[1], strict=False)
    gw  = ipaddress.ip_address(sys.argv[2])
    hosts = [h for h in net.hosts() if h != gw]
    if hosts:
        print(f"{hosts[0]},{hosts[-1]},{net.netmask}")
    else:
        print(f"{net.network_address+1},{net.broadcast_address-1},{net.netmask}")
except Exception:
    print(",,")
PYEOF
}

# _write_dnsmasq_conf <dns_enabled: true|false>
# Requires: NETWORK_ID, VLAN, CHOSEN_ID, CIDR, GATEWAY, DNS_SERVER
_write_dnsmasq_conf() {
    local dns_enabled="${1:-false}"
    local dir; dir=$(_dnsmasq_dir)
    mkdir -p "${dir}"

    local dhcp_hosts; dhcp_hosts=$(_dnsmasq_dhcp_hosts)
    local hosts;      hosts=$(_dnsmasq_hosts)
    local dhcp_opts;  dhcp_opts=$(_dnsmasq_dhcp_opts)
    touch "${dhcp_hosts}" "${hosts}" "${dhcp_opts}"

    local veth_n; veth_n=$(veth_ns_name "${VLAN}" "${CHOSEN_ID}")
    local dhcp_range; dhcp_range=$(_cidr_dhcp_range "${CIDR}" "${GATEWAY}")
    local port_line="port=0"
    [ "${dns_enabled}" = "true" ] && port_line="port=53"

    cat > "$(_dnsmasq_conf)" << EOF
# Auto-generated by network-extension-wrapper.sh — do not edit
${port_line}
interface=${veth_n}
no-hosts
bind-interfaces
pid-file=$(_dnsmasq_pid)
dhcp-range=${dhcp_range},12h
dhcp-option=3,${GATEWAY}
dhcp-hostsfile=${dhcp_hosts}
addn-hosts=${hosts}
dhcp-optsfile=${dhcp_opts}
log-facility=/var/log/cloudstack/network-extension-dnsmasq-${NETWORK_ID}.log
EOF
    [ -n "${DNS_SERVER}" ] && echo "dhcp-option=6,${DNS_SERVER}" >> "$(_dnsmasq_conf)"
    log "dnsmasq: wrote config $(_dnsmasq_conf) (dns_enabled=${dns_enabled})"
}

_svc_start_or_reload_dnsmasq() {
    local pid_f; pid_f=$(_dnsmasq_pid)
    if [ -f "${pid_f}" ] && kill -0 "$(cat "${pid_f}")" 2>/dev/null; then
        log "dnsmasq: sending SIGHUP to reload (pid=$(cat "${pid_f}"))"
        ip netns exec "${NAMESPACE}" kill -HUP "$(cat "${pid_f}")" 2>/dev/null || true
    else
        log "dnsmasq: starting in namespace ${NAMESPACE}"
        if ! ip netns exec "${NAMESPACE}" dnsmasq --conf-file="$(_dnsmasq_conf)"; then
            log "WARNING: dnsmasq start failed — is dnsmasq installed on this host?"
        fi
    fi
}

_svc_stop_dnsmasq() {
    local pid_f; pid_f=$(_dnsmasq_pid)
    if [ -f "${pid_f}" ]; then
        local pid; pid=$(cat "${pid_f}")
        kill "${pid}" 2>/dev/null || true
        rm -f "${pid_f}"
        log "dnsmasq: stopped (pid=${pid})"
    fi
    # Kill any orphaned dnsmasq for this network
    pkill -f "dnsmasq.*${NETWORK_ID}" 2>/dev/null || true
}

##############################################################################
# Helpers: haproxy  (LB via haproxy)
##############################################################################

# Regenerate haproxy config from all persisted per-rule JSON files.
# Requires: NETWORK_ID
_write_haproxy_conf() {
    local lb_dir; lb_dir=$(_haproxy_dir)
    local pid_f;  pid_f=$(_haproxy_pid)
    local sock_f; sock_f=$(_haproxy_sock)
    mkdir -p "${lb_dir}"

    python3 - "${lb_dir}" "${pid_f}" "${sock_f}" > "$(_haproxy_conf)" 2>/dev/null << 'PYEOF'
import json, os, sys

lb_dir   = sys.argv[1]
pid_file = sys.argv[2]
sock     = sys.argv[3]

rules = []
if os.path.isdir(lb_dir):
    for fn in sorted(os.listdir(lb_dir)):
        if fn.endswith('.json'):
            try:
                with open(os.path.join(lb_dir, fn)) as f:
                    r = json.load(f)
                if not r.get('revoke', False):
                    rules.append(r)
            except Exception:
                pass

lines = [
    "global",
    "    daemon",
    "    maxconn 4096",
    "    log /dev/log local0",
    f"    stats socket {sock} mode 660 level admin",
    f"    pidfile {pid_file}",
    "",
    "defaults",
    "    mode tcp",
    "    timeout connect 5s",
    "    timeout client  50s",
    "    timeout server  50s",
    "    log global",
    "",
]

ALG_MAP = {
    'roundrobin': 'roundrobin', 'leastconn': 'leastconn',
    'source': 'source', 'static-rr': 'static-rr',
    'least_conn': 'leastconn',
}

for rule in rules:
    rid      = rule['id']
    pub_ip   = rule.get('publicIp', '')
    pub_port = rule.get('publicPort', 0)
    alg      = ALG_MAP.get(rule.get('algorithm', '').lower(), 'roundrobin')
    backends = [b for b in rule.get('backends', []) if not b.get('revoked', False)]
    if not backends:
        continue
    lines += [
        f"frontend cs_lb_{rid}_front",
        f"    bind {pub_ip}:{pub_port}",
        f"    default_backend cs_lb_{rid}_back",
        "",
        f"backend cs_lb_{rid}_back",
        f"    balance {alg}",
    ]
    for i, b in enumerate(backends):
        bip   = b.get('ip', '')
        bport = b.get('port', pub_port)
        lines.append(f"    server backend_{rid}_{i} {bip}:{bport} check")
    lines.append("")

print('\n'.join(lines))
PYEOF
    log "haproxy: wrote config $(_haproxy_conf)"
}

_svc_reload_haproxy() {
    local conf_f; conf_f=$(_haproxy_conf)
    local pid_f;  pid_f=$(_haproxy_pid)

    if [ -f "${pid_f}" ] && kill -0 "$(cat "${pid_f}")" 2>/dev/null; then
        log "haproxy: reloading"
        if ! ip netns exec "${NAMESPACE}" haproxy -f "${conf_f}" -p "${pid_f}" \
                -sf "$(cat "${pid_f}")" 2>/dev/null; then
            log "WARNING: haproxy reload failed"
        fi
    else
        log "haproxy: starting in namespace ${NAMESPACE}"
        if ! ip netns exec "${NAMESPACE}" haproxy -f "${conf_f}" -p "${pid_f}" 2>/dev/null; then
            log "WARNING: haproxy start failed — is haproxy installed on this host?"
        fi
    fi
}

_svc_stop_haproxy() {
    local pid_f; pid_f=$(_haproxy_pid)
    if [ -f "${pid_f}" ]; then
        local pid; pid=$(cat "${pid_f}")
        ip netns exec "${NAMESPACE}" kill "${pid}" 2>/dev/null || kill "${pid}" 2>/dev/null || true
        rm -f "${pid_f}"
        log "haproxy: stopped (pid=${pid})"
    fi
}

##############################################################################
# Helpers: apache2  (userdata / metadata HTTP service)
#
# apache2 runs inside the namespace, listening on <GATEWAY>:80.
# An iptables DNAT rule inside the namespace redirects requests destined for
# 169.254.169.254:80 to <GATEWAY>:80 so VMs can use the standard metadata URL.
#
# Files served:
#   ${STATE_DIR}/<NETWORK_ID>/metadata/<VM_IP>/latest/user-data
#   ${STATE_DIR}/<NETWORK_ID>/metadata/<VM_IP>/latest/password
#   ${STATE_DIR}/<NETWORK_ID>/metadata/<VM_IP>/latest/meta-data/public-keys/0/openssh-key
#   ${STATE_DIR}/<NETWORK_ID>/metadata/<VM_IP>/latest/meta-data/hypervisor-hostname
#   ${STATE_DIR}/<NETWORK_ID>/metadata/<VM_IP>/latest/meta-data/local-hostname
#
# Apache2 uses a CGI script to dispatch requests to the per-IP subtree.
##############################################################################

_write_apache2_conf() {
    local dir;  dir=$(_apache2_dir)
    local www;  www=$(_metadata_dir)
    local cgi;  cgi=$(_apache2_cgi)
    local mods; mods=$(_find_apache2_modules_dir)
    local apuser; apuser=$(_apache2_user)
    mkdir -p "${dir}" "${www}"

    # ---- CGI dispatcher script ----
    cat > "${cgi}" << 'CGISCRIPT'
#!/bin/bash
CLIENT="${REMOTE_ADDR}"
BASEDIR="$(dirname "$0")/../metadata"
REQ="${PATH_INFO:-${REQUEST_URI}}"
REQ="${REQ#/}"
FILE="${BASEDIR}/${CLIENT}/${REQ}"
if [ -f "${FILE}" ]; then
    printf 'Content-Type: text/plain\r\n\r\n'
    cat "${FILE}"
else
    printf 'Status: 404 Not Found\r\nContent-Type: text/plain\r\n\r\nNot found\n'
fi
CGISCRIPT
    chmod +x "${cgi}"

    # ---- Detect MPM module ----
    local mpm_mod="mpm_event_module"
    local mpm_so="mod_mpm_event.so"
    if [ ! -f "${mods}/${mpm_so}" ] && [ -f "${mods}/mod_mpm_prefork.so" ]; then
        mpm_mod="mpm_prefork_module"; mpm_so="mod_mpm_prefork.so"
    fi

    # ---- Check for authz_core (required in apache2 >= 2.4) ----
    local authz_line=""
    [ -f "${mods}/mod_authz_core.so" ] && \
        authz_line="LoadModule authz_core_module ${mods}/mod_authz_core.so"

    local unixd_line=""
    [ -f "${mods}/mod_unixd.so" ] && \
        unixd_line="LoadModule unixd_module ${mods}/mod_unixd.so"

    local require_line="Allow from all"
    [ -f "${mods}/mod_authz_core.so" ] && require_line="Require all granted"

    cat > "$(_apache2_conf)" << EOF
# Auto-generated by network-extension-wrapper.sh — do not edit
ServerRoot /tmp
PidFile $(_apache2_pid)
ServerName metadata-${NETWORK_ID}
Listen ${GATEWAY}:80
User ${apuser}
Group ${apuser}

LoadModule ${mpm_mod} ${mods}/${mpm_so}
LoadModule cgi_module ${mods}/mod_cgi.so
LoadModule alias_module ${mods}/mod_alias.so
${unixd_line}
${authz_line}

DocumentRoot ${www}
ErrorLog /var/log/cloudstack/network-extension-apache2-${NETWORK_ID}.log

<VirtualHost ${GATEWAY}:80>
    ServerName metadata
    ScriptAlias / ${cgi}/
    <Directory ${dir}>
        Options +ExecCGI
        AllowOverride None
        ${require_line}
    </Directory>
</VirtualHost>
EOF
    log "apache2: wrote config $(_apache2_conf)"
}

_svc_start_or_reload_apache2() {
    local bin; bin=$(_find_apache2_bin)
    local pid_f; pid_f=$(_apache2_pid)

    if [ -f "${pid_f}" ] && kill -0 "$(cat "${pid_f}")" 2>/dev/null; then
        log "apache2: graceful restart (pid=$(cat "${pid_f}"))"
        ip netns exec "${NAMESPACE}" "${bin}" -f "$(_apache2_conf)" -k graceful 2>/dev/null || \
            log "WARNING: apache2 graceful restart failed"
    else
        log "apache2: starting in namespace ${NAMESPACE}"
        if ! ip netns exec "${NAMESPACE}" "${bin}" -f "$(_apache2_conf)" -k start 2>/dev/null; then
            log "WARNING: apache2 start failed — is apache2/httpd installed on this host?"
        fi
    fi

    # DNAT 169.254.169.254:80 → GATEWAY:80  (idempotent)
    ip netns exec "${NAMESPACE}" iptables -t nat \
        -C PREROUTING -d 169.254.169.254/32 -p tcp --dport 80 \
        -j DNAT --to-destination "${GATEWAY}:80" 2>/dev/null || \
    ip netns exec "${NAMESPACE}" iptables -t nat \
        -A PREROUTING -d 169.254.169.254/32 -p tcp --dport 80 \
        -j DNAT --to-destination "${GATEWAY}:80"

    # Allow metadata traffic inbound to the namespace (INPUT)
    ip netns exec "${NAMESPACE}" iptables -t filter \
        -C INPUT -p tcp --dport 80 -j ACCEPT 2>/dev/null || \
    ip netns exec "${NAMESPACE}" iptables -t filter \
        -A INPUT -p tcp --dport 80 -j ACCEPT
}

_svc_stop_apache2() {
    local bin; bin=$(_find_apache2_bin)
    local pid_f; pid_f=$(_apache2_pid)

    if [ -f "${pid_f}" ] && kill -0 "$(cat "${pid_f}")" 2>/dev/null; then
        local pid; pid=$(cat "${pid_f}")
        ip netns exec "${NAMESPACE}" "${bin}" -f "$(_apache2_conf)" -k stop 2>/dev/null || \
            kill "${pid}" 2>/dev/null || true
        rm -f "${pid_f}"
        log "apache2: stopped (pid=${pid})"
    fi
}

##############################################################################
# Command: config-dhcp-subnet
# Configure dnsmasq for DHCP (DNS disabled at port 53).
##############################################################################

cmd_config_dhcp_subnet() {
    parse_args "$@"
    _load_state
    acquire_lock "${NETWORK_ID}"
    log "config-dhcp-subnet: network=${NETWORK_ID} ns=${NAMESPACE} gw=${GATEWAY} cidr=${CIDR}"
    [ -z "${GATEWAY}" ] && die "config-dhcp-subnet: missing --gateway"
    [ -z "${CIDR}" ]    && die "config-dhcp-subnet: missing --cidr"
    _write_dnsmasq_conf false
    _svc_start_or_reload_dnsmasq
    release_lock
    log "config-dhcp-subnet: done network=${NETWORK_ID}"
}

##############################################################################
# Command: config-dns-subnet
# Configure dnsmasq for DNS (also enables DHCP; DNS on port 53).
##############################################################################

cmd_config_dns_subnet() {
    parse_args "$@"
    _load_state
    acquire_lock "${NETWORK_ID}"
    log "config-dns-subnet: network=${NETWORK_ID} ns=${NAMESPACE} gw=${GATEWAY} cidr=${CIDR}"
    [ -z "${GATEWAY}" ] && die "config-dns-subnet: missing --gateway"
    [ -z "${CIDR}" ]    && die "config-dns-subnet: missing --cidr"
    _write_dnsmasq_conf true
    _svc_start_or_reload_dnsmasq
    release_lock
    log "config-dns-subnet: done network=${NETWORK_ID}"
}

##############################################################################
# Command: remove-dhcp-subnet
# Tear down dnsmasq DHCP for this network.
##############################################################################

cmd_remove_dhcp_subnet() {
    parse_args "$@"
    _load_state
    acquire_lock "${NETWORK_ID}"
    log "remove-dhcp-subnet: network=${NETWORK_ID}"
    _svc_stop_dnsmasq
    rm -rf "$(_dnsmasq_dir)"
    release_lock
    log "remove-dhcp-subnet: done network=${NETWORK_ID}"
}

##############################################################################
# Command: remove-dns-subnet
# Disable DNS (port 53) but keep DHCP running if configured.
##############################################################################

cmd_remove_dns_subnet() {
    parse_args "$@"
    _load_state
    acquire_lock "${NETWORK_ID}"
    log "remove-dns-subnet: network=${NETWORK_ID}"
    if [ -f "$(_dnsmasq_conf)" ]; then
        _write_dnsmasq_conf false
        _svc_start_or_reload_dnsmasq
    fi
    release_lock
    log "remove-dns-subnet: done network=${NETWORK_ID}"
}

##############################################################################
# Command: add-dhcp-entry
# Add a static DHCP host entry (mac→ip) to dnsmasq.
##############################################################################

cmd_add_dhcp_entry() {
    parse_args "$@"
    _load_state
    acquire_lock "${NETWORK_ID}"
    log "add-dhcp-entry: network=${NETWORK_ID} mac=${MAC} ip=${VM_IP} hostname=${HOSTNAME}"
    [ -z "${MAC}" ]   && die "add-dhcp-entry: missing --mac"
    [ -z "${VM_IP}" ] && die "add-dhcp-entry: missing --ip"

    local dhcp_hosts; dhcp_hosts=$(_dnsmasq_dhcp_hosts)
    mkdir -p "$(_dnsmasq_dir)"
    touch "${dhcp_hosts}"

    # Remove any existing entry for this MAC
    grep -v "^${MAC}," "${dhcp_hosts}" > "${dhcp_hosts}.tmp" 2>/dev/null || true
    mv "${dhcp_hosts}.tmp" "${dhcp_hosts}"

    if [ -n "${HOSTNAME}" ]; then
        echo "${MAC},${VM_IP},${HOSTNAME},infinite" >> "${dhcp_hosts}"
    else
        echo "${MAC},${VM_IP},infinite" >> "${dhcp_hosts}"
    fi

    _svc_start_or_reload_dnsmasq
    release_lock
    log "add-dhcp-entry: done mac=${MAC} ip=${VM_IP}"
}

##############################################################################
# Command: remove-dhcp-entry
# Remove a static DHCP host entry from dnsmasq.
##############################################################################

cmd_remove_dhcp_entry() {
    parse_args "$@"
    _load_state
    acquire_lock "${NETWORK_ID}"
    log "remove-dhcp-entry: network=${NETWORK_ID} mac=${MAC}"
    [ -z "${MAC}" ] && die "remove-dhcp-entry: missing --mac"

    local dhcp_hosts; dhcp_hosts=$(_dnsmasq_dhcp_hosts)
    if [ -f "${dhcp_hosts}" ]; then
        grep -v "^${MAC}," "${dhcp_hosts}" > "${dhcp_hosts}.tmp" 2>/dev/null || true
        mv "${dhcp_hosts}.tmp" "${dhcp_hosts}"
        _svc_start_or_reload_dnsmasq
    fi
    release_lock
    log "remove-dhcp-entry: done mac=${MAC}"
}

##############################################################################
# Command: set-dhcp-options
# Set extra DHCP options for a NIC (by NIC ID as dnsmasq tag).
##############################################################################

cmd_set_dhcp_options() {
    parse_args "$@"
    _load_state
    acquire_lock "${NETWORK_ID}"
    log "set-dhcp-options: network=${NETWORK_ID} nic=${NIC_ID}"

    local dhcp_opts; dhcp_opts=$(_dnsmasq_dhcp_opts)
    mkdir -p "$(_dnsmasq_dir)"
    touch "${dhcp_opts}"

    # Parse JSON options with Python3 and write to opts file
    python3 - "${NIC_ID}" "${DHCP_OPTIONS_JSON}" "${dhcp_opts}" << 'PYEOF'
import json, sys, os

nic_id   = sys.argv[1]
opts_str = sys.argv[2]
optsfile = sys.argv[3]

try:
    opts = json.loads(opts_str)
except Exception:
    opts = {}

# Read existing lines; drop any previously set for this nic
try:
    with open(optsfile) as f:
        lines = f.readlines()
except FileNotFoundError:
    lines = []

marker = f"# nic:{nic_id}:"
lines = [l for l in lines if not l.startswith(marker)]

for code, value in opts.items():
    lines.append(f"{marker}\ndhcp-option=tag:{nic_id},{code},{value}\n")

with open(optsfile, 'w') as f:
    f.writelines(lines)
PYEOF

    _svc_start_or_reload_dnsmasq
    release_lock
    log "set-dhcp-options: done nic=${NIC_ID}"
}

##############################################################################
# Command: add-dns-entry
# Add a hostname→IP mapping to dnsmasq.
##############################################################################

cmd_add_dns_entry() {
    parse_args "$@"
    _load_state
    acquire_lock "${NETWORK_ID}"
    log "add-dns-entry: network=${NETWORK_ID} hostname=${HOSTNAME} ip=${VM_IP}"
    [ -z "${VM_IP}" ]    && die "add-dns-entry: missing --ip"
    [ -z "${HOSTNAME}" ] && die "add-dns-entry: missing --hostname"

    local hosts; hosts=$(_dnsmasq_hosts)
    mkdir -p "$(_dnsmasq_dir)"
    touch "${hosts}"

    # Remove existing entry for this IP then append fresh
    grep -v "^${VM_IP}[[:space:]]" "${hosts}" > "${hosts}.tmp" 2>/dev/null || true
    mv "${hosts}.tmp" "${hosts}"
    echo "${VM_IP} ${HOSTNAME}" >> "${hosts}"

    _svc_start_or_reload_dnsmasq
    release_lock
    log "add-dns-entry: done ${VM_IP} ${HOSTNAME}"
}

##############################################################################
# Command: remove-dns-entry
# Remove a hostname→IP mapping from dnsmasq.
##############################################################################

cmd_remove_dns_entry() {
    parse_args "$@"
    _load_state
    acquire_lock "${NETWORK_ID}"
    log "remove-dns-entry: network=${NETWORK_ID} ip=${VM_IP}"
    [ -z "${VM_IP}" ] && die "remove-dns-entry: missing --ip"

    local hosts; hosts=$(_dnsmasq_hosts)
    if [ -f "${hosts}" ]; then
        grep -v "^${VM_IP}[[:space:]]" "${hosts}" > "${hosts}.tmp" 2>/dev/null || true
        mv "${hosts}.tmp" "${hosts}"
        _svc_start_or_reload_dnsmasq
    fi
    release_lock
    log "remove-dns-entry: done ${VM_IP}"
}

##############################################################################
# Command: save-userdata
# Write base64-decoded user-data for a VM; start/reload apache2.
##############################################################################

cmd_save_userdata() {
    parse_args "$@"
    _load_state
    acquire_lock "${NETWORK_ID}"
    log "save-userdata: network=${NETWORK_ID} ip=${VM_IP}"
    [ -z "${VM_IP}" ] && die "save-userdata: missing --ip"

    local vm_dir; vm_dir="$(_metadata_dir)/${VM_IP}/latest"
    mkdir -p "${vm_dir}"

    if [ -n "${USERDATA}" ]; then
        printf '%s' "${USERDATA}" | base64 -d > "${vm_dir}/user-data" 2>/dev/null || \
            printf '%s' "${USERDATA}" > "${vm_dir}/user-data"
    fi

    _write_apache2_conf
    _svc_start_or_reload_apache2
    release_lock
    log "save-userdata: done ${VM_IP}"
}

##############################################################################
# Command: save-password
# Write a VM password served via the metadata HTTP service.
##############################################################################

cmd_save_password() {
    parse_args "$@"
    _load_state
    acquire_lock "${NETWORK_ID}"
    log "save-password: network=${NETWORK_ID} ip=${VM_IP}"
    [ -z "${VM_IP}" ] && die "save-password: missing --ip"

    local vm_dir; vm_dir="$(_metadata_dir)/${VM_IP}/latest"
    mkdir -p "${vm_dir}"
    printf '%s' "${PASSWORD}" > "${vm_dir}/password"

    _write_apache2_conf
    _svc_start_or_reload_apache2
    release_lock
    log "save-password: done ${VM_IP}"
}

##############################################################################
# Command: save-sshkey
# Write a base64-encoded SSH public key for a VM.
##############################################################################

cmd_save_sshkey() {
    parse_args "$@"
    _load_state
    acquire_lock "${NETWORK_ID}"
    log "save-sshkey: network=${NETWORK_ID} ip=${VM_IP}"
    [ -z "${VM_IP}" ] && die "save-sshkey: missing --ip"

    local key_dir; key_dir="$(_metadata_dir)/${VM_IP}/latest/meta-data/public-keys/0"
    mkdir -p "${key_dir}"
    # SSH_KEY is base64-encoded by the Java caller
    printf '%s' "${SSH_KEY}" | base64 -d > "${key_dir}/openssh-key" 2>/dev/null || \
        printf '%s' "${SSH_KEY}" > "${key_dir}/openssh-key"

    _write_apache2_conf
    _svc_start_or_reload_apache2
    release_lock
    log "save-sshkey: done ${VM_IP}"
}

##############################################################################
# Command: save-hypervisor-hostname
# Write the hypervisor hostname into the VM's meta-data.
##############################################################################

cmd_save_hypervisor_hostname() {
    parse_args "$@"
    _load_state
    acquire_lock "${NETWORK_ID}"
    log "save-hypervisor-hostname: network=${NETWORK_ID} ip=${VM_IP} host=${HYPERVISOR_HOSTNAME}"
    [ -z "${VM_IP}" ] && die "save-hypervisor-hostname: missing --ip"

    local meta_dir; meta_dir="$(_metadata_dir)/${VM_IP}/latest/meta-data"
    mkdir -p "${meta_dir}"
    printf '%s' "${HYPERVISOR_HOSTNAME}" > "${meta_dir}/hypervisor-hostname"

    _write_apache2_conf
    _svc_start_or_reload_apache2
    release_lock
    log "save-hypervisor-hostname: done ${VM_IP}"
}

##############################################################################
# Command: apply-lb-rules
# Apply/revoke load balancing rules via haproxy inside the namespace.
# --lb-rules <json-array>  — array of LB rule objects (see Java side for schema)
##############################################################################

cmd_apply_lb_rules() {
    parse_args "$@"
    _load_state
    acquire_lock "${NETWORK_ID}"
    log "apply-lb-rules: network=${NETWORK_ID} ns=${NAMESPACE}"

    # Normalise empty input
    [ -z "${LB_RULES_JSON}" ] && LB_RULES_JSON="[]"

    local lb_dir; lb_dir=$(_haproxy_dir)
    mkdir -p "${lb_dir}"

    # Persist/remove per-rule state files
    python3 - "${lb_dir}" "${LB_RULES_JSON}" << 'PYEOF'
import json, os, sys

lb_dir = sys.argv[1]
rules  = json.loads(sys.argv[2])

for rule in rules:
    rid = str(rule['id'])
    fn  = os.path.join(lb_dir, f"{rid}.json")
    if rule.get('revoke', False):
        try:
            os.remove(fn)
        except FileNotFoundError:
            pass
    else:
        with open(fn, 'w') as f:
            json.dump(rule, f)
PYEOF

    # Regenerate haproxy config
    _write_haproxy_conf

    # Count active rules (json files in lb_dir, excluding haproxy.cfg / haproxy.pid / etc.)
    local active_rules
    active_rules=$(find "${lb_dir}" -maxdepth 1 -name '*.json' 2>/dev/null | wc -l)

    if [ "${active_rules}" -gt 0 ]; then
        _svc_reload_haproxy
    else
        log "apply-lb-rules: no active rules; stopping haproxy"
        _svc_stop_haproxy
    fi

    release_lock
    log "apply-lb-rules: done network=${NETWORK_ID}"
}

##############################################################################
# Command: custom-action

cmd_custom_action() {
    NETWORK_ID=""
    VPC_ID=""
    ACTION_NAME=""
    ACTION_PARAMS_JSON="{}"
    while [ $# -gt 0 ]; do
        case "$1" in
            --network-id)    NETWORK_ID="$2";               shift 2 ;;
            --vpc-id)        VPC_ID="$2";                   shift 2 ;;
            --action)        ACTION_NAME="$2";               shift 2 ;;
            --action-params) ACTION_PARAMS_JSON="${2:-{}}";  shift 2 ;;
            --physical-network-extension-details|--network-extension-details)
                             shift 2 ;;
            *)               shift ;;
        esac
    done
    [ -z "${NETWORK_ID}" ]  && die "custom-action: missing --network-id"
    [ -z "${ACTION_NAME}" ] && die "custom-action: missing --action"

    # Set NAMESPACE/CHOSEN_ID similar to parse_args
    if [ -z "${NAMESPACE}" ]; then
        if [ -n "${VPC_ID}" ]; then
            NAMESPACE="cs-net-${VPC_ID}"
        else
            local NS_FROM_DETAILS
            NS_FROM_DETAILS=$(_json_get "${EXTENSION_DETAILS}" "namespace")
            NAMESPACE="${NS_FROM_DETAILS:-cs-net-${NETWORK_ID}}"
        fi
    fi
    CHOSEN_ID="${VPC_ID:-${NETWORK_ID}}"

    _load_state

    log "custom-action: network=${NETWORK_ID} ns=${NAMESPACE} action=${ACTION_NAME} params=${ACTION_PARAMS_JSON}"

    case "${ACTION_NAME}" in
        reboot-device)
            local veth_h veth_n
            veth_h=$(veth_host_name "${VLAN}" "${CHOSEN_ID}")
            veth_n=$(veth_ns_name   "${VLAN}" "${CHOSEN_ID}")
            ip link set "${veth_h}" down 2>/dev/null || true
            ip netns exec "${NAMESPACE}" ip link set "${veth_n}" down 2>/dev/null || true
            sleep 1
            ip link set "${veth_h}" up 2>/dev/null || true
            ip netns exec "${NAMESPACE}" ip link set "${veth_n}" up 2>/dev/null || true
            echo "reboot-device: OK (namespace=${NAMESPACE})"
            ;;
        dump-config)
            echo "=== Namespace: ${NAMESPACE} ==="
            ip netns exec "${NAMESPACE}" ip addr 2>/dev/null || echo "(no namespace)"
            echo "=== Host bridge: $(host_bridge_name "${GUEST_ETH}" "${VLAN}") ==="
            ip link show "$(host_bridge_name "${GUEST_ETH}" "${VLAN}")" 2>/dev/null || echo "(not found)"
            echo "=== NAT table ==="
            ip netns exec "${NAMESPACE}" iptables -t nat    -L -n -v 2>/dev/null || echo "(unavailable)"
            echo "=== FILTER table ==="
            ip netns exec "${NAMESPACE}" iptables -t filter -L -n -v 2>/dev/null || echo "(unavailable)"
            echo "=== State files ==="
            ls -la "${STATE_DIR}/${NETWORK_ID}/" 2>/dev/null || echo "(no state)"
            ;;
        *)
            local hook="${STATE_DIR}/hooks/custom-action-${ACTION_NAME}.sh"
            if [ -x "${hook}" ]; then
                exec "${hook}" --network-id "${NETWORK_ID}" --action "${ACTION_NAME}" \
                     --action-params "${ACTION_PARAMS_JSON}"
            else
                die "Unknown action '${ACTION_NAME}'. Built-ins: reboot-device, dump-config"
            fi
            ;;
    esac
}

##############################################################################
# Main dispatcher
##############################################################################

ensure_dirs

COMMAND="${1:-}"
shift || true

case "${COMMAND}" in
    implement)                cmd_implement                "$@" ;;
    shutdown)                 cmd_shutdown                 "$@" ;;
    destroy)                  cmd_destroy                  "$@" ;;
    assign-ip)                cmd_assign_ip                "$@" ;;
    release-ip)               cmd_release_ip               "$@" ;;
    add-static-nat)           cmd_add_static_nat           "$@" ;;
    delete-static-nat)        cmd_delete_static_nat        "$@" ;;
    add-port-forward)         cmd_add_port_forward         "$@" ;;
    delete-port-forward)      cmd_delete_port_forward      "$@" ;;
    # DHCP / DNS (dnsmasq)
    config-dhcp-subnet)       cmd_config_dhcp_subnet       "$@" ;;
    remove-dhcp-subnet)       cmd_remove_dhcp_subnet       "$@" ;;
    add-dhcp-entry)           cmd_add_dhcp_entry           "$@" ;;
    remove-dhcp-entry)        cmd_remove_dhcp_entry        "$@" ;;
    set-dhcp-options)         cmd_set_dhcp_options         "$@" ;;
    config-dns-subnet)        cmd_config_dns_subnet        "$@" ;;
    remove-dns-subnet)        cmd_remove_dns_subnet        "$@" ;;
    add-dns-entry)            cmd_add_dns_entry            "$@" ;;
    remove-dns-entry)         cmd_remove_dns_entry         "$@" ;;
    # UserData / metadata (apache2)
    save-userdata)            cmd_save_userdata            "$@" ;;
    save-password)            cmd_save_password            "$@" ;;
    save-sshkey)              cmd_save_sshkey              "$@" ;;
    save-hypervisor-hostname) cmd_save_hypervisor_hostname "$@" ;;
    # Load balancing (haproxy)
    apply-lb-rules)           cmd_apply_lb_rules           "$@" ;;
    # Custom actions
    custom-action)            cmd_custom_action            "$@" ;;
    "")
        echo "Usage: $0 {implement|shutdown|destroy|assign-ip|release-ip|" \
             "add-static-nat|delete-static-nat|add-port-forward|delete-port-forward|" \
             "config-dhcp-subnet|remove-dhcp-subnet|add-dhcp-entry|remove-dhcp-entry|set-dhcp-options|" \
             "config-dns-subnet|remove-dns-subnet|add-dns-entry|remove-dns-entry|" \
             "save-userdata|save-password|save-sshkey|save-hypervisor-hostname|" \
             "apply-lb-rules|custom-action} [options]" >&2
        exit 1 ;;
    *)
        echo "Unknown command: ${COMMAND}" >&2
        exit 1 ;;
esac

exit 0

