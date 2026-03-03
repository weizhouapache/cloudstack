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

import java.util.List;

import org.apache.cloudstack.network.element.api.command.AddExternalNetworkDeviceCmd;
import org.apache.cloudstack.network.element.api.command.DeleteExternalNetworkDeviceCmd;
import org.apache.cloudstack.network.element.api.command.ListExternalNetworkDevicesCmd;
import org.apache.cloudstack.network.element.api.command.UpdateExternalNetworkDeviceCmd;
import org.apache.cloudstack.network.element.api.response.ExternalNetworkDeviceResponse;

import com.cloud.utils.component.PluggableService;

/**
 * Service for managing external network device details stored in
 * {@code extension_resource_map_details}.
 *
 * <p>An "external network device" is simply the set of connection details
 * (host, port, username, password, sshkey, and any custom key/value pairs)
 * stored against the {@code extension_resource_map} entry that links a
 * {@code NetworkOrchestrator} extension to a {@code PhysicalNetwork}.</p>
 *
 * <p>Well-known keys:</p>
 * <ul>
 *   <li>{@code host}     – IP / hostname (display=true)</li>
 *   <li>{@code port}     – SSH / API port, default 22 (display=true)</li>
 *   <li>{@code username} – SSH / API username (display=true)</li>
 *   <li>{@code password} – SSH / API password (display=false – never returned)</li>
 *   <li>{@code sshkey}   – SSH private key PEM (display=false – never returned)</li>
 * </ul>
 * Any additional keys passed via {@code details} are stored with display=true
 * and passed to the entry-point script as {@code CS_NET_<KEY>} env vars.
 */
public interface ExternalNetworkDeviceService extends PluggableService {

    ExternalNetworkDeviceResponse addExternalNetworkDevice(AddExternalNetworkDeviceCmd cmd);

    List<ExternalNetworkDeviceResponse> listExternalNetworkDevices(ListExternalNetworkDevicesCmd cmd);

    ExternalNetworkDeviceResponse updateExternalNetworkDevice(UpdateExternalNetworkDeviceCmd cmd);

    void deleteExternalNetworkDevice(DeleteExternalNetworkDeviceCmd cmd);

    /**
     * Builds an {@link ExternalNetworkDeviceResponse} for the given physical network.
     * Returns {@code null} if no extension is registered for it.
     */
    ExternalNetworkDeviceResponse buildResponse(long physicalNetworkId);
}

