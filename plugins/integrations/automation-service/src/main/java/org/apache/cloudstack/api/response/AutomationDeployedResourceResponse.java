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
import java.util.List;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseResponse;
import org.apache.cloudstack.api.EntityReference;
import com.cloud.automation.resource.AutomationDeployedResource;
import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

@SuppressWarnings("unused")
@EntityReference(value = {AutomationDeployedResource.class})
public class AutomationDeployedResourceResponse extends BaseResponse {

    @SerializedName(ApiConstants.ID)
    @Param(description = "the id of the deployed resource")
    private String id;

    @SerializedName(ApiConstants.NAME)
    @Param(description = "the name of deployed service")
    private String name;

    @SerializedName(ApiConstants.DESCRIPTION)
    @Param(description = "the description of the running service")
    private String description;

    @SerializedName(ApiConstants.ACCESS_INFO)
    @Param(description = "a description of how to access the service")
    private String accessInfo;

    @SerializedName(ApiConstants.ACCOUNT_ID)
    @Param(description = "the account id associated with the Automation Service")
    private Long accountId;

    @SerializedName(ApiConstants.ACCOUNT)
    @Param(description = "the account name associated with the Automation Service")
    private String accountName;

    @SerializedName(ApiConstants.ZONE_ID)
    @Param(description = "the id of the zone in which Automation Controller Version is available")
    private String zoneId;

    @SerializedName(ApiConstants.ZONE_NAME)
    @Param(description = "the name of the zone in which Automation Controller Version is available")
    private String zoneName;

    @SerializedName(ApiConstants.STATE)
    @Param(description = "the current state of this running service")
    private String state;

    @SerializedName(ApiConstants.CREATED)
    @Param(description = "date created")
    private Date created;

    @SerializedName(ApiConstants.LAST_UPDATED)
    @Param(description = "Last update time")
    private Date lastUpdated;

    @SerializedName(ApiConstants.DEPLOYED_UNIT_SERVICES)
    @Param(description = "the list of services")
    private List<AutomationDeployedUnitResourceResponse> deployedunitservices;

    @SerializedName(ApiConstants.DEPLOYED_VIRTUAL_MACHINES)
    @Param(description = "the list of virtualmachine associated with this Automation Service Group")
    private List<UserVmResponse> deployedVms;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getAccessInfo() {
        return accessInfo;
    }

    public void setAccessInfo(String accessInfo) {
        this.accessInfo = accessInfo;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    public String getZoneId() {
        return zoneId;
    }

    public void setZoneId(String zoneId) {
        this.zoneId = zoneId;
    }

    public String getZoneName() {
        return zoneName;
    }

    public void setZoneName(String zoneName) {
        this.zoneName = zoneName;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public Date setCreated() {
        return created;
    }

    public void setCreated(Date created) {
        this.created = created;
    }

    public Date getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(Date lastUpdated) {
        this.lastUpdated = lastUpdated;
    }
    public List<AutomationDeployedUnitResourceResponse> getDeployedUnitServices() {
        return deployedunitservices;
    }

    public void setDeployedUnitServices(List<AutomationDeployedUnitResourceResponse> deployedunitservices) {
        this.deployedunitservices = deployedunitservices;
    }

    public List<UserVmResponse> getDeployedVms() {
        return deployedVms;
    }

    public void setDeployedVms(List<UserVmResponse> deployedVms) {
        this.deployedVms = deployedVms;
    }
}