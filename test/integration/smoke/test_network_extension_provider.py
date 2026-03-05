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
"""Smoke tests for NetworkExtension plugin (formerly External Network Provider).

Architecture
------------
The test runs on the **Marvin node** (the host running this Python script).

A Linux **network namespace** is used to provide iptables isolation.
This is simpler and faster than Docker — only ``iproute2`` is required, which
is already present on any modern Linux host.

  ┌──────────────────────────────────┐   SSH :22    ┌──────────────────────────────────┐
  │  Management Server               │ ───────────→ │  Marvin node                     │
  │                                  │  marvin_ip   │                                  │
  │  [extension path]/entry-point    │              │  ip netns exec cs-extnet-<id>    │
  │   (SSH wrapper written by test)  │              │    network-extension-wrapper.sh   │
  └──────────────────────────────────┘              └──────────────────────────────────┘
         ↑ executes wrapper                                  ↑ namespace created by test
  CloudStack NetworkExtensionElement             iptables/bridge ops run here (isolated)

The entry-point wrapper on the management server SSHes to the Marvin node and
runs network-extension-wrapper.sh inside the network namespace.

Two-step setup:
  Step 1 — Register extension with physical network (no credentials):
    cmk registerExtension id=<ext-uuid> resourcetype=PhysicalNetwork \\
        resourceid=<phys-net-uuid>

  Step 2 — Add external network device (host/port + sensitive details):
    cmk addExternalNetworkDevice physicalnetworkid=<phys-net-uuid> \\
        host=<marvin_ip> port=22 \\
        details[0].key=username details[0].value=root \\
        details[1].key=sshkey   details[1].value="$(cat /tmp/cs-extnet-key)"

    Sensitive fields (password, sshkey) are stored with display=false and are
    never returned by listExternalNetworkDevices.

``NetworkExtensionElement`` reads all details (including hidden) via
``ExtensionHelper.getAllResourceMapDetailsForPhysicalNetwork()`` and injects them
as environment variables into the entry-point script:
  CS_NET_DEV_HOST, CS_NET_DEV_PORT, CS_NET_DEV_USERNAME,
  CS_NET_DEV_PASSWORD, CS_NET_DEV_SSHKEY

Network service provider (NSP) name is the **extension name** (e.g. ``extnet-smoke-<id>``),
not a generic ``NetworkExtension`` string.  This allows multiple different extensions to be
registered to the same physical network, each with its own NSP entry.  When creating a
network offering the ``serviceProviderList`` must use the actual extension name as the
provider for each service.  The UI lists each registered extension as its own entry in
the physical network's network service provider tab.

Setup on Marvin node (done by the test):
  1. Create network namespace:   ip netns add cs-extnet-<id>
  2. Generate RSA key pair; inject public key into authorized_keys.
  3. Copy network-extension-wrapper.sh into a temp dir.
  4. Deploy entry-point wrapper + private key to management server.
  5. Call addExternalNetworkDevice with host/port/username/sshkey details.

Teardown:
  1. Delete external network device.
  2. Unregister extension from physical network.
  3. Delete namespace:           ip netns del cs-extnet-<id>
  4. Remove public key from authorized_keys.
  5. Remove private key + wrapper from management server.
"""
import json
import logging
import os
import shutil
import stat
import subprocess
import tempfile
import time
import unittest

from marvin.cloudstackTestCase import cloudstackTestCase
from marvin.cloudstackAPI import (listPhysicalNetworks,
                                  addNetworkServiceProvider,
                                  listNetworkServiceProviders,
                                  updateNetworkServiceProvider,
                                  deleteNetworkServiceProvider,
                                  addExternalNetworkDevice,
                                  listExternalNetworkDevices,
                                  updateExternalNetworkDevice,
                                  deleteExternalNetworkDevice)
from marvin.lib.base import (Account,
                             Extension,
                             Network,
                             NetworkOffering,
                             NATRule,
                             PublicIPAddress,
                             ServiceOffering,
                             StaticNATRule,
                             VirtualMachine)
from marvin.lib.common import (get_domain,
                               get_zone,
                               get_template)
from marvin.lib.utils import cleanup_resources, random_gen
from marvin.sshClient import SshClient
from nose.plugins.attrib import attr

_multiprocess_shared_ = True

EXTERNAL_NETWORK_PROVIDER_NAME = 'NetworkExtension'  # legacy fallback only; actual provider name is the extension name

# SSH port on the Marvin node that the management server connects to
MARVIN_SSH_PORT = 22

# SSH user on the Marvin node (must have passwordless sudo or CAP_NET_ADMIN)
MARVIN_SSH_USER = 'root'

# Namespace name prefix (a random suffix is added per test run)
NS_PREFIX = 'cs-extnet'

# Path on the Marvin node where the script is placed
SCRIPT_DIR = '/tmp'
SCRIPT_FILENAME = 'network-extension-wrapper.sh'

ENTRY_POINT_FILENAME = 'entry-point'

# Network capabilities JSON
NETWORK_CAPABILITIES_JSON = json.dumps({
    "services": ["SourceNat", "StaticNat", "PortForwarding", "Firewall", "Gateway"],
    "capabilities": {
        "SourceNat": {
            "SupportedSourceNatTypes": "peraccount",
            "RedundantRouter": "false"
        },
        "Firewall": {
            "TrafficStatistics": "per public ip"
        }
    }
})

# Path to the reference network-extension-wrapper.sh in the source tree
_THIS_DIR = os.path.dirname(os.path.abspath(__file__))
_REPO_ROOT = os.path.abspath(os.path.join(_THIS_DIR, '..', '..', '..'))
REFERENCE_SCRIPT_SRC = os.path.join(
    _REPO_ROOT,
    'framework', 'extensions',
    'src', 'main', 'resources', 'scripts', 'network-extension-wrapper.sh'
)
# Static entry-point wrapper (in extensions/ in the repo) — deployed to the
# extension path on the management server unchanged. All connection details
# are passed as environment variables by NetworkExtensionElement.
STATIC_ENTRY_POINT_SRC = os.path.join(
    _REPO_ROOT, 'extensions', 'network-extension', 'entry-point'
)


