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
import org.apache.cloudstack.api.response.VnfProviderResponse;
import org.apache.cloudstack.vnf.VnfProviderManager;
import org.apache.cloudstack.vnf.VnfProvider;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.user.Account;
import com.cloud.utils.exception.CloudRuntimeException;

import javax.inject.Inject;

@APICommand(name = "updateVnfProvider",
        description = "Updates an existing Vnf provider.",
        responseObject = VnfProviderResponse.class,
        since = "4.22.1",
        requestHasSensitiveInfo = true,
        responseHasSensitiveInfo = false,
        authorized = {RoleType.Admin})
public class UpdateVnfProviderCmd extends BaseCmd {

    @Inject
    VnfProviderManager vnfProviderManager;

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name = ApiConstants.ID,
            type = CommandType.UUID,
            entityType = VnfProviderResponse.class,
            required = true,
            description = "Id of the Vnf provider")
    private Long id;

    @Parameter(name = ApiConstants.NAME,
            type = CommandType.STRING,
            description = "Name of the Vnf provider")
    private String name;

    @Parameter(name = ApiConstants.DESCRIPTION,
            type = CommandType.STRING,
            description = "Description of the Vnf provider")
    private String description;

    @Parameter(name = ApiConstants.VNF_DEFINITION,
            type = CommandType.STRING,
            description = "The VNF provider definition in YAML format")
    private String definition;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getDefinition() {
        return definition;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public void execute() {
        try {
            VnfProvider result = vnfProviderManager.updateVnfProvider(this);
            if (result != null) {
                VnfProviderResponse response = vnfService.createVnfProviderResponse(result);
                response.setResponseName(getCommandName());
                this.setResponseObject(response);
            } else {
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to update Vnf provider:" + getId());
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
