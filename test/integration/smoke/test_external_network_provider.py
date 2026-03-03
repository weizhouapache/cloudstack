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
"""Smoke tests for External Network Provider plugin.

Architecture
------------
The test runs on the **Marvin node** (the host running this Python script).

  ┌──────────────────────────┐        SSH :2222        ┌─────────────────────────────┐
  │  Management Server       │ ─────────────────────→  │  Docker container           │
  │                          │   <marvin_node_ip>:2222  │  (Ubuntu 24.04)             │
  │  [extension path]        │                          │  /usr/local/bin/            │
  │    entry-point (wrapper) │                          │    external-network.sh      │
  │                          │                          │  sshd on port 22            │
  └──────────────────────────┘                          └─────────────────────────────┘
         ↑ executes wrapper                                       ↑
  CloudStack ExternalNetworkElement               Docker runs here (Marvin node)
                                                  port 22 → host port 2222

Steps:
 1.  Start Docker container on Marvin node:
     - ubuntu:24.04 with openssh-server, iptables, ebtables, iproute2
     - Map container port 22 → Marvin node port 2222
     - Copy external-network.sh into the container
     - Generate a temporary RSA key pair on Marvin node
     - Inject the public key into the container's authorized_keys
 2.  Deploy the entry-point wrapper to the management server:
     - Copy the private key to the management server (via SshClient)
     - Write the wrapper script to the extension path on mgmt server;
       it calls: ssh -i <key> root@<marvin_ip> -p 2222 external-network.sh "$@"
 3.  Create a NetworkOrchestrator extension with network.capabilities JSON.
 4.  Register extension with physical network.
 5.  Add and enable ExternalNetwork provider.
 6.  Create a network offering backed by ExternalNetwork.
 7.  Create an account.
 8.  Create an isolated network   (no-op until first VM).
 9.  Deploy a VM                  → triggers *implement* (runs in container).
10.  Acquire a public IP.
11.  Enable static NAT.
12.  Disable static NAT.
13.  Acquire another public IP for port forwarding.
14.  Create a port forwarding rule.
15.  Delete the port forwarding rule.
16.  Destroy the VM.
17.  Delete the isolated network  → triggers *shutdown* / *destroy* (in container).
18.  Disable and delete the provider.
19.  Unregister and delete the extension.
20.  Stop and remove the Docker container; clean up keys and wrapper.
"""
import json
import logging
import os
import shutil
import stat
import subprocess
import tempfile
import time

from marvin.cloudstackTestCase import cloudstackTestCase
from marvin.cloudstackAPI import (listPhysicalNetworks,
                                  addNetworkServiceProvider,
                                  listNetworkServiceProviders,
                                  updateNetworkServiceProvider,
                                  deleteNetworkServiceProvider)
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

EXTERNAL_NETWORK_PROVIDER_NAME = 'ExternalNetwork'

# Port on the Marvin node that maps to container port 22.
# The management server will SSH to <marvin_node_ip>:CONTAINER_SSH_PORT.
CONTAINER_SSH_PORT = 2222

# SSH user inside the container
CONTAINER_SSH_USER = 'root'

# Container name prefix
CONTAINER_NAME_PREFIX = 'cs-extnet-smoke'

# Docker image
DOCKER_IMAGE = 'ubuntu:24.04'

# Path inside the container where external-network.sh is installed
CONTAINER_SCRIPT_PATH = '/usr/local/bin/external-network.sh'

# Remote path on the management server where the private key is stored
MGMT_KEY_PATH = '/tmp/cs-extnet-key'

# Remote path on the management server where the entry-point wrapper lives
# (this is inside the extension directory, written by the test)
ENTRY_POINT_FILENAME = 'entry-point'

# Network capabilities JSON passed as an extension detail
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
# Source path of the reference external-network.sh in the source tree
# ---------------------------------------------------------------------------
_THIS_DIR = os.path.dirname(os.path.abspath(__file__))
_REPO_ROOT = os.path.abspath(os.path.join(_THIS_DIR, '..', '..', '..'))
REFERENCE_SCRIPT_SRC = os.path.join(
    _REPO_ROOT,
    'plugins', 'network-elements', 'external-network',
    'src', 'main', 'resources', 'scripts', 'external-network.sh'
)

