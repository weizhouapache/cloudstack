# NetworkExtension for Apache CloudStack

This directory contains the **NetworkExtension** `NetworkOrchestrator` extension —
a CloudStack plugin that delegates all network operations to an external device
over SSH.  The device can be a Linux server (using network namespaces,
bridges, and iptables), a network appliance that accepts SSH commands, or any
other host that can run the `network-extension-wrapper.sh` (or a compatible
script) to perform network configurations.

The extension is implemented in
`framework/extensions/src/main/java/org/apache/cloudstack/framework/extensions/network/NetworkExtensionElement.java`
and loaded automatically by the management server — **no separate plugin JAR is
required**.

---

## Table of Contents

1. [Architecture](#architecture)
2. [Directory contents](#directory-contents)
3. [How it works](#how-it-works)
4. [Installation](#installation)
   - [Management server](#management-server)
   - [Remote network device](#remote-network-device)
5. [Step-by-step API setup](#step-by-step-api-setup)
   - [1. Create the extension](#1-create-the-extension)
   - [2. Register the extension with a physical network](#2-register-the-extension-with-a-physical-network)
   - [3. Add external network device credentials](#3-add-external-network-device-credentials)
   - [4. Create a network offering](#4-create-a-network-offering)
   - [5. Create an isolated network](#5-create-an-isolated-network)
   - [6. Acquire a public IP and enable Source NAT](#6-acquire-a-public-ip-and-enable-source-nat)
   - [7. Enable / disable Static NAT](#7-enable--disable-static-nat)
   - [8. Add / delete Port Forwarding](#8-add--delete-port-forwarding)
   - [9. Delete the network](#9-delete-the-network)
   - [10. Unregister and delete the extension](#10-unregister-and-delete-the-extension)
6. [Multiple extensions on the same physical network](#multiple-extensions-on-the-same-physical-network)
7. [Wrapper script operations reference](#wrapper-script-operations-reference)
8. [Environment variable reference](#environment-variable-reference)
9. [Custom actions](#custom-actions)
10. [Developer / testing notes](#developer--testing-notes)

---

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│  CloudStack Management Server                            │
│                                                          │
│  NetworkExtensionElement.java                            │
│      │ executes (path resolved from Extension record)    │
│      ▼                                                   │
│  /etc/cloudstack/extensions/<ext-name>/entry-point       │
│  (this directory, deployed during installation)          │
└──────────────────────┬───────────────────────────────────┘
                       │ SSH (CS_NET_DEV_HOST : CS_NET_DEV_PORT)
                       │ credentials from extension_resource_map_details
                       ▼
┌──────────────────────────────────────────────────────────┐
│  Remote Network Device  (Linux server / appliance)       │
│                                                          │
│  ip netns exec <CS_NET_NAMESPACE>                        │
│      network-extension-wrapper.sh <command> [args...]    │
│                                                          │
│  Operations performed inside the namespace:              │
│    • VLAN sub-interface + Linux bridge creation          │
│    • iptables SNAT  (source NAT / masquerade)            │
│    • iptables DNAT  (static NAT, port forwarding)        │
│    • iptables FORWARD rules                              │
└──────────────────────────────────────────────────────────┘
```

**Key design principles:**

* The `entry-point` script runs on the **management server**.  All connection
  details (`host`, `port`, `username`, `sshkey`, `namespace`, `script_path`)
  are passed as environment variables injected by `NetworkExtensionElement` — the
  script itself is completely generic and requires no local configuration.
* The `network-extension-wrapper.sh` script runs on the **remote device** inside
  a network namespace.  It performs the actual iptables and bridge operations.
* The two scripts are intentionally decoupled: you can replace either script
  with a custom implementation (Python, Go, etc.) as long as the interface
  contract (arguments and exit codes) is maintained.  The script can be any
  executable — shell script, Python script, compiled binary, etc.
* The **extension name** is used as the **Network Service Provider (NSP) name**.
  This means multiple different NetworkExtension extensions can be registered to
  the same physical network, each appearing as its own named provider.

---

## Directory contents

| File | Installed location | Purpose |
|------|--------------------|---------|
| `entry-point` | management server | SSH proxy — executed by `NetworkExtensionElement` |
| `network-extension-wrapper.sh` | remote network device | Performs iptables / bridge operations |
| `README.md` | — | This documentation |

> **Source tree paths:**
> * `entry-point` → `extensions/network-extension/entry-point`
> * `network-extension-wrapper.sh` → `extensions/network-extension/network-extension-wrapper.sh`
>   *(also packaged at `framework/extensions/src/main/resources/scripts/network-extension-wrapper.sh`)*

---

## How it works

### Lifecycle of a CloudStack network operation

1. **CloudStack** decides that a network operation must be applied (e.g.
   `implement`, `addStaticNat`, `applyPortForwardingRules`).
2. **`NetworkExtensionElement`** (Java) resolves the extension that is registered
   on the physical network whose name matches the network's service provider.  It
   reads the device details (`host`, `port`, `username`, `sshkey`, `namespace`,
   `script_path`) stored in `extension_resource_map_details`.
3. `NetworkExtensionElement` builds a command line:
   ```
   <extension_path>/entry-point <command> --network-id <id> [--vlan V] [--gateway G] ...
   ```
   and injects all device details as `CS_NET_DEV_*` and `CS_NET_*` environment
   variables into the process.
4. **`entry-point`** reads those environment variables, writes the SSH private key
   to a temporary file (if `CS_NET_DEV_SSHKEY` is set), then SSHes to the remote
   host and runs:
   ```bash
   ip netns exec <CS_NET_NAMESPACE>  \
       <CS_NET_SCRIPT_PATH> <command> [arguments...]
   ```
5. **`network-extension-wrapper.sh`** executes the requested operation using
   `ip link`, `iptables`, `ip addr`, etc. inside the network namespace.
6. Exit code `0` = success; any non-zero exit causes CloudStack to treat the
   operation as failed.

### Authentication priority (entry-point)

1. `CS_NET_DEV_SSHKEY` — PEM key written to a temp file, used with `ssh -i`.
   **Preferred** — the temp file is deleted on exit.
2. `CS_NET_DEV_PASSWORD` — passed to `sshpass(1)` if available.
3. Neither set — relies on the SSH agent or host key on the management server.

---

## Installation

### Management server

During package installation the `entry-point` script is deployed to:

```
/etc/cloudstack/extensions/<extension-name>/entry-point
```

In **developer mode** the extensions directory defaults to `extensions/` relative
to the repo root working directory, so `extensions/network-extension/entry-point`
is found automatically when `path=network-extension/entry-point` is set at
extension creation time.

### Remote network device

Copy `network-extension-wrapper.sh` to each remote device that will act as the
network gateway:

```bash
# From the CloudStack source tree:
scp extensions/network-extension/network-extension-wrapper.sh \
    root@<device>:/usr/local/share/cloudstack/network-extension-wrapper.sh
chmod +x /usr/local/share/cloudstack/network-extension-wrapper.sh
```

The default path expected by `entry-point` is
`/usr/local/share/cloudstack/network-extension-wrapper.sh`.
You can override this per-physical-network by passing a `script_path` detail
when calling `addExternalNetworkDevice` (see below).

**Prerequisites on the remote device:**
* `iproute2` (`ip link`, `ip addr`, `ip netns`)
* `iptables`
* `sshd` running and reachable from the management server
* The SSH user must have permission to run `ip` and `iptables` (root or `sudo`)

---

## Step-by-step API setup

All examples below use `cmk` (the CloudStack CLI).  Replace `<zone-uuid>`,
`<phys-net-uuid>`, etc. with real values from your environment.

### 1. Create the extension

```bash
cmk createExtension \
    name=my-extnet \
    type=NetworkOrchestrator \
    path=network-extension/entry-point \
    "details[0].key=network.capabilities" \
    "details[0].value={\"services\":[\"SourceNat\",\"StaticNat\",\"PortForwarding\",\"Firewall\",\"Gateway\"],\"capabilities\":{\"SourceNat\":{\"SupportedSourceNatTypes\":\"peraccount\",\"RedundantRouter\":\"false\"},\"Firewall\":{\"TrafficStatistics\":\"per public ip\"}}}"
```

The `network.capabilities` detail declares which services this extension provides
and their CloudStack capability values.  These are consulted when listing network
service providers and when validating network offerings.

**`network.capabilities` JSON format:**
```json
{
  "services": ["SourceNat", "StaticNat", "PortForwarding", "Firewall", "Gateway"],
  "capabilities": {
    "SourceNat": {
      "SupportedSourceNatTypes": "peraccount",
      "RedundantRouter": "false"
    },
    "Firewall": {
      "TrafficStatistics": "per public ip"
    }
  }
}
```

Services not listed in `capabilities` (e.g. `StaticNat`, `PortForwarding`,
`Gateway`) are still offered — CloudStack treats missing capability values as
"no constraint" and accepts any value when creating the network offering.

If you omit the `network.capabilities` detail entirely, the extension defaults
to all five services with `SourceNat.SupportedSourceNatTypes=peraccount`.

Verify the extension was created and its state is `Enabled`:
```bash
cmk listExtensions name=my-extnet
```

To enable or disable the extension:
```bash
cmk updateExtension id=<ext-uuid> state=Enabled
cmk updateExtension id=<ext-uuid> state=Disabled
```

### 2. Register the extension with a physical network

```bash
cmk registerExtension \
    id=<extension-uuid> \
    resourcetype=PhysicalNetwork \
    resourceid=<phys-net-uuid>
```

This creates a **Network Service Provider** (NSP) entry named `my-extnet` on the
physical network and enables it automatically.  The NSP name is the **extension
name** — not the generic string `NetworkExtension`.

Verify:
```bash
cmk listNetworkServiceProviders physicalnetworkid=<phys-net-uuid>
# → a provider named "my-extnet" should appear in state Enabled
```

To disable or re-enable the NSP:
```bash
cmk updateNetworkServiceProvider id=<nsp-uuid> state=Disabled
cmk updateNetworkServiceProvider id=<nsp-uuid> state=Enabled
```

To unregister:
```bash
cmk unregisterExtension \
    id=<extension-uuid> \
    resourcetype=PhysicalNetwork \
    resourceid=<phys-net-uuid>
```

### 3. Add external network device credentials

Store the connection details for the remote device.  These become environment
variables passed to `entry-point` at runtime:

```bash
cmk addExternalNetworkDevice \
    physicalnetworkid=<phys-net-uuid> \
    host=192.168.1.50 \
    port=22 \
    "details[0].key=username"    "details[0].value=root" \
    "details[1].key=sshkey"      "details[1].value=$(cat /root/.ssh/id_rsa)" \
    "details[2].key=namespace"   "details[2].value=cs-net-prod" \
    "details[3].key=script_path" "details[3].value=/usr/local/share/cloudstack/network-extension-wrapper.sh"
```

> **Security note:** `sshkey` (and `password`) are stored with `display=false`
> and are **never** returned by `listExternalNetworkDevices`.  All other keys
> (`host`, `port`, `username`, `namespace`, `script_path`) are visible in list
> responses.

| Detail key | Environment variable | Notes |
|---|---|---|
| `host` (top-level param) | `CS_NET_DEV_HOST` | Required |
| `port` (top-level param) | `CS_NET_DEV_PORT` | Default: 22 |
| `username` | `CS_NET_DEV_USERNAME` | Default: root |
| `password` | `CS_NET_DEV_PASSWORD` | Via `sshpass`; not logged |
| `sshkey` | `CS_NET_DEV_SSHKEY` | PEM; not logged; preferred over password |
| `namespace` | `CS_NET_NAMESPACE` | Linux netns name on remote host |
| `script_path` | `CS_NET_SCRIPT_PATH` | Full path on remote host |

List devices (sensitive fields are hidden):
```bash
cmk listExternalNetworkDevices physicalnetworkid=<phys-net-uuid>
```

Update device details (e.g. change namespace):
```bash
cmk updateExternalNetworkDevice \
    physicalnetworkid=<phys-net-uuid> \
    "details[0].key=namespace" "details[0].value=cs-net-new"
```

Delete device:
```bash
cmk deleteExternalNetworkDevice physicalnetworkid=<phys-net-uuid>
```

### 4. Create a network offering

Use the **extension name** (`my-extnet`) as the service provider — not the
generic string `NetworkExtension`:

```bash
cmk createNetworkOffering \
    name="My ExtNet Offering" \
    displaytext="Isolated network via my-extnet" \
    guestiptype=Isolated \
    traffictype=GUEST \
    supportedservices="SourceNat,StaticNat,PortForwarding,Firewall,Gateway" \
    "serviceProviderList[0].service=SourceNat"      "serviceProviderList[0].provider=my-extnet" \
    "serviceProviderList[1].service=StaticNat"      "serviceProviderList[1].provider=my-extnet" \
    "serviceProviderList[2].service=PortForwarding" "serviceProviderList[2].provider=my-extnet" \
    "serviceProviderList[3].service=Firewall"       "serviceProviderList[3].provider=my-extnet" \
    "serviceProviderList[4].service=Gateway"        "serviceProviderList[4].provider=my-extnet" \
    "serviceCapabilityList[0].service=SourceNat" \
    "serviceCapabilityList[0].capabilitytype=SupportedSourceNatTypes" \
    "serviceCapabilityList[0].capabilityvalue=peraccount"
```

Enable the offering:
```bash
cmk updateNetworkOffering id=<offering-uuid> state=Enabled
```

> The `serviceCapabilityList` entries must match the values declared in the
> extension's `network.capabilities` detail.  If the extension's JSON does not
> declare a capability value for a service, CloudStack accepts any value (or no
> value) without error.

### 5. Create an isolated network

```bash
cmk createNetwork \
    name=my-network \
    displaytext="My isolated network" \
    networkofferingid=<offering-uuid> \
    zoneid=<zone-uuid>
```

When a VM is first deployed into this network, CloudStack calls
`NetworkExtensionElement.implement()`, which triggers the `implement` command:

```bash
# Management server executes:
entry-point implement \
    --network-id 42 \
    --vlan 100 \
    --gateway 10.0.1.1 \
    --cidr 10.0.1.0/24

# entry-point SSHes to device and runs inside the namespace:
ip netns exec cs-net-prod \
    /usr/local/share/cloudstack/network-extension-wrapper.sh implement \
    --network-id 42 \
    --vlan 100 \
    --gateway 10.0.1.1 \
    --cidr 10.0.1.0/24
```

The wrapper creates a VLAN sub-interface and Linux bridge, assigns the gateway
IP to the bridge, enables IP forwarding, and creates dedicated per-network
iptables chains (`CS_EXTNET_42` in `nat` and `CS_EXTNET_FWD_42` in `filter`).

### 6. Acquire a public IP and enable Source NAT

```bash
cmk associateIpAddress networkid=<network-uuid>
```

CloudStack calls `applyIps()` which issues `assign-ip` with `--source-nat true`
for the source-NAT IP:

```bash
entry-point assign-ip \
    --network-id 42 \
    --vlan 100 \
    --public-ip 203.0.113.10 \
    --source-nat true \
    --gateway 10.0.1.1 \
    --cidr 10.0.1.0/24
```

The wrapper:
1. Adds `203.0.113.10/32` as a secondary address on the physical interface.
2. Adds an iptables SNAT rule: traffic from `10.0.1.0/24` outbound → source `203.0.113.10`.
3. Adds an iptables FORWARD rule allowing traffic from the guest CIDR to the
   physical interface.

When the IP is released (via `disassociateIpAddress`), `release-ip` is called,
which removes all associated rules and the IP address.

### 7. Enable / disable Static NAT

```bash
# Enable static NAT: map public IP 203.0.113.20 to VM private IP 10.0.1.5
cmk enableStaticNat \
    ipaddressid=<public-ip-uuid> \
    virtualmachineid=<vm-uuid> \
    networkid=<network-uuid>
```

CloudStack calls `applyStaticNats()` → `add-static-nat`:

```bash
entry-point add-static-nat \
    --network-id 42 \
    --vlan 100 \
    --public-ip 203.0.113.20 \
    --private-ip 10.0.1.5
```

iptables rules added:
```bash
# DNAT inbound
iptables -t nat -A CS_EXTNET_42 -d 203.0.113.20 -j DNAT --to-destination 10.0.1.5
# SNAT outbound
iptables -t nat -A CS_EXTNET_42 -s 10.0.1.5 -o eth0 -j SNAT --to-source 203.0.113.20
# FORWARD inbound + outbound
iptables -t filter -A CS_EXTNET_FWD_42 -d 10.0.1.5 -o csbr42 -j ACCEPT
iptables -t filter -A CS_EXTNET_FWD_42 -s 10.0.1.5 -i csbr42 -j ACCEPT
```

```bash
# Disable static NAT
cmk disableStaticNat ipaddressid=<public-ip-uuid>
```

CloudStack calls `delete-static-nat`, which removes all four rules above.

### 8. Add / delete Port Forwarding

```bash
# Forward TCP port 2222 on public IP 203.0.113.20 → VM port 22
cmk createPortForwardingRule \
    ipaddressid=<public-ip-uuid> \
    privateport=22 \
    publicport=2222 \
    protocol=TCP \
    virtualmachineid=<vm-uuid> \
    networkid=<network-uuid>
```

CloudStack calls `applyPFRules()` → `add-port-forward`:

```bash
entry-point add-port-forward \
    --network-id 42 \
    --vlan 100 \
    --public-ip 203.0.113.20 \
    --public-port 2222 \
    --private-ip 10.0.1.5 \
    --private-port 22 \
    --protocol TCP
```

iptables rules added:
```bash
# DNAT inbound
iptables -t nat -A CS_EXTNET_42 -p tcp -d 203.0.113.20 --dport 2222 \
    -j DNAT --to-destination 10.0.1.5:22
# FORWARD
iptables -t filter -A CS_EXTNET_FWD_42 -p tcp -d 10.0.1.5 --dport 22 \
    -o csbr42 -j ACCEPT
```

Port ranges (e.g. `80:90`) are supported and passed verbatim to iptables `--dport`.

```bash
# Delete the rule
cmk deletePortForwardingRule id=<rule-uuid>
```

This calls `delete-port-forward` which removes the DNAT and FORWARD rules.

### 9. Delete the network

```bash
cmk deleteNetwork id=<network-uuid>
```

CloudStack calls `shutdown()` (to clean up active state) then `destroy()` (full
removal).  Both commands perform identical cleanup:

```bash
entry-point shutdown --network-id 42 --vlan 100
entry-point destroy  --network-id 42 --vlan 100
```

The wrapper:
1. Removes jump rules from PREROUTING, POSTROUTING, and FORWARD.
2. Flushes and deletes iptables chains `CS_EXTNET_42` and `CS_EXTNET_FWD_42`.
3. Brings down and deletes bridge `csbr42`.
4. Brings down and deletes VLAN interface `eth0.100` (VLAN ID read from state if
   not passed in arguments).
5. Removes all state under `/var/lib/cloudstack/network-extension/42/`.

### 10. Unregister and delete the extension

```bash
# Disable and delete the NSP
cmk updateNetworkServiceProvider id=<nsp-uuid> state=Disabled
cmk deleteNetworkServiceProvider id=<nsp-uuid>

# Remove external network device credentials
cmk deleteExternalNetworkDevice physicalnetworkid=<phys-net-uuid>

# Unregister the extension from the physical network
cmk unregisterExtension \
    id=<extension-uuid> \
    resourcetype=PhysicalNetwork \
    resourceid=<phys-net-uuid>

# Delete the extension
# (only possible once it is unregistered from all physical networks)
cmk deleteExtension id=<extension-uuid>
```

---

## Multiple extensions on the same physical network

Because each extension is registered as its own NSP (named after the extension),
multiple independent external network providers can coexist on the same physical
network:

```bash
# Register two extensions, each backed by a different device
cmk registerExtension id=<ext-a-uuid> resourcetype=PhysicalNetwork resourceid=<pn-uuid>
cmk registerExtension id=<ext-b-uuid> resourcetype=PhysicalNetwork resourceid=<pn-uuid>

# Add device credentials for each (currently one device per physical network)
cmk addExternalNetworkDevice physicalnetworkid=<pn-uuid> host=10.0.0.1 ...
```

When creating network offerings, reference the specific extension name:

```bash
# Network offering backed by ext-a-name
cmk createNetworkOffering ... \
    "serviceProviderList[0].provider=ext-a-name" ...

# Network offering backed by ext-b-name
cmk createNetworkOffering ... \
    "serviceProviderList[0].provider=ext-b-name" ...
```

CloudStack resolves which extension to call by:
1. Looking up the service provider name stored in `ntwk_service_map` for the
   guest network.
2. Finding the registered extension on the physical network whose name matches
   that provider name.
3. Calling `NetworkExtensionElement` scoped to that specific provider/extension
   (via `NetworkExtensionElement.withProviderName()`).

---

## Wrapper script operations reference

The `network-extension-wrapper.sh` script runs on the remote device inside a
Linux network namespace.  It receives the command as its first positional argument
followed by named `--option value` pairs.

All commands:
* Write timestamped entries to `/var/log/cloudstack/management/network-extension.log`.
* Use a per-network lock file (`/var/run/cloudstack/extnet-<id>.lock`) to
  serialise concurrent operations.
* Persist state under `/var/lib/cloudstack/network-extension/<network-id>/`.

### `implement`

Called when CloudStack activates the network (typically on first VM deploy).

```
network-extension-wrapper.sh implement \
    --network-id <id> \
    --vlan <vlan-id>        (empty string if no VLAN tagging)
    --gateway <gateway-ip> \
    --cidr <cidr>
```

Actions:
1. Create VLAN sub-interface `<PHYS_IFACE>.<vlan>` (skipped if `--vlan` is empty).
2. Create Linux bridge `csbr<id>` and bring it up.
3. Attach VLAN interface to bridge.
4. Assign `<gateway-ip>/<prefix>` to the bridge.
5. Enable IPv4 forwarding (`sysctl net.ipv4.ip_forward=1`).
6. Create iptables chains `CS_EXTNET_<id>` (nat table) and
   `CS_EXTNET_FWD_<id>` (filter table).
7. Add jump rules: nat PREROUTING → `CS_EXTNET_<id>`, nat POSTROUTING →
   `CS_EXTNET_<id>`, filter FORWARD → `CS_EXTNET_FWD_<id>`.
8. Add FORWARD ACCEPT rules for bridge ingress / ESTABLISHED egress.
9. Save VLAN, gateway, CIDR, bridge name to state files.

The physical interface is configured via the `NETWORK_EXTENSION_PHYS_IFACE`
environment variable (default: `eth0`).

### `shutdown` / `destroy`

Called when a network is shut down or permanently destroyed.  Both commands
perform identical cleanup.

```
network-extension-wrapper.sh shutdown --network-id <id> [--vlan <vlan-id>]
network-extension-wrapper.sh destroy  --network-id <id> [--vlan <vlan-id>]
```

Actions:
1. Remove jump rules from nat PREROUTING, nat POSTROUTING, filter FORWARD.
2. Flush and delete chains `CS_EXTNET_<id>` (nat) and `CS_EXTNET_FWD_<id>` (filter).
3. Bring down and delete bridge `csbr<id>`.
4. Bring down and delete VLAN interface (VLAN ID read from state if `--vlan` is
   not supplied).
5. Remove state directory `/var/lib/cloudstack/network-extension/<id>/`.

### `assign-ip`

Called when a public IP is associated with the network.

```
network-extension-wrapper.sh assign-ip \
    --network-id <id> \
    --vlan <vlan-id> \
    --public-ip <ip> \
    --source-nat true|false \
    --gateway <gw> \
    --cidr <cidr>
```

Actions:
1. Add `<public-ip>/32` as a secondary address on `<PHYS_IFACE>`.
2. If `--source-nat true`:
   * SNAT rule: traffic from `<cidr>` outbound → source `<public-ip>`.
   * FORWARD ACCEPT: traffic from `<cidr>` towards `<PHYS_IFACE>`.
3. Save IP state to `/var/lib/cloudstack/network-extension/<id>/ips/<public-ip>`.

### `release-ip`

Called when a public IP is released / disassociated.

```
network-extension-wrapper.sh release-ip \
    --network-id <id> \
    --public-ip <ip>
```

Actions:
1. Remove SNAT rule for `<cidr>` → `<public-ip>` (CIDR read from state if not set).
2. Remove any DNAT/SNAT rules referencing `<public-ip>`.
3. Remove `<public-ip>/32` from `<PHYS_IFACE>`.
4. Delete IP state file.

### `add-static-nat`

Called when Static NAT (one-to-one NAT) is enabled for a public IP.

```
network-extension-wrapper.sh add-static-nat \
    --network-id <id> \
    --vlan <vlan-id> \
    --public-ip <public-ip> \
    --private-ip <private-ip>
```

iptables rules added (all in chain `CS_EXTNET_<id>` / `CS_EXTNET_FWD_<id>`):

| Table | Chain | Rule |
|-------|-------|------|
| `nat` | `CS_EXTNET_<id>` | `-d <public-ip> -j DNAT --to-destination <private-ip>` |
| `nat` | `CS_EXTNET_<id>` | `-s <private-ip> -o <PHYS_IFACE> -j SNAT --to-source <public-ip>` |
| `filter` | `CS_EXTNET_FWD_<id>` | `-d <private-ip> -o csbr<id> -j ACCEPT` |
| `filter` | `CS_EXTNET_FWD_<id>` | `-s <private-ip> -i csbr<id> -j ACCEPT` |

State saved to `/var/lib/cloudstack/network-extension/<id>/static-nat/<public-ip>`.

### `delete-static-nat`

```
network-extension-wrapper.sh delete-static-nat \
    --network-id <id> \
    --public-ip <public-ip> \
    [--private-ip <private-ip>]
```

Removes all four rules added by `add-static-nat`.  If `--private-ip` is omitted,
it is read from the state file.

### `add-port-forward`

Called when a Port Forwarding rule is added.

```
network-extension-wrapper.sh add-port-forward \
    --network-id <id> \
    --vlan <vlan-id> \
    --public-ip <public-ip> \
    --public-port <port-or-range> \
    --private-ip <private-ip> \
    --private-port <port-or-range> \
    --protocol tcp|udp
```

iptables rules added:

| Table | Chain | Rule |
|-------|-------|------|
| `nat` | `CS_EXTNET_<id>` | `-p <proto> -d <public-ip> --dport <public-port> -j DNAT --to-destination <private-ip>:<private-port>` |
| `filter` | `CS_EXTNET_FWD_<id>` | `-p <proto> -d <private-ip> --dport <private-port> -o csbr<id> -j ACCEPT` |

Port ranges (`80:90`) are passed verbatim to iptables `--dport`.

State saved to
`/var/lib/cloudstack/network-extension/<id>/port-forward/<proto>_<public-ip>_<public-port>`.

### `delete-port-forward`

```
network-extension-wrapper.sh delete-port-forward \
    --network-id <id> \
    --public-ip <public-ip> \
    --public-port <port-or-range> \
    --private-ip <private-ip> \
    --private-port <port-or-range> \
    --protocol tcp|udp
```

Removes the DNAT and FORWARD rules added by `add-port-forward`.

### `custom-action`

```
network-extension-wrapper.sh custom-action \
    --network-id <id> \
    --action <action-name>
```

Built-in actions:

| Action | Description |
|--------|-------------|
| `reboot-device` | Bounces the bridge: `ip link set csbr<id> down && up` |
| `dump-config` | Prints iptables rules and bridge/interface state to stdout |

To add custom actions, place an executable script at
`/var/lib/cloudstack/network-extension/hooks/custom-action-<name>.sh`.
Unknown action names are delegated to the hook if present; otherwise the command
fails with a descriptive error.

---

## Environment variable reference

### Connection details (injected from `extension_resource_map_details`)

| Variable | Source key | Description |
|----------|------------|-------------|
| `CS_NET_DEV_HOST` | `host` (top-level) | IP / hostname of the remote device — **required** |
| `CS_NET_DEV_PORT` | `port` (top-level) | SSH port — default: 22 |
| `CS_NET_DEV_USERNAME` | `username` | SSH user — default: root |
| `CS_NET_DEV_PASSWORD` | `password` | SSH password via `sshpass` — sensitive, not logged |
| `CS_NET_DEV_SSHKEY` | `sshkey` | PEM-encoded SSH private key — sensitive, not logged; preferred |
| `CS_NET_NAMESPACE` | `namespace` | Linux network namespace on the remote host — **required** |
| `CS_NET_SCRIPT_PATH` | `script_path` | Full path to `network-extension-wrapper.sh` on remote host |

### Per-network details (injected from `network_details`, keys prefixed `ext.`)

Only `network_details` keys starting with `ext.` are injected.  The key is
upper-cased and dots are replaced by underscores:

| Variable | Source key | Description |
|----------|------------|-------------|
| `CS_NET_EXT_NAMESPACE` | `ext.namespace` | Per-network namespace override |
| `CS_NET_EXT_VRF` | `ext.vrf` | VRF name (custom use) |
| `CS_NET_EXT_BRIDGE` | `ext.bridge` | Bridge name override (custom use) |

Set per-network details via:
```bash
cmk updateNetwork id=<network-uuid> \
    "details[0].key=ext.namespace" "details[0].value=cs-net-42-override"
```

### Physical interface on the remote device

Set `NETWORK_EXTENSION_PHYS_IFACE` (or legacy alias `EXTERNAL_NETWORK_PHYS_IFACE`)
in the environment on the remote device to change the interface used for VLAN
sub-interfaces and public IP secondary addresses (default: `eth0`).

### Action parameters (custom-action only)

Caller-supplied parameters from `runNetworkCustomAction` are exposed as
`CS_ACTION_PARAM_<KEY>` (upper-cased, spaces / dashes / dots replaced by `_`).

---

## Custom actions

Define custom actions per extension via the CloudStack API:

```bash
# Add a custom action to the extension
cmk addCustomAction \
    extensionid=<ext-uuid> \
    name=dump-config \
    description="Dump iptables rules and bridge state" \
    resourcetype=Network
```

Trigger the action on a network:
```bash
cmk runNetworkCustomAction \
    networkid=<network-uuid> \
    actionid=<custom-action-uuid>
```

CloudStack calls `NetworkExtensionElement.runCustomAction()`, which issues:
```bash
entry-point custom-action --network-id <id> --action dump-config
```

The `entry-point` SSHes to the device and runs:
```bash
ip netns exec cs-net-prod \
    /usr/local/share/cloudstack/network-extension-wrapper.sh \
    custom-action --network-id <id> --action dump-config
```

---

## Developer / testing notes

The integration smoke test at
`test/integration/smoke/test_network_extension_provider.py`
exercises the full lifecycle using a **Linux network namespace** on the Marvin
node as the simulated remote device:

```
Marvin node (this machine)
  ├── ip netns add cs-extnet-<id>            ← isolated namespace
  ├── ~/.ssh/authorized_keys ← test RSA key  ← management server connects here
  └── /tmp/cs-extnet-test-<id>/
        └── network-extension-wrapper.sh     ← copied from repo

Management server (may be the same machine)
  └── <extension_path>/entry-point           ← deployed by test (static copy)
        reads CS_NET_DEV_* env vars
        SSHes back to Marvin node :22
        runs ip netns exec cs-extnet-<id> <script> <args>
```

The test covers:
* Create / list / update / delete external network device.
* Full network lifecycle: implement → assign-ip (source NAT) → static NAT →
  port forwarding → shutdown / destroy.
* NSP state transitions: Disabled → Enabled → Disabled → Deleted.

Run the test:
```bash
cd test/integration/smoke
nosetests test_network_extension_provider.py \
    --with-marvin --marvin-config=<config.cfg> \
    -s -a 'tags=advanced,smoke' 2>&1 | tee /tmp/extnet-test.log
```

**Prerequisites:**
* `iproute2` on the Marvin node (`ip netns list` must succeed).
* The Marvin node must be reachable by SSH from the management server on port 22.
* Set `MARVIN_NODE_IP=<ip>` if auto-detection of the Marvin node IP fails.

