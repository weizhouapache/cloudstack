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
import org.apache.cloudstack.vnf.api.command.DeleteVnfBrokerCmd;
import org.apache.cloudstack.vnf.api.command.ListVnfBrokersCmd;
import org.apache.cloudstack.vnf.api.command.RegisterVnfBrokerCmd;
import org.apache.cloudstack.vnf.api.command.UpdateVnfBrokerCmd;
import org.apache.cloudstack.vnf.api.response.VnfBrokerResponse;

import java.util.List;

public class VnfBrokerManagerImpl extends ComponentLifecycleBase implements VnfBrokerManager {


    @Override
    public List<Class<?>> getCommands() {
        return List.of();
    }

    @Override
    public VnfBroker registerVnfBroker(RegisterVnfBrokerCmd registerVnfBrokerCmd) {
        return null;
    }

    @Override
    public VnfBrokerResponse createVnfBrokerResponse(VnfBroker result) {
        return null;
    }

    @Override
    public VnfBroker updateVnfBroker(UpdateVnfBrokerCmd updateVnfBrokerCmd) {
        return null;
    }

    @Override
    public boolean deleteVnfBroker(DeleteVnfBrokerCmd deleteVnfBrokerCmd) {
        return false;
    }

    @Override
    public List<? extends VnfBroker> listVnfBrokers(ListVnfBrokersCmd listVnfBrokersCmd) {
        return List.of();
    }
}
