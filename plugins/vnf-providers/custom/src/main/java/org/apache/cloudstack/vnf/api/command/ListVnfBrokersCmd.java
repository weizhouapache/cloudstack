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
package org.apache.cloudstack.vnf.api.command;

import java.util.ArrayList;
import java.util.List;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseListCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.ZoneResponse;
import org.apache.cloudstack.vnf.VnfBroker;
import org.apache.cloudstack.vnf.VnfBrokerManager;
import org.apache.cloudstack.vnf.api.response.VnfBrokerResponse;

import javax.inject.Inject;

@APICommand(name = "listVnfBrokers",
        description = "Lists Vnf brokers.",
        responseObject = VnfBrokerResponse.class,
        since = "4.22.0",
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false,
        authorized = {RoleType.Admin})
public class ListVnfBrokersCmd extends BaseListCmd {

    @Inject
    VnfBrokerManager vnfBrokerManager;

    @Parameter(name = ApiConstants.ID,
            type = CommandType.UUID,
            entityType = VnfBrokerResponse.class,
            description = "UUID of the Vnf broker.")
    private Long id;

    @Parameter(name = ApiConstants.ZONE_ID,
            type = CommandType.UUID,
            entityType = ZoneResponse.class,
            description = "UUID of zone to which the Vnf broker belongs to.")
    private Long zoneId;
    @Parameter(name = ApiConstants.NAME,
            type = CommandType.STRING,
            description = "the name of the Vnf broker.")
    private String name;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Long getId() {
        return id;
    }


    @Override
    public void execute() {
        List<? extends VnfBroker> subnets = vnfBrokerManager.listVnfBrokers(this);
        ListResponse<VnfBrokerResponse> response = new ListResponse<>();
        List<VnfBrokerResponse> subnetResponses = new ArrayList<>();
        for (VnfBroker subnet : subnets) {
            VnfBrokerResponse subnetResponse = vnfBrokerManager.createVnfBrokerResponse(subnet);
            subnetResponse.setObjectName("bgppeer");
            subnetResponses.add(subnetResponse);
        }

        response.setResponses(subnetResponses, subnets.size());
        response.setResponseName(getCommandName());
        this.setResponseObject(response);
    }

}
