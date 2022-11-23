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
package org.apache.cloudstack.api.command.user.desktop.cluster;

import javax.inject.Inject;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.DesktopClusterResponse;
import org.apache.cloudstack.api.response.SuccessResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.log4j.Logger;

import com.cloud.exception.ConcurrentOperationException;
import com.cloud.desktop.cluster.DesktopCluster;
import com.cloud.desktop.cluster.DesktopClusterEventTypes;
import com.cloud.desktop.cluster.DesktopClusterService;
import com.cloud.utils.exception.CloudRuntimeException;

@APICommand(name = StopDesktopClusterCmd.APINAME, description = "Stops a running Desktop cluster",
        responseObject = SuccessResponse.class,
        responseView = ResponseObject.ResponseView.Restricted,
        entityType = {DesktopCluster.class},
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = true,
        authorized = {RoleType.Admin, RoleType.ResourceAdmin, RoleType.DomainAdmin, RoleType.User})
public class StopDesktopClusterCmd extends BaseAsyncCmd {
    public static final Logger LOGGER = Logger.getLogger(StopDesktopClusterCmd.class.getName());
    public static final String APINAME = "stopDesktopCluster";

    @Inject
    public DesktopClusterService desktopClusterService;

    /////////////////////////////////////////////////////
    //////////////// API parameters /////////////////////
    /////////////////////////////////////////////////////
    @Parameter(name = ApiConstants.ID, type = CommandType.UUID,
            entityType = DesktopClusterResponse.class, required = true,
            description = "the ID of the Desktop cluster")
    private Long id;

    /////////////////////////////////////////////////////
    /////////////////// Accessors ///////////////////////
    /////////////////////////////////////////////////////

    public Long getId() {
        return id;
    }

    @Override
    public String getEventType() {
        return DesktopClusterEventTypes.EVENT_DESKTOP_CLUSTER_STOP;
    }

    @Override
    public String getEventDescription() {
        String description = "Stopping Desktop cluster";
        DesktopCluster cluster = _entityMgr.findById(DesktopCluster.class, getId());
        if (cluster != null) {
            description += String.format(" ID: %s", cluster.getUuid());
        } else {
            description += String.format(" ID: %d", getId());
        }
        return description;
    }

    @Override
    public String getCommandName() {
        return APINAME.toLowerCase() + "response";
    }

    @Override
    public long getEntityOwnerId() {
        return CallContext.current().getCallingAccount().getId();
    }

    /////////////////////////////////////////////////////
    /////////////// API Implementation///////////////////
    /////////////////////////////////////////////////////

    @Override
    public void execute() throws ServerApiException, ConcurrentOperationException {
        try {
            if (!desktopClusterService.stopDesktopCluster(getId())) {
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, String.format("Failed to start Desktop cluster ID: %d", getId()));
            }
            final SuccessResponse response = new SuccessResponse(getCommandName());
            setResponseObject(response);
        } catch (CloudRuntimeException ex) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, ex.getMessage());
        }
    }

}