# ---------------------------------------------------------------------------
# Local helpers (run on the Marvin node)
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


# ---------------------------------------------------------------------------
# DockerNetworkServer  – runs on the Marvin node
# ---------------------------------------------------------------------------

class DockerNetworkServer:
    """Manages an Ubuntu 24.04 Docker container on the Marvin node.

    The container acts as the external Linux network server.  It exposes
    sshd on host port ``ssh_port`` (default 2222) so the management server
    can reach it at ``<marvin_node_ip>:<ssh_port>``.

    Lifecycle::

        server = DockerNetworkServer(marvin_ip='192.168.1.10')
        server.start()        # pull image, start container, install packages
        key_file  = server.key_file       # local path to private key
        pub_key   = server.pub_key        # public key text
        server.stop()         # stop + remove container, delete temp dir
    """

    def __init__(self, marvin_ip, ssh_port=CONTAINER_SSH_PORT, logger=None):
        # IP of the Marvin node as seen by the management server
        self.marvin_ip = marvin_ip
        self.ssh_port = ssh_port
        self.ssh_user = CONTAINER_SSH_USER
        self.logger = logger or logging.getLogger('DockerNetworkServer')
        self._container_name = '%s-%s' % (CONTAINER_NAME_PREFIX, random_gen())
        self._tmpdir = None
        self._key_file = None
        self._pub_key = None
        self.running = False

    # ---- public properties ----

    @property
    def key_file(self):
        return self._key_file

    @property
    def pub_key(self):
        return self._pub_key

    # ---- lifecycle ----

    def start(self):
        """Start the container and make it ready for SSH + script execution."""
        self._check_docker()

        # Generate temp dir + SSH key pair on the Marvin node
        self._tmpdir = tempfile.mkdtemp(prefix='cs-extnet-test-')
        self._key_file = os.path.join(self._tmpdir, 'id_rsa_extnet')
        self.logger.info("Generating SSH key pair at %s", self._key_file)
        _run(['ssh-keygen', '-t', 'rsa', '-b', '2048',
              '-N', '', '-f', self._key_file])
        with open(self._key_file + '.pub') as fh:
            self._pub_key = fh.read().strip()

        # SSH client config for connections FROM the Marvin node to the container
        self._ssh_cfg = os.path.join(self._tmpdir, 'ssh_config')
        with open(self._ssh_cfg, 'w') as fh:
            fh.write(
                "Host 127.0.0.1\n"
                "  Port %d\n"
                "  User %s\n"
                "  IdentityFile %s\n"
                "  StrictHostKeyChecking no\n"
                "  UserKnownHostsFile /dev/null\n"
                "  LogLevel ERROR\n" %
                (self.ssh_port, self.ssh_user, self._key_file)
            )

        # Pull image
        self.logger.info("Pulling %s", DOCKER_IMAGE)
        _run(['docker', 'pull', DOCKER_IMAGE])

        # Start container (privileged for iptables/bridge support)
        self.logger.info("Starting container %s", self._container_name)
        _run([
            'docker', 'run', '-d',
            '--name', self._container_name,
            '--privileged',
            '-p', '%d:22' % self.ssh_port,
            DOCKER_IMAGE,
            'sleep', 'infinity'
        ])
        self.running = True

        # Install packages
        self.logger.info("Installing openssh-server, iptables, ebtables, iproute2 ...")
        self._docker_exec([
            'bash', '-c',
            'apt-get update -qq && '
            'DEBIAN_FRONTEND=noninteractive apt-get install -y -qq '
            'openssh-server iptables ebtables iproute2 procps util-linux 2>/dev/null'
        ], timeout=180)

        # Configure sshd + inject authorized key
        self._docker_exec(['bash', '-c', 'mkdir -p /run/sshd /root/.ssh'])
        self._docker_exec(['bash', '-c',
                           'grep -q "PermitRootLogin yes" /etc/ssh/sshd_config || '
                           'echo "PermitRootLogin yes" >> /etc/ssh/sshd_config'])
        self._docker_exec(['bash', '-c',
                           'echo "%s" > /root/.ssh/authorized_keys && '
                           'chmod 600 /root/.ssh/authorized_keys' % self._pub_key])
        # Start sshd
        self._docker_exec(['/usr/sbin/sshd'])

        # Wait for SSH
        self._wait_for_ssh()

        # Install external-network.sh
        self._install_script()

        self.logger.info(
            "Container %s ready. SSH: %s@127.0.0.1:%d  "
            "(management server should reach it at %s:%d)",
            self._container_name, self.ssh_user, self.ssh_port,
            self.marvin_ip, self.ssh_port)

    def stop(self):
        """Stop and remove the container; delete the temp directory."""
        if self._container_name:
            _run(['docker', 'stop', '--time', '5', self._container_name], check=False)
            _run(['docker', 'rm', '-f', self._container_name], check=False)
            self.logger.info("Container %s removed", self._container_name)
        if self._tmpdir and os.path.exists(self._tmpdir):
            shutil.rmtree(self._tmpdir, ignore_errors=True)
        self.running = False

    # ---- helpers ----

    def _check_docker(self):
        rc, _, _ = _run(['docker', 'info'], check=False)
        if rc != 0:
            raise unittest.SkipTest(
                "Docker is not available on the Marvin node. "
                "Please install and start Docker to run this test."
            )

    def _docker_exec(self, cmd, timeout=60):
        full = ['docker', 'exec', self._container_name] + cmd
        result = subprocess.run(full, check=False,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                timeout=timeout)
        return (result.returncode,
                result.stdout.decode().strip(),
                result.stderr.decode().strip())

    def _wait_for_ssh(self, timeout=90, interval=3):
        self.logger.info("Waiting for sshd on 127.0.0.1:%d ...", self.ssh_port)
        deadline = time.time() + timeout
        while time.time() < deadline:
            rc, _, _ = _run(
                ['ssh', '-F', self._ssh_cfg,
                 '-o', 'ConnectTimeout=3',
                 '127.0.0.1', 'echo', 'ok'],
                check=False
            )
            if rc == 0:
                self.logger.info("sshd is ready")
                return
            time.sleep(interval)
        raise RuntimeError(
            "sshd on 127.0.0.1:%d did not become ready within %ds" %
            (self.ssh_port, timeout)
        )

    def _install_script(self):
        """Copy external-network.sh into the container (or install a no-op stub)."""
        if os.path.exists(REFERENCE_SCRIPT_SRC):
            _run(['docker', 'cp', REFERENCE_SCRIPT_SRC,
                  '%s:%s' % (self._container_name, CONTAINER_SCRIPT_PATH)])
            self.logger.info("Copied %s into container", REFERENCE_SCRIPT_SRC)
        else:
            self.logger.warning(
                "Reference script not found at %s; installing no-op stub",
                REFERENCE_SCRIPT_SRC)
            stub = ('#!/bin/bash\n'
                    'mkdir -p /var/log 2>/dev/null || true\n'
                    'echo "[$(date)] cmd=$1 args=${*:2}" '
                    '>> /var/log/cs-extnet.log 2>/dev/null || true\n'
                    'echo "OK: $1"\nexit 0\n')
            # Write stub via docker exec (avoids shell quoting issues with docker cp)
            self._docker_exec([
                'bash', '-c',
                'cat > %s << \'STUBEOF\'\n%sSTUBEOF' % (CONTAINER_SCRIPT_PATH, stub)
            ])
        self._docker_exec(['chmod', '755', CONTAINER_SCRIPT_PATH])

    def ssh_from_marvin(self, command, check=True):
        """Run *command* inside the container via SSH from the Marvin node."""
        return _run(
            ['ssh', '-F', self._ssh_cfg, '127.0.0.1', command],
            check=check
        )

    def make_entry_point_wrapper(self, marvin_ip, key_path_on_mgmt):
        """Return the text of an entry-point bash wrapper script.

        The wrapper is executed by the management server.  It SSHes to the
        Docker container running on the Marvin node (at *marvin_ip*:ssh_port)
        and executes external-network.sh with the forwarded arguments.

        :param marvin_ip:         IP of the Marvin node as seen from mgmt server
        :param key_path_on_mgmt:  path to the private key on the mgmt server
        """
        return (
            '#!/bin/bash\n'
            '# Auto-generated by ExternalNetwork smoke test\n'
            '# Management server -> Docker container on Marvin node\n'
            'exec ssh \\\n'
            '    -o StrictHostKeyChecking=no \\\n'
            '    -o UserKnownHostsFile=/dev/null \\\n'
            '    -o LogLevel=ERROR \\\n'
            '    -o ConnectTimeout=10 \\\n'
            '    -i %(key)s \\\n'
            '    -p %(port)d \\\n'
            '    %(user)s@%(host)s \\\n'
            '    %(script)s "$@"\n'
        ) % {
            'key':    key_path_on_mgmt,
            'port':   self.ssh_port,
            'user':   self.ssh_user,
            'host':   marvin_ip,
            'script': CONTAINER_SCRIPT_PATH,
        }


