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
# Manages VLAN/bridge creation, source NAT (MASQUERADE), static NAT (DNAT/SNAT),
# and port forwarding via iptables on a Linux gateway server.
#
# Usage:
#   network-extension-wrapper.sh <command> [options]
#
# Commands:
#   implement          - Create VLAN interface and bridge for a network
#   shutdown           - Tear down the bridge/VLAN for a network
#   destroy            - Same as shutdown (full cleanup)
#   assign-ip          - Assign a public IP and optionally enable source NAT
#   release-ip         - Release a public IP and remove associated rules
#   add-static-nat     - Add a static NAT rule (1:1 public<->private mapping)
#   delete-static-nat  - Remove a static NAT rule
#   add-port-forward   - Add a port forwarding rule (DNAT for specific ports)
#   delete-port-forward - Remove a port forwarding rule
##############################################################################

set -e

LOCK_DIR="/var/run/cloudstack"
LOG_FILE="/var/log/cloudstack/management/network-extension.log"
STATE_DIR="/var/lib/cloudstack/network-extension"

# Physical interface that carries VLAN traffic (configurable)
PHYS_IFACE="${NETWORK_EXTENSION_PHYS_IFACE:-${EXTERNAL_NETWORK_PHYS_IFACE:-eth0}}"

# iptables chain prefix for CloudStack external network rules
CHAIN_PREFIX="CS_EXTNET"

##############################################################################
# Helpers
##############################################################################

log() {
    local ts
    ts=$(date '+%Y-%m-%d %H:%M:%S')
    echo "[${ts}] $*" >> "${LOG_FILE}" 2>/dev/null || true
    echo "$*"
}

die() {
    log "ERROR: $*"
    exit 1
}

ensure_dirs() {
    mkdir -p "${LOCK_DIR}" "${STATE_DIR}" "$(dirname "${LOG_FILE}")" 2>/dev/null || true
}

# Acquire a per-network lock to serialize operations
acquire_lock() {
    local network_id="$1"
    LOCKFILE="${LOCK_DIR}/extnet-${network_id}.lock"
    exec 200>"${LOCKFILE}"
    flock -w 30 200 || die "Failed to acquire lock for network ${network_id}"
}

release_lock() {
    exec 200>&- 2>/dev/null || true
}

bridge_name() {
    local network_id="$1"
    echo "csbr${network_id}"
}

vlan_iface_name() {
    local vlan="$1"
    if [ -z "${vlan}" ]; then
        echo ""
        return
    fi
    echo "${PHYS_IFACE}.${vlan}"
}

# Get the network CIDR in a.b.c.d/prefix format
cidr_to_network() {
    local cidr="$1"
    # use ipcalc or just pass through
    echo "${cidr}"
}

# Custom iptables chain for a network
nat_chain() {
    local network_id="$1"
    echo "${CHAIN_PREFIX}_${network_id}"
}

filter_chain() {
    local network_id="$1"
    echo "${CHAIN_PREFIX}_FWD_${network_id}"
}

ensure_chain() {
    local table="$1"
    local chain="$2"
    iptables -t "${table}" -n -L "${chain}" >/dev/null 2>&1 || \
        iptables -t "${table}" -N "${chain}"
}

# Ensure our chain is jumped to from the parent chain
ensure_jump() {
    local table="$1"
    local parent="$2"
    local chain="$3"
    iptables -t "${table}" -C "${parent}" -j "${chain}" 2>/dev/null || \
        iptables -t "${table}" -I "${parent}" -j "${chain}"
}

##############################################################################
# Parse common arguments
##############################################################################

parse_args() {
    NETWORK_ID=""
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
            --network-id)  NETWORK_ID="$2"; shift 2 ;;
            --vlan)        VLAN="$2"; shift 2 ;;
            --gateway)     GATEWAY="$2"; shift 2 ;;
            --cidr)        CIDR="$2"; shift 2 ;;
            --public-ip)   PUBLIC_IP="$2"; shift 2 ;;
            --private-ip)  PRIVATE_IP="$2"; shift 2 ;;
            --public-port) PUBLIC_PORT="$2"; shift 2 ;;
            --private-port) PRIVATE_PORT="$2"; shift 2 ;;
            --protocol)    PROTOCOL="$2"; shift 2 ;;
            --source-nat)  SOURCE_NAT="$2"; shift 2 ;;
            *)             shift ;;
        esac
    done

    [ -z "${NETWORK_ID}" ] && die "Missing --network-id"
}

