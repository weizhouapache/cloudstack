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
package org.apache.cloudstack.network.element;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;

import org.apache.cloudstack.extension.Extension;
import org.apache.cloudstack.extension.ExtensionHelper;
import org.apache.cloudstack.network.element.api.command.AddExternalNetworkDeviceCmd;
import org.apache.cloudstack.network.element.api.command.DeleteExternalNetworkDeviceCmd;
import org.apache.cloudstack.network.element.api.command.ListExternalNetworkDevicesCmd;
import org.apache.cloudstack.network.element.api.command.UpdateExternalNetworkDeviceCmd;
import org.apache.cloudstack.network.element.api.response.ExternalNetworkDeviceResponse;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.dao.PhysicalNetworkVO;
import com.cloud.utils.component.ManagerBase;

/**
 * Implements {@link ExternalNetworkDeviceService} by storing all device details
 * as {@code extension_resource_map_details} via {@link ExtensionHelper}.
 *
 * <p>No separate database tables are created.  The existing
 * {@code extension_resource_map} entry (created by
 * {@code registerExtension physicalnetworkid=...}) acts as the "device record";
 * its details hold the connection info.</p>
 *
 * <p>Sensitivity rules:</p>
 * <ul>
 *   <li>{@code password} and {@code sshkey} are stored with {@code display=false}
 *       and are never returned in API responses.</li>
 *   <li>{@code host} and {@code port} are top-level fields in the response.</li>
 *   <li>All other keys (username, custom properties, etc.) are stored with
 *       {@code display=true} and appear in the {@code details} map of responses.</li>
 * </ul>
 */
public class ExternalNetworkDeviceServiceImpl extends ManagerBase implements ExternalNetworkDeviceService {

    /** Keys that are always stored with display=false (sensitive). */
    static final Set<String> SENSITIVE_KEYS = Set.of("password", "sshkey");

    /** Well-known connection keys exposed as top-level fields in the response. */
    static final String KEY_HOST = "host";
    static final String KEY_PORT = "port";

    @Inject
    private ExtensionHelper extensionHelper;

    @Inject
    private PhysicalNetworkDao physicalNetworkDao;

    // -----------------------------------------------------------------------
    // PluggableService
    // -----------------------------------------------------------------------

    @Override
    public List<Class<?>> getCommands() {
        List<Class<?>> cmds = new ArrayList<>();
        cmds.add(AddExternalNetworkDeviceCmd.class);
        cmds.add(ListExternalNetworkDevicesCmd.class);
        cmds.add(UpdateExternalNetworkDeviceCmd.class);
        cmds.add(DeleteExternalNetworkDeviceCmd.class);
        return cmds;
    }

    // -----------------------------------------------------------------------
    // CRUD
    // -----------------------------------------------------------------------

    @Override
    public ExternalNetworkDeviceResponse addExternalNetworkDevice(AddExternalNetworkDeviceCmd cmd) {
        long physNetId = cmd.getPhysicalNetworkId();

        // Extension must already be registered with this physical network
        Long mapId = extensionHelper.getResourceMapIdForPhysicalNetwork(physNetId);
        if (mapId == null) {
            throw new InvalidParameterValueException(
                    "No NetworkOrchestrator extension is registered with physical network " + physNetId
                    + ". Please call registerExtension first.");
        }

        // host and port are top-level params; all other properties (username,
        // password, sshkey, custom keys) come through the 'details' map
        Map<String, String> details = new HashMap<>();
        if (cmd.getHost() != null) details.put(KEY_HOST, cmd.getHost());
        if (cmd.getPort() != null) details.put(KEY_PORT, String.valueOf(cmd.getPort()));

        // Merge extra details (username, password, sshkey, custom props)
        if (cmd.getDetails() != null) {
            convertMapParam(cmd.getDetails(), details);
        }

        if (details.isEmpty()) {
            throw new InvalidParameterValueException(
                    "At least host or one detail (e.g. username, sshkey) must be provided.");
        }

        // Sensitive keys are stored with display=false and never returned
        Set<String> displayKeys = new HashSet<>(details.keySet());
        displayKeys.removeAll(SENSITIVE_KEYS);

        extensionHelper.updateResourceMapDetails(mapId, details, displayKeys);
        return buildResponse(physNetId);
    }

    @Override
    public List<ExternalNetworkDeviceResponse> listExternalNetworkDevices(ListExternalNetworkDevicesCmd cmd) {
        List<ExternalNetworkDeviceResponse> result = new ArrayList<>();

        List<Long> physNetIds;
        if (cmd.getPhysicalNetworkId() != null) {
            physNetIds = List.of(cmd.getPhysicalNetworkId());
        } else {
            physNetIds = extensionHelper.listPhysicalNetworkIdsWithExtension();
        }

        for (Long physNetId : physNetIds) {
            ExternalNetworkDeviceResponse resp = buildResponse(physNetId);
            if (resp != null) {
                result.add(resp);
            }
        }
        return result;
    }

