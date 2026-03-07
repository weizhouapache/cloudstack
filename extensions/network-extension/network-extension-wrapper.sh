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
# Runs on a KVM host; manages Linux network namespaces, VLAN interfaces,
# bridges, and iptables rules to implement isolated guest networks.
#
# Architecture:
#   - Each CloudStack isolated network gets its own Linux network namespace
#     named "cs-net-<networkId>" (passed via --namespace).
#   - The namespace contains:
#       * A bridge (csbr<networkId>) connected to the VLAN interface for
#         guest VM traffic.
#       * A veth pair (veth-h-<id> on host / veth-n-<id> in namespace)
#         connecting the namespace to the host's shared public bridge
#         (cspublic) for source NAT and public IP access.
#       * iptables rules for source NAT, static NAT, and port forwarding.
#
# Public-IP bridge:
#   A shared bridge "cspublic" on the KVM host carries public IP addresses.
#   Each network namespace connects to it via a veth pair; the namespace-side
#   endpoint is given the public IP address(es) assigned to the network.
#
# Usage:
#   network-extension-wrapper.sh <command> [options]
#
# Commands:
#   implement          - Create namespace, internal bridge, veth pair, iptables
#   shutdown           - Remove rules/veth pair (keep namespace for restart)
#   destroy            - Fully delete namespace and all state
#   assign-ip          - Add a public IP; configure source NAT if requested
#   release-ip         - Remove a public IP and its iptables rules
#   add-static-nat     - Add a 1:1 NAT mapping (public IP <-> private IP)
#   delete-static-nat  - Remove a 1:1 NAT mapping
#   add-port-forward   - Add a DNAT port forwarding rule
#   delete-port-forward - Remove a DNAT port forwarding rule
#   custom-action      - Run a built-in or hook-based operator action
#
# Both JSON blobs are forwarded by network-extension.sh as named CLI arguments:
#   --physical-network-extension-details <json>
#       All extension_resource_map_details (hosts, port, username, phys_iface, …)
#   --network-extension-details <json>
#       Per-network opaque JSON blob (host, namespace, …)
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
# CLI args take precedence over environment variables.
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

# Physical interface for VLAN traffic — read from physical-network details,
# then fall back to env-var / default.
PHYS_IFACE_FROM_DETAILS=$(_json_get "${PHYS_DETAILS}" "phys_iface")
PHYS_IFACE="${PHYS_IFACE_FROM_DETAILS:-${NETWORK_EXTENSION_PHYS_IFACE:-eth0}}"

# Shared bridge on the host for public IP access — same precedence.
PUBLIC_BRIDGE_FROM_DETAILS=$(_json_get "${PHYS_DETAILS}" "public_bridge")
PUBLIC_BRIDGE="${PUBLIC_BRIDGE_FROM_DETAILS:-cspublic}"

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

bridge_name()    { echo "csbr${1}"; }
veth_host_name() { echo "veth-h-${1}"; }
veth_ns_name()   { echo "veth-n-${1}"; }
nat_chain()      { echo "${CHAIN_PREFIX}_${1}"; }
filter_chain()   { echo "${CHAIN_PREFIX}_FWD_${1}"; }

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

ensure_public_bridge() {
    if ! ip link show "${PUBLIC_BRIDGE}" >/dev/null 2>&1; then
        ip link add name "${PUBLIC_BRIDGE}" type bridge
        ip link set "${PUBLIC_BRIDGE}" up
        log "Created shared public bridge ${PUBLIC_BRIDGE}"
    fi
}

##############################################################################
# Parse common arguments
##############################################################################

