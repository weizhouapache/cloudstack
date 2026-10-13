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
package com.cloud.hypervisor.kvm.resource.wrapper;

import com.cloud.agent.api.Answer;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.hypervisor.kvm.storage.KVMStoragePool;
import com.cloud.hypervisor.kvm.storage.KVMStoragePoolManager;
import com.cloud.hypervisor.kvm.storage.RbdDiffHelper;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.storage.Storage;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.cloudstack.backup.RestoreKbossBackupAnswer;
import org.apache.cloudstack.backup.RestoreKbossBackupCommand;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.cloudstack.storage.to.BackupDeltaTO;
import org.apache.cloudstack.storage.to.PrimaryDataStoreTO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.cloudstack.utils.qemu.QemuImg;
import org.apache.cloudstack.utils.qemu.QemuImgException;
import org.apache.cloudstack.utils.qemu.QemuImgFile;
import org.libvirt.LibvirtException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@ResourceWrapper(handles = RestoreKbossBackupCommand.class)
public class LibvirtRestoreKbossBackupCommandWrapper extends CommandWrapper<RestoreKbossBackupCommand, Answer, LibvirtComputingResource> {
    @Override
    public Answer execute(RestoreKbossBackupCommand cmd, LibvirtComputingResource resource) {
        Set<Pair<BackupDeltaTO, VolumeObjectTO>> backupToAndVolumeObjectPairs = cmd.getBackupAndVolumePairs();
        Set<BackupDeltaTO> deltasToRemove = cmd.getDeltasToRemove();
        Set<String> secondaryStorageUrls = cmd.getSecondaryStorageUrls();

        KVMStoragePoolManager storagePoolManager = resource.getStoragePoolMgr();

        Set<String> secondaryStorageUuids = new HashSet<>();
        try {
            KVMStoragePool secondaryStorage = mountSecondaryStorages(secondaryStorageUrls, backupToAndVolumeObjectPairs.stream().findFirst().get().first().getDataStore().getUrl(),
                    storagePoolManager, secondaryStorageUuids);

            restoreVolumes(backupToAndVolumeObjectPairs, secondaryStorage, storagePoolManager, cmd.isQuickRestore(), cmd.getWait() * 1000,
                    cmd.getRbdDiffChains());

            deleteDeltas(deltasToRemove, storagePoolManager);
        } catch (LibvirtException | QemuImgException | IOException e) {
            return new RestoreKbossBackupAnswer(cmd, e, secondaryStorageUuids);
        } finally {
            if (!cmd.isQuickRestore()) {
                for (String uuid : secondaryStorageUuids) {
                    storagePoolManager.deleteStoragePool(Storage.StoragePoolType.NetworkFilesystem, uuid);
                }
            }
        }
        return new RestoreKbossBackupAnswer(cmd, secondaryStorageUuids);
    }

    protected void restoreVolumes(Set<Pair<BackupDeltaTO, VolumeObjectTO>> backupToAndVolumeObjectPairs, KVMStoragePool secondaryStorage, KVMStoragePoolManager storagePoolManager,
            boolean quickRestore, int timeoutInMillis, Map<String, List<BackupDeltaTO>> rbdDiffChains) throws LibvirtException, QemuImgException {
        for (Pair<BackupDeltaTO, VolumeObjectTO> backupToVolumeToPair : backupToAndVolumeObjectPairs) {
            String fullBackupPath = secondaryStorage.getLocalPathFor(backupToVolumeToPair.first().getPath());

            VolumeObjectTO volumeObjectTO = backupToVolumeToPair.second();
            PrimaryDataStoreTO primaryDataStoreTO = (PrimaryDataStoreTO) volumeObjectTO.getDataStore();
            KVMStoragePool primaryStoragePool = storagePoolManager.getStoragePool(primaryDataStoreTO.getPoolType(), primaryDataStoreTO.getUuid());

            List<BackupDeltaTO> rbdChain = rbdDiffChains == null ? null : rbdDiffChains.get(volumeObjectTO.getUuid());
            if (CollectionUtils.isNotEmpty(rbdChain)) {
                restoreRbdVolume(volumeObjectTO, primaryStoragePool, secondaryStorage, storagePoolManager, rbdChain);
                continue;
            }

            String fullVolumePath = primaryStoragePool.getLocalPathFor(volumeObjectTO.getPath());

            QemuImgFile backup = new QemuImgFile(fullBackupPath, QemuImg.PhysicalDiskFormat.QCOW2);
            QemuImgFile volume = new QemuImgFile(fullVolumePath, QemuImg.PhysicalDiskFormat.QCOW2);

            QemuImg qemuImg = getQemuImg(timeoutInMillis);

            if (quickRestore) {
                logger.info("Creating delta over old volume [{}] at [{}] with backing store stored at [{}].", volumeObjectTO.getUuid(), fullVolumePath, fullBackupPath);
                qemuImg.create(volume, backup);
            } else {
                logger.info("Restoring volume [{}] at [{}] with backup stored at [{}].", volumeObjectTO.getUuid(), fullVolumePath, fullBackupPath);
                qemuImg.convert(backup, volume);
            }
        }
    }

