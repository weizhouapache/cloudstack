//
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
//

package com.cloud.agent.api;

import com.cloud.agent.api.to.VirtualMachineTO;

public class PrepareForMigrationCommand extends Command {
    private VirtualMachineTO vm;
    private boolean rollback;

    /**
     * Indicates if this command is for the source host (true) or destination host (false, default).
     */
    private boolean isSource = false; // default: destination

    /**
     * Legacy constructor that defaults to destination host.
     * @param vm VirtualMachineTO
     */
    public PrepareForMigrationCommand(VirtualMachineTO vm) {
        this(vm, false);
    }

    /**
     * Create a PrepareForMigrationCommand for a VM, specifying if this is for the source host.
     * @param vm VirtualMachineTO
     * @param isSource true if source host, false if destination host
     */
    public PrepareForMigrationCommand(VirtualMachineTO vm, boolean isSource) {
        this.vm = vm;
        this.isSource = isSource;
    }

    public VirtualMachineTO getVirtualMachine() {
        return vm;
    }

    public void setRollback(boolean rollback) {
        this.rollback = rollback;
    }

    public boolean isRollback() {
        return rollback;
    }

    /**
     * Returns true if this command is for the source host, false if destination.
     */
    public boolean isSource() {
        return isSource;
    }

    /**
     * Set whether this command is for the source host.
     */
    public void setSource(boolean isSource) {
        this.isSource = isSource;
    }

    @Override
    public boolean executeInSequence() {
        return true;
    }
}
