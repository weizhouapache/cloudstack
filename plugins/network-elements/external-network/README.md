# External Network Provider Plugin

This plugin allows CloudStack to delegate guest network operations (create/delete
network, source NAT, static NAT, port forwarding, firewall) to an **external network
device** via a user-supplied **wrapper script** installed on the management server.

---

## Supported External Devices

The external network device can be any device that supports remote management:

| Device type | Transport | Status |
|---|---|---|
| Linux server (namespace/bridge/iptables) | SSH | ✅ Implemented |
| Network appliance / router / firewall | SSH | 🔧 Custom script required |
| Network appliance / SDN controller | HTTP API | 🔧 Custom script required |

Currently the reference implementation (`external-network.sh`) targets a **Linux
server** using network namespaces, bridges, and iptables.  Support for other device
types (e.g. a hardware firewall or an SDN controller with a REST API) can be added
by writing a custom wrapper script — no Java code changes are needed.

---

## How It Works

```
CloudStack Management Server
  │
  │  Calls wrapper script (installed locally on mgmt server)
  │  Passes device credentials as environment variables
  │
  └─► entry-point <command> [options]
           │
           │  Connects to the external device (SSH or HTTP API)
           │
           └─► External Network Device
                  └─► Performs the network operation
                       (create namespace, configure iptables, call REST API, …)
```

1. **CloudStack Management Server** calls the wrapper script (`entry-point`) for
   every network lifecycle event: create network, delete network, assign public IP,
   add static NAT rule, add port forwarding rule, etc.

