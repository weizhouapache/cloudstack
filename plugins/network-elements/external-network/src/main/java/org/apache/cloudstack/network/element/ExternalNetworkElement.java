// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.network.element;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.apache.cloudstack.extension.Extension;
import org.apache.cloudstack.extension.ExtensionHelper;

import com.cloud.deploy.DeployDestination;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.Network;
import com.cloud.network.Network.Capability;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.NetworkModel;
import com.cloud.network.PhysicalNetworkServiceProvider;
import com.cloud.network.PublicIpAddress;
import com.cloud.network.dao.NetworkDetailsDao;
import com.cloud.network.dao.NetworkServiceMapDao;
import com.cloud.network.element.IpDeployer;
import com.cloud.network.element.NetworkElement;
import com.cloud.network.element.PortForwardingServiceProvider;
import com.cloud.network.element.SourceNatServiceProvider;
import com.cloud.network.element.StaticNatServiceProvider;
import com.cloud.network.rules.PortForwardingRule;
import com.cloud.network.rules.StaticNat;
import com.cloud.offering.NetworkOffering;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.NicProfile;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.VirtualMachineProfile;

/**
 * ExternalNetworkElement is a network plugin that delegates all network
 * configuration to an external script. The script manages VLAN/bridge setup,
 * iptables-based source NAT, static NAT, and port forwarding on a Linux
 * server acting as the network gateway.
 *
 * <h3>Extension-based configuration</h3>
 * The script path and network capabilities are resolved dynamically from a
 * {@link Extension} of type {@code NetworkOrchestrator} that is associated
 * with the network's physical network via the Extension Resource Map.
 *
 * <h3>Device credentials via extension_resource_map_details</h3>
 * When registering the extension to a physical network, pass the network
 * device properties as details:
 * <pre>
 *   cmk registerExtension id=&lt;ext-uuid&gt; resourcetype=PhysicalNetwork \
 *       resourceid=&lt;phys-net-uuid&gt; \
 *       details[0].key=host       details[0].value=192.168.1.10 \
 *       details[1].key=port       details[1].value=22 \
 *       details[2].key=username   details[2].value=root \
 *       details[3].key=sshkey     details[3].value="$(cat /root/.ssh/id_rsa)"
 * </pre>
 * These details are passed to the entry-point script as environment variables
 * prefixed with {@code CS_NET_DEV_}: e.g. {@code CS_NET_DEV_HOST},
 * {@code CS_NET_DEV_PORT}, {@code CS_NET_DEV_USERNAME},
 * {@code CS_NET_DEV_PASSWORD}, {@code CS_NET_DEV_SSHKEY}.
 * Sensitive fields (password, sshkey) are omitted from debug logs.
 *
 * <h3>Network details via extension_resource_map_details (per network)</h3>
 * Additional per-network properties (VLAN mappings, gateway overrides, etc.)
 * are also stored as {@code extension_resource_map_details} and exposed to
 * the script as {@code CS_NET_*} environment variables.
 *
 * <h3>Network capabilities via JSON</h3>
 * When creating the extension, pass the network capabilities as a detail
 * with the key {@code network.capabilities}. The value is a JSON object:
 * <pre>
 * {
 *   "services": ["SourceNat", "StaticNat", "PortForwarding", "Firewall", "Gateway"],
 *   "capabilities": {
 *     "SourceNat": {
 *       "SupportedSourceNatTypes": "peraccount",
 *       "RedundantRouter": "false"
 *     },
 *     "Firewall": {
 *       "TrafficStatistics": "per public ip"
 *     }
 *   }
 * }
 * </pre>
 * If no {@code network.capabilities} detail is set, defaults to all services.
 */
