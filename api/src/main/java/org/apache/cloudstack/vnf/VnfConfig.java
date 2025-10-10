//
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
//

package org.apache.cloudstack.vnf;

import java.util.HashMap;
import java.util.Map;

public class VnfConfig {

    private Long vnfId;
    private String vnfName;
    private String providerType;

    // SSH Configuration
    private String sshHost;
    private int sshPort = 22;
    private String sshUsername;
    private String sshPassword;
    private String sshPrivateKey;
    private String sshPrivateKeyPath;

    // HTTP Configuration
    private String httpEndpoint;
    private String httpUsername;
    private String httpPassword;
    private String httpToken;
    private int httpTimeout = 30000;

    private Map<String, String> customProperties = new HashMap<>();

    public VnfConfig() {
    }

    public VnfConfig(Long vnfId) {
        this.vnfId = vnfId;
    }

    public Long getVnfId() {
        return vnfId;
    }

    public String getVnfName() {
        return vnfName;
    }

    public String getProviderType() {
        return providerType;
    }

    public String getSshHost() {
        return sshHost;
    }

    public int getSshPort() {
        return sshPort;
    }

    public String getSshUsername() {
        return sshUsername;
    }

    public String getSshPassword() {
        return sshPassword;
    }

    public String getSshPrivateKey() {
        return sshPrivateKey;
    }

    public String getSshPrivateKeyPath() {
        return sshPrivateKeyPath;
    }

    public String getHttpEndpoint() {
        return httpEndpoint;
    }

    public String getHttpUsername() {
        return httpUsername;
    }

    public String getHttpPassword() {
        return httpPassword;
    }

    public String getHttpToken() {
        return httpToken;
    }

    public int getHttpTimeout() {
        return httpTimeout;
    }

    public Map<String, String> getCustomProperties() {
        return customProperties;
    }
}
