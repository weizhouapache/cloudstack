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

package org.apache.cloudstack.backup.veeam;

import java.util.List;
import java.util.Map;

import com.cloud.vm.VirtualMachine;
import org.apache.cloudstack.backup.Backup;
import org.apache.cloudstack.backup.BackupOffering;
import org.apache.cloudstack.backup.veeam.api.Job;
import org.apache.cloudstack.backup.veeam.api.Ref;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

import com.cloud.hypervisor.Hypervisor;
import com.cloud.utils.Pair;

/**
 * Base abstract class for Veeam Backup & Replication API clients
 * Defines the interface for all Veeam client implementations
 */
public abstract class VeeamClientBase {
    protected Logger logger = LogManager.getLogger(getClass());

    protected final Integer veeamServerVersion;

    public VeeamClientBase(Integer veeamServerVersion) {
        this.veeamServerVersion = veeamServerVersion;
    }

    // Job Management
    public abstract List<BackupOffering> listJobs();
    public abstract Job listJob(final String jobId);
    public abstract boolean toggleJobSchedule(final String jobId);
    public abstract boolean startBackupJob(final String jobId);
    public abstract BackupOffering cloneVeeamJob(final Job parentJob, final String clonedJobName);
    public abstract boolean addVMToVeeamJob(final String jobId, final String jobName, final String parentJobId, final String vmInstanceName,
                                           final String hierarchyRef, final VirtualMachine vm);
    public abstract boolean removeVMFromVeeamJob(final String jobId, final String vmInstanceName,
                                                final String hierarchyRef, final Hypervisor.HypervisorType hypervisorType);
    public abstract boolean deleteJobAndBackup(final String jobName);

    // Backup Management
    public abstract void listAllBackups();
    public abstract boolean deleteBackup(final String restorePointId);
    public abstract boolean syncBackupRepository();
    public abstract Map<String, Backup.Metric> getBackupMetrics();

    // Restore Operations
    public abstract boolean restoreFullVM(final String vmInstanceName, final String restorePointId);
    public abstract Pair<Boolean, String> restoreVMToDifferentLocation(String restorePointId, String restoreLocation,
                                                                       String hostIp, String dataStoreUuid);

    // Restore Points
    public abstract List<Backup.RestorePoint> listRestorePoints(String backupName, String hierarchyRef,
                                                                String vmInternalName, Map<String, Backup.Metric> metricsMap,
                                                                Hypervisor.HypervisorType hypervisorType);

    // Repository Management
    public abstract Ref listBackupRepository(final String backupServerId, final String backupName);
}