parse_args() {
    NETWORK_ID=""
    NAMESPACE=""
    VLAN=""
    GATEWAY=""
    CIDR=""
    PUBLIC_IP=""
    PRIVATE_IP=""
    PUBLIC_PORT=""
    PRIVATE_PORT=""
    PROTOCOL=""
    SOURCE_NAT="false"

    while [ $# -gt 0 ]; do
        case "$1" in
            --network-id)   NETWORK_ID="$2";   shift 2 ;;
            --namespace)    NAMESPACE="$2";     shift 2 ;;
            --vlan)         VLAN="$2";          shift 2 ;;
            --gateway)      GATEWAY="$2";       shift 2 ;;
            --cidr)         CIDR="$2";          shift 2 ;;
            --public-ip)    PUBLIC_IP="$2";     shift 2 ;;
            --private-ip)   PRIVATE_IP="$2";    shift 2 ;;
            --public-port)  PUBLIC_PORT="$2";   shift 2 ;;
            --private-port) PRIVATE_PORT="$2";  shift 2 ;;
            --protocol)     PROTOCOL="$2";      shift 2 ;;
            --source-nat)   SOURCE_NAT="$2";    shift 2 ;;
            # already consumed by _pre_scan_args — skip silently
            --physical-network-extension-details|--network-extension-details)
                            shift 2 ;;
            *)              shift ;;
        esac
    done

    [ -z "${NETWORK_ID}" ] && die "Missing --network-id"

    # Derive namespace: prefer CLI arg, then ext.details JSON, then default pattern
    if [ -z "${NAMESPACE}" ]; then
        NS_FROM_DETAILS=$(_json_get "${EXTENSION_DETAILS}" "namespace")
        NAMESPACE="${NS_FROM_DETAILS:-cs-net-${NETWORK_ID}}"
    fi
}

# Load persisted state (used by shutdown, destroy, IP operations)
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
            # Derive from extension details JSON, then default
            NS_FROM_DETAILS=$(_json_get "${EXTENSION_DETAILS}" "namespace")
            NAMESPACE="${NS_FROM_DETAILS:-cs-net-${NETWORK_ID}}"
        fi
    fi
}

##############################################################################
# Command: implement
##############################################################################

