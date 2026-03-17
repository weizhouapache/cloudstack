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
#   ethX.<vlan>          – VLAN sub-interface on the physical NIC (ethX)
#   br<ethX>-<vlan>      – Linux bridge:  ethX.<vlan> + veth-host-<vlan>
#   veth-host-<vlan>     – host end of the veth pair → in the bridge
#   veth-ns-<vlan>       – namespace end → assigned the network gateway IP
#
# ethX is resolved from the kvmnetworklabel stored in
# --physical-network-extension-details:
#   eth1     → eth1  (already a physical NIC)
#   cloudbr1 → eth1  (find the first non-virtual bridge member)
# by checking /sys/devices/virtual/net/ and /sys/class/net/<br>/brif/.
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
#       <id>     = network or VPC id (from --network-id or vpc-id)
#       pub_ethX = resolved from public_kvmnetworklabel (falls back to ethX)
#
# Interface name lengths (Linux limit: 15 chars)
#   veth-host-<vlan>   max 14 (vlan ≤ 4094) ✓
#   veth-ns-<vlan>     max 12                ✓
#   vph-<pvlan>-<id>   max 14 (pvlan ≤ 4094, id ≤ 9999) ✓
#   vpn-<pvlan>-<id>   max 14                             ✓
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
# Resolve physical NIC from a kvmnetworklabel
#
#   eth1     → eth1     (not in /sys/devices/virtual/net/ → already physical)
#   cloudbr1 → eth1     (virtual bridge → find first non-virtual brif member)
# ---------------------------------------------------------------------------

get_eth_from_label() {
    local label="$1"
    [ -z "${label}" ] && echo "eth0" && return

    # Already a physical NIC?
    if [ ! -d "/sys/devices/virtual/net/${label}" ]; then
        echo "${label}"
        return
    fi

    # Virtual device – check if it is a bridge with physical members
    if [ -d "/sys/class/net/${label}/brif" ]; then
        local member iface
        for member in /sys/class/net/${label}/brif/*; do
            [ -e "${member}" ] || continue
            iface=$(basename "${member}")
            if [ ! -d "/sys/devices/virtual/net/${iface}" ]; then
                echo "${iface}"
                return
            fi
        done
    fi

    # Fallback: return the label itself
    echo "${label}"
}

# Resolve guest-side and public-side physical interfaces
KVM_LABEL_RAW=$(_json_get "${PHYS_DETAILS}" "kvmnetworklabel")
PUB_KVM_LABEL_RAW=$(_json_get "${PHYS_DETAILS}" "public_kvmnetworklabel")

GUEST_ETH=$(get_eth_from_label "${KVM_LABEL_RAW:-eth0}")
PUB_ETH=$(get_eth_from_label "${PUB_KVM_LABEL_RAW:-${KVM_LABEL_RAW:-eth0}}")

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
host_bridge_name() { echo "br${1}-${2}"; }

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

# Generate guest host veth name: vh-<vlan>-<id> (ensure <=15 chars)
veth_host_name() {
    local vlan="$1" id="$2" name short
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
    local vlan="$1" id="$2" name short
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
pub_veth_host_name() { echo "vph-${1}-${2}"; }
pub_veth_ns_name()   { echo "vpn-${1}-${2}"; }

nat_chain()    { echo "${CHAIN_PREFIX}_${1}"; }
filter_chain() { echo "${CHAIN_PREFIX}_FWD_${1}"; }

# ---------------------------------------------------------------------------
# ensure_host_bridge <eth> <vlan>
# Idempotently creates br<eth>-<vlan> with <eth>.<vlan> as a member.
# Prints the bridge name.
# ---------------------------------------------------------------------------

ensure_host_bridge() {
    local eth="$1"
    local vlan="$2"
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
# Command: custom-action
##############################################################################

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
    implement)           cmd_implement           "$@" ;;
    shutdown)            cmd_shutdown            "$@" ;;
    destroy)             cmd_destroy             "$@" ;;
    assign-ip)           cmd_assign_ip           "$@" ;;
    release-ip)          cmd_release_ip          "$@" ;;
    add-static-nat)      cmd_add_static_nat      "$@" ;;
    delete-static-nat)   cmd_delete_static_nat   "$@" ;;
    add-port-forward)    cmd_add_port_forward    "$@" ;;
    delete-port-forward) cmd_delete_port_forward "$@" ;;
    custom-action)       cmd_custom_action       "$@" ;;
    "")
        echo "Usage: $0 {implement|shutdown|destroy|assign-ip|release-ip|add-static-nat|delete-static-nat|add-port-forward|delete-port-forward|custom-action} [options]" >&2
        exit 1 ;;
    *)
        echo "Unknown command: ${COMMAND}" >&2
        exit 1 ;;
esac

exit 0