public class ExternalNetworkElement extends AdapterBase implements
        NetworkElement, SourceNatServiceProvider, StaticNatServiceProvider,
        PortForwardingServiceProvider, IpDeployer {

    public static final String NETWORK_CAPABILITIES_DETAIL_KEY = "network.capabilities";

    private static final Map<Service, Map<Capability, String>> DEFAULT_CAPABILITIES = initDefaultCapabilities();

    @Inject
    private NetworkModel networkModel;
    @Inject
    private NetworkServiceMapDao ntwkSrvcDao;
    @Inject
    private ExtensionHelper extensionHelper;
    @Inject
    private NetworkDetailsDao networkDetailsDao;

    private static Map<Service, Map<Capability, String>> initDefaultCapabilities() {
        Map<Service, Map<Capability, String>> caps = new HashMap<>();

        Map<Capability, String> sourceNatCaps = new HashMap<>();
        sourceNatCaps.put(Capability.SupportedSourceNatTypes, "peraccount");
        sourceNatCaps.put(Capability.RedundantRouter, "false");
        caps.put(Service.SourceNat, sourceNatCaps);

        caps.put(Service.StaticNat, new HashMap<>());
        caps.put(Service.PortForwarding, new HashMap<>());

        Map<Capability, String> firewallCaps = new HashMap<>();
        firewallCaps.put(Capability.TrafficStatistics, "per public ip");
        caps.put(Service.Firewall, firewallCaps);

        caps.put(Service.Gateway, new HashMap<>());
        return caps;
    }

    @Override
    public Map<Service, Map<Capability, String>> getCapabilities() {
        return DEFAULT_CAPABILITIES;
    }

    /**
     * Parse network capabilities from the extension's {@code network.capabilities}
     * JSON detail. Returns the default capabilities if no detail is set.
     *
     * @param extensionId the extension ID
     * @return parsed capabilities map
     */
    protected Map<Service, Map<Capability, String>> getCapabilitiesFromExtension(long extensionId) {
        Map<String, String> details = extensionHelper.getExtensionDetails(extensionId);
        if (details == null || !details.containsKey(NETWORK_CAPABILITIES_DETAIL_KEY)) {
            return DEFAULT_CAPABILITIES;
        }
        String json = details.get(NETWORK_CAPABILITIES_DETAIL_KEY);
        return parseNetworkCapabilitiesJson(json);
    }

    /**
     * Parse a network capabilities JSON string into a capabilities map.
     * <p>Expected format:</p>
     * <pre>
     * {
     *   "services": ["SourceNat", "StaticNat", ...],
     *   "capabilities": {
     *     "SourceNat": { "SupportedSourceNatTypes": "peraccount" },
     *     ...
     *   }
     * }
     * </pre>
     */
    protected static Map<Service, Map<Capability, String>> parseNetworkCapabilitiesJson(String json) {
        Map<Service, Map<Capability, String>> caps = new HashMap<>();
        if (json == null || json.isBlank()) {
            return DEFAULT_CAPABILITIES;
        }
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            // Parse services array
            JsonArray servicesArray = root.getAsJsonArray("services");
            if (servicesArray == null || servicesArray.isEmpty()) {
                return DEFAULT_CAPABILITIES;
            }

            // Parse capabilities object (optional)
            JsonObject capabilitiesObj = root.has("capabilities") ?
                    root.getAsJsonObject("capabilities") : new JsonObject();

            for (JsonElement svcElem : servicesArray) {
                String svcName = svcElem.getAsString();
                Service service = Service.getService(svcName);
                if (service == null) {
                    continue;
                }

                Map<Capability, String> svcCaps = new HashMap<>();
                if (capabilitiesObj.has(svcName)) {
                    JsonObject svcCapsObj = capabilitiesObj.getAsJsonObject(svcName);
                    for (Map.Entry<String, JsonElement> entry : svcCapsObj.entrySet()) {
                        Capability cap = Capability.getCapability(entry.getKey());
                        if (cap != null) {
                            svcCaps.put(cap, entry.getValue().getAsString());
                        }
                    }
                }
                caps.put(service, svcCaps);
            }
            return caps;
        } catch (Exception e) {
            return DEFAULT_CAPABILITIES;
        }
    }

    @Override
    public Provider getProvider() {
        return Provider.ExternalNetwork;
    }

    protected boolean canHandle(Network network, Service service) {
        if (!networkModel.isProviderForNetwork(getProvider(), network.getId())) {
            logger.debug("ExternalNetwork is not a provider for network {}", network.getDisplayText());
            return false;
        }
        if (!ntwkSrvcDao.canProviderSupportServiceInNetwork(network.getId(), service, Provider.ExternalNetwork)) {
            logger.debug("ExternalNetwork can't provide {} on network {}", service.getName(), network.getDisplayText());
            return false;
        }
        return true;
    }

    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
        super.configure(name, params);
        return true;
    }

    // ---- NetworkElement lifecycle ----

    @Override
    public boolean implement(Network network, NetworkOffering offering, DeployDestination dest,
                             ReservationContext context) throws ConcurrentOperationException,
            ResourceUnavailableException, InsufficientCapacityException {
        if (!canHandle(network, Service.SourceNat)) {
            return false;
        }
        logger.info("Implementing external network for network {} (VLAN {})", network.getId(), network.getBroadcastUri());
        String vlanId = getVlanId(network);

        return executeScript(network,
                "implement",
                "--network-id", String.valueOf(network.getId()),
                "--vlan", safeStr(vlanId),
                "--gateway", safeStr(network.getGateway()),
                "--cidr", safeStr(network.getCidr()));
    }

    @Override
    public boolean prepare(Network network, NicProfile nic, VirtualMachineProfile vm,
                           DeployDestination dest, ReservationContext context) throws ConcurrentOperationException,
            ResourceUnavailableException, InsufficientCapacityException {
        return true;
    }

    @Override
    public boolean release(Network network, NicProfile nic, VirtualMachineProfile vm,
                           ReservationContext context) throws ConcurrentOperationException, ResourceUnavailableException {
        return true;
    }

    @Override
    public boolean shutdown(Network network, ReservationContext context, boolean cleanup)
            throws ConcurrentOperationException, ResourceUnavailableException {
        logger.info("Shutting down external network for network {}", network.getId());
        return executeScript(network,
                "shutdown",
                "--network-id", String.valueOf(network.getId()),
                "--vlan", safeStr(getVlanId(network)));
    }

    @Override
    public boolean destroy(Network network, ReservationContext context) throws ConcurrentOperationException, ResourceUnavailableException {
        logger.info("Destroying external network for network {}", network.getId());
        return executeScript(network,
                "destroy",
                "--network-id", String.valueOf(network.getId()),
                "--vlan", safeStr(getVlanId(network)));
    }

    @Override
    public boolean isReady(PhysicalNetworkServiceProvider provider) {
        return true;
    }

    @Override
    public boolean shutdownProviderInstances(PhysicalNetworkServiceProvider provider, ReservationContext context)
            throws ConcurrentOperationException, ResourceUnavailableException {
        return true;
    }

    @Override
    public boolean canEnableIndividualServices() {
        return true;
    }

    @Override
    public boolean verifyServicesCombination(Set<Service> services) {
        return true;
    }

    // ---- IpDeployer ----

    @Override
    public boolean applyIps(Network network, List<? extends PublicIpAddress> ipAddress, Set<Service> services)
            throws ResourceUnavailableException {
        if (ipAddress == null || ipAddress.isEmpty()) {
            return true;
        }
        logger.info("Applying {} IPs for network {}", ipAddress.size(), network.getId());
        String vlanId = getVlanId(network);

        for (PublicIpAddress ip : ipAddress) {
            boolean isSourceNat = ip.isSourceNat();
            boolean isRevoke = ip.getState() == com.cloud.network.IpAddress.State.Releasing;
            String action = isRevoke ? "release-ip" : "assign-ip";

            boolean result = executeScript(network, action,
                    "--network-id", String.valueOf(network.getId()),
                    "--vlan", safeStr(vlanId),
                    "--public-ip", ip.getAddress().addr(),
                    "--source-nat", String.valueOf(isSourceNat),
                    "--gateway", safeStr(network.getGateway()),
                    "--cidr", safeStr(network.getCidr()));
            if (!result) {
                throw new ResourceUnavailableException("Failed to " + action + " for IP " + ip.getAddress().addr(),
                        Network.class, network.getId());
            }
        }
        return true;
    }

    // ---- SourceNatServiceProvider (via IpDeployingRequester) ----

    @Override
    public IpDeployer getIpDeployer(Network network) {
        return this;
    }

    // ---- StaticNatServiceProvider ----

    @Override
    public boolean applyStaticNats(Network config, List<? extends StaticNat> rules)
            throws ResourceUnavailableException {
        if (rules == null || rules.isEmpty()) {
            return true;
        }
        if (!canHandle(config, Service.StaticNat)) {
            return false;
        }
        logger.info("Applying {} static NAT rules for network {}", rules.size(), config.getId());
        String vlanId = getVlanId(config);

        for (StaticNat rule : rules) {
            String action = rule.isForRevoke() ? "delete-static-nat" : "add-static-nat";
            boolean result = executeScript(config, action,
                    "--network-id", String.valueOf(config.getId()),
                    "--vlan", safeStr(vlanId),
                    "--public-ip", getIpAddress(rule.getSourceIpAddressId()),
                    "--private-ip", safeStr(rule.getDestIpAddress()));
            if (!result) {
                throw new ResourceUnavailableException("Failed to " + action + " for static NAT rule",
                        Network.class, config.getId());
            }
        }
        return true;
    }

    // ---- PortForwardingServiceProvider ----

    @Override
    public boolean applyPFRules(Network network, List<PortForwardingRule> rules)
            throws ResourceUnavailableException {
        if (rules == null || rules.isEmpty()) {
            return true;
        }
        if (!canHandle(network, Service.PortForwarding)) {
            return false;
        }
        logger.info("Applying {} port forwarding rules for network {}", rules.size(), network.getId());
        String vlanId = getVlanId(network);

        for (PortForwardingRule rule : rules) {
            boolean isRevoke = rule.getState() == com.cloud.network.rules.FirewallRule.State.Revoke;
            String action = isRevoke ? "delete-port-forward" : "add-port-forward";

            String publicPort = PortForwardingServiceProvider.getPublicPortRange(rule);
            String privatePort = PortForwardingServiceProvider.getPrivatePFPortRange(rule);

            boolean result = executeScript(network, action,
                    "--network-id", String.valueOf(network.getId()),
                    "--vlan", safeStr(vlanId),
                    "--public-ip", getIpAddress(rule.getSourceIpAddressId()),
                    "--public-port", safeStr(publicPort),
                    "--private-ip", safeStr(rule.getDestinationIpAddress() != null ? rule.getDestinationIpAddress().addr() : null),
                    "--private-port", safeStr(privatePort),
                    "--protocol", safeStr(rule.getProtocol()));
            if (!result) {
                throw new ResourceUnavailableException("Failed to " + action + " for port forwarding rule",
                        Network.class, network.getId());
            }
        }
        return true;
    }

    // ---- Credential / detail key constants ----

    /** Well-known keys stored in extension_resource_map_details (PhysicalNetwork binding). */
    public static final String DETAIL_HOST     = "host";
    public static final String DETAIL_PORT     = "port";
    public static final String DETAIL_USERNAME = "username";
    public static final String DETAIL_PASSWORD = "password";
    public static final String DETAIL_SSHKEY   = "sshkey";

    /** Env-var prefix for device-access details passed to the script. */
    private static final String ENV_DEV_PREFIX  = "CS_NET_DEV_";
    /** Env-var prefix for generic resource-map details passed to the script. */
    private static final String ENV_MAP_PREFIX  = "CS_NET_";

    /** Details that are sensitive and must not appear in log output. */
    private static final java.util.Set<String> SENSITIVE_KEYS =
            java.util.Set.of(DETAIL_PASSWORD, DETAIL_SSHKEY);

    // ---- Extension-based script resolution ----

    /**
     * Resolves the script path from the NetworkOrchestrator extension associated
     * with the network's physical network and executes it.
     *
     * Device credentials and other per-physical-network details stored in
     * {@code extension_resource_map_details} are passed to the script as
     * environment variables:
     * <ul>
     *   <li>{@code CS_NET_DEV_HOST} – IP / hostname of the network device</li>
     *   <li>{@code CS_NET_DEV_PORT} – SSH port (default 22)</li>
     *   <li>{@code CS_NET_DEV_USERNAME} – SSH username</li>
     *   <li>{@code CS_NET_DEV_PASSWORD} – SSH password (sensitive, not logged)</li>
     *   <li>{@code CS_NET_DEV_SSHKEY} – SSH private key PEM (sensitive, not logged)</li>
     *   <li>{@code CS_NET_<KEY>} – any other detail key, upper-cased</li>
     * </ul>
     */
    protected boolean executeScript(Network network, String command, String... args) {
        File scriptFile = resolveScriptFile(network);

        List<String> cmdLine = new ArrayList<>();
        cmdLine.add(scriptFile.getAbsolutePath());
        cmdLine.add(command);
        for (String arg : args) {
            cmdLine.add(arg);
        }

        logger.debug("Executing external network script: {}", String.join(" ", cmdLine));

        try {
            ProcessBuilder pb = new ProcessBuilder(cmdLine);
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            env.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");

            // Inject device credentials and other resource-map details as env vars
            injectResourceMapEnv(env, network.getPhysicalNetworkId());

            // Inject per-network details (namespace, vrf, etc.) from network_details
            injectNetworkDetailsEnv(env, network.getId());

            Process process = pb.start();
            byte[] output = process.getInputStream().readAllBytes();
            int exitCode = process.waitFor();

            String outputStr = new String(output).trim();
            if (!outputStr.isEmpty()) {
                logger.debug("Script output: {}", outputStr);
            }

            if (exitCode != 0) {
                logger.error("External network script failed with exit code {}: {}", exitCode, outputStr);
                return false;
            }
            return true;
        } catch (Exception e) {
            logger.error("Failed to execute external network script: {}", e.getMessage(), e);
            throw new CloudRuntimeException("Failed to execute external network script", e);
        }
    }

    /**
     * Loads the {@code extension_resource_map_details} for the given physical
     * network and injects them into the process environment.
     *
     * <p>Well-known device-access keys (host, port, username, password, sshkey)
     * are mapped to {@code CS_NET_DEV_<KEY>}; all other keys become
     * {@code CS_NET_<KEY>} (upper-cased).
     */
    protected void injectResourceMapEnv(Map<String, String> env, Long physicalNetworkId) {
        if (physicalNetworkId == null) {
            return;
        }
        // Use getAllResourceMapDetails (includes display=false) so that sensitive
        // fields like password and sshkey are injected into the script env vars
        Map<String, String> details = extensionHelper.getAllResourceMapDetailsForPhysicalNetwork(physicalNetworkId);
        if (details == null || details.isEmpty()) {
            return;
        }

        java.util.Set<String> deviceKeys = java.util.Set.of(
                DETAIL_HOST, DETAIL_PORT, DETAIL_USERNAME, DETAIL_PASSWORD, DETAIL_SSHKEY);

        for (Map.Entry<String, String> entry : details.entrySet()) {
            String key   = entry.getKey();
            String value = entry.getValue();
            if (value == null) {
                continue;
            }
            String envKey;
            if (deviceKeys.contains(key.toLowerCase())) {
                envKey = ENV_DEV_PREFIX + key.toUpperCase();
            } else {
                envKey = ENV_MAP_PREFIX + key.toUpperCase();
            }
            env.put(envKey, value);
            if (!SENSITIVE_KEYS.contains(key.toLowerCase())) {
                logger.debug("  env {}={}", envKey, value);
            } else {
                logger.debug("  env {}=<redacted>", envKey);
            }
        }
    }

    /**
     * Injects per-network properties stored in {@code network_details} as
     * {@code CS_NET_<KEY>} environment variables.
     *
     * <p>These are the network-level details set by operators (or by the
     * entry-point script on first use) that describe how this specific
     * CloudStack network is represented on the external device — for example:</p>
     * <ul>
     *   <li>{@code ext.namespace} → {@code CS_NET_EXT_NAMESPACE} – the Linux
     *       network namespace assigned to this network on the device</li>
     *   <li>{@code ext.vrf}       → {@code CS_NET_EXT_VRF} – VRF name</li>
     *   <li>{@code ext.bridge}    → {@code CS_NET_EXT_BRIDGE} – bridge name</li>
     * </ul>
     * Only keys that start with {@code ext.} are injected, upper-cased and
     * with the dot replaced by an underscore, e.g.
     * {@code ext.namespace} → {@code CS_NET_EXT_NAMESPACE}.
     */
    protected void injectNetworkDetailsEnv(Map<String, String> env, long networkId) {
        Map<String, String> networkDetails = networkDetailsDao.listDetailsKeyPairs(networkId);
        if (networkDetails == null || networkDetails.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : networkDetails.entrySet()) {
            String key   = entry.getKey();
            String value = entry.getValue();
            if (value == null || !key.startsWith("ext.")) {
                continue;
            }
            // ext.namespace -> CS_NET_EXT_NAMESPACE
            String envKey = "CS_NET_" + key.toUpperCase().replace('.', '_');
            env.put(envKey, value);
            logger.debug("  network-detail env {}={}", envKey, value);
        }
    }

    /**
     * Resolves the script file from the NetworkOrchestrator extension
     * associated
     * with the given network's physical network.
     *
     * The extension's path (resolved by ExtensionHelper from the extensions base
     * directory + the extension's relative path) is expected to contain an
     * executable script. The path itself can point to either:
     * <ul>
     *   <li>A directory containing an {@code entry-point} script, or</li>
     *   <li>A directory whose name is used to find the script as {@code <name>.sh}</li>
     * </ul>
     */
    protected File resolveScriptFile(Network network) {
        Long physicalNetworkId = network.getPhysicalNetworkId();
        if (physicalNetworkId == null) {
            throw new CloudRuntimeException("Network " + network.getId() + " has no physical network");
        }

        Extension extension = extensionHelper.getExtensionForPhysicalNetwork(physicalNetworkId);
        if (extension == null) {
            throw new CloudRuntimeException(
                    "No NetworkOrchestrator extension found for physical network " + physicalNetworkId +
                    ". Please create a NetworkOrchestrator extension and associate it with the physical network.");
        }
        if (!Extension.Type.NetworkOrchestrator.equals(extension.getType())) {
            throw new CloudRuntimeException(
                    "Extension " + extension.getName() + " is not of type NetworkOrchestrator");
        }
        if (!Extension.State.Enabled.equals(extension.getState())) {
            throw new CloudRuntimeException(
                    "Extension " + extension.getName() + " is not enabled");
        }
        if (!extension.isPathReady()) {
            throw new CloudRuntimeException(
                    "Extension " + extension.getName() + " path is not ready");
        }

        String extensionPath = extensionHelper.getExtensionScriptPath(extension);
        if (extensionPath == null) {
            throw new CloudRuntimeException(
                    "Could not resolve path for extension " + extension.getName());
        }

        File extensionDir = new File(extensionPath);

        // Try: <extensionPath>/entry-point (standard convention)
        File entryPoint = new File(extensionDir, "entry-point");
        if (entryPoint.exists() && entryPoint.canExecute()) {
            return entryPoint;
        }

        // Try: <extensionPath>/<extensionName>.sh
        File namedScript = new File(extensionDir, extension.getName() + ".sh");
        if (namedScript.exists() && namedScript.canExecute()) {
            return namedScript;
        }

        // Try: the extensionPath itself is the script (if it's a file, not a dir)
        if (extensionDir.isFile() && extensionDir.canExecute()) {
            return extensionDir;
        }

        throw new CloudRuntimeException(
                "No executable script found in extension path " + extensionPath +
                ". Expected either 'entry-point' or '" + extension.getName() + ".sh' in the extension directory.");
    }

    // ---- Helpers ----

    private String getVlanId(Network network) {
        return network.getBroadcastUri() != null ?
                com.cloud.network.Networks.BroadcastDomainType.getValue(network.getBroadcastUri()) : null;
    }

    private String getIpAddress(Long ipAddressId) {
        if (ipAddressId == null) {
            return "";
        }
        com.cloud.network.IpAddress ip = networkModel.getIp(ipAddressId);
        return ip != null ? ip.getAddress().addr() : "";
    }

    private String safeStr(String value) {
        return value != null ? value : "";
    }
}

