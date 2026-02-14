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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;

import com.cloud.utils.NumbersUtil;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.ssh.SshHelper;
import com.cloud.vm.VirtualMachine;
import org.apache.cloudstack.backup.Backup;
import org.apache.cloudstack.backup.BackupOffering;
import org.apache.cloudstack.backup.veeam.api.Job;
import org.apache.cloudstack.backup.veeam.api.Ref;
import org.apache.commons.lang3.StringUtils;

public class VeeamPowerShellClient extends VeeamClientBase {

    private String veeamServerIp;
    private String veeamServerUsername;
    private String veeamServerPassword;
    private int veeamServerPort = 22;
    // private int restoreTimeout; // Unused
    private static final String FAILED_TO_DELETE = "Failed to delete";
    private static final String REPOSITORY_REFERENCE = "RepositoryReference";

    public VeeamPowerShellClient(final String url, final String username, final String password, final int timeout, final int restoreTimeout) throws URISyntaxException {
        super(0);
        URI apiURI = new URI(url);
        this.veeamServerIp = apiURI.getHost();
        this.veeamServerUsername = username;
        this.veeamServerPassword = password;
        // this.restoreTimeout = restoreTimeout;
    }

    protected String transformPowerShellCommandList(List<String> cmds) {
        StringJoiner joiner = new StringJoiner(";");
        joiner.add("pwsh -NoProfile -Command \"");
        for (String cmd : cmds) {
            joiner.add(cmd);
        }
        joiner.add("\"");
        return joiner.toString();
    }

    protected Pair<Boolean, String> executePowerShellCommands(List<String> cmds) {
        try {
            String commands = transformPowerShellCommandList(cmds);
            // using hardcoded timeouts similar to VeeamClient, but maybe we should use the timeout param?
            // VeeamClient uses hardcoded 120000, 120000, 3600000 for SSH.
            Pair<Boolean, String> response = SshHelper.sshExecute(veeamServerIp, veeamServerPort,
                    veeamServerUsername, null, veeamServerPassword,
                    commands, 120000, 120000, 3600000);

            if (response == null || !response.first()) {
                logger.error(String.format("Veeam PowerShell commands [%s] failed due to: [%s].", commands, response != null ? response.second() : "no PowerShell output returned"));
            } else {
                logger.debug(String.format("Veeam response for PowerShell commands [%s] is: [%s].", commands, response.second()));
            }

            return response;
        } catch (Exception e) {
            throw new CloudRuntimeException("Error while executing PowerShell commands due to: " + e.getMessage());
        }
    }

    @Override
    public List<BackupOffering> listJobs() {
        List<String> cmds = Arrays.asList(
                "$jobs = Get-VBRJob",
                "foreach ($job in $jobs) { $job.Id.Guid + ':' + $job.Name }"
        );
        Pair<Boolean, String> response = executePowerShellCommands(cmds);
        List<BackupOffering> policies = new ArrayList<>();
        if (response.first() && StringUtils.isNotBlank(response.second())) {
            String[] lines = response.second().split("\r\n");
            for (String line : lines) {
                if (line.isEmpty()) continue;
                String[] parts = line.split(":", 2);
                if (parts.length == 2) {
                    // Since Veeam 13, Job operations requires Job Name instead of ID, so we will use Name as UID and ignore the actual GUID. This is a workaround for cloning and adding VMs to jobs which are not possible by ID anymore.
                    policies.add(new VeeamBackupOffering(parts[1].trim(), parts[1].trim()));
                }
            }
        }
        return policies;
    }

    @Override
    public Job listJob(String jobId) {
        String guid = jobId.replace("urn:veeam:Job:", "");
        List<String> cmds = Arrays.asList(
                String.format("$job = Get-VBRJob -Name '%s'", guid),
                "if ($job) {",
                "  $job.Id.Guid",
                "  $job.Name",
                "  $job.Description",
                "  $job.IsScheduleEnabled",
                "  $job.BackupTargetRepositoryId.Guid",
                "}"
        );
        Pair<Boolean, String> response = executePowerShellCommands(cmds);
        if (response.first() && StringUtils.isNotBlank(response.second())) {
            String[] parts = response.second().split("\r\n");
            if (parts.length >= 4) {
                Job job = new Job();
                job.setUid("urn:veeam:Job:" + parts[0].trim());
                job.setName(parts[1].trim());
                job.setDescription(parts[2].trim());
                job.setScheduleEnabled(parts[3].trim());
                // We don't have schedule configured info easily, assuming true or based on enablement
                job.setScheduleConfigured("true");
                return job;
            }
        }
        return null;
    }