    /**
     * Rebuild a Ceph volume by replaying its whole diff chain with {@code rbd import-diff}.
     *
     * <p>There is no quick-restore equivalent here: a qcow2 quick restore leaves the backup as the
     * backing file of a fresh delta, which a raw RBD image cannot reference. The chain is always
     * replayed in full instead.</p>
     */
    protected void restoreRbdVolume(VolumeObjectTO volumeObjectTO, KVMStoragePool primaryStoragePool, KVMStoragePool secondaryStorage,
            KVMStoragePoolManager storagePoolManager, List<BackupDeltaTO> rbdChain) {
        String image = RbdDiffHelper.qualifiedImage(primaryStoragePool,
                volumeObjectTO.getPath() != null ? volumeObjectTO.getPath() : volumeObjectTO.getUuid());
        // A chain can span secondary stores, so resolve every diff against the store it was
        // written to rather than against the one holding the backup being restored. They are all
        // mounted already.
        List<String> diffFiles = rbdChain.stream()
                .map(delta -> poolForDelta(delta, secondaryStorage, storagePoolManager).getLocalPathFor(delta.getPath()))
                .collect(Collectors.toList());

        try (RbdDiffHelper rbd = RbdDiffHelper.forPool(primaryStoragePool)) {
            // Prefer the image's own size: it is what the Instance's disk was sized to. Only a
            // restore into a brand new volume has no image to read it from.
            Long size = rbd.imageSize(image);
            if (size == null) {
                size = volumeObjectTO.getSize();
            }
            if (size == null || size <= 0) {
                throw new CloudRuntimeException(String.format("Unable to determine the size to recreate RBD image %s with.", image));
            }

            logger.info("Restoring volume [{}] by replaying {} RBD diff(s) into image [{}].", volumeObjectTO.getUuid(), diffFiles.size(), image);
            if (!rbd.importDiffChain(image, size, RbdDiffHelper.dataPoolOf(primaryStoragePool), diffFiles)) {
                throw new CloudRuntimeException(String.format("Failed to restore RBD image %s from its backup diff chain.", image));
            }
        } catch (IOException ex) {
            throw new CloudRuntimeException(String.format("Could not prepare the Ceph credentials needed to restore volume %s.", volumeObjectTO.getUuid()), ex);
        }
    }

    protected KVMStoragePool poolForDelta(BackupDeltaTO delta, KVMStoragePool fallback, KVMStoragePoolManager storagePoolManager) {
        if (delta.getDataStore() == null || delta.getDataStore().getUrl() == null) {
            return fallback;
        }
        return storagePoolManager.getStoragePoolByURI(delta.getDataStore().getUrl());
    }

    protected QemuImg getQemuImg(int timeoutInMillis) throws LibvirtException, QemuImgException {
        return new QemuImg(timeoutInMillis);
    }

    protected void deleteDeltas(Set<BackupDeltaTO> deltasToRemove, KVMStoragePoolManager storagePoolManager) throws IOException {
        for (BackupDeltaTO deltaToRemove : deltasToRemove) {
            PrimaryDataStoreTO primaryDataStoreTO = (PrimaryDataStoreTO) deltaToRemove.getDataStore();
            KVMStoragePool primaryStoragePool = storagePoolManager.getStoragePool(primaryDataStoreTO.getPoolType(), primaryDataStoreTO.getUuid());
            String fullDeltaPath = primaryStoragePool.getLocalPathFor(deltaToRemove.getPath());
            logger.debug("Deleting leftover delta [{}].", fullDeltaPath);
            Files.deleteIfExists(Path.of(fullDeltaPath));
        }
    }

    protected KVMStoragePool mountSecondaryStorages(Set<String> parentSecondaryStorageUrls, String secondaryStorageUrl, KVMStoragePoolManager storagePoolManager, Set<String> secondaryStorageUuids) {
        for (String url : parentSecondaryStorageUrls) {
            KVMStoragePool pool = storagePoolManager.getStoragePoolByURI(url);
            secondaryStorageUuids.add(pool.getUuid());
        }
        KVMStoragePool pool = storagePoolManager.getStoragePoolByURI(secondaryStorageUrl);
        secondaryStorageUuids.add(pool.getUuid());
        return pool;
    }
}