cmd_implement() {
    parse_args "$@"
    acquire_lock "${NETWORK_ID}"

    log "implement: network=${NETWORK_ID} ns=${NAMESPACE} vlan=${VLAN} gw=${GATEWAY} cidr=${CIDR}"

    local br veth_h veth_n
    br=$(bridge_name "${NETWORK_ID}")
    veth_h=$(veth_host_name "${NETWORK_ID}")
    veth_n=$(veth_ns_name "${NETWORK_ID}")

    # ---- Create namespace ----
    if ! ip netns list 2>/dev/null | grep -q "^${NAMESPACE}\b"; then
        ip netns add "${NAMESPACE}"
        log "Created namespace ${NAMESPACE}"
    fi
    ip netns exec "${NAMESPACE}" ip link set lo up 2>/dev/null || true

    # ---- VLAN interface and internal bridge ----
    if [ -n "${VLAN}" ]; then
        local vif="${PHYS_IFACE}.${VLAN}"

        # Create VLAN interface on host if not present
        if ! ip link show "${vif}" >/dev/null 2>&1 && \
           ! ip netns exec "${NAMESPACE}" ip link show "${vif}" >/dev/null 2>&1; then
            ip link add link "${PHYS_IFACE}" name "${vif}" type vlan id "${VLAN}"
            log "Created VLAN interface ${vif}"
        fi

        # Move VLAN interface into namespace (if not already there)
        if ip link show "${vif}" >/dev/null 2>&1; then
            ip link set "${vif}" netns "${NAMESPACE}"
            log "Moved ${vif} to namespace ${NAMESPACE}"
        fi
        ip netns exec "${NAMESPACE}" ip link set "${vif}" up

        # Create internal bridge inside namespace
        if ! ip netns exec "${NAMESPACE}" ip link show "${br}" >/dev/null 2>&1; then
            # Create on host and move into namespace
            ip link add name "${br}" type bridge 2>/dev/null || true
            ip link set "${br}" netns "${NAMESPACE}" 2>/dev/null || true
            # If bridge was already in namespace (re-implement):
            ip netns exec "${NAMESPACE}" ip link add name "${br}" type bridge 2>/dev/null || true
            log "Created bridge ${br} in namespace ${NAMESPACE}"
        fi
        ip netns exec "${NAMESPACE}" ip link set "${br}" up

        # Attach VLAN interface to bridge inside namespace
        ip netns exec "${NAMESPACE}" ip link set "${vif}" master "${br}" 2>/dev/null || true
    fi

    # Assign gateway IP to bridge inside namespace
    if [ -n "${GATEWAY}" ] && [ -n "${CIDR}" ]; then
        local prefix
        prefix=$(echo "${CIDR}" | cut -d'/' -f2)
        ip netns exec "${NAMESPACE}" ip addr show "${br}" 2>/dev/null | \
            grep -q "${GATEWAY}/${prefix}" || \
            ip netns exec "${NAMESPACE}" ip addr add "${GATEWAY}/${prefix}" dev "${br}"
        log "Assigned ${GATEWAY}/${prefix} to ${br} in ${NAMESPACE}"
    fi

    # ---- veth pair: connect namespace to shared public bridge ----
    ensure_public_bridge

    if ! ip link show "${veth_h}" >/dev/null 2>&1; then
        ip link add "${veth_h}" type veth peer name "${veth_n}"
        ip link set "${veth_n}" netns "${NAMESPACE}"
        ip link set "${veth_h}" master "${PUBLIC_BRIDGE}"
        ip link set "${veth_h}" up
        ip netns exec "${NAMESPACE}" ip link set "${veth_n}" up
        log "Created veth ${veth_h} (host) <-> ${veth_n} (namespace)"
    fi

    # Default route inside namespace: traffic leaves via veth
    ip netns exec "${NAMESPACE}" ip route show | grep -q "^default" || \
        ip netns exec "${NAMESPACE}" ip route add default dev "${veth_n}" 2>/dev/null || true

    # ---- IP forwarding inside namespace ----
    ip netns exec "${NAMESPACE}" sysctl -w net.ipv4.ip_forward=1 >/dev/null 2>&1 || true

    # ---- iptables chains inside namespace ----
    local nchain fchain
    nchain=$(nat_chain "${NETWORK_ID}")
    fchain=$(filter_chain "${NETWORK_ID}")

    ensure_chain nat    "${nchain}"
    ensure_chain filter "${fchain}"
    ensure_jump  nat    PREROUTING  "${nchain}"
    ensure_jump  nat    POSTROUTING "${nchain}"
    ensure_jump  filter FORWARD     "${fchain}"

    # Allow forwarding for bridge (guest VM) traffic
    ip netns exec "${NAMESPACE}" iptables -t filter \
        -C "${fchain}" -i "${br}" -j ACCEPT 2>/dev/null || \
    ip netns exec "${NAMESPACE}" iptables -t filter \
        -A "${fchain}" -i "${br}" -j ACCEPT

    ip netns exec "${NAMESPACE}" iptables -t filter \
        -C "${fchain}" -o "${br}" -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || \
    ip netns exec "${NAMESPACE}" iptables -t filter \
        -A "${fchain}" -o "${br}" -m state --state RELATED,ESTABLISHED -j ACCEPT

    # ---- Persist state ----
    mkdir -p "${STATE_DIR}/${NETWORK_ID}"
    echo "${VLAN}"      > "${STATE_DIR}/${NETWORK_ID}/vlan"
    echo "${GATEWAY}"   > "${STATE_DIR}/${NETWORK_ID}/gateway"
    echo "${CIDR}"      > "${STATE_DIR}/${NETWORK_ID}/cidr"
    echo "${br}"        > "${STATE_DIR}/${NETWORK_ID}/bridge"
    echo "${NAMESPACE}" > "${STATE_DIR}/${NETWORK_ID}/namespace"

    release_lock
    log "implement: done network=${NETWORK_ID} namespace=${NAMESPACE}"
}

##############################################################################
# Command: shutdown
# Remove iptables rules and veth pair. Keep namespace + bridge for restart.
##############################################################################