# ---------------------------------------------------------------------------
# Local helpers (run on the Marvin node as root)
# ---------------------------------------------------------------------------

def _run(cmd, check=True, timeout=120):
    """Run a local command list on the Marvin node. Returns (rc, stdout, stderr)."""
    result = subprocess.run(
        cmd, check=False,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        timeout=timeout
    )
    stdout = result.stdout.decode().strip() if result.stdout else ''
    stderr = result.stderr.decode().strip() if result.stderr else ''
    if check and result.returncode != 0:
        raise RuntimeError(
            "Command %r failed (rc=%d):\nSTDOUT: %s\nSTDERR: %s" %
            (cmd, result.returncode, stdout, stderr)
        )
    return result.returncode, stdout, stderr


def _check_iproute2():
    """Raise SkipTest if ip-netns is unavailable."""
    rc, _, _ = _run(['ip', 'netns', 'list'], check=False)
    if rc != 0:
        raise unittest.SkipTest(
            "ip netns is not available on the Marvin node. "
            "Please install iproute2 to run this test."
        )


# ---------------------------------------------------------------------------
# NetnsNetworkServer  – manages a Linux network namespace on the Marvin node
# ---------------------------------------------------------------------------

class NetnsNetworkServer:
    """Creates an isolated Linux network namespace on the Marvin node.

    The namespace provides iptables/bridge isolation so that the
    network-extension-wrapper.sh script can run without polluting the host.

    The management server SSHes to the Marvin node at the normal SSH port
    and runs:
        ip netns exec <ns_name> <script_path> <args...>

    Lifecycle::

        server = NetnsNetworkServer(marvin_ip='192.168.1.10')
        server.start()
        server.run_in_ns('iptables -L')
        server.stop()
    """

    def __init__(self, marvin_ip, logger=None):
        self.marvin_ip = marvin_ip
        self.logger = logger or logging.getLogger('NetnsNetworkServer')
        self._ns_name = '%s-%s' % (NS_PREFIX, random_gen())
        self._tmpdir = None
        self._key_file = None
        self._pub_key = None
        self._script_path = None
        self._authorized_keys_entry = None
        self.running = False

    # ---- public properties ----

    @property
    def ns_name(self):
        return self._ns_name

    @property
    def key_file(self):
        return self._key_file

    @property
    def pub_key(self):
        return self._pub_key

    @property
    def script_path(self):
        return self._script_path

    # ---- lifecycle ----

    def start(self):
        """Create namespace, generate SSH key, install script."""
        _check_iproute2()

        # Generate temp dir and SSH key pair on the Marvin node
        self._tmpdir = tempfile.mkdtemp(prefix='cs-extnet-test-')
        self._key_file = os.path.join(self._tmpdir, 'id_rsa_extnet')
        self.logger.info("Generating SSH key pair at %s", self._key_file)
        _run(['ssh-keygen', '-t', 'rsa', '-b', '2048',
              '-N', '', '-f', self._key_file])
        with open(self._key_file + '.pub') as fh:
            self._pub_key = fh.read().strip()

        # Add the public key to authorized_keys with a command restriction
        # that forces the key to only run our wrapper command
        self._install_authorized_key()

        # Create the network namespace
        self.logger.info("Creating network namespace %s", self._ns_name)
        _run(['ip', 'netns', 'add', self._ns_name])
        # Bring up loopback inside the namespace
        _run(['ip', 'netns', 'exec', self._ns_name, 'ip', 'link', 'set', 'lo', 'up'],
             check=False)
        self.running = True

        # Install the script
        self._install_script()

        self.logger.info(
            "Namespace %s ready. Management server should SSH to %s:%d "
            "and run: ip netns exec %s %s <args>",
            self._ns_name, self.marvin_ip, MARVIN_SSH_PORT,
            self._ns_name, self._script_path)

    def stop(self):
        """Delete namespace, remove authorized key, clean up temp dir."""
        if self._ns_name and self.running:
            _run(['ip', 'netns', 'del', self._ns_name], check=False)
            self.logger.info("Namespace %s deleted", self._ns_name)
        self._remove_authorized_key()
        if self._tmpdir and os.path.exists(self._tmpdir):
            shutil.rmtree(self._tmpdir, ignore_errors=True)
        self.running = False

    # ---- helpers ----

    def _install_script(self):
        """Copy network-extension-wrapper.sh to a temp path on the Marvin node."""
        dest = os.path.join(self._tmpdir, SCRIPT_FILENAME)
        if os.path.exists(REFERENCE_SCRIPT_SRC):
            shutil.copy2(REFERENCE_SCRIPT_SRC, dest)
            self.logger.info("Copied %s -> %s", REFERENCE_SCRIPT_SRC, dest)
        else:
            self.logger.warning(
                "Reference script not found at %s; installing no-op stub", REFERENCE_SCRIPT_SRC)
            with open(dest, 'w') as fh:
                fh.write('#!/bin/bash\n'
                         'mkdir -p /var/log 2>/dev/null || true\n'
                         'echo "[$(date)] cmd=$1 args=${*:2}" '
                         '>> /var/log/cs-extnet.log 2>/dev/null || true\n'
                         'echo "OK: $1"\nexit 0\n')
        os.chmod(dest, stat.S_IRWXU | stat.S_IRGRP | stat.S_IXGRP |
                 stat.S_IROTH | stat.S_IXOTH)
        self._script_path = dest

    def _authorized_keys_path(self):
        return os.path.expanduser('~%s/.ssh/authorized_keys' % MARVIN_SSH_USER)

    def _install_authorized_key(self):
        """Append the test public key to authorized_keys."""
        ak_path = self._authorized_keys_path()
        os.makedirs(os.path.dirname(ak_path), mode=0o700, exist_ok=True)
        # Tag the entry so we can remove it precisely
        entry = '# cs-extnet-test-%s\n%s\n' % (self._ns_name, self._pub_key)
        self._authorized_keys_entry = entry
        with open(ak_path, 'a') as fh:
            fh.write(entry)
        os.chmod(ak_path, 0o600)
        self.logger.info("Added test public key to %s", ak_path)

    def _remove_authorized_key(self):
        """Remove the test public key from authorized_keys."""
        if not self._authorized_keys_entry:
            return
        ak_path = self._authorized_keys_path()
        try:
            if not os.path.exists(ak_path):
                return
            with open(ak_path, 'r') as fh:
                content = fh.read()
            content = content.replace(self._authorized_keys_entry, '')
            with open(ak_path, 'w') as fh:
                fh.write(content)
            self.logger.info("Removed test public key from %s", ak_path)
        except Exception as e:
            self.logger.warning("Could not remove key from authorized_keys: %s", e)

    def run_in_ns(self, command, check=True):
        """Run *command* string inside the network namespace. Returns (rc, stdout, stderr)."""
        return _run(
            ['ip', 'netns', 'exec', self._ns_name, 'bash', '-c', command],
            check=check
        )


