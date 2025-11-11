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

package org.apache.cloudstack.vnf.api.command;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiArgValidator;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.ZoneResponse;
import org.apache.cloudstack.vnf.VnfProviderConnection;
import org.apache.cloudstack.vnf.VnfProviderManager;
import org.apache.cloudstack.vnf.api.response.VnfBrokerResponse;
import org.apache.commons.collections.MapUtils;

import com.cloud.user.Account;

import javax.inject.Inject;
import java.util.Collection;
import java.util.Map;

@APICommand(name = "registerVnfBroker",
        description = "Registers a Vnf broker for a zone.",
        responseObject = VnfBrokerResponse.class,
        since = "4.22.1",
        requestHasSensitiveInfo = true,
        responseHasSensitiveInfo = false,
        authorized = {RoleType.Admin})
public class RegisterVnfBrokerCmd extends BaseCmd {

    @Inject
    VnfProviderManager vnfProviderManager;

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////
    @Parameter(name = ApiConstants.ZONE_ID,
            type = CommandType.UUID,
            entityType = ZoneResponse.class,
            required = true,
            description = "UUID of the zone which the Vnf broker belongs to.",
            validations = {ApiArgValidator.PositiveNumber})
    private Long zoneId;

    @Parameter(name = ApiConstants.NAME,
            type = CommandType.STRING,
            required = true,
            description = "Name of the Vnf broker")
    private String name;

    @Parameter(name = ApiConstants.DESCRIPTION,
            type = CommandType.STRING,
            description = "Description of the Vnf broker")
    private String description;

    @Parameter(name = ApiConstants.IP_ADDRESS,
            type = CommandType.STRING,
            description = "The IPv4 address of the Vnf broker")
    private String ipAddress;

    @Parameter(name = ApiConstants.ACCESS_METHOD,
            type = CommandType.STRING,
            description = "The access method of the Vnf broker")
    private String accessMethod;

    @Parameter(name = ApiConstants.DETAILS, type = CommandType.MAP,
            description = "Vnf broker details in key/value pairs")
    protected Map details;

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getAccessMethod() {
        return accessMethod;
    }

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Map<String, String> getDetails() {
        if (MapUtils.isEmpty(details)) {
            return null;
        }
        Collection<String> paramsCollection = this.details.values();
        return (Map<String, String>) (paramsCollection.toArray())[0];
    }

    @Override
    public void execute() {
        VnfProviderConnection result = vnfProviderManager.registerVnfBroker(this);
        if (result != null) {
            VnfBrokerResponse response = vnfProviderManager.createVnfBrokerResponse(result);
            response.setResponseName(getCommandName());
            this.setResponseObject(response);
        } else {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to create Vnf broker.");
        }
    }

    @Override
    public long getEntityOwnerId() {
        return Account.ACCOUNT_ID_SYSTEM;
    }

}