##############################################################################
# Command: implement
# Create VLAN sub-interface, bridge, and base iptables chains
##############################################################################

cmd_implement() {
    parse_args "$@"
    acquire_lock "${NETWORK_ID}"

    log "implement: network=${NETWORK_ID} vlan=${VLAN} gw=${GATEWAY} cidr=${CIDR}"

    local br
    br=$(bridge_name "${NETWORK_ID}")

    # Create VLAN interface if specified
    if [ -n "${VLAN}" ]; then
        local vif
        vif=$(vlan_iface_name "${VLAN}")
        if ! ip link show "${vif}" >/dev/null 2>&1; then
            ip link add link "${PHYS_IFACE}" name "${vif}" type vlan id "${VLAN}"
            log "Created VLAN interface ${vif}"
        fi
        ip link set "${vif}" up
    fi

    # Create bridge if not exists
    if ! ip link show "${br}" >/dev/null 2>&1; then
        ip link add name "${br}" type bridge
        log "Created bridge ${br}"
    fi
    ip link set "${br}" up

    # Attach VLAN interface to bridge
    if [ -n "${VLAN}" ]; then
        local vif
        vif=$(vlan_iface_name "${VLAN}")
        if ! bridge link show | grep -q "${vif}"; then
            ip link set "${vif}" master "${br}"
            log "Attached ${vif} to bridge ${br}"
        fi
    fi

    # Assign gateway IP to the bridge
    if [ -n "${GATEWAY}" ] && [ -n "${CIDR}" ]; then
        local prefix
        prefix=$(echo "${CIDR}" | cut -d'/' -f2)
        if ! ip addr show "${br}" | grep -q "${GATEWAY}/${prefix}"; then
            ip addr add "${GATEWAY}/${prefix}" dev "${br}"
            log "Assigned ${GATEWAY}/${prefix} to ${br}"
        fi
    fi

    # Enable IP forwarding
    sysctl -w net.ipv4.ip_forward=1 >/dev/null 2>&1

    # Create iptables chains for this network
    local nchain fchain
    nchain=$(nat_chain "${NETWORK_ID}")
    fchain=$(filter_chain "${NETWORK_ID}")

    ensure_chain nat "${nchain}"
    ensure_chain filter "${fchain}"
    ensure_jump nat PREROUTING "${nchain}"
    ensure_jump nat POSTROUTING "${nchain}"
    ensure_jump filter FORWARD "${fchain}"

    # Allow forwarding for the bridge
    iptables -t filter -C "${fchain}" -i "${br}" -j ACCEPT 2>/dev/null || \
        iptables -t filter -A "${fchain}" -i "${br}" -j ACCEPT
    iptables -t filter -C "${fchain}" -o "${br}" -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || \
        iptables -t filter -A "${fchain}" -o "${br}" -m state --state RELATED,ESTABLISHED -j ACCEPT

    # Save state
    mkdir -p "${STATE_DIR}/${NETWORK_ID}"
    echo "${VLAN}" > "${STATE_DIR}/${NETWORK_ID}/vlan"
    echo "${GATEWAY}" > "${STATE_DIR}/${NETWORK_ID}/gateway"
    echo "${CIDR}" > "${STATE_DIR}/${NETWORK_ID}/cidr"
    echo "${br}" > "${STATE_DIR}/${NETWORK_ID}/bridge"

    release_lock
    log "implement: done for network ${NETWORK_ID}"
}

##############################################################################
# Command: shutdown / destroy
# Tear down bridge, VLAN interface, and iptables chains
##############################################################################