    @Override
    public ExternalNetworkDeviceResponse updateExternalNetworkDevice(UpdateExternalNetworkDeviceCmd cmd) {
        long physNetId = cmd.getPhysicalNetworkId();
        Long mapId = extensionHelper.getResourceMapIdForPhysicalNetwork(physNetId);
        if (mapId == null) {
            throw new InvalidParameterValueException(
                    "No NetworkOrchestrator extension is registered with physical network " + physNetId);
        }

        // host and port are top-level; all other properties come through details
        Map<String, String> updates = new HashMap<>();
        if (cmd.getHost() != null) updates.put(KEY_HOST, cmd.getHost());
        if (cmd.getPort() != null) updates.put(KEY_PORT, String.valueOf(cmd.getPort()));
        if (cmd.getDetails() != null) convertMapParam(cmd.getDetails(), updates);

        if (updates.isEmpty()) {
            throw new InvalidParameterValueException("No fields provided for update.");
        }

        Set<String> displayKeys = new HashSet<>(updates.keySet());
        displayKeys.removeAll(SENSITIVE_KEYS);

        extensionHelper.updateResourceMapDetails(mapId, updates, displayKeys);
        return buildResponse(physNetId);
    }

    @Override
    public void deleteExternalNetworkDevice(DeleteExternalNetworkDeviceCmd cmd) {
        long physNetId = cmd.getPhysicalNetworkId();
        Long mapId = extensionHelper.getResourceMapIdForPhysicalNetwork(physNetId);
        if (mapId == null) {
            throw new InvalidParameterValueException(
                    "No NetworkOrchestrator extension is registered with physical network " + physNetId);
        }
        // Remove all device details from the resource map
        Map<String, String> existing = extensionHelper.getAllResourceMapDetailsForPhysicalNetwork(physNetId);
        if (!existing.isEmpty()) {
            extensionHelper.removeResourceMapDetails(mapId, new ArrayList<>(existing.keySet()));
        }
    }

    // -----------------------------------------------------------------------
    // Response builder
    // -----------------------------------------------------------------------

    @Override
    public ExternalNetworkDeviceResponse buildResponse(long physicalNetworkId) {
        Long mapId = extensionHelper.getResourceMapIdForPhysicalNetwork(physicalNetworkId);
        if (mapId == null) {
            return null;
        }
        Extension extension = extensionHelper.getExtensionForPhysicalNetwork(physicalNetworkId);
        PhysicalNetworkVO physNet = physicalNetworkDao.findById(physicalNetworkId);

        // Only display=true details are returned
        Map<String, String> details = extensionHelper.getResourceMapDetailsForPhysicalNetwork(physicalNetworkId);

        ExternalNetworkDeviceResponse resp = new ExternalNetworkDeviceResponse();
        if (physNet != null) {
            resp.setPhysicalNetworkId(physNet.getUuid());
            resp.setPhysicalNetworkName(physNet.getName());
        }
        if (extension != null) {
            resp.setExtensionId(extension.getUuid());
            resp.setExtensionName(extension.getName());
        }
        resp.setHost(details.remove(KEY_HOST));
        resp.setPort(details.remove(KEY_PORT));
        // remaining display=true details (username, namespace, vrf, etc.) go into details map
        if (!details.isEmpty()) {
            resp.setDetails(details);
        }
        return resp;
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Converts the raw Marvin/API {@code Map} parameter (which uses nested
     * {@code Map<String,String>} entries like {@code {0: {key: "namespace", value: "ns1"}}})
     * into a flat {@code Map<String,String>}.
     */
    @SuppressWarnings("unchecked")
    private void convertMapParam(Map raw, Map<String, String> target) {
        if (raw == null) return;
        for (Object val : raw.values()) {
            if (val instanceof Map) {
                Map<String, String> entry = (Map<String, String>) val;
                // Marvin encodes as {key: "k", value: "v"} or directly as {"k": "v"}
                if (entry.containsKey("key") && entry.containsKey("value")) {
                    String k = entry.get("key");
                    String v = entry.get("value");
                    if (k != null && !k.isBlank()) {
                        target.put(k.trim(), v != null ? v : "");
                    }
                } else {
                    for (Map.Entry<String, String> e : entry.entrySet()) {
                        if (e.getKey() != null && !e.getKey().isBlank()) {
                            target.put(e.getKey().trim(), e.getValue() != null ? e.getValue() : "");
                        }
                    }
                }
            }
        }
    }
}

