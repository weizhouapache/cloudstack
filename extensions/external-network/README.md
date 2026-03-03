# External Network Extension for Apache CloudStack

This directory contains the **ExternalNetwork** `NetworkOrchestrator` extension.
It runs on the **management server** and delegates all network operations to an
external Linux host (or network appliance) over SSH.

## Contents

| File | Location | Purpose |
|------|----------|---------|
| `entry-point` | management server | SSH proxy wrapper — executed by CloudStack |
| `external-network.sh` | remote network device | Performs actual iptables / bridge operations |

---

## How it works

```
CloudStack Management Server
  ExternalNetworkElement.java
      │ executes
      ▼
  extensions/external-network/entry-point        ← this script
      │ SSH (using CS_NET_DEV_* env vars)
      ▼
  Remote Network Device (Linux host)
      ip netns exec <namespace> external-network.sh <command> [args...]
```

1. **CloudStack** calls `entry-point` with a command and arguments, e.g.:
   ```
   entry-point implement --network-id 42 --vlan 100 --gateway 10.0.1.1 --cidr 10.0.1.0/24
   ```

2. **`entry-point`** reads connection details from environment variables injected by
   `ExternalNetworkElement`:
   - `CS_NET_DEV_HOST` – IP / hostname of the remote device
   - `CS_NET_DEV_PORT` – SSH port (default: 22)
   - `CS_NET_DEV_USERNAME` – SSH user (default: root)
   - `CS_NET_DEV_SSHKEY` – SSH private key PEM *(preferred)*
   - `CS_NET_DEV_PASSWORD` – SSH password via `sshpass` *(fallback)*
   - `CS_NET_NAMESPACE` – Linux network namespace on the remote host
   - `CS_NET_SCRIPT_PATH` – path to `external-network.sh` on remote host
     (default: `/usr/local/share/cloudstack/external-network.sh`)

3. **`entry-point`** SSHes to the remote host and runs:
   ```
   ip netns exec <namespace> <script_path> <command> [args...]
   ```

4. **`external-network.sh`** performs the requested operation (create bridge,
   configure iptables rules, etc.) inside the isolated network namespace.

---

## Installation

### Management server

The `entry-point` script is installed automatically during package installation:

```
/etc/cloudstack/extensions/external-network/entry-point
```

It is also accessible (via symlink) at:

```
/usr/share/cloudstack-management/extensions/external-network/entry-point
```

CloudStack resolves the extension path using `extensionsDirectory + "/" + relativePath`.  
In **production**, `extensionsDirectory = /usr/share/cloudstack-management/extensions`.  
In **developer** mode, `extensionsDirectory = extensions` (relative to the working directory,
i.e., the repo root — so `extensions/external-network/entry-point` works out of the box).

When creating the extension via API, set `path=external-network/entry-point`.

### Remote network device

Copy `external-network.sh` to the remote host:

```bash
scp external-network.sh root@<device>:/usr/local/share/cloudstack/external-network.sh
chmod +x /usr/local/share/cloudstack/external-network.sh
```

---

## CloudStack API setup

### 1. Create the extension

```bash
cmk createExtension \
    name=my-extnet \
    type=NetworkOrchestrator \
    path=external-network/entry-point \
    "details[0].key=network.capabilities" \
    "details[0].value={\"services\":[\"SourceNat\",\"StaticNat\",\"PortForwarding\",\"Firewall\",\"Gateway\"],\"capabilities\":{\"SourceNat\":{\"SupportedSourceNatTypes\":\"peraccount\"}}}"
```

### 2. Register the extension with a physical network

```bash
cmk registerExtension \
    id=<extension-uuid> \
    resourcetype=PhysicalNetwork \
    resourceid=<physical-network-uuid>
```

### 3. Add the external network device (SSH credentials)

```bash
cmk addExternalNetworkDevice \
    physicalnetworkid=<physical-network-uuid> \
    host=<device-ip> \
    port=22 \
    "details[0].key=username"   "details[0].value=root" \
    "details[1].key=sshkey"     "details[1].value=$(cat /root/.ssh/id_rsa)" \
    "details[2].key=namespace"  "details[2].value=cs-net-prod"
```