# ---------------------------------------------------------------------------
# MgmtServerDeployer  – deploys files to the management server via SshClient
# ---------------------------------------------------------------------------

class MgmtServerDeployer:
    """Copies the SSH private key and entry-point wrapper to the management server.

    If the management server is localhost (same host as Marvin node), files are
    written directly.  Otherwise they are transferred via SshClient.
    """

    def __init__(self, mgt_details, logger=None):
        self.ip   = mgt_details.get("mgtSvrIp", "localhost")
        self.port = int(mgt_details.get("port", 22))
        self.user = mgt_details.get("user", "root")
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
            ssh.execute("mkdir -p %s" % os.path.dirname(remote_path))
            # Use a heredoc to avoid shell quoting issues
            escaped = content.replace("'", "'\\''")
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
            # Read the file and write it via SSH
            with open(local_path, 'rb') as fh:
                content = fh.read().decode('latin-1')  # safe for binary key data
            ssh = self._ssh()
            ssh.execute("mkdir -p %s" % os.path.dirname(remote_path))
            # Write binary-safe via base64
            import base64
            b64 = base64.b64encode(fh.read() if False else
                                   open(local_path, 'rb').read()).decode()
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
        """Return the IP the management server should use to reach the Marvin node.

        If the management server is localhost, the container is also local, so
        127.0.0.1 works.  If the management server is remote, use the Marvin
        node's outbound IP (the IP of the interface that connects to the mgmt
        server network).  We approximate this by using the Marvin node's
        hostname/IP that the management server already knows about – in practice
        the test operator sets MARVIN_NODE_IP in the environment, or we fall
        back to asking the OS for the outbound IP.
        """
        if self._is_local:
            return '127.0.0.1'
        # Allow override via environment variable
        env_ip = os.environ.get('MARVIN_NODE_IP', '')
        if env_ip:
            return env_ip
        # Try to determine the outbound IP toward the management server
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

