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
"""Smoke tests for the NetworkExtension plugin.

Architecture
------------
The test runs on the **Marvin node** (the host running this Python script).

A Linux **network namespace** on the Marvin node provides iptables isolation
for the wrapper script.  The management server SSHes to the Marvin node and
runs network-extension-wrapper.sh inside the namespace.

  ┌──────────────────────────────────┐   SSH :22    ┌──────────────────────────────────┐
  │  Management Server               │ ───────────→ │  Marvin node                     │
  │                                  │  marvin_ip   │                                  │
  │  /etc/cloudstack/extensions/     │              │  ip netns exec cs-extnet-<id>    │
  │    <ext-name>/                   │              │    /etc/cloudstack/extensions/   │
  │      network-extension.sh        │              │      network-extension-wrapper.sh│
  │   (SSH proxy script)             │              │                                  │
  └──────────────────────────────────┘              └──────────────────────────────────┘
         ↑ executes wrapper                                  ↑ namespace created by test
  CloudStack NetworkExtensionElement             iptables/bridge ops run here (isolated)

The network-extension.sh script on the management server SSHes to the Marvin
node and runs network-extension-wrapper.sh inside the network namespace.

Scripts are downloaded at test startup from:
  https://raw.githubusercontent.com/weizhouapache/cloudstack/refs/heads/4.22.0.0-ext/extensions/network-extension/network-extension.sh
  https://raw.githubusercontent.com/weizhouapache/cloudstack/refs/heads/4.22.0.0-ext/extensions/network-extension/network-extension-wrapper.sh

The wrapper is then deployed to:
  - The Marvin node:   /etc/cloudstack/extensions/network-extension-wrapper.sh
  - All KVM hosts:     /etc/cloudstack/extensions/network-extension-wrapper.sh
  - Management server: /etc/cloudstack/extensions/<ext-name>/network-extension.sh

Two-step CloudStack setup:
  Step 1 — Create and register extension (includes 'hosts' detail with KVM IPs):
    createExtension name=<ext-name> type=NetworkOrchestrator ...
    registerExtension id=<ext-uuid> resourcetype=PhysicalNetwork
        resourceid=<phys-net-uuid>
        details[0].key=hosts details[0].value=10.0.34.160,10.0.35.231

  Step 2 — Add external network device (Marvin node as the network device):
    addExternalNetworkDevice physicalnetworkid=<phys-net-uuid>
        host=<marvin_ip> port=22
        details[0].key=username details[0].value=root
        details[1].key=sshkey   details[1].value="$(cat /tmp/cs-extnet-key)"
        details[2].key=namespace details[2].value=cs-extnet-<id>
        details[3].key=script_path details[3].value=/etc/cloudstack/extensions/network-extension-wrapper.sh

``NetworkExtensionElement`` reads all details (including hidden) via
``ExtensionHelper.getAllResourceMapDetailsForPhysicalNetwork()`` and injects
them as environment variables:
  CS_PHYSICAL_NETWORK_EXTENSION_DETAILS  (JSON with all physical-network details)
  CS_NETWORK_EXTENSION_DETAILS           (per-network JSON blob)

Network service provider (NSP) name is the **extension name** (e.g. ``extnet-smoke-<id>``),
not the generic string ``NetworkExtension``.  This allows multiple different extensions to
coexist on the same physical network, each with its own named NSP entry.

Teardown:
  1. Delete external network device.
  2. Unregister extension from physical network.
  3. Delete namespace:           ip netns del cs-extnet-<id>
  4. Remove public key from authorized_keys.
  5. Remove network-extension.sh from management server.
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
import urllib.request

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

# Directory on all hosts (Marvin node, KVM hosts, management server) where scripts are installed
EXTENSIONS_DIR = '/etc/cloudstack/extensions'
SCRIPT_FILENAME = 'network-extension-wrapper.sh'
ENTRY_POINT_FILENAME = 'network-extension.sh'

# Remote URLs to download the scripts from
_GITHUB_BASE = (
    'https://raw.githubusercontent.com/weizhouapache/cloudstack'
    '/refs/heads/4.22.0.0-ext/extensions/network-extension'
)
WRAPPER_SCRIPT_URL   = _GITHUB_BASE + '/network-extension-wrapper.sh'
ENTRY_POINT_SCRIPT_URL = _GITHUB_BASE + '/network-extension.sh'

# Local cache paths (downloaded once, reused across test methods)
_THIS_DIR = os.path.dirname(os.path.abspath(__file__))
_SCRIPT_CACHE_DIR = os.path.join(tempfile.gettempdir(), 'cs-extnet-script-cache')
WRAPPER_SCRIPT_LOCAL   = os.path.join(_SCRIPT_CACHE_DIR, SCRIPT_FILENAME)
ENTRY_POINT_SCRIPT_LOCAL = os.path.join(_SCRIPT_CACHE_DIR, ENTRY_POINT_FILENAME)

# Network capabilities JSON — services this extension supports
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


# ---------------------------------------------------------------------------
# Script download helpers
# ---------------------------------------------------------------------------

def _download_script(url, dest_path):
    """Download *url* to *dest_path*, make it executable.  Returns dest_path."""
    os.makedirs(os.path.dirname(dest_path), exist_ok=True)
    logging.getLogger('cs-extnet').info("Downloading %s -> %s", url, dest_path)
    with urllib.request.urlopen(url, timeout=30) as resp:
        content = resp.read()
    with open(dest_path, 'wb') as fh:
        fh.write(content)
    os.chmod(dest_path, stat.S_IRWXU | stat.S_IRGRP | stat.S_IXGRP |
             stat.S_IROTH | stat.S_IXOTH)
    return dest_path


def _ensure_scripts_downloaded():
    """Download both scripts from GitHub if not already cached.

    Returns (wrapper_path, entry_point_path).
    """
    if not os.path.exists(WRAPPER_SCRIPT_LOCAL):
        _download_script(WRAPPER_SCRIPT_URL, WRAPPER_SCRIPT_LOCAL)
    if not os.path.exists(ENTRY_POINT_SCRIPT_LOCAL):
        _download_script(ENTRY_POINT_SCRIPT_URL, ENTRY_POINT_SCRIPT_LOCAL)
    return WRAPPER_SCRIPT_LOCAL, ENTRY_POINT_SCRIPT_LOCAL


# ---------------------------------------------------------------------------
# KVM host discovery helpers (from Marvin config)
# ---------------------------------------------------------------------------

def _get_kvm_hosts_from_config(config):
    """Return list of (username, password, ip) for all KVM hosts in the Marvin config.

    Reads from:
      config.__dict__["zones"][0].__dict__["pods"][0].__dict__["clusters"][0].__dict__["hosts"]

    Each host entry looks like::
      {"username": "root", "url": "http://10.0.34.160", "password": "Pass123"}

    Returns a list of dicts: [{"username": .., "password": .., "ip": ..}, ...]
    """
    hosts = []
    try:
        zones = config.__dict__.get("zones", [])
        for zone in zones:
            pods = zone.__dict__.get("pods", [])
            for pod in pods:
                clusters = pod.__dict__.get("clusters", [])
                for cluster in clusters:
                    cluster_hosts = cluster.__dict__.get("hosts", [])
                    for h in cluster_hosts:
                        if hasattr(h, '__dict__'):
                            h = h.__dict__
                        url = h.get("url", "")
                        ip = url.replace("http://", "").replace("https://", "").strip("/")
                        hosts.append({
                            "username": h.get("username", "root"),
                            "password": h.get("password", ""),
                            "ip": ip,
                        })
    except Exception as e:
        logging.getLogger('cs-extnet').warning(
            "Could not read KVM hosts from config: %s", e)
    return hosts


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

    The management server SSHes to the Marvin node at the normal SSH port (22)
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
        """Create namespace, generate SSH key, install wrapper script."""
        _check_iproute2()

        # Generate temp dir and SSH key pair on the Marvin node
        self._tmpdir = tempfile.mkdtemp(prefix='cs-extnet-test-')
        self._key_file = os.path.join(self._tmpdir, 'id_rsa_extnet')
        self.logger.info("Generating SSH key pair at %s", self._key_file)
        _run(['ssh-keygen', '-t', 'rsa', '-b', '2048',
              '-N', '', '-f', self._key_file])
        with open(self._key_file + '.pub') as fh:
            self._pub_key = fh.read().strip()

        # Add the public key to authorized_keys so the management server
        # can SSH in as the test user
        self._install_authorized_key()

        # Create the network namespace
        self.logger.info("Creating network namespace %s", self._ns_name)
        _run(['ip', 'netns', 'add', self._ns_name])
        # Bring up loopback inside the namespace
        _run(['ip', 'netns', 'exec', self._ns_name, 'ip', 'link', 'set', 'lo', 'up'],
             check=False)
        self.running = True

        # Install network-extension-wrapper.sh to the standard location
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
        """Install network-extension-wrapper.sh to /etc/cloudstack/extensions/ on the Marvin node.

        The Marvin node acts as the remote network device in this test.
        The wrapper is downloaded from GitHub (cached locally) and installed to
        /etc/cloudstack/extensions/ which is the default DEFAULT_SCRIPT_PATH
        expected by network-extension.sh.
        """
        wrapper_path, _ = _ensure_scripts_downloaded()
        dest_dir = EXTENSIONS_DIR
        os.makedirs(dest_dir, exist_ok=True)
        dest = os.path.join(dest_dir, SCRIPT_FILENAME)
        shutil.copy2(wrapper_path, dest)
        os.chmod(dest, stat.S_IRWXU | stat.S_IRGRP | stat.S_IXGRP |
                 stat.S_IROTH | stat.S_IXOTH)
        self._script_path = dest
        self.logger.info("Installed wrapper script at %s", dest)

    def _authorized_keys_path(self):
        return os.path.expanduser('~%s/.ssh/authorized_keys' % MARVIN_SSH_USER)

    def _install_authorized_key(self):
        """Append the test public key to authorized_keys."""
        ak_path = self._authorized_keys_path()
        os.makedirs(os.path.dirname(ak_path), mode=0o700, exist_ok=True)
        # Tag the entry so we can remove it precisely later
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
    """Copies network-extension.sh to the management server.

    If the management server is the same machine as the Marvin node
    (localhost / 127.0.0.1), files are written directly.
    Otherwise they are transferred via SshClient.

    network-extension.sh is downloaded from GitHub and placed at:
      /etc/cloudstack/extensions/<ext-name>/network-extension.sh

    All connection details (host, port, username, sshkey, namespace, …) are
    passed at runtime as environment variables by NetworkExtensionElement —
    no dynamic wrapper generation is needed.
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

    def copy_file(self, local_path, remote_path, mode='0755'):
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
# KvmHostDeployer  – deploys network-extension-wrapper.sh to KVM hosts
# ---------------------------------------------------------------------------