> **Note:** `sshkey` is stored with `display=false` and is never returned by
> `listExternalNetworkDevices`.

### 4. Add and enable the ExternalNetwork service provider

```bash
cmk addNetworkServiceProvider \
    name=ExternalNetwork \
    physicalnetworkid=<physical-network-uuid>

cmk updateNetworkServiceProvider \
    id=<provider-uuid> \
    state=Enabled
```

### 5. Create a network offering

```bash
cmk createNetworkOffering \
    name="External Network Offering" \
    guestiptype=Isolated \
    traffictype=GUEST \
    supportedservices=SourceNat,StaticNat,PortForwarding,Firewall,Gateway \
    "serviceProviderList[0].service=SourceNat"      "serviceProviderList[0].provider=ExternalNetwork" \
    "serviceProviderList[1].service=StaticNat"      "serviceProviderList[1].provider=ExternalNetwork" \
    "serviceProviderList[2].service=PortForwarding" "serviceProviderList[2].provider=ExternalNetwork" \
    "serviceProviderList[3].service=Firewall"       "serviceProviderList[3].provider=ExternalNetwork" \
    "serviceProviderList[4].service=Gateway"        "serviceProviderList[4].provider=ExternalNetwork" \
    "serviceCapabilityList[0].service=SourceNat" \
    "serviceCapabilityList[0].capabilitytype=SupportedSourceNatTypes" \
    "serviceCapabilityList[0].capabilityvalue=peraccount"
```

### 6. Create an isolated network

```bash
cmk createNetwork \
    name=my-network \
    networkofferingid=<offering-uuid> \
    zoneid=<zone-uuid>
```

---

## Environment variable reference

### Connection (injected from `extension_resource_map_details`)

| Variable | Source key | Description |
|----------|------------|-------------|
| `CS_NET_DEV_HOST` | `host` | IP / hostname of remote device |
| `CS_NET_DEV_PORT` | `port` | SSH port (default: 22) |
| `CS_NET_DEV_USERNAME` | `username` | SSH user (default: root) |
| `CS_NET_DEV_PASSWORD` | `password` | SSH password (via `sshpass`) |
| `CS_NET_DEV_SSHKEY` | `sshkey` | SSH private key PEM |

### Per-device configuration

| Variable | Source key | Description |
|----------|------------|-------------|
| `CS_NET_NAMESPACE` | `namespace` | Network namespace on remote host |
| `CS_NET_SCRIPT_PATH` | `script_path` | Path to `external-network.sh` on remote host |

### Per-network (injected from `network_details`, keys starting with `ext.`)

| Variable | Source key | Description |
|----------|------------|-------------|
| `CS_NET_EXT_NAMESPACE` | `ext.namespace` | Per-network namespace override |
| `CS_NET_EXT_BRIDGE` | `ext.bridge` | Bridge name override |
| `CS_NET_EXT_VRF` | `ext.vrf` | VRF name |

---

## Supported commands

The `entry-point` forwards these commands (plus all arguments) to
`external-network.sh` on the remote host:

| Command | Triggered by |
|---------|-------------|
| `implement` | Network created / restarted |
| `shutdown` | Network shutdown |
| `destroy` | Network destroyed |
| `assign-ip` | Public IP acquired |
| `release-ip` | Public IP released |
| `add-static-nat` | Static NAT enabled |
| `delete-static-nat` | Static NAT disabled |
| `add-port-forward` | Port forwarding rule created |
| `delete-port-forward` | Port forwarding rule deleted |

---

## Authentication

The `entry-point` tries credentials in this order:

1. **SSH key** (`CS_NET_DEV_SSHKEY`) — writes key to a temp file (`chmod 600`),
   uses `ssh -i <tempfile>`. Temp file is deleted on exit.
2. **Password** (`CS_NET_DEV_PASSWORD`) — uses `sshpass`. Requires `sshpass`
   to be installed on the management server (`yum install sshpass` /
   `apt install sshpass`).
3. **SSH agent / host keys** — no explicit credentials; relies on the
   management server's SSH configuration.

> **Recommendation:** Use SSH key authentication. Generate a dedicated key pair
> and store only the private key in CloudStack (it is never returned by the API).
</content>
</invoke>