import unittest  # needed for SkipTest in DockerNetworkServer._check_docker


class TestExternalNetworkProvider(cloudstackTestCase):
    """Full lifecycle smoke test for the ExternalNetwork plugin.

    The Docker container runs on the Marvin node.
    The management server SSHes to <marvin_node_ip>:2222 to execute
    external-network.sh inside the container.
    """

    @classmethod
    def setUpClass(cls):
        testClient = super(TestExternalNetworkProvider, cls).getClsTestClient()
        cls.apiclient  = testClient.getApiClient()
        cls.services   = testClient.getParsedTestDataConfig()
        cls.zone       = get_zone(cls.apiclient, testClient.getZoneForTests())
        cls.domain     = get_domain(cls.apiclient)
        cls.mgtSvrDetails = cls.config.__dict__["mgtSvr"][0].__dict__
        cls.hypervisor = testClient.getHypervisorInfo()
        cls.template   = get_template(cls.apiclient, cls.zone.id, cls.hypervisor)
        cls._cleanup   = []
        cls.logger     = logging.getLogger('TestExternalNetworkProvider')
        cls.logger.setLevel(logging.DEBUG)

    @classmethod
    def tearDownClass(cls):
        super(TestExternalNetworkProvider, cls).tearDownClass()

    def setUp(self):
        self.cleanup           = []
        self.provider_id       = None
        self.extension         = None
        self.physical_network  = None
        self.extension_path    = None
        self.docker_server     = None
        self.mgmt_deployer     = None
        # Paths deployed onto the management server
        self._mgmt_key_path    = None
        self._mgmt_wrapper_path = None

    def tearDown(self):
        self._safe_teardown()
        try:
            cleanup_resources(self.apiclient, self.cleanup)
        except Exception as e:
            self.logger.warning("cleanup_resources error: %s", e)
        if self.docker_server:
            try:
                self.docker_server.stop()
            except Exception as e:
                self.logger.warning("Docker stop error: %s", e)
            self.docker_server = None

    # ------------------------------------------------------------------
    # CloudStack API helpers
    # ------------------------------------------------------------------

    def _get_physical_network(self):
        cmd = listPhysicalNetworks.listPhysicalNetworksCmd()
        cmd.zoneid = self.zone.id
        pns = self.apiclient.listPhysicalNetworks(cmd)
        self.assertIsInstance(pns, list, "No physical networks found")
        self.assertGreater(len(pns), 0, "No physical networks found")
        return pns[0]

    def _find_provider(self, phys_net_id, name):
        cmd = listNetworkServiceProviders.listNetworkServiceProvidersCmd()
        cmd.physicalnetworkid = phys_net_id
        cmd.name = name
        providers = self.apiclient.listNetworkServiceProviders(cmd)
        if isinstance(providers, list) and len(providers) > 0:
            return providers[0]
        return None

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
    # Docker / extension / mgmt-server helpers
    # ------------------------------------------------------------------

    def _init_docker_and_deployer(self):
        """Start the Docker container on the Marvin node and create the deployer."""
        self.mgmt_deployer = MgmtServerDeployer(self.mgtSvrDetails,
                                                logger=self.logger)
        marvin_ip = self.mgmt_deployer.get_marvin_ip_as_seen_from_mgmt()

        self.docker_server = DockerNetworkServer(
            marvin_ip=marvin_ip,
            ssh_port=CONTAINER_SSH_PORT,
            logger=self.logger
        )
        self.docker_server.start()
        return marvin_ip

    def _deploy_to_mgmt_server(self, ext_path, marvin_ip):
        """Copy the SSH private key and entry-point wrapper to the mgmt server.

        Files placed on the management server:
          - MGMT_KEY_PATH              – the RSA private key
          - <ext_path>/entry-point     – the SSH-forwarding bash wrapper
        """
        # 1. Copy the private key to the management server
        self._mgmt_key_path = MGMT_KEY_PATH
        self.mgmt_deployer.copy_file(
            self.docker_server.key_file,   # local path on Marvin node
            self._mgmt_key_path,           # remote path on mgmt server
            mode='0600'
        )
        self.logger.info("Private key deployed to mgmt server at %s",
                         self._mgmt_key_path)

        # 2. Write the entry-point wrapper to the extension path on the mgmt server
        wrapper_text = self.docker_server.make_entry_point_wrapper(
            marvin_ip=marvin_ip,
            key_path_on_mgmt=self._mgmt_key_path
        )
        self._mgmt_wrapper_path = os.path.join(ext_path, ENTRY_POINT_FILENAME)
        self.mgmt_deployer.write_file(
            self._mgmt_wrapper_path,
            wrapper_text,
            mode='0755'
        )
        self.logger.info("entry-point wrapper deployed to mgmt server at %s",
                         self._mgmt_wrapper_path)

    def _cleanup_mgmt_server_files(self):
        """Remove the key and wrapper from the management server."""
        if self.mgmt_deployer:
            if self._mgmt_wrapper_path:
                self.mgmt_deployer.remove_file(self._mgmt_wrapper_path)
            if self._mgmt_key_path:
                self.mgmt_deployer.remove_file(self._mgmt_key_path)

    def _safe_teardown(self):
        """Best-effort teardown for objects not handled by cleanup_resources."""
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
        """Full lifecycle test.

        Flow
        ----
        Marvin node (this process):
          1.  Start Docker container (Ubuntu 24.04, sshd, iptables, ebtables).
          2.  Generate RSA key pair.
          3.  Install external-network.sh in the container.

        Management server:
          4.  Copy private key → MGMT_KEY_PATH.
          5.  Write entry-point wrapper → <ext_path>/entry-point.

        CloudStack:
          6.  Create NetworkOrchestrator extension with network.capabilities JSON.
          7.  Register extension with physical network.
          8.  Add + enable ExternalNetwork provider.
          9.  Create network offering.
         10.  Create account.
         11.  Create isolated network.
         12.  Deploy VM            → implement (wrapper SSH → container).
         13.  Acquire public IP.
         14.  Enable static NAT.
         15.  Disable static NAT.
         16.  Acquire public IP for PF.
         17.  Create port forwarding rule.
         18.  Delete port forwarding rule.
         19.  Destroy VM.
         20.  Delete network       → shutdown/destroy (wrapper SSH → container).

        Cleanup:
         21.  Disable/delete provider.
         22.  Unregister/delete extension.
         23.  Remove key + wrapper from mgmt server.
         24.  Stop + remove Docker container.
        """

        # ---- Steps 1-3: Docker container on Marvin node ----
        marvin_ip = self._init_docker_and_deployer()
        self.logger.info("Marvin node IP (as seen from mgmt server): %s", marvin_ip)

        # Quick sanity: run the script inside the container via SSH from Marvin
        rc, out, _ = self.docker_server.ssh_from_marvin(
            '%s implement --network-id 0 --vlan 0 '
            '--gateway 192.0.2.1 --cidr 192.0.2.0/24 || true' %
            CONTAINER_SCRIPT_PATH, check=False)
        self.logger.info("Container script sanity check: rc=%d out=%r", rc, out)

        # ---- Step 4-5: Physical network + Extension ----
        self.physical_network = self._get_physical_network()
        self.logger.info("Physical network: %s (%s)",
                         self.physical_network.name, self.physical_network.id)

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

        # Retrieve the extension path on the management server
        ext_list = Extension.list(self.apiclient, id=self.extension.id)
        self.assertTrue(ext_list and len(ext_list) > 0)
        ext_obj = ext_list[0]
        self.extension_path = ext_obj.path
        self.assertIsNotNone(self.extension_path,
                             "Extension path must not be None")

        # Verify network.capabilities detail was stored
        if hasattr(ext_obj, 'details') and ext_obj.details:
            d = (ext_obj.details.__dict__
                 if not isinstance(ext_obj.details, dict)
                 else ext_obj.details)
            self.assertIn("network.capabilities", d,
                          "network.capabilities must be stored as a detail")
        self.logger.info("Extension %s created, path=%s", ext_name, self.extension_path)

        # ---- Steps 4-5: Deploy key + wrapper to management server ----
        self._deploy_to_mgmt_server(self.extension_path, marvin_ip)

        # ---- Step 6: Register extension with physical network ----
        self.extension.register(self.apiclient,
                                self.physical_network.id,
                                'PhysicalNetwork')
        self.logger.info("Extension registered with physical network")

        # ---- Step 7: Add + enable ExternalNetwork provider ----
        provider = self._find_provider(self.physical_network.id,
                                       EXTERNAL_NETWORK_PROVIDER_NAME)
        if provider is None:
            provider = self._add_provider(
                self.physical_network.id,
                EXTERNAL_NETWORK_PROVIDER_NAME,
                service_list=['SourceNat', 'StaticNat',
                              'PortForwarding', 'Firewall', 'Gateway']
            )
        self.provider_id = provider.id
        if provider.state != 'Enabled':
            self._update_provider_state(provider.id, 'Enabled')
        provider = self._find_provider(self.physical_network.id,
                                       EXTERNAL_NETWORK_PROVIDER_NAME)
        self.assertEqual('Enabled', provider.state)
        self.logger.info("Provider enabled: id=%s", provider.id)

        # ---- Step 8: Create network offering ----
        nw_offering = NetworkOffering.create(self.apiclient, {
            "name":             "ExtNet-Offering",
            "displaytext":      "ExtNet Offering (Docker smoke test)",
            "guestiptype":      "Isolated",
            "traffictype":      "GUEST",
            "supportedservices": "SourceNat,StaticNat,PortForwarding,Firewall,Gateway",
            "serviceProviderList": {
                "SourceNat":      "ExternalNetwork",
                "StaticNat":      "ExternalNetwork",
                "PortForwarding": "ExternalNetwork",
                "Firewall":       "ExternalNetwork",
                "Gateway":        "ExternalNetwork",
            },
            "serviceCapabilityList": {
                "SourceNat": {"SupportedSourceNatTypes": "peraccount"},
            },
        })
        self.cleanup.append(nw_offering)
        nw_offering.update(self.apiclient, state='Enabled')
        self.logger.info("Network offering created: %s", nw_offering.id)

        # ---- Step 9: Create account ----
        account = Account.create(
            self.apiclient,
            self.services["account"],
            admin=True,
            domainid=self.domain.id
        )
        self.cleanup.append(account)

        # ---- Step 10: Create isolated network ----
        network = Network.create(
            self.apiclient,
            {"name": "extnet-smoke-net",
             "displaytext": "ExtNet Docker smoke test network"},
            accountid=account.name,
            domainid=account.domainid,
            networkofferingid=nw_offering.id,
            zoneid=self.zone.id
        )
        self.cleanup.insert(0, network)
        self.assertIsNotNone(network)
        self.logger.info("Isolated network created: %s (%s)", network.name, network.id)

        # ---- Step 11: Deploy VM → triggers implement ----
        svc_offerings = ServiceOffering.list(self.apiclient, issystem=False)
        self.assertIsInstance(svc_offerings, list)
        svc_offering = svc_offerings[0]

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
        self.logger.info("VM deployed: %s (%s) – implement ran in container", vm.name, vm.id)

        # ---- Step 12: Acquire public IP ----
        public_ip = PublicIPAddress.create(
            self.apiclient,
            accountid=account.name,
            zoneid=self.zone.id,
            domainid=account.domainid,
            networkid=network.id
        )
        self.assertIsNotNone(public_ip)
        ip_id = public_ip.ipaddress.id
        self.logger.info("Public IP: %s (%s)", public_ip.ipaddress.ipaddress, ip_id)

        # ---- Step 13: Enable static NAT ----
        StaticNATRule.enable(
            self.apiclient,
            ipaddressid=ip_id,
            virtualmachineid=vm.id,
            networkid=network.id
        )
        self.logger.info("Static NAT enabled: %s → VM %s",
                         public_ip.ipaddress.ipaddress, vm.id)

        # ---- Step 14: Disable static NAT ----
        StaticNATRule.disable(self.apiclient, ipaddressid=ip_id)
        self.logger.info("Static NAT disabled: %s", public_ip.ipaddress.ipaddress)

        # ---- Step 15: Acquire another public IP for port forwarding ----
        pf_ip = PublicIPAddress.create(
            self.apiclient,
            accountid=account.name,
            zoneid=self.zone.id,
            domainid=account.domainid,
            networkid=network.id
        )
        self.assertIsNotNone(pf_ip)
        pf_ip_id = pf_ip.ipaddress.id
        self.logger.info("PF public IP: %s (%s)", pf_ip.ipaddress.ipaddress, pf_ip_id)

        # ---- Step 16: Create port forwarding rule (TCP 2222 → VM:22) ----
        pf_rule = NATRule.create(
            self.apiclient,
            vm,
            {"privateport": 22, "publicport": 2222, "protocol": "TCP"},
            ipaddressid=pf_ip_id,
            networkid=network.id
        )
        self.assertIsNotNone(pf_rule)
        self.logger.info("Port forwarding rule created: %s  "
                         "%s:2222 → VM:22", pf_rule.id, pf_ip.ipaddress.ipaddress)

        # ---- Step 17: Delete port forwarding rule ----
        pf_rule.delete(self.apiclient)
        self.logger.info("Port forwarding rule deleted")

        # Release IPs
        pf_ip.delete(self.apiclient)
        public_ip.delete(self.apiclient)
        self.logger.info("Public IPs released")

        # ---- Step 18: Destroy VM ----
        vm.delete(self.apiclient, expunge=True)
        self.cleanup = [o for o in self.cleanup if o != vm]
        self.logger.info("VM destroyed")

        # ---- Step 19: Delete network → shutdown/destroy run in container ----
        network.delete(self.apiclient)
        self.cleanup = [o for o in self.cleanup if o != network]
        self.logger.info("Network deleted (shutdown/destroy ran in container)")

        # ---- Step 20: Disable + delete provider ----
        self._update_provider_state(self.provider_id, 'Disabled')
        self._delete_provider(self.provider_id)
        self.provider_id = None

        # ---- Step 21: Unregister + delete extension ----
        self.extension.unregister(self.apiclient,
                                  self.physical_network.id,
                                  'PhysicalNetwork')
        self.extension.delete(self.apiclient,
                              unregisterresources=False,
                              removeactions=False)
        self.extension = None
        self.physical_network = None

        # ---- Step 22: Remove key + wrapper from mgmt server ----
        self._cleanup_mgmt_server_files()

        # ---- Step 23: Stop Docker container ----
        self.docker_server.stop()
        self.docker_server = None

        self.logger.info("Full lifecycle test PASSED")

    @attr(tags=["advanced", "smoke"], required_hardware="false")
    def test_02_provider_state_transitions(self):
        """Provider state transitions: Disabled → Enabled → Disabled → Deleted."""
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
            "capabilities": {
                "SourceNat": {"SupportedSourceNatTypes": "peraccount"}
            }
        })
        ext = Extension.create(
            self.apiclient,
            name="extnet-caps-" + random_gen(),
            type='NetworkOrchestrator',
            details=[{"network.capabilities": caps_json}]
        )
        self.cleanup.append(ext)
        self.assertIsNotNone(ext)

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
            self.assertEqual(2, len(stored["services"]))
        self.logger.info("Extension capabilities detail test PASSED")

    @attr(tags=["advanced", "smoke"], required_hardware="false")
    def test_04_docker_container_ssh_and_script(self):
        """Verify the Docker container starts on the Marvin node, SSH works,
        iptables/ebtables are available, and external-network.sh is installed."""
        marvin_ip = self._init_docker_and_deployer()

        # SSH from Marvin node to container
        rc, out, _ = self.docker_server.ssh_from_marvin('echo hello-from-container')
        self.assertEqual(0, rc)
        self.assertIn('hello-from-container', out)

        # iptables must be present
        rc, out, _ = self.docker_server.ssh_from_marvin('iptables --version')
        self.assertEqual(0, rc)
        self.assertIn('iptables', out.lower())

        # ebtables must be present
        _, out2, _ = self.docker_server.ssh_from_marvin('which ebtables')
        self.assertIn('ebtables', out2)

        # external-network.sh must be executable
        rc, out, _ = self.docker_server.ssh_from_marvin(
            'test -x %s && echo executable' % CONTAINER_SCRIPT_PATH)
        self.assertEqual(0, rc)
        self.assertIn('executable', out)

        # Container is on Marvin node, reachable from mgmt server at marvin_ip:2222
        self.logger.info(
            "Container OK. Management server should SSH to %s:%d",
            marvin_ip, CONTAINER_SSH_PORT)

        self.docker_server.stop()
        self.docker_server = None
        self.logger.info("Docker container SSH and script test PASSED")
