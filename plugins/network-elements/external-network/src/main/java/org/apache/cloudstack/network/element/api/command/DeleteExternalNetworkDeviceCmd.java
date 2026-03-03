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

import javax.inject.Inject;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.PhysicalNetworkResponse;
import org.apache.cloudstack.api.response.SuccessResponse;
import org.apache.cloudstack.network.element.ExternalNetworkDeviceService;

import com.cloud.user.Account;

/**
 * Removes the external network device details from a physical network.
 *
 * <p>This deletes all device connection details (host, port, username, password,
 * sshkey, and any extra details) stored in {@code extension_resource_map_details}
 * for the NetworkOrchestrator extension registered with the given physical network.
 * It does NOT unregister the extension itself.</p>
 *
 * <p>Example:</p>
 * <pre>
 *   cmk deleteExternalNetworkDevice physicalnetworkid=&lt;uuid&gt;
 * </pre>
 */
@APICommand(name = "deleteExternalNetworkDevice",
        description = "Removes all external network device details from a physical network. "
                + "The NetworkOrchestrator extension registration itself is NOT removed.",
        responseObject = SuccessResponse.class,
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false,
        authorized = {RoleType.Admin},
        since = "4.22.0")
public class DeleteExternalNetworkDeviceCmd extends BaseCmd {

    @Inject
    ExternalNetworkDeviceService externalNetworkDeviceService;

    @Parameter(name = ApiConstants.PHYSICAL_NETWORK_ID, type = CommandType.UUID,
            entityType = PhysicalNetworkResponse.class, required = true,
            description = "UUID of the physical network whose device details should be deleted")
    private Long physicalNetworkId;

    public Long getPhysicalNetworkId() { return physicalNetworkId; }

    @Override
    public long getEntityOwnerId() {
        return Account.ACCOUNT_ID_SYSTEM;
    }

    @Override
    public void execute() {
        try {
            externalNetworkDeviceService.deleteExternalNetworkDevice(this);
            SuccessResponse response = new SuccessResponse(getCommandName());
            setResponseObject(response);
        } catch (Exception e) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }
}

