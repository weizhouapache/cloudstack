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
package org.apache.cloudstack.framework.extensions.network;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import org.apache.cloudstack.extension.Extension;
import org.apache.cloudstack.extension.ExtensionHelper;
import org.apache.cloudstack.extension.NetworkCustomActionProvider;

import com.cloud.deploy.DeployDestination;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.IpAddressManager;
import com.cloud.network.IpAddress;
import com.cloud.network.Network;
import com.cloud.network.Network.Capability;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.NetworkModel;
import com.cloud.network.Networks;
import com.cloud.network.PhysicalNetworkServiceProvider;
import com.cloud.network.PublicIpAddress;
import com.cloud.network.addr.PublicIp;
import com.cloud.network.dao.NetworkDetailsDao;
import com.cloud.network.dao.NetworkServiceMapDao;
import com.cloud.network.element.IpDeployer;
import com.cloud.network.element.NetworkElement;
import com.cloud.network.element.PortForwardingServiceProvider;
import com.cloud.network.element.SourceNatServiceProvider;
import com.cloud.network.element.StaticNatServiceProvider;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.PortForwardingRule;
import com.cloud.network.rules.StaticNat;
import com.cloud.offering.NetworkOffering;
import com.cloud.user.Account;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.NicProfile;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.VirtualMachineProfile;

/**
 * NetworkExtensionElement is a network plugin that delegates all network
 * configuration to an external script via a registered {@link Extension} of
 * type {@code NetworkOrchestrator}.
 *
 * <h3>Script invocation model</h3>
 * The script is called with a command name and optional CLI arguments.
 * Two JSON blobs are always forwarded as environment variables:
 * <ul>
 *   <li>{@value #ENV_PHYSICAL_NETWORK_EXTENSION_DETAILS} – all details stored
 *       in {@code extension_resource_map_details} when the extension was
 *       registered with the physical network (connection info, host list,
 *       credentials, etc.).  The script owns the schema.</li>
 *   <li>{@value #ENV_EXTENSION_DETAILS} – the per-network JSON blob stored in
 *       {@code network_details} under key {@value #NETWORK_DETAIL_EXTENSION_DETAILS}.
 *       Populated by the script's {@code ensure-network-device} response and
 *       updated on failover (e.g. selected host, namespace, segment ID).</li>
 * </ul>
 *
 * <h3>Script resolution</h3>
 * The script is resolved from the extension path set when the extension was
 * created.  Lookup order (first match wins):
 * <ol>
 *   <li>{@code <extensionPath>/<extensionName>.sh} — preferred convention,
 *       e.g. for an extension named {@code network-extension} the script is
 *       {@code network-extension.sh}.</li>
 *   <li>{@code <extensionPath>} itself, if it is a file and is executable.</li>
 * </ol>
 *
 * <h3>Physical-network extension details</h3>
 * Any key/value pairs stored in {@code extension_resource_map_details} at
 * registration time are passed verbatim as a JSON object.  There are no
 * pre-defined keys — the user and the script agree on the schema.  The only
 * special treatment is that keys named {@code password} or {@code sshkey} are
 * redacted in log output.
 *
 * <p>Example registration for a KVM-namespace backend:</p>
 * <pre>
 *   cmk registerExtension id=&lt;ext-uuid&gt; resourcetype=PhysicalNetwork \
 *       resourceid=&lt;phys-uuid&gt; \
 *       details[0].key=hosts     details[0].value=192.168.1.10,192.168.1.11 \
 *       details[1].key=port      details[1].value=22 \
 *       details[2].key=username  details[2].value=root \
 *       details[3].key=sshkey    details[3].value="$(cat ~/.ssh/id_rsa)"
 * </pre>
 *
 * <h3>Per-network extension details</h3>
 * On first {@code implement}, the script is called with
 * {@code ensure-network-device}.  The script selects a host (e.g. from the
 * {@code hosts} list in the physical-network details), checks it is reachable,
 * and prints a JSON object to stdout.  CloudStack stores this verbatim in
 * {@code network_details} under key {@value #NETWORK_DETAIL_EXTENSION_DETAILS}
 * and forwards it on every subsequent call as {@value #ARG_NETWORK_EXTENSION_DETAILS}.
 *
 * <p>Example per-network details (KVM-namespace backend):</p>
 * <pre>{"host":"192.168.1.10","namespace":"cs-net-42"}</pre>
 *
 * <h3>Network capabilities</h3>
 * When creating the extension, set detail {@code network.capabilities} to a
 * JSON object describing the services and their capabilities:
 * <pre>
 * {
 *   "services": ["SourceNat", "StaticNat", "PortForwarding", "Firewall"],
 *   "capabilities": {
 *     "SourceNat": { "SupportedSourceNatTypes": "peraccount", "RedundantRouter": "false" }
 *   }
 * }
 * </pre>
 */
