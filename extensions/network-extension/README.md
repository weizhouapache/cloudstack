# NetworkExtension for Apache CloudStack

This directory contains the **NetworkExtension** `NetworkOrchestrator` extension.
It runs on the **management server** and delegates all network operations to an
external Linux host (or network appliance) over SSH.

## Contents

| File | Location | Purpose |
|------|----------|---------|
| `entry-point` | management server | SSH proxy wrapper — executed by CloudStack |
| `network-extension-wrapper.sh` | remote network device | Performs actual iptables / bridge operations |

> **Note:** `network-extension-wrapper.sh` is located in
> `plugins/network-elements/network-extension/src/main/resources/scripts/` in the
> source tree and must be copied to the remote host.

---

## How it works

```
CloudStack Management Server
  NetworkExtensionElement.java
      │ executes
      ▼
  extensions/network-extension/entry-point        ← this script
      │ SSH (using CS_NET_DEV_* env vars)
      ▼
  Remote Network Device (Linux host)
      ip netns exec <namespace> network-extension-wrapper.sh <command> [args...]
```

1. **CloudStack** calls `entry-point` with a command and arguments, e.g.:
   ```
   entry-point implement --network-id 42 --vlan 100 --gateway 10.0.1.1 --cidr 10.0.1.0/24
   ```

2. **`entry-point`** reads connection details from environment variables injected by
   `NetworkExtensionElement`:
   - `CS_NET_DEV_HOST` – IP / hostname of the remote device
   - `CS_NET_DEV_PORT` – SSH port (default: 22)
   - `CS_NET_DEV_USERNAME` – SSH user (default: root)
   - `CS_NET_DEV_SSHKEY` – SSH private key PEM *(preferred)*
   - `CS_NET_DEV_PASSWORD` – SSH password via `sshpass` *(fallback)*
   - `CS_NET_NAMESPACE` – Linux network namespace on the remote host
   - `CS_NET_SCRIPT_PATH` – path to `network-extension-wrapper.sh` on remote host
     (default: `/usr/local/share/cloudstack/network-extension-wrapper.sh`)

3. **`entry-point`** SSHes to the remote host and runs:
   ```
   ip netns exec <namespace> <script_path> <command> [args...]
   ```

4. **`network-extension-wrapper.sh`** performs the requested operation (create bridge,
   configure iptables rules, etc.) inside the isolated network namespace.

---

## Installation

### Management server

The `entry-point` script is installed automatically during package installation:

```
/etc/cloudstack/extensions/network-extension/entry-point
```

It is also accessible (via symlink) at:

```
/usr/share/cloudstack-management/extensions/network-extension/entry-point
```

CloudStack resolves the extension path using `extensionsDirectory + "/" + relativePath`.  
In **production**, `extensionsDirectory = /usr/share/cloudstack-management/extensions`.  
In **developer** mode, `extensionsDirectory = extensions` (relative to the working directory,
i.e., the repo root — so `extensions/network-extension/entry-point` works out of the box).

When creating the extension via API, set `path=network-extension/entry-point`.

### Remote network device

Copy `network-extension-wrapper.sh` to the remote host:

```bash
scp plugins/network-elements/network-extension/src/main/resources/scripts/network-extension-wrapper.sh \
    root@<device>:/usr/local/share/cloudstack/network-extension-wrapper.sh
chmod +x /usr/local/share/cloudstack/network-extension-wrapper.sh
```

---

## CloudStack API setup

### 1. Create the extension

```bash
cmk createExtension \
    name=my-extnet \
    type=NetworkOrchestrator \
    path=network-extension/entry-point \
    "details[0].key=network.capabilities" \
    "details[0].value={\"services\":[\"SourceNat\",\"StaticNat\",\"PortForwarding\",\"Firewall\",\"Gateway\"],\"capabilities\":{\"SourceNat\":{\"SupportedSourceNatTypes\":\"peraccount\"}}}"
```

### 2. Register the extension with a physical network (and supply device credentials)

```bash
cmk registerExtension \
    id=<extension-uuid> \
    resourcetype=PhysicalNetwork \
    resourceid=<physical-network-uuid> \
    "details[0].key=host"       "details[0].value=<device-ip>" \
    "details[1].key=port"       "details[1].value=22" \
    "details[2].key=username"   "details[2].value=root" \
    "details[3].key=sshkey"     "details[3].value=$(cat /root/.ssh/id_rsa)" \
    "details[4].key=namespace"  "details[4].value=cs-net-prod"
```

> **Note:** `sshkey` is stored with `display=false` and is never returned by list APIs.
> The NSP named `my-extnet` is created and enabled automatically.

### 3. Create a network offering

Use the extension name (`my-extnet`) as the service provider — **not** `NetworkExtension`:

```bash
cmk createNetworkOffering \
    name="NetworkExtension Offering" \
    guestiptype=Isolated \
    traffictype=GUEST \
    supportedservices=SourceNat,StaticNat,PortForwarding,Firewall,Gateway \
    "serviceProviderList[0].service=SourceNat"      "serviceProviderList[0].provider=my-extnet" \
    "serviceProviderList[1].service=StaticNat"      "serviceProviderList[1].provider=my-extnet" \
    "serviceProviderList[2].service=PortForwarding" "serviceProviderList[2].provider=my-extnet" \
    "serviceProviderList[3].service=Firewall"       "serviceProviderList[3].provider=my-extnet" \
    "serviceProviderList[4].service=Gateway"        "serviceProviderList[4].provider=my-extnet" \
    "serviceCapabilityList[0].service=SourceNat" \
    "serviceCapabilityList[0].capabilitytype=SupportedSourceNatTypes" \
    "serviceCapabilityList[0].capabilityvalue=peraccount"
```

### 4. Create an isolated network

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
| `CS_NET_DEV_PASSWORD` | `password` | SSH