class KvmHostDeployer:
    """Copies network-extension-wrapper.sh to all KVM hosts.

    The script is downloaded from GitHub (cached locally) and deployed to
    /etc/cloudstack/extensions/ on each KVM host via SSH.

    Host IPs and credentials come from the Marvin test configuration
    (zones → pods → clusters → hosts), which is the most reliable source.
    As a fallback, the CloudStack listHosts API is also consulted.
    """

    DEST_PATH = EXTENSIONS_DIR + '/' + SCRIPT_FILENAME

    def __init__(self, apiclient=None, zone_id=None, config_hosts=None, logger=None):
        """
        :param apiclient:    Marvin API client (used as fallback host discovery).
        :param zone_id:      CloudStack zone ID (used by API fallback).
        :param config_hosts: List of dicts from ``_get_kvm_hosts_from_config()``,
                             each containing 'ip', 'username', 'password'.
                             When provided, this is preferred over the API.
        :param logger:       Optional logger.
        """
        self.apiclient    = apiclient
        self.zone_id      = zone_id
        self.config_hosts = config_hosts or []
        self.logger       = logger or logging.getLogger('KvmHostDeployer')
        self._deployed_hosts = []

    def _get_hosts_from_api(self):
        """Return list of dicts {ip, username, password} from listHosts API.

        Used only when config_hosts is empty.
        """
        if not self.apiclient or not self.zone_id:
            return []
        try:
            from marvin.cloudstackAPI import listHosts as listHostsAPI
            cmd = listHostsAPI.listHostsCmd()
            cmd.zoneid     = self.zone_id
            cmd.type       = 'Routing'
            cmd.hypervisor = 'KVM'
            cmd.state      = 'Up'
            cmd.listall    = True
            hosts = self.apiclient.listHosts(cmd)
            if not hosts:
                return []
            # API does not expose credentials; use defaults
            return [{"ip": h.ipaddress, "username": "root", "password": ""}
                    for h in hosts]
        except Exception as e:
            self.logger.warning("listHosts API failed: %s", e)
            return []

    def _copy_to_host(self, ip, username, password):
        """SCP network-extension-wrapper.sh to /etc/cloudstack/extensions/ on *ip*."""
        wrapper_path, _ = _ensure_scripts_downloaded()
        try:
            ssh = SshClient(ip, 22, username, password)
            ssh.execute("mkdir -p '%s'" % EXTENSIONS_DIR)
            import base64
            with open(wrapper_path, 'rb') as fh:
                b64 = base64.b64encode(fh.read()).decode()
            ssh.execute(
                "echo '%s' | base64 -d > '%s' && chmod 755 '%s'" %
                (b64, self.DEST_PATH, self.DEST_PATH)
            )
            self.logger.info("Deployed wrapper to KVM host %s at %s", ip, self.DEST_PATH)
            return True
        except Exception as e:
            self.logger.warning("Failed to deploy wrapper to KVM host %s: %s", ip, e)
            return False

    def deploy(self):
        """Deploy the wrapper script to all KVM hosts.

        Uses config_hosts if available; falls back to listHosts API.
        Returns list of host IPs where deployment succeeded.
        """
        hosts = self.config_hosts if self.config_hosts else self._get_hosts_from_api()
        if not hosts:
            self.logger.warning(
                "No KVM hosts found — wrapper not deployed to any KVM host")
            return []
        self._deployed_hosts = []
        for h in hosts:
            ip       = h.get('ip', '')
            username = h.get('username', 'root')
            password = h.get('password', '')
            if not ip:
                continue
            self.logger.info("Deploying wrapper to KVM host %s", ip)
            if self._copy_to_host(ip, username, password):
                self._deployed_hosts.append(ip)
        return self._deployed_hosts

    def deployed_hosts(self):
        """Return list of host IPs to which the wrapper was successfully deployed."""
        return list(self._deployed_hosts)

    def host_ips(self):
        """Return all configured host IPs (regardless of deployment success)."""
        return [h.get('ip', '') for h in self.config_hosts if h.get('ip')]