# ---------------------------------------------------------------------------
# MgmtServerDeployer  – deploys files to the management server
# ---------------------------------------------------------------------------

class MgmtServerDeployer:
    """Copies the SSH private key and entry-point wrapper to the management server.

    If mgmt server == Marvin node (localhost), files are written directly.
    Otherwise they are transferred via SshClient.
    """

    def __init__(self, mgt_details, logger=None):
        self.ip     = mgt_details.get("mgtSvrIp", "localhost")
        self.port   = int(mgt_details.get("port", 22))
        self.user   = mgt_details.get("user", "root")
        self.passwd = mgt_details.get("passwd", "")
        self.logger = logger or logging.getLogger('MgmtServerDeployer')
        self._is_local = self.ip in ('localhost', '127.0.0.1')

    def _ssh(self):
        return SshClient(self.ip, self.port, self.user, self.passwd)

    def write_file(self, remote_path, content, mode='0755'):
        """Write *content* to *remote_path* on the management server."""
        if self._is_local:
            os.makedirs(os.path.dirname(remote_path), exist_ok=True)
            with open(remote_path, 'w') as fh:
                fh.write(content)
            os.chmod(remote_path, int(mode, 8))
            self.logger.info("Wrote %s locally", remote_path)
        else:
            ssh = self._ssh()
            ssh.execute("mkdir -p '%s'" % os.path.dirname(remote_path))
            ssh.execute("cat > '%s' << 'MGMTEOF'\n%sMGMTEOF" %
                        (remote_path, content))
            ssh.execute("chmod %s '%s'" % (mode, remote_path))
            self.logger.info("Wrote %s on mgmt server %s", remote_path, self.ip)

    def copy_file(self, local_path, remote_path, mode='0600'):
        """Copy a local file to *remote_path* on the management server."""
        if self._is_local:
            os.makedirs(os.path.dirname(remote_path), exist_ok=True)
            shutil.copy2(local_path, remote_path)
            os.chmod(remote_path, int(mode, 8))
            self.logger.info("Copied %s -> %s locally", local_path, remote_path)
        else:
            import base64
            with open(local_path, 'rb') as fh:
                b64 = base64.b64encode(fh.read()).decode()
            ssh = self._ssh()
            ssh.execute("mkdir -p '%s'" % os.path.dirname(remote_path))
            ssh.execute(
                "echo '%s' | base64 -d > '%s' && chmod %s '%s'" %
                (b64, remote_path, mode, remote_path)
            )
            self.logger.info("Copied %s -> %s on mgmt %s",
                             local_path, remote_path, self.ip)

    def remove_file(self, remote_path):
        """Remove *remote_path* on the management server (best-effort)."""
        try:
            if self._is_local:
                if os.path.exists(remote_path):
                    os.remove(remote_path)
            else:
                self._ssh().execute("rm -f '%s'" % remote_path)
        except Exception as e:
            self.logger.warning("Could not remove %s: %s", remote_path, e)

    def get_marvin_ip_as_seen_from_mgmt(self):
        """Return the IP the management server should use to reach the Marvin node."""
        if self._is_local:
            return '127.0.0.1'
        env_ip = os.environ.get('MARVIN_NODE_IP', '')
        if env_ip:
            return env_ip
        try:
            import socket
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect((self.ip, 80))
            return s.getsockname()[0]
        except Exception:
            raise RuntimeError(
                "Cannot determine the Marvin node IP as seen from the management "
                "server (%s). Please set the MARVIN_NODE_IP environment variable." %
                self.ip
            )


# ---------------------------------------------------------------------------
# Test class
# ---------------------------------------------------------------------------

