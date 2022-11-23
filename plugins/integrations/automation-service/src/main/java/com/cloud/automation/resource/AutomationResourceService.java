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

package com.cloud.automation.resource;


import org.apache.cloudstack.api.command.user.automation.resource.ListAutomationDeployedResourceCmd;
import org.apache.cloudstack.api.command.user.automation.resource.AddDeployedResourceGroupCmd;
import org.apache.cloudstack.api.command.user.automation.resource.AddDeployedUnitResourceCmd;
import org.apache.cloudstack.api.command.user.automation.resource.DeleteDeployedUnitResourceCmd;
import org.apache.cloudstack.api.command.user.automation.resource.DeleteDeployedResourceGroupCmd;
import org.apache.cloudstack.api.command.user.automation.resource.UpdateDeployedResourceGroupCmd;
import org.apache.cloudstack.api.response.AutomationDeployedResourceResponse;
import org.apache.cloudstack.api.response.AutomationDeployedUnitResourceResponse;
import org.apache.cloudstack.api.response.ListResponse;

import com.cloud.utils.component.PluggableService;
import com.cloud.utils.exception.CloudRuntimeException;

public interface AutomationResourceService extends PluggableService {

    ListResponse<AutomationDeployedResourceResponse> listAutomationDeployedResource(ListAutomationDeployedResourceCmd cmd);
    AutomationDeployedResourceResponse addDeployedResourceGroup(AddDeployedResourceGroupCmd cmd);
    AutomationDeployedUnitResourceResponse addDeployedUnitResource(AddDeployedUnitResourceCmd cmd);
    void deleteDeployedResource(DeleteDeployedResourceGroupCmd cmd);
    void deleteDeployedUnitResource(DeleteDeployedUnitResourceCmd cmd);
    AutomationDeployedResourceResponse updateDeployedResourceGroup(UpdateDeployedResourceGroupCmd cmd) throws CloudRuntimeException;
}