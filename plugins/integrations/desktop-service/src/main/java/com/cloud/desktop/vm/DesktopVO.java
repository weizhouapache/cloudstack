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
package com.cloud.desktop.vm;

import java.util.Date;
import java.util.UUID;


import javax.persistence.Column;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;

import com.cloud.utils.db.GenericDao;

@Entity
@Table(name = "desktop")
public class DesktopVO implements Desktop {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private long id;

    @Column(name = "uuid")
    private String uuid;

    @Column(name = "name")
    private String name;

    @Column(name = "zone_id")
    private long zoneId;

    @Column(name = "desktop_version_id")
    private long desktopVersionId;

    @Column(name = "service_offering_id")
    private long serviceOfferingId;

    @Column(name = "ad_domain_name")
    private String adDomainName;

    @Column(name = "network_id")
    private long networkId;

    @Column(name = "domain_id")
    private long domainId;

    @Column(name = "account_id")
    private long accountId;

    @Column(name = "state")
    private State  state;

    @Column(name = GenericDao.CREATED_COLUMN)
    private Date created;

    @Column(name = GenericDao.REMOVED_COLUMN)
    private Date removed;

    @Override
    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    @Override
    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    @Override
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public long getZoneId() {
        return zoneId;
    }

    public void setZoneId(long zoneId) {
        this.zoneId = zoneId;
    }

    @Override
    public long getDesktopVersionId() {
        return desktopVersionId;
    }

    public void setDesktopVersionId(long desktopVersionId) {
        this.desktopVersionId = desktopVersionId;
    }

    @Override
    public long getServiceOfferingId() {
        return serviceOfferingId;
    }

    public void setServiceOfferingId(long serviceOfferingId) {
        this.serviceOfferingId = serviceOfferingId;
    }

    @Override
    public String getAdDomainName() {
        return adDomainName;
    }

    public void setAdDomainName(String adDomainName) {
        this.adDomainName = adDomainName;
    }

    @Override
    public long getNetworkId() {
        return networkId;
    }

    public void setNetworkId(long networkId) {
        this.networkId = networkId;
    }

    @Override
    public long getDomainId() {
        return domainId;
    }

    public void setDomainId(long domainId) {
        this.domainId = domainId;
    }

    @Override
    public long getAccountId() {
        return accountId;
    }

    public void setAccountId(long accountId) {
        this.accountId = accountId;
    }

    @Override
    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    @Override
    public boolean isDisplay() {
        return true;
    }

    public Date getRemoved() {
        if (removed == null)
            return null;
        return new Date(removed.getTime());
    }

    @Override
    public Date getCreated() {
        return created;
    }

    public DesktopVO() {
        this.uuid = UUID.randomUUID().toString();
    }

    public DesktopVO(String name, long zoneId, long desktopVersionId, long serviceOfferingId, String adDomainName,
                               long networkId, long domainId, long accountId, State state) {
        this.uuid = UUID.randomUUID().toString();
        this.name = name;
        this.zoneId = zoneId;
        this.desktopVersionId = desktopVersionId;
        this.serviceOfferingId = serviceOfferingId;
        this.adDomainName = adDomainName;
        this.networkId = networkId;
        this.domainId = domainId;
        this.accountId = accountId;
        this.state = state;
    }

    @Override
    public Class<?> getEntityType() {
        return Desktop.class;
    }
}
