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

import com.cloud.network.Network.Capability;
import com.cloud.network.Network.Service;

public interface ExtensionHelper {

    /** Detail key used to store the JSON network capabilities of a NetworkOrchestrator extension. */
    String NETWORK_CAPABILITIES_DETAIL_KEY = "network.capabilities";

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

    /**
     * Returns all NetworkOrchestrator extensions registered with the given
     * physical network. Multiple extensions can be registered with one network,
     * each appearing as its own service provider tab named after the extension.
     */
    List<Extension> listExtensionsForPhysicalNetwork(long physicalNetworkId);

    /**
     * Finds the extension registered with the given physical network whose name
     * matches the given provider name (case-insensitive).  Returns {@code null}
     * if no matching extension is found.
     *
     * <p>This is the preferred lookup when multiple extensions are registered on
     * the same physical network: the provider name stored in
     * {@code ntwk_service_map} is used to pinpoint the exact extension that
     * handles a given network.</p>
     *
     * @param physicalNetworkId the physical network ID
     * @param providerName      the provider name (must equal the extension name)
     * @return the matching {@link Extension}, or {@code null}
     */
    Extension getExtensionForPhysicalNetworkAndProvider(long physicalNetworkId, String providerName);

    /**
     * Returns ALL {@code extension_resource_map_details} (including hidden) for
     * the specific extension registered on the given physical network.  Used by
     * {@code ExternalNetworkElement} to inject device credentials into the script
     * environment for the correct extension when multiple different extensions are
     * registered on the same physical network.
     *
     * @param physicalNetworkId the physical network ID
     * @param extensionId       the extension ID
     * @return all key/value details including non-display ones, or an empty map
     */
    Map<String, String> getAllResourceMapDetailsForExtensionOnPhysicalNetwork(long physicalNetworkId, long extensionId);

    /**
     * Returns {@code true} if the given provider name is backed by a
     * {@code NetworkOrchestrator} extension registered on any physical network.
     * This is used by {@code NetworkModelImpl} to detect extension-backed providers
     * that are not in the static {@code s_providerToNetworkElementMap}.
     *
     * @param providerName the provider / extension name
     * @return true if the provider is an external network extension provider
     */
    boolean isExternalNetworkProvider(String providerName);

    /**
     * Returns the effective {@link Service} → ({@link Capability} → value) capabilities
     * for the given external network provider, looking it up by name on the given
     * physical network.
     *
     * <p>If {@code physicalNetworkId} is {@code null}, the method searches across all
     * physical networks that have extensions registered and returns the capabilities for
     * the first matching extension.</p>
     *
     * @param physicalNetworkId physical network ID, or {@code null} for offering-level queries
     * @param providerName      provider / extension name
     * @return capabilities map, or the default capabilities if no matching extension is found
     */
    Map<Service, Map<Capability, String>> getNetworkCapabilitiesForProvider(Long physicalNetworkId, String providerName);
}