cmd_shutdown() {
    parse_args "$@"
    acquire_lock "${NETWORK_ID}"

    log "shutdown: network=${NETWORK_ID} vlan=${VLAN}"

    local br
    br=$(bridge_name "${NETWORK_ID}")

    # Flush and remove iptables chains
    local nchain fchain
    nchain=$(nat_chain "${NETWORK_ID}")
    fchain=$(filter_chain "${NETWORK_ID}")

    # Remove jumps from parent chains
    iptables -t nat -D PREROUTING -j "${nchain}" 2>/dev/null || true
    iptables -t nat -D POSTROUTING -j "${nchain}" 2>/dev/null || true
    iptables -t filter -D FORWARD -j "${fchain}" 2>/dev/null || true

    # Flush and delete chains
    iptables -t nat -F "${nchain}" 2>/dev/null || true
    iptables -t nat -X "${nchain}" 2>/dev/null || true
    iptables -t filter -F "${fchain}" 2>/dev/null || true
    iptables -t filter -X "${fchain}" 2>/dev/null || true

    # Remove bridge
    if ip link show "${br}" >/dev/null 2>&1; then
        ip link set "${br}" down
        ip link delete "${br}" type bridge
        log "Deleted bridge ${br}"
    fi

    # Remove VLAN interface
    if [ -z "${VLAN}" ] && [ -f "${STATE_DIR}/${NETWORK_ID}/vlan" ]; then
        VLAN=$(cat "${STATE_DIR}/${NETWORK_ID}/vlan")
    fi
    if [ -n "${VLAN}" ]; then
        local vif
        vif=$(vlan_iface_name "${VLAN}")
        if ip link show "${vif}" >/dev/null 2>&1; then
            ip link set "${vif}" down
            ip link delete "${vif}"
            log "Deleted VLAN interface ${vif}"
        fi
    fi

    # Clean state
    rm -rf "${STATE_DIR}/${NETWORK_ID}"

    release_lock
    log "shutdown: done for network ${NETWORK_ID}"
}

##############################################################################
# Command: assign-ip
# Add a public IP to the external interface and set up source NAT if needed
##############################################################################

cmd_assign_ip() {
    parse_args "$@"
    acquire_lock "${NETWORK_ID}"

    log "assign-ip: network=${NETWORK_ID} ip=${PUBLIC_IP} source_nat=${SOURCE_NAT}"

    [ -z "${PUBLIC_IP}" ] && die "Missing --public-ip"

    # Add the public IP to the physical interface (as secondary address)
    if ! ip addr show "${PHYS_IFACE}" | grep -q "${PUBLIC_IP}/"; then
        ip addr add "${PUBLIC_IP}/32" dev "${PHYS_IFACE}"
        log "Added ${PUBLIC_IP}/32 to ${PHYS_IFACE}"
    fi

    # If source NAT, set up MASQUERADE/SNAT for the guest network
    if [ "${SOURCE_NAT}" = "true" ]; then
        local nchain br
        nchain=$(nat_chain "${NETWORK_ID}")
        br=$(bridge_name "${NETWORK_ID}")

        # SNAT: traffic from guest network going out gets NATted to the public IP
        iptables -t nat -C "${nchain}" -s "${CIDR}" -o "${PHYS_IFACE}" -j SNAT --to-source "${PUBLIC_IP}" 2>/dev/null || \
            iptables -t nat -A "${nchain}" -s "${CIDR}" -o "${PHYS_IFACE}" -j SNAT --to-source "${PUBLIC_IP}"
        log "Source NAT enabled: ${CIDR} -> ${PUBLIC_IP}"

        # Allow forwarding from guest to external
        local fchain
        fchain=$(filter_chain "${NETWORK_ID}")
        iptables -t filter -C "${fchain}" -o "${PHYS_IFACE}" -s "${CIDR}" -j ACCEPT 2>/dev/null || \
            iptables -t filter -A "${fchain}" -o "${PHYS_IFACE}" -s "${CIDR}" -j ACCEPT
    fi

    # Save IP state
    mkdir -p "${STATE_DIR}/${NETWORK_ID}/ips"
    echo "${SOURCE_NAT}" > "${STATE_DIR}/${NETWORK_ID}/ips/${PUBLIC_IP}"

    release_lock
    log "assign-ip: done for ${PUBLIC_IP} on network ${NETWORK_ID}"
}

