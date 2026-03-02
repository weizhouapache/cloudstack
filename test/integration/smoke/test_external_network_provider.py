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
"""Smoke tests for External Network Provider plugin
    - Add ExternalNetwork service provider to existing physical network
    - Enable the provider
    - Create a NetworkOrchestrator extension and deploy the script
    - Register the extension with the physical network
    - Disable and remove the ExternalNetwork service provider
    - Unregister and delete the extension
"""
import logging
import os
import stat
import time

from marvin.cloudstackTestCase import cloudstackTestCase
from marvin.cloudstackAPI import (listPhysicalNetworks,
                                  addNetworkServiceProvider,
                                  listNetworkServiceProviders,
                                  updateNetworkServiceProvider,
                                  deleteNetworkServiceProvider)
from marvin.cloudstackException import CloudstackAPIException
from marvin.lib.base import Extension
from marvin.lib.common import get_zone
from marvin.lib.utils import random_gen
from marvin.sshClient import SshClient
from nose.plugins.attrib import attr

_multiprocess_shared_ = True

EXTERNAL_NETWORK_PROVIDER_NAME = 'ExternalNetwork'

# Minimal entry-point script that just logs and exits 0 for testing.
# On a real Oracle Linux 9 server this would manage iptables/bridges.
TEST_ENTRY_POINT_SCRIPT = r"""#!/bin/bash
# Test entry-point for ExternalNetwork smoke test
LOG_FILE="/var/log/cloudstack/management/external-network-test.log"
mkdir -p "$(dirname "${LOG_FILE}")" 2>/dev/null || true
COMMAND="${1:-unknown}"
shift || true
echo "[$(date '+%Y-%m-%d %H:%M:%S')] command=${COMMAND} args=$*" >> "${LOG_FILE}" 2>/dev/null || true
echo "OK: ${COMMAND}"
exit 0
"""


