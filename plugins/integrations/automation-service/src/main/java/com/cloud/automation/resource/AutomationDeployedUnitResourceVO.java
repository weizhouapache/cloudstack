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

package com.cloud.automation.resource;

import java.util.Date;
import java.util.UUID;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

import com.cloud.utils.db.GenericDao;

@Entity
@Table(name = "automation_deployed_resources_group_details")
public class AutomationDeployedUnitResourceVO implements AutomationDeployedUnitResource {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private long id;

    @Column(name = "uuid")
    private String uuid;

    @Column(name = "deployed_group_id")
    private Long deployedGroupId;

    @Column(name = "deployed_vm_id")
    private Long deployedVmId;

    @Column(name = "service_unit_name")
    private String serviceUnitName;

    @Column(name = "state")
    @Enumerated(value = EnumType.STRING)
    State state = State.Active;

    @Column(name = GenericDao.CREATED_COLUMN)
    Date created;

    public AutomationDeployedUnitResourceVO() {
        this.uuid = UUID.randomUUID().toString();
    }

    public AutomationDeployedUnitResourceVO(Long deployedGroupId) {
        this.deployedGroupId = deployedGroupId;
    }

    public AutomationDeployedUnitResourceVO(Long deployedGroupId, Long deployedVmId, String serviceUnitName, String state) {
        this.uuid = UUID.randomUUID().toString();
        this.deployedGroupId = deployedGroupId;
        this.deployedVmId = deployedVmId;
        this.serviceUnitName = serviceUnitName;
        this.state = State.valueOf(state);
    }

    @Override
    public long getId() {
        return id;
    }

    @Override
    public String getUuid() {
        return uuid;
    }

    @Override
    public Long getDeployedGroupId() {
        return deployedGroupId;
    }

    @Override
    public Long getDeployedVmId() {
        return deployedVmId;
    }

    @Override
    public String getServiceUnitName() {
        return serviceUnitName;
    }

    @Override
    public State getState() {
        return state;
    }

    @Override
    public Date getCreated() {
        return created;
    }
}