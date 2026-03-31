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
"""Support module for the NetworkExtension smoke tests.

**Do not run this file directly.**  Tests are discovered and executed through
``test_network_extension_namespace.py``, which exposes the test class under a
discoverable name.

This module provides:
  * Module-level constants (script names, URLs, capabilities JSON).
  * Helper functions (_download_script, _ensure_scripts_downloaded, etc.).
  * Deployer classes (MgmtServerDeployer, KvmHostDeployer).
  * Base test class ``_TestNetworkExtensionNamespace`` (underscore = not
    collected directly by nose/Marvin).

Renamed from ``test_network_extension_provider.py``.  The canonical test file
is ``test_network_extension_namespace.py``.
"""
import json
import logging
import os
import random
import shutil
import stat
import subprocess
import tempfile
import urllib.parse

from marvin.cloudstackTestCase import cloudstackTestCase
from marvin.cloudstackAPI import (listPhysicalNetworks,
                                  listTrafficTypes,
                                  listNetworkServiceProviders,
                                  updateNetworkServiceProvider,
                                  deleteNetworkServiceProvider,
                                  createFirewallRule,
                                  listPublicIpAddresses)
from marvin.lib.base import (Account,
                             Extension,
                             LoadBalancerRule,
                             Network,
                             NetworkOffering,
                             NATRule,
                             PublicIPAddress,
                             ServiceOffering,
                             SSHKeyPair,
                             StaticNATRule,
                             Template,
                             VirtualMachine,
                             VPC,
                             VpcOffering)
from marvin.lib.common import (get_domain, get_zone, get_template)
from marvin.lib.utils import cleanup_resources, random_gen
from marvin.sshClient import SshClient
from nose.plugins.attrib import attr

_multiprocess_shared_ = True

# Directory on KVM agents where network-namespace scripts are installed
EXTENSIONS_DIR        = '/etc/cloudstack/extensions/network-namespace'
SCRIPT_FILENAME       = 'network-namespace-wrapper.sh'
ENTRY_POINT_FILENAME  = 'network-namespace.sh'

# State directory used by network-namespace-wrapper.sh on KVM hosts
KVM_STATE_DIR = '/var/lib/cloudstack/network-namespace'

# Remote URLs to download the scripts from
_GITHUB_BASE = (
    'https://raw.githubusercontent.com/weizhouapache/cloudstack'
    '/refs/heads/4.22.0.0-ext/extensions/network-namespace'
)
WRAPPER_SCRIPT_URL     = _GITHUB_BASE + '/network-namespace-wrapper.sh'
ENTRY_POINT_SCRIPT_URL = _GITHUB_BASE + '/network-namespace.sh'

# Local cache paths (downloaded once, reused across test methods)
_THIS_DIR = os.path.dirname(os.path.abspath(__file__))
_SCRIPT_CACHE_DIR = os.path.join(tempfile.gettempdir(), 'cs-extnet-script-cache')
WRAPPER_SCRIPT_LOCAL     = os.path.join(_SCRIPT_CACHE_DIR, SCRIPT_FILENAME)
ENTRY_POINT_SCRIPT_LOCAL = os.path.join(_SCRIPT_CACHE_DIR, ENTRY_POINT_FILENAME)

# Network capabilities JSON — all services this extension supports.
# Tests select a subset when creating NetworkOfferings.
NETWORK_CAPABILITIES_JSON = json.dumps({
    "services": [
        "Dhcp", "Dns", "UserData",
        "SourceNat", "StaticNat", "PortForwarding", "Firewall", "Lb", "NetworkACL"
    ],
    "capabilities": {
        "Lb": {
            "SupportedLBAlgorithms": "roundrobin,leastconn,source",
            "SupportedLBIsolation": "dedicated",
            "SupportedProtocols": "tcp,udp,tcp-proxy",
            "SupportedStickinessMethods": "lbcookie,appsession",
            "LbSchemes": "Public",
            "SslTermination": "false",
            "VmAutoScaling": "false"
        },
        "Firewall": {
            "TrafficStatistics": "per public ip",
            "SupportedProtocols": "tcp,udp,icmp",
            "SupportedEgressProtocols": "tcp,udp,icmp,all",
            "SupportedTrafficDirection": "ingress,egress",
            "MultipleIps": "true"
        },
        "Dns": {
            "AllowDnsSuffixModification": "true",
            "ExternalDns": "true"
        },
        "Dhcp": {
            "DhcpAccrossMultipleSubnets": "true"
        },
        "Gateway": {
            "RedundantRouter": "false"
        },
        "SourceNat": {
            "SupportedSourceNatTypes": "peraccount",
            "RedundantRouter": "false"
        },
        "StaticNat": {
            "Supported": "true"
        },
        "PortForwarding": {
            "SupportedProtocols": "tcp,udp"
        },
        "UserData": {
            "Supported": "true"
        },
        "NetworkACL": {
            "SupportedProtocols": "tcp,udp,icmp"
        }
    }
})


# ---------------------------------------------------------------------------
# Script download helpers
# ---------------------------------------------------------------------------

def _download_script(url, dest_path):
    """Download *url* to *dest_path* via curl or wget and make it executable."""
    os.makedirs(os.path.dirname(dest_path), exist_ok=True)
    log = logging.getLogger('cs-extnet')
    log.info("Downloading %s -> %s", url, dest_path)
    for cmd in (['curl', '-fsSL', url, '-o', dest_path],
                ['wget', '-q',    url, '-O', dest_path]):
        if subprocess.run(cmd, check=False).returncode == 0:
            os.chmod(dest_path, stat.S_IRWXU | stat.S_IRGRP | stat.S_IXGRP |
                     stat.S_IROTH | stat.S_IXOTH)
            return dest_path
    raise RuntimeError("Failed to download %s with curl and wget" % url)