##############################################################################
# Command: release-ip
# Remove a public IP and any associated source NAT rules
##############################################################################

cmd_release_ip() {
    parse_args "$@"
    acquire_lock "${NETWORK_ID}"

    log "release-ip: network=${NETWORK_ID} ip=${PUBLIC_IP}"

    [ -z "${PUBLIC_IP}" ] && die "Missing --public-ip"

    # Read CIDR from state if not passed
    if [ -z "${CIDR}" ] && [ -f "${STATE_DIR}/${NETWORK_ID}/cidr" ]; then
        CIDR=$(cat "${STATE_DIR}/${NETWORK_ID}/cidr")
    fi

    # Remove source NAT rule if present
    local nchain fchain
    nchain=$(nat_chain "${NETWORK_ID}")
    fchain=$(filter_chain "${NETWORK_ID}")

    if [ -n "${CIDR}" ]; then
        iptables -t nat -D "${nchain}" -s "${CIDR}" -o "${PHYS_IFACE}" -j SNAT --to-source "${PUBLIC_IP}" 2>/dev/null || true
        iptables -t filter -D "${fchain}" -o "${PHYS_IFACE}" -s "${CIDR}" -j ACCEPT 2>/dev/null || true
    fi

    # Remove static NAT rules referencing this IP
    iptables -t nat -S "${nchain}" 2>/dev/null | grep -- "--to-destination.*${PUBLIC_IP}" | while read -r rule; do
        iptables -t nat -D "${nchain}" ${rule#-A ${nchain}} 2>/dev/null || true
    done
    iptables -t nat -S "${nchain}" 2>/dev/null | grep -- "-d ${PUBLIC_IP}" | while read -r rule; do
        iptables -t nat -D "${nchain}" ${rule#-A ${nchain}} 2>/dev/null || true
    done

    # Remove the IP from the physical interface
    ip addr del "${PUBLIC_IP}/32" dev "${PHYS_IFACE}" 2>/dev/null || true
    log "Removed ${PUBLIC_IP}/32 from ${PHYS_IFACE}"

    # Clean state
    rm -f "${STATE_DIR}/${NETWORK_ID}/ips/${PUBLIC_IP}"

    release_lock
    log "release-ip: done for ${PUBLIC_IP} on network ${NETWORK_ID}"
}

##############################################################################
# Command: add-static-nat
# 1:1 NAT mapping: public IP <-> private IP
# DNAT inbound traffic destined for public IP to private IP
# SNAT outbound traffic from private IP to public IP
##############################################################################

cmd_add_static_nat() {
    parse_args "$@"
    acquire_lock "${NETWORK_ID}"

    log "add-static-nat: network=${NETWORK_ID} ${PUBLIC_IP} <-> ${PRIVATE_IP}"

    [ -z "${PUBLIC_IP}" ] && die "Missing --public-ip"
    [ -z "${PRIVATE_IP}" ] && die "Missing --private-ip"

    local nchain fchain br
    nchain=$(nat_chain "${NETWORK_ID}")
    fchain=$(filter_chain "${NETWORK_ID}")
    br=$(bridge_name "${NETWORK_ID}")

    # DNAT: inbound to public IP -> private IP
    iptables -t nat -C "${nchain}" -d "${PUBLIC_IP}" -j DNAT --to-destination "${PRIVATE_IP}" 2>/dev/null || \
        iptables -t nat -A "${nchain}" -d "${PUBLIC_IP}" -j DNAT --to-destination "${PRIVATE_IP}"

    # SNAT: outbound from private IP -> public IP
    iptables -t nat -C "${nchain}" -s "${PRIVATE_IP}" -o "${PHYS_IFACE}" -j SNAT --to-source "${PUBLIC_IP}" 2>/dev/null || \
        iptables -t nat -A "${nchain}" -s "${PRIVATE_IP}" -o "${PHYS_IFACE}" -j SNAT --to-source "${PUBLIC_IP}"

    # Allow forwarding for static NAT traffic
    iptables -t filter -C "${fchain}" -d "${PRIVATE_IP}" -o "${br}" -j ACCEPT 2>/dev/null || \
        iptables -t filter -A "${fchain}" -d "${PRIVATE_IP}" -o "${br}" -j ACCEPT
    iptables -t filter -C "${fchain}" -s "${PRIVATE_IP}" -i "${br}" -j ACCEPT 2>/dev/null || \
        iptables -t filter -A "${fchain}" -s "${PRIVATE_IP}" -i "${br}" -j ACCEPT

    # Save state
    mkdir -p "${STATE_DIR}/${NETWORK_ID}/static-nat"
    echo "${PRIVATE_IP}" > "${STATE_DIR}/${NETWORK_ID}/static-nat/${PUBLIC_IP}"

    release_lock
    log "add-static-nat: done ${PUBLIC_IP} <-> ${PRIVATE_IP}"
}

##############################################################################
# Command: delete-static-nat
##############################################################################

cmd_delete_static_nat() {
    parse_args "$@"
    acquire_lock "${NETWORK_ID}"

    log "delete-static-nat: network=${NETWORK_ID} ${PUBLIC_IP} <-> ${PRIVATE_IP}"

    [ -z "${PUBLIC_IP}" ] && die "Missing --public-ip"

    # Read private IP from state if not passed
    if [ -z "${PRIVATE_IP}" ] && [ -f "${STATE_DIR}/${NETWORK_ID}/static-nat/${PUBLIC_IP}" ]; then
        PRIVATE_IP=$(cat "${STATE_DIR}/${NETWORK_ID}/static-nat/${PUBLIC_IP}")
    fi
    [ -z "${PRIVATE_IP}" ] && die "Missing --private-ip and no state found"

    local nchain fchain br
    nchain=$(nat_chain "${NETWORK_ID}")
    fchain=$(filter_chain "${NETWORK_ID}")
    br=$(bridge_name "${NETWORK_ID}")

    # Remove DNAT
    iptables -t nat -D "${nchain}" -d "${PUBLIC_IP}" -j DNAT --to-destination "${PRIVATE_IP}" 2>/dev/null || true

    # Remove SNAT
    iptables -t nat -D "${nchain}" -s "${PRIVATE_IP}" -o "${PHYS_IFACE}" -j SNAT --to-source "${PUBLIC_IP}" 2>/dev/null || true

    # Remove forwarding rules
    iptables -t filter -D "${fchain}" -d "${PRIVATE_IP}" -o "${br}" -j ACCEPT 2>/dev/null || true
    iptables -t filter -D "${fchain}" -s "${PRIVATE_IP}" -i "${br}" -j ACCEPT 2>/dev/null || true

    # Clean state
    rm -f "${STATE_DIR}/${NETWORK_ID}/static-nat/${PUBLIC_IP}"

    release_lock
    log "delete-static-nat: done ${PUBLIC_IP} <-> ${PRIVATE_IP}"
}

##############################################################################
# Command: add-port-forward
# DNAT specific port(s) on public IP to private IP:port
##############################################################################

cmd_add_port_forward() {
    parse_args "$@"
    acquire_lock "${NETWORK_ID}"

    log "add-port-forward: network=${NETWORK_ID} ${PUBLIC_IP}:${PUBLIC_PORT} -> ${PRIVATE_IP}:${PRIVATE_PORT} (${PROTOCOL})"

    [ -z "${PUBLIC_IP}" ] && die "Missing --public-ip"
    [ -z "${PUBLIC_PORT}" ] && die "Missing --public-port"
    [ -z "${PRIVATE_IP}" ] && die "Missing --private-ip"
    [ -z "${PRIVATE_PORT}" ] && die "Missing --private-port"
    [ -z "${PROTOCOL}" ] && PROTOCOL="tcp"

    local nchain fchain br
    nchain=$(nat_chain "${NETWORK_ID}")
    fchain=$(filter_chain "${NETWORK_ID}")
    br=$(bridge_name "${NETWORK_ID}")

    # Handle port ranges: "80:90" format for iptables
    local pub_port_iptables="${PUBLIC_PORT}"
    local priv_port_iptables="${PRIVATE_PORT}"

    # DNAT: inbound traffic to public IP:port -> private IP:port
    iptables -t nat -C "${nchain}" -p "${PROTOCOL}" -d "${PUBLIC_IP}" --dport "${pub_port_iptables}" \
        -j DNAT --to-destination "${PRIVATE_IP}:${priv_port_iptables}" 2>/dev/null || \
    iptables -t nat -A "${nchain}" -p "${PROTOCOL}" -d "${PUBLIC_IP}" --dport "${pub_port_iptables}" \
        -j DNAT --to-destination "${PRIVATE_IP}:${priv_port_iptables}"

    # Allow forwarding for this port forward
    iptables -t filter -C "${fchain}" -p "${PROTOCOL}" -d "${PRIVATE_IP}" --dport "${priv_port_iptables}" \
        -o "${br}" -j ACCEPT 2>/dev/null || \
    iptables -t filter -A "${fchain}" -p "${PROTOCOL}" -d "${PRIVATE_IP}" --dport "${priv_port_iptables}" \
        -o "${br}" -j ACCEPT

    # Save state
    local safe_port
    safe_port=$(echo "${PUBLIC_PORT}" | tr ':' '-')
    mkdir -p "${STATE_DIR}/${NETWORK_ID}/port-forward"
    echo "${PROTOCOL} ${PUBLIC_IP} ${PUBLIC_PORT} ${PRIVATE_IP} ${PRIVATE_PORT}" > \
        "${STATE_DIR}/${NETWORK_ID}/port-forward/${PROTOCOL}_${PUBLIC_IP}_${safe_port}"

    release_lock
    log "add-port-forward: done ${PUBLIC_IP}:${PUBLIC_PORT} -> ${PRIVATE_IP}:${PRIVATE_PORT} (${PROTOCOL})"
}

##############################################################################
# Command: delete-port-forward
##############################################################################

cmd_delete_port_forward() {
    parse_args "$@"
    acquire_lock "${NETWORK_ID}"

    log "delete-port-forward: network=${NETWORK_ID} ${PUBLIC_IP}:${PUBLIC_PORT} -> ${PRIVATE_IP}:${PRIVATE_PORT} (${PROTOCOL})"

    [ -z "${PUBLIC_IP}" ] && die "Missing --public-ip"
    [ -z "${PUBLIC_PORT}" ] && die "Missing --public-port"
    [ -z "${PRIVATE_IP}" ] && die "Missing --private-ip"
    [ -z "${PRIVATE_PORT}" ] && die "Missing --private-port"
    [ -z "${PROTOCOL}" ] && PROTOCOL="tcp"

    local nchain fchain br
    nchain=$(nat_chain "${NETWORK_ID}")
    fchain=$(filter_chain "${NETWORK_ID}")
    br=$(bridge_name "${NETWORK_ID}")

    local pub_port_iptables="${PUBLIC_PORT}"
    local priv_port_iptables="${PRIVATE_PORT}"

    # Remove DNAT
    iptables -t nat -D "${nchain}" -p "${PROTOCOL}" -d "${PUBLIC_IP}" --dport "${pub_port_iptables}" \
        -j DNAT --to-destination "${PRIVATE_IP}:${priv_port_iptables}" 2>/dev/null || true

    # Remove forwarding rule
    iptables -t filter -D "${fchain}" -p "${PROTOCOL}" -d "${PRIVATE_IP}" --dport "${priv_port_iptables}" \
        -o "${br}" -j ACCEPT 2>/dev/null || true

    # Clean state
    local safe_port
    safe_port=$(echo "${PUBLIC_PORT}" | tr ':' '-')
    rm -f "${STATE_DIR}/${NETWORK_ID}/port-forward/${PROTOCOL}_${PUBLIC_IP}_${safe_port}"

    release_lock
    log "delete-port-forward: done ${PUBLIC_IP}:${PUBLIC_PORT} -> ${PRIVATE_IP}:${PRIVATE_PORT} (${PROTOCOL})"
}

##############################################################################
# Command: custom-action
# Run an operator-defined action against this network's device/namespace.
#
# Built-in actions:
#   reboot-device  – bring the namespace interfaces down and back up
#   dump-config    – dump iptables rules and bridge/interface state
#
# Caller-supplied parameters are available as CS_ACTION_PARAM_<KEY> env vars.
# Custom scripts can extend this function to support additional actions.
##############################################################################

cmd_custom_action() {
    NETWORK_ID=""
    ACTION_NAME=""

    while [ $# -gt 0 ]; do
        case "$1" in
            --network-id) NETWORK_ID="$2"; shift 2 ;;
            --action)     ACTION_NAME="$2"; shift 2 ;;
            *)            shift ;;
        esac
    done

    [ -z "${NETWORK_ID}" ] && die "custom-action: missing --network-id"
    [ -z "${ACTION_NAME}" ] && die "custom-action: missing --action"

    log "custom-action: network=${NETWORK_ID} action=${ACTION_NAME}"

    case "${ACTION_NAME}" in

        reboot-device)
            # Bounce all interfaces attached to the bridge for this network
            local br
            br=$(bridge_name "${NETWORK_ID}")
            acquire_lock "${NETWORK_ID}"
            log "reboot-device: bouncing bridge ${br}"
            if ip link show "${br}" >/dev/null 2>&1; then
                ip link set "${br}" down
                sleep 1
                ip link set "${br}" up
                log "reboot-device: bridge ${br} is back up"
            else
                log "reboot-device: bridge ${br} not found, nothing to do"
            fi
            release_lock
            echo "reboot-device: OK (bridge=${br})"
            ;;

        dump-config)
            # Dump iptables rules and interface/bridge state for this network
            local br
            br=$(bridge_name "${NETWORK_ID}")
            echo "=== Bridge / Interface state for network ${NETWORK_ID} ==="
            ip link show "${br}" 2>/dev/null || echo "(bridge ${br} not found)"
            echo ""
            echo "=== NAT table ==="
            iptables -t nat -L -n -v --line-numbers 2>/dev/null | grep -E "CLOUDSTACK|${CHAIN_PREFIX}" || \
                iptables -t nat -L -n -v 2>/dev/null | head -60 || echo "(iptables not available)"
            echo ""
            echo "=== FILTER table ==="
            iptables -t filter -L -n -v --line-numbers 2>/dev/null | grep -E "CLOUDSTACK|${CHAIN_PREFIX}" || \
                iptables -t filter -L -n -v 2>/dev/null | head -60 || echo "(iptables not available)"
            echo ""
            echo "=== State files ==="
            ls -la "${STATE_DIR}/${NETWORK_ID}/" 2>/dev/null || echo "(no state directory)"
            ;;

        *)
            # Unknown action — allow extension via a hook script if present
            local hook="${STATE_DIR}/hooks/custom-action-${ACTION_NAME}.sh"
            if [ -x "${hook}" ]; then
                log "custom-action: delegating to hook ${hook}"
                exec "${hook}" --network-id "${NETWORK_ID}" --action "${ACTION_NAME}"
            else
                die "custom-action: unknown action '${ACTION_NAME}'. " \
                    "Supported built-in actions: reboot-device, dump-config. " \
                    "To add custom actions, create ${STATE_DIR}/hooks/custom-action-<name>.sh"
            fi
            ;;
    esac
}

##############################################################################
# Main dispatcher
##############################################################################

ensure_dirs

COMMAND="${1}"
shift || true

case "${COMMAND}" in
    implement)          cmd_implement "$@" ;;
    shutdown)           cmd_shutdown "$@" ;;
    destroy)            cmd_shutdown "$@" ;;
    assign-ip)          cmd_assign_ip "$@" ;;
    release-ip)         cmd_release_ip "$@" ;;
    add-static-nat)     cmd_add_static_nat "$@" ;;
    delete-static-nat)  cmd_delete_static_nat "$@" ;;
    add-port-forward)   cmd_add_port_forward "$@" ;;
    delete-port-forward) cmd_delete_port_forward "$@" ;;
    custom-action)      cmd_custom_action "$@" ;;
    *)
        echo "Usage: $0 {implement|shutdown|destroy|assign-ip|release-ip|add-static-nat|delete-static-nat|add-port-forward|delete-port-forward|custom-action} [options]"
        exit 1
        ;;
esac

exit 0