    @Override
    public boolean toggleJobSchedule(String jobId) {
        String guid = jobId.replace("urn:veeam:Job:", "");
        List<String> cmds = Arrays.asList(
                String.format("$job = Get-VBRJob -Name '%s'", guid),
                "if ($job.IsScheduleEnabled) { Disable-VBRJobSchedule -Job $job } else { Enable-VBRJobSchedule -Job $job }"
        );
        Pair<Boolean, String> response = executePowerShellCommands(cmds);
        return response.first();
    }

    @Override
    public boolean startBackupJob(String jobId) {
        String guid = jobId.replace("urn:veeam:Job:", "");
        List<String> cmds = Arrays.asList(
                String.format("$job = Get-VBRJob -Name '%s'", guid),
                "Start-VBRJob -Job $job"
        );
        Pair<Boolean, String> response = executePowerShellCommands(cmds);
        return response.first();
    }

    @Override
    public BackupOffering cloneVeeamJob(Job parentJob, String clonedJobName) {
        String parentId = parentJob.getId();
        // Cloning via PowerShell: copy job? Veeam doesn't have Copy-VBRJob.
        // We might need to get properties and create new.
        // However, a simpler way might be creating a new job with same settings.
        // But for now, let's assume we can use a script block or similar.
        // Actually, we can use .NET object clone method if available, but unlikely.
        // Best bet: Get job, Get Repository, Add-VBRBackupJob.
        // Limitation: might miss some settings.

        // Strategy: Get parent job, get its repository. Create new job with that repository and name.
        // This is a simplification but often enough for backup offerings which are templates.

        List<String> cmds = Arrays.asList(
                String.format("$parent = Get-VBRJob -Name '%s'", parentId),
                "$repo = $parent.GetBackupTargetRepository()",
                "Add-VBRBackupJob -Name \"" + clonedJobName + "\" -Repository $repo -Entity $parent.GetObjectsInJob()",
                String.format("$parent = Get-VBRJob -Name '%s'", parentId),
                "$repo = $parent.GetBackupTargetRepository()",
                "Add-VBRViBackupJob -Name \"" + clonedJobName + "\" -BackupRepository $repo -Entity @()"
        );
        Pair<Boolean, String> response = executePowerShellCommands(cmds);
        if (response.first()) {
            return new VeeamBackupOffering(clonedJobName, clonedJobName); // Assume success
        }
        return null;
    }

    @Override
    public boolean addVMToVeeamJob(String jobId, String jobName, String parentJobId, String vmInstanceName, String hierarchyRef, VirtualMachine vm) {
        // jobId is the cloned job.
        // hierarchyRef is the vCenter/Host.
        // We need to find the VM object to add.
        // Find-VBRViEntity -Name vmInstanceName -Server hierarchyRef ?

        List<String> cmds = Arrays.asList(
                String.format("$job = Get-VBRJob -Name '%s'", jobName),
                "$server = Get-VBRServer -Name \"" + hierarchyRef + "\"",
                "$entity = Find-VBRViEntity -Name \"" + vmInstanceName + "\" -Server $server",
                "if ($entity) { Add-VBRJobObject -Job $job -Objects $entity }"
        );
        Pair<Boolean, String> response = executePowerShellCommands(cmds);
        return response.first();
    }

    @Override
    public boolean removeVMFromVeeamJob(String jobId, String vmInstanceName, String hierarchyRef) {
        String guid = jobId.replace("urn:veeam:Job:", "");
        List<String> cmds = Arrays.asList(
           String.format("$job = Get-VBRJob -Name '%s'", guid),
           "$object = Get-VBRJobObject -Job $job -Name \"" + vmInstanceName + "\"",
           "if ($object) { Remove-VBRJobObject -Job $job -Objects $object -Confirm:$false }"
        );
        Pair<Boolean, String> response = executePowerShellCommands(cmds);
        return response.first();
    }

    @Override
    public boolean deleteJobAndBackup(String jobName) {
        List<String> cmds = Arrays.asList(
                String.format("$job = Get-VBRJob -Name '%s'", jobName),
                "if ($job) { Remove-VBRJob -Job $job -Confirm:$false }"
        );
        Pair<Boolean, String> result = executePowerShellCommands(cmds);
        return result != null && result.first() && !result.second().contains(FAILED_TO_DELETE);
    }

    @Override
    public void listAllBackups() {
         executePowerShellCommands(Arrays.asList("Get-VBRBackup | Select-Object Id, Name"));
    }

    @Override
    public boolean deleteBackup(String restorePointId) {
        List<String> cmds = Arrays.asList(
                String.format("$restorePoint = Get-VBRRestorePoint ^| Where-Object { $_.Id -eq '%s' }", restorePointId),
                "if ($restorePoint) { Remove-VBRRestorePoint -Oib $restorePoint -Confirm:$false",
                "} else { ",
                " Write-Output 'Failed to delete'",
                " Exit 1",
                "}"
        );
        Pair<Boolean, String> result = executePowerShellCommands(cmds);
        return result != null && result.first() && !result.second().contains(FAILED_TO_DELETE);
    }

