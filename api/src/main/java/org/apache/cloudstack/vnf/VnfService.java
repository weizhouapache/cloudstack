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

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public interface VnfService {

    enum ServiceCategory {
        FIREWALL,                   // Security
        NAT,                        // NAT, port forwarding
        ROUTING,                    // Static route, BGP, OSPF, policy-based route
        INTERFACE_MANAGEMENT,       // IP, VLAN, Bridge, Bond
        ADDRESS_MANAGEMENT,         // DHCP, DNS, IPAM
        VPN,                        // Site-to-Site VPN, Remote access VPN, OpenVPN, Wireguard
        LOAD_BALANCING,             // Load balancer, haproxy, nginx
        TRAFFIC_MANAGEMENT,         // traffic shaper, QoS, bandwidth
        SYSTEM_MANAGEMENT,          // reboot server, update server, manage services, create or restore backup
        USER_MANAGEMENT,            // add user, remove user, reset user password, add or remove token
        MONITORING                  // system logs, system metrics, health check, statistics
    }

    enum VnfOperation {
        // Firewall Operations
        FIREWALL_RULE_CREATE("FIREWALL_RULE_CREATE", ServiceCategory.FIREWALL, "Create new firewall rule"),
        FIREWALL_RULE_UPDATE("FIREWALL_RULE_UPDATE", ServiceCategory.FIREWALL, "Modify existing firewall rule"),
        FIREWALL_RULE_DELETE("FIREWALL_RULE_DELETE", ServiceCategory.FIREWALL, "Delete firewall rule"),
        FIREWALL_RULE_LIST("FIREWALL_RULE_LIST", ServiceCategory.FIREWALL, "List all firewall rules"),

        // NAT Operations
        NAT_PORT_FORWARD_CREATE("NAT_PORT_FORWARD_CREATE", ServiceCategory.NAT, "Create port forwarding rule"),
        NAT_SOURCE_CREATE("NAT_SOURCE_CREATE", ServiceCategory.NAT, "Create source NAT rule"),
        NAT_DESTINATION_CREATE("NAT_DESTINATION_CREATE", ServiceCategory.NAT, "Create destination NAT rule"),
        NAT_RULE_DELETE("NAT_RULE_DELETE", ServiceCategory.NAT, "Delete NAT rule"),

        // Routing Operations
        STATIC_ROUTE_ADD("STATIC_ROUTE_ADD", ServiceCategory.ROUTING, "Add static route"),
        STATIC_ROUTE_DELETE("STATIC_ROUTE_DELETE", ServiceCategory.ROUTING, "Delete static route"),
        BGP_NEIGHBOR_CONFIGURE("BGP_NEIGHBOR_CONFIGURE", ServiceCategory.ROUTING, "Configure BGP neighbor"),
        BGP_NEIGHBOR_REMOVE("BGP_NEIGHBOR_REMOVE", ServiceCategory.ROUTING, "Remove BGP neighbor"),
        OSPF_AREA_CONFIGURE("OSPF_AREA_CONFIGURE", ServiceCategory.ROUTING, "Configure OSPF area"),
        POLICY_ROUTE_CREATE("POLICY_ROUTE_CREATE", ServiceCategory.ROUTING, "Create policy-based route"),

        // DHCP Operations
        DHCP_SERVER_CONFIGURE("DHCP_SERVER_CONFIGURE", ServiceCategory.ADDRESS_MANAGEMENT, "Configure DHCP server"),
        DHCP_SERVER_START("DHCP_SERVER_START", ServiceCategory.ADDRESS_MANAGEMENT, "Start DHCP server"),
        DHCP_SERVER_STOP("DHCP_SERVER_STOP", ServiceCategory.ADDRESS_MANAGEMENT, "Stop DHCP server"),
        DHCP_STATIC_LEASE_ADD("DHCP_STATIC_LEASE_ADD", ServiceCategory.ADDRESS_MANAGEMENT, "Add DHCP static lease"),
        DHCP_STATIC_LEASE_REMOVE("DHCP_STATIC_LEASE_REMOVE", ServiceCategory.ADDRESS_MANAGEMENT, "Remove DHCP static lease"),

        // DNS Operations
        DNS_RESOLVER_CONFIGURE("DNS_RESOLVER_CONFIGURE", ServiceCategory.ADDRESS_MANAGEMENT, "Configure DNS resolver"),
        DNS_HOST_OVERRIDE_ADD("DNS_HOST_OVERRIDE_ADD", ServiceCategory.ADDRESS_MANAGEMENT, "Add DNS host override"),
        DNS_HOST_OVERRIDE_REMOVE("DNS_HOST_OVERRIDE_REMOVE", ServiceCategory.ADDRESS_MANAGEMENT, "Remove DNS host override"),
        DNS_FORWARDER_CONFIGURE("DNS_FORWARDER_CONFIGURE", ServiceCategory.ADDRESS_MANAGEMENT, "Configure DNS forwarder"),

        // VPN Operations
        VPN_IPSEC_TUNNEL_CREATE("VPN_IPSEC_TUNNEL_CREATE", ServiceCategory.VPN, "Create IPSec tunnel"),
        VPN_IPSEC_TUNNEL_DELETE("VPN_IPSEC_TUNNEL_DELETE", ServiceCategory.VPN, "Delete IPSec tunnel"),
        VPN_OPENVPN_SERVER_CONFIGURE("VPN_OPENVPN_SERVER_CONFIGURE", ServiceCategory.VPN, "Configure OpenVPN server"),
        VPN_OPENVPN_CLIENT_CONFIGURE("VPN_OPENVPN_CLIENT_CONFIGURE", ServiceCategory.VPN, "Configure OpenVPN client"),

        // Load Balancing Operations
        LOAD_BALANCER_POOL_CREATE("LOAD_BALANCER_POOL_CREATE", ServiceCategory.LOAD_BALANCING, "Create load balancer pool"),
        LOAD_BALANCER_VIRTUAL_SERVER_CREATE("LOAD_BALANCER_VIRTUAL_SERVER_CREATE", ServiceCategory.LOAD_BALANCING, "Create load balancer virtual server"),
        LOAD_BALANCER_VIRTUAL_SERVER_DELETE("LOAD_BALANCER_VIRTUAL_SERVER_DELETE", ServiceCategory.LOAD_BALANCING, "Delete load balancer virtual server"),
        LOAD_BALANCER_MEMBER_ADD("LOAD_BALANCER_MEMBER_ADD", ServiceCategory.LOAD_BALANCING, "Add member to load balancer pool"),
        LOAD_BALANCER_MEMBER_REMOVE("LOAD_BALANCER_MEMBER_REMOVE", ServiceCategory.LOAD_BALANCING, "Remove member from load balancer pool"),

        // Interface Operations
        INTERFACE_CONFIGURE("INTERFACE_CONFIGURE", ServiceCategory.INTERFACE_MANAGEMENT, "Configure network interface"),
        INTERFACE_VLAN_CREATE("INTERFACE_VLAN_CREATE", ServiceCategory.INTERFACE_MANAGEMENT, "Create VLAN interface"),
        INTERFACE_BRIDGE_CREATE("INTERFACE_BRIDGE_CREATE", ServiceCategory.INTERFACE_MANAGEMENT, "Create bridge interface"),
        INTERFACE_BOND_CREATE("INTERFACE_BOND_CREATE", ServiceCategory.INTERFACE_MANAGEMENT, "Create bond interface"),

        // System Operations
        SYSTEM_REBOOT("SYSTEM_REBOOT", ServiceCategory.SYSTEM_MANAGEMENT, "Reboot system"),
        SERVICE_RESTART("SERVICE_RESTART", ServiceCategory.SYSTEM_MANAGEMENT, "Restart service");

        private final String operationCode;
        private final ServiceCategory category;
        private final String description;

        VnfOperation(String operationCode, ServiceCategory category, String description) {
            this.operationCode = operationCode;
            this.category = category;
            this.description = description;
        }

        public String getOperationCode() { return operationCode; }
        public ServiceCategory getCategory() { return category; }
        public String getDescription() { return description; }

        public static List<VnfOperation> getOperationsByCategory(ServiceCategory category) {
            return Arrays.stream(values())
                    .filter(op -> op.getCategory() == category)
                    .collect(Collectors.toList());
        }
    }

    List<VnfProvider> getVnfProviders();
}
