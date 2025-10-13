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
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.vnf.VnfBroker;
import org.apache.cloudstack.vnf.VnfBrokerManager;
import org.apache.cloudstack.vnf.api.response.VnfBrokerResponse;
import org.apache.commons.collections.MapUtils;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.user.Account;
import com.cloud.utils.exception.CloudRuntimeException;

import javax.inject.Inject;
import java.util.Collection;
import java.util.Map;

@APICommand(name = "updateVnfBroker",
        description = "Updates an existing Vnf broker.",
        responseObject = VnfBrokerResponse.class,
        since = "4.22.0",
        requestHasSensitiveInfo = true,
        responseHasSensitiveInfo = false,
        authorized = {RoleType.Admin})
public class UpdateVnfBrokerCmd extends BaseCmd {

    @Inject
    VnfBrokerManager vnfBrokerManager;

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, entityType = VnfBrokerResponse.class, required = true, description = "Id of the Vnf broker")
    private Long id;

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
            description = "The IPv4 address of the Vnf broker.")
    private String ipAddress;

    @Parameter(name = ApiConstants.DETAILS, type = CommandType.MAP,
            description = "Vnf broker details in key/value pairs.")
    protected Map details;

    @Parameter(name = ApiConstants.CLEAN_UP_DETAILS,
            type = CommandType.BOOLEAN,
            description = "optional boolean field, which indicates if details should be cleaned up or not (if set to true, details are removed for this resource; if false or not set, no action)")
    private Boolean cleanupDetails;

    public Long getId() {
        return id;
    }

    public Map<String, String> getDetails() {
        if (MapUtils.isEmpty(details)) {
            return null;
        }
        Collection<String> paramsCollection = this.details.values();
        return (Map<String, String>) (paramsCollection.toArray())[0];
    }

    public boolean isCleanupDetails(){
        return cleanupDetails == null ? false : cleanupDetails.booleanValue();
    }

    @Override
    public void execute() {
        try {
            VnfBroker result = vnfBrokerManager.updateVnfBroker(this);
            if (result != null) {
                VnfBrokerResponse response = vnfBrokerManager.createVnfBrokerResponse(result);
                response.setResponseName(getCommandName());
                this.setResponseObject(response);
            } else {
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to update Vnf broker:" + getId());
            }
        } catch (InvalidParameterValueException ex) {
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR, ex.getMessage());
        } catch (CloudRuntimeException ex) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, ex.getMessage());
        }

    }

    @Override
    public long getEntityOwnerId() {
        return Account.ACCOUNT_ID_SYSTEM;
    }
}
