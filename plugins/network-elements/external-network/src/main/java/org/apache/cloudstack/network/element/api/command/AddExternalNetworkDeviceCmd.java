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
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.response.PhysicalNetworkResponse;
import org.apache.cloudstack.network.element.api.response.ExternalNetworkDeviceResponse;
import org.apache.cloudstack.network.element.ExternalNetworkDeviceService;

import com.cloud.user.Account;

/**
 * Registers an external network device (Linux host / network appliance) with
 * a physical network for the ExternalNetwork provider.
 *
 * <p>Internally this stores the connection details as
 * {@code extension_resource_map_details} on the extension that is already
 * registered with the physical network.  Sensitive fields (password, sshkey)
 * are stored with {@code display=false} and are never returned by
 * {@code listExternalNetworkDevices}.</p>
 *
 * <p>Example:</p>
 * <pre>
 *   cmk addExternalNetworkDevice physicalnetworkid=&lt;uuid&gt; \
 *       host=192.168.1.10 port=22 \
 *       details[0].key=username details[0].value=root \
 *       details[1].key=sshkey   details[1].value="$(cat /root/.ssh/id_rsa)"
 * </pre>
 */
@APICommand(name = "addExternalNetworkDevice",
        description = "Adds an external network device to a physical network. "
                + "The device details (host, port, and any extra details such as username/password/sshkey) "
                + "are stored as extension_resource_map_details on the NetworkOrchestrator extension "
                + "registered with the physical network. "
                + "Sensitive keys (password, sshkey) are stored with display=false and never returned.",
        responseObject = ExternalNetworkDeviceResponse.class,
        requestHasSensitiveInfo = true,
        responseHasSensitiveInfo = false,
        authorized = {RoleType.Admin},
        since = "4.22.0")
public class AddExternalNetworkDeviceCmd extends BaseCmd {

    @Inject
    ExternalNetworkDeviceService externalNetworkDeviceService;

    @Parameter(name = ApiConstants.PHYSICAL_NETWORK_ID, type = CommandType.UUID,
            entityType = PhysicalNetworkResponse.class, required = true,
            description = "UUID of the physical network. A NetworkOrchestrator extension must already be registered with it.")
    private Long physicalNetworkId;

    @Parameter(name = "host", type = CommandType.STRING, required = true,
            description = "IP address or hostname of the external network device")
    private String host;

    @Parameter(name = "port", type = CommandType.INTEGER,
            description = "SSH / API port of the external network device (default: 22)")
    private Integer port;

    @Parameter(name = ApiConstants.DETAILS, type = CommandType.MAP,
            description = "Extra device details in key/value pairs. "
                    + "Use this to pass credentials and any custom properties, e.g.: "
                    + "details[0].key=username details[0].value=root "
                    + "details[1].key=password details[1].value=secret "
                    + "details[2].key=sshkey   details[2].value=\"$(cat id_rsa)\". "
                    + "Keys 'password' and 'sshkey' are stored with display=false and never returned by list.")
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
                    externalNetworkDeviceService.addExternalNetworkDevice(this);
            response.setResponseName(getCommandName());
            response.setObjectName("externalnetworkdevice");
            setResponseObject(response);
        } catch (Exception e) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, e.getMessage());
        }
    }
}