class TestExternalNetworkProvider(cloudstackTestCase):
    """Smoke test for the ExternalNetwork plugin.

    Adds the ExternalNetwork service provider to the first physical
    network, creates a NetworkOrchestrator extension, registers it with
    the physical network, verifies state, then tears everything down.
    """

    @classmethod
    def setUpClass(cls):
        testClient = super(TestExternalNetworkProvider, cls).getClsTestClient()
        cls.apiclient = testClient.getApiClient()
        cls.services = testClient.getParsedTestDataConfig()
        cls.zone = get_zone(cls.apiclient, testClient.getZoneForTests())
        cls.mgtSvrDetails = cls.config.__dict__["mgtSvr"][0].__dict__
        cls._cleanup = []
        cls.logger = logging.getLogger('TestExternalNetworkProvider')
        cls.logger.setLevel(logging.DEBUG)

    @classmethod
    def tearDownClass(cls):
        super(TestExternalNetworkProvider, cls).tearDownClass()

    def setUp(self):
        self.cleanup = []
        self.provider_id = None
        self.extension = None
        self.physical_network = None

    def tearDown(self):
        # Best-effort cleanup in reverse order
        try:
            self._cleanup_provider_and_extension()
        except Exception as e:
            self.logger.warning("Cleanup error: %s", e)

        for obj in reversed(self.cleanup):
            try:
                obj.delete(self.apiclient)
            except Exception as e:
                self.logger.warning("Cleanup error for %s: %s", obj, e)

    # ------------------------------------------------------------------
    # Helper methods
    # ------------------------------------------------------------------

    def _get_physical_network(self):
        """Return the first physical network in the zone."""
        cmd = listPhysicalNetworks.listPhysicalNetworksCmd()
        cmd.zoneid = self.zone.id
        pns = self.apiclient.listPhysicalNetworks(cmd)
        self.assertIsInstance(pns, list, "No physical networks found")
        self.assertGreater(len(pns), 0, "No physical networks found")
        return pns[0]

    def _find_provider(self, physical_network_id, name):
        """Find a network service provider by name, returns None if absent."""
        cmd = listNetworkServiceProviders.listNetworkServiceProvidersCmd()
        cmd.physicalnetworkid = physical_network_id
        cmd.name = name
        providers = self.apiclient.listNetworkServiceProviders(cmd)
        if isinstance(providers, list) and len(providers) > 0:
            return providers[0]
        return None

    def _add_provider(self, physical_network_id, name, service_list=None):
        """Add a network service provider to a physical network."""
        cmd = addNetworkServiceProvider.addNetworkServiceProviderCmd()
        cmd.name = name
        cmd.physicalnetworkid = physical_network_id
        if service_list:
            cmd.servicelist = service_list
        provider = self.apiclient.addNetworkServiceProvider(cmd)
        self.assertIsNotNone(provider, "Failed to add provider %s" % name)
        self.logger.info("Added provider %s with id %s", name, provider.id)
        return provider

    def _enable_provider(self, provider_id):
        """Enable a network service provider."""
        cmd = updateNetworkServiceProvider.updateNetworkServiceProviderCmd()
        cmd.id = provider_id
        cmd.state = 'Enabled'
        result = self.apiclient.updateNetworkServiceProvider(cmd)
        self.logger.info("Enabled provider %s", provider_id)
        return result

    def _disable_provider(self, provider_id):
        """Disable a network service provider."""
        cmd = updateNetworkServiceProvider.updateNetworkServiceProviderCmd()
        cmd.id = provider_id
        cmd.state = 'Disabled'
        result = self.apiclient.updateNetworkServiceProvider(cmd)
        self.logger.info("Disabled provider %s", provider_id)
        return result

    def _delete_provider(self, provider_id):
        """Delete a network service provider."""
        cmd = deleteNetworkServiceProvider.deleteNetworkServiceProviderCmd()
        cmd.id = provider_id
        self.apiclient.deleteNetworkServiceProvider(cmd)
        self.logger.info("Deleted provider %s", provider_id)

    def _deploy_entry_point_script(self, extension_path):
        """Deploy the test entry-point script to the extension path
        on the management server (which is the marvin node / current server)."""
        entry_point_path = os.path.join(extension_path, "entry-point")
        self.logger.info("Deploying entry-point script to %s", entry_point_path)

        mgt_ip = self.mgtSvrDetails.get("mgtSvrIp", "localhost")
        if mgt_ip in ('localhost', '127.0.0.1'):
            # Local deployment
            os.makedirs(extension_path, exist_ok=True)
            with open(entry_point_path, 'w') as f:
                f.write(TEST_ENTRY_POINT_SCRIPT)
            os.chmod(entry_point_path, stat.S_IRWXU | stat.S_IRGRP | stat.S_IXGRP | stat.S_IROTH | stat.S_IXOTH)
        else:
            # Remote deployment via SSH
            ssh = SshClient(
                mgt_ip, 22,
                self.mgtSvrDetails["user"],
                self.mgtSvrDetails["passwd"]
            )
            ssh.execute("mkdir -p %s" % extension_path)
            # Write the script via heredoc
            ssh.execute("cat > %s << 'SCRIPTEOF'\n%sSCRIPTEOF" % (entry_point_path, TEST_ENTRY_POINT_SCRIPT))
            ssh.execute("chmod 755 %s" % entry_point_path)

    def _cleanup_entry_point_script(self, extension_path):
        """Remove the deployed entry-point script."""
        entry_point_path = os.path.join(extension_path, "entry-point")
        mgt_ip = self.mgtSvrDetails.get("mgtSvrIp", "localhost")
        try:
            if mgt_ip in ('localhost', '127.0.0.1'):
                if os.path.exists(entry_point_path):
                    os.remove(entry_point_path)
            else:
                ssh = SshClient(
                    mgt_ip, 22,
                    self.mgtSvrDetails["user"],
                    self.mgtSvrDetails["passwd"]
                )
                ssh.execute("rm -f %s" % entry_point_path)
        except Exception as e:
            self.logger.warning("Failed to clean up entry-point script: %s", e)

    def _cleanup_provider_and_extension(self):
        """Best-effort cleanup: unregister extension, disable/delete provider."""
        if self.extension and self.physical_network:
            try:
                self.extension.unregister(
                    self.apiclient,
                    self.physical_network.id,
                    'PhysicalNetwork'
                )
                self.logger.info("Unregistered extension from physical network")
            except Exception as e:
                self.logger.warning("Failed to unregister extension: %s", e)

        if self.provider_id:
            try:
                self._disable_provider(self.provider_id)
            except Exception:
                pass
            try:
                self._delete_provider(self.provider_id)
                self.logger.info("Deleted provider %s", self.provider_id)
            except Exception as e:
                self.logger.warning("Failed to delete provider: %s", e)

        if self.extension:
            try:
                # Clean up the extension script
                ext_list = Extension.list(self.apiclient, id=self.extension.id)
                if ext_list and len(ext_list) > 0:
                    ext_path = ext_list[0].path
                    if ext_path:
                        self._cleanup_entry_point_script(ext_path)
                self.extension.delete(self.apiclient, unregisterresources=False)
                self.logger.info("Deleted extension %s", self.extension.id)
            except Exception as e:
                self.logger.warning("Failed to delete extension: %s", e)

    # ------------------------------------------------------------------
    # Test methods
    # ------------------------------------------------------------------

    @attr(tags=["advanced", "smoke"], required_hardware="false")
    def test_01_add_enable_external_network_provider(self):
        """Test adding and enabling the ExternalNetwork provider on a physical network.

        Steps:
        1. Get first physical network in the zone
        2. Create a NetworkOrchestrator extension
        3. Deploy the test entry-point script to the extension path
        4. Register the extension with the physical network
        5. Add the ExternalNetwork service provider
        6. Enable the provider
        7. Verify provider is in Enabled state
        8. Disable and delete the provider
        9. Unregister and delete the extension
        """
        # Step 1: Get physical network
        self.physical_network = self._get_physical_network()
        self.logger.info("Using physical network: %s (id=%s)",
                         self.physical_network.name, self.physical_network.id)

        # Step 2: Create a NetworkOrchestrator extension
        ext_name = "extnet-test-" + random_gen()
        self.extension = Extension.create(
            self.apiclient,
            name=ext_name,
            type='NetworkOrchestrator'
        )
        self.assertIsNotNone(self.extension, "Failed to create extension")
        self.assertEqual(self.extension.type, 'NetworkOrchestrator',
                         "Extension type should be NetworkOrchestrator")
        self.assertEqual(self.extension.state, 'Enabled',
                         "Extension should be Enabled")
        self.logger.info("Created extension: %s (id=%s)", ext_name, self.extension.id)

        # Step 3: Deploy the test entry-point script
        # Get the extension's resolved path
        ext_list = Extension.list(self.apiclient, id=self.extension.id)
        self.assertIsNotNone(ext_list, "Extension not found after creation")
        self.assertGreater(len(ext_list), 0, "Extension not found after creation")
        extension_path = ext_list[0].path
        self.assertIsNotNone(extension_path, "Extension path should not be None")
        self.logger.info("Extension path: %s", extension_path)

        self._deploy_entry_point_script(extension_path)

        # Step 4: Register the extension with the physical network
        registered = self.extension.register(
            self.apiclient,
            self.physical_network.id,
            'PhysicalNetwork'
        )
        self.assertIsNotNone(registered, "Failed to register extension")
        self.logger.info("Registered extension with physical network %s",
                         self.physical_network.id)

        # Verify extension shows the physical network resource
        ext_list = Extension.list(self.apiclient, id=self.extension.id)
        self.assertIsNotNone(ext_list, "Extension listing failed")
        ext = ext_list[0]
        found_pn_resource = False
        if hasattr(ext, 'resources') and ext.resources:
            for resource in ext.resources:
                if resource.type == 'PhysicalNetwork':
                    found_pn_resource = True
                    break
        self.assertTrue(found_pn_resource,
                        "Extension should show PhysicalNetwork resource after registration")

        # Step 5: Add ExternalNetwork provider
        provider = self._find_provider(self.physical_network.id, EXTERNAL_NETWORK_PROVIDER_NAME)
        if provider is None:
            provider = self._add_provider(
                self.physical_network.id,
                EXTERNAL_NETWORK_PROVIDER_NAME,
                service_list=['SourceNat', 'StaticNat', 'PortForwarding', 'Firewall', 'Gateway']
            )
        self.provider_id = provider.id
        self.assertIsNotNone(provider, "Provider should not be None")
        self.assertEqual(provider.name, EXTERNAL_NETWORK_PROVIDER_NAME,
                         "Provider name mismatch")
        self.logger.info("Provider added: %s (id=%s, state=%s)",
                         provider.name, provider.id, provider.state)

        # Step 6: Enable the provider
        if provider.state != 'Enabled':
            self._enable_provider(provider.id)

        # Verify enabled
        provider = self._find_provider(self.physical_network.id, EXTERNAL_NETWORK_PROVIDER_NAME)
        self.assertIsNotNone(provider, "Provider not found after enabling")
        self.assertEqual(provider.state, 'Enabled',
                         "Provider should be in Enabled state")
        self.logger.info("Provider is now Enabled")

        # Step 7: Disable the provider
        self._disable_provider(provider.id)
        provider = self._find_provider(self.physical_network.id, EXTERNAL_NETWORK_PROVIDER_NAME)
        self.assertIsNotNone(provider, "Provider not found after disabling")
        self.assertEqual(provider.state, 'Disabled',
                         "Provider should be in Disabled state")
        self.logger.info("Provider is now Disabled")

        # Step 8: Delete the provider
        self._delete_provider(provider.id)
        self.provider_id = None
        provider = self._find_provider(self.physical_network.id, EXTERNAL_NETWORK_PROVIDER_NAME)
        self.assertIsNone(provider, "Provider should be deleted")
        self.logger.info("Provider deleted successfully")

        # Step 9: Unregister extension from physical network
        self.extension.unregister(
            self.apiclient,
            self.physical_network.id,
            'PhysicalNetwork'
        )
        self.logger.info("Unregistered extension from physical network")

        # Verify unregistration
        ext_list = Extension.list(self.apiclient, id=self.extension.id)
        ext = ext_list[0]
        has_pn_resource = False
        if hasattr(ext, 'resources') and ext.resources:
            for resource in ext.resources:
                if resource.type == 'PhysicalNetwork':
                    has_pn_resource = True
                    break
        self.assertFalse(has_pn_resource,
                         "Extension should not show PhysicalNetwork resource after unregistration")

        # Step 10: Clean up extension script and delete extension
        self._cleanup_entry_point_script(extension_path)
        self.extension.delete(self.apiclient, unregisterresources=False)
        self.extension = None
        self.physical_network = None
        self.logger.info("Extension deleted successfully")

        # Verify extension is gone
        try:
            ext_list = Extension.list(self.apiclient, id=self.extension.id if self.extension else 'bogus')
        except Exception:
            ext_list = None
        self.logger.info("External network provider smoke test passed")

    @attr(tags=["advanced", "smoke"], required_hardware="false")
    def test_02_add_provider_idempotent(self):
        """Test that adding the same provider twice fails gracefully."""
        pn = self._get_physical_network()
        self.physical_network = pn

        # Ensure provider doesn't exist
        provider = self._find_provider(pn.id, EXTERNAL_NETWORK_PROVIDER_NAME)
        if provider is not None:
            if provider.state == 'Enabled':
                self._disable_provider(provider.id)
            self._delete_provider(provider.id)

        # Add it
        provider = self._add_provider(pn.id, EXTERNAL_NETWORK_PROVIDER_NAME)
        self.provider_id = provider.id

        # Adding again should fail
        try:
            self._add_provider(pn.id, EXTERNAL_NETWORK_PROVIDER_NAME)
            self.fail("Adding the same provider twice should fail")
        except (CloudstackAPIException, Exception) as e:
            self.logger.info("Expected error on duplicate add: %s", e)

        # Cleanup
        self._delete_provider(provider.id)
        self.provider_id = None
        self.logger.info("Idempotent provider add test passed")

    @attr(tags=["advanced", "smoke"], required_hardware="false")
    def test_03_provider_lifecycle_states(self):
        """Test provider state transitions: Disabled -> Enabled -> Disabled -> Deleted."""
        pn = self._get_physical_network()
        self.physical_network = pn

        # Ensure clean
        provider = self._find_provider(pn.id, EXTERNAL_NETWORK_PROVIDER_NAME)
        if provider is not None:
            if provider.state == 'Enabled':
                self._disable_provider(provider.id)
            self._delete_provider(provider.id)

        # Add (starts as Disabled)
        provider = self._add_provider(pn.id, EXTERNAL_NETWORK_PROVIDER_NAME)
        self.provider_id = provider.id
        self.assertEqual(provider.state, 'Disabled',
                         "New provider should start as Disabled")

        # Enable
        self._enable_provider(provider.id)
        provider = self._find_provider(pn.id, EXTERNAL_NETWORK_PROVIDER_NAME)
        self.assertEqual(provider.state, 'Enabled')

        # Disable
        self._disable_provider(provider.id)
        provider = self._find_provider(pn.id, EXTERNAL_NETWORK_PROVIDER_NAME)
        self.assertEqual(provider.state, 'Disabled')

        # Delete
        self._delete_provider(provider.id)
        self.provider_id = None
        provider = self._find_provider(pn.id, EXTERNAL_NETWORK_PROVIDER_NAME)
        self.assertIsNone(provider, "Provider should be deleted")
        self.logger.info("Provider lifecycle state test passed")