cmd_shutdown() {
    parse_args "$@"
    _load_state
    acquire_lock "${NETWORK_ID}"

    log "shutdown: network=${NETWORK_ID} ns=${NAMESPACE}"

    local nchain fchain veth_h
    nchain=$(nat_chain "${NETWORK_ID}")
    fchain=$(filter_chain "${NETWORK_ID}")
    veth_h=$(veth_host_name "${NETWORK_ID}")

    # Flush and remove iptables chains
    ip netns exec "${NAMESPACE}" iptables -t nat    -D PREROUTING  -j "${nchain}" 2>/dev/null || true
    ip netns exec "${NAMESPACE}" iptables -t nat    -D POSTROUTING -j "${nchain}" 2>/dev/null || true
    ip netns exec "${NAMESPACE}" iptables -t filter -D FORWARD     -j "${fchain}" 2>/dev/null || true
    ip netns exec "${NAMESPACE}" iptables -t nat    -F "${nchain}" 2>/dev/null || true
    ip netns exec "${NAMESPACE}" iptables -t nat    -X "${nchain}" 2>/dev/null || true
    ip netns exec "${NAMESPACE}" iptables -t filter -F "${fchain}" 2>/dev/null || true
    ip netns exec "${NAMESPACE}" iptables -t filter -X "${fchain}" 2>/dev/null || true

    # Remove veth pair (removes namespace end automatically)
    ip link del "${veth_h}" 2>/dev/null || true

    # Clean IP/rule state (will be re-applied on next implement)
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

    local veth_h
    veth_h=$(veth_host_name "${NETWORK_ID}")

    # Remove veth host end (also removes namespace end)
    ip link del "${veth_h}" 2>/dev/null || true

    # Remove VLAN interface (may be inside namespace)
    if [ -n "${VLAN}" ]; then
        local vif="${PHYS_IFACE}.${VLAN}"
        ip netns exec "${NAMESPACE}" ip link del "${vif}" 2>/dev/null || \
            ip link del "${vif}" 2>/dev/null || true
    fi

    # Delete the namespace (removes all remaining interfaces inside it)
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
##############################################################################

cmd_assign_ip() {
    parse_args "$@"
    _load_state
    acquire_lock "${NETWORK_ID}"

    log "assign-ip: network=${NETWORK_ID} ns=${NAMESPACE} ip=${PUBLIC_IP} source_nat=${SOURCE_NAT}"
    [ -z "${PUBLIC_IP}" ] && die "Missing --public-ip"

    local veth_n nchain fchain
    veth_n=$(veth_ns_name "${NETWORK_ID}")
    nchain=$(nat_chain "${NETWORK_ID}")
    fchain=$(filter_chain "${NETWORK_ID}")

    # Add public IP to the namespace-side veth endpoint
    ip netns exec "${NAMESPACE}" ip addr show "${veth_n}" 2>/dev/null | \
        grep -q "${PUBLIC_IP}/32" || \
        ip netns exec "${NAMESPACE}" ip addr add "${PUBLIC_IP}/32" dev "${veth_n}"
    ip netns exec "${NAMESPACE}" ip link set "${veth_n}" up

    # Add host route so incoming traffic for this IP is sent to the veth host-end
    ip route show | grep -q "^${PUBLIC_IP}" || \
        ip route add "${PUBLIC_IP}/32" dev "$(veth_host_name "${NETWORK_ID}")" 2>/dev/null || true

    # Source NAT: SNAT guest CIDR traffic leaving via veth
    if [ "${SOURCE_NAT}" = "true" ] && [ -n "${CIDR}" ]; then
        ip netns exec "${NAMESPACE}" iptables -t nat \
            -C "${nchain}" -s "${CIDR}" -o "${veth_n}" -j SNAT --to-source "${PUBLIC_IP}" 2>/dev/null || \
        ip netns exec "${NAMESPACE}" iptables -t nat \
            -A "${nchain}" -s "${CIDR}" -o "${veth_n}" -j SNAT --to-source "${PUBLIC_IP}"
        ip netns exec "${NAMESPACE}" iptables -t filter \
            -C "${fchain}" -o "${veth_n}" -s "${CIDR}" -j ACCEPT 2>/dev/null || \
        ip netns exec "${NAMESPACE}" iptables -t filter \
            -A "${fchain}" -o "${veth_n}" -s "${CIDR}" -j ACCEPT
        log "Source NAT: ${CIDR} -> ${PUBLIC_IP} in ${NAMESPACE}"
    fi

    mkdir -p "${STATE_DIR}/${NETWORK_ID}/ips"
    echo "${SOURCE_NAT}" > "${STATE_DIR}/${NETWORK_ID}/ips/${PUBLIC_IP}"

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

    local veth_n nchain fchain
    veth_n=$(veth_ns_name "${NETWORK_ID}")
    nchain=$(nat_chain "${NETWORK_ID}")
    fchain=$(filter_chain "${NETWORK_ID}")

    # Remove source NAT
    if [ -n "${CIDR}" ]; then
        ip netns exec "${NAMESPACE}" iptables -t nat \
            -D "${nchain}" -s "${CIDR}" -o "${veth_n}" -j SNAT --to-source "${PUBLIC_IP}" 2>/dev/null || true
        ip netns exec "${NAMESPACE}" iptables -t filter \
            -D "${fchain}" -o "${veth_n}" -s "${CIDR}" -j ACCEPT 2>/dev/null || true
    fi

    # Remove static NAT rules referencing this public IP
    ip netns exec "${NAMESPACE}" iptables -t nat -S "${nchain}" 2>/dev/null | \
        grep -- "-d ${PUBLIC_IP}\|--to-source ${PUBLIC_IP}" | \
        while read -r rule; do
            ip netns exec "${NAMESPACE}" iptables -t nat \
                -D "${nchain}" ${rule#-A ${nchain}} 2>/dev/null || true
        done

    # Remove host route and IP from veth
    ip route del "${PUBLIC_IP}/32" 2>/dev/null || true
    ip netns exec "${NAMESPACE}" ip addr del "${PUBLIC_IP}/32" dev "${veth_n}" 2>/dev/null || true

    rm -f "${STATE_DIR}/${NETWORK_ID}/ips/${PUBLIC_IP}"

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

    local veth_n br nchain fchain
    veth_n=$(veth_ns_name "${NETWORK_ID}")
    br=$(bridge_name "${NETWORK_ID}")
    nchain=$(nat_chain "${NETWORK_ID}")
    fchain=$(filter_chain "${NETWORK_ID}")

    # Ensure public IP is on veth
    ip netns exec "${NAMESPACE}" ip addr show "${veth_n}" 2>/dev/null | \
        grep -q "${PUBLIC_IP}/32" || \
        ip netns exec "${NAMESPACE}" ip addr add "${PUBLIC_IP}/32" dev "${veth_n}" 2>/dev/null || true
    ip route show | grep -q "^${PUBLIC_IP}" || \
        ip route add "${PUBLIC_IP}/32" dev "$(veth_host_name "${NETWORK_ID}")" 2>/dev/null || true

    # DNAT: inbound to public IP -> private IP
    ip netns exec "${NAMESPACE}" iptables -t nat \
        -C "${nchain}" -d "${PUBLIC_IP}" -j DNAT --to-destination "${PRIVATE_IP}" 2>/dev/null || \
    ip netns exec "${NAMESPACE}" iptables -t nat \
        -A "${nchain}" -d "${PUBLIC_IP}" -j DNAT --to-destination "${PRIVATE_IP}"

    # SNAT: outbound from private IP -> public IP
    ip netns exec "${NAMESPACE}" iptables -t nat \
        -C "${nchain}" -s "${PRIVATE_IP}" -o "${veth_n}" -j SNAT --to-source "${PUBLIC_IP}" 2>/dev/null || \
    ip netns exec "${NAMESPACE}" iptables -t nat \
        -A "${nchain}" -s "${PRIVATE_IP}" -o "${veth_n}" -j SNAT --to-source "${PUBLIC_IP}"

    # Forwarding
    ip netns exec "${NAMESPACE}" iptables -t filter \
        -C "${fchain}" -d "${PRIVATE_IP}" -o "${br}" -j ACCEPT 2>/dev/null || \
    ip netns exec "${NAMESPACE}" iptables -t filter \
        -A "${fchain}" -d "${PRIVATE_IP}" -o "${br}" -j ACCEPT
    ip netns exec "${NAMESPACE}" iptables -t filter \
        -C "${fchain}" -s "${PRIVATE_IP}" -i "${br}" -j ACCEPT 2>/dev/null || \
    ip netns exec "${NAMESPACE}" iptables -t filter \
        -A "${fchain}" -s "${PRIVATE_IP}" -i "${br}" -j ACCEPT

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

    local veth_n br nchain fchain
    veth_n=$(veth_ns_name "${NETWORK_ID}")
    br=$(bridge_name "${NETWORK_ID}")
    nchain=$(nat_chain "${NETWORK_ID}")
    fchain=$(filter_chain "${NETWORK_ID}")

    ip netns exec "${NAMESPACE}" iptables -t nat \
        -D "${nchain}" -d "${PUBLIC_IP}" -j DNAT --to-destination "${PRIVATE_IP}" 2>/dev/null || true
    ip netns exec "${NAMESPACE}" iptables -t nat \
        -D "${nchain}" -s "${PRIVATE_IP}" -o "${veth_n}" -j SNAT --to-source "${PUBLIC_IP}" 2>/dev/null || true
    ip netns exec "${NAMESPACE}" iptables -t filter \
        -D "${fchain}" -d "${PRIVATE_IP}" -o "${br}" -j ACCEPT 2>/dev/null || true
    ip netns exec "${NAMESPACE}" iptables -t filter \
        -D "${fchain}" -s "${PRIVATE_IP}" -i "${br}" -j ACCEPT 2>/dev/null || true

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

    local veth_n br nchain fchain
    veth_n=$(veth_ns_name "${NETWORK_ID}")
    br=$(bridge_name "${NETWORK_ID}")
    nchain=$(nat_chain "${NETWORK_ID}")
    fchain=$(filter_chain "${NETWORK_ID}")

    # Ensure public IP on veth and host route
    ip netns exec "${NAMESPACE}" ip addr show "${veth_n}" 2>/dev/null | \
        grep -q "${PUBLIC_IP}/32" || \
        ip netns exec "${NAMESPACE}" ip addr add "${PUBLIC_IP}/32" dev "${veth_n}" 2>/dev/null || true
    ip route show | grep -q "^${PUBLIC_IP}" || \
        ip route add "${PUBLIC_IP}/32" dev "$(veth_host_name "${NETWORK_ID}")" 2>/dev/null || true

    # DNAT
    ip netns exec "${NAMESPACE}" iptables -t nat \
        -C "${nchain}" -p "${PROTOCOL}" -d "${PUBLIC_IP}" --dport "${PUBLIC_PORT}" \
        -j DNAT --to-destination "${PRIVATE_IP}:${PRIVATE_PORT}" 2>/dev/null || \
    ip netns exec "${NAMESPACE}" iptables -t nat \
        -A "${nchain}" -p "${PROTOCOL}" -d "${PUBLIC_IP}" --dport "${PUBLIC_PORT}" \
        -j DNAT --to-destination "${PRIVATE_IP}:${PRIVATE_PORT}"

    # Allow forwarding
    ip netns exec "${NAMESPACE}" iptables -t filter \
        -C "${fchain}" -p "${PROTOCOL}" -d "${PRIVATE_IP}" --dport "${PRIVATE_PORT}" \
        -o "${br}" -j ACCEPT 2>/dev/null || \
    ip netns exec "${NAMESPACE}" iptables -t filter \
        -A "${fchain}" -p "${PROTOCOL}" -d "${PRIVATE_IP}" --dport "${PRIVATE_PORT}" \
        -o "${br}" -j ACCEPT

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

    local br nchain fchain
    br=$(bridge_name "${NETWORK_ID}")
    nchain=$(nat_chain "${NETWORK_ID}")
    fchain=$(filter_chain "${NETWORK_ID}")

    ip netns exec "${NAMESPACE}" iptables -t nat \
        -D "${nchain}" -p "${PROTOCOL}" -d "${PUBLIC_IP}" --dport "${PUBLIC_PORT}" \
        -j DNAT --to-destination "${PRIVATE_IP}:${PRIVATE_PORT}" 2>/dev/null || true
    ip netns exec "${NAMESPACE}" iptables -t filter \
        -D "${fchain}" -p "${PROTOCOL}" -d "${PRIVATE_IP}" --dport "${PRIVATE_PORT}" \
        -o "${br}" -j ACCEPT 2>/dev/null || true

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
    ACTION_NAME=""
    ACTION_PARAMS_JSON="{}"
    while [ $# -gt 0 ]; do
        case "$1" in
            --network-id)    NETWORK_ID="$2";        shift 2 ;;
            --action)        ACTION_NAME="$2";        shift 2 ;;
            --action-params) ACTION_PARAMS_JSON="${2:-{}}"; shift 2 ;;
            # already consumed by _pre_scan_args — skip silently
            --physical-network-extension-details|--network-extension-details)
                             shift 2 ;;
            *)               shift ;;
        esac
    done
    [ -z "${NETWORK_ID}" ]  && die "custom-action: missing --network-id"
    [ -z "${ACTION_NAME}" ] && die "custom-action: missing --action"

    # Expose each key from the action-params JSON as CS_ACTION_PARAM_<KEY>=value.
    # Relies on a simple grep approach so jq is not required.
    # Keys are upper-cased and non-alphanumeric characters replaced by _.
    if [ "${ACTION_PARAMS_JSON}" != "{}" ] && [ -n "${ACTION_PARAMS_JSON}" ]; then
        while IFS= read -r pair; do
            raw_key=$(printf '%s' "${pair}" | cut -d'"' -f2)
            raw_val=$(printf '%s' "${pair}" | sed 's/^"[^"]*":"\{0,1\}//;s/"\{0,1\}$//')
            [ -z "${raw_key}" ] && continue
            env_key="CS_ACTION_PARAM_$(printf '%s' "${raw_key}" | tr '[:lower:]' '[:upper:]' | tr -cs 'A-Z0-9' '_')"
            export "${env_key}=${raw_val}"
        done < <(printf '%s' "${ACTION_PARAMS_JSON}" | grep -o '"[^"]*":"[^"]*"')
    fi

    _load_state

    log "custom-action: network=${NETWORK_ID} ns=${NAMESPACE} action=${ACTION_NAME}"

    case "${ACTION_NAME}" in
        reboot-device)
            local veth_h veth_n
            veth_h=$(veth_host_name "${NETWORK_ID}")
            veth_n=$(veth_ns_name "${NETWORK_ID}")
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
                exec "${hook}" --network-id "${NETWORK_ID}" --action "${ACTION_NAME}"
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
    implement)           cmd_implement          "$@" ;;
    shutdown)            cmd_shutdown           "$@" ;;
    destroy)             cmd_destroy            "$@" ;;
    assign-ip)           cmd_assign_ip          "$@" ;;
    release-ip)          cmd_release_ip         "$@" ;;
    add-static-nat)      cmd_add_static_nat     "$@" ;;
    delete-static-nat)   cmd_delete_static_nat  "$@" ;;
    add-port-forward)    cmd_add_port_forward   "$@" ;;
    delete-port-forward) cmd_delete_port_forward "$@" ;;
    custom-action)       cmd_custom_action      "$@" ;;
    "")
        echo "Usage: $0 {implement|shutdown|destroy|assign-ip|release-ip|add-static-nat|delete-static-nat|add-port-forward|delete-port-forward|custom-action} [options]" >&2
        exit 1 ;;
    *)
        echo "Unknown command: ${COMMAND}" >&2
        exit 1 ;;
esac

exit 0

