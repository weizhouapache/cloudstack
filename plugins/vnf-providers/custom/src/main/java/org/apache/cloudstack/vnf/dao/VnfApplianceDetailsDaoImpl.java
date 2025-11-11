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
package org.apache.cloudstack.vnf.dao;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.cloud.utils.db.GenericSearchBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Func;
import com.cloud.utils.db.SearchCriteria.Op;
import org.apache.cloudstack.resourcedetail.ResourceDetailsDaoBase;
import org.apache.cloudstack.vnf.vo.VnfApplianceDetailsVO;

public class VnfApplianceDetailsDaoImpl extends ResourceDetailsDaoBase<VnfApplianceDetailsVO> implements VnfApplianceDetailsDao {
    protected final SearchBuilder<VnfApplianceDetailsVO> DetailSearch;
    private final GenericSearchBuilder<VnfApplianceDetailsVO, String> ValueSearch;

    public VnfApplianceDetailsDaoImpl() {

        DetailSearch = createSearchBuilder();
        DetailSearch.and("resourceId", DetailSearch.entity().getResourceId(), SearchCriteria.Op.EQ);
        DetailSearch.and("name", DetailSearch.entity().getName(), SearchCriteria.Op.EQ);
        DetailSearch.and("value", DetailSearch.entity().getValue(), SearchCriteria.Op.EQ);
        DetailSearch.and("display", DetailSearch.entity().isDisplay(), SearchCriteria.Op.EQ);
        DetailSearch.done();

        ValueSearch = createSearchBuilder(String.class);
        ValueSearch.select(null, Func.DISTINCT, ValueSearch.entity().getValue());
        ValueSearch.and("resourceId", ValueSearch.entity().getResourceId(), SearchCriteria.Op.EQ);
        ValueSearch.and("name", ValueSearch.entity().getName(), Op.EQ);
        ValueSearch.and("display", ValueSearch.entity().isDisplay(), SearchCriteria.Op.EQ);
        ValueSearch.done();
    }

    @Override
    public Map<String, String> getVnfApplianceDetails(long vnfApplianceId) {
        SearchCriteria<VnfApplianceDetailsVO> sc = DetailSearch.create();
        sc.setParameters("resourceId", vnfApplianceId);
        sc.setParameters("display", true);

        List<VnfApplianceDetailsVO> results = search(sc, null);
        if (results.isEmpty()) {
            return null;
        }
        Map<String, String> details = new HashMap<>(results.size());
        for (VnfApplianceDetailsVO result : results) {
            details.put(result.getDetailName(), result.getValue());
        }

        return details;
    }

    @Override
    public String getDetail(long vnfApplianceId, String detailName) {
        SearchCriteria<String> sc = ValueSearch.create();
        sc.setParameters("name", detailName);
        sc.setParameters("resourceId", vnfApplianceId);
        List<String> results = customSearch(sc, null);
        if (results.isEmpty()) {
            return null;
        } else {
            return results.get(0);
        }
    }

    @Override
    public void addDetail(long resourceId, String key, String value, boolean display) {
        persist(new VnfApplianceDetailsVO(resourceId, key, value, display));
    }

    @Override
    public int removeByVnfApplianceId(long vnfApplianceId) {
        SearchCriteria<VnfApplianceDetailsVO> sc = DetailSearch.create();
        sc.setParameters("resourceId", vnfApplianceId);
        return remove(sc);
    }
}