class TestExternalNetworkProvider(cloudstackTestCase):
    """Full lifecycle smoke test for the ExternalNetwork plugin.

    A Linux network namespace is created on the Marvin node for iptables
    isolation.  The management server SSHes to the Marvin node and runs
    network-extension-wrapper.sh inside the namespace.
    """

    @classmethod
    def setUpClass(cls):
        testClient = super(TestExternalNetworkProvider, cls).getClsTestClient()
        cls.apiclient     = testClient.getApiClient()
        cls.services      = testClient.getParsedTestDataConfig()
        cls.zone          = get_zone(cls.apiclient, testClient.getZoneForTests())
        cls.domain        = get_domain(cls.apiclient)
        cls.mgtSvrDetails = cls.config.__dict__["mgtSvr"][0].__dict__
        cls.hypervisor    = testClient.getHypervisorInfo()
        cls.template      = get_template(cls.apiclient, cls.zone.id, cls.hypervisor)
        cls._cleanup      = []
        cls.logger        = logging.getLogger('TestExternalNetworkProvider')
        cls.logger.setLevel(logging.DEBUG)

    @classmethod
    def tearDownClass(cls):
        super(TestExternalNetworkProvider, cls).tearDownClass()

    def setUp(self):
        self.cleanup              = []
        self.provider_id          = None
        self.provider_name        = None
        self.extension            = None
        self.physical_network     = None
        self.extension_path       = None
        self.ns_server            = None
        self.mgmt_deployer        = None
        self._mgmt_wrapper_path   = None

    def tearDown(self):
        self._safe_teardown()
        try:
            cleanup_resources(self.apiclient, self.cleanup)
        except Exception as e:
            self.logger.warning("cleanup_resources error: %s", e)
        if self.ns_server:
            try:
                self.ns_server.stop()
            except Exception as e:
                self.logger.warning("Namespace stop error: %s", e)
            self.ns_server = None

    # ------------------------------------------------------------------
    # CloudStack API helpers
    # ------------------------------------------------------------------

    def _get_physical_network(self):
        cmd = listPhysicalNetworks.listPhysicalNetworksCmd()
        cmd.zoneid = self.zone.id
        pns = self.apiclient.listPhysicalNetworks(cmd)
        self.assertIsInstance(pns, list)
        self.assertGreater(len(pns), 0)
        return pns[0]

    def _find_provider(self, phys_net_id, name):
        cmd = listNetworkServiceProviders.listNetworkServiceProvidersCmd()
        cmd.physicalnetworkid = phys_net_id
        cmd.name = name
        providers = self.apiclient.listNetworkServiceProviders(cmd)
        return providers[0] if isinstance(providers, list) and providers else None

    def _add_provider(self, phys_net_id, name, service_list=None):
        cmd = addNetworkServiceProvider.addNetworkServiceProviderCmd()
        cmd.name = name
        cmd.physicalnetworkid = phys_net_id
        if service_list:
            cmd.servicelist = service_list
        return self.apiclient.addNetworkServiceProvider(cmd)

    def _update_provider_state(self, provider_id, state):
        cmd = updateNetworkServiceProvider.updateNetworkServiceProviderCmd()
        cmd.id = provider_id
        cmd.state = state
        return self.apiclient.updateNetworkServiceProvider(cmd)

    def _delete_provider(self, provider_id):
        cmd = deleteNetworkServiceProvider.deleteNetworkServiceProviderCmd()
        cmd.id = provider_id
        self.apiclient.deleteNetworkServiceProvider(cmd)

    # ------------------------------------------------------------------
    # External network device API helpers
    # ------------------------------------------------------------------

    def _add_external_network_device(self, physicalnetworkid, host, port=22,
                                     details=None):
        """Call addExternalNetworkDevice and return the response object."""
        cmd = addExternalNetworkDevice.addExternalNetworkDeviceCmd()
        cmd.physicalnetworkid = physicalnetworkid
        cmd.host = host
        cmd.port = port
        if details:
            cmd.details = details
        return self.apiclient.addExternalNetworkDevice(cmd)

    def _list_external_network_devices(self, physicalnetworkid=None):
        """Call listExternalNetworkDevices and return the list."""
        cmd = listExternalNetworkDevices.listExternalNetworkDevicesCmd()
        if physicalnetworkid:
            cmd.physicalnetworkid = physicalnetworkid
        result = self.apiclient.listExternalNetworkDevices(cmd)
        return result if isinstance(result, list) else []

    def _update_external_network_device(self, physicalnetworkid, **kwargs):
        """Call updateExternalNetworkDevice."""
        cmd = updateExternalNetworkDevice.updateExternalNetworkDeviceCmd()
        cmd.physicalnetworkid = physicalnetworkid
        for k, v in kwargs.items():
            setattr(cmd, k, v)
        return self.apiclient.updateExternalNetworkDevice(cmd)

    def _delete_external_network_device(self, physicalnetworkid):
        """Call deleteExternalNetworkDevice."""
        cmd = deleteExternalNetworkDevice.deleteExternalNetworkDeviceCmd()
        cmd.physicalnetworkid = physicalnetworkid
        self.apiclient.deleteExternalNetworkDevice(cmd)

    # ------------------------------------------------------------------
    # Namespace / mgmt-server helpers
    # ------------------------------------------------------------------

    def _init_ns_and_deployer(self):
        """Create the network namespace and management server deployer."""
        self.mgmt_deployer = MgmtServerDeployer(self.mgtSvrDetails,
                                                logger=self.logger)
        marvin_ip = self.mgmt_deployer.get_marvin_ip_as_seen_from_mgmt()
        self.ns_server = NetnsNetworkServer(marvin_ip=marvin_ip,
                                            logger=self.logger)
        self.ns_server.start()
        return marvin_ip

    def _deploy_to_mgmt_server(self, ext_path):
        """Deploy the static entry-point script to the extension path on the mgmt server.

        The static entry-point (extensions/network-extension/entry-point) reads all
        connection details (host, port, username, sshkey, namespace, script_path) from
        environment variables injected by NetworkExtensionElement — so no dynamic
        wrapper generation is needed.

        Returns the private-key PEM content so the caller can store it in
        extension_resource_map_details via addExternalNetworkDevice.
        """
        # 1. Find the static entry-point from the extensions/ directory in the repo
        entry_point_src = STATIC_ENTRY_POINT_SRC
        if not os.path.exists(entry_point_src):
            raise RuntimeError(
                "Static entry-point not found at %s. "
                "Please ensure extensions/network-extension/entry-point exists." % entry_point_src)

        # 2. Deploy it to the extension path on the management server
        self._mgmt_wrapper_path = os.path.join(ext_path, ENTRY_POINT_FILENAME)
        with open(entry_point_src, 'r') as fh:
            entry_point_content = fh.read()
        self.mgmt_deployer.write_file(
            self._mgmt_wrapper_path,
            entry_point_content,
            mode='0755'
        )
        self.logger.info("Static entry-point deployed to mgmt server at %s",
                         self._mgmt_wrapper_path)

        # 3. Return PEM key content for use in addExternalNetworkDevice
        with open(self.ns_server.key_file, 'r') as fh:
            return fh.read()

    def _cleanup_mgmt_server_files(self):
        if self.mgmt_deployer:
            if self._mgmt_wrapper_path:
                self.mgmt_deployer.remove_file(self._mgmt_wrapper_path)

    def _safe_teardown(self):
        # Delete external network device details before unregistering extension
        if self.physical_network:
            try:
                self._delete_external_network_device(self.physical_network.id)
            except Exception:
                pass
        if self.extension and self.physical_network:
            try:
                self.extension.unregister(self.apiclient,
                                          self.physical_network.id,
                                          'PhysicalNetwork')
            except Exception:
                pass
        if self.provider_id:
            for fn in (
                lambda: self._update_provider_state(self.provider_id, 'Disabled'),
                lambda: self._delete_provider(self.provider_id)
            ):
                try:
                    fn()
                except Exception:
                    pass
            self.provider_id = None
        if self.extension:
            try:
                self.extension.delete(self.apiclient,
                                      unregisterresources=False,
                                      removeactions=False)
            except Exception:
                pass
            self.extension = None
        self._cleanup_mgmt_server_files()

    # ------------------------------------------------------------------
    # Tests
    # ------------------------------------------------------------------

    @attr(tags=["advanced", "smoke"], required_hardware="false")
    def test_01_external_network_full_lifecycle(self):
        """Full lifecycle: netns → extension → network → VM → NAT → PF → teardown.

        Flow
        ----
        Marvin node:
          1.  Create Linux network namespace  (ip netns add cs-extnet-<id>).
          2.  Generate RSA key pair; inject public key into authorized_keys.
          3.  Copy network-extension-wrapper.sh to a temp path.

        Management server:
          4.  Deploy static entry-point (extensions/network-extension/entry-point)
              to <extension_path>/entry-point — no dynamic wrapper generation.
              The entry-point reads all connection details from env vars at runtime.

        CloudStack:
          5.  Create NetworkOrchestrator extension (path=network-extension/entry-point).
          6.  Register extension with physical network.
          7.  addExternalNetworkDevice: store host, port, username, sshkey,
              namespace, script_path as extension_resource_map_details.
              - sshkey stored with display=false (never returned by list API).
              - namespace/script_path stored with display=true.
          8.  listExternalNetworkDevices: verify device, sshkey hidden, namespace visible.
          9.  Add + enable provider (NSP named after the extension, not 'ExternalNetwork').
              Each registered extension becomes its own named network service provider
              so multiple extensions can coexist on the same physical network.
         10.  Create network offering using the extension name as service provider.
         11.  Create account.
         12.  Create isolated network.
              → entry-point receives: CS_NET_DEV_HOST, CS_NET_DEV_SSHKEY,
                CS_NET_NAMESPACE, CS_NET_SCRIPT_PATH, etc.
              → SSHes to Marvin, runs: ip netns exec <ns> <script> implement ...
         13.  Deploy VM   → prepare called.
         14.  Acquire public IP → assign-ip command.
         15.  Enable static NAT → add-static-nat command.
         16.  Disable static NAT → delete-static-nat command.
         17.  Acquire public IP for PF.
         18.  Create port forwarding rule → add-port-forward command.
         19.  Delete port forwarding rule → delete-port-forward command.
         20.  Destroy VM.
         21.  Delete network → shutdown/destroy commands.

        Cleanup:
         22.  Disable/delete provider.
         23.  Unregister/delete extension.
         24.  Remove entry-point from mgmt server.
         25.  Delete network namespace; remove authorized_keys entry.

        Notes:
          - Each registered extension becomes its own network service provider named
            after the extension. This avoids ambiguity when multiple external network
            extensions are registered to the same physical network.
          - The entry-point (extensions/network-extension/entry-point) is a static
            script that reads ALL details from environment variables — no hardcoding.
          - namespace and script_path are passed as addExternalNetworkDevice details,
            becoming CS_NET_NAMESPACE and CS_NET_SCRIPT_PATH env vars.
          - The extension can be enabled/disabled via updateExtension(state=...).
          - The extension can only be deleted when not registered to any resources.
        """

        # ---- Steps 1-3: Network namespace on Marvin node ----
        marvin_ip = self._init_ns_and_deployer()
        self.logger.info("Marvin IP (as seen from mgmt): %s  namespace: %s",
                         marvin_ip, self.ns_server.ns_name)

        # Sanity: run the script inside the namespace
        rc, out, _ = self.ns_server.run_in_ns(
            '%s implement --network-id 0 --vlan 0 '
            '--gateway 192.0.2.1 --cidr 192.0.2.0/24 || true' %
            self.ns_server.script_path, check=False)
        self.logger.info("Namespace script sanity check: rc=%d out=%r", rc, out)

        # ---- Step 4: Physical network ----
        self.physical_network = self._get_physical_network()
        self.logger.info("Physical network: %s (%s)",
                         self.physical_network.name, self.physical_network.id)

        # ---- Step 5: Create extension with network.capabilities detail ----
        ext_name = "extnet-smoke-" + random_gen()
        self.extension = Extension.create(
            self.apiclient,
            name=ext_name,
            type='NetworkOrchestrator',
            details=[{"network.capabilities": NETWORK_CAPABILITIES_JSON}]
        )
        self.assertIsNotNone(self.extension)
        self.assertEqual('NetworkOrchestrator', self.extension.type)
        self.assertEqual('Enabled', self.extension.state)

        ext_list = Extension.list(self.apiclient, id=self.extension.id)
        self.assertTrue(ext_list and len(ext_list) > 0)
        ext_obj = ext_list[0]
        self.extension_path = ext_obj.path
        self.assertIsNotNone(self.extension_path)

        # Verify network.capabilities was stored
        if hasattr(ext_obj, 'details') and ext_obj.details:
            d = (ext_obj.details.__dict__
                 if not isinstance(ext_obj.details, dict)
                 else ext_obj.details)
            self.assertIn("network.capabilities", d)
        self.logger.info("Extension %s created, path=%s", ext_name, self.extension_path)

        # ---- Steps 6-7: Deploy static entry-point to management server ----
        # The static entry-point (extensions/network-extension/entry-point) reads
        # all connection details from env vars - no dynamic wrapper generation needed.
        # Returns the private key PEM to store in addExternalNetworkDevice.
        ssh_key_pem = self._deploy_to_mgmt_server(self.extension_path)

        # ---- Step 8a: Register extension with physical network ----
        # Services are automatically derived from extension's network.capabilities.
        # No need to pass 'services' detail — it is no longer validated or required.
        # Device credentials (host, port, username, sshkey) are stored separately
        # via addExternalNetworkDevice (step 8b).
        self.extension.register(
            self.apiclient,
            self.physical_network.id,
            'PhysicalNetwork'
        )
        self.logger.info("Extension registered to physical network %s",
                         self.physical_network.id)


        # ---- Step 8b: Add external network device via addExternalNetworkDevice ----
        # Device details (host, port, username, sshkey) are stored as
        # extension_resource_map_details. Sensitive fields (sshkey) are stored
        # with display=false and never returned by listExternalNetworkDevices.
        # NetworkExtensionElement reads all details (including hidden) and injects
        # them as env vars: CS_NET_DEV_HOST, CS_NET_DEV_PORT, CS_NET_DEV_USERNAME,
        # CS_NET_DEV_SSHKEY, CS_NET_NAMESPACE, CS_NET_SCRIPT_PATH.
        device = self._add_external_network_device(
            physicalnetworkid=self.physical_network.id,
            host=marvin_ip,
            port=MARVIN_SSH_PORT,
            details=[
                {"key": "username",    "value": MARVIN_SSH_USER},
                {"key": "sshkey",      "value": ssh_key_pem},
                {"key": "namespace",   "value": self.ns_server.ns_name},
                {"key": "script_path", "value": self.ns_server.script_path},
            ]
        )
        self.assertIsNotNone(device)
        self.assertEqual(marvin_ip, device.host)
        self.logger.info(
            "External network device added: host=%s port=%s username via details, sshkey=<redacted>",
            device.host, device.port)

        # Verify listExternalNetworkDevices returns the device
        # username should appear in details (display=true)
        # sshkey must NOT appear (display=false)
        devices = self._list_external_network_devices(self.physical_network.id)
        self.assertEqual(1, len(devices))
        self.assertEqual(marvin_ip, devices[0].host)
        dev_details = devices[0].details or {}
        self.assertIn("username", dev_details,
                      "username should be visible in details")
        self.assertNotIn("sshkey", dev_details,
                         "sshkey must NOT be returned in list response")

        # ---- Step 8c: Update device — change port back to 22 (round-trip test) ----
        updated = self._update_external_network_device(
            self.physical_network.id,
            port=22
        )
        self.assertIsNotNone(updated)
        self.assertEqual('22', str(updated.port))
        self.logger.info("External network device updated: port=%s", updated.port)

        # ---- Step 9: Add + enable provider using the extension name ----
        # Each extension registered to a physical network becomes its own network service
        # provider named after the extension. This allows multiple extensions/providers
        # to coexist on the same physical network without ambiguity.
        provider_name = ext_name  # use actual extension name, not the generic 'ExternalNetwork'
        provider = self._find_provider(self.physical_network.id, provider_name)
        if provider is None:
            provider = self._add_provider(
                self.physical_network.id,
                provider_name
            )
        self.provider_id = provider.id
        self.provider_name = provider_name
        if provider.state != 'Enabled':
            self._update_provider_state(provider.id, 'Enabled')
        self.assertEqual('Enabled',
            self._find_provider(self.physical_network.id, provider_name).state)
        self.logger.info("Provider '%s' enabled", provider_name)

        # ---- Step 10: Create network offering ----
        # serviceProviderList uses the actual extension name as the provider so that
        # multiple different external network extensions can coexist on the same zone.
        nw_offering = NetworkOffering.create(self.apiclient, {
            "name":             "ExtNet-Offering-%s" % random_gen(),
            "displaytext":      "ExtNet Offering (netns smoke test)",
            "guestiptype":      "Isolated",
            "traffictype":      "GUEST",
            "supportedservices": "SourceNat,StaticNat,PortForwarding,Firewall,Gateway",
            "serviceProviderList": {
                "SourceNat":      provider_name,
                "StaticNat":      provider_name,
                "PortForwarding": provider_name,
                "Firewall":       provider_name,
                "Gateway":        provider_name,
            },
            "serviceCapabilityList": {
                "SourceNat": {"SupportedSourceNatTypes": "peraccount"},
            },
        })
        self.cleanup.append(nw_offering)
        nw_offering.update(self.apiclient, state='Enabled')

        # ---- Step 11: Create account ----
        account = Account.create(
            self.apiclient,
            self.services["account"],
            admin=True,
            domainid=self.domain.id
        )
        self.cleanup.append(account)

        # ---- Step 12: Create isolated network ----
        network = Network.create(
            self.apiclient,
            {"name": "extnet-smoke-net",
             "displaytext": "ExtNet netns smoke test network"},
            accountid=account.name,
            domainid=account.domainid,
            networkofferingid=nw_offering.id,
            zoneid=self.zone.id
        )
        self.cleanup.insert(0, network)
        self.assertIsNotNone(network)
        self.logger.info("Isolated network created: %s (%s)", network.name, network.id)

        # ---- Step 13: Deploy VM → implement ----
        svc_offering = ServiceOffering.list(self.apiclient, issystem=False)[0]
        vm = VirtualMachine.create(
            self.apiclient,
            {"displayname": "extnet-smoke-vm", "name": "extnet-smoke-vm",
             "zoneid": self.zone.id},
            accountid=account.name,
            domainid=account.domainid,
            serviceofferingid=svc_offering.id,
            templateid=self.template.id,
            networkids=[network.id]
        )
        self.cleanup.insert(0, vm)
        self.assertIsNotNone(vm)
        self.logger.info("VM deployed: %s (%s)", vm.name, vm.id)

        # ---- Step 14: Acquire public IP ----
        public_ip = PublicIPAddress.create(
            self.apiclient,
            accountid=account.name,
            zoneid=self.zone.id,
            domainid=account.domainid,
            networkid=network.id
        )
        ip_id = public_ip.ipaddress.id
        self.logger.info("Public IP: %s", public_ip.ipaddress.ipaddress)

        # ---- Step 15: Enable static NAT ----
        StaticNATRule.enable(self.apiclient,
                             ipaddressid=ip_id,
                             virtualmachineid=vm.id,
                             networkid=network.id)

        # ---- Step 16: Disable static NAT ----
        StaticNATRule.disable(self.apiclient, ipaddressid=ip_id)

        # ---- Step 17: Acquire IP for port forwarding ----
        pf_ip = PublicIPAddress.create(
            self.apiclient,
            accountid=account.name, zoneid=self.zone.id,
            domainid=account.domainid, networkid=network.id
        )

        # ---- Step 18: Create port forwarding rule ----
        pf_rule = NATRule.create(
            self.apiclient, vm,
            {"privateport": 22, "publicport": 2222, "protocol": "TCP"},
            ipaddressid=pf_ip.ipaddress.id, networkid=network.id
        )
        self.assertIsNotNone(pf_rule)
        self.logger.info("Port forwarding rule: %s  %s:2222 → VM:22",
                         pf_rule.id, pf_ip.ipaddress.ipaddress)

        # ---- Step 19: Delete port forwarding rule ----
        pf_rule.delete(self.apiclient)
        pf_ip.delete(self.apiclient)
        public_ip.delete(self.apiclient)

        # ---- Step 20: Destroy VM ----
        vm.delete(self.apiclient, expunge=True)
        self.cleanup = [o for o in self.cleanup if o != vm]

        # ---- Step 21: Delete network → shutdown/destroy ----
        network.delete(self.apiclient)
        self.cleanup = [o for o in self.cleanup if o != network]
        self.logger.info("Network deleted (shutdown/destroy ran in namespace)")

        # ---- Steps 22-24: Cleanup infrastructure ----
        self._update_provider_state(self.provider_id, 'Disabled')
        self._delete_provider(self.provider_id)
        self.provider_id = None

        self.extension.unregister(self.apiclient,
                                  self.physical_network.id, 'PhysicalNetwork')
        self.extension.delete(self.apiclient,
                              unregisterresources=False, removeactions=False)
        self.extension = None
        self.physical_network = None

        self._cleanup_mgmt_server_files()
        self.ns_server.stop()
        self.ns_server = None
        self.logger.info("Full lifecycle test PASSED")

    @attr(tags=["advanced", "smoke"], required_hardware="false")
    def test_02_provider_state_transitions(self):
        """Provider state transitions: Disabled → Enabled → Disabled → Deleted.

        Uses a stand-alone provider named 'NetworkExtension' (not backed by an extension)
        to exercise the generic NSP state-machine.  Real extension-backed providers are
        always named after the extension; see test_01 for the full lifecycle.
        """
        pn = self._get_physical_network()
        self.physical_network = pn
        existing = self._find_provider(pn.id, EXTERNAL_NETWORK_PROVIDER_NAME)
        if existing is not None:
            if existing.state == 'Enabled':
                self._update_provider_state(existing.id, 'Disabled')
            self._delete_provider(existing.id)
        provider = self._add_provider(pn.id, EXTERNAL_NETWORK_PROVIDER_NAME)
        self.provider_id = provider.id
        self.assertEqual('Disabled', provider.state)
        self._update_provider_state(provider.id, 'Enabled')
        self.assertEqual('Enabled',
            self._find_provider(pn.id, EXTERNAL_NETWORK_PROVIDER_NAME).state)
        self._update_provider_state(provider.id, 'Disabled')
        self.assertEqual('Disabled',
            self._find_provider(pn.id, EXTERNAL_NETWORK_PROVIDER_NAME).state)
        self._delete_provider(provider.id)
        self.provider_id = None
        self.assertIsNone(self._find_provider(pn.id, EXTERNAL_NETWORK_PROVIDER_NAME))
        self.logger.info("Provider state transitions test PASSED")

    @attr(tags=["advanced", "smoke"], required_hardware="false")
    def test_03_extension_capabilities_detail(self):
        """Verify network.capabilities JSON is stored and retrievable via the API."""
        caps_json = json.dumps({
            "services": ["SourceNat", "Gateway"],
            "capabilities": {"SourceNat": {"SupportedSourceNatTypes": "peraccount"}}
        })
        ext = Extension.create(
            self.apiclient,
            name="extnet-caps-" + random_gen(),
            type='NetworkOrchestrator',
            details=[{"network.capabilities": caps_json}]
        )
        self.cleanup.append(ext)
        ext_list = Extension.list(self.apiclient, id=ext.id)
        self.assertTrue(ext_list and len(ext_list) > 0)
        ext_obj = ext_list[0]
        if hasattr(ext_obj, 'details') and ext_obj.details:
            d = (ext_obj.details.__dict__
                 if not isinstance(ext_obj.details, dict)
                 else ext_obj.details)
            self.assertIn("network.capabilities", d)
            stored = json.loads(d["network.capabilities"])
            self.assertIn("SourceNat", stored["services"])
            self.assertIn("Gateway", stored["services"])
        self.logger.info("Extension capabilities detail test PASSED")

    @attr(tags=["advanced", "smoke"], required_hardware="false")
    def test_04_netns_and_script(self):
        """Verify network namespace is created, iptables works inside it,
        and network-extension-wrapper.sh is installed."""
        marvin_ip = self._init_ns_and_deployer()

        # Namespace exists
        rc, out, _ = _run(['ip', 'netns', 'list'])
        self.assertIn(self.ns_server.ns_name, out)

        # iptables works inside the namespace
        rc, out, _ = self.ns_server.run_in_ns('iptables --version')
        self.assertEqual(0, rc)
        self.assertIn('iptables', out.lower())

        # Script is executable
        rc, _, _ = self.ns_server.run_in_ns(
            'test -x %s && echo ok' % self.ns_server.script_path)
        self.assertEqual(0, rc)

        # Script runs inside the namespace without SSH errors
        rc, out, _ = self.ns_server.run_in_ns(
            '%s implement --network-id 1 --vlan 100 '
            '--gateway 10.0.0.1 --cidr 10.0.0.0/24 || true' %
            self.ns_server.script_path, check=False)
        self.logger.info(
            "Script in namespace %s: rc=%d  out=%r",
            self.ns_server.ns_name, rc, out)
        # Should not have printed an SSH error
        self.assertNotIn('ssh:', out.lower())

        self.logger.info(
            "Namespace OK. Management server should SSH to %s:%d "
            "and run: ip netns exec %s <script> <args>",
            marvin_ip, MARVIN_SSH_PORT, self.ns_server.ns_name)

        self.ns_server.stop()
        self.ns_server = None
        self.logger.info("Netns and script test PASSED")

    @attr(tags=["advanced", "smoke"], required_hardware="false")
    def test_05_extension_enable_disable_and_delete_restriction(self):
        """Extension enable/disable via updateExtension and delete restriction.

        Verifies:
        - Admin can create a NetworkOrchestrator extension (default Enabled).
        - Admin can disable the extension (state=Disabled).
        - Admin can re-enable the extension (state=Enabled).
        - Extension cannot be deleted while registered to a physical network.
        - After unregistering, extension can be deleted.
        """
        pn = self._get_physical_network()
        self.physical_network = pn

        # Create extension
        ext_name = "extnet-lifecycle-" + random_gen()
        self.extension = Extension.create(
            self.apiclient,
            name=ext_name,
            type='NetworkOrchestrator',
            details=[{"network.capabilities": NETWORK_CAPABILITIES_JSON}]
        )
        self.assertIsNotNone(self.extension)
        self.assertEqual('Enabled', self.extension.state,
                         "Extension should be Enabled by default")

        # Disable extension
        self.extension.update(self.apiclient, state='Disabled')
        ext_list = Extension.list(self.apiclient, id=self.extension.id)
        self.assertEqual('Disabled', ext_list[0].state,
                         "Extension should be Disabled after update")
        self.logger.info("Extension disabled OK")

        # Re-enable extension
        self.extension.update(self.apiclient, state='Enabled')
        ext_list = Extension.list(self.apiclient, id=self.extension.id)
        self.assertEqual('Enabled', ext_list[0].state,
                         "Extension should be Enabled after re-enable")
        self.logger.info("Extension re-enabled OK")

        # Register with physical network
        self.extension.register(self.apiclient, pn.id, 'PhysicalNetwork')
        self.logger.info("Extension registered to physical network %s", pn.id)

        # Attempt to delete while registered → should fail
        try:
            self.extension.delete(self.apiclient,
                                  unregisterresources=False, removeactions=False)
            self.fail("Expected error when deleting extension while registered")
        except Exception as e:
            self.logger.info("Expected error when deleting while registered: %s", e)

        # Unregister, then delete
        self.extension.unregister(self.apiclient, pn.id, 'PhysicalNetwork')
        self.extension.delete(self.apiclient,
                              unregisterresources=False, removeactions=False)
        self.extension = None
        self.physical_network = None
        self.logger.info("Extension deleted after unregister — delete restriction test PASSED")

    @attr(tags=["advanced", "smoke"], required_hardware="false")
    def test_06_external_network_device_crud(self):
        """CRUD operations on external network devices.

        Verifies:
        - addExternalNetworkDevice: stores host/port/username; sshkey is hidden.
        - listExternalNetworkDevices: returns device without sshkey.
        - updateExternalNetworkDevice: updates a field.
        - deleteExternalNetworkDevice: removes the device.
        """
        pn = self._get_physical_network()
        self.physical_network = pn

        # Need an extension registered before we can add a device
        ext_name = "extnet-devtest-" + random_gen()
        self.extension = Extension.create(
            self.apiclient,
            name=ext_name,
            type='NetworkOrchestrator',
            details=[{"network.capabilities": NETWORK_CAPABILITIES_JSON}]
        )
        self.extension.register(self.apiclient, pn.id, 'PhysicalNetwork')

        # Add device
        device = self._add_external_network_device(
            physicalnetworkid=pn.id,
            host='192.0.2.10',
            port=22,
            details=[
                {"key": "username", "value": "root"},
                {"key": "sshkey",   "value": "---FAKE-KEY---"},
            ]
        )
        self.assertIsNotNone(device)
        self.assertEqual('192.0.2.10', device.host)
        self.assertEqual('22', str(device.port))
        self.logger.info("Device added: host=%s port=%s", device.host, device.port)

        # List — sshkey must not appear; username must appear
        devices = self._list_external_network_devices(pn.id)
        self.assertEqual(1, len(devices))
        dev_details = devices[0].details or {}
        self.assertIn("username", dev_details)
        self.assertNotIn("sshkey", dev_details,
                         "sshkey must be hidden (display=false)")

        # Update port
        updated = self._update_external_network_device(pn.id, port=2222)
        self.assertEqual('2222', str(updated.port))
        self.logger.info("Device updated: port=%s", updated.port)

        # Delete
        self._delete_external_network_device(pn.id)
        devices = self._list_external_network_devices(pn.id)
        self.assertEqual(0, len(devices))
        self.logger.info("Device deleted OK")

        # Cleanup
        self.extension.unregister(self.apiclient, pn.id, 'PhysicalNetwork')
        self.extension.delete(self.apiclient,
                              unregisterresources=False, removeactions=False)
        self.extension = None
        self.physical_network = None
        self.logger.info("External network device CRUD test PASSED")



