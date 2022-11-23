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

import java.util.List;

import org.springframework.stereotype.Component;

import com.cloud.desktop.cluster.DesktopClusterVmMapVO;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;


@Component
public class DesktopClusterVmMapDaoImpl extends GenericDaoBase<DesktopClusterVmMapVO, Long> implements DesktopClusterVmMapDao {

    private final SearchBuilder<DesktopClusterVmMapVO> desktopIdSearch;
    private final SearchBuilder<DesktopClusterVmMapVO> desktopIdAndVmType;
    private final SearchBuilder<DesktopClusterVmMapVO> desktopIdAndNotVmType;

    public DesktopClusterVmMapDaoImpl() {
        desktopIdSearch = createSearchBuilder();
        desktopIdSearch.and("desktopClusterId", desktopIdSearch.entity().getDesktopClusterId(), SearchCriteria.Op.EQ);
        desktopIdSearch.done();

        desktopIdAndVmType = createSearchBuilder();
        desktopIdAndVmType.and("desktopClusterId", desktopIdAndVmType.entity().getDesktopClusterId(), SearchCriteria.Op.EQ);
        desktopIdAndVmType.and("type", desktopIdAndVmType.entity().getType(), SearchCriteria.Op.EQ);
        desktopIdAndVmType.done();

        desktopIdAndNotVmType = createSearchBuilder();
        desktopIdAndNotVmType.and("desktopClusterId", desktopIdAndNotVmType.entity().getDesktopClusterId(), SearchCriteria.Op.EQ);
        desktopIdAndNotVmType.and("type", desktopIdAndNotVmType.entity().getType(), SearchCriteria.Op.NEQ);
        desktopIdAndNotVmType.done();

    }

    @Override
    public List<DesktopClusterVmMapVO> listByDesktopClusterId(long desktopClusterId) {
        SearchCriteria<DesktopClusterVmMapVO> sc = desktopIdSearch.create();
        sc.setParameters("desktopClusterId", desktopClusterId);
        return listBy(sc, null);
    }

    @Override
    public List<DesktopClusterVmMapVO> listByDesktopClusterIdAndVmType(long desktopClusterId, String type) {
        SearchCriteria<DesktopClusterVmMapVO> sc = desktopIdAndVmType.create();
        sc.setParameters("desktopClusterId", desktopClusterId);
        sc.setParameters("type", type);
        return listBy(sc);
    }

    @Override
    public List<DesktopClusterVmMapVO> listByDesktopClusterIdAndNotVmType(long desktopClusterId, String type) {
        SearchCriteria<DesktopClusterVmMapVO> sc = desktopIdAndNotVmType.create();
        sc.setParameters("desktopClusterId", desktopClusterId);
        sc.setParameters("type", type);
        return listBy(sc);
    }
}