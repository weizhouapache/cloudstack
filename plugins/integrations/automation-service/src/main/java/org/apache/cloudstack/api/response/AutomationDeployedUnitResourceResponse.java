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

package org.apache.cloudstack.api.response;

import java.util.Date;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseResponse;
import org.apache.cloudstack.api.EntityReference;

import com.cloud.automation.resource.AutomationDeployedUnitResource;
import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

@SuppressWarnings("unused")
@EntityReference(value = {AutomationDeployedUnitResource.class})
public class AutomationDeployedUnitResourceResponse extends BaseResponse {

    @SerializedName(ApiConstants.ID)
    @Param(description = "the id of the deployed resource")
    private String id;

    @SerializedName(ApiConstants.DEPLOYED_GROUP_ID)
    @Param(description = "the id of the deployed resource")
    private String deployedGroupId;

    @SerializedName(ApiConstants.SERVICE_UNIT_NAME)
    @Param(description = "the description of the running service")
    private String serviceUnitName;

    @SerializedName(ApiConstants.STATE)
    @Param(description = "the current state of this running service")
    private String state;

    @SerializedName(ApiConstants.INSTANCE_NAME)
    @Param(description = "the current state of this running service")
    private String vmName;

    @SerializedName(ApiConstants.CREATED)
    @Param(description = "date created")
    private Date created;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDeployedGroupId() {
        return deployedGroupId;
    }

    public void setDeployedGroupId(String id) {
        this.id = deployedGroupId;
    }

    public String getServiceUnitName() {
        return serviceUnitName;
    }

    public void setServiceUnitName(String serviceUnitName) {
        this.serviceUnitName = serviceUnitName;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getVmName() {
        return vmName;
    }

    public void setVmName(String vmName) {
        this.vmName = vmName;
    }

    public Date setCreated() {
        return created;
    }

    public void setCreated(Date created) {
        this.created = created;
    }

}
