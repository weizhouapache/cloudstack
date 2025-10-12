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

import org.apache.cloudstack.api.command.user.vnf.BaseVnfCmd;

public class CustomVnfProvider extends BaseVnfProvider {

    Long getVnfBrokerId() {
        return null;
    }

    @Override
    public VnfConnector getConnector(BaseVnfCmd command) {
        // TODO: get connector from VNF broker
        return new VnfHttpConnector();
    }

    @Override
    public VnfDataFormatHandler getDataFormatHandler(BaseVnfCmd command) {
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