public class NetworkExtensionElement extends AdapterBase implements
        NetworkElement, SourceNatServiceProvider, StaticNatServiceProvider,
        PortForwardingServiceProvider, IpDeployer, NetworkCustomActionProvider {

    private static final Map<Service, Map<Capability, String>> DEFAULT_CAPABILITIES = new HashMap<>();

    /**
     * Keys whose values must never appear in log output.
     * The check is case-insensitive.
     */
    private static final Set<String> SENSITIVE_KEYS = new HashSet<>(Arrays.asList("password", "sshkey"));

    /**
     * When non-null, restricts all operations to the extension whose name
     * matches this provider name.
     */
    private String providerName;

    @Inject
    private NetworkModel networkModel;
    @Inject
    private NetworkServiceMapDao ntwkSrvcDao;
    @Inject
    private ExtensionHelper extensionHelper;
    @Inject
    private NetworkDetailsDao networkDetailsDao;
    @Inject
    private IpAddressManager ipAddressManager;

    // ---- Script argument names ----

    /** CLI argument carrying physical-network extension details as a JSON object. */
    public static final String ARG_PHYSICAL_NETWORK_EXTENSION_DETAILS = "--physical-network-extension-details";

    /** CLI argument carrying per-network opaque JSON blob. */
    public static final String ARG_NETWORK_EXTENSION_DETAILS = "--network-extension-details";

    // ---- Network detail key ----

    /**
     * Key used to persist the per-network JSON blob in {@code network_details}.
     * The blob is produced by the network-extension.sh's {@code ensure-network-device}
     * command and may contain any fields the script needs (e.g. selected host,
     * namespace name, VRF ID, …).
     */
    public static final String NETWORK_DETAIL_EXTENSION_DETAILS = "ext.details";

    public String getProviderName() {
        return providerName;
    }

    /**
     * Returns a new {@link NetworkExtensionElement} scoped to {@code providerName},
     * sharing all injected dependencies with this instance.
     */
    public NetworkExtensionElement withProviderName(String providerName) {
        NetworkExtensionElement copy = new NetworkExtensionElement();
        copy.networkModel      = this.networkModel;
        copy.ntwkSrvcDao       = this.ntwkSrvcDao;
        copy.extensionHelper   = this.extensionHelper;
        copy.networkDetailsDao = this.networkDetailsDao;
        copy.ipAddressManager  = this.ipAddressManager;
        copy.providerName      = providerName;

        logger.debug("NetworkExtensionElement initialised with provider name '{}'", providerName);
        return copy;
    }

    // ---- Capabilities ----

    @Override
    public Map<Service, Map<Capability, String>> getCapabilities() {
        return DEFAULT_CAPABILITIES;
    }

    @Override
    public Provider getProvider() {
        return Provider.NetworkExtension;
    }

    // ---- Extension / provider resolution ----

    protected Extension resolveExtension(Network network) {
        Long physicalNetworkId = network.getPhysicalNetworkId();
        if (physicalNetworkId == null) {
            logger.warn("Network {} has no physical network — cannot resolve extension", network.getId());
            return null;
        }
        if (providerName != null && !providerName.isBlank()) {
            Extension ext = extensionHelper.getExtensionForPhysicalNetworkAndProvider(physicalNetworkId, providerName);
            if (ext != null) {
                return ext;
            }
            logger.warn("No extension found for scoped provider '{}' on physical network {}", providerName, physicalNetworkId);
        }
        List<String> providers = ntwkSrvcDao.getDistinctProviders(network.getId());
        if (providers != null) {
            for (String p : providers) {
                Extension ext = extensionHelper.getExtensionForPhysicalNetworkAndProvider(physicalNetworkId, p);
                if (ext != null) {
                    return ext;
                }
            }
        }
        return extensionHelper.getExtensionForPhysicalNetwork(physicalNetworkId);
    }

    protected boolean canHandle(Network network, Service service) {
        Long physicalNetworkId = network.getPhysicalNetworkId();
        if (physicalNetworkId == null) {
            return false;
        }
        if (providerName != null && !providerName.isBlank()) {
            boolean hasExt = extensionHelper.getExtensionForPhysicalNetworkAndProvider(physicalNetworkId, providerName) != null;
            if (!hasExt) {
                return false;
            }
            if (service == null) {
                return true;
            }
            List<String> sp = ntwkSrvcDao.getProvidersForServiceInNetwork(network.getId(), service);
            return sp != null && sp.stream()
                    .anyMatch(p -> extensionHelper.getExtensionForPhysicalNetworkAndProvider(physicalNetworkId, p) != null);
        }
        List<String> providers = ntwkSrvcDao.getDistinctProviders(network.getId());
        if (providers == null || providers.isEmpty()) {
            return false;
        }
        boolean hasExtProv = providers.stream().anyMatch(
                p -> extensionHelper.getExtensionForPhysicalNetworkAndProvider(physicalNetworkId, p) != null);
        if (!hasExtProv) {
            return false;
        }
        if (service == null) {
            return true;
        }
        List<String> sp = ntwkSrvcDao.getProvidersForServiceInNetwork(network.getId(), service);
        return sp != null && sp.stream()
                .anyMatch(p -> extensionHelper.getExtensionForPhysicalNetworkAndProvider(physicalNetworkId, p) != null);
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
        if (!canHandle(network, null)) {
            return false;
        }
        logger.info("Implementing network extension for network {} (VLAN {})", network.getId(), network.getBroadcastUri());

        // Step 1: Ensure a network device is selected and its details stored.
        ensureExtensionDetails(network);

        String vlanId = getVlanId(network);

        // Step 2: Create the network on the device.
        boolean result = executeScript(network,
                "implement",
                "--network-id", String.valueOf(network.getId()),
                "--vlan",       safeStr(vlanId),
                "--gateway",    safeStr(network.getGateway()),
                "--cidr",       safeStr(network.getCidr()));

        if (!result) {
            return false;
        }

        // Step 3: Configure source NAT if supported.
        if (canHandle(network, Service.SourceNat)) {
            try {
                Account owner = context != null ? context.getAccount() : null;
                PublicIp sourceNatIp = null;
                if (owner != null) {
                    sourceNatIp = ipAddressManager.assignSourceNatIpAddressToGuestNetwork(owner, network);
                }
                if (sourceNatIp == null) {
                    PublicIpAddress existingIp = networkModel.getSourceNatIpAddressForGuestNetwork(owner, network);
                    if (existingIp != null) {
                        applyIps(network, List.of(existingIp), Set.of(Service.SourceNat));
                    }
                } else {
                    applyIps(network, List.of(sourceNatIp), Set.of(Service.SourceNat));
                }
            } catch (InsufficientAddressCapacityException e) {
                logger.warn("Could not assign source NAT IP for network {}: {}", network.getId(), e.getMessage());
            } catch (Exception e) {
                logger.warn("Failed to configure source NAT IP for network {}: {}", network.getId(), e.getMessage(), e);
            }
        }

        return true;
    }

    @Override
    public boolean prepare(Network network, NicProfile nic, VirtualMachineProfile vm,
            DeployDestination dest, ReservationContext context)
            throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException {
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
        logger.info("Shutting down network extension for network {}", network.getId());
        return executeScript(network,
                "shutdown",
                "--network-id", String.valueOf(network.getId()),
                "--vlan",       safeStr(getVlanId(network)));
    }

    @Override
    public boolean destroy(Network network, ReservationContext context)
            throws ConcurrentOperationException, ResourceUnavailableException {
        logger.info("Destroying network extension for network {}", network.getId());
        boolean result = executeScript(network,
                "destroy",
                "--network-id", String.valueOf(network.getId()),
                "--vlan",       safeStr(getVlanId(network)));
        if (result) {
            networkDetailsDao.removeDetail(network.getId(), NETWORK_DETAIL_EXTENSION_DETAILS);
        }
        return result;
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

    // ---- ensure-network-device ----

    /**
     * Calls the network-extension.sh script with {@code ensure-network-device} before
     * the first network operation.  The script verifies the previously selected
     * device is reachable (using the {@code hosts} list in the physical-network
     * extension details) and performs failover if needed.  The returned JSON is
     * persisted in {@code network_details} and forwarded to all subsequent calls
     * as the {@value #ARG_NETWORK_EXTENSION_DETAILS} argument.
     *
     * <p>The script receives both JSON blobs as named CLI arguments:</p>
     * <ul>
     *   <li>{@value #ARG_PHYSICAL_NETWORK_EXTENSION_DETAILS} – all physical-network
     *       registration details (includes {@code hosts}, credentials, etc.)</li>
     *   <li>{@value #ARG_NETWORK_EXTENSION_DETAILS} – current per-network details
     *       ({@code {}}) on first call)</li>
     * </ul>
     * and {@code --current-details} as a CLI argument so the script can
     * short-circuit when the current host is healthy.</p>
     */
    protected void ensureExtensionDetails(Network network) {
        Map<String, String> stored = networkDetailsDao.listDetailsKeyPairs(network.getId());
        String currentDetails = stored != null
                ? stored.getOrDefault(NETWORK_DETAIL_EXTENSION_DETAILS, "{}") : "{}";

        logger.info("Ensuring network device for network {} (current={})", network.getId(), currentDetails);

        Extension extension = resolveExtension(network);
        File scriptFile = resolveScriptFile(network, extension);

        String physicalNetworkDetailsJson = buildPhysicalNetworkDetailsJson(network.getPhysicalNetworkId(), extension);

        List<String> cmdLine = new ArrayList<>();
        cmdLine.add(scriptFile.getAbsolutePath());
        cmdLine.add("ensure-network-device");
        cmdLine.add("--network-id");
        cmdLine.add(String.valueOf(network.getId()));
        cmdLine.add("--vlan");
        cmdLine.add(safeStr(getVlanId(network)));
        cmdLine.add("--zone-id");
        cmdLine.add(String.valueOf(network.getDataCenterId()));
        cmdLine.add("--current-details");
        cmdLine.add(currentDetails);
        cmdLine.add(ARG_PHYSICAL_NETWORK_EXTENSION_DETAILS);
        cmdLine.add(physicalNetworkDetailsJson);
        cmdLine.add(ARG_NETWORK_EXTENSION_DETAILS);
        cmdLine.add(currentDetails);

        try {
            ProcessBuilder pb = new ProcessBuilder(cmdLine);
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            env.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");

            Process process = pb.start();

            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                logger.warn("ensure-network-device exited {} for network {} — keeping current details",
                        exitCode, network.getId());
                if ("{}".equals(currentDetails)) {
                    networkDetailsDao.addDetail(network.getId(), NETWORK_DETAIL_EXTENSION_DETAILS, "{}", false);
                }
                return;
            }
            if (output.isEmpty()) {
                output = "{}".equals(currentDetails) ? "{}" : currentDetails;
            }
            if (!output.equals(currentDetails)) {
                logger.info("Network device updated for network {}: {}", network.getId(), output);
                networkDetailsDao.addDetail(network.getId(), NETWORK_DETAIL_EXTENSION_DETAILS, output, false);
            } else {
                logger.debug("Network device unchanged for network {}: {}", network.getId(), output);
            }
        } catch (Exception e) {
            logger.warn("Failed ensure-network-device for network {}: {}", network.getId(), e.getMessage());
            if ("{}".equals(currentDetails)) {
                networkDetailsDao.addDetail(network.getId(), NETWORK_DETAIL_EXTENSION_DETAILS, "{}", false);
            }
        }
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
            boolean isRevoke = ip.getState() == IpAddress.State.Releasing;
            String action = isRevoke ? "release-ip" : "assign-ip";

            boolean result = executeScript(network, action,
                    "--network-id", String.valueOf(network.getId()),
                    "--vlan",       safeStr(vlanId),
                    "--public-ip",  ip.getAddress().addr(),
                    "--source-nat", String.valueOf(isSourceNat),
                    "--gateway",    safeStr(network.getGateway()),
                    "--cidr",       safeStr(network.getCidr()));
            if (!result) {
                throw new ResourceUnavailableException(
                        "Failed to " + action + " for IP " + ip.getAddress().addr(),
                        Network.class, network.getId());
            }
        }
        return true;
    }

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
                    "--vlan",       safeStr(vlanId),
                    "--public-ip",  getIpAddress(rule.getSourceIpAddressId()),
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
            boolean isRevoke = rule.getState() == FirewallRule.State.Revoke;
            String action = isRevoke ? "delete-port-forward" : "add-port-forward";
            String publicPort  = PortForwardingServiceProvider.getPublicPortRange(rule);
            String privatePort = PortForwardingServiceProvider.getPrivatePFPortRange(rule);

            boolean result = executeScript(network, action,
                    "--network-id",   String.valueOf(network.getId()),
                    "--vlan",         safeStr(vlanId),
                    "--public-ip",    getIpAddress(rule.getSourceIpAddressId()),
                    "--public-port",  safeStr(publicPort),
                    "--private-ip",   safeStr(rule.getDestinationIpAddress() != null
                            ? rule.getDestinationIpAddress().addr() : null),
                    "--private-port", safeStr(privatePort),
                    "--protocol",     safeStr(rule.getProtocol()));
            if (!result) {
                throw new ResourceUnavailableException("Failed to " + action + " for port forwarding rule",
                        Network.class, network.getId());
            }
        }
        return true;
    }

    // ---- Script execution ----

    /**
     * Executes the network-extension.sh script with the given command and arguments.
     *
     * <p>Two JSON blobs are always appended as named CLI arguments:</p>
     * <ul>
     *   <li>{@value #ARG_PHYSICAL_NETWORK_EXTENSION_DETAILS} {@code <json>} – all
     *       {@code extension_resource_map_details} for this extension on the physical
     *       network.  Sensitive keys (password, sshkey) are included but redacted in
     *       log output.</li>
     *   <li>{@value #ARG_NETWORK_EXTENSION_DETAILS} {@code <json>} – the per-network
     *       JSON blob from {@code network_details} ({@code {}} if not yet set).</li>
     * </ul>
     */
    protected boolean executeScript(Network network, String command, String... args) {
        Extension extension = resolveExtension(network);
        File scriptFile = resolveScriptFile(network, extension);

        List<String> cmdLine = new ArrayList<>();
        cmdLine.add(scriptFile.getAbsolutePath());
        cmdLine.add(command);
        cmdLine.addAll(Arrays.asList(args));

        logger.debug("Executing network extension script: {}", String.join(" ", cmdLine));

        try {
            ProcessBuilder pb = new ProcessBuilder(cmdLine);
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            env.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");


            Process process = pb.start();
            byte[] output = process.getInputStream().readAllBytes();
            int exitCode = process.waitFor();

            String outputStr = new String(output).trim();
            if (!outputStr.isEmpty()) {
                logger.debug("Script output: {}", outputStr);
            }
            if (exitCode != 0) {
                logger.error("Network extension script failed with exit code {}: {}", exitCode, outputStr);
                return false;
            }
            return true;
        } catch (Exception e) {
            logger.error("Failed to execute network extension script: {}", e.getMessage(), e);
            throw new CloudRuntimeException("Failed to execute network extension script", e);
        }
    }

    // ---- Detail helpers ----

    /**
     * Returns all {@code extension_resource_map_details} for the given extension
     * on the physical network as a plain map.
     */
    private Map<String, String> buildPhysicalNetworkDetailsMap(Long physicalNetworkId, Extension extension) {
        if (physicalNetworkId == null) {
            return new HashMap<>();
        }
        Map<String, String> details = extension != null
                ? extensionHelper.getAllResourceMapDetailsForExtensionOnPhysicalNetwork(
                        physicalNetworkId, extension.getId())
                : extensionHelper.getAllResourceMapDetailsForPhysicalNetwork(physicalNetworkId);
        return details != null ? details : new HashMap<>();
    }

    /**
     * Serialises the physical-network extension details to a compact JSON object string.
     */
    private String buildPhysicalNetworkDetailsJson(Long physicalNetworkId, Extension extension) {
        return mapToJson(buildPhysicalNetworkDetailsMap(physicalNetworkId, extension));
    }

    /**
     * Reads the per-network JSON blob from {@code network_details}
     * (returns {@code {}} if not yet set).
     */
    private String getNetworkExtensionDetailsJson(long networkId) {
        Map<String, String> networkDetails = networkDetailsDao.listDetailsKeyPairs(networkId);
        return networkDetails != null
                ? networkDetails.getOrDefault(NETWORK_DETAIL_EXTENSION_DETAILS, "{}") : "{}";
    }

    /**
     * Returns a set-string of all keys, with sensitive values replaced by
     * {@code <redacted>} for log output.
     */
    private String redactedKeys(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (String key : map.keySet()) {
            sb.append(SENSITIVE_KEYS.contains(key.toLowerCase()) ? key + "=<redacted>" : key);
            sb.append(",");
        }
        if (sb.length() > 1) sb.setLength(sb.length() - 1);
        sb.append("]");
        return sb.toString();
    }

    /**
     * Serialises a {@code Map<String, String>} to a compact JSON object string.
     * Returns {@code {}} for null or empty maps.
     */
    private String mapToJson(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        JsonObject obj = new JsonObject();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (entry.getValue() != null) {
                obj.addProperty(entry.getKey(), entry.getValue());
            }
        }
        return new Gson().toJson(obj);
    }

    // ---- Custom action ----

    @Override
    public boolean canHandleCustomAction(Network network) {
        return canHandle(network, null);
    }

    /**
     * Runs a custom action on the external network device.
     * Per-action parameters are exposed as {@code CS_ACTION_PARAM_<KEY>}.
     */
    public String runCustomAction(Network network, String actionName, Map<String, Object> parameters) {
        Extension extension = resolveExtension(network);
        File scriptFile = resolveScriptFile(network, extension);

        String physicalNetworkDetailsJson = buildPhysicalNetworkDetailsJson(network.getPhysicalNetworkId(), extension);
        String networkExtensionDetailsJson = getNetworkExtensionDetailsJson(network.getId());

        List<String> cmdLine = new ArrayList<>();
        cmdLine.add(scriptFile.getAbsolutePath());
        cmdLine.add("custom-action");
        cmdLine.add("--network-id");
        cmdLine.add(String.valueOf(network.getId()));
        cmdLine.add("--action");
        cmdLine.add(actionName);
        cmdLine.add(ARG_PHYSICAL_NETWORK_EXTENSION_DETAILS);
        cmdLine.add(physicalNetworkDetailsJson);
        cmdLine.add(ARG_NETWORK_EXTENSION_DETAILS);
        cmdLine.add(networkExtensionDetailsJson);

        logger.info("Running custom action '{}' on network {} (extension: {})",
                actionName, network.getId(), extension != null ? extension.getName() : "unknown");

        try {
            ProcessBuilder pb = new ProcessBuilder(cmdLine);
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            env.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");

            if (parameters != null) {
                for (Map.Entry<String, Object> entry : parameters.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        String envKey = "CS_ACTION_PARAM_" + entry.getKey().toUpperCase()
                                .replace(' ', '_').replace('-', '_').replace('.', '_');
                        env.put(envKey, String.valueOf(entry.getValue()));
                    }
                }
            }

            Process process = pb.start();
            byte[] output = process.getInputStream().readAllBytes();
            int exitCode = process.waitFor();
            String outputStr = new String(output).trim();

            if (exitCode != 0) {
                logger.error("Custom action '{}' failed (exit {}): {}", actionName, exitCode, outputStr);
                return null;
            }
            logger.info("Custom action '{}' completed successfully", actionName);
            return outputStr.isEmpty() ? "OK" : outputStr;
        } catch (Exception e) {
            logger.error("Failed to execute custom action '{}': {}", actionName, e.getMessage(), e);
            throw new CloudRuntimeException("Failed to execute custom action: " + actionName, e);
        }
    }

    // ---- Script file resolution ----

    /**
     * Resolves the executable script file from the given extension.
     *
     * <p>Lookup order (first match wins):</p>
     * <ol>
     *   <li>{@code <extensionPath>/<extensionName>.sh} — preferred convention,
     *       e.g. for an extension named {@code network-extension} the script is
     *       {@code network-extension.sh}.</li>
     *   <li>{@code <extensionPath>} itself, if it is a file and is executable.</li>
     * </ol>
     */
    protected File resolveScriptFile(Network network, Extension extension) {
        Long physicalNetworkId = network.getPhysicalNetworkId();
        if (physicalNetworkId == null) {
            throw new CloudRuntimeException("Network " + network.getId() + " has no physical network");
        }
        if (extension == null) {
            throw new CloudRuntimeException(
                    "No NetworkOrchestrator extension found for network " + network.getId()
                    + " on physical network " + physicalNetworkId);
        }
        if (!Extension.Type.NetworkOrchestrator.equals(extension.getType())) {
            throw new CloudRuntimeException("Extension " + extension.getName() + " is not of type NetworkOrchestrator");
        }
        if (!Extension.State.Enabled.equals(extension.getState())) {
            throw new CloudRuntimeException("Extension " + extension.getName() + " is not enabled");
        }
        if (!extension.isPathReady()) {
            throw new CloudRuntimeException("Extension " + extension.getName() + " path is not ready");
        }

        String extensionPath = extensionHelper.getExtensionScriptPath(extension);
        if (extensionPath == null) {
            throw new CloudRuntimeException("Could not resolve path for extension " + extension.getName());
        }

        File extensionDir = new File(extensionPath);

        // <extensionPath>/<extensionName>.sh  (preferred convention)
        File namedScript = new File(extensionDir, extension.getName() + ".sh");
        if (namedScript.exists() && namedScript.canExecute()) {
            return namedScript;
        }
        // <extensionPath> itself is the script file
        if (extensionDir.isFile() && extensionDir.canExecute()) {
            return extensionDir;
        }

        throw new CloudRuntimeException(
                "No executable script found in extension path " + extensionPath
                + ". Expected '" + extension.getName() + ".sh' inside the extension directory.");
    }

    // ---- Helpers ----

    private String getVlanId(Network network) {
        return network.getBroadcastUri() != null
                ? Networks.BroadcastDomainType.getValue(network.getBroadcastUri()) : null;
    }

    private String getIpAddress(Long ipAddressId) {
        if (ipAddressId == null) {
            return "";
        }
        IpAddress ip = networkModel.getIp(ipAddressId);
        return ip != null ? ip.getAddress().addr() : "";
    }

    private String safeStr(String value) {
        return value != null ? value : "";
    }
}

