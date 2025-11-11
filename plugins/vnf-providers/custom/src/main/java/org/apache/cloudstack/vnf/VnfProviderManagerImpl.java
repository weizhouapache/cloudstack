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
package org.apache.cloudstack.vnf;

import com.cloud.utils.component.ComponentLifecycleBase;
import org.apache.cloudstack.api.command.user.vnf.PerformVnfActionCmd;
import org.apache.cloudstack.vnf.api.command.DeleteVnfBrokerCmd;
import org.apache.cloudstack.vnf.api.command.DeleteVnfProviderCmd;
import org.apache.cloudstack.vnf.api.command.ListVnfBrokersCmd;
import org.apache.cloudstack.vnf.api.command.RegisterVnfBrokerCmd;
import org.apache.cloudstack.vnf.api.command.RegisterVnfProviderCmd;
import org.apache.cloudstack.vnf.api.command.UpdateVnfBrokerCmd;
import org.apache.cloudstack.vnf.api.command.UpdateVnfProviderCmd;
import org.apache.cloudstack.vnf.api.command.VnfListAllProvidersCmd;
import org.apache.cloudstack.vnf.api.response.VnfBrokerResponse;

import java.util.ArrayList;
import java.util.List;

public class VnfProviderManagerImpl extends ComponentLifecycleBase implements VnfProviderManager {


    @Override
    public List<Class<?>> getCommands() {
        List<Class<?>> cmdList = new ArrayList<>();

        cmdList.add(RegisterVnfProviderCmd.class);
        cmdList.add(DeleteVnfProviderCmd.class);
        cmdList.add(UpdateVnfProviderCmd.class);
        cmdList.add(VnfListAllProvidersCmd.class);
        cmdList.add(PerformVnfActionCmd.class);

        cmdList.add(RegisterVnfBrokerCmd.class);
        cmdList.add(UpdateVnfBrokerCmd.class);
        cmdList.add(DeleteVnfBrokerCmd.class);
        cmdList.add(ListVnfBrokersCmd.class);

        return cmdList;
    }

    @Override
    public VnfProviderConnection registerVnfBroker(RegisterVnfBrokerCmd registerVnfBrokerCmd) {
        return null;
    }

    @Override
    public VnfBrokerResponse createVnfBrokerResponse(VnfProviderConnection result) {
        return null;
    }

    @Override
    public VnfProviderConnection updateVnfBroker(UpdateVnfBrokerCmd updateVnfBrokerCmd) {
        return null;
    }

    @Override
    public boolean deleteVnfBroker(DeleteVnfBrokerCmd deleteVnfBrokerCmd) {
        return false;
    }

    @Override
    public List<? extends VnfProviderConnection> listVnfBrokers(ListVnfBrokersCmd listVnfBrokersCmd) {
        return List.of();
    }

    @Override
    public VnfProvider registerVnfProvider(RegisterVnfProviderCmd registerVnfProviderCmd) {
        return null;
    }

    @Override
    public boolean deleteVnfProvider(DeleteVnfProviderCmd deleteVnfProviderCmd) {
        return false;
    }

    @Override
    public VnfProvider updateVnfProvider(UpdateVnfProviderCmd updateVnfProviderCmd) {
        return null;
    }

    @Override
    public List<? extends VnfProvider> listVnfProviders(VnfListAllProvidersCmd vnfListAllProvidersCmd) {
        // search by Name or/and keyword
        return List.of();
    }
}
