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

import java.util.List;
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
     * per-physical-network properties set at registration time.
     *
     * @param physicalNetworkId  the physical network ID
     * @return key/value details (display=true only), or an empty map if none are set
     */
    Map<String, String> getResourceMapDetailsForPhysicalNetwork(long physicalNetworkId);

    /**
     * Returns ALL extension_resource_map_details (including hidden) for the
     * extension registered to the given physical network.  Used internally by
     * ExternalNetworkElement to inject credentials into the script environment.
     *
     * @param physicalNetworkId  the physical network ID
     * @return all key/value details including non-display ones, or an empty map
     */
    Map<String, String> getAllResourceMapDetailsForPhysicalNetwork(long physicalNetworkId);

    /**
     * Returns the extension_resource_map id for the extension registered to the
     * given physical network, or {@code null} if not registered.
     */
    Long getResourceMapIdForPhysicalNetwork(long physicalNetworkId);

    /**
     * Updates (upserts) extension_resource_map_details for a given map entry.
     * Existing keys in {@code details} are overwritten; keys not present in
     * {@code details} are left unchanged.
     *
     * @param resourceMapId  the extension_resource_map.id
     * @param details        key/value pairs to upsert
     * @param displayKeys    set of keys whose display flag should be {@code true};
     *                       any key NOT in this set gets display=false (hidden)
     */
    void updateResourceMapDetails(long resourceMapId, Map<String, String> details,
            java.util.Set<String> displayKeys);

    /**
     * Removes specific keys from extension_resource_map_details for the given map entry.
     *
     * @param resourceMapId  the extension_resource_map.id
     * @param keys           keys to remove
     */
    void removeResourceMapDetails(long resourceMapId, List<String> keys);

    /**
     * Lists all physical network IDs that have a NetworkOrchestrator extension
     * registered (i.e. have an entry in extension_resource_map of type PhysicalNetwork).
     */
    List<Long> listPhysicalNetworkIdsWithExtension();
}