def _ensure_scripts_downloaded():
    """Download both scripts from GitHub if not already cached.

    If GitHub is unreachable the function falls back to the corresponding
    scripts in the local source tree so that tests can run offline:
      * wrapper      → extensions/network-namespace/network-namespace-wrapper.sh
      * entry point  → extensions/network-namespace/network-namespace.sh

    Returns (wrapper_path, entry_point_path).
    """
    _src_root = os.path.normpath(
        os.path.join(os.path.dirname(os.path.abspath(__file__)),
                     '..', '..', '..'))

    if not os.path.exists(WRAPPER_SCRIPT_LOCAL):
        try:
            _download_script(WRAPPER_SCRIPT_URL, WRAPPER_SCRIPT_LOCAL)
        except Exception:
            # Offline fallback: deploy the source-tree implementation.
            _local_impl = os.path.join(
                _src_root, 'extensions', 'network-namespace',
                'network-namespace-wrapper.sh')
            if os.path.exists(_local_impl):
                os.makedirs(os.path.dirname(WRAPPER_SCRIPT_LOCAL),
                            exist_ok=True)
                shutil.copy2(_local_impl, WRAPPER_SCRIPT_LOCAL)
                os.chmod(WRAPPER_SCRIPT_LOCAL,
                         stat.S_IRWXU | stat.S_IRGRP | stat.S_IXGRP |
                         stat.S_IROTH | stat.S_IXOTH)
                logging.getLogger('cs-extnet').info(
                    "Offline fallback: using local %s as %s",
                    _local_impl, WRAPPER_SCRIPT_LOCAL)
            else:
                raise

    if not os.path.exists(ENTRY_POINT_SCRIPT_LOCAL):
        try:
            _download_script(ENTRY_POINT_SCRIPT_URL, ENTRY_POINT_SCRIPT_LOCAL)
        except Exception:
            _local_ep = os.path.join(
                _src_root, 'extensions', 'network-namespace',
                'network-namespace.sh')
            if os.path.exists(_local_ep):
                os.makedirs(os.path.dirname(ENTRY_POINT_SCRIPT_LOCAL),
                            exist_ok=True)
                shutil.copy2(_local_ep, ENTRY_POINT_SCRIPT_LOCAL)
                os.chmod(ENTRY_POINT_SCRIPT_LOCAL,
                         stat.S_IRWXU | stat.S_IRGRP | stat.S_IXGRP |
                         stat.S_IROTH | stat.S_IXOTH)
                logging.getLogger('cs-extnet').info(
                    "Offline fallback: using local %s as %s",
                    _local_ep, ENTRY_POINT_SCRIPT_LOCAL)
            else:
                raise

    return WRAPPER_SCRIPT_LOCAL, ENTRY_POINT_SCRIPT_LOCAL


# ---------------------------------------------------------------------------
# KVM host discovery helpers (from Marvin config)
# ---------------------------------------------------------------------------

def _get_kvm_hosts_from_config(config):
    """Return list of host dicts for all KVM hosts in the Marvin config.

    Each entry: {"ip": .., "username": .., "password": ..}
    """
    hosts = []
    try:
        for zone in config.__dict__.get("zones", []):
            for pod in zone.__dict__.get("pods", []):
                for cluster in pod.__dict__.get("clusters", []):
                    for h in cluster.__dict__.get("hosts", []):
                        if hasattr(h, '__dict__'):
                            h = h.__dict__
                        url = h.get("url", "")
                        if not url.startswith('http://') and not url.startswith('https://'):
                            url = 'http://' + url
                        parsed = urllib.parse.urlparse(url)
                        ip = parsed.hostname or ''
                        if not ip:
                            continue
                        hosts.append({
                            "ip":       ip,
                            "username": h.get("username", "root"),
                            "password": h.get("password", ""),
                        })
    except Exception as e:
        logging.getLogger('cs-extnet').warning(
            "Could not read KVM hosts from config: %s", e)
    return hosts


# ---------------------------------------------------------------------------
# SSH helper
# ---------------------------------------------------------------------------

def _ssh_copy_file(host_ip, host_port, username, password, local_path, remote_path):
    """Transfer *local_path* to *remote_path* on *host_ip* via SshClient."""
    ssh = SshClient(host_ip, int(host_port), username, password)
    ssh.execute("mkdir -p '%s'" % os.path.dirname(remote_path))
    # Use SFTP upload to avoid very large shell arguments for script content.
    ssh.scp(local_path, remote_path)
    ssh.execute("chmod 755 '%s'" % remote_path)


# ---------------------------------------------------------------------------
# MgmtServerDeployer  – deploys network-namespace.sh to the management server
# ---------------------------------------------------------------------------

class MgmtServerDeployer:
    """Copies network-namespace.sh to the management server via SSH."""

    def __init__(self, mgt_details, logger=None):
        self.ip     = mgt_details.get("mgtSvrIp", "localhost")
        self.port   = 22
        self.user   = mgt_details.get("user", "root")
        self.passwd = mgt_details.get("passwd", "")
        self.logger = logger or logging.getLogger('MgmtServerDeployer')

    def copy_file(self, local_path, remote_path, mode='0755'):
        _ssh_copy_file(self.ip, self.port, self.user, self.passwd,
                       local_path, remote_path)
        self.logger.info("Copied %s -> %s on mgmt %s",
                         local_path, remote_path, self.ip)

    def remove_file(self, remote_path):
        try:
            SshClient(self.ip, self.port, self.user, self.passwd).execute(
                "rm -f '%s'" % remote_path)
        except Exception as e:
            self.logger.warning("Could not remove %s: %s", remote_path, e)


# ---------------------------------------------------------------------------
# KvmHostDeployer  – deploys network-namespace-wrapper.sh to KVM hosts
# ---------------------------------------------------------------------------

class KvmHostDeployer:
    """Copies network-namespace-wrapper.sh to all KVM hosts via SSH."""

    DEST_PATH = EXTENSIONS_DIR + '/' + SCRIPT_FILENAME

    def __init__(self, config_hosts=None, logger=None):
        self.config_hosts    = config_hosts or []
        self.logger          = logger or logging.getLogger('KvmHostDeployer')
        self._deployed_hosts = []

    def deploy(self):
        """Deploy wrapper to all configured hosts. Returns list of deployed IPs."""
        wrapper_path, _ = _ensure_scripts_downloaded()
        self._deployed_hosts = []
        if not self.config_hosts:
            self.logger.warning("No KVM hosts configured — wrapper not deployed")
            return []
        for h in self.config_hosts:
            ip       = h.get('ip', '')
            username = h.get('username', 'root')
            password = h.get('password', '')
            if not ip:
                continue
            self.logger.info("Deploying wrapper to KVM host %s", ip)
            try:
                _ssh_copy_file(ip, 22, username, password,
                               wrapper_path, self.DEST_PATH)
                self.logger.info("Deployed wrapper to %s at %s", ip, self.DEST_PATH)
                self._deployed_hosts.append(ip)
            except Exception as e:
                self.logger.warning("Failed deploying to %s: %s", ip, e)
        return self._deployed_hosts

    def host_ips_csv(self):
        """Return comma-separated IP list of all configured hosts."""
        return ','.join(h.get('ip', '') for h in self.config_hosts if h.get('ip'))


# ---------------------------------------------------------------------------
# Test class
# ---------------------------------------------------------------------------