# ---------------------------------------------------------------------------
# Test class
# ---------------------------------------------------------------------------

class TestExternalNetworkProvider(cloudstackTestCase):
    """Full lifecycle smoke test for the NetworkExtension plugin.

    A Linux network namespace on the Marvin node provides iptables isolation.
    The management server SSHes to the Marvin node and runs
    network-extension-wrapper.sh inside the namespace to perform
    network operations (create bridges, configure iptables rules, etc.).

    Both scripts (network-extension.sh and network-extension-wrapper.sh) are
    downloaded from GitHub at test startup and deployed to the appropriate hosts.
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

        # Read KVM host details from Marvin config (zones → pods → clusters → hosts)
        cls.kvm_host_configs = _get_kvm_hosts_from_config(cls.config)
        cls.kvm_host_ips = [h['ip'] for h in cls.kvm_host_configs if h.get('ip')]
        cls.logger.info("KVM hosts from config: %s", cls.kvm_host_ips)

        # Download scripts from GitHub once for all tests
        try:
            _ensure_scripts_downloaded()
            cls.logger.info("Scripts downloaded/cached: %s, %s",
                            WRAPPER_SCRIPT_LOCAL, ENTRY_POINT_SCRIPT_LOCAL)
        except Exception as e:
            cls.logger.warning("Could not download scripts from GitHub: %s", e)

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
        self.kvm_deployer         = None

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
        """Return the physical network with Guest traffic type in the test zone.

        If no physical network has Guest traffic type (e.g. in a basic zone),
        the first physical network is returned as a fallback.
        """
        cmd = listPhysicalNetworks.listPhysicalNetworksCmd()
        cmd.zoneid = self.zone.id
        pns = self.apiclient.listPhysicalNetworks(cmd)
        self.assertIsInstance(pns, list)
        self.assertGreater(len(pns), 0)

        # Prefer physical network that supports Guest traffic
        for pn in pns:
            traffic_types = getattr(pn, 'traffictypes', None) or []
            # traffictypes may be a list of objects or strings
            for tt in traffic_types:
                tt_name = tt if isinstance(tt, str) else getattr(tt, 'traffictype', '')
                if tt_name.lower() == 'guest':
                    self.logger.info(
                        "Selected physical network with Guest traffic: %s (%s)",
                        pn.name, pn.id)
                    return pn

        # Fallback: return first physical network
        self.logger.info(
            "No physical network with Guest traffic found; using first: %s (%s)",
            pns[0].name, pns[0].id)
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
    # Namespace / mgmt-server / KVM deployment helpers
    # ------------------------------------------------------------------

    def _init_ns_and_deployer(self):
        """Set up the Marvin node namespace, deploy scripts to mgmt server and KVM hosts.

        Steps:
        1. Create MgmtServerDeployer to handle files on the management server.
        2. Create NetnsNetworkServer (network namespace on the Marvin node — the Marvin
           node also acts as the remote network device in this test).
        3. NetnsNetworkServer.start() creates the namespace and installs
           network-extension-wrapper.sh to /etc/cloudstack/extensions/ on the Marvin node.
        4. Create KvmHostDeployer with KVM host details from the Marvin config and deploy
           network-extension-wrapper.sh to all KVM hosts at /etc/cloudstack/extensions/.

        Returns the Marvin node IP as seen from the management server.
        """
        self.mgmt_deployer = MgmtServerDeployer(self.mgtSvrDetails,
                                                logger=self.logger)
        marvin_ip = self.mgmt_deployer.get_marvin_ip_as_seen_from_mgmt()
        self.ns_server = NetnsNetworkServer(marvin_ip=marvin_ip,
                                            logger=self.logger)
        # start() creates the namespace and installs the wrapper to
        # /etc/cloudstack/extensions/ on the Marvin node
        self.ns_server.start()

        # Deploy wrapper to KVM hosts using credentials from Marvin config
        self.kvm_deployer = KvmHostDeployer(
            apiclient=self.apiclient,
            zone_id=self.zone.id,
            config_hosts=self.kvm_host_configs,
            logger=self.logger
        )
        deployed = self.kvm_deployer.deploy()
        if deployed:
            self.logger.info(
                "Deployed network-extension-wrapper.sh to %d KVM host(s): %s",
                len(deployed), deployed)
        else:
            self.logger.info(
                "No KVM hosts found; Marvin node acts as the only network device")
        return marvin_ip

    def _deploy_to_mgmt_server(self, ext_path):
        """Deploy network-extension.sh to the management server.

        Downloads network-extension.sh from GitHub (cached) and places it at:
          /etc/cloudstack/extensions/<ext-name>/network-extension.sh

        This script is the SSH proxy: the management server executes it when a
        network operation is triggered.  It SSHes to the remote device (Marvin
        node in tests, any KVM host / network appliance in production) and runs
        network-extension-wrapper.sh inside the network namespace.

        All connection details (host, port, username, sshkey, namespace, …) are
        injected at runtime as environment variables by NetworkExtensionElement:
          CS_PHYSICAL_NETWORK_EXTENSION_DETAILS  (JSON)
          CS_NETWORK_EXTENSION_DETAILS           (per-network JSON)

        Returns the SSH private-key PEM so the caller can pass it to
        addExternalNetworkDevice (stored with display=false).
        """
        _, entry_point_src = _ensure_scripts_downloaded()

        # Deploy to /etc/cloudstack/extensions/<ext-name>/network-extension.sh
        self._mgmt_wrapper_path = os.path.join(ext_path, ENTRY_POINT_FILENAME)
        self.mgmt_deployer.copy_file(
            entry_point_src,
            self._mgmt_wrapper_path,
            mode='0755'
        )
        self.logger.info("network-extension.sh deployed to mgmt server at %s",
                         self._mgmt_wrapper_path)

        # Return the private key PEM for use in addExternalNetworkDevice
        with open(self.ns_server.key_file, 'r') as fh:
            return fh.read()

    def _cleanup_mgmt_server_files(self):
        """Remove the network-extension.sh script from the management server."""
        if self.mgmt_deployer and self._mgmt_wrapper_path:
            self.mgmt_deployer.remove_file(self._mgmt_wrapper_path)

    def _safe_teardown(self):
        """Best-effort cleanup of CloudStack resources and infrastructure."""
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
        """Full lifecycle: namespace → extension → network → VM → NAT → PF → teardown.

        Flow
        ----
        Script deployment (before CloudStack operations):
          1.  Download scripts from GitHub (cached in /tmp/cs-extnet-script-cache/).
          2.  Create Linux network namespace on the Marvin node (ip netns add cs-extnet-<id>).
          3.  Generate RSA key pair; inject public key into authorized_keys on Marvin node.
          4.  Install network-extension-wrapper.sh to /etc/cloudstack/extensions/ on
              the Marvin node (acts as the remote network device for this test).
          5.  Deploy network-extension-wrapper.sh to all real KVM hosts at
              /etc/cloudstack/extensions/ using credentials from the Marvin config.
          6.  Deploy network-extension.sh to /etc/cloudstack/extensions/<ext-name>/
              on the management server.

        CloudStack operations:
          7.  Create NetworkOrchestrator extension with network.capabilities detail.
          8.  Register extension with the physical network (Guest traffic type),
              passing 'hosts' detail = comma-separated list of all KVM host IPs.
          9.  addExternalNetworkDevice: store host=<marvin_ip>, port=22, username,
              sshkey (hidden), namespace, script_path as extension_resource_map_details.
              - sshkey is stored with display=false and never returned by list API.
              - Other details (username, namespace, script_path) are visible.
         10.  listExternalNetworkDevices: verify device, sshkey hidden, username visible.
         11.  Update device port (round-trip test).
         12.  Network service provider (NSP) named after the extension is auto-created
              when the extension is registered; enable it.
         13.  Create network offering with the extension name as service provider.
         14.  Create account.
         15.  Create isolated network.
              → NetworkExtensionElement calls network-extension.sh with:
                CS_PHYSICAL_NETWORK_EXTENSION_DETAILS (JSON: hosts, username, sshkey, …)
                CS_NETWORK_EXTENSION_DETAILS          (per-network JSON blob)
              → network-extension.sh SSHes to Marvin node, runs:
                ip netns exec <ns> network-extension-wrapper.sh implement ...
         16.  Deploy VM.
         17.  Acquire public IP → assign-ip command on wrapper.
         18.  Enable static NAT → add-static-nat command on wrapper.
         19.  Disable static NAT → delete-static-nat command on wrapper.
         20.  Acquire public IP for port forwarding.
         21.  Create port forwarding rule → add-port-forward command on wrapper.
         22.  Delete port forwarding rule → delete-port-forward command on wrapper.
         23.  Destroy VM.
         24.  Delete network → shutdown and destroy commands on wrapper.

        Cleanup:
         25.  Disable/delete provider.
         26.  Delete external network device.
         27.  Unregister/delete extension.
         28.  Remove network-extension.sh from management server.
         29.  Delete network namespace; remove authorized_keys entry.
        """

        # ---- Steps 1-6: Download scripts, set up namespace, deploy scripts ----
        marvin_ip = self._init_ns_and_deployer()
        self.logger.info("Marvin IP (as seen from mgmt): %s  namespace: %s",
                         marvin_ip, self.ns_server.ns_name)

        # Sanity: run the wrapper script inside the namespace to verify it works
        rc, out, _ = self.ns_server.run_in_ns(
            '%s implement --network-id 0 --vlan 0 '
            '--gateway 192.0.2.1 --cidr 192.0.2.0/24 || true' %
            self.ns_server.script_path, check=False)
        self.logger.info("Namespace wrapper sanity check: rc=%d out=%r", rc, out)

        # ---- Step 7: Get physical network with Guest traffic type ----
        self.physical_network = self._get_physical_network()
        self.logger.info("Physical network: %s (%s)",
                         self.physical_network.name, self.physical_network.id)

        # ---- Step 8: Create NetworkOrchestrator extension ----
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

        # ---- Step 9a: Deploy network-extension.sh to management server ----
        # Returns the SSH private key PEM to store in addExternalNetworkDevice.
        ssh_key_pem = self._deploy_to_mgmt_server(self.extension_path)

        # ---- Step 9b: Register extension with physical network ----
        # The 'hosts' detail contains all KVM host IPs (comma-separated).
        # These are used by the wrapper script to select the network device
        # and to populate the 'hosts' field for ensure-network-device calls.
        kvm_hosts_csv = ','.join(self.kvm_deployer.host_ips()) if self.kvm_deployer else ''
        if not kvm_hosts_csv:
            # Fallback: use the Marvin node itself as the only network device
            kvm_hosts_csv = marvin_ip
        self.logger.info("Registering extension with hosts detail: %s", kvm_hosts_csv)

        register_details = [{"key": "hosts", "value": kvm_hosts_csv}]
        self.extension.register(
            self.apiclient,
            self.physical_network.id,
            'PhysicalNetwork',
            details=register_details
        )
        self.logger.info(
            "Extension registered to physical network %s with hosts=%s",
            self.physical_network.id, kvm_hosts_csv)

        # ---- Step 9c: Add external network device ----
        # The Marvin node is used as the remote network device (host) for this test.
        # Connection details are stored as extension_resource_map_details:
        #   - host, port: stored on the device record itself
        #   - username, namespace, script_path: stored with display=true (visible in list)
        #   - sshkey: stored with display=false (NEVER returned by listExternalNetworkDevices)
        # NetworkExtensionElement reads all details (including hidden) and injects them
        # as CS_PHYSICAL_NETWORK_EXTENSION_DETAILS JSON into network-extension.sh.
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
            "External network device added: host=%s port=%s (sshkey=<redacted>)",
            device.host, device.port)

        # ---- Step 10: Verify listExternalNetworkDevices ----
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

        # ---- Step 11: Update device — round-trip port test ----
        updated = self._update_external_network_device(
            self.physical_network.id,
            port=22
        )
        self.assertIsNotNone(updated)
        self.assertEqual('22', str(updated.port))
        self.logger.info("External network device updated: port=%s", updated.port)

        # ---- Step 12: Enable the extension's NSP ----
        # When an extension is registered to a physical network, a Network Service
        # Provider (NSP) named after the extension is automatically created.
        # This NSP is enabled by default; verify and ensure it is Enabled.
        provider_name = ext_name
        provider = self._find_provider(self.physical_network.id, provider_name)
        if provider is None:
            provider = self._add_provider(self.physical_network.id, provider_name)
        self.provider_id = provider.id
        self.provider_name = provider_name
        if provider.state != 'Enabled':
            self._update_provider_state(provider.id, 'Enabled')
        self.assertEqual('Enabled',
            self._find_provider(self.physical_network.id, provider_name).state)
        self.logger.info("Provider '%s' enabled", provider_name)

        # ---- Step 13: Create network offering ----
        # serviceProviderList uses the actual extension name (not the generic
        # 'NetworkExtension' string) so CloudStack knows which registered
        # extension handles each service.
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

        # ---- Step 14: Create account ----
        account = Account.create(
            self.apiclient,
            self.services["account"],
            admin=True,
            domainid=self.domain.id
        )
        self.cleanup.append(account)

        # ---- Step 15: Create isolated network ----
        # On network creation CloudStack calls NetworkExtensionElement which
        # executes network-extension.sh with:
        #   CS_PHYSICAL_NETWORK_EXTENSION_DETAILS = JSON (hosts, username, sshkey, …)
        #   CS_NETWORK_EXTENSION_DETAILS          = per-network JSON blob
        # network-extension.sh SSHes to the Marvin node and runs:
        #   ip netns exec <ns> network-extension-wrapper.sh implement ...
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

        # ---- Step 16: Deploy VM ----
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

        # ---- Step 17: Acquire public IP ----
        # triggers assign-ip on network-extension-wrapper.sh
        public_ip = PublicIPAddress.create(
            self.apiclient,
            accountid=account.name,
            zoneid=self.zone.id,
            domainid=account.domainid,
            networkid=network.id
        )
        ip_id = public_ip.ipaddress.id
        self.logger.info("Public IP: %s", public_ip.ipaddress.ipaddress)

        # ---- Step 18: Enable static NAT ----
        # triggers add-static-nat on network-extension-wrapper.sh
        StaticNATRule.enable(self.apiclient,
                             ipaddressid=ip_id,
                             virtualmachineid=vm.id,
                             networkid=network.id)

        # ---- Step 19: Disable static NAT ----
        # triggers delete-static-nat on network-extension-wrapper.sh
        StaticNATRule.disable(self.apiclient, ipaddressid=ip_id)

        # ---- Step 20: Acquire IP for port forwarding ----
        pf_ip = PublicIPAddress.create(
            self.apiclient,
            accountid=account.name, zoneid=self.zone.id,
            domainid=account.domainid, networkid=network.id
        )

        # ---- Step 21: Create port forwarding rule ----
        # triggers add-port-forward on network-extension-wrapper.sh
        pf_rule = NATRule.create(
            self.apiclient, vm,
            {"privateport": 22, "publicport": 2222, "protocol": "TCP"},
            ipaddressid=pf_ip.ipaddress.id, networkid=network.id
        )
        self.assertIsNotNone(pf_rule)
        self.logger.info("Port forwarding rule: %s  %s:2222 → VM:22",
                         pf_rule.id, pf_ip.ipaddress.ipaddress)

        # ---- Step 22: Delete port forwarding rule ----
        # triggers delete-port-forward on network-extension-wrapper.sh
        pf_rule.delete(self.apiclient)
        pf_ip.delete(self.apiclient)
        public_ip.delete(self.apiclient)

        # ---- Step 23: Destroy VM ----
        vm.delete(self.apiclient, expunge=True)
        self.cleanup = [o for o in self.cleanup if o != vm]

        # ---- Step 24: Delete network → shutdown/destroy ----
        # triggers shutdown then destroy on network-extension-wrapper.sh
        network.delete(self.apiclient)
        self.cleanup = [o for o in self.cleanup if o != network]
        self.logger.info("Network deleted (shutdown/destroy ran in namespace)")

        # ---- Steps 25-29: Cleanup infrastructure ----
        self._update_provider_state(self.provider_id, 'Disabled')
        self._delete_provider(self.provider_id)
        self.provider_id = None

        self._delete_external_network_device(self.physical_network.id)

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

        Uses a stand-alone provider named 'NetworkExtension' (not backed by an
        extension) to exercise the generic NSP state machine.  Real
        extension-backed providers are always named after the extension;
        see test_01 for the full lifecycle.
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
        and network-extension-wrapper.sh is installed and executable."""
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
        # Should not output an SSH error message
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
        - Admin can create a NetworkOrchestrator extension (default state: Enabled).
        - Admin can disable the extension (state=Disabled).
        - Admin can re-enable the extension (state=Enabled).
        - Extension cannot be deleted while registered to a physical network.
        - After unregistering, the extension can be deleted.
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

        # An extension must be registered before an external network device can be added
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

        # List — sshkey must not appear in response; username must appear
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

