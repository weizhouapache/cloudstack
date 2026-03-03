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
package org.apache.cloudstack.network.element.api.response;

import java.util.Map;

import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseResponse;

/**
 * API response for an external network device.
 *
 * <p>The device connection details (host, port) are returned as top-level
 * fields.  Credentials and other properties (username, password, sshkey, etc.)
 * are passed via {@code details} at write time and are returned in the
 * {@code details} map with only display=true entries — sensitive keys
 * (password, sshkey) are intentionally omitted.</p>
 */
public class ExternalNetworkDeviceResponse extends BaseResponse {

    @SerializedName("physicalnetworkid")
    @Param(description = "UUID of the physical network this device is registered to")
    private String physicalNetworkId;

    @SerializedName("physicalnetworkname")
    @Param(description = "Name of the physical network")
    private String physicalNetworkName;

    @SerializedName("extensionid")
    @Param(description = "UUID of the NetworkOrchestrator extension")
    private String extensionId;

    @SerializedName("extensionname")
    @Param(description = "Name of the NetworkOrchestrator extension")
    private String extensionName;

    @SerializedName("host")
    @Param(description = "IP address or hostname of the external network device")
    private String host;

    @SerializedName("port")
    @Param(description = "SSH / API port of the external network device")
    private String port;

    @SerializedName(ApiConstants.DETAILS)
    @Param(description = "Device details (display=true entries only). "
            + "Sensitive keys such as password and sshkey are never returned.")
    private Map<String, String> details;

    public String getPhysicalNetworkId()             { return physicalNetworkId; }
    public void   setPhysicalNetworkId(String v)     { this.physicalNetworkId = v; }
    public String getPhysicalNetworkName()            { return physicalNetworkName; }
    public void   setPhysicalNetworkName(String v)   { this.physicalNetworkName = v; }
    public String getExtensionId()                    { return extensionId; }
    public void   setExtensionId(String v)            { this.extensionId = v; }
    public String getExtensionName()                  { return extensionName; }
    public void   setExtensionName(String v)          { this.extensionName = v; }
    public String getHost()                           { return host; }
    public void   setHost(String v)                   { this.host = v; }
    public String getPort()                           { return port; }
    public void   setPort(String v)                   { this.port = v; }
    public Map<String, String> getDetails()           { return details; }
    public void   setDetails(Map<String, String> v)   { this.details = v; }
}