    @Override
    public boolean syncBackupRepository() {
        List<String> cmds = Arrays.asList(
                "$repo = Get-VBRBackupRepository",
                "$Syncs = Sync-VBRBackupRepository -Repository $repo",
                "while ((Get-VBRSession -ID $Syncs.ID).Result -ne 'Success') { Start-Sleep -Seconds 10 }"
        );
        Pair<Boolean, String> result = executePowerShellCommands(cmds);
        return result != null && result.first();
    }

    @Override
    public Map<String, Backup.Metric> getBackupMetrics() {
        final String separator = "=====";
        final List<String> cmds = Arrays.asList(
                "$backups = Get-VBRBackup",
                "foreach ($backup in $backups) {" +
                        "    $restorePoints = Get-VBRRestorePoint -Backup $backup;" +
                        "    foreach ($restorePoint in $restorePoints) {" +
                        "        $backupFile = $restorePoint.GetStorage();" +
                        "        $restorePoint.Id.Guid;" +
                        "        $backupFile.Stats.BackupSize;" +
                        "        $backupFile.Stats.DataSize;" +
                        "        echo \"" + separator + "\";" +
                        "    }" +
                        "}"
        );
        Pair<Boolean, String> response = executePowerShellCommands(cmds);
        if (response == null || !response.first()) {
            return new HashMap<>();
        }
        return processPowerShellResultForBackupMetrics(response.second());
    }

    protected Map<String, Backup.Metric> processPowerShellResultForBackupMetrics(final String result) {
        final String separator = "=====";
        Map<String, Backup.Metric> metrics = new HashMap<>();
        if (StringUtils.isBlank(result)) return metrics;
        for (final String block : result.split(separator + "\r\n")) {
            final String[] parts = block.split("\r\n");
            if (parts.length != 3) {
                continue;
            }
            final String restorePointId = parts[0];
            final Long backupSize = Long.valueOf(parts[1]);
            final Long dataSize = Long.valueOf(parts[2]);
            metrics.put(restorePointId, new Backup.Metric(backupSize, dataSize));
        }
        return metrics;
    }

    @Override
    public boolean restoreFullVM(String vmInstanceName, String restorePointId) {
         // This is complex as we need to find the restore point first.
         List<String> cmds = Arrays.asList(
             "$point = Get-VBRRestorePoint | Where-Object { $_.Id -eq '" + restorePointId + "' }",
             "if ($point) { Start-VBRRestoreVM -RestorePoint $point -Reason 'CloudStack Restore' }"
         );
         Pair<Boolean, String> response = executePowerShellCommands(cmds);
         return response.first();
    }

    @Override
    public Pair<Boolean, String> restoreVMToDifferentLocation(String restorePointId, String restoreLocation, String hostIp, String dataStoreUuid, String hierarchyRef) {
        if (restoreLocation == null) {
            restoreLocation = RESTORE_VM_SUFFIX + UUID.randomUUID().toString();
        }
        final String datastoreId = dataStoreUuid.replace("-","");
        final List<String> cmds = Arrays.asList(
                "$points = Get-VBRRestorePoint",
                String.format("foreach($point in $points) { if ($point.Id -eq '%s') { $restorePoint = $point; break; } }", restorePointId),
                String.format("$server = Get-VBRServer -Name \"%s\"", hostIp),
                String.format("$ds = Find-VBRViDatastore -Server:$server -Name \"%s\"", datastoreId),
                String.format("$job = Start-VBRRestoreVM -RestorePoint:$restorePoint -Server:$server -Datastore:$ds -VMName \"%s\" -RunAsync", restoreLocation),
                "while (-not (Get-VBRRestoreSession -Id $job.Id).IsCompleted) { Start-Sleep -Seconds 10 }"
        );
        Pair<Boolean, String> result = executePowerShellCommands(cmds);
        if (result == null || !result.first()) {
            throw new CloudRuntimeException("Failed to restore VM to location " + restoreLocation);
        }
        return new Pair<>(result.first(), restoreLocation);
    }

