#!/usr/bin/env python
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

from marvin.cloudstackTestCase import cloudstackTestCase
from marvin.lib import utils
from marvin.lib.base import (Account, ServiceOffering, DiskOffering, VirtualMachine, BackupOffering,
                             Backup, Configurations, Volume, StoragePool)
from marvin.lib.common import (get_domain, get_zone, get_template)
from nose.plugins.attrib import attr
from marvin.codes import FAILED
import time
import tempfile
import urllib.parse
import urllib.request
import os

SUPPORTED_PRIMARY_STORAGE_POOL_TYPES = ['networkfilesystem', 'rbd']

class TestKBOSSBackupAndRecovery(cloudstackTestCase):

    @classmethod
    def setUpClass(cls):
        # Setup

        cls.testClient = super(TestKBOSSBackupAndRecovery, cls).getClsTestClient()
        cls.api_client = cls.testClient.getApiClient()
        print(cls.api_client)
        cls.services = cls.testClient.getParsedTestDataConfig()
        cls.zone = get_zone(cls.api_client, cls.testClient.getZoneForTests())
        cls.services["mode"] = cls.zone.networktype
        cls.hypervisor = cls.testClient.getHypervisorInfo()
        cls.domain = get_domain(cls.api_client)
        cls.template = get_template(cls.api_client, cls.zone.id, cls.services["ostype"])
        if cls.template == FAILED:
            assert False, "get_template() failed to return template with description %s" % cls.services["ostype"]
        cls.services["small"]["zoneid"] = cls.zone.id
        cls.services["small"]["template"] = cls.template.id
        cls._cleanup = []

        if cls.hypervisor.lower() != 'kvm':
            cls.skipTest(cls, reason="Test can be run only on KVM hypervisor")

        storage_pools = StoragePool.list(cls.api_client)
        usable_pools = [p for p in storage_pools if getattr(p, 'state', 'Up') == 'Up']
        if not usable_pools:
            cls.skipTest(cls, reason="Test can be run only if an enabled primary storage pool exists")
        cls.storage_pool = usable_pools[0]
        if cls.storage_pool.type.lower() not in SUPPORTED_PRIMARY_STORAGE_POOL_TYPES:
            cls.skipTest(cls, reason="Test can be run only if the primary storage is of type NFS or RBD (Ceph)")

        # Check backup configuration values, set them to enable the kboss provider
        backup_enabled_cfg = Configurations.list(cls.api_client, name='backup.framework.enabled')
        backup_provider_cfg = Configurations.list(cls.api_client, name='backup.framework.provider.plugin')
        cls.backup_enabled = backup_enabled_cfg[0].value
        cls.backup_provider = backup_provider_cfg[0].value

        if cls.backup_enabled == "false":
            cls.skipTest(cls, reason="Test can be run only if the config backup.framework.enabled is true")
        if cls.backup_provider != "kboss":
            Configurations.update(cls.api_client, 'backup.framework.provider.plugin', value='kboss')

        cls.account = Account.create(cls.api_client, cls.services["account"], domainid=cls.domain.id)

        cls._cleanup = [cls.account]

        cls.basic_backup_offering = BackupOffering.createOffering(cls.api_client, utils.random_gen(), utils.random_gen(), cls.zone.id)
        cls._cleanup.append(cls.basic_backup_offering)
        cls.compress_backup_offering = BackupOffering.createOffering(cls.api_client, utils.random_gen(), utils.random_gen(), cls.zone.id, compress=True)
        cls._cleanup.append(cls.compress_backup_offering)
        cls.validate_backup_offering = BackupOffering.createOffering(cls.api_client, utils.random_gen(), utils.random_gen(), cls.zone.id, validate=True,
                                                                     validationsteps="screenshot")
        cls._cleanup.append(cls.validate_backup_offering)

        cls.offering = ServiceOffering.create(cls.api_client,cls.services["service_offerings"]["small"])
        cls.diskoffering = DiskOffering.create(cls.api_client, cls.services["disk_offering"])
        cls._cleanup.extend([cls.offering, cls.diskoffering])
        cls.vm = VirtualMachine.create(cls.api_client, cls.services["small"], accountid=cls.account.name,
                                       domainid=cls.account.domainid, serviceofferingid=cls.offering.id,
                                       diskofferingid=cls.diskoffering.id, mode=cls.services["mode"])


    @classmethod
    def tearDownClass(cls):
        try:
            # Cleanup resources used
            utils.cleanup_resources(cls.api_client, cls._cleanup)

            if cls.backup_provider != "kboss":
                Configurations.update(cls.api_client, 'backup.framework.provider.plugin', value=cls.backup_provider)
        except Exception as e:
            raise Exception("Warning: Exception during cleanup : %s" % e)

    def setUp(self):
        if self.hypervisor.lower() != 'kvm':
            raise self.skipTest("Skipping test cases which must only run for Simulator")
        self.cleanup = []

    def tearDown(self):
        try:
            utils.cleanup_resources(self.api_client, self.cleanup)
        except Exception as e:
            raise Exception("Warning: Exception during cleanup : %s" % e)

    def waitForCompression(self, vm):
        def checkBackupCompression():
            backups = Backup.list(self.api_client, vm.id)
            if isinstance(backups, list) and len(backups) != 0 and backups[0].compressionstatus == "Compressed":
                return True, None
            return False, None

        res, _ = utils.wait_until(10, 60, checkBackupCompression)
        if not res:
            self.fail("Failed to wait for backup compression of VM %s" % vm.id)

    def waitForValidation(self, vm):
        def checkBackupValidation():
            backups = Backup.list(self.api_client, vm.id)
            if isinstance(backups, list) and len(backups) != 0 and backups[0].validationstatus == "Valid":
                return True, None
            return False, None

        res, _ = utils.wait_until(10, 60, checkBackupValidation)
        if not res:
            self.fail("Failed to wait for backup compression of VM %s" % vm.id)

    def download_screenshot(self, backupid):
        extract_ss = Backup.downloadValidationScreenshot(self.api_client, backupid)

        try:
            formatted_url = urllib.parse.unquote_plus(extract_ss.url)
            self.debug("Attempting to download screenshot at url %s" % formatted_url)
            response = urllib.request.urlopen(formatted_url)
            self.debug("response from screenshot url %s" % response.getcode())
            fd, path = tempfile.mkstemp()
            self.debug("Saving screenshot %s to path %s" % (backupid, path))
            os.close(fd)
            with open(path, 'wb') as fd:
                fd.write(response.read())
            self.debug("Saved screenshot successfully")
        except Exception:
            self.fail(
                "Extract screenshot of backup Failed with invalid URL %s (backup id: %s)" \
                % (extract_ss.url, backupid)
            )

    @attr(tags=["advanced", "backup"], required_hardware="true")
    def test_vm_backup_lifecycle(self):
        """
        Test VM backup lifecycle
        """

        # Verify there are no backups for the VM
        backups = Backup.list(self.api_client, self.vm.id)
        self.assertEqual(backups, None, "There should not exist any backup for the VM")

        # Assign VM to offering and create ad-hoc backup
        self.basic_backup_offering.assignOffering(self.api_client, self.vm.id)
        Backup.create(self.api_client, self.vm.id)

        # Verify backup is created for the VM
        backups = Backup.list(self.api_client, self.vm.id)
        self.assertEqual(len(backups), 1, "There should exist only one backup for the VM")
        backup = backups[0]

        # Delete backup
        Backup.delete(self.api_client, backup.id)

        # Verify backup is deleted
        backups = Backup.list(self.api_client, self.vm.id)
        self.assertEqual(backups, None, "There should not exist any backup for the VM")

        # Remove VM from offering
        self.basic_backup_offering.removeOffering(self.api_client, self.vm.id)

    def _is_rbd_storage(self):
        """
        Whether this environment's primary storage is Ceph/RBD, where KbossBackupProvider backs a
        volume up as an `rbd export-diff` stream instead of a qcow2 delta.
        """
        return self.storage_pool.type.lower() == 'rbd'

    @attr(tags=["advanced", "backup"], required_hardware="true")
    def test_vm_backup_lifecycle_with_compression(self):
        """
        Test VM backup lifecycle with compression
        """

        if self._is_rbd_storage():
            self.skipTest("Compression operates on a qcow2 image on secondary storage; an rbd "
                          "export-diff stream is not one, so the provider refuses such an offering "
                          "for Ceph Instances.")
        compression_enabled = Configurations.list(self.api_client, name='backup.compression.task.enabled')
        if compression_enabled[0].value == "false":
            self.skipTest("Skipping test due to backup compression task is disabled.")

        # Verify there are no backups for the VM
        backups = Backup.list(self.api_client, self.vm.id)
        self.assertEqual(backups, None, "There should not exist any backup for the VM")

        # Assign VM to offering and create ad-hoc backup
        self.compress_backup_offering.assignOffering(self.api_client, self.vm.id)
        Backup.create(self.api_client, self.vm.id)

        # Verify backup is created for the VM
        backups = Backup.list(self.api_client, self.vm.id)
        self.assertEqual(len(backups), 1, "There should exist only one backup for the VM")
        backup = backups[0]

        self.waitForCompression(self.vm)

        # Delete backup
        Backup.delete(self.api_client, backup.id)

        # Verify backup is deleted
        backups = Backup.list(self.api_client, self.vm.id)
        self.assertEqual(backups, None, "There should not exist any backup for the VM")

        # Remove VM from offering
        self.compress_backup_offering.removeOffering(self.api_client, self.vm.id)

    @attr(tags=["advanced", "backup"], required_hardware="true")
    def test_vm_backup_lifecycle_with_validation(self):
        """
        Test VM backup lifecycle with validation
        """

        if self._is_rbd_storage():
            self.skipTest("Backup validation boots the backed up image; an rbd export-diff stream "
                          "is not a bootable image, so the provider refuses such an offering for "
                          "Ceph Instances.")
        validation_enabled = Configurations.list(self.api_client, name='backup.validation.task.enabled')
        if validation_enabled[0].value == "false":
            self.skipTest("Skipping test due to backup compression task is disabled.")

        # Verify there are no backups for the VM
        backups = Backup.list(self.api_client, self.vm.id)
        self.assertEqual(backups, None, "There should not exist any backup for the VM")

        # Assign VM to offering and create ad-hoc backup
        self.validate_backup_offering.assignOffering(self.api_client, self.vm.id)
        Backup.create(self.api_client, self.vm.id)

        # Verify backup is created for the VM
        backups = Backup.list(self.api_client, self.vm.id)
        self.assertEqual(len(backups), 1, "There should exist only one backup for the VM")
        backup = backups[0]

        # Verify validation is performed
        self.waitForValidation(self.vm)
        self.download_screenshot(backup.id)

        # Delete backup
        Backup.delete(self.api_client, backup.id)

        # Verify backup is deleted
        backups = Backup.list(self.api_client, self.vm.id)
        self.assertEqual(backups, None, "There should not exist any backup for the VM")

        # Remove VM from offering
        self.validate_backup_offering.removeOffering(self.api_client, self.vm.id)

    @attr(tags=["advanced", "backup"], required_hardware="true")
    def test_vm_backup_create_vm_from_backup(self):
        """
        Test creating a new VM from a backup
        """
        self.basic_backup_offering.assignOffering(self.api_client, self.vm.id)

        # Create a file and take backup
        try:
            ssh_client_vm = self.vm.get_ssh_client(reconnect=True)
            ssh_client_vm.execute("touch test_backup_and_recovery.txt")
        except Exception as err:
            self.fail("SSH failed for Virtual machine: %s due to %s" % (self.vm.ipaddress, err))

        time.sleep(5)

        Backup.create(self.api_client, self.vm.id, "backup1")
        Backup.create(self.api_client, self.vm.id, "backup2")

        # Verify backup is created for the VM
        backups = Backup.list(self.api_client, self.vm.id)
        self.assertEqual(len(backups), 2, "There should exist two backups for the VM")

        # Remove VM from offering
        self.basic_backup_offering.removeOffering(self.api_client, self.vm.id)

        # Verify no. of backups after removing the backup offering
        backups = Backup.list(self.api_client, self.vm.id)
        self.assertEqual(len(backups), 2, "There should exist two backups for the VM")

        # Create a new VM from first backup
        new_vm_name = "vm-from-backup1-" + str(int(time.time()))
        new_vm = Backup.createVMFromBackup(
            self.api_client,
            self.services["small"],
            mode=self.services["mode"],
            backupid=backups[0].id,
            vmname=new_vm_name,
            accountname=self.account.name,
            domainid=self.account.domainid,
            zoneid=self.zone.id,
            networkids=None,
            templateid=None
        )
        self.cleanup.append(new_vm)

        # Verify the new VM was created successfully
        self.assertIsNotNone(new_vm, "Failed to create VM from backup")
        self.assertEqual(new_vm.name, new_vm_name, "VM name does not match the requested name")

        # Verify the new VM is running
        self.assertEqual(new_vm.state, "Running", "New VM should be in Running state")

        # Verify the new VM has the correct service offering
        self.assertEqual(new_vm.serviceofferingid, self.offering.id,
                         "New VM should have the correct service offering")

        # Verify the new VM has the correct zone
        self.assertEqual(new_vm.zoneid, self.zone.id, "New VM should be in the correct zone")

        # Verify the new VM has the correct number of volumes (ROOT + DATADISK)
        volumes = Volume.list(
            self.api_client,
            virtualmachineid=new_vm.id,
            listall=True
        )
        self.assertTrue(isinstance(volumes, list), "List volumes should return a valid list")
        self.assertEqual(2, len(volumes), "The new VM should have 2 volumes (ROOT + DATADISK)")

        # Verify that the file is present in the Instance created from backup
        try:
            ssh_client_new_vm = new_vm.get_ssh_client(reconnect=True)
            result = ssh_client_new_vm.execute("ls test_backup_and_recovery.txt")
            self.assertEqual(result[0], "test_backup_and_recovery.txt",
                             "Instance created from Backup should have the same file as the backup.")
        except Exception as err:
            self.fail("SSH failed for Virtual machine: %s due to %s" % (self.vm.ipaddress, err))

        # Delete backups
        Backup.delete(self.api_client, backups[0].id)
        Backup.delete(self.api_client, backups[1].id)

    # ------------------------------------------------------------------
    # Whole-chain validation: every backup is restorable and bootable, and
    # stays so after a backup is removed from the middle of the chain
    # ------------------------------------------------------------------
    # The other tests in this file each take a backup or two and delete them again;
    # none of them restores. That left the restore path uncovered, and a defect that
    # erased a Ceph volume's path on every backup went unnoticed until a chain was
    # restored by hand. This walks the whole chain instead: FULL + 3 INCREMENTALs,
    # restoring from every backup and building an Instance from every backup, then
    # deleting the middle incremental and repeating both sweeps over what survives.
    #
    # On Ceph the mid-chain delete is what earns the runtime. An `rbd export-diff`
    # stream carries no pointer to the diff before it, so a restore replays the whole
    # sequence from the full anchor; a diff whose descendant still needs it cannot be
    # dropped when it is deleted, only hidden. Restoring the tip across that gap is
    # what breaks if the provider ever sends a chain with a hole in it.
    #
    # Sizes are asserted throughout: an incremental must be a small fraction of the
    # full. That is what separates a real delta from the whole image being copied
    # again every cycle, and nothing else in this file looks at size at all.

    def _backup_type(self, backup):
        return (getattr(backup, 'type', 'FULL') or 'FULL').upper()

    def _home_listing(self, ssh_client):
        return "".join(ssh_client.execute("ls ~"))

    def _assert_chain_sizes(self, backups, context):
        """A FULL carries the allocated image; an INCREMENTAL must carry only a delta."""
        full_size = int(backups[0].size)
        self.assertGreater(full_size, 0, "%s: FULL reports a size of 0" % context)
        for inc in backups[1:]:
            self.assertLess(int(inc.size), full_size / 10,
                "%s: incremental is %s bytes against a FULL of %s -- a delta should be a small "
                "fraction of the full, not the whole image again" % (context, inc.size, full_size))

    def _restore_and_check(self, backup, expected, absent, context):
        """Restore over the Instance's own volume and verify what the guest comes back with.

        A restore rewinds the volume wholesale, so markers written after the backup was
        taken must be gone; checking only that the expected ones are present would pass
        even if the restore had quietly done nothing.
        """
        self.vm.stop(self.api_client)
        time.sleep(5)
        Backup.restoreVM(self.api_client, backup.id)
        self.vm.start(self.api_client)
        listing = self._home_listing(self.vm.get_ssh_client(reconnect=True))
        for marker in expected:
            self.assertIn(marker, listing, "%s: marker %s taken before this backup is missing after the restore" % (context, marker))
        for marker in absent:
            self.assertNotIn(marker, listing, "%s: marker %s was written after this backup, so the restore should have rolled it back" % (context, marker))

    def _create_vm_from_backup_and_check(self, backup, expected, absent, context):
        """Build an Instance from a backup and verify the guest, not just that it boots."""
        name = "kboss-chain-%s-%d" % (context, int(time.time()))
        new_vm = Backup.createVMFromBackup(
            self.api_client,
            self.services["small"],
            mode=self.services["mode"],
            backupid=backup.id,
            vmname=name,
            accountname=self.account.name,
            domainid=self.account.domainid,
            zoneid=self.zone.id,
            networkids=None,
            templateid=None
        )
        self.assertIsNotNone(new_vm, "%s: createVMFromBackup returned nothing" % context)
        try:
            listing = self._home_listing(new_vm.get_ssh_client(reconnect=True))
            for marker in expected:
                self.assertIn(marker, listing, "%s: marker %s is missing from the Instance created from this backup" % (context, marker))
            for marker in absent:
                self.assertNotIn(marker, listing, "%s: marker %s was written after this backup, so an Instance created from it should not have it" % (context, marker))
        finally:
            # Destroyed as we go rather than through self.cleanup: this test builds seven
            # of these, and keeping them all alive risks exhausting the environment.
            try:
                new_vm.delete(self.api_client, expunge=True)
            except Exception:
                pass

    @attr(tags=["advanced", "backup"], required_hardware="true")
    def test_kboss_full_chain_restore_and_create_vm_survive_mid_chain_delete(self):
        """
        FULL + 3 INCREMENTALs: restore from every backup and create an Instance from
        every backup; then delete the 2nd incremental and repeat over the survivors.
        """
        self.basic_backup_offering.assignOffering(self.api_client, self.vm.id)
        backups = []
        try:
            ssh = self.vm.get_ssh_client(reconnect=True)

            # --- build FULL -> INC1 -> INC2 -> INC3, a marker per backup ---------
            markers = []
            for idx in range(4):
                marker = "chain_marker_%d.txt" % idx
                # sync: without it the file can still be in the guest page cache rather
                # than on the volume when the backup is taken moments later.
                ssh.execute("touch ~/%s; sync" % marker)
                markers.append(marker)
                Backup.create(self.api_client, self.vm.id, "kboss_chain_%d" % idx)
                time.sleep(3)

            backups = Backup.list(self.api_client, self.vm.id)
            backups.sort(key=lambda b: b.created)
            self.assertEqual(len(backups), 4, "Expected FULL + 3 INCREMENTAL backups")
            self.assertEqual(self._backup_type(backups[0]), 'FULL')
            for inc in backups[1:]:
                self.assertEqual(self._backup_type(inc), 'INCREMENTAL')
            self._assert_chain_sizes(backups, "complete chain")
            sizes_before = [int(b.size) for b in backups]

            # --- restore from each, then build an Instance from each -------------
            for idx, backup in enumerate(backups):
                self._restore_and_check(backup, markers[:idx + 1], markers[idx + 1:], "complete chain, backup %d" % idx)
            for idx, backup in enumerate(backups):
                self._create_vm_from_backup_and_check(backup, markers[:idx + 1], markers[idx + 1:], "full%d" % idx)

            # --- delete the 2nd incremental --------------------------------------
            deleted = backups[2]
            Backup.delete(self.api_client, deleted.id)
            survivors = Backup.list(self.api_client, self.vm.id)
            survivors.sort(key=lambda b: b.created)
            self.assertEqual(len(survivors), 3, "Deleting one backup should leave exactly the other three")
            self.assertNotIn(deleted.id, [b.id for b in survivors], "The deleted backup must no longer be listed")

            # Removing a sibling must not alter what the others report.
            self.assertEqual([int(b.size) for b in survivors], [sizes_before[0], sizes_before[1], sizes_before[3]],
                "Deleting a backup changed the reported size of the backups that remain")

            # --- restore from each survivor, then build an Instance from each ----
            # survivors[2] is the old INC3: restoring it replays the chain across the
            # deleted INC2, so its marker must still come back.
            expected = [markers[:1], markers[:2], markers[:4]]
            absent = [markers[1:], markers[2:], []]
            for idx, backup in enumerate(survivors):
                self._restore_and_check(backup, expected[idx], absent[idx], "after delete, backup %d" % idx)
            for idx, backup in enumerate(survivors):
                self._create_vm_from_backup_and_check(backup, expected[idx], absent[idx], "post%d" % idx)

            # --- the chain must leave nothing behind ------------------------------
            for backup in reversed(survivors):
                Backup.delete(self.api_client, backup.id)
            backups = []
            self.assertFalse(Backup.list(self.api_client, self.vm.id),
                "Every backup was deleted, so none should be left for this Instance")
        finally:
            for b in reversed(backups):
                try:
                    Backup.delete(self.api_client, b.id)
                except Exception:
                    pass
            self.basic_backup_offering.removeOffering(self.api_client, self.vm.id)
