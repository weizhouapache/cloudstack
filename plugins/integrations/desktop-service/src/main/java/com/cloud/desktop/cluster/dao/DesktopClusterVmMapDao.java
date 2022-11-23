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
package com.cloud.desktop.cluster.dao;

import com.cloud.desktop.cluster.DesktopClusterVmMapVO;
import com.cloud.utils.db.GenericDao;

import java.util.List;

public interface DesktopClusterVmMapDao extends GenericDao<DesktopClusterVmMapVO, Long> {
    public List<DesktopClusterVmMapVO> listByDesktopClusterId(long desktopClusterId);
    public List<DesktopClusterVmMapVO> listByDesktopClusterIdAndVmType(long desktopClusterId, String type); //search for dekstop vm list
    public List<DesktopClusterVmMapVO> listByDesktopClusterIdAndNotVmType(long desktopClusterId, String type); //search for control vm list
}