2. **The wrapper script** is installed on the management server under
   `/etc/cloudstack/extensions/<extension-name>/entry-point`.  
   It is responsible for connecting to the external device and performing the
   requested operation.  It receives all necessary context as environment variables
   (see [Environment Variables](#environment-variables-reference)).

3. **The external device** performs the actual network configuration.  For a Linux
   server this means creating a network namespace, configuring bridges and iptables
   rules, etc.  For a hardware appliance it might mean calling a REST API.

4. **Device credentials** (`host`, `port`, `username`, `password` or `sshkey`) are
   stored securely in the CloudStack database and are injected into the wrapper
   script's environment on every call.  The script uses them to authenticate to the
   external device — they are never hard-coded in the script.

---

## Wrapper Script Operations

CloudStack calls the wrapper script with the following commands.  The script must
handle all of them:

| Command | Trigger | Description |
|---|---|---|
| `implement` | Network created / started | Create the network on the external device (e.g. create namespace, configure bridge, set up default routing) |
| `shutdown` | Network stopped | Tear down the network, remove NAT rules |
| `destroy` | Network deleted | Remove all state for the network on the device |
| `assign-ip` | Public IP acquired | Notify the device of a new public IP |
| `release-ip` | Public IP released | Remove the public IP from the device |
| `add-static-nat` | Static NAT enabled | Create a 1-to-1 NAT mapping |
| `delete-static-nat` | Static NAT disabled | Remove the 1-to-1 NAT mapping |
| `add-port-forward` | Port forwarding rule added | Create a DNAT rule |
| `delete-port-forward` | Port forwarding rule removed | Remove the DNAT rule |
| `custom-action` | `runCustomAction` API | Run an operator-defined ad-hoc action |

The wrapper script can be written in **any language** — it just needs to be
executable on the management server:

- Shell script (`#!/bin/bash`) — simplest, no extra dependencies
- Python script (`#!/usr/bin/env python3`) — useful when calling HTTP APIs
- Any other executable binary or interpreter

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────┐
│               CloudStack Management Server           │
│                                                      │
│  ExternalNetworkElement.java                         │
│    │                                                 │
│    │ reads device credentials from DB                │
│    │ injects them as CS_NET_DEV_* env vars           │
│    ▼                                                 │
│  /etc/cloudstack/extensions/                         │
│    └── <extension-name>/                             │
│          └── entry-point   ◄── wrapper script        │
└──────────────────┬──────────────────────────────────┘
                   │ SSH / HTTP API
                   ▼
         ┌─────────────────────┐
         │  External Device    │
         │  (Linux server,     │
         │   firewall, SDN…)   │
         └─────────────────────┘
```

---

## Multi-Extension Support

A single physical network can have **multiple different** NetworkOrchestrator
extensions registered, each serving a different set of guest networks.  For example:

- `my-linux-router` — handles networks that use a Linux server for NAT/routing
- `my-firewall` — handles networks that use a hardware firewall appliance

When CloudStack needs to operate on a network it determines the correct extension by:

1. Reading the network's service-map (`ntwk_service_map`) to find the provider names
   assigned to that network.
2. Matching each provider name against the **extension names** registered on the
   physical network in `extension_resource_map`.
3. Using the matching extension's wrapper script and device credentials.

> **The extension name must exactly match the network service provider name.**

---

## Setup

### Step 1 — Create a NetworkOrchestrator Extension

The extension defines the wrapper script path and declares which network services
it can provide.

```bash
cmk createExtension \
  name="my-linux-router" \
  type=NetworkOrchestrator \
  path="my-linux-router/entry-point" \
  details[0].key=network.capabilities \
  details[0].value='{"services":["SourceNat","StaticNat","PortForwarding","Firewall","Gateway"],"capabilities":{"SourceNat":{"SupportedSourceNatTypes":"peraccount","RedundantRouter":"false"}}}'
```

The `network.capabilities` detail key is defined as
`ExtensionHelper.NETWORK_CAPABILITIES_DETAIL_KEY` in the Java codebase.
It holds a JSON object that declares which services the extension provides
and their capabilities.  If omitted, all default services are available
(SourceNat, StaticNat, PortForwarding, Firewall, Gateway).

To register a second extension on the same physical network (e.g. for a different
device type):

```bash
cmk createExtension \
  name="my-firewall" \
  type=NetworkOrchestrator \
  path="my-firewall/entry-point" \
  details[0].key=network.capabilities \
  details[0].value='{"services":["Firewall","PortForwarding"]}'
```

> **Note:** `createExtension` only registers the extension definition in the
> database.  The wrapper script must be installed on the management server
> separately (see Step 2).

### Step 2 — Install the Wrapper Script on the Management Server

Place the executable wrapper script at:
```
/etc/cloudstack/extensions/<extension-name>/entry-point
```

Example for `my-linux-router` (SSH-based Linux server):

```bash
#!/bin/bash
# Wrapper script: entry-point
# Installed on the CloudStack management server.
# Called by CloudStack for every network operation.
#
# Device credentials are injected as environment variables by CloudStack:
#   CS_NET_DEV_HOST      - IP/hostname of the external Linux server
#   CS_NET_DEV_PORT      - SSH port (default: 22)
#   CS_NET_DEV_USERNAME  - SSH username
#   CS_NET_DEV_PASSWORD  - SSH password  (if using password auth)
#   CS_NET_DEV_SSHKEY    - SSH private key PEM  (if using key auth)
#   CS_NET_*             - any other details registered with the extension
#
# All arguments ($@) are forwarded verbatim to the remote script.

HOST="${CS_NET_DEV_HOST}"
PORT="${CS_NET_DEV_PORT:-22}"
USER="${CS_NET_DEV_USERNAME:-root}"
SSH_OPTS="-p ${PORT} -o StrictHostKeyChecking=no -o BatchMode=yes"

if [ -n "${CS_NET_DEV_SSHKEY}" ]; then
    KEYFILE=$(mktemp)
    printf '%s' "${CS_NET_DEV_SSHKEY}" > "${KEYFILE}"
    chmod 600 "${KEYFILE}"
    ssh -i "${KEYFILE}" ${SSH_OPTS} "${USER}@${HOST}" \
        "/opt/cloudstack/external-network.sh $*"
    RC=$?
    rm -f "${KEYFILE}"
    exit ${RC}
else
    sshpass -p "${CS_NET_DEV_PASSWORD}" \
        ssh ${SSH_OPTS} "${USER}@${HOST}" \
        "/opt/cloudstack/external-network.sh $*"
fi
```

Example for an HTTP API device (Python):

```python
#!/usr/bin/env python3
# Wrapper script for a network appliance with a REST API.
# Device credentials are in CS_NET_DEV_HOST, CS_NET_DEV_PASSWORD, etc.
import os, sys, requests

HOST     = os.environ["CS_NET_DEV_HOST"]
API_KEY  = os.environ.get("CS_NET_DEV_PASSWORD", "")
command  = sys.argv[1] if len(sys.argv) > 1 else ""
args     = sys.argv[2:]

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

Registering the extension creates a **network service provider** on the physical
network named after the extension.  Use `registerExtension` with
`resourcetype=PhysicalNetwork` and `resourceid=<phys-net-uuid>`.

```bash
# Register my-linux-router — stores device credentials for later retrieval
cmk registerExtension \
  id=<ext-uuid-my-linux-router> \
  resourcetype=PhysicalNetwork \
  resourceid=<phys-net-uuid>
```

> After registering, the extension appears as a network service provider in the
> physical network.  Enable it via the UI or:
> ```bash
> cmk updateNetworkServiceProvider id=<nsp-uuid> state=Enabled
> ```

### Step 4 — Add the External Network Device

Store the device connection credentials (host, port, username, password or SSH key)
in the database via `addExternalNetworkDevice`.  These are passed as environment
variables to the wrapper script on every call.

```bash
# Add device credentials for my-linux-router (SSH key auth)
cmk addExternalNetworkDevice \
  physicalnetworkid=<phys-net-uuid> \
  host=192.168.100.10 \
  port=22 \
  details[0].key=username  details[0].value=root \
  details[1].key=sshkey    details[1].value="$(cat /root/.ssh/id_rsa)"

# Add device credentials for my-firewall (password auth)
cmk addExternalNetworkDevice \
  physicalnetworkid=<phys-net-uuid> \
  host=192.168.100.20 \
  port=22 \
  details[0].key=username  details[0].value=admin \
  details[1].key=password  details[1].value=secret
```

> Sensitive fields (`password`, `sshkey`) are stored with `display=false` and are
> **never** returned by `listExternalNetworkDevices`.

To list, update, or remove device credentials:

```bash
# List devices
cmk listExternalNetworkDevices physicalnetworkid=<phys-net-uuid>

# Update a credential (e.g. rotate SSH key)
cmk updateExternalNetworkDevice \
  physicalnetworkid=<phys-net-uuid> \
  details[0].key=sshkey details[0].value="$(cat /root/.ssh/new_key)"

# Remove all device credentials (does NOT unregister the extension)
cmk deleteExternalNetworkDevice physicalnetworkid=<phys-net-uuid>
```

### Step 5 — Create a Network Offering

When creating a network offering, select the service providers from the network
service providers that are now registered on the physical network.  The provider
name in the offering **must match the extension name** so CloudStack can route
operations to the correct wrapper script.

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

In the UI, the **Create Network Offering** dialog lists all service providers
registered on guest physical networks, including your external network providers.

### Step 6 — Create a Network

```bash
cmk createNetwork \
  name="my-network" \
  networkofferingid=<offering-uuid> \
  zoneid=<zone-uuid>
```

When this network is created, CloudStack calls the `entry-point` wrapper script
with the `implement` command.  The wrapper connects to the external device and
creates the network there (e.g. creates a Linux namespace, sets up a bridge and
gateway, configures source NAT).

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

Custom actions allow operators to run ad-hoc operations on the external device
managing a network (e.g. reboot the device, dump its current configuration).

### Register a Custom Action

```bash
cmk addCustomAction \
  extensionid=<ext-uuid-my-linux-router> \
  name="reboot-device" \
  description="Bounce bridge interfaces for this network" \
  resourcetype=Network

cmk addCustomAction \
  extensionid=<ext-uuid-my-linux-router> \
  name="dump-config" \
  description="Dump iptables rules and interface state" \
  resourcetype=Network
```

### Run a Custom Action

```bash
cmk runCustomAction \
  customactionid=<action-uuid> \
  resourcetype=Network \
  resourceid=<network-uuid>
```

### List Available Actions for a Network

```bash
cmk listCustomActions resourcetype=Network resourceid=<network-uuid> enabled=true
```

### UI

On the guest network detail page, a **Run Action** button (⚡) appears when the
network's provider is an external network extension.  It opens a dialog listing all
enabled custom actions for that extension.

### Built-in Actions (Linux server script)

| Action | Description |
|---|---|
| `reboot-device` | Brings the network's bridge interface down and back up |
| `dump-config` | Dumps iptables NAT + FILTER rules and bridge/interface state |

To add custom actions, place an executable hook script at:
```
<STATE_DIR>/hooks/custom-action-<name>.sh
```

### Custom Action Parameters

```bash
cmk runCustomAction \
  customactionid=<action-uuid> \
  resourcetype=Network \
  resourceid=<network-uuid> \
  parameters[0].key=timeout \
  parameters[0].value=30
```

Parameters are injected into the wrapper script as `CS_ACTION_PARAM_<KEY>`
environment variables.

---

## Environment Variables Reference

### Device Credentials (injected from `extension_resource_map_details`)

These are stored when adding the external network device (step 4) and are injected
into the wrapper script on every call.

| Variable | Source key | Description |
|---|---|---|
| `CS_NET_DEV_HOST` | `host` | IP or hostname of the external device |
| `CS_NET_DEV_PORT` | `port` | SSH port or API port (default `22`) |
| `CS_NET_DEV_USERNAME` | `username` | SSH username or API user |
| `CS_NET_DEV_PASSWORD` | `password` | SSH password or API key/token (sensitive, not logged) |
| `CS_NET_DEV_SSHKEY` | `sshkey` | SSH private key PEM (sensitive, not logged) |

Any other key stored via `addExternalNetworkDevice details[n].key=...` is injected
as `CS_NET_<KEY>` (upper-cased).

### Per-Network Details (from `network_details`)

Keys starting with `ext.` are injected as `CS_NET_EXT_<KEY>`:

| Variable | Source key | Description |
|---|---|---|
| `CS_NET_EXT_NAMESPACE` | `ext.namespace` | Linux network namespace name |
| `CS_NET_EXT_VRF` | `ext.vrf` | VRF name |
| `CS_NET_EXT_BRIDGE` | `ext.bridge` | Bridge name |

### Custom Action Parameters

| Variable | Description |
|---|---|
| `CS_ACTION_PARAM_<KEY>` | Caller-supplied parameter, key upper-cased |

---

## How Extension Resolution Works (Multi-Extension)

When CloudStack needs to operate on a network, it determines which wrapper script
to call and which credentials to use by matching the network's provider name to a
registered extension:

```
Network N → serviceProviderList.provider = "my-linux-router"
                      │
                      ▼
            ntwk_service_map.provider = "my-linux-router"
                      │
                      ▼
    getExtensionForPhysicalNetworkAndProvider(physNetId, "my-linux-router")
                      │
                      ▼
    extension_resource_map WHERE physical_network_id = physNetId
      → extension WHERE name = "my-linux-router"  ✓
                      │
          ┌───────────┴───────────┐
          ▼                       ▼
    Script path              Device credentials
    (entry-point for         (host/port/sshkey for
     my-linux-router)         my-linux-router only,
                              NOT for my-firewall)
```

If no provider name matches any registered extension name, the first registered
extension is used as a fallback (with a warning in the logs).

---

## Database Tables

| Table | Purpose |
|---|---|
| `extension` | Extension definitions: name, type, wrapper script path, enabled/disabled state |
| `extension_details` | Extension-level settings, e.g. `network.capabilities` JSON (key constant: `ExtensionHelper.NETWORK_CAPABILITIES_DETAIL_KEY`) |
| `extension_resource_map` | Links an extension to a physical network (creates the network service provider) |
| `extension_resource_map_details` | Device credentials and settings per registration (host, port, username, password/sshkey) — populated by `addExternalNetworkDevice` |
| `extension_custom_action` | Custom action definitions (name, description, parameters) per extension |
| `ntwk_service_map` | Maps network ID → service → provider name (used for extension resolution) |
| `network_offering_service_map` | Maps network offering → service → provider name |

---

## Troubleshooting

**"No NetworkOrchestrator extension found for network X"**  
→ Either no extension is registered on the physical network, or none has a name
matching the network's provider.  
Check: `cmk listExtensions`, verify `extension_resource_map` and `ntwk_service_map`
in the database, and confirm the provider name in the network offering matches the
extension name exactly.

**Script exits non-zero**  
→ Check management server logs for `External network script failed` messages —
the script's stdout is included.  Run the wrapper script manually on the management
server to debug, e.g.:
```bash
CS_NET_DEV_HOST=192.168.100.10 CS_NET_DEV_USERNAME=root \
  /etc/cloudstack/extensions/my-linux-router/entry-point \
  implement --network-id 42 --vlan 100 --gateway 10.0.0.1 --cidr 10.0.0.0/24
```

**Credentials not injected / script cannot connect**  
→ Sensitive keys (`password`, `sshkey`) are stored with `display=false` and will
not appear in `listExternalNetworkDevices` output, but they are injected into the
wrapper script environment on every call.  
Update credentials using `updateExternalNetworkDevice`:
```bash
cmk updateExternalNetworkDevice \
  physicalnetworkid=<phys-net-uuid> \
  details[0].key=sshkey details[0].value="$(cat /root/.ssh/id_rsa)"
```

**Wrong extension called for a network**  
→ Check that the `provider` name in `createNetworkOffering` exactly matches (same
case) the extension `name` used in `createExtension` and `registerExtension`.

**Network service provider not showing in network offering creation**  
→ Ensure the extension is registered with the correct physical network
(`registerExtension resourcetype=PhysicalNetwork`) and the network service provider
is in `Enabled` state (`cmk updateNetworkServiceProvider id=<nsp-uuid> state=Enabled`).

