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
import javax.persistence.Temporal;
import javax.persistence.TemporalType;

@Entity
@Table(name = "automation_deployed_resources_group")
public class AutomationDeployedResourceVO implements AutomationDeployedResource {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private long id;

    @Column(name = "uuid")
    private String uuid;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "domain_id")
    private long domainId;

    @Column(name = "zone_id")
    private Long zoneId;

    @Column(name = "controller_id")
    private Long controllerId;

    @Column(name = "name")
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "access_info")
    private String accessInfo;

    @Column(name = "state")
    @Enumerated(value = EnumType.STRING)
    State state = State.Active;

    @Column(name = GenericDao.CREATED_COLUMN)
    Date created;

    @Column(name = "last_updated")
    @Temporal(value = TemporalType.TIMESTAMP)
    Date lastUpdated;

    public AutomationDeployedResourceVO() {
        this.uuid = UUID.randomUUID().toString();
    }

    public AutomationDeployedResourceVO(long accountId,long domainId, Long zoneId, Long controllerId, String name, String description, String accessInfo) {
        this.uuid = UUID.randomUUID().toString();
        this.accountId = accountId;
        this.domainId = domainId;
        this.zoneId = zoneId;
        this.controllerId = controllerId;
        this.name = name;
        this.description = description;
        this.accessInfo = accessInfo;
        this.lastUpdated = new Date();
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
    public long getAccountId() {
        return accountId;
    }

    public long getControllerId() {
        return controllerId;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public String getAccessInfo() {
        return accessInfo;
    }

    public void setAccessInfo(String accessInfo) {
        this.accessInfo = accessInfo;
    }

    @Override
    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    @Override
    public Date getCreated() {
        return created;
    }

    @Override
    public Date getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(Date lastUpdated) {
        this.lastUpdated = lastUpdated;
    }
    @Override
    public long getDomainId() {
        return domainId;
    }

    public void setDomainId(long domainId) {
        this.domainId = domainId;
    }

    @Override
    public Long getZoneId() {
        return zoneId;
    }

    public void setZoneId(long zoneId) {
        this.zoneId = zoneId;
    }

    @Override
    public Class<?> getEntityType() {
        return AutomationDeployedResource.class;
    }

}