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

package org.apache.cloudstack.vnf.api.response;

import java.util.Date;
import java.util.Map;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseResponse;
import org.apache.cloudstack.api.EntityReference;

import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;
import org.apache.cloudstack.vnf.VnfProviderConnection;

@EntityReference(value = VnfProviderConnection.class)
public class VnfBrokerResponse extends BaseResponse {
    @SerializedName(ApiConstants.ID)
    @Param(description = "id of the Vnf broker")
    private String id;

    @SerializedName(ApiConstants.IP_ADDRESS)
    @Param(description = "IPv4 address of Vnf broker")
    private String ipAddress;

    @SerializedName(ApiConstants.ZONE_ID)
    @Param(description = "id of zone to which the Vnf broker belongs to." )
    private String zoneId;

    @SerializedName(ApiConstants.ZONE_NAME)
    @Param(description = "name of zone to which the Vnf broker belongs to." )
    private String zoneName;

    @SerializedName(ApiConstants.CREATED)
    @Param(description = "date when this Vnf broker was created." )
    private Date created;

    @SerializedName(ApiConstants.DETAILS)
    @Param(description = "additional key/value details of the Vnf broker")
    private Map details;

    public void setId(String id) {
        this.id = id;
    }

    public void setDetails(Map details) {
        this.details = details;
    }

    public String getId() {
        return id;
    }

    public Map getDetails() {
        return details;
    }
}
