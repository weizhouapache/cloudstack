/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cloudstack.vnf;

import org.apache.cloudstack.api.command.user.vnf.PerformVnfActionCmd;

public class CustomVnfProvider extends BaseVnfProvider {

    // TODO: define endpoints for services, the operation would be the lower case of VnfOperation
    // For example, for VnfOperation.FIREWALL_RULE_CREATE,
    // The SSH command argument would be "<script path> firewall_rule_create <other args>", other args are formatted in json
    // The HTTP request would be "http://<vnf-broker-endpoint>/firewall_rule_create", json data is sent via POST

    protected Long getVnfBrokerId() {
        return null;
    }

    @Override
    public VnfConnector getConnector(PerformVnfActionCmd command) {
        // TODO: get connector from VNF broker
        return new VnfHttpConnector();
    }

    @Override
    public VnfDataFormatHandler getDataFormatHandler(PerformVnfActionCmd command) {
        // TODO: get dataformat handle from VNF broker
        return new VnfJsonHandler();
    }

    @Override
    public String executeVnfCommand(VnfConfig vnfConfig, VnfConnector vnfConnector, VnfDataFormatHandler dataFormatHandler, String formattedData) {
        // TODO: add broker specific logic
        // Replace vnfConfig with VNF broker config
        // Pass vnfConfig as part of data to VNF broker

        return vnfConnector.execute(vnfConfig, dataFormatHandler.getDataFormat(), formattedData);
    }
}