class TestNetworkExtensionNamespace(cloudstackTestCase):
    """Smoke tests for the NetworkExtension plugin.

    Not discovered directly — exposed as ``TestNetworkExtensionNamespace``
    through ``test_network_extension_namespace.py``.

    Covers:
      test_01 — NSP state transitions (Disabled/Enabled/Disabled)
      test_02 — network.capabilities detail stored correctly
      test_03 — extension enable/disable and delete restriction
      test_04 — DHCP/DNS/UserData: cloud-init VM on a shared network reaches Running state
      test_05 — full isolated lifecycle: static NAT, PF, LB, restart
                (all with SSH connectivity verification via keypair)
      test_06 — VPC multi-tier + VPC restart with SSH verification
    """

    @classmethod
    def setUpClass(cls):
        testClient = super(TestNetworkExtensionNamespace, cls).getClsTestClient()
        cls.apiclient     = testClient.getApiClient()
        cls.services      = testClient.getParsedTestDataConfig()
        cls.zone          = get_zone(cls.apiclient, testClient.getZoneForTests())
        cls.domain        = get_domain(cls.apiclient)
        cls.mgtSvrDetails = cls.config.__dict__["mgtSvr"][0].__dict__
        cls.hv            = testClient.getHypervisorInfo()
        cls._cleanup      = []
        cls.tmp_files     = []
        cls.keypair       = None

        cls.logger = logging.getLogger("TestNetworkExtensionNamespace")
        cls.stream_handler = logging.StreamHandler()
        cls.logger.setLevel(logging.DEBUG)
        cls.logger.addHandler(cls.stream_handler)

        # KVM host credentials from Marvin config
        cls.kvm_host_configs = _get_kvm_hosts_from_config(cls.config)
        cls.logger.info("KVM hosts from config: %s",
                        [h['ip'] for h in cls.kvm_host_configs if h.get('ip')])

        # ---- Cloud-init template (Ubuntu 22.04) ----
        # Used for hardware tests that verify actual SSH connectivity.
        # Falls back to the default test template when the cloud-init entry
        # is absent from the services config (e.g. on simulator).
        try:
            tpl_data = (cls.services
                        .get("test_templates_cloud_init", {})
                        .get(cls.hv.lower()))
            if tpl_data:
                cls.logger.info("Registering cloud-init template for %s", cls.hv)
                tpl = Template.register(
                    cls.apiclient,
                    tpl_data,
                    zoneid=cls.zone.id,
                    hypervisor=cls.hv,
                )
                tpl.download(cls.apiclient)
                cls._cleanup.append(tpl)
                cls.template = tpl
                cls.logger.info("Cloud-init template registered: %s", tpl.id)
            else:
                cls.logger.info("No cloud-init template for %s; using default",
                                cls.hv)
                cls.template = get_template(cls.apiclient, cls.zone.id, cls.hv)
        except Exception as e:
            cls.logger.warning("Cloud-init template registration failed: %s; "
                               "falling back to default", e)
            cls.template = get_template(cls.apiclient, cls.zone.id, cls.hv)

        # ---- SSH keypair (written to a temp file) ----
        try:
            kp = SSHKeyPair.create(cls.apiclient, name=random_gen() + ".pem")
            cls._cleanup.append(SSHKeyPair(kp.__dict__, None))
            pkfile = os.path.join(tempfile.gettempdir(), kp.name)
            kp.private_key_file = pkfile
            cls.tmp_files.append(pkfile)
            with open(pkfile, "w+") as fh:
                fh.write(kp.privatekey)
            os.chmod(pkfile, 0o400)
            cls.keypair = kp
            cls.logger.info("SSH keypair '%s' written to %s", kp.name, pkfile)
        except Exception as e:
            cls.logger.warning("Could not create SSH keypair: %s", e)

        # ---- Download wrapper scripts from GitHub ----
        try:
            _ensure_scripts_downloaded()
            cls.logger.info("Scripts cached: %s  %s",
                            WRAPPER_SCRIPT_LOCAL, ENTRY_POINT_SCRIPT_LOCAL)
        except Exception as e:
            cls.logger.warning("Could not download scripts from GitHub: %s", e)

    @classmethod
    def tearDownClass(cls):
        super(TestNetworkExtensionNamespace, cls).tearDownClass()
        for tmp_file in cls.tmp_files:
            try:
                os.remove(tmp_file)
            except Exception:
                pass

    def setUp(self):
        self.cleanup           = []
        self.provider_id       = None
        self.physical_network  = None
        self.extension         = None
        self.extension_path    = None
        self.mgmt_deployer     = None
        self._mgmt_script_path = None
        self.kvm_deployer      = None
        self._ssh_private_key_file = None

    def tearDown(self):
        self._safe_teardown()
        try:
            cleanup_resources(self.apiclient, self.cleanup)
        except Exception as e:
            self.logger.warning("cleanup_resources error: %s", e)

    # ------------------------------------------------------------------
    # CloudStack API helpers
    # ------------------------------------------------------------------

    def _get_physical_network(self):
        """Return the physical network with Guest traffic type in the test zone."""
        cmd = listPhysicalNetworks.listPhysicalNetworksCmd()
        cmd.zoneid = self.zone.id
        pns = self.apiclient.listPhysicalNetworks(cmd)
        self.assertIsInstance(pns, list)
        self.assertGreater(len(pns), 0)

        for pn in pns:
            tt_cmd = listTrafficTypes.listTrafficTypesCmd()
            tt_cmd.physicalnetworkid = pn.id
            traffic_types = self.apiclient.listTrafficTypes(tt_cmd)
            if traffic_types:
                for tt in traffic_types:
                    if getattr(tt, 'traffictype', '').lower() == 'guest':
                        self.logger.info(
                            "Selected physical network with Guest traffic: "
                            "%s (%s)", pn.name, pn.id)
                        return pn

        self.logger.info("No physical network with Guest traffic found; "
                         "using first: %s (%s)", pns[0].name, pns[0].id)
        return pns[0]

    def _find_provider(self, phys_net_id, name):
        cmd = listNetworkServiceProviders.listNetworkServiceProvidersCmd()
        cmd.physicalnetworkid = phys_net_id
        cmd.name = name
        providers = self.apiclient.listNetworkServiceProviders(cmd)
        return providers[0] if isinstance(providers, list) and providers else None

    def _update_provider_state(self, provider_id, state):
        cmd = updateNetworkServiceProvider.updateNetworkServiceProviderCmd()
        cmd.id    = provider_id
        cmd.state = state
        return self.apiclient.updateNetworkServiceProvider(cmd)

    def _delete_provider(self, provider_id):
        cmd = deleteNetworkServiceProvider.deleteNetworkServiceProviderCmd()
        cmd.id = provider_id
        self.apiclient.deleteNetworkServiceProvider(cmd)

    # ------------------------------------------------------------------
    # Script deployment helpers
    # ------------------------------------------------------------------

    def _deploy_scripts(self):
        """Deploy scripts to management server and all KVM hosts."""
        _, entry_point_src = _ensure_scripts_downloaded()

        self.mgmt_deployer = MgmtServerDeployer(self.mgtSvrDetails,
                                                logger=self.logger)
        # Extension path is the entrypoint file path; append .sh if omitted.
        self._mgmt_script_path = (self.extension_path or "").strip().rstrip('/')
        self.mgmt_deployer.copy_file(entry_point_src, self._mgmt_script_path)
        self.logger.info("network-namespace.sh deployed to mgmt at %s",
                         self._mgmt_script_path)

        self.kvm_deployer = KvmHostDeployer(config_hosts=self.kvm_host_configs,
                                            logger=self.logger)
        deployed = self.kvm_deployer.deploy()
        self.logger.info("network-namespace-wrapper.sh deployed to %d host(s): %s",
                         len(deployed), deployed)

    def _cleanup_mgmt_script(self):
        if self.mgmt_deployer and self._mgmt_script_path:
            self.mgmt_deployer.remove_file(self._mgmt_script_path)
            self._mgmt_script_path = None

    # ------------------------------------------------------------------
    # Teardown helper
    # ------------------------------------------------------------------

    def _safe_teardown(self):
        """Best-effort cleanup of extension/NSP/scripts."""
        if self.extension and self.physical_network:
            try:
                self.extension.unregister(self.apiclient,
                                          self.physical_network.id,
                                          'PhysicalNetwork')
            except Exception:
                pass
        if self.provider_id:
            for fn in (lambda: self._update_provider_state(self.provider_id, 'Disabled'),
                       lambda: self._delete_provider(self.provider_id)):
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
        self._cleanup_mgmt_script()

    # ------------------------------------------------------------------
    # SSH helpers (provider-agnostic — no KVM namespace checks)
    # ------------------------------------------------------------------

    def _verify_vm_ssh_access(self, ip, port=22, timeout=30, retries=3):
        """Return True if SSH to *ip*:*port* succeeds using the active keypair.

        The VM is expected to run Ubuntu 22.04 (cloud-init) with username
        ``ubuntu``.  Returns False if no keypair is available or if the
        connection fails.
        """
        key_file = self._ssh_private_key_file
        if not key_file and self.keypair:
            key_file = getattr(self.keypair, 'private_key_file', None)

        if not key_file:
            self.logger.warning("No SSH keypair available; returning False")
            return False
        try:
            ssh = SshClient(
                ip, int(port), "ubuntu", None,
                keyPairFiles=key_file,
                timeout=timeout,
                retries=retries,
            )
            out = ssh.execute("echo EXTNET_SSH_OK")
            return any("EXTNET_SSH_OK" in line for line in out)
        except Exception as e:
            self.logger.warning("SSH to %s:%s failed: %s", ip, port, e)
            return False

    def _assert_vm_ssh_accessible(self, ip, port=22, msg=None):
        """Assert that SSH to *ip*:*port* succeeds."""
        result = self._verify_vm_ssh_access(ip, port)
        self.assertTrue(
            result,
            msg or "SSH to %s:%s should be accessible" % (ip, port))

    def _assert_vm_ssh_not_accessible(self, ip, port=22, msg=None):
        """Assert that SSH to *ip*:*port* fails (uses short timeout)."""
        result = self._verify_vm_ssh_access(ip, port, timeout=5, retries=1)
        self.assertFalse(
            result,
            msg or "SSH to %s:%s should NOT be accessible" % (ip, port))

    def _create_firewall_rule_for_ssh(self, ipaddressid):
        """Create an ingress TCP/22 firewall rule on *ipaddressid*.

        Returns the created rule ID.
        """
        cmd = createFirewallRule.createFirewallRuleCmd()
        cmd.ipaddressid = ipaddressid
        cmd.protocol    = 'TCP'
        cmd.startport   = 22
        cmd.endport     = 22
        cmd.cidrlist    = ['0.0.0.0/0']
        rule = self.apiclient.createFirewallRule(cmd)
        self.assertIsNotNone(rule, "createFirewallRule returned None")
        self.logger.info("FW rule (TCP/22) created: id=%s on ipaddressid=%s",
                         rule.id, ipaddressid)
        return rule.id

    def _get_source_nat_ip(self, network_id):
        """Return the source NAT public IP object for *network_id*, or None."""
        cmd = listPublicIpAddresses.listPublicIpAddressesCmd()
        cmd.networkid   = network_id
        cmd.issourcenat = True
        try:
            result = self.apiclient.listPublicIpAddresses(cmd)
            if isinstance(result, list) and result:
                return result[0]
        except Exception as e:
            self.logger.warning("_get_source_nat_ip(%s): %s", network_id, e)
        return None

    # ------------------------------------------------------------------
    # Extension + NSP + offering setup helper (shared by tests 04-06)
    # ------------------------------------------------------------------

    def _setup_extension_nsp_offering(self, ext_name_prefix,
                                      supported_services=None,
                                      guestiptype="Isolated"):
        """Create extension, deploy scripts, register to physical network,
        enable NSP, and create a NetworkOffering.

        *supported_services* is a comma-separated list of CloudStack service
        names.  Defaults to ``"SourceNat,StaticNat,PortForwarding,Firewall"``.

        *guestiptype* controls the guest IP type for the NetworkOffering:
        ``"Isolated"`` (default) or ``"Shared"``.

        Sets ``self.physical_network``, ``self.extension``,
        ``self.extension_path``, ``self.provider_id``,
        ``self.kvm_deployer``, ``self.mgmt_deployer``.

        Returns ``(nw_offering, ext_name)``.  Skips when no KVM hosts are
        available.
        """
        _svc = supported_services or "SourceNat,StaticNat,PortForwarding,Firewall"
        self.physical_network = self._get_physical_network()

        ext_name = "%s-%s" % (ext_name_prefix, random_gen())
        self.extension = Extension.create(
            self.apiclient,
            name=ext_name,
            type='NetworkOrchestrator',
            details=[{"network.capabilities": NETWORK_CAPABILITIES_JSON}]
        )
        self.assertIsNotNone(self.extension)
        self.assertEqual('Enabled', self.extension.state)

        ext_list = Extension.list(self.apiclient, id=self.extension.id)
        self.assertTrue(ext_list and len(ext_list) > 0)
        self.extension_path = ext_list[0].path
        self.assertIsNotNone(self.extension_path)
        self.logger.info("Extension '%s' created, path=%s",
                         ext_name, self.extension_path)

        # Deploy scripts
        self._deploy_scripts()
        kvm_hosts_csv = self.kvm_deployer.host_ips_csv()
        if not kvm_hosts_csv:
            self.skipTest("No KVM hosts available — skipping")

        # Register extension to physical network
        register_details = [
            {"hosts": kvm_hosts_csv},
            {"username": self.kvm_host_configs[0].get('username', 'root')},
            {"password": self.kvm_host_configs[0].get('password', '')},
        ]

        self.extension.register(
            self.apiclient,
            self.physical_network.id,
            'PhysicalNetwork',
            details=register_details
        )
        self.logger.info("Extension registered, hosts=%s", kvm_hosts_csv)

        # Enable NSP
        provider = self._find_provider(self.physical_network.id, ext_name)
        self.assertIsNotNone(provider,
                             "NSP '%s' not found after registration" % ext_name)
        self.provider_id = provider.id
        if provider.state != 'Enabled':
            self._update_provider_state(provider.id, 'Enabled')
        self.assertEqual('Enabled',
                         self._find_provider(self.physical_network.id,
                                             ext_name).state)
        self.logger.info("NSP '%s' enabled", ext_name)

        # Create NetworkOffering
        _provider_map = {s.strip(): ext_name for s in _svc.split(',')}
        offering_params = {
            "name":              "ExtNet-Offering-%s" % random_gen(),
            "displaytext":       "ExtNet test offering",
            "guestiptype":       guestiptype,
            "traffictype":       "GUEST",
            "supportedservices": _svc,
            "serviceProviderList": _provider_map,
        }
        if guestiptype == "Shared":
            # CloudStack requires shared guest offerings to explicitly allow
            # caller-specified IP ranges.
            offering_params["specifyIpRanges"] = True
        if guestiptype == "Isolated" and "SourceNat" in _svc:
            offering_params["serviceCapabilityList"] = {
                "SourceNat": {"SupportedSourceNatTypes": "peraccount"},
            }
        nw_offering = NetworkOffering.create(self.apiclient, offering_params)
        self.cleanup.append(nw_offering)
        nw_offering.update(self.apiclient, state='Enabled')
        self.logger.info("NetworkOffering '%s' enabled (services: %s)",
                         nw_offering.name, _svc)

        return nw_offering, ext_name

    def _create_account_keypair(self, account, name_suffix=""):
        """Create an SSH keypair scoped to *account* and save private key file."""
        try:
            kp_name = "extnet-%s-%s" % (name_suffix or random_gen(), random_gen())
            kp = SSHKeyPair.create(
                self.apiclient,
                name=kp_name,
                account=account.name,
                domainid=account.domainid,
            )
            self.cleanup.append(SSHKeyPair(kp.__dict__, None))

            pkfile = os.path.join(tempfile.gettempdir(), kp.name)
            with open(pkfile, "w+") as fh:
                fh.write(kp.privatekey)
            os.chmod(pkfile, 0o400)

            self.tmp_files.append(pkfile)
            kp.private_key_file = pkfile
            self._ssh_private_key_file = pkfile
            self.logger.info("Account keypair '%s' written to %s", kp.name, pkfile)
            return kp
        except Exception as e:
            self.logger.warning("Could not create account keypair: %s", e)
            return None

    def _create_account_network_vm(self, nw_offering, name_suffix="",
                                   network_params=None):
        """Create an account, an isolated network, and deploy a cloud-init VM.

        The VM is deployed with an account-scoped SSH keypair so that SSH
        access can be tested directly.  Username is ``ubuntu``.

        Returns ``(account, network, vm)``.
        """
        suffix = name_suffix or random_gen()

        account = Account.create(
            self.apiclient,
            self.services["account"],
            admin=True,
            domainid=self.domain.id
        )
        self.cleanup.append(account)

        net_params = {
            "name":        "extnet-net-%s" % suffix,
            "displaytext": "ExtNet test network %s" % suffix,
        }
        if network_params:
            net_params.update(network_params)

        network = Network.create(
            self.apiclient,
            net_params,
            accountid=account.name,
            domainid=account.domainid,
            networkofferingid=nw_offering.id,
            zoneid=self.zone.id
        )
        self.cleanup.insert(0, network)
        self.assertIsNotNone(network)
        self.logger.info("Network created: %s (%s)", network.name, network.id)

        svc_offering = ServiceOffering.list(self.apiclient, issystem=False)[0]

        vm_cfg = {
            "displayname": "extnet-vm-%s" % suffix,
            "name":        "extnet-vm-%s" % suffix,
            "zoneid":      self.zone.id,
        }
        vm_kwargs = dict(
            accountid=account.name,
            domainid=account.domainid,
            serviceofferingid=svc_offering.id,
            templateid=self.template.id,
            networkids=[network.id],
        )
        account_keypair = self._create_account_keypair(account, suffix)
        if account_keypair:
            vm_kwargs["keypair"] = account_keypair.name

        vm = VirtualMachine.create(self.apiclient, vm_cfg, **vm_kwargs)
        self.cleanup.insert(0, vm)
        self.assertIsNotNone(vm)
        self.logger.info("VM deployed: %s (%s)", vm.name, vm.id)

        return account, network, vm

    def _teardown_extension(self):
        """Ordered teardown: disable NSP → delete provider → unregister
        extension → delete extension → remove mgmt script."""
        self._update_provider_state(self.provider_id, 'Disabled')
        self._delete_provider(self.provider_id)
        self.provider_id = None

        self.extension.unregister(self.apiclient,
                                  self.physical_network.id, 'PhysicalNetwork')
        self.extension.delete(self.apiclient,
                              unregisterresources=False, removeactions=False)
        self.extension        = None
        self.physical_network = None
        self._cleanup_mgmt_script()

    # ------------------------------------------------------------------
    # Tests — API-only (no KVM / no SSH)
    # ------------------------------------------------------------------

    @attr(tags=["advanced", "smoke"], required_hardware="false")
    def test_01_provider_state_transitions(self):
        """NSP state machine: Disabled → Enabled → Disabled → Deleted."""
        pn = self._get_physical_network()
        self.physical_network = pn

        ext_name = "extnet-nsp-" + random_gen()
        self.extension = Extension.create(
            self.apiclient,
            name=ext_name,
            type='NetworkOrchestrator',
            details=[{"network.capabilities": NETWORK_CAPABILITIES_JSON}]
        )
        self.extension.register(self.apiclient, pn.id, 'PhysicalNetwork')

        provider = self._find_provider(pn.id, ext_name)
        self.assertIsNotNone(provider)
        self.provider_id = provider.id

        # Normalise to Disabled first
        if provider.state == 'Enabled':
            self._update_provider_state(provider.id, 'Disabled')
        self.assertEqual('Disabled', self._find_provider(pn.id, ext_name).state)

        self._update_provider_state(provider.id, 'Enabled')
        self.assertEqual('Enabled', self._find_provider(pn.id, ext_name).state)
        self.logger.info("NSP enabled OK")

        self._update_provider_state(provider.id, 'Disabled')
        self.assertEqual('Disabled', self._find_provider(pn.id, ext_name).state)
        self.logger.info("NSP disabled OK")

        self.logger.info("test_01 PASSED")

    @attr(tags=["advanced", "smoke"], required_hardware="false")
    def test_02_extension_capabilities_detail(self):
        """Verify network.capabilities JSON is stored and retrievable via API."""
        caps_json = json.dumps({
            "services": ["SourceNat"],
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
        self.logger.info("test_02 PASSED")

    @attr(tags=["advanced", "smoke"], required_hardware="false")
    def test_03_extension_enable_disable_and_delete_restriction(self):
        """Extension enable/disable; deletion blocked while registered."""
        pn = self._get_physical_network()
        self.physical_network = pn

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

        self.extension.update(self.apiclient, state='Disabled')
        ext_list = Extension.list(self.apiclient, id=self.extension.id)
        self.assertEqual('Disabled', ext_list[0].state)
        self.logger.info("Extension disabled OK")

        self.extension.update(self.apiclient, state='Enabled')
        ext_list = Extension.list(self.apiclient, id=self.extension.id)
        self.assertEqual('Enabled', ext_list[0].state)
        self.logger.info("Extension re-enabled OK")

        self.extension.register(self.apiclient, pn.id, 'PhysicalNetwork')
        self.logger.info("Extension registered to physical network %s", pn.id)

        # Deletion while registered must fail
        try:
            self.extension.delete(self.apiclient,
                                  unregisterresources=False, removeactions=False)
            self.fail("Expected error when deleting extension while registered")
        except Exception as e:
            self.logger.info("Expected error when deleting while registered: %s",
                             e)

        self.extension.unregister(self.apiclient, pn.id, 'PhysicalNetwork')
        self.extension.delete(self.apiclient,
                              unregisterresources=False, removeactions=False)
        self.extension        = None
        self.physical_network = None
        self.logger.info("test_03 PASSED")

    # ------------------------------------------------------------------
    # Tests — hardware required
    # ------------------------------------------------------------------

    @attr(tags=["advanced", "smoke"], required_hardware="true")
    def test_04_dhcp_dns_userdata(self):
        """DHCP / DNS / UserData: cloud-init VM on a shared network reaches Running state.

        Creates a shared network with the extension providing Dhcp, Dns,
        and UserData services.  Deploys a VM with the cloud-init template.
        Verifies the VM reaches Running state, which implies it received a
        DHCP address from the extension.  No SSH verification is performed.

        Steps:
          1. Set up extension + NSP + offering (Dhcp, Dns, UserData) with
             guestiptype=Shared.
          2. Create account + shared network + cloud-init VM.
          3. Assert VM state == Running.
          4. Teardown.
        """
        svc = "Dhcp,Dns,UserData"
        nw_offering, _ext_name = self._setup_extension_nsp_offering(
            "extnet-dhcp", supported_services=svc, guestiptype="Shared")

        # Shared offerings with specifyIpRanges=True require explicit range.
        third_octet = random.randint(32, 220)
        shared_params = {
            "gateway": "172.31.%d.1" % third_octet,
            "netmask": "255.255.255.0",
            "startip": "172.31.%d.10" % third_octet,
            "endip": "172.31.%d.200" % third_octet,
        }

        account, network, vm = self._create_account_network_vm(
            nw_offering, name_suffix="dhcp", network_params=shared_params)

        # Verify VM is in Running state — DHCP must have worked
        self.assertEqual(
            'Running', vm.state,
            "VM should be in Running state after deploy (implies DHCP worked)")
        self.logger.info("VM %s is Running — DHCP/DNS/UserData path exercised",
                         vm.name)

        # Cleanup
        vm.delete(self.apiclient, expunge=True)
        self.cleanup = [o for o in self.cleanup if o != vm]
        network.delete(self.apiclient)
        self.cleanup = [o for o in self.cleanup if o != network]
        self._teardown_extension()
        self.logger.info("test_04 PASSED")

    @attr(tags=["advanced", "smoke"], required_hardware="true")
    def test_05_isolated_network_full_lifecycle(self):
        """Full isolated-network lifecycle with SSH connectivity verification.

        Uses a single cloud-init VM (Ubuntu 22.04, SSH keypair, username
        ``ubuntu``) throughout.

        Sub-tests in order
        ------------------
        A. Static NAT
             allocate IP → enable static NAT → create FW rule (TCP/22)
             → assert SSH works → disable static NAT → assert SSH fails
        B. Port forwarding (22→22)
             allocate IP → create PF rule → create FW rule (TCP/22)
             → assert SSH works → delete PF rule → assert SSH fails
        C. Load balancer (haproxy, round-robin TCP/22)
             allocate IP → create LB rule → assign VM → create FW rule
             → assert SSH works → remove VM from LB → delete LB rule
        D. Network restart
             allocate IP → create PF rule → create FW rule
             → assert SSH works (baseline)
             → restartNetwork(cleanup=True)
             → assert SSH works (namespace rebuilt, rules reapplied)
        """
        # ---- Setup ----
        svc = "SourceNat,StaticNat,PortForwarding,Firewall,Lb,UserData,Dhcp,Dns"
        nw_offering, _ext_name = self._setup_extension_nsp_offering(
            "extnet-isolated", supported_services=svc)
        account, network, vm = self._create_account_network_vm(
            nw_offering, name_suffix="iso")

        # ==============================================================
        # A. Static NAT
        # ==============================================================
        self.logger.info("--- Sub-test A: Static NAT ---")
        snat_ip_obj = PublicIPAddress.create(
            self.apiclient,
            accountid=account.name,
            zoneid=self.zone.id,
            domainid=account.domainid,
            networkid=network.id
        )
        snat_ip    = snat_ip_obj.ipaddress.ipaddress
        snat_ip_id = snat_ip_obj.ipaddress.id

        StaticNATRule.enable(self.apiclient,
                             ipaddressid=snat_ip_id,
                             virtualmachineid=vm.id,
                             networkid=network.id)
        self.logger.info("Static NAT enabled on %s", snat_ip)

        self._create_firewall_rule_for_ssh(snat_ip_id)
        self._assert_vm_ssh_accessible(
            snat_ip, 22,
            "SSH via static NAT %s:22 should succeed" % snat_ip)
        self.logger.info("Verified: SSH works via static NAT %s", snat_ip)

        StaticNATRule.disable(self.apiclient, ipaddressid=snat_ip_id)
        self.logger.info("Static NAT disabled on %s", snat_ip)
        self._assert_vm_ssh_not_accessible(
            snat_ip, 22,
            "SSH via %s:22 should fail after static NAT disabled" % snat_ip)
        self.logger.info("Verified: SSH fails after static NAT disabled")
        snat_ip_obj.delete(self.apiclient)

        # ==============================================================
        # B. Port forwarding
        # ==============================================================
        self.logger.info("--- Sub-test B: Port forwarding ---")
        pf_ip_obj = PublicIPAddress.create(
            self.apiclient,
            accountid=account.name,
            zoneid=self.zone.id,
            domainid=account.domainid,
            networkid=network.id
        )
        pf_ip    = pf_ip_obj.ipaddress.ipaddress
        pf_ip_id = pf_ip_obj.ipaddress.id

        pf_rule = NATRule.create(
            self.apiclient, vm,
            {"privateport": 22, "publicport": 22, "protocol": "TCP"},
            ipaddressid=pf_ip_id,
            networkid=network.id
        )
        self.assertIsNotNone(pf_rule)
        self.logger.info("PF rule created: %s:22 → VM:22", pf_ip)

        self._create_firewall_rule_for_ssh(pf_ip_id)
        self._assert_vm_ssh_accessible(
            pf_ip, 22,
            "SSH via PF %s:22 should succeed" % pf_ip)
        self.logger.info("Verified: SSH works via port forwarding %s", pf_ip)

        pf_rule.delete(self.apiclient)
        self.logger.info("PF rule deleted on %s", pf_ip)
        self._assert_vm_ssh_not_accessible(
            pf_ip, 22,
            "SSH via %s:22 should fail after PF rule deleted" % pf_ip)
        self.logger.info("Verified: SSH fails after PF rule deleted")
        pf_ip_obj.delete(self.apiclient)

        # ==============================================================
        # C. Load balancer (haproxy)
        # ==============================================================
        self.logger.info("--- Sub-test C: Load balancer ---")
        lb_ip_obj = PublicIPAddress.create(
            self.apiclient,
            accountid=account.name,
            zoneid=self.zone.id,
            domainid=account.domainid,
            networkid=network.id
        )
        lb_ip    = lb_ip_obj.ipaddress.ipaddress
        lb_ip_id = lb_ip_obj.ipaddress.id

        lb_rule = LoadBalancerRule.create(
            self.apiclient,
            {"name":        "lb-ssh-%s" % random_gen(),
             "alg":         "roundrobin",
             "privateport": 22,
             "publicport":  22},
            ipaddressid=lb_ip_id,
            accountid=account.name,
            networkid=network.id,
            domainid=account.domainid
        )
        self.assertIsNotNone(lb_rule)
        lb_rule.assign(self.apiclient, vms=[vm])
        self.logger.info("LB rule created, VM assigned: %s:22", lb_ip)

        self._create_firewall_rule_for_ssh(lb_ip_id)
        self._assert_vm_ssh_accessible(
            lb_ip, 22,
            "SSH via LB %s:22 should succeed (haproxy required on KVM hosts)"
            % lb_ip)
        self.logger.info("Verified: SSH works via haproxy LB %s", lb_ip)

        lb_rule.remove(self.apiclient, vms=[vm])
        lb_rule.delete(self.apiclient)
        lb_ip_obj.delete(self.apiclient)
        self.logger.info("LB rule deleted")

        # ==============================================================
        # D. Network restart (cleanup=True)
        # ==============================================================
        self.logger.info("--- Sub-test D: Network restart ---")
        rst_ip_obj = PublicIPAddress.create(
            self.apiclient,
            accountid=account.name,
            zoneid=self.zone.id,
            domainid=account.domainid,
            networkid=network.id
        )
        rst_ip    = rst_ip_obj.ipaddress.ipaddress
        rst_ip_id = rst_ip_obj.ipaddress.id

        rst_pf = NATRule.create(
            self.apiclient, vm,
            {"privateport": 22, "publicport": 22, "protocol": "TCP"},
            ipaddressid=rst_ip_id,
            networkid=network.id
        )
        self._create_firewall_rule_for_ssh(rst_ip_id)
        self._assert_vm_ssh_accessible(
            rst_ip, 22,
            "SSH via %s:22 should work before restart" % rst_ip)
        self.logger.info("Baseline SSH verified before restart")

        self.logger.info("Restarting network %s (cleanup=True) ...", network.id)
        network.restart(self.apiclient, cleanup=True)
        self.logger.info("Network restart completed")

        self._assert_vm_ssh_accessible(
            rst_ip, 22,
            "SSH via %s:22 should work after network restart" % rst_ip)
        self.logger.info("Verified: SSH restored after restart")

        rst_pf.delete(self.apiclient)
        rst_ip_obj.delete(self.apiclient)

        # ---- Final cleanup ----
        vm.delete(self.apiclient, expunge=True)
        self.cleanup = [o for o in self.cleanup if o != vm]
        network.delete(self.apiclient)
        self.cleanup = [o for o in self.cleanup if o != network]
        self._teardown_extension()
        self.logger.info("test_05 PASSED")

    @attr(tags=["advanced", "smoke"], required_hardware="true")
    def test_06_vpc_multi_tier_and_restart(self):
        """VPC multi-tier + VPC restart with SSH connectivity verification.

        Creates two VPC tier networks backed by the extension, deploys a
        VM in each tier, and verifies SSH access independently.  Then:

          1. VPC restart (cleanup=True) — verify SSH still works for both VMs.
          2. Delete tier-1 VM + network — verify tier-2 VM is still accessible.
          3. Delete tier-2 VM + network.
          4. Delete VPC.
          5. Teardown extension.

        The VPC tier network offering uses ``useVpc=on`` as required by
        CloudStack for VPC-associated tier networks.
        """
        # ---- Setup: extension + NSP + isolated offering (for reference) ----
        svc = "SourceNat,StaticNat,PortForwarding,Lb,UserData,Dhcp,Dns"
        _nw_offering, ext_name = self._setup_extension_nsp_offering(
            "extnet-vpc", supported_services=svc)

        # ---- VPC tier network offering (useVpc=on) ----
        vpc_tier_svc = "SourceNat,StaticNat,PortForwarding,Lb,UserData,Dhcp,Dns"
        _tier_prov   = {s.strip(): ext_name for s in vpc_tier_svc.split(',')}
        vpc_tier_offering = NetworkOffering.create(self.apiclient, {
            "name":              "ExtNet-VPCTier-%s" % random_gen(),
            "displaytext":       "ExtNet VPC tier offering",
            "guestiptype":       "Isolated",
            "traffictype":       "GUEST",
            "availability":      "Optional",
            "useVpc":            "on",
            "supportedservices": vpc_tier_svc,
            "serviceProviderList": _tier_prov,
            "serviceCapabilityList": {
                "SourceNat": {"SupportedSourceNatTypes": "peraccount"},
            },
        })
        self.cleanup.append(vpc_tier_offering)
        vpc_tier_offering.update(self.apiclient, state='Enabled')
        self.logger.info("VPC tier offering '%s' enabled", vpc_tier_offering.name)

        # ---- VPC offering ----
        vpc_svc  = "SourceNat,StaticNat,PortForwarding,Lb,UserData"
        _vpc_prov = {s.strip(): ext_name for s in vpc_svc.split(',')}
        vpc_offering = VpcOffering.create(self.apiclient, {
            "name":              "ExtNet-VPC-%s" % random_gen(),
            "displaytext":       "ExtNet VPC offering",
            "supportedservices": vpc_svc,
            "serviceProviderList": _vpc_prov,
        })
        self.cleanup.append(vpc_offering)
        vpc_offering.update(self.apiclient, state='Enabled')
        self.logger.info("VPC offering '%s' enabled", vpc_offering.name)

        # ---- Account ----
        suffix  = random_gen()
        account = Account.create(
            self.apiclient,
            self.services["account"],
            admin=True,
            domainid=self.domain.id
        )
        self.cleanup.append(account)
        account_keypair = self._create_account_keypair(account, suffix)

        # ---- VPC ----
        vpc = VPC.create(
            self.apiclient,
            {"name":        "extnet-vpc-%s" % suffix,
             "displaytext": "ExtNet VPC %s" % suffix,
             "cidr":        "10.1.0.0/16"},
            vpcofferingid=vpc_offering.id,
            zoneid=self.zone.id,
            account=account.name,
            domainid=account.domainid
        )
        self.cleanup.insert(0, vpc)
        self.logger.info("VPC created: %s (%s)", vpc.name, vpc.id)

        # ---- Tier 1 ----
        tier1 = Network.create(
            self.apiclient,
            {"name":        "tier1-%s" % suffix,
             "displaytext": "Tier 1 %s" % suffix},
            accountid=account.name,
            domainid=account.domainid,
            networkofferingid=vpc_tier_offering.id,
            zoneid=self.zone.id,
            vpcid=vpc.id,
            gateway="10.1.1.1",
            netmask="255.255.255.0"
        )
        self.cleanup.insert(0, tier1)
        self.logger.info("Tier 1 created: %s (%s)", tier1.name, tier1.id)

        # ---- Tier 2 ----
        tier2 = Network.create(
            self.apiclient,
            {"name":        "tier2-%s" % suffix,
             "displaytext": "Tier 2 %s" % suffix},
            accountid=account.name,
            domainid=account.domainid,
            networkofferingid=vpc_tier_offering.id,
            zoneid=self.zone.id,
            vpcid=vpc.id,
            gateway="10.1.2.1",
            netmask="255.255.255.0"
        )
        self.cleanup.insert(0, tier2)
        self.logger.info("Tier 2 created: %s (%s)", tier2.name, tier2.id)

        svc_offering = ServiceOffering.list(self.apiclient, issystem=False)[0]

        # ---- VM in tier 1 ----
        vm1_cfg = {"displayname": "vm1-%s" % suffix,
                   "name":        "vm1-%s" % suffix,
                   "zoneid":      self.zone.id}
        vm1_kw  = dict(accountid=account.name,
                       domainid=account.domainid,
                       serviceofferingid=svc_offering.id,
                       templateid=self.template.id,
                       networkids=[tier1.id])
        if account_keypair:
            vm1_kw["keypair"] = account_keypair.name
        vm1 = VirtualMachine.create(self.apiclient, vm1_cfg, **vm1_kw)
        self.cleanup.insert(0, vm1)
        self.logger.info("VM1 deployed in tier 1: %s (%s)", vm1.name, vm1.id)

        # ---- VM in tier 2 ----
        vm2_cfg = {"displayname": "vm2-%s" % suffix,
                   "name":        "vm2-%s" % suffix,
                   "zoneid":      self.zone.id}
        vm2_kw  = dict(accountid=account.name,
                       domainid=account.domainid,
                       serviceofferingid=svc_offering.id,
                       templateid=self.template.id,
                       networkids=[tier2.id])
        if account_keypair:
            vm2_kw["keypair"] = account_keypair.name
        vm2 = VirtualMachine.create(self.apiclient, vm2_cfg, **vm2_kw)
        self.cleanup.insert(0, vm2)
        self.logger.info("VM2 deployed in tier 2: %s (%s)", vm2.name, vm2.id)

        # ---- Tier 1: PF ----
        pf_ip1 = PublicIPAddress.create(
            self.apiclient,
            accountid=account.name,
            zoneid=self.zone.id,
            domainid=account.domainid,
            networkid=tier1.id,
            vpcid=vpc.id
        )
        pf_rule1 = NATRule.create(
            self.apiclient, vm1,
            {"privateport": 22, "publicport": 22, "protocol": "TCP"},
            ipaddressid=pf_ip1.ipaddress.id,
            networkid=tier1.id
        )
        tier1_ip = pf_ip1.ipaddress.ipaddress
        self.logger.info("Tier 1 PF: %s:22 → VM1:22", tier1_ip)

        # ---- Tier 2: LB ----
        lb_ip2 = PublicIPAddress.create(
            self.apiclient,
            accountid=account.name,
            zoneid=self.zone.id,
            domainid=account.domainid,
            networkid=tier2.id,
            vpcid=vpc.id
        )
        lb_rule2 = LoadBalancerRule.create(
            self.apiclient,
            {"name":        "vpc-lb-ssh-%s" % random_gen(),
             "alg":         "roundrobin",
             "privateport": 22,
             "publicport":  22},
            ipaddressid=lb_ip2.ipaddress.id,
            accountid=account.name,
            networkid=tier2.id,
            domainid=account.domainid
        )
        self.assertIsNotNone(lb_rule2)
        lb_rule2.assign(self.apiclient, vms=[vm2])
        tier2_ip = lb_ip2.ipaddress.ipaddress
        self.logger.info("Tier 2 LB: %s:22 → VM2:22", tier2_ip)

        # ---- Verify SSH to both VMs ----
        self._assert_vm_ssh_accessible(
            tier1_ip, 22,
            "SSH to tier-1 VM (%s) should succeed" % tier1_ip)
        self.logger.info("Verified: SSH to tier-1 VM works")

        self._assert_vm_ssh_accessible(
            tier2_ip, 22,
            "SSH to tier-2 VM via LB (%s) should succeed" % tier2_ip)
        self.logger.info("Verified: SSH to tier-2 VM works via LB")

        # ---- VPC restart (cleanup=True) ----
        self.logger.info("Restarting VPC %s (cleanup=True) ...", vpc.id)
        vpc.restart(self.apiclient, cleanup=True)
        self.logger.info("VPC restart completed")

        self._assert_vm_ssh_accessible(
            tier1_ip, 22,
            "SSH to tier-1 VM must work after VPC restart")
        self._assert_vm_ssh_accessible(
            tier2_ip, 22,
            "SSH to tier-2 VM via LB must work after VPC restart")
        self.logger.info("Verified: both VMs accessible after VPC restart")

        # ---- Delete tier 1 VM + network ----
        pf_rule1.delete(self.apiclient)
        pf_ip1.delete(self.apiclient)
        vm1.delete(self.apiclient, expunge=True)
        self.cleanup = [o for o in self.cleanup if o != vm1]
        tier1.delete(self.apiclient)
        self.cleanup = [o for o in self.cleanup if o != tier1]
        self.logger.info("Tier 1 VM + network deleted")

        # Tier 2 must remain accessible
        self._assert_vm_ssh_accessible(
            tier2_ip, 22,
            "SSH to tier-2 VM via LB must still work after tier-1 deleted")
        self.logger.info("Verified: tier-2 VM still accessible via LB after tier-1 deleted")

        # ---- Delete tier 2 VM + network ----
        lb_rule2.remove(self.apiclient, vms=[vm2])
        lb_rule2.delete(self.apiclient)
        lb_ip2.delete(self.apiclient)
        vm2.delete(self.apiclient, expunge=True)
        self.cleanup = [o for o in self.cleanup if o != vm2]
        tier2.delete(self.apiclient)
        self.cleanup = [o for o in self.cleanup if o != tier2]
        self.logger.info("Tier 2 VM + network deleted")

        # ---- Delete VPC ----
        vpc.delete(self.apiclient)
        self.cleanup = [o for o in self.cleanup if o != vpc]

        self._teardown_extension()
        self.logger.info("test_06 PASSED")