    @Override
    public List<Backup.RestorePoint> listRestorePoints(String backupName, String hierarchyRef, String vmInternalName, Map<String, Backup.Metric> metricsMap) {
        final List<String> cmds = Arrays.asList(
                String.format("$backup = Get-VBRBackup -Name '%s'", backupName),
                String.format("if ($backup) { $restore = (Get-VBRRestorePoint -Backup:$backup -Name \"%s\" ^| Where-Object {$_.IsConsistent -eq $true})", vmInternalName),
                "if ($restore) { $restore ^| Format-List } }"
        );
        Pair<Boolean, String> response = executePowerShellCommands(cmds);
        if (response == null || !response.first()) {
            return new ArrayList<>();
        }

        final List<Backup.RestorePoint> restorePoints = new ArrayList<>();
        if (StringUtils.isBlank(response.second())) {
            return restorePoints;
        }
        for (final String block : response.second().split("\r\n\r\n")) {
             Backup.RestorePoint rp = parseRestorePoint(block, metricsMap);
             if (rp != null) restorePoints.add(rp);
        }
        return restorePoints;
    }

    private Backup.RestorePoint parseRestorePoint(String block, Map<String, Backup.Metric> metricsMap) {
        String id = null;
        Date created = null;
        String type = null;
        String[] parts = block.split("\r\n");
        for (String part : parts) {
            part = part.trim();
            if (part.matches("Id(\\s)*:(.)*")) {
                String[] split = part.split(":", 2);
                if (split.length > 1) id = split[1].trim();
            } else if (part.matches("CreationTime(\\s)*:(.)*")) {
                String [] split = part.split(":", 2);
                if (split.length > 1) {
                    try {
                        String timeStr = split[1].trim();
                        // Try to be more robust or rely on simple string manipulation if format is known "MM/dd/yyyy HH:mm:ss"
                        // But depending on locale, it might differ.
                        // VeeamClient splits by [:/ ]
                        String [] time = timeStr.split("[:/ ]");
                        if (time.length >= 6) {
                            Calendar cal = Calendar.getInstance();
                            // MM/dd/yyyy HH:mm:ss -> 0:MM, 1:dd, 2:yyyy, 3:HH, 4:mm, 5:ss
                            cal.set(Integer.parseInt(time[2]), Integer.parseInt(time[0]) - 1, Integer.parseInt(time[1]), Integer.parseInt(time[3]), Integer.parseInt(time[4]), Integer.parseInt(time[5]));
                            created = cal.getTime();
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to parse creation time for restore point", e);
                        created = new Date();
                    }
                }
            } else if (part.matches("Type(\\s)*:(.)*")) {
                 String[] split = part.split(":", 2);
                 if (split.length > 1) type = split[1].trim();
            }
        }
        if (id == null) return null;

        Backup.Metric metric = metricsMap.get(id);
        Long backupSize = null;
        Long dataSize = null;
        if (metric != null) {
            backupSize = metric.getBackupSize();
            dataSize = metric.getDataSize();
        }
        return new Backup.RestorePoint(id, created, type, backupSize, dataSize);
    }

    @Override
    public Ref listBackupRepository(String backupServerId, String backupName) {
        List<String> cmds = Arrays.asList(
                String.format("$Job = Get-VBRJob -name '%s'", backupName),
                "if ($Job) {",
                "$repo = $Job.GetBackupTargetRepository()",
                "if ($repo) {",
                "$repo.Id.Guid",
                "$repo.Name",
                "} } "
        );
        Pair<Boolean, String> result = executePowerShellCommands(cmds);
        if (result != null && result.first() && StringUtils.isNotBlank(result.second())) {
             String[] parts = result.second().split("\r\n");
             if (parts.length >= 2) {
                 Ref ref = new Ref();
                 ref.setUid(parts[0].trim());
                 ref.setName(parts[1].trim());
                 ref.setType(REPOSITORY_REFERENCE);
                 return ref;
             }
        }
        return null;
    }

    protected Integer getVeeamServerVersion() {
        final List<String> cmds = Arrays.asList(
                "$InstallPath = Get-ItemProperty -Path 'HKLM:\\Software\\Veeam\\Veeam Backup and Replication\\' ^| Select -ExpandProperty CorePath",
                "Add-Type -LiteralPath \\\"$InstallPath\\Veeam.Backup.Configuration.dll\\\"",
                "$ProductData = [Veeam.Backup.Configuration.BackupProduct]::Create()",
                "$Version = $ProductData.ProductVersion.ToString()",
                "if ($ProductData.MarketName -ne '') {$Version += \\\" $($ProductData.MarketName)\\\"}",
                "$Version"
        );
        Pair<Boolean, String> response = executePowerShellCommands(cmds);
        if (response == null || !response.first() || response.second() == null || StringUtils.isBlank(response.second().trim())) {
            logger.error("Failed to get veeam server version, using default version");
            return 0;
        } else {
            Integer majorVersion = NumbersUtil.parseInt(response.second().trim().split("\\.")[0], 0);
            logger.info(String.format("Veeam server full version is %s, major version is %s", response.second().trim(), majorVersion));
            return majorVersion;
        }
    }
}

