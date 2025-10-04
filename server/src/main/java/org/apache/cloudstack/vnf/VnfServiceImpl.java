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

import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.component.PluggableService;
import org.apache.cloudstack.api.command.admin.vnf.VnfDeployApplianceCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vnf.VnfListAppliancesCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vnf.VnfListTemplatesCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vnf.VnfRegisterTemplateCmdByAdmin;
import org.apache.cloudstack.api.command.admin.vnf.VnfUpdateTemplateCmdByAdmin;
import org.apache.cloudstack.api.command.user.vnf.VnfDeleteTemplateCmd;
import org.apache.cloudstack.api.command.user.vnf.VnfDeployApplianceCmd;
import org.apache.cloudstack.api.command.user.vnf.VnfListAppliancesCmd;
import org.apache.cloudstack.api.command.user.vnf.VnfListProvidersCmd;
import org.apache.cloudstack.api.command.user.vnf.VnfListTemplatesCmd;
import org.apache.cloudstack.api.command.user.vnf.VnfRegisterTemplateCmd;
import org.apache.cloudstack.api.command.user.vnf.VnfUpdateTemplateCmd;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.storage.template.VnfTemplateManager;

public class VnfServiceImpl extends ManagerBase implements VnfService, PluggableService, Configurable {

    @Inject
    VnfTemplateManager vnfTemplateManager;

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
}
