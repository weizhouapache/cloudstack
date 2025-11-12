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

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.uservm.UserVm;

import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseListCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.UserVmResponse;
import org.apache.cloudstack.api.response.VnfProviderResponse;
import org.apache.cloudstack.vnf.VnfProviderManager;
import org.apache.cloudstack.vnf.VnfProvider;
import org.apache.commons.lang3.StringUtils;

import javax.inject.Inject;

@APICommand(name = "vnfListProviders", description = "Lists VNF providers.",
        responseObject = VnfProviderResponse.class,
        requestHasSensitiveInfo = false, responseHasSensitiveInfo = false)
public class VnfListProvidersCmd extends BaseListCmd {

    @Inject
    VnfProviderManager vnfProviderManager;

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////

    @Parameter(name = ApiConstants.NAME,
            type = CommandType.STRING,
            description = "Name of the Vnf provider")
    private String name;

    @Parameter(name = ApiConstants.VIRTUAL_MACHINE_ID,
            type = CommandType.UUID,
            entityType = UserVmResponse.class,
            description = "The ID of the virtual machine")
    private Long vmId;


    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////


    public String getName() {
        return name;
    }

    public Long getVmId() {
        return vmId;
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public void execute()  {
        List<VnfProvider> vnfProviders = new ArrayList<>();
        if (vmId != null) {
            UserVm userVm = _userVmService.getUserVm(vmId);
            String vnfProviderName = vnfTemplateManager.getVnfProviderForVm(userVm);
            if (vnfProviderName != null) {
                if (name != null && !vnfProviderName.equals(name)) {
                    throw new InvalidParameterValueException("VNF provider names do not match. VNF provider name: " + vnfProviderName);
                }
                VnfProvider vnfProvider = vnfService.getVnfProviderByName(vnfProviderName);
                if (vnfProvider != null) {
                    vnfProviders.add(vnfProvider);
                }
            }
        } else if (StringUtils.isEmpty(name)) {
            vnfProviders.addAll(vnfService.getVnfProviders());
        } else {
            VnfProvider vnfProvider = vnfService.getVnfProviderByName(name);
            if (vnfProvider != null) {
                vnfProviders.add(vnfProvider);
            }
        }
        vnfProviders.addAll(vnfProviderManager.listVnfProviders(this));
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
