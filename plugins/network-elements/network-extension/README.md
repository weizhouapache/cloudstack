# NetworkExtension Plugin

This plugin allows CloudStack to delegate guest network operations (create/delete
network, source NAT, static NAT, port forwarding, firewall) to an **external network
device** via a user-supplied **entry-point script** installed on the management server.

The entry-point script acts as the bridge between CloudStack and the external device.
It can use **any protocol** to drive the device: SSH commands on a Linux server,
REST API calls to a firewall or SDN controller, NETCONF/gRPC to a network appliance,
or any other mechanism.  CloudStack only cares that the script is executable and
follows the command interface described below.

---

## Design Principles

- **One extension = one network service provider.**  
  When a `NetworkOrchestrator` extension is registered with a physical network via
  `registerExtension`, the **extension name becomes the network service provider
  name**.  There is no separate "NetworkExtension" catch-all provider.

- **Device credentials live in the extension registration.**  
  Connection details (host, port, username, password, SSH key, API tokens, etc.)
  are stored as `details` on the `registerExtension` call.  There are no separate
  `addExternalNetworkDevice` / `listExternalNetworkDevices` APIs.

- **Multiple extensions per physical network are supported.**  
  Each extension appears as its own tab in the physical network's
  *Service Providers* view, with its own enable/disable state.

- **Same script, different physical networks.**  
  If you need the same entry-point script to serve two physical networks, create two
  extensions with different names (and the same `path`), and register each one to
  its respective physical network.

---

## Supported External Devices

The external device can be anything that accepts remote management commands:

| Device type | Transport | Status |
|---|---|---|
| Linux server (namespace/bridge/iptables) | SSH | ✅ Implemented (see `network-extension-wrapper.sh`) |
| Network appliance / router / firewall | SSH | 🔧 Custom script required |
| SDN controller / network OS | REST API | 🔧 Custom script required |
| Network appliance | NETCONF / gRPC | 🔧 Custom script required |

---

## How It Works

```
CloudStack Management Server
  │
  │  Executes entry-point script (installed locally on mgmt server)
  │  Passes device credentials as CS_NET_DEV_* env vars
  │  (credentials read from extension_resource_map_details)
  │
  └─► /etc/cloudstack/extensions/<extension-name>/entry-point <command> [options]
           │
           │  Connects to the external device (SSH, REST API, NETCONF, …)
           │
           └─► External Network Device
                  └─► Performs the network operation
```

1. **CloudStack** calls the entry-point script for every network lifecycle event:
   create network, delete network, assign public IP, add static NAT rule, etc.

