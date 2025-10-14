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
package org.apache.cloudstack.vnf;


import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.VNF;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.VnfTemplateDetailVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VnfTemplateDetailsDao;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.component.PluggableService;

import com.cloud.vm.UserVmVO;
import com.cloud.vm.dao.UserVmDao;
import org.apache.cloudstack.api.command.admin.vnf.VnfDeployApplianceCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vnf.VnfListAppliancesCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vnf.VnfListTemplatesCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vnf.VnfRegisterTemplateCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vnf.VnfUpdateTemplateCmdByAdmin;
import org.apache.cloudstack.api.command.user.vnf.BaseVnfCmd;
import org.apache.cloudstack.api.command.user.vnf.VnfDeleteTemplateCmd;
import org.apache.cloudstack.api.command.user.vnf.VnfDeployApplianceCmd;
import org.apache.cloudstack.api.command.user.vnf.VnfListAppliancesCmd;
import org.apache.cloudstack.api.command.user.vnf.VnfListProvidersCmd;
import org.apache.cloudstack.api.command.user.vnf.VnfListTemplatesCmd;
import org.apache.cloudstack.api.command.user.vnf.VnfRegisterTemplateCmd;
import org.apache.cloudstack.api.command.user.vnf.VnfUpdateTemplateCmd;
import org.apache.cloudstack.api.response.VnfOperationResponse;
import org.apache.cloudstack.api.response.VnfProviderResponse;
import org.apache.cloudstack.api.response.VnfServiceResponse;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.storage.template.VnfTemplateManager;
import org.apache.commons.collections.MapUtils;

public class VnfServiceImpl extends ManagerBase implements VnfService, PluggableService, Configurable {

    @Inject
    VnfTemplateManager vnfTemplateManager;
    @Inject
    UserVmDao userVmDao;
    @Inject
    VMTemplateDao templateDao;
    @Inject
    VnfTemplateDetailsDao vnfTemplateDetailsDao;

    protected List<VnfProvider> vnfProviders;

    @Override
    public List<VnfProvider> getVnfProviders() {
        return vnfProviders;
    }

    public void setVnfProviders(List<VnfProvider> vnfProviders) {
        this.vnfProviders = vnfProviders;
    }

    @Override
    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {
        return true;
    }

    @Override
    public VnfProvider getVnfProviderByName(String name) {
        return vnfProviders.stream()
                .filter(vnfProvider -> vnfProvider.getName().equalsIgnoreCase(name))
                .findFirst().orElse(null);
    }

    @Override
    public Set<ServiceCategory> getSupportedServices(VnfProvider vnfProvider) {
        return vnfProvider.getSupportedOperations().keySet();
    }

    @Override
    public List<Class<?>> getCommands() {
        final List<Class<?>> cmdList = new ArrayList<>();
        if (!VnfFrameworkEnabled.value()) {
            return cmdList;
        }

        // VNF Templates
        cmdList.add(VnfListTemplatesCmd.class);
        cmdList.add(VnfListTemplatesCmdByAdmin.class);
        cmdList.add(VnfRegisterTemplateCmd.class);
        cmdList.add(VnfRegisterTemplateCmdByAdmin.class);
        cmdList.add(VnfUpdateTemplateCmd.class);
        cmdList.add(VnfUpdateTemplateCmdByAdmin.class);
        cmdList.add(VnfDeleteTemplateCmd.class);

        // VNF Appliances
        cmdList.add(VnfListAppliancesCmd.class);
        cmdList.add(VnfListAppliancesCmdByAdmin.class);
        cmdList.add(VnfDeployApplianceCmd.class);
        cmdList.add(VnfDeployApplianceCmdByAdmin.class);

        // VNF Provider
        cmdList.add(VnfListProvidersCmd.class);

        return cmdList;
    }

    @Override
    public String getConfigComponentName() {
        return VnfService.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[]{
                VnfFrameworkEnabled
        };
    }

