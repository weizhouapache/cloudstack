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
import org.apache.cloudstack.vnf.api.command.DeleteVnfProviderCmd;
import org.apache.cloudstack.vnf.api.command.RegisterVnfProviderCmd;
import org.apache.cloudstack.vnf.api.command.UpdateVnfProviderCmd;
import org.apache.cloudstack.vnf.api.command.VnfListProvidersCmd;

import java.util.ArrayList;
import java.util.List;

public class VnfProviderManagerImpl extends ComponentLifecycleBase implements VnfProviderManager {


    @Override
    public List<Class<?>> getCommands() {
        List<Class<?>> cmdList = new ArrayList<>();

        cmdList.add(RegisterVnfProviderCmd.class);
        cmdList.add(DeleteVnfProviderCmd.class);
        cmdList.add(UpdateVnfProviderCmd.class);
        cmdList.add(VnfListProvidersCmd.class);
        cmdList.add(PerformVnfActionCmd.class);

        return cmdList;
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
    public List<? extends VnfProvider> listVnfProviders(VnfListProvidersCmd vnfListProvidersCmd) {
        // search by Name or/and keyword
        return new ArrayList<>();
    }
}
