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
package com.cloud.desktop.cluster;

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
@Table(name = "desktop_ip_range")
public class DesktopClusterIpRangeVO implements DesktopClusterIpRange {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private long id;

    @Column(name = "uuid")
    private String uuid;

    @Column(name = "desktop_cluster_id")
    private long desktopClusterId;

    @Column(name = "gateway")
    private String gateway;

    @Column(name = "netmask")
    private String netmask;

    @Column(name = "start_ip")
    private String startIp;

    @Column(name = "end_ip")
    private String endIp;

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
    public long getDesktopClusterId() {
        return desktopClusterId;
    }

    public void setDesktopClusterId(long desktopClusterId) {
        this.desktopClusterId = desktopClusterId;
    }

    @Override
    public String getGateway() {
        return gateway;
    }

    public void setGateway(String gateway) {
        this.gateway = gateway;
    }

    @Override
    public String getNetmask() {
        return netmask;
    }

    public void setNetmask(String netmask) {
        this.netmask = netmask;
    }

    @Override
    public String getStartIp() {
        return startIp;
    }

    public void setStartIp(String startIp) {
        this.startIp = startIp;
    }

    @Override
    public String getEndIp() {
        return endIp;
    }

    public void setEndIp(String endIp) {
        this.endIp = endIp;
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

    public DesktopClusterIpRangeVO() {
        this.uuid = UUID.randomUUID().toString();
    }

    public DesktopClusterIpRangeVO(long desktopClusterId, String gateway, String netmask, String startIp, String endIp) {
        this.uuid = UUID.randomUUID().toString();
        this.desktopClusterId = desktopClusterId;
        this.gateway = gateway;
        this.netmask = netmask;
        this.startIp = startIp;
        this.endIp = endIp;
    }
}
