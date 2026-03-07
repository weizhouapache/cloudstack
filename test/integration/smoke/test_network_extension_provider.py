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
The test is executed on the **Marvin node**.

  Management Server
  /etc/cloudstack/extensions/<ext-name>/network-extension.sh
      |
      |  CloudStack calls network-extension.sh for each network operation.
      |  The script SSHes to a KVM host (chosen from the "hosts" detail
      |  registered with the physical network) and runs:
      v
  KVM host(s)
  /etc/cloudstack/extensions/network-extension-wrapper.sh
      - creates a network namespace per isolated network
      - configures iptables for SourceNat, StaticNat, PortForwarding, Firewall

Scripts are downloaded at test startup from GitHub:
  network-extension.sh         -> management server
  network-extension-wrapper.sh -> every KVM host

CloudStack setup performed by the test:
  1. createExtension name=<ext-name> type=NetworkOrchestrator
  2. registerExtension to physical network with details:
       hosts=<kvm-ip1>,<kvm-ip2>,...
       username=<ssh-user>
       password=<ssh-password>
  3. Enable the NSP auto-created by the registration.
  4. Create NetworkOffering backed by the extension.
  5. Create network, VM, NAT/PF rules, then tear everything down.

Teardown:
  1. Delete CloudStack resources (PF rules, VM, network, offering, account).
  2. Disable/delete provider, unregister/delete extension.
  3. Remove network-extension.sh from management server.
"""
import base64
import json
import logging
import os
import stat
import subprocess
import tempfile
import urllib.parse

from marvin.cloudstackTestCase import cloudstackTestCase
from marvin.cloudstackAPI import (listPhysicalNetworks,
                                  listTrafficTypes,
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
from marvin.lib.common import (get_domain, get_zone, get_template)
from marvin.lib.utils import cleanup_resources, random_gen
from marvin.sshClient import SshClient
from nose.plugins.attrib import attr

_multiprocess_shared_ = True

# Directory on all hosts where scripts live
EXTENSIONS_DIR         = '/etc/cloudstack/extensions'
SCRIPT_FILENAME        = 'network-extension-wrapper.sh'
ENTRY_POINT_FILENAME   = 'network-extension.sh'

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
    """Return list of host dicts for all KVM hosts in the Marvin config.

    Each host entry looks like::
      {"username": "root", "url": "http://10.0.34.160", "password": "Pass123"}

    Returns a list of dicts: [{"ip": .., "port": .., "username": .., "password": ..}, ...]
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
    """Transfer *local_path* to *remote_path* on *host_ip* via SshClient (password auth)."""
    ssh = SshClient(host_ip, int(host_port), username, password)
    ssh.execute("mkdir -p '%s'" % os.path.dirname(remote_path))
    with open(local_path, 'rb') as fh:
        b64 = base64.b64encode(fh.read()).decode()
    ssh.execute("echo '%s' | base64 -d > '%s' && chmod 755 '%s'" %
                (b64, remote_path, remote_path))


# ---------------------------------------------------------------------------
# MgmtServerDeployer  – deploys network-extension.sh to the management server
# ---------------------------------------------------------------------------

class MgmtServerDeployer:
    """Copies network-extension.sh to the management server.

    Uses direct file write when the mgmt server is localhost / 127.0.0.1;
    otherwise transfers via SshClient (password authentication).
    """

    def __init__(self, mgt_details, logger=None):
        self.ip     = mgt_details.get("mgtSvrIp", "localhost")
        self.port   = 22
        self.user   = mgt_details.get("user", "root")
        self.passwd = mgt_details.get("passwd", "")
        self.logger = logger or logging.getLogger('MgmtServerDeployer')

    def copy_file(self, local_path, remote_path, mode='0755'):
        """Copy *local_path* to *remote_path* on the management server."""
        _ssh_copy_file(self.ip, self.port, self.user, self.passwd,
                       local_path, remote_path)
        self.logger.info("Copied %s -> %s on mgmt %s",
                         local_path, remote_path, self.ip)

    def remove_file(self, remote_path):
        """Remove *remote_path* on the management server (best-effort)."""
        try:
            SshClient(self.ip, self.port, self.user, self.passwd).execute(
                "rm -f '%s'" % remote_path)
        except Exception as e:
            self.logger.warning("Could not remove %s: %s", remote_path, e)



# ---------------------------------------------------------------------------
# KvmHostDeployer  – deploys network-extension-wrapper.sh to KVM hosts
# ---------------------------------------------------------------------------

