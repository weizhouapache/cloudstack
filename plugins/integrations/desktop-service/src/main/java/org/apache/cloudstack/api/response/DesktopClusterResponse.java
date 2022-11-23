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

import com.cloud.desktop.cluster.DesktopCluster;
import com.cloud.network.Network.GuestType;
import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

@SuppressWarnings("unused")
@EntityReference(value = {DesktopCluster.class})
public class DesktopClusterResponse extends BaseResponse implements ControlledEntityResponse {
    @SerializedName(ApiConstants.ID)
    @Param(description = "the id of the Desktop Cluster")
    private String id;

    @SerializedName(ApiConstants.NAME)
    @Param(description = "the name of the Desktop Cluster")
    private String name;

    @SerializedName(ApiConstants.DESCRIPTION)
    @Param(description = "the description of the Desktop cluster")
    private String description;

    @SerializedName(ApiConstants.ZONE_ID)
    @Param(description = "the name of the zone of the Desktop Cluster")
    private String zoneId;

    @SerializedName(ApiConstants.ZONE_NAME)
    @Param(description = "the name of the zone of the Desktop Cluster")
    private String zoneName;

    @SerializedName(ApiConstants.SERVICE_OFFERING_ID)
    @Param(description = "the ID of the service offering of the Desktop Cluster")
    private String serviceOfferingId;

    @SerializedName("serviceofferingname")
    @Param(description = "the name of the service offering of the Desktop Cluster")
    private String serviceOfferingName;

    @SerializedName(ApiConstants.DESKTOP_AD_DOMAIN_NAME)
    @Param(description = "the ID of the template of the Desktop Cluster")
    private String adDomainName;

    @SerializedName(ApiConstants.NETWORK_ID)
    @Param(description = "the ID of the network of the Desktop Cluster")
    private String networkId;

    @SerializedName(ApiConstants.NETWORK_TYPE)
    @Param(description = "the ID of the network of the Desktop Cluster")
    private GuestType networkType;

    @SerializedName(ApiConstants.ASSOCIATED_NETWORK_NAME)
    @Param(description = "the name of the network of the Desktop Cluster")
    private String associatedNetworkName;

    @SerializedName(ApiConstants.DESKTOP_CONTROLLER_VERSION)
    @Param(description = "the Controller version for the Desktop Cluster")
    private String controllerVersion;

    @SerializedName(ApiConstants.DESKTOP_CONTROLLER_VERSION_NAME)
    @Param(description = "the name of the Controller version for the Desktop Cluster")
    private String controllerVersionName;

    @SerializedName(ApiConstants.ACCOUNT)
    @Param(description = "the account associated with the Desktop Cluster")
    private String accountName;

    @SerializedName(ApiConstants.PROJECT_ID)
    @Param(description = "the project id of the Desktop Cluster")
    private String projectId;

    @SerializedName(ApiConstants.PROJECT)
    @Param(description = "the project name of the Desktop Cluster")
    private String projectName;

    @SerializedName(ApiConstants.DOMAIN_ID)
    @Param(description = "the ID of the domain in which the Desktop Cluster exists")
    private String domainId;

    @SerializedName(ApiConstants.DOMAIN)
    @Param(description = "the name of the domain in which the Desktop Cluster exists")
    private String domainName;

    @SerializedName(ApiConstants.STATE)
    @Param(description = "the state of the Desktop Cluster")
    private String state;

    @SerializedName(ApiConstants.DESKTOP_CONTROL_VIRTUAL_MACHINES)
    @Param(description = "the list of virtualmachine associated with this Desktop Cluster")
    private List<UserVmResponse> controlVms;

    @SerializedName(ApiConstants.DESKTOP_USER_VIRTUAL_MACHINES)
    @Param(description = "the list of virtualmachine associated with this Desktop Cluster")
    private List<UserVmResponse> desktopVms;

    @SerializedName(ApiConstants.DESKTOP_WORKS_VM_IP)
    @Param(description = "Public IP Address of the cluster")
    private String worksVmIp;

    @SerializedName(ApiConstants.DESKTOP_DC_VM_IP)
    @Param(description = "Public IP Address of the cluster")
    private String dcVmIp;

    @SerializedName(ApiConstants.IP_ADDRESS)
    @Param(description = "Public IP Address of the cluster")
    private String ipAddress;

    @SerializedName(ApiConstants.IP_ADDRESS_ID)
    @Param(description = "Public IP Address ID of the cluster")
    private String ipAddressId;

    @SerializedName(ApiConstants.CREATED)
    @Param(description = "the date this template was created")
    private Date created;

    public DesktopClusterResponse() {
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

    public String getServiceOfferingId() {
        return serviceOfferingId;
    }

    public void setServiceOfferingId(String serviceOfferingId) {
        this.serviceOfferingId = serviceOfferingId;
    }

    public String getAdDomainName() {
        return adDomainName;
    }

    public void setAdDomainName(String adDomainName) {
        this.adDomainName = adDomainName;
    }

    public String getNetworkId() {
        return networkId;
    }

    public void setNetworkId(String networkId) {
        this.networkId = networkId;
    }

    public String getAssociatedNetworkName() {
        return associatedNetworkName;
    }

    public void setAssociatedNetworkName(String associatedNetworkName) {
        this.associatedNetworkName = associatedNetworkName;
    }

    public String getControllerVersion() {
        return controllerVersion;
    }

    public void setControllerVersion(String controllerVersion) {
        this.controllerVersion = controllerVersion;
    }

    public String getControllerVersionName() {
        return controllerVersionName;
    }

    public void setControllerVersionName(String controllerVersionName) {
        this.controllerVersionName= controllerVersionName;
    }

    public String getProjectId() {
        return projectId;
    }

    @Override
    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    @Override
    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    @Override
    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    @Override
    public void setDomainId(String domainId) {
        this.domainId = domainId;
    }

    @Override
    public void setDomainName(String domainName) {
        this.domainName = domainName;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getId() {
        return this.id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getServiceOfferingName() {
        return serviceOfferingName;
    }

    public void setServiceOfferingName(String serviceOfferingName) {
        this.serviceOfferingName = serviceOfferingName;
    }

    public List<UserVmResponse> getControlVms() {
        return controlVms;
    }

    public void setControlVms(List<UserVmResponse> controlVms) {
        this.controlVms = controlVms;
    }

    public List<UserVmResponse> getDesktopVms() {
        return desktopVms;
    }

    public void setDesktopVms(List<UserVmResponse> desktopVms) {
        this.desktopVms = desktopVms;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public void setIpAddressId(String ipAddressId) {
        this.ipAddressId = ipAddressId;
    }

    public GuestType getNetworkType() {
        return networkType;
    }

    public void setNetworkType(GuestType networkType) {
        this.networkType = networkType;
    }

    public String getWorksVmIp() {
        return worksVmIp;
    }

    public void setWorksVmIp(String worksVmIp) {
        this.worksVmIp = worksVmIp;
    }

    public String getDcVmIp() {
        return dcVmIp;
    }

    public void setDcVmIp(String dcVmIp) {
        this.dcVmIp = dcVmIp;
    }

    public void setCreated(Date created) {
        this.created = created;
    }
}
