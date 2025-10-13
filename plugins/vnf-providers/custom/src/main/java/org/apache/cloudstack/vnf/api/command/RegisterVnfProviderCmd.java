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

import com.cloud.exception.InvalidParameterValueException;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.VnfProviderResponse;
import org.apache.cloudstack.vnf.VnfBrokerManager;
import org.apache.cloudstack.vnf.VnfProvider;
import org.apache.cloudstack.vnf.VnfService;
import org.apache.cloudstack.vnf.api.response.VnfBrokerResponse;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;

import com.cloud.user.Account;

import javax.inject.Inject;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@APICommand(name = "registerVnfProvider",
        description = "Registers a Vnf provider with a Vnf broker.",
        responseObject = VnfBrokerResponse.class,
        since = "4.22.0",
        requestHasSensitiveInfo = true,
        responseHasSensitiveInfo = false,
        authorized = {RoleType.Admin})
public class RegisterVnfProviderCmd extends BaseCmd {

    @Inject
    VnfBrokerManager vnfBrokerManager;

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name = ApiConstants.NAME,
            type = CommandType.STRING,
            required = true,
            description = "Name of the Vnf provider")
    private String name;

    @Parameter(name = ApiConstants.DESCRIPTION,
            type = CommandType.STRING,
            description = "Description of the Vnf provider")
    private String description;

    @Parameter(name = ApiConstants.VNF_BROKER_ID,
            type = CommandType.STRING,
            description = "The ID of the Vnf broker")
    private String vnfBrokerId;

    @Parameter(name = ApiConstants.SUPPORTED_SERVICES,
            type = CommandType.MAP,
            description = "desired service categories and operations")
    private Map supportedServices;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////


    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getVnfBrokerId() {
        return vnfBrokerId;
    }

    public Map getSupportedServices() {
        Map<VnfService.ServiceCategory, List<VnfService.VnfOperation>> serviceMap = null;

        if (MapUtils.isNotEmpty(supportedServices)) {
            serviceMap = new HashMap<>();
            Collection serviceCapabilityCollection = supportedServices.values();
            Iterator iter = serviceCapabilityCollection.iterator();
            while (iter.hasNext()) {
                HashMap<String, String> serviceOperationsMap = (HashMap<String, String>) iter.next();
                VnfService.ServiceCategory serviceCategory;
                String serviceName = serviceOperationsMap.get("service");
                String operations = serviceOperationsMap.get("operations");

                if (serviceName != null) {
                    serviceCategory = VnfService.ServiceCategory.getService(serviceName);
                } else {
                    throw new InvalidParameterValueException("Service is not specified");
                }
                if (serviceCategory == null) {
                    throw new InvalidParameterValueException("Invalid service: " + serviceName);
                }
                List<VnfService.VnfOperation> vnfOperations = operations == null ? null :
                        Arrays.stream(operations.split(",")).map(op -> VnfService.VnfOperation.getOperation(serviceCategory, op)).collect(Collectors.toList());
                if (CollectionUtils.isEmpty(vnfOperations)) {
                    throw new InvalidParameterValueException("Invalid operations: " + operations + " in service category: " + serviceName);
                }

                serviceMap.put(serviceCategory, vnfOperations);
            }
        }
        return serviceMap;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public void execute() {
        VnfProvider result = vnfBrokerManager.registerVnfProvider(this);
        if (result != null) {
            VnfProviderResponse response = vnfService.createVnfProviderResponse(result);
            response.setResponseName(getCommandName());
            this.setResponseObject(response);
        } else {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, "Failed to create Vnf provider.");
        }
    }

    @Override
    public long getEntityOwnerId() {
        return Account.ACCOUNT_ID_SYSTEM;
    }

}