    @Override
    public VnfProviderResponse createVnfProviderResponse(VnfProvider vnfProvider) {
        VnfProviderResponse response = new VnfProviderResponse();
        response.setName(vnfProvider.getName());
        response.setDescription(vnfProvider.getDescription());
        if (vnfProvider.getUuid() != null) {
            response.setId(vnfProvider.getUuid());
            response.setType(VnfProvider.TYPE.CUSTOM.name());
        } else {
            response.setType(VnfProvider.TYPE.BUILTIN.name());
        }
        Map<ServiceCategory, List<VnfOperation>> supportedOperations = vnfProvider.getSupportedOperations();
        if (MapUtils.isNotEmpty(supportedOperations)) {
            for (Map.Entry<ServiceCategory, List<VnfOperation>> entry : supportedOperations.entrySet()) {
                VnfServiceResponse serviceResponse = new VnfServiceResponse();
                serviceResponse.setName(entry.getKey().name());
                List<VnfOperationResponse> operationResponses = new ArrayList<>();
                for (VnfOperation operation : entry.getValue()) {
                    VnfOperationResponse operationResponse = new VnfOperationResponse();
                    operationResponse.setName(operation.name());
                    operationResponse.setService(operation.getCategory().name());
                    operationResponse.setDescription(operation.getDescription());
                    operationResponses.add(operationResponse);
                }
                serviceResponse.setOperations(operationResponses);
                response.addService(serviceResponse);
            }
        }
        response.setObjectName("vnfprovider");
        return response;
    }

    @Override
    public void executeVnfCommand(BaseVnfCmd command) {
        VnfProvider vnfProvider = validateVnfCommand(command);
        executeVnfCommand(vnfProvider, command);
    }

    protected VnfProvider validateVnfCommand(BaseVnfCmd command) {
        Long vnfId = command.getVnfId();
        UserVmVO vnf = userVmDao.findById(vnfId);
        if (vnf == null) {
            throw new InvalidParameterValueException(String.format("Unable to find VNF appliance with ID %s", vnfId));
        }
        VMTemplateVO vnfTemplate = templateDao.findByIdIncludingRemoved(vnf.getTemplateId());
        VnfTemplateDetailVO vnfProviderName = vnfTemplateDetailsDao.findDetail(vnfTemplate.getId(), VNF.VnfDetail.VNF_PROVIDER.name().toLowerCase());
        if (vnfProviderName == null) {
            throw new InvalidParameterValueException(String.format("VNF Template %s does not have a VNF Provider associated with it", vnfTemplate.getName()));
        }
        VnfProvider vnfProvider = getVnfProviderByName(vnfProviderName.getValue());
        if (vnfProvider == null) {
            throw new InvalidParameterValueException(String.format("VNF Provider %s associated with VNF Template %s is not supported by CloudStack",
                    vnfProviderName.getValue(), vnfTemplate.getName()));
        }
        return vnfProvider;
    }

    protected void executeVnfCommand(VnfProvider vnfProvider, BaseVnfCmd command) {
        // 1. Validate command
        ServiceCategory serviceCategory = command.getServiceCategory();
        if (serviceCategory == null) {
            throw new InvalidParameterValueException("Service category is not specified in the command");
        }
        List<VnfOperation> operations = vnfProvider.getSupportedOperations().get(serviceCategory);
        if (operations == null || operations.isEmpty()) {
            throw new InvalidParameterValueException(String.format("VNF Provider %s does not support any operations for service category %s",
                    vnfProvider.getName(), serviceCategory));
        }
        // TODO: Validate if the specific operation is supported

        // 2. Get appropriate connector
        VnfConnector vnfConnector = vnfProvider.getConnector(command);
        if (vnfConnector == null) {
            throw new InvalidParameterValueException(String.format("VNF Provider %s does not have a connector for command %s",
                    getName(), command.getClass().getSimpleName()));
        }

        // 3. Get appropriate data format handler
        VnfDataFormatHandler dataFormatHandler = vnfProvider.getDataFormatHandler(command);
        if (dataFormatHandler == null) {
            throw new InvalidParameterValueException(String.format("VNF Provider %s does not have a date format handler for command %s",
                    getName(), command.getClass().getSimpleName()));
        }

        // 4. Get VNF configuration
        VnfConfig vnfConfig = new VnfConfig(command.getVnfId());
        // TODO: add more information to vnfConfig if needed

        // 5. Create VNF command
        VnfCommand vnfCommand = new VnfCommand();
        // TODO: populate vnfCommand with necessary information from BaseVnfCmd

        // 6. Execute command via connector
        String result = vnfProvider.executeVnfCommand(vnfConfig, vnfConnector, vnfCommand, dataFormatHandler);

        // 7. Cleanup
        vnfConnector.close();
    }
}
