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
package org.apache.cloudstack.vnf.api.command;

import java.util.ArrayList;
import java.util.List;

import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.command.user.vnf.VnfListProvidersCmd;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.VnfProviderResponse;
import org.apache.cloudstack.vnf.VnfBrokerManager;
import org.apache.cloudstack.vnf.VnfProvider;

import javax.inject.Inject;

@APICommand(name = "vnfListAllProviders", description = "Lists all VNF providers.",
        responseObject = VnfProviderResponse.class,
        requestHasSensitiveInfo = false, responseHasSensitiveInfo = false)
public class VnfListAllProvidersCmd extends VnfListProvidersCmd {

    @Inject
    VnfBrokerManager vnfBrokerManager;

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name = ApiConstants.NAME,
            type = CommandType.STRING,
            description = "Name of the Vnf provider")
    private String name;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////


    public String getName() {
        return name;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public void execute()  {
        List<VnfProvider> vnfProviders = vnfService.getVnfProviders();
        vnfProviders.addAll(vnfBrokerManager.listVnfProviders(this));
        final ListResponse<VnfProviderResponse> response = new ListResponse<>();
        final List<VnfProviderResponse> responses = new ArrayList<>();

        for (VnfProvider vnfProvider : vnfProviders) {
            VnfProviderResponse vnfProviderResponse = vnfService.createVnfProviderResponse((vnfProvider));
            responses.add(vnfProviderResponse);
        }
        response.setResponses(responses, responses.size());
        response.setResponseName(this.getCommandName());
        setResponseObject(response);
    }
}
