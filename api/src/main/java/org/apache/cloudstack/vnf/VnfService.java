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
package org.apache.cloudstack.vnf;

import org.apache.cloudstack.api.command.user.vnf.BaseVnfCmd;
import org.apache.cloudstack.framework.config.ConfigKey;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public interface VnfService {

    ConfigKey<Boolean> VnfFrameworkEnabled = new ConfigKey<>("Advanced", Boolean.class, "vnf.framework.enabled", "true",
            "If VNF (Virtual Network Functions) framework is enabled", true, ConfigKey.Scope.Global);

    enum DataFormat {
        PLAINTEXT,
        JSON,
        XML,
        YAML
    }

    enum Connector {
        SSH,
        HTTP,
        HTTPS
    }

    Set<ServiceCategory> getSupportedServices(VnfProvider vnfProvider);

    enum ServiceCategory {
        // Interfaces
        INTERFACE_MANAGEMENT,       // Interfaces, IP, VLAN, Bridge, Bonding

        // Networking
        ROUTING,                    // Static route, BGP, OSPF, policy-based route
        WIRELESS,                   // Wireless interfaces, and settings

        // Firewall
        FIREWALL_RULES,             // Firewall rules and policies
        NAT,                        // Source NAT, destination NAT, port forwarding
        TRAFFIC_MANAGEMENT,         // traffic shaper, QoS, bandwidth
        VIRTUAL_IPS,                // Add virtual IP addresses (aliases, CARP, proxy ARP, etc.) for use in rules or NAT

        // Services
        LOAD_BALANCING,             // Load balancer, haproxy, nginx
        DHCP,                       // DHCP server, scopes, leases, reservations
        DNS,                        // DNS resolution, forwarding, zones, records
        VPN,                        // Site-to-Site VPN, Remote access VPN, OpenVPN, Wireguard

        // System
        SYSTEM_MANAGEMENT,          // reboot server, update server, manage services, create or restore backup
        USER_MANAGEMENT,            // add user, remove user, reset user password, add or remove token

        // Security
        IDS_IPS_MANAGEMENT,         // Intrusion Detection and Prevention System

        // Status
        EVENT_MANAGEMENT,           // system logs, events
        MONITORING;                  // system metrics, service status, health check, statistics

        public static ServiceCategory getService(String serviceName) {
            return Arrays.stream(values())
                    .filter(service -> service.name().equalsIgnoreCase(serviceName))
                    .findFirst().orElse(null);
        }
    }


    enum VnfOperation {
        // Firewall Operations
        FIREWALL_RULE_CREATE(ServiceCategory.FIREWALL_RULES, "Create new firewall rule"),
        FIREWALL_RULE_UPDATE(ServiceCategory.FIREWALL_RULES, "Modify existing firewall rule"),
        FIREWALL_RULE_DELETE(ServiceCategory.FIREWALL_RULES, "Delete firewall rule"),
        FIREWALL_RULE_LIST(ServiceCategory.FIREWALL_RULES, "List all firewall rules"),

        // NAT Operations
        NAT_PORT_FORWARD_CREATE(ServiceCategory.NAT, "Create port forwarding rule"),
        NAT_SOURCE_CREATE(ServiceCategory.NAT, "Create source NAT rule"),
        NAT_DESTINATION_CREATE(ServiceCategory.NAT, "Create destination NAT rule"),
        NAT_RULE_DELETE(ServiceCategory.NAT, "Delete NAT rule"),

        // Routing Operations
        STATIC_ROUTE_ADD(ServiceCategory.ROUTING, "Add static route"),
        STATIC_ROUTE_DELETE(ServiceCategory.ROUTING, "Delete static route"),
        BGP_NEIGHBOR_CONFIGURE(ServiceCategory.ROUTING, "Configure BGP neighbor"),
        BGP_NEIGHBOR_REMOVE(ServiceCategory.ROUTING, "Remove BGP neighbor"),
        OSPF_AREA_CONFIGURE(ServiceCategory.ROUTING, "Configure OSPF area"),
        POLICY_ROUTE_CREATE(ServiceCategory.ROUTING, "Create policy-based route"),

        // DHCP Operations
        DHCP_SERVER_CONFIGURE(ServiceCategory.DHCP, "Configure DHCP server"),
        DHCP_SERVER_START(ServiceCategory.DHCP, "Start DHCP server"),
        DHCP_SERVER_STOP(ServiceCategory.DHCP, "Stop DHCP server"),
        DHCP_STATIC_LEASE_ADD(ServiceCategory.DHCP, "Add DHCP static lease"),
        DHCP_STATIC_LEASE_REMOVE(ServiceCategory.DHCP, "Remove DHCP static lease"),

        // DNS Operations
        DNS_RESOLVER_CONFIGURE(ServiceCategory.DNS, "Configure DNS resolver"),
        DNS_HOST_OVERRIDE_ADD(ServiceCategory.DNS, "Add DNS host override"),
        DNS_HOST_OVERRIDE_REMOVE(ServiceCategory.DNS, "Remove DNS host override"),
        DNS_FORWARDER_CONFIGURE(ServiceCategory.DNS, "Configure DNS forwarder"),

        // VPN Operations
        VPN_IPSEC_TUNNEL_CREATE(ServiceCategory.VPN, "Create IPSec tunnel"),
        VPN_IPSEC_TUNNEL_DELETE(ServiceCategory.VPN, "Delete IPSec tunnel"),
        VPN_OPENVPN_SERVER_CONFIGURE(ServiceCategory.VPN, "Configure OpenVPN server"),
        VPN_OPENVPN_CLIENT_CONFIGURE(ServiceCategory.VPN, "Configure OpenVPN client"),

        // Load Balancing Operations
        LOAD_BALANCER_POOL_CREATE(ServiceCategory.LOAD_BALANCING, "Create load balancer pool"),
        LOAD_BALANCER_VIRTUAL_SERVER_CREATE(ServiceCategory.LOAD_BALANCING, "Create load balancer virtual server"),
        LOAD_BALANCER_VIRTUAL_SERVER_DELETE(ServiceCategory.LOAD_BALANCING, "Delete load balancer virtual server"),
        LOAD_BALANCER_MEMBER_ADD(ServiceCategory.LOAD_BALANCING, "Add member to load balancer pool"),
        LOAD_BALANCER_MEMBER_REMOVE(ServiceCategory.LOAD_BALANCING, "Remove member from load balancer pool"),

        // Interface Operations
        INTERFACE_CONFIGURE(ServiceCategory.INTERFACE_MANAGEMENT, "Configure network interface"),
        INTERFACE_VLAN_CREATE(ServiceCategory.INTERFACE_MANAGEMENT, "Create VLAN interface"),
        INTERFACE_BRIDGE_CREATE(ServiceCategory.INTERFACE_MANAGEMENT, "Create bridge interface"),
        INTERFACE_BOND_CREATE(ServiceCategory.INTERFACE_MANAGEMENT, "Create bond interface"),

        // System management
        SYSTEM_REBOOT(ServiceCategory.SYSTEM_MANAGEMENT, "Reboot system"),
        SERVICE_RESTART(ServiceCategory.SYSTEM_MANAGEMENT, "Restart service"),
        PACKAGE_INSTALL(ServiceCategory.SYSTEM_MANAGEMENT, "Install package"),
        PACKAGE_UNINSTALL(ServiceCategory.SYSTEM_MANAGEMENT, "Uninstall package"),

        // User management
        USER_ADD(ServiceCategory.USER_MANAGEMENT, "Add user"),
        USER_REMOVE(ServiceCategory.USER_MANAGEMENT, "Remove user"),
        USER_LIST(ServiceCategory.USER_MANAGEMENT, "List users"),
        USER_RESET_PASSWORD(ServiceCategory.USER_MANAGEMENT, "Reset user password");

        private final ServiceCategory category;
        private final String description;

        VnfOperation(ServiceCategory category, String description) {
            this.category = category;
            this.description = description;
        }

        public ServiceCategory getCategory() { return category; }
        public String getDescription() { return description; }

        public static List<VnfOperation> getOperationsByCategory(ServiceCategory category) {
            return Arrays.stream(values())
                    .filter(op -> op.getCategory() == category)
                    .collect(Collectors.toList());
        }
    }

    List<VnfProvider> getVnfProviders();

    VnfProvider getVnfProviderByName(String name);

    void executeVnfCommand(BaseVnfCmd command);
}
