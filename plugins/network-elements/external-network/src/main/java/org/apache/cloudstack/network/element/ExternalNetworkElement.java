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
 * The script path is resolved dynamically from a {@link Extension} of type
 * {@code NetworkOrchestrator} that is associated with the network's physical
 * network via the Extension Resource Map. Administrators create the extension
 * via the API (e.g. {@code createExtension}), place their script(s) in the
 * extension's path, then associate the extension with a physical network.
 *
 * The element looks for an executable file named by the extension's relative
 * path under the global extensions directory. The script receives a command
 * as the first argument followed by key-value options.
 */
public class ExternalNetworkElement extends AdapterBase implements
        NetworkElement, SourceNatServiceProvider, StaticNatServiceProvider,
        PortForwardingServiceProvider, IpDeployer {

    private static final Map<Service, Map<Capability, String>> capabilities = initCapabilities();

    @Inject
    private NetworkModel networkModel;
    @Inject
    private NetworkServiceMapDao ntwkSrvcDao;
    @Inject
    private ExtensionHelper extensionHelper;

    private static Map<Service, Map<Capability, String>> initCapabilities() {
        Map<Service, Map<Capability, String>> caps = new HashMap<>();

        // Source NAT
        Map<Capability, String> sourceNatCaps = new HashMap<>();
        sourceNatCaps.put(Capability.SupportedSourceNatTypes, "peraccount");
        sourceNatCaps.put(Capability.RedundantRouter, "false");
        caps.put(Service.SourceNat, sourceNatCaps);

        // Static NAT
        caps.put(Service.StaticNat, new HashMap<>());

        // Port Forwarding
        caps.put(Service.PortForwarding, new HashMap<>());

        // Firewall
        Map<Capability, String> firewallCaps = new HashMap<>();
        firewallCaps.put(Capability.TrafficStatistics, "per public ip");
        caps.put(Service.Firewall, firewallCaps);

        // Gateway
        caps.put(Service.Gateway, new HashMap<>());

        return caps;
    }

    @Override
    public Map<Service, Map<Capability, String>> getCapabilities() {
        return capabilities;
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

    // ---- Extension-based script resolution ----

    /**
     * Resolves the script path from the NetworkOrchestrator extension associated
     * with the network's physical network and executes it.
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
            pb.environment().put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
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
     * Resolves the script file from the NetworkOrchestrator extension associated
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

