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

import java.util.Map;

import javax.inject.Inject;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.PhysicalNetworkResponse;
import org.apache.cloudstack.network.element.api.response.ExternalNetworkDeviceResponse;
import org.apache.cloudstack.network.element.ExternalNetworkDeviceService;

import com.cloud.user.Account;

/**
 * Updates the connection details of an external network device.
 *
 * <p>Only fields that are provided will be updated; omitted fields are left unchanged.</p>
 *
 * <p>Example:</p>
 * <pre>
 *   cmk updateExternalNetworkDevice physicalnetworkid=&lt;uuid&gt; host=192.168.1.20
 *   cmk updateExternalNetworkDevice physicalnetworkid=&lt;uuid&gt; \
 *       details[0].key=username details[0].value=admin \
 *       details[1].key=sshkey   details[1].value="$(cat new_key)"
 * </pre>
 */
@APICommand(name = "updateExternalNetworkDevice",
        description = "Updates the connection details of an external network device. "
                + "Only supplied fields are changed; others are left intact. "
                + "Pass credentials and custom properties via the details map.",
        responseObject = ExternalNetworkDeviceResponse.class,
        requestHasSensitiveInfo = true,
        responseHasSensitiveInfo = false,
        authorized = {RoleType.Admin},
        since = "4.22.0")
public class UpdateExternalNetworkDeviceCmd extends BaseCmd {

    @Inject
    ExternalNetworkDeviceService externalNetworkDeviceService;

    @Parameter(name = ApiConstants.PHYSICAL_NETWORK_ID, type = CommandType.UUID,
            entityType = PhysicalNetworkResponse.class, required = true,
            description = "UUID of the physical network whose device should be updated")
    private Long physicalNetworkId;

    @Parameter(name = "host", type = CommandType.STRING,
            description = "New IP address or hostname")
    private String host;

    @Parameter(name = "port", type = CommandType.INTEGER,
            description = "New SSH / API port")
    private Integer port;

    @Parameter(name = ApiConstants.DETAILS, type = CommandType.MAP,
            description = "Extra device details to add or update (key/value pairs). "
                    + "Use to set/update credentials: details[0].key=username details[0].value=root. "
                    + "Keys 'password' and 'sshkey' are stored with display=false. "
                    + "Existing keys not mentioned here are left unchanged.")
    private Map details;

    public Long getPhysicalNetworkId() { return physicalNetworkId; }
    public String getHost()            { return host; }
    public Integer getPort()           { return port; }
    public Map getDetails()            { return details; }

    @Override
    public long getEntityOwnerId() {
        return Account.ACCOUNT_ID_SYSTEM;
    }

    @Override
    public void execute() {
        try {
            ExternalNetworkDeviceResponse response =
                    externalNetworkDeviceService.updateExternalNetworkDevice(this);
            response.setResponseName(getCommandName());
            response.setObjectName("externalnetworkdevice");
            setResponseObject(response);
        } catch (Exception e) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }
}

