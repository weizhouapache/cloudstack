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

package com.cloud.automation.controller.dao;

import java.util.List;

import com.cloud.automation.controller.AutomationController;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.TransactionLegacy;
import org.springframework.stereotype.Component;

import com.cloud.automation.controller.AutomationControllerVO;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchCriteria;

@Component
public class AutomationControllerDaoImpl extends GenericDaoBase<AutomationControllerVO, Long> implements AutomationControllerDao {
    private final SearchBuilder<AutomationControllerVO> StateSearch;
    private final SearchBuilder<AutomationControllerVO> SameNetworkSearch;
    private final SearchBuilder<AutomationControllerVO> AutomationTemplateSearch;

    public AutomationControllerDaoImpl() {
        StateSearch = createSearchBuilder();
        StateSearch.and("state", StateSearch.entity().getState(), SearchCriteria.Op.EQ);
        StateSearch.done();

        SameNetworkSearch = createSearchBuilder();
        SameNetworkSearch.and("network_id", SameNetworkSearch.entity().getNetworkId(), SearchCriteria.Op.EQ);
        SameNetworkSearch.done();

        AutomationTemplateSearch = createSearchBuilder();
        AutomationTemplateSearch.and("automationTemplateId", AutomationTemplateSearch.entity().getAutomationTemplateId(), SearchCriteria.Op.EQ);
        AutomationTemplateSearch.done();
    }

    @Override
    public List<AutomationControllerVO> findAutomationControllersInState(AutomationController.State state) {
        SearchCriteria<AutomationControllerVO> sc = StateSearch.create();
        sc.setParameters("state", state);
        return listBy(sc);
    }

    @Override
    public List<AutomationControllerVO> listAllInZone(long dataCenterId) {
        SearchCriteria<AutomationControllerVO> sc = createSearchCriteria();
        SearchCriteria<AutomationControllerVO> scc = createSearchCriteria();
        scc.addOr("zoneId", SearchCriteria.Op.EQ, dataCenterId);
        scc.addOr("zoneId", SearchCriteria.Op.NULL);
        sc.addAnd("zoneId", SearchCriteria.Op.SC, scc);
        return listBy(sc);
    }

    @Override
    public List<AutomationControllerVO> listAllByAutomationVersion(long automationTemplateId) {
        SearchCriteria<AutomationControllerVO> sc = AutomationTemplateSearch.create();
        sc.setParameters("automationTemplateId", automationTemplateId);
        return this.listBy(sc);
    }

    @Override
    public boolean updateState(AutomationController.State currentState, AutomationController.Event event, AutomationController.State nextState,
                               AutomationController vo, Object data) {
        // TODO: ensure this update is correct
        TransactionLegacy txn = TransactionLegacy.currentTxn();
        txn.start();

        AutomationControllerVO ccVo = (AutomationControllerVO)vo;
        ccVo.setState(nextState);
        super.update(ccVo.getId(), ccVo);

        txn.commit();
        return true;
    }
}

