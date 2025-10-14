/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cloudstack.vnf;

import org.apache.cloudstack.api.command.user.vnf.BaseVnfCmd;
import org.apache.cloudstack.vnf.VnfService.ServiceCategory;
import org.apache.cloudstack.vnf.VnfService.VnfOperation;

import java.util.List;
import java.util.Map;

public class DummyVnfProvider extends BaseVnfProvider {

    @Override
    public Map<ServiceCategory, List<VnfOperation>> getSupportedOperations() {
        return Map.of(
                ServiceCategory.INTERFACE_MANAGEMENT, List.of(VnfOperation.INTERFACE_CONFIGURE),
                ServiceCategory.FIREWALL_RULES, List.of(VnfOperation.FIREWALL_RULE_CREATE, VnfOperation.FIREWALL_RULE_DELETE, VnfOperation.FIREWALL_RULE_UPDATE, VnfOperation.FIREWALL_RULE_LIST),
                ServiceCategory.LOAD_BALANCING, List.of(VnfOperation.NAT_SOURCE_CREATE, VnfOperation.NAT_DESTINATION_CREATE, VnfOperation.NAT_RULE_DELETE, VnfOperation.NAT_PORT_FORWARD_CREATE),
                ServiceCategory.DHCP, List.of(VnfOperation.DHCP_SERVER_CONFIGURE, VnfOperation.DHCP_SERVER_RESTART, VnfOperation.DHCP_STATIC_LEASE_ADD, VnfOperation.DHCP_STATIC_LEASE_REMOVE),
                ServiceCategory.DNS, List.of(VnfOperation.DNS_HOST_OVERRIDE_ADD, VnfOperation.DNS_HOST_OVERRIDE_REMOVE)
        );
    }

    @Override
    public VnfConnector getConnector(BaseVnfCmd command) {
        return new VnfSshConnector();
    }

    @Override
    public VnfDataFormatHandler getDataFormatHandler(BaseVnfCmd command) {
        return new VnfPlainTextHandler();
    }
}