2. **The entry-point script** is installed on the management server under
   `/etc/cloudstack/extensions/<extension-name>/entry-point`.  
   It connects to the external device using whatever protocol is appropriate and
   performs the requested operation.  All necessary context is supplied via
   environment variables (see [Environment Variables](#environment-variables-reference)).

3. **Device credentials** are stored in the CloudStack database when the extension
   is registered with a physical network, and are injected into the script's
   environment on every call — they are never hard-coded in the script.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────┐
│               CloudStack Management Server           │
│                                                      │
│  NetworkExtensionElement.java                        │
│    │                                                 │
│    │  reads credentials from extension_resource_map_details
│    │  injects them as CS_NET_DEV_* env vars          │
│    ▼                                                 │
│  /etc/cloudstack/extensions/                         │
│    └── <extension-name>/                             │
│          └── entry-point   ◄── entry-point script    │
└──────────────────┬──────────────────────────────────┘
                   │ SSH / REST API / NETCONF / …
                   ▼
         ┌─────────────────────┐
         │  External Device    │
         │  (Linux server,     │
         │   firewall, SDN…)   │
         └─────────────────────┘
```

---

## Entry-Point Script Operations

CloudStack calls the entry-point script with the following commands.
The script must handle all commands that correspond to the services declared
in the extension's `network.capabilities` detail:

| Command | Trigger | Description |
|---|---|---|
| `implement` | Network created / restarted | Create the network on the device (namespace, bridge, routing, source NAT) |
| `shutdown` | Network stopped | Tear down the network, remove NAT rules |
| `destroy` | Network deleted | Remove all state for the network on the device |
| `assign-ip` | Public IP acquired | Notify the device of a new public IP |
| `release-ip` | Public IP released | Remove the public IP from the device |
| `add-static-nat` | Static NAT enabled | Create a 1-to-1 NAT mapping |
| `delete-static-nat` | Static NAT disabled | Remove the 1-to-1 NAT mapping |
| `add-port-forward` | Port forwarding rule added | Create a DNAT rule |
| `delete-port-forward` | Port forwarding rule removed | Remove the DNAT rule |
| `apply-firewall` | Firewall rules changed | Apply the full set of firewall rules |
| `custom-action` | `runCustomAction` API | Run an operator-defined ad-hoc action |

The entry-point script can be written in **any language** — it just needs to be
executable on the management server:

- Shell script (`#!/bin/bash`) — simplest, great for SSH-based devices
- Python script (`#!/usr/bin/env python3`) — ideal for REST API calls
- Any other compiled or interpreted executable

The reference implementation for a Linux server is
`plugins/network-elements/network-extension/src/main/resources/scripts/network-extension-wrapper.sh`.
The `extensions/network-extension/entry-point` script is an SSH-based relay that
invokes `network-extension-wrapper.sh` inside a Linux network namespace on the
remote host.

---

## Multi-Extension Support

A single physical network can have **multiple different** `NetworkOrchestrator`
extensions registered (one per extension name).  Each one appears as a separate
tab in the physical network's Service Providers view.

When CloudStack needs to operate on a network, it resolves the correct extension:

```
Network N → ntwk_service_map.provider = "my-linux-router"
                      │
                      ▼
    getExtensionForPhysicalNetworkAndProvider(physNetId, "my-linux-router")
                      │
                      ▼
    extension WHERE name = "my-linux-router"
                      │
          ┌───────────┴────────────┐
          ▼                        ▼
    Script path               Device credentials
    (entry-point for          (host/port/sshkey stored in
     my-linux-router)          extension_resource_map_details
                               for this registration only)
```

> **The extension name must exactly match the NSP / provider name.**

---

## Setup

### Step 1 — Create a NetworkOrchestrator Extension

The `network.capabilities` detail declares which network services the extension
provides.  If omitted, all default services are available.

```bash
cmk createExtension \
  name="my-linux-router" \
  type=NetworkOrchestrator \
  path="my-linux-router/entry-point" \
  details[0].key=network.capabilities \
  details[0].value='{"services":["SourceNat","StaticNat","PortForwarding","Firewall","Gateway"],"capabilities":{"SourceNat":{"SupportedSourceNatTypes":"peraccount","RedundantRouter":"false"}}}'
```

To create a second extension for a different device on the same physical network:

```bash
cmk createExtension \
  name="my-firewall" \
  type=NetworkOrchestrator \
  path="my-firewall/entry-point" \
  details[0].key=network.capabilities \
  details[0].value='{"services":["Firewall","PortForwarding"]}'
```

> **Important:** Only extensions of type `NetworkOrchestrator` can be registered
> with a physical network.  Other extension types (e.g. `Orchestrator`) are for
> external compute only.

### Step 2 — Install the Entry-Point Script on the Management Server

Place the executable entry-point script at:
```
/etc/cloudstack/extensions/<extension-name>/entry-point
```

**Example: SSH-based Linux server** (using the bundled `entry-point` relay)

```bash
# Copy the bundled SSH relay to the extension directory
mkdir -p /etc/cloudstack/extensions/my-linux-router
cp extensions/network-extension/entry-point \
   /etc/cloudstack/extensions/my-linux-router/entry-point
chmod +x /etc/cloudstack/extensions/my-linux-router/entry-point

# Copy network-extension-wrapper.sh to the remote host
scp plugins/network-elements/network-extension/src/main/resources/scripts/network-extension-wrapper.sh \
    root@192.168.100.10:/usr/local/share/cloudstack/network-extension-wrapper.sh
ssh root@192.168.100.10 "chmod +x /usr/local/share/cloudstack/network-extension-wrapper.sh"
```

The bundled `entry-point` script SSHes to the remote host and runs
`network-extension-wrapper.sh` inside the Linux network namespace.  All connection
details are injected by CloudStack as `CS_NET_DEV_*` environment variables.

**Example: minimal custom entry-point (Bash + SSH)**

```bash
#!/bin/bash
# Entry-point script: forwards all operations to a remote Linux server via SSH.
# CloudStack injects device credentials as CS_NET_DEV_* environment variables.

HOST="${CS_NET_DEV_HOST}"
PORT="${CS_NET_DEV_PORT:-22}"
USER="${CS_NET_DEV_USERNAME:-root}"
SSH_OPTS="-p ${PORT} -o StrictHostKeyChecking=no -o BatchMode=yes"

if [ -n "${CS_NET_DEV_SSHKEY}" ]; then
    KEYFILE=$(mktemp)
    printf '%s' "${CS_NET_DEV_SSHKEY}" > "${KEYFILE}"
    chmod 600 "${KEYFILE}"
    ssh -i "${KEYFILE}" ${SSH_OPTS} "${USER}@${HOST}" \
        "/usr/local/share/cloudstack/network-extension-wrapper.sh $*"
    RC=$?
    rm -f "${KEYFILE}"
    exit ${RC}
else
    sshpass -p "${CS_NET_DEV_PASSWORD}" \
        ssh ${SSH_OPTS} "${USER}@${HOST}" \
        "/usr/local/share/cloudstack/network-extension-wrapper.sh $*"
fi
```

**Example: REST API device (Python)**

```python
#!/usr/bin/env python3
# Entry-point script for a network appliance with a REST API.
import os, sys, requests

HOST    = os.environ["CS_NET_DEV_HOST"]
API_KEY = os.environ.get("CS_NET_DEV_PASSWORD", "")
command = sys.argv[1] if len(sys.argv) > 1 else ""
args    = sys.argv[2:]

base_url = f"https://{HOST}/api/v1"
headers  = {"Authorization": f"Bearer {API_KEY}"}

if command == "implement":
    network_id = next((args[i+1] for i, a in enumerate(args) if a == "--network-id"), None)
    r = requests.post(f"{base_url}/networks", json={"id": network_id}, headers=headers)
    r.raise_for_status()
elif command == "destroy":
    network_id = next((args[i+1] for i, a in enumerate(args) if a == "--network-id"), None)
    r = requests.delete(f"{base_url}/networks/{network_id}", headers=headers)
    r.raise_for_status()
# … handle other commands …
```

### Step 3 — Register the Extension with a Physical Network

Registering the extension **automatically**:
- Creates a **network service provider** named after the extension on the physical network
- Sets the provider's supported-service flags from `network.capabilities`
- Sets the provider state to **Enabled** (so services are immediately visible when creating a network offering)

No separate `addNetworkServiceProvider` call is needed.

```bash
cmk registerExtension \
  id=<ext-uuid-my-linux-router> \
  resourcetype=PhysicalNetwork \
  resourceid=<phys-net-uuid> \
  details[0].key=host      details[0].value=192.168.100.10 \
  details[1].key=port      details[1].value=22 \
  details[2].key=username  details[2].value=root \
  details[3].key=sshkey    details[3].value="$(cat /root/.ssh/id_rsa)"
```

> - Sensitive keys (`password`, `sshkey`) are stored with `display=false` and are
>   **never** returned in API list responses, but are injected into the script
>   environment on every call.
> - Any other `details` key is injected as `CS_NET_<KEY>` (upper-cased).
> - **The same extension cannot be registered twice on the same physical network.**
>   To use the same script for two separate device instances on the same physical
>   network, create two extensions with different names and the same `path`.

To temporarily disable the provider (services will be hidden from network offering creation):
```bash
cmk updateNetworkServiceProvider id=<nsp-uuid> state=Disabled
```

Or use the UI: **Infrastructure → Physical Networks → [network] → Network Service
Providers**, select the extension tab.  Enable/Disable/Shutdown buttons appear on
the tab, identical to built-in providers like VirtualRouter.

Unregistering the extension also removes the network service provider:
```bash
cmk unregisterExtension id=<ext-uuid> resourcetype=PhysicalNetwork resourceid=<phys-net-uuid>
```

### Step 4 — Create a Network Offering

Select the registered extension as a service provider.  The provider name **must
exactly match the extension name** (case-sensitive).

```bash
cmk createNetworkOffering \
  name="Linux Router Offering" \
  guestiptype=Isolated \
  traffictype=Guest \
  supportedservices="SourceNat,StaticNat,PortForwarding,Firewall,Dns,Dhcp" \
  serviceProviderList[0].service=SourceNat       serviceProviderList[0].provider="my-linux-router" \
  serviceProviderList[1].service=StaticNat       serviceProviderList[1].provider="my-linux-router" \
  serviceProviderList[2].service=PortForwarding  serviceProviderList[2].provider="my-linux-router" \
  serviceProviderList[3].service=Firewall        serviceProviderList[3].provider="my-linux-router" \
  serviceProviderList[4].service=Dns             serviceProviderList[4].provider=VirtualRouter \
  serviceProviderList[5].service=Dhcp            serviceProviderList[5].provider=VirtualRouter
```

In the **UI**, the *Create Network Offering* provider dropdown lists only registered
external network service providers (extensions whose name matches an enabled NSP).
A generic "NetworkExtension" entry is **not** shown — only actual registered
extension names appear.

### Step 5 — Create a Network

```bash
cmk createNetwork \
  name="my-network" \
  networkofferingid=<offering-uuid> \
  zoneid=<zone-uuid>
```

CloudStack executes `entry-point implement ...`.  The script connects to the device
and creates the network (e.g. creates a Linux namespace, configures a bridge and
gateway, sets up source NAT via iptables, or calls a REST API).

---

## Network Lifecycle

| Event | Script command | What the script should do |
|---|---|---|
| Network created / restarted | `implement` | Create namespace/bridge, configure routing and source NAT |
| Public IP assigned | `assign-ip` | Bind the public IP on the device |
| Public IP released | `release-ip` | Remove the public IP from the device |
| Static NAT enabled | `add-static-nat` | Add 1-to-1 NAT rule |
| Static NAT disabled | `delete-static-nat` | Remove 1-to-1 NAT rule |
| Port forwarding added | `add-port-forward` | Add DNAT rule |
| Port forwarding removed | `delete-port-forward` | Remove DNAT rule |
| Network stopped | `shutdown` | Remove NAT rules, keep state |
| Network deleted | `destroy` | Remove namespace/config, clean all state |

---

## Custom Actions on Networks

Custom actions allow operators to trigger ad-hoc operations on the external device
managing a network (e.g. reload device configuration, dump interface state).

```bash
# Define a custom action for an extension
cmk addCustomAction \
  extensionid=<ext-uuid> \
  name="dump-config" \
  description="Dump iptables rules and interface state" \
  resourcetype=Network

# Run the action on a specific network
cmk runCustomAction \
  customactionid=<action-uuid> \
  resourcetype=Network \
  resourceid=<network-uuid>
```

In the UI, a **Run Action** button (⚡) appears on the guest network detail page
when the network's provider is an extension-backed external network provider.

---

## Environment Variables Reference

### Device Credentials (from `extension_resource_map_details`)

These are stored via `registerExtension details[n].key=...` and injected into the
entry-point script on every call:

| Variable | `details` key | Description |
|---|---|---|
| `CS_NET_DEV_HOST` | `host` | IP or hostname of the external device |
| `CS_NET_DEV_PORT` | `port` | SSH / API port (default `22`) |
| `CS_NET_DEV_USERNAME` | `username` | SSH username or API user |
| `CS_NET_DEV_PASSWORD` | `password` | SSH password or API token (sensitive, never logged) |
| `CS_NET_DEV_SSHKEY` | `sshkey` | SSH private key PEM (sensitive, never logged) |

Any other key stored via `registerExtension details[n].key=...` is injected as
`CS_NET_<KEY>` (upper-cased, e.g. `namespace` → `CS_NET_NAMESPACE`).

### Per-Network Details (from `network_details`)

Keys starting with `ext.` are injected as `CS_NET_EXT_<KEY>` (e.g.
`ext.vrf` → `CS_NET_EXT_VRF`).

### Custom Action Parameters

Parameters passed via `runCustomAction parameters[n].key=...` are injected as
`CS_ACTION_PARAM_<KEY>` (upper-cased).

---

## Database Tables

| Table | Purpose |
|---|---|
| `extension` | Extension definitions (name, type, path, state) |
| `extension_details` | Extension-level settings, e.g. `network.capabilities` JSON |
| `extension_resource_map` | Links extension → physical network; NSP is created with the extension name |
| `extension_resource_map_details` | Device credentials per registration (populated by `registerExtension details`) |
| `extension_custom_action` | Custom action definitions per extension |
| `ntwk_service_map` | Maps network → service → provider name (= extension name) |
| `network_offering_service_map` | Maps network offering → service → provider name |

---

## Troubleshooting

**"No NetworkOrchestrator extension found for network X"**  
→ Confirm the provider name in the network offering matches the extension name
exactly (case-sensitive).  
→ Check `cmk listExtensions`, `extension_resource_map`, and `ntwk_service_map`.

**Script exits non-zero**  
→ Check management server logs for `Network extension script failed` — the script's
stdout/stderr is included in the log.  
→ Run the entry-point script manually on the management server to debug:
```bash
CS_NET_DEV_HOST=192.168.100.10 CS_NET_DEV_USERNAME=root \
  /etc/cloudstack/extensions/my-linux-router/entry-point \
  implement --network-id 42 --vlan 100 --gateway 10.0.0.1 --cidr 10.0.0.0/24
```

**Credentials not injected / script cannot connect**  
→ Sensitive keys (`password`, `sshkey`) are stored with `display=false` and will
not appear in list API responses, but they **are** injected into the entry-point
script environment.  To update credentials, unregister and re-register the
extension with the corrected `details`.

**Provider not showing in network offering creation UI**  
→ The dropdown only shows extensions whose name matches a registered and enabled
network service provider.  Make sure `registerExtension` was called with
`resourcetype=PhysicalNetwork` and the NSP is in `Enabled` state.

**"Extension already registered" error**  
→ An extension can only be registered **once** per physical network.  To use the
same entry-point script a second time on the same physical network, create a new
extension with a different name but the same `path`.
