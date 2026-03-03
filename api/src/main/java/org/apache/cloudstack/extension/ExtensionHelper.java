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

package org.apache.cloudstack.extension;

import java.util.Map;

public interface ExtensionHelper {
    Long getExtensionIdForCluster(long clusterId);
    Extension getExtension(long id);
    Extension getExtensionForCluster(long clusterId);
    Long getExtensionIdForPhysicalNetwork(long physicalNetworkId);
    Extension getExtensionForPhysicalNetwork(long physicalNetworkId);
    String getExtensionScriptPath(Extension extension);
    Map<String, String> getExtensionDetails(long extensionId);

    /**
     * Returns the extension_resource_map_details for the extension registered
     * to the given physical network.  These details hold device-access
     * information (host, port, username, password, sshkey) and any other
     * per-physical-network properties set at registration time via
     * {@code registerExtension physicalnetworkid=<id> details[0].key=host ...}.
     *
     * @param physicalNetworkId  the physical network ID
     * @return key/value details, or an empty map if none are set
     */
    Map<String, String> getResourceMapDetailsForPhysicalNetwork(long physicalNetworkId);
}
