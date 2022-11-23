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

package com.cloud.automation.version.dao;

import java.util.List;

import com.cloud.utils.db.SearchBuilder;
import org.springframework.stereotype.Component;

import com.cloud.automation.version.AutomationControllerVersionVO;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchCriteria;

@Component
public class AutomationControllerVersionDaoImpl extends GenericDaoBase<AutomationControllerVersionVO, Long> implements AutomationControllerVersionDao {

    private final SearchBuilder<AutomationControllerVersionVO> versionIdSearch;

    public AutomationControllerVersionDaoImpl() {
        versionIdSearch = createSearchBuilder();
        versionIdSearch.and("versionId", versionIdSearch.entity().getId(), SearchCriteria.Op.EQ);
        versionIdSearch.done();
    }

    @Override
    public List<AutomationControllerVersionVO> listAllInZone(long dataCenterId) {
        SearchCriteria<AutomationControllerVersionVO> sc = createSearchCriteria();
        SearchCriteria<AutomationControllerVersionVO> scc = createSearchCriteria();
        scc.addOr("zoneId", SearchCriteria.Op.EQ, dataCenterId);
        scc.addOr("zoneId", SearchCriteria.Op.NULL);
        sc.addAnd("zoneId", SearchCriteria.Op.SC, scc);
        return listBy(sc);
    }

    @Override
    public List<AutomationControllerVersionVO> listByVersionId(long versionId) {
        SearchCriteria<AutomationControllerVersionVO> sc = versionIdSearch.create();
        sc.setParameters("versionId", versionId);
        return listBy(sc, null);
    }
}

