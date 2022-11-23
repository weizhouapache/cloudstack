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

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseResponse;
import org.apache.cloudstack.api.EntityReference;

import com.cloud.desktop.cluster.DesktopClusterIpRange;
import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

@SuppressWarnings("unused")
@EntityReference(value = {DesktopClusterIpRange.class})
public class DesktopClusterIpRangeResponse extends BaseResponse {
    @SerializedName(ApiConstants.ID)
    @Param(description = "the id of the Desktop Cluster IP Range")
    private String id;

    @SerializedName(ApiConstants.DESKTOP_CLUSTER_NAME)
    @Param(description = "the name of the Desktop Cluster")
    private String desktopClusterName;

    @SerializedName(ApiConstants.ASSOCIATED_NETWORK_NAME)
    @Param(description = "the name of the network of the Desktop Cluster")
    private String associatedNetworkName;

    @SerializedName(ApiConstants.NETWORK_ID)
    @Param(description = "the id of the network of the Desktop Cluster")
    private String networkId;

    @SerializedName(ApiConstants.GATEWAY)
    @Param(description = "the gateway of the network of the Desktop Cluster")
    private String gateway;

    @SerializedName(ApiConstants.NETMASK)
    @Param(description = "the netmask of the network of the Desktop Cluster")
    private String netmask;

    @SerializedName(ApiConstants.START_IP)
    @Param(description = "the start ip of the network of the Desktop Cluster")
    private String startIp;

    @SerializedName(ApiConstants.END_IP)
    @Param(description = "the end ip of the network of the Desktop Cluster")
    private String endIp;

    public DesktopClusterIpRangeResponse() {
    }

    public String getDesktopClusterName() {
        return desktopClusterName;
    }

    public void setDesktopClusterName(String desktopClusterName) {
        this.desktopClusterName = desktopClusterName;
    }

    public String getAssociatedNetworkName() {
        return associatedNetworkName;
    }

    public void setAssociatedNetworkName(String associatedNetworkName) {
        this.associatedNetworkName = associatedNetworkName;
    }

    public String getNetworkId() {
        return networkId;
    }

    public void setNetworkId(String networkId) {
        this.networkId = networkId;
    }

    public String getId() {
        return this.id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getGateway() {
        return gateway;
    }

    public void setGateway(String gateway) {
        this.gateway = gateway;
    }

    public String getNetmask() {
        return netmask;
    }

    public void setNetmask(String netmask) {
        this.netmask = netmask;
    }

    public String getStartIp() {
        return startIp;
    }

    public void setStartIp(String startIp) {
        this.startIp = startIp;
    }

    public String getEndIp() {
        return endIp;
    }

    public void setEndIp(String endIp) {
        this.endIp = endIp;
    }

}
