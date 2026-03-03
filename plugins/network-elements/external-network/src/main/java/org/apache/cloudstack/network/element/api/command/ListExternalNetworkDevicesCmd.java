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
package org.apache.cloudstack.network.element.api.command;

import java.util.List;

import javax.inject.Inject;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseListCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.PhysicalNetworkResponse;
import org.apache.cloudstack.network.element.api.response.ExternalNetworkDeviceResponse;
import org.apache.cloudstack.network.element.ExternalNetworkDeviceService;

/**
 * Lists external network devices registered with physical networks.
 *
 * <p>Sensitive fields (password, sshkey) are never included in the response.</p>
 *
 * <p>Example:</p>
 * <pre>
 *   cmk listExternalNetworkDevices physicalnetworkid=&lt;uuid&gt;
 *   cmk listExternalNetworkDevices   # lists all
 * </pre>
 */
@APICommand(name = "listExternalNetworkDevices",
        description = "Lists external network devices registered with physical networks.",
        responseObject = ExternalNetworkDeviceResponse.class,
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false,
        authorized = {RoleType.Admin},
        since = "4.22.0")
public class ListExternalNetworkDevicesCmd extends BaseListCmd {

    @Inject
    ExternalNetworkDeviceService externalNetworkDeviceService;

    @Parameter(name = ApiConstants.PHYSICAL_NETWORK_ID, type = CommandType.UUID,
            entityType = PhysicalNetworkResponse.class,
            description = "UUID of the physical network to filter by (optional)")
    private Long physicalNetworkId;

    public Long getPhysicalNetworkId() { return physicalNetworkId; }

    @Override
    public void execute() {
        List<ExternalNetworkDeviceResponse> devices =
                externalNetworkDeviceService.listExternalNetworkDevices(this);
        ListResponse<ExternalNetworkDeviceResponse> response = new ListResponse<>();
        response.setResponses(devices, devices.size());
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}