class KvmHostDeployer:
    """Copies network-extension-wrapper.sh to all KVM hosts via SSH (password auth)."""

    DEST_PATH = EXTENSIONS_DIR + '/' + SCRIPT_FILENAME

    def __init__(self, config_hosts=None, logger=None):
        self.config_hosts    = config_hosts or []
        self.logger          = logger or logging.getLogger('KvmHostDeployer')
        self._deployed_hosts = []

    def deploy(self):
        """Deploy wrapper to all configured hosts.

        Returns list of (ip, port) tuples where deployment succeeded.
        """
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

class TestNetworkExtensionProvider(cloudstackTestCase):
    """Smoke tests for the NetworkExtension plugin.

    Covers:
      test_01 — full network lifecycle (create, VM, NAT, PF, delete)
      test_02 — NSP state transitions (Disabled/Enabled/Deleted)
      test_03 — network.capabilities detail stored correctly
      test_04 — extension enable/disable and delete restriction
    """

    @classmethod
    def setUpClass(cls):
        testClient = super(TestNetworkExtensionProvider, cls).getClsTestClient()
        cls.apiclient     = testClient.getApiClient()
        cls.services      = testClient.getParsedTestDataConfig()
        cls.zone          = get_zone(cls.apiclient, testClient.getZoneForTests())
        cls.domain        = get_domain(cls.apiclient)
        cls.mgtSvrDetails = cls.config.__dict__["mgtSvr"][0].__dict__
        cls.hypervisor    = testClient.getHypervisorInfo()
        cls.template      = get_template(cls.apiclient, cls.zone.id, cls.hypervisor)
        cls._cleanup      = []

        cls.logger = logging.getLogger("TestNetworkExtensionProvider")
        cls.stream_handler = logging.StreamHandler()
        cls.logger.setLevel(logging.DEBUG)
        cls.logger.addHandler(cls.stream_handler)

        # KVM host credentials from Marvin config
        cls.kvm_host_configs = _get_kvm_hosts_from_config(cls.config)
        cls.logger.info("KVM hosts from config: %s",
                        [h['ip'] for h in cls.kvm_host_configs if h.get('ip')])

        # Download scripts from GitHub once for all tests
        try:
            _ensure_scripts_downloaded()
            cls.logger.info("Scripts cached: %s  %s",
                            WRAPPER_SCRIPT_LOCAL, ENTRY_POINT_SCRIPT_LOCAL)
        except Exception as e:
            cls.logger.warning("Could not download scripts from GitHub: %s", e)

    @classmethod
    def tearDownClass(cls):
        super(TestNetworkExtensionProvider, cls).tearDownClass()

    def setUp(self):
        self.cleanup           = []
        self.provider_id       = None
        self.physical_network  = None
        self.extension         = None
        self.extension_path    = None
        self.mgmt_deployer     = None
        self._mgmt_script_path = None
        self.kvm_deployer      = None

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
        """Return the physical network with Guest traffic type in the test zone.

        Uses the listTrafficTypes API on each physical network to reliably
        detect Guest traffic support.  Falls back to the first physical network
        if none explicitly has Guest traffic.
        """
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
        """Deploy scripts to management server and all KVM hosts.

        Requires self.extension_path to be set before calling.
          network-extension.sh       -> mgmt: <extension_path>/network-extension.sh
          network-extension-wrapper.sh -> each KVM host: EXTENSIONS_DIR/network-extension-wrapper.sh
        """
        _, entry_point_src = _ensure_scripts_downloaded()

        # Management server
        self.mgmt_deployer = MgmtServerDeployer(self.mgtSvrDetails,
                                                logger=self.logger)
        self._mgmt_script_path = os.path.join(self.extension_path, ENTRY_POINT_FILENAME)
        self.mgmt_deployer.copy_file(entry_point_src, self._mgmt_script_path)
        self.logger.info("network-extension.sh deployed to mgmt at %s",
                         self._mgmt_script_path)

        # KVM hosts
        self.kvm_deployer = KvmHostDeployer(config_hosts=self.kvm_host_configs,
                                            logger=self.logger)
        deployed = self.kvm_deployer.deploy()
        self.logger.info("network-extension-wrapper.sh deployed to %d KVM host(s): %s",
                         len(deployed), deployed)

    def _cleanup_mgmt_script(self):
        """Remove network-extension.sh from the management server."""
        if self.mgmt_deployer and self._mgmt_script_path:
            self.mgmt_deployer.remove_file(self._mgmt_script_path)
            self._mgmt_script_path = None

    # ------------------------------------------------------------------
    # Teardown helper
    # ------------------------------------------------------------------

    def _safe_teardown(self):
        """Best-effort cleanup of CloudStack resources."""
        if self.extension and self.physical_network:
            try:
                self.extension.unregister(self.apiclient,
                                          self.physical_network.id, 'PhysicalNetwork')
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
                                      unregisterresources=False, removeactions=False)
            except Exception:
                pass
            self.extension = None
        self._cleanup_mgmt_script()

    # ------------------------------------------------------------------
    # KVM namespace verification helpers
    # ------------------------------------------------------------------

    def _ssh_kvm(self, cmd):
        """Run *cmd* on the first deployed KVM host via SSH.

        Returns (stdout_lines, returncode).  Returns ([], -1) if no KVM
        host is available.
        """
        if not self.kvm_deployer or not self.kvm_deployer._deployed_hosts:
            self.logger.warning("_ssh_kvm: no KVM hosts available — skipping")
            return [], -1
        host_ip = self.kvm_deployer._deployed_hosts[0]
        h = next((x for x in self.kvm_host_configs if x.get('ip') == host_ip), None)
        if h is None:
            return [], -1
        try:
            ssh = SshClient(host_ip, 22,
                            h.get('username', 'root'),
                            h.get('password', ''))
            out = ssh.execute(cmd)
            return out, 0
        except Exception as e:
            self.logger.warning("_ssh_kvm(%s) failed: %s", cmd, e)
            return [], -1

    def _namespace_exists(self, namespace):
        """Return True if *namespace* exists on the KVM host."""
        lines, rc = self._ssh_kvm("ip netns list 2>/dev/null")
        if rc != 0:
            return None  # unknown (no KVM host available)
        return any(namespace in line for line in lines)

    def _namespace_has_ip(self, namespace, ip):
        """Return True if *ip* is assigned inside *namespace* on the KVM host."""
        lines, rc = self._ssh_kvm(
            "ip netns exec %s ip addr 2>/dev/null" % namespace)
        if rc != 0:
            return None  # unknown
        return any(ip in line for line in lines)

    def _assert_namespace_exists(self, namespace, msg=None):
        result = self._namespace_exists(namespace)
        if result is None:
            self.logger.warning("Skipping namespace check (no KVM host): %s", namespace)
            return
        self.assertTrue(result, msg or "Namespace %s should exist" % namespace)

    def _assert_namespace_not_exists(self, namespace, msg=None):
        result = self._namespace_exists(namespace)
        if result is None:
            self.logger.warning("Skipping namespace check (no KVM host): %s", namespace)
            return
        self.assertFalse(result, msg or "Namespace %s should not exist" % namespace)

    def _assert_ip_in_namespace(self, namespace, ip, msg=None):
        result = self._namespace_has_ip(namespace, ip)
        if result is None:
            self.logger.warning("Skipping IP check (no KVM host): %s in %s", ip, namespace)
            return
        self.assertTrue(result,
                        msg or "IP %s should be in namespace %s" % (ip, namespace))

    def _assert_ip_not_in_namespace(self, namespace, ip, msg=None):
        result = self._namespace_has_ip(namespace, ip)
        if result is None:
            self.logger.warning("Skipping IP check (no KVM host): %s in %s", ip, namespace)
            return
        self.assertFalse(result,
                         msg or "IP %s should NOT be in namespace %s" % (ip, namespace))

    # ------------------------------------------------------------------
    # Tests
    # ------------------------------------------------------------------

    @attr(tags=["advanced", "smoke"], required_hardware="false")
    def test_01_external_network_full_lifecycle(self):
        """Full lifecycle: extension → network → VM → NAT → PF → teardown.

        1.  Get physical network (Guest traffic type).
        2.  Create NetworkOrchestrator extension.
        3.  Deploy network-extension.sh to management server.
        4.  Deploy network-extension-wrapper.sh to all KVM hosts.
        5.  Register extension to physical network with KVM hosts + credentials.
        6.  Enable the NSP auto-created by the registration.
        7.  Create NetworkOffering backed by the extension.
        8.  Create account + isolated network.
        9.  Deploy VM.
        10. implement — source NAT IP must be present in the namespace.
        11. Enable static NAT — IP added to namespace.
        12. Disable static NAT — IP removed from namespace.
        13. Add port forwarding rule — IP added to namespace.
        14. Delete port forwarding rule — IP removed from namespace.
        15. Shutdown / destroy network — namespace removed.
        16. Cleanup CloudStack resources, extension, scripts.
        """

        # ---- Step 1: Physical network ----
        self.physical_network = self._get_physical_network()

        # ---- Step 2: Create extension ----
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
        self.extension_path = ext_list[0].path
        self.assertIsNotNone(self.extension_path)
        self.logger.info("Extension '%s' created, path=%s", ext_name, self.extension_path)

        # ---- Steps 3-4: Deploy scripts ----
        self._deploy_scripts()

        # ---- Step 5: Register extension with physical network ----
        kvm_hosts_csv = self.kvm_deployer.host_ips_csv()
        if not kvm_hosts_csv:
            self.fail("No KVM hosts available — cannot register extension")

        register_details = [
            {"key": "hosts",    "value": kvm_hosts_csv},
            {"key": "username", "value": self.kvm_host_configs[0].get('username', 'root')},
            {"key": "password", "value": self.kvm_host_configs[0].get('password', '')},
        ]
        self.extension.register(
            self.apiclient,
            self.physical_network.id,
            'PhysicalNetwork',
            details=register_details
        )
        self.logger.info("Extension registered to physical network %s, hosts=%s",
                         self.physical_network.id, kvm_hosts_csv)

        # ---- Step 6: Enable NSP ----
        provider = self._find_provider(self.physical_network.id, ext_name)
        self.assertIsNotNone(provider,
                             "NSP '%s' not found after extension registration" % ext_name)
        self.provider_id = provider.id
        if provider.state != 'Enabled':
            self._update_provider_state(provider.id, 'Enabled')
        self.assertEqual('Enabled',
                         self._find_provider(self.physical_network.id, ext_name).state)
        self.logger.info("NSP '%s' enabled", ext_name)

        # ---- Step 7: Network offering ----
        nw_offering = NetworkOffering.create(self.apiclient, {
            "name":              "ExtNet-Offering-%s" % random_gen(),
            "displaytext":       "ExtNet smoke-test offering",
            "guestiptype":       "Isolated",
            "traffictype":       "GUEST",
            "supportedservices": "SourceNat,StaticNat,PortForwarding,Firewall",
            "serviceProviderList": {
                "SourceNat":      ext_name,
                "StaticNat":      ext_name,
                "PortForwarding": ext_name,
                "Firewall":       ext_name,
            },
            "serviceCapabilityList": {
                "SourceNat": {"SupportedSourceNatTypes": "peraccount"},
            },
        })
        self.cleanup.append(nw_offering)
        nw_offering.update(self.apiclient, state='Enabled')
        self.logger.info("Network offering '%s' created and enabled", nw_offering.name)

        # ---- Step 8a: Account ----
        account = Account.create(
            self.apiclient,
            self.services["account"],
            admin=True,
            domainid=self.domain.id
        )
        self.cleanup.append(account)

        # ---- Step 8b: Isolated network ----
        network = Network.create(
            self.apiclient,
            {"name": "extnet-smoke-net", "displaytext": "ExtNet smoke test network"},
            accountid=account.name,
            domainid=account.domainid,
            networkofferingid=nw_offering.id,
            zoneid=self.zone.id
        )
        self.cleanup.insert(0, network)
        self.assertIsNotNone(network)
        self.logger.info("Isolated network created: %s (%s)", network.name, network.id)

        # Derive expected namespace name (cs-net-<networkId>)
        namespace = "cs-net-%s" % network.id

        # ---- Step 9: Deploy VM — triggers implement ----
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

        # ---- Step 10: verify implement — namespace exists + source NAT IP present ----
        self._assert_namespace_exists(namespace,
            "Namespace %s should exist after network implement" % namespace)
        self.logger.info("Verified: namespace %s exists after implement", namespace)

        # Acquire the source NAT IP and verify it is in the namespace
        source_nat_ip_obj = PublicIPAddress.create(
            self.apiclient,
            accountid=account.name,
            zoneid=self.zone.id,
            domainid=account.domainid,
            networkid=network.id,
            isSourceNat=True
        )
        source_nat_ip = source_nat_ip_obj.ipaddress.ipaddress
        self.logger.info("Source NAT IP: %s", source_nat_ip)

        self._assert_ip_in_namespace(namespace, source_nat_ip,
            "Source NAT IP %s should be assigned in namespace %s after implement"
            % (source_nat_ip, namespace))
        self.logger.info("Verified: source NAT IP %s in namespace %s", source_nat_ip, namespace)

        # ---- Step 11: Static NAT — enable → IP in namespace ----
        static_ip = PublicIPAddress.create(
            self.apiclient,
            accountid=account.name,
            zoneid=self.zone.id,
            domainid=account.domainid,
            networkid=network.id
        )
        static_ip_addr = static_ip.ipaddress.ipaddress
        static_ip_id   = static_ip.ipaddress.id
        self.logger.info("Static NAT public IP: %s", static_ip_addr)

        StaticNATRule.enable(self.apiclient,
                             ipaddressid=static_ip_id,
                             virtualmachineid=vm.id,
                             networkid=network.id)
        self.logger.info("Static NAT enabled")

        self._assert_ip_in_namespace(namespace, static_ip_addr,
            "Static NAT IP %s should be in namespace %s after add-static-nat"
            % (static_ip_addr, namespace))
        self.logger.info("Verified: static NAT IP %s in namespace %s", static_ip_addr, namespace)

        # ---- Step 12: Static NAT — disable → IP removed from namespace ----
        StaticNATRule.disable(self.apiclient, ipaddressid=static_ip_id)
        self.logger.info("Static NAT disabled")

        self._assert_ip_not_in_namespace(namespace, static_ip_addr,
            "Static NAT IP %s should NOT be in namespace %s after delete-static-nat"
            % (static_ip_addr, namespace))
        self.logger.info("Verified: static NAT IP %s removed from namespace %s",
                         static_ip_addr, namespace)
        static_ip.delete(self.apiclient)

        # ---- Step 13: Port forwarding — create rule → IP in namespace ----
        pf_ip = PublicIPAddress.create(
            self.apiclient,
            accountid=account.name,
            zoneid=self.zone.id,
            domainid=account.domainid,
            networkid=network.id
        )
        pf_ip_addr = pf_ip.ipaddress.ipaddress
        pf_rule = NATRule.create(
            self.apiclient, vm,
            {"privateport": 22, "publicport": 2222, "protocol": "TCP"},
            ipaddressid=pf_ip.ipaddress.id,
            networkid=network.id
        )
        self.assertIsNotNone(pf_rule)
        self.logger.info("Port forwarding rule created: %s:2222 -> VM:22", pf_ip_addr)

        self._assert_ip_in_namespace(namespace, pf_ip_addr,
            "Port forwarding IP %s should be in namespace %s after add-port-forward"
            % (pf_ip_addr, namespace))
        self.logger.info("Verified: PF IP %s in namespace %s", pf_ip_addr, namespace)

        # ---- Step 14: Port forwarding — delete rule → IP removed from namespace ----
        pf_rule.delete(self.apiclient)
        self.logger.info("Port forwarding rule deleted")

        self._assert_ip_not_in_namespace(namespace, pf_ip_addr,
            "Port forwarding IP %s should NOT be in namespace %s after delete-port-forward"
            % (pf_ip_addr, namespace))
        self.logger.info("Verified: PF IP %s removed from namespace %s", pf_ip_addr, namespace)
        pf_ip.delete(self.apiclient)
        source_nat_ip_obj.delete(self.apiclient)

        # ---- Step 15: Destroy VM → shutdown + destroy network → namespace removed ----
        vm.delete(self.apiclient, expunge=True)
        self.cleanup = [o for o in self.cleanup if o != vm]
        self.logger.info("VM destroyed")

        network.delete(self.apiclient)
        self.cleanup = [o for o in self.cleanup if o != network]
        self.logger.info("Network deleted")

        self._assert_namespace_not_exists(namespace,
            "Namespace %s should be removed after network destroy" % namespace)
        self.logger.info("Verified: namespace %s removed after destroy", namespace)

        # ---- Step 16: Cleanup ----
        self._update_provider_state(self.provider_id, 'Disabled')
        self._delete_provider(self.provider_id)
        self.provider_id = None

        self.extension.unregister(self.apiclient,
                                  self.physical_network.id, 'PhysicalNetwork')
        self.extension.delete(self.apiclient,
                              unregisterresources=False, removeactions=False)
        self.extension      = None
        self.physical_network = None

        self._cleanup_mgmt_script()
        self.logger.info("test_01 PASSED")

    @attr(tags=["advanced", "smoke"], required_hardware="false")
    def test_02_provider_state_transitions(self):
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

        self.logger.info("test_02 PASSED")

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
        self.logger.info("test_03 PASSED")

    @attr(tags=["advanced", "smoke"], required_hardware="false")
    def test_04_extension_enable_disable_and_delete_restriction(self):
        """Extension enable/disable; deletion blocked while registered."""
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
        self.logger.info("test_04 PASSED")

