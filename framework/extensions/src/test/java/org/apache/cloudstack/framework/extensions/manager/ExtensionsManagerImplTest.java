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

package org.apache.cloudstack.framework.extensions.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.security.InvalidParameterException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.cloud.exception.PermissionDeniedException;
import com.cloud.user.AccountService;
import org.apache.cloudstack.acl.Role;
import org.apache.cloudstack.acl.RoleService;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.response.ExtensionCustomActionResponse;
import org.apache.cloudstack.api.response.ExtensionResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.extension.CustomActionResultResponse;
import org.apache.cloudstack.extension.Extension;
import org.apache.cloudstack.extension.ExtensionCustomAction;
import org.apache.cloudstack.extension.ExtensionResourceMap;
import org.apache.cloudstack.framework.extensions.api.AddCustomActionCmd;
import org.apache.cloudstack.framework.extensions.api.CreateExtensionCmd;
import org.apache.cloudstack.framework.extensions.api.DeleteCustomActionCmd;
import org.apache.cloudstack.framework.extensions.api.DeleteExtensionCmd;
import org.apache.cloudstack.framework.extensions.api.ListCustomActionCmd;
import org.apache.cloudstack.framework.extensions.api.ListExtensionsCmd;
import org.apache.cloudstack.framework.extensions.api.RegisterExtensionCmd;
import org.apache.cloudstack.framework.extensions.api.RunCustomActionCmd;
import org.apache.cloudstack.framework.extensions.api.UnregisterExtensionCmd;
import org.apache.cloudstack.framework.extensions.api.UpdateCustomActionCmd;
import org.apache.cloudstack.framework.extensions.api.UpdateExtensionCmd;
import org.apache.cloudstack.framework.extensions.command.CleanupExtensionFilesCommand;
import org.apache.cloudstack.framework.extensions.command.ExtensionServerActionBaseCommand;
import org.apache.cloudstack.framework.extensions.command.GetExtensionPathChecksumCommand;
import org.apache.cloudstack.framework.extensions.command.PrepareExtensionPathCommand;
import org.apache.cloudstack.framework.extensions.dao.ExtensionCustomActionDao;
import org.apache.cloudstack.framework.extensions.dao.ExtensionCustomActionDetailsDao;
import org.apache.cloudstack.framework.extensions.dao.ExtensionDao;
import org.apache.cloudstack.framework.extensions.dao.ExtensionDetailsDao;
import org.apache.cloudstack.framework.extensions.dao.ExtensionResourceMapDao;
import org.apache.cloudstack.framework.extensions.dao.ExtensionResourceMapDetailsDao;
import org.apache.cloudstack.framework.extensions.vo.ExtensionCustomActionDetailsVO;
import org.apache.cloudstack.framework.extensions.vo.ExtensionCustomActionVO;
import org.apache.cloudstack.framework.extensions.vo.ExtensionResourceMapDetailsVO;
import org.apache.cloudstack.framework.extensions.vo.ExtensionResourceMapVO;
import org.apache.cloudstack.framework.extensions.vo.ExtensionVO;
import org.apache.cloudstack.utils.identity.ManagementServerNode;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.RunCustomActionAnswer;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.alert.AlertManager;
import com.cloud.cluster.ClusterManager;
import com.cloud.cluster.ManagementServerHostVO;
import com.cloud.cluster.dao.ManagementServerHostDao;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.host.Host;
import com.cloud.host.dao.HostDao;
import com.cloud.host.dao.HostDetailsDao;
import com.cloud.hypervisor.ExternalProvisioner;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.network.Network;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkServiceMapDao;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.dao.PhysicalNetworkVO;
import org.apache.cloudstack.extension.NetworkCustomActionProvider;
import com.cloud.org.Cluster;
import com.cloud.serializer.GsonHelper;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.user.Account;
import com.cloud.utils.Pair;
import com.cloud.utils.UuidUtils;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.VmDetailConstants;
import com.cloud.vm.dao.VMInstanceDao;

@RunWith(MockitoJUnitRunner.Silent.class)
public class ExtensionsManagerImplTest {

    @Spy
    @InjectMocks
    private ExtensionsManagerImpl extensionsManager;

    @Mock
    private ExtensionDao extensionDao;
    @Mock
    private ExtensionDetailsDao extensionDetailsDao;
    @Mock
    private ExtensionResourceMapDao extensionResourceMapDao;
    @Mock
    private ExtensionResourceMapDetailsDao extensionResourceMapDetailsDao;
    @Mock
    private ClusterDao clusterDao;
    @Mock
    private AgentManager agentMgr;
    @Mock
    private HostDao hostDao;
    @Mock
    private HostDetailsDao hostDetailsDao;
    @Mock
    private ExternalProvisioner externalProvisioner;
    @Mock
    private ExtensionCustomActionDao extensionCustomActionDao;
    @Mock
    private ExtensionCustomActionDetailsDao extensionCustomActionDetailsDao;
    @Mock
    private VMInstanceDao vmInstanceDao;
    @Mock
    private VirtualMachineManager virtualMachineManager;
    @Mock
    private EntityManager entityManager;
    @Mock
    private ManagementServerHostDao managementServerHostDao;
    @Mock
    private ClusterManager clusterManager;
    @Mock
    private AlertManager alertManager;
    @Mock
    private VMTemplateDao templateDao;
    @Mock
    private RoleService roleService;
    @Mock
    private AccountService accountService;
    @Mock
    private PhysicalNetworkDao physicalNetworkDao;
    @Mock
    private NetworkDao networkDao;
    @Mock
    private NetworkServiceMapDao networkServiceMapDao;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    public void getDefaultExtensionRelativePathReturnsExpectedPath() {
        String name = "testExtension";
        String expected = Extension.getDirectoryName(name) + File.separator + Extension.getDirectoryName(name) + ".sh";
        String result = extensionsManager.getDefaultExtensionRelativePath(name);
        assertEquals(expected, result);
    }

    @Test
    public void getValidatedExtensionRelativePathReturnsNormalizedPath() {
        String name = "ext";
        String path = "ext/entry.sh";
        String result = extensionsManager.getValidatedExtensionRelativePath(name, path);
        assertTrue(result.startsWith("ext/"));
    }

    @Test(expected = InvalidParameterException.class)
    public void getValidatedExtensionRelativePathThrowsForDeepPath() {
        String name = "ext";
        String path = "ext/a/b/c/entry.sh";
        extensionsManager.getValidatedExtensionRelativePath(name, path);
    }

    @Test
    public void getResultFromAnswersStringReturnsSuccess() {
        Extension ext = mock(Extension.class);
        Answer[] answers = new Answer[]{new Answer(mock(PrepareExtensionPathCommand.class), true, "ok")};
        String json = GsonHelper.getGson().toJson(answers);
        ManagementServerHostVO msHost = mock(ManagementServerHostVO.class);
        Pair<Boolean, String> result = extensionsManager.getResultFromAnswersString(json, ext, msHost, "op");
        assertTrue(result.first());
        assertEquals("ok", result.second());
    }

    @Test
    public void getResultFromAnswersStringReturnsFailure() {
        Extension ext = mock(Extension.class);
        Answer[] answers = new Answer[]{new Answer(mock(PrepareExtensionPathCommand.class), false, "fail")};
        String json = GsonHelper.getGson().toJson(answers);
        ManagementServerHostVO msHost = mock(ManagementServerHostVO.class);
        Pair<Boolean, String> result = extensionsManager.getResultFromAnswersString(json, ext, msHost, "op");
        assertFalse(result.first());
        assertEquals("fail", result.second());
    }

    @Test
    public void prepareExtensionPathOnMSPeerReturnsTrueOnSuccess() {
        Extension ext = mock(Extension.class);
        ManagementServerHostVO msHost = mock(ManagementServerHostVO.class);
        when(msHost.getMsid()).thenReturn(1L);
        when(clusterManager.execute(anyString(), anyLong(), anyString(), eq(true)))
                .thenReturn("answer");
        doReturn(new Pair<>(true, "ok")).when(extensionsManager).getResultFromAnswersString(anyString(), eq(ext), eq(msHost), anyString());
        assertTrue(extensionsManager.prepareExtensionPathOnMSPeer(ext, msHost));
    }

    @Test
    public void prepareExtensionPathOnCurrentServerReturnsSuccess() {
        doNothing().when(externalProvisioner).prepareExtensionPath(anyString(), anyBoolean(), anyString());
        Pair<Boolean, String> result = extensionsManager.prepareExtensionPathOnCurrentServer("name", true, "entry");
        assertTrue(result.first());
        assertNull(result.second());
    }

    @Test
    public void cleanupExtensionFilesOnMSPeerReturnsTrueOnSuccess() {
        Extension ext = mock(Extension.class);
        ManagementServerHostVO msHost = mock(ManagementServerHostVO.class);
        when(msHost.getMsid()).thenReturn(1L);
        when(clusterManager.execute(anyString(), anyLong(), anyString(), eq(true)))
                .thenReturn("answer");
        doReturn(new Pair<>(true, "ok")).when(extensionsManager).getResultFromAnswersString(anyString(), eq(ext), eq(msHost), anyString());
        assertTrue(extensionsManager.cleanupExtensionFilesOnMSPeer(ext, msHost));
    }

    @Test
    public void cleanupExtensionFilesOnCurrentServerReturnsSuccess() {
        Pair<Boolean, String> result = extensionsManager.cleanupExtensionFilesOnCurrentServer("name", "entry");
        assertTrue(result.first());
    }

    @Test
    public void getParametersListFromMapReturnsEmptyListForNull() {
        List<ExtensionCustomAction.Parameter> result = extensionsManager.getParametersListFromMap("action", null);
        assertTrue(result.isEmpty());
    }

    @Test(expected = InvalidParameterValueException.class)
    public void unregisterExtensionWithClusterThrowsIfClusterNotFound() {
        when(clusterDao.findByUuid(anyString())).thenReturn(null);
        extensionsManager.unregisterExtensionWithCluster("uuid", 1L);
    }

    @Test
    public void getExtensionFromResourceReturnsNullIfEntityNotFound() {
        when(entityManager.findByUuid(any(), anyString())).thenReturn(null);
        assertNull(extensionsManager.getExtensionFromResource(ExtensionCustomAction.ResourceType.VirtualMachine, "uuid"));
    }

    @Test
    public void getActionMessageReturnsDefaultOnBlank() {
        ExtensionCustomAction action = mock(ExtensionCustomAction.class);
        Extension ext = mock(Extension.class);
        when(action.getSuccessMessage()).thenReturn(null);
        String msg = extensionsManager.getActionMessage(true, action, ext, ExtensionCustomAction.ResourceType.VirtualMachine, null);
        assertTrue(msg.contains("Successfully completed"));
    }

    @Test
    public void getActionMessageReturnsDefaultMessageForSuccessWithoutCustomMessage() {
        ExtensionCustomAction action = mock(ExtensionCustomAction.class);
        Extension extension = mock(Extension.class);
        when(action.getSuccessMessage()).thenReturn(null);

        String result = extensionsManager.getActionMessage(true, action, extension, ExtensionCustomAction.ResourceType.VirtualMachine, null);

        assertTrue(result.contains("Successfully completed"));
    }

    @Test
    public void getActionMessageReturnsCustomSuccessMessage() {
        ExtensionCustomAction action = mock(ExtensionCustomAction.class);
        when(action.getName()).thenReturn("actionName");
        Extension extension = mock(Extension.class);
        when(extension.getName()).thenReturn("extension");
        when(action.getSuccessMessage()).thenReturn("Custom success message");
        String result = extensionsManager.getActionMessage(true, action, extension, ExtensionCustomAction.ResourceType.VirtualMachine, null);
        assertEquals("Custom success message", result);
    }

    @Test
    public void getActionMessageReturnsDefaultMessageForFailureWithoutCustomMessage() {
        ExtensionCustomAction action = mock(ExtensionCustomAction.class);
        Extension extension = mock(Extension.class);
        when(action.getErrorMessage()).thenReturn(null);

        String result = extensionsManager.getActionMessage(false, action, extension, ExtensionCustomAction.ResourceType.VirtualMachine, null);

        assertTrue(result.contains("Failed to complete"));
    }

    @Test
    public void getActionMessageReturnsCustomFailureMessage() {
        ExtensionCustomAction action = mock(ExtensionCustomAction.class);
        when(action.getName()).thenReturn("actionName");
        Extension extension = mock(Extension.class);
        when(extension.getName()).thenReturn("extension");
        when(action.getErrorMessage()).thenReturn("Custom failure message");
        String result = extensionsManager.getActionMessage(false, action, extension, ExtensionCustomAction.ResourceType.VirtualMachine, null);
        assertEquals("Custom failure message", result);
    }

    @Test
    public void getActionMessageHandlesNullActionMessage() {
        ExtensionCustomAction action = mock(ExtensionCustomAction.class);
        when(action.getSuccessMessage()).thenReturn(null);
        Extension extension = mock(Extension.class);
        String result = extensionsManager.getActionMessage(true, action, extension, ExtensionCustomAction.ResourceType.VirtualMachine, null);
        assertTrue(result.contains("Successfully completed"));
    }

    @Test
    public void getFilteredExternalDetailsReturnsFilteredMap() {
        Map<String, String> details = new HashMap<>();
        String key = "detail.key";
        details.put(VmDetailConstants.EXTERNAL_DETAIL_PREFIX + key, "value");
        details.put("other.key", "value2");
        Map<String, String> filtered = extensionsManager.getFilteredExternalDetails(details);
        assertTrue(filtered.containsKey(key));
        assertFalse(filtered.containsKey("other.key"));
    }

    @Test
    public void sendExtensionPathNotReadyAlertCallsAlertManager() {
        Extension ext = mock(Extension.class);
        when(ext.getState()).thenReturn(Extension.State.Enabled);
        extensionsManager.sendExtensionPathNotReadyAlert(ext);
        verify(alertManager, atLeastOnce()).sendAlert(eq(AlertManager.AlertType.ALERT_TYPE_EXTENSION_PATH_NOT_READY),
                anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    public void sendExtensionPathNotReadyAlertDoesNotCallsAlertManager() {
        Extension ext = mock(Extension.class);
        when(ext.getState()).thenReturn(Extension.State.Disabled);
        extensionsManager.sendExtensionPathNotReadyAlert(ext);
        verify(alertManager, never()).sendAlert(eq(AlertManager.AlertType.ALERT_TYPE_EXTENSION_PATH_NOT_READY),
                anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    public void updateExtensionPathReadyUpdatesWhenStateDiffers() {
        Extension ext = mock(Extension.class);
        when(ext.getId()).thenReturn(1L);
        when(ext.isPathReady()).thenReturn(false);
        ExtensionVO vo = mock(ExtensionVO.class);
        when(extensionDao.createForUpdate(1L)).thenReturn(vo);
        when(extensionDao.update(1L, vo)).thenReturn(true);
        extensionsManager.updateExtensionPathReady(ext, true);
        verify(extensionDao).update(1L, vo);
    }

    @Test
    public void disableExtensionUpdatesState() {
        ExtensionVO vo = mock(ExtensionVO.class);
        when(extensionDao.createForUpdate(1L)).thenReturn(vo);
        when(extensionDao.update(1L, vo)).thenReturn(true);
        extensionsManager.disableExtension(1L);
        verify(extensionDao).update(1L, vo);
    }

    @Test
    public void getExtensionFromResourceReturnsExtensionForValidResource() {
        VirtualMachine vm = mock(VirtualMachine.class);
        when(entityManager.findByUuid(eq(VirtualMachine.class), eq("vm-uuid"))).thenReturn(vm);
        when(virtualMachineManager.findClusterAndHostIdForVm(vm, false)).thenReturn(new Pair<>(1L, 1L));
        ExtensionResourceMapVO mapVO = mock(ExtensionResourceMapVO.class);
        when(mapVO.getExtensionId()).thenReturn(100L);
        when(extensionResourceMapDao.findByResourceIdAndType(1L, ExtensionResourceMap.ResourceType.Cluster)).thenReturn(mapVO);
        ExtensionVO extension = mock(ExtensionVO.class);
        when(extensionDao.findById(100L)).thenReturn(extension);

        Extension result = extensionsManager.getExtensionFromResource(ExtensionCustomAction.ResourceType.VirtualMachine, "vm-uuid");

        assertEquals(extension, result);
    }

    @Test
    public void getExtensionFromResourceReturnsNullForInvalidResourceUuid() {
        when(entityManager.findByUuid(eq(VirtualMachine.class), eq("invalid-uuid"))).thenReturn(null);

        Extension result = extensionsManager.getExtensionFromResource(ExtensionCustomAction.ResourceType.VirtualMachine, "invalid-uuid");

        assertNull(result);
    }

    @Test
    public void getExtensionFromResourceReturnsNullForMissingClusterMapping() {
        VirtualMachine vm = mock(VirtualMachine.class);
        when(entityManager.findByUuid(eq(VirtualMachine.class), eq("vm-uuid"))).thenReturn(vm);
        when(virtualMachineManager.findClusterAndHostIdForVm(vm, false)).thenReturn(new Pair<>(null, null));

        Extension result = extensionsManager.getExtensionFromResource(ExtensionCustomAction.ResourceType.VirtualMachine, "vm-uuid");

        assertNull(result);
    }

    @Test
    public void getExtensionFromResourceReturnsNullForMissingExtensionMapping() {
        VirtualMachine vm = mock(VirtualMachine.class);
        when(entityManager.findByUuid(eq(VirtualMachine.class), eq("vm-uuid"))).thenReturn(vm);
        when(virtualMachineManager.findClusterAndHostIdForVm(vm, false)).thenReturn(new Pair<>(1L, 1L));
        when(extensionResourceMapDao.findByResourceIdAndType(1L, ExtensionResourceMap.ResourceType.Cluster)).thenReturn(null);

        Extension result = extensionsManager.getExtensionFromResource(ExtensionCustomAction.ResourceType.VirtualMachine, "vm-uuid");

        assertNull(result);
    }

    @Test
    public void updateExtensionPathReadyUpdatesStateWhenNotReady() {
        Extension ext = mock(Extension.class);
        when(ext.getId()).thenReturn(1L);
        when(ext.isPathReady()).thenReturn(true);
        ExtensionVO vo = mock(ExtensionVO.class);
        when(extensionDao.createForUpdate(1L)).thenReturn(vo);
        when(extensionDao.update(1L, vo)).thenReturn(true);

        extensionsManager.updateExtensionPathReady(ext, false);

        verify(extensionDao).update(1L, vo);
    }

    @Test
    public void updateExtensionPathReadyDoesNotUpdateWhenStateUnchanged() {
        Extension ext = mock(Extension.class);
        when(ext.isPathReady()).thenReturn(true);
        extensionsManager.updateExtensionPathReady(ext, true);
        verify(extensionDao, never()).update(anyLong(), any());
    }

    @Test
    public void disableExtensionChangesStateToDisabled() {
        ExtensionVO vo = mock(ExtensionVO.class);
        when(extensionDao.createForUpdate(1L)).thenReturn(vo);
        when(extensionDao.update(1L, vo)).thenReturn(true);

        extensionsManager.disableExtension(1L);

        verify(vo).setState(Extension.State.Disabled);
        verify(extensionDao).update(1L, vo);
    }

    @Test
    public void updateAllExtensionHostsRemovesHostsSuccessfully() throws OperationTimedoutException, AgentUnavailableException {
        Extension extension = mock(Extension.class);
        when(extension.getId()).thenReturn(1L);
        Long clusterId = 100L;
        Long hostId = 200L;
        when(hostDao.listIdsByClusterId(clusterId)).thenReturn(List.of(hostId));
        extensionsManager.updateAllExtensionHosts(extension, clusterId, true);
        verify(agentMgr).send(eq(hostId), any(Command.class));
    }

    @Test
    public void updateAllExtensionHostsAddsHostsSuccessfully() throws OperationTimedoutException, AgentUnavailableException {
        Extension extension = mock(Extension.class);
        when(extension.getId()).thenReturn(1L);
        Long clusterId = 100L;
        Long hostId = 200L;
        when(hostDao.listIdsByClusterId(clusterId)).thenReturn(List.of(hostId));
        extensionsManager.updateAllExtensionHosts(extension, clusterId, false);
        verify(agentMgr).send(eq(hostId), any(Command.class));
    }

    @Test
    public void updateAllExtensionHostsHandlesEmptyHostListGracefully() throws OperationTimedoutException, AgentUnavailableException {
        Extension extension = mock(Extension.class);
        Long clusterId = 100L;
        when(hostDao.listIdsByClusterId(clusterId)).thenReturn(Collections.emptyList());
        extensionsManager.updateAllExtensionHosts(extension, clusterId, false);
        verify(agentMgr, never()).send(anyLong(), any(Command.class));
    }

    @Test
    public void updateAllExtensionHostsHandlesNullClusterId() throws OperationTimedoutException, AgentUnavailableException {
        Extension extension = mock(Extension.class);
        when(extension.getId()).thenReturn(1L);
        when(extensionResourceMapDao.listResourceIdsByExtensionIdAndType(eq(1L), any())).thenReturn(Collections.emptyList());
        extensionsManager.updateAllExtensionHosts(extension, null, false);
        verify(agentMgr, never()).send(anyLong(), any(Command.class));
    }

    @Test
    public void getExternalAccessDetailsReturnsMapWithHostAndExtension() {
        Map<String, String> map = new HashMap<>();
        map.put("external.detail.key", "value");
        long hostId = 1L;
        ExtensionResourceMap resourceMap = mock(ExtensionResourceMap.class);
        when(resourceMap.getId()).thenReturn(2L);
        when(resourceMap.getExtensionId()).thenReturn(3L);
        when(hostDetailsDao.findDetails(hostId)).thenReturn(null);
        when(extensionResourceMapDetailsDao.listDetailsKeyPairs(2L, true)).thenReturn(Collections.emptyMap());
        when(extensionDetailsDao.listDetailsKeyPairs(3L, true)).thenReturn(map);
        try (MockedStatic<CallContext> ignored = mockStatic(CallContext.class)) {
            mockCallerRole(RoleType.Admin);
            Map<String, Map<String, String>> result = extensionsManager.getExternalAccessDetails(map, hostId, resourceMap);
            assertTrue(result.containsKey(ApiConstants.ACTION));
            assertFalse(result.containsKey(ApiConstants.HOST));
            assertFalse(result.containsKey(ApiConstants.RESOURCE_MAP));
            assertTrue(result.containsKey(ApiConstants.EXTENSION));
            assertTrue(result.containsKey(ApiConstants.CALLER));
        }
    }

    @Test(expected = CloudRuntimeException.class)
    public void checkOrchestratorTemplatesThrowsIfTemplatesExist() {
        when(templateDao.listIdsByExtensionId(1L)).thenReturn(Arrays.asList(1L, 2L));
        extensionsManager.checkOrchestratorTemplates(1L);
    }

    @Test
    public void getExtensionsPathReturnsProvisionerPath() {
        when(externalProvisioner.getExtensionsPath()).thenReturn("/tmp/extensions");
        assertEquals("/tmp/extensions", extensionsManager.getExtensionsPath());
    }

    @Test
    public void getExtensionIdForClusterReturnsNullIfNoMap() {
        when(extensionResourceMapDao.findByResourceIdAndType(anyLong(), any())).thenReturn(null);
        assertNull(extensionsManager.getExtensionIdForCluster(1L));
    }

    @Test
    public void getExtensionIdForClusterReturnsIdIfMapExists() {
        ExtensionResourceMapVO map = mock(ExtensionResourceMapVO.class);
        when(map.getExtensionId()).thenReturn(5L);
        when(extensionResourceMapDao.findByResourceIdAndType(anyLong(), any())).thenReturn(map);
        assertEquals(Long.valueOf(5L), extensionsManager.getExtensionIdForCluster(1L));
    }

    @Test
    public void getExtensionReturnsExtension() {
        ExtensionVO ext = mock(ExtensionVO.class);
        when(extensionDao.findById(1L)).thenReturn(ext);
        assertEquals(ext, extensionsManager.getExtension(1L));
    }

    @Test
    public void getExtensionForClusterReturnsNullIfNoId() {
        when(extensionResourceMapDao.findByResourceIdAndType(anyLong(), any())).thenReturn(null);
        assertNull(extensionsManager.getExtensionForCluster(1L));
    }

    @Test
    public void getExtensionForClusterReturnsExtensionIfIdExists() {
        ExtensionResourceMapVO map = mock(ExtensionResourceMapVO.class);
        when(map.getExtensionId()).thenReturn(5L);
        when(extensionResourceMapDao.findByResourceIdAndType(anyLong(), any())).thenReturn(map);
        ExtensionVO ext = mock(ExtensionVO.class);
        when(extensionDao.findById(5L)).thenReturn(ext);
        assertEquals(ext, extensionsManager.getExtensionForCluster(1L));
    }

    @Test
    public void checkExtensionPathSyncUpdatesReadyWhenChecksumIsBlank() {
        Extension ext = mock(Extension.class);
        when(ext.getName()).thenReturn("ext");
        when(ext.getRelativePath()).thenReturn("entry.sh");
        when(externalProvisioner.getChecksumForExtensionPath("ext", "entry.sh")).thenReturn("");

        extensionsManager.checkExtensionPathState(ext, Collections.emptyList());

        verify(extensionsManager).updateExtensionPathReady(ext, false);
    }

    @Test
    public void checkExtensionPathSyncUpdatesReadyWhenNoHostsProvided() {
        ExtensionVO ext = mock(ExtensionVO.class);
        when(ext.getName()).thenReturn("ext");
        when(ext.getRelativePath()).thenReturn("entry.sh");
        when(externalProvisioner.getChecksumForExtensionPath("ext", "entry.sh")).thenReturn("checksum123");
        when(extensionDao.createForUpdate(anyLong())).thenReturn(ext);
        extensionsManager.checkExtensionPathState(ext, Collections.emptyList());
        verify(extensionsManager).updateExtensionPathReady(ext, true);
    }

    @Test
    public void checkExtensionPathSyncUpdatesReadyWhenChecksumsMatchAcrossHosts() {
        ExtensionVO ext = mock(ExtensionVO.class);
        when(ext.getName()).thenReturn("ext");
        when(ext.getRelativePath()).thenReturn("entry.sh");
        when(externalProvisioner.getChecksumForExtensionPath("ext", "entry.sh")).thenReturn("checksum123");
        when(extensionDao.createForUpdate(anyLong())).thenReturn(ext);
        ManagementServerHostVO msHost = mock(ManagementServerHostVO.class);
        doReturn(new Pair<>(true, "checksum123")).when(extensionsManager).getChecksumForExtensionPathOnMSPeer(ext, msHost);
        extensionsManager.checkExtensionPathState(ext, Collections.singletonList(msHost));
        verify(extensionsManager).updateExtensionPathReady(ext, true);
    }

    @Test
    public void checkExtensionPathStateUpdatesNotReadyWhenChecksumsDifferAcrossHosts() {
        Extension ext = mock(Extension.class);
        when(ext.getName()).thenReturn("ext");
        when(ext.getRelativePath()).thenReturn("entry.sh");
        when(externalProvisioner.getChecksumForExtensionPath("ext", "entry.sh")).thenReturn("checksum123");
        ManagementServerHostVO msHost = mock(ManagementServerHostVO.class);
        when(msHost.getMsid()).thenReturn(1L);
        doReturn(new Pair<>(true, "checksum456")).when(extensionsManager).getChecksumForExtensionPathOnMSPeer(ext, msHost);
        extensionsManager.checkExtensionPathState(ext, Collections.singletonList(msHost));
        verify(extensionsManager).updateExtensionPathReady(ext, false);
    }

    @Test
    public void checkExtensionPathStateUpdatesNotReadyWhenPeerChecksumFails() {
        Extension ext = mock(Extension.class);
        when(ext.getName()).thenReturn("ext");
        when(ext.getRelativePath()).thenReturn("entry.sh");
        when(externalProvisioner.getChecksumForExtensionPath("ext", "entry.sh")).thenReturn("checksum123");

        ManagementServerHostVO msHost = mock(ManagementServerHostVO.class);
        when(msHost.getMsid()).thenReturn(1L);
        doReturn(new Pair<>(false, null)).when(extensionsManager).getChecksumForExtensionPathOnMSPeer(ext, msHost);

        extensionsManager.checkExtensionPathState(ext, Collections.singletonList(msHost));

        verify(extensionsManager).updateExtensionPathReady(ext, false);
    }

    @Test
    public void testCreateExtension_Success() {
        CreateExtensionCmd cmd = mock(CreateExtensionCmd.class);
        when(cmd.getName()).thenReturn("ext1");
        when(cmd.getDescription()).thenReturn("desc");
        when(cmd.getType()).thenReturn("Orchestrator");
        when(cmd.getPath()).thenReturn(null);
        when(cmd.isOrchestratorRequiresPrepareVm()).thenReturn(null);
        when(cmd.getState()).thenReturn(null);
        when(extensionDao.findByName("ext1")).thenReturn(null);
        when(extensionDao.persist(any())).thenAnswer(inv -> {
            ExtensionVO extensionVO = inv.getArgument(0);
            ReflectionTestUtils.setField(extensionVO, "id", 1L);
            return extensionVO;
        });
        when(managementServerHostDao.listBy(any())).thenReturn(Collections.emptyList());

        Extension ext = extensionsManager.createExtension(cmd);

        assertEquals("ext1", ext.getName());
        verify(extensionDao).persist(any());
    }

    @Test
    public void testCreateExtension_DuplicateName() {
        CreateExtensionCmd cmd = mock(CreateExtensionCmd.class);
        when(cmd.getName()).thenReturn("ext1");
        when(extensionDao.findByName("ext1")).thenReturn(mock(ExtensionVO.class));

        assertThrows(CloudRuntimeException.class, () -> extensionsManager.createExtension(cmd));
    }

    @Test
    public void prepareExtensionPathAcrossServersReturnsTrueWhenAllServersSucceed() {
        Extension ext = mock(Extension.class);
        when(ext.getName()).thenReturn("ext");
        when(ext.isUserDefined()).thenReturn(true);
        when(ext.getRelativePath()).thenReturn("entry.sh");
        when(ext.getId()).thenReturn(1L);
        when(ext.isPathReady()).thenReturn(false);

        ManagementServerHostVO msHost1 = mock(ManagementServerHostVO.class);
        ManagementServerHostVO msHost2 = mock(ManagementServerHostVO.class);
        when(msHost1.getMsid()).thenReturn(100L);
        when(msHost2.getMsid()).thenReturn(200L);

        when(managementServerHostDao.listBy(any())).thenReturn(Arrays.asList(msHost1, msHost2));

        try (MockedStatic<ManagementServerNode> managementServerNodeMockedStatic = mockStatic(ManagementServerNode.class)) {
            managementServerNodeMockedStatic.when(ManagementServerNode::getManagementServerId).thenReturn(101L);
            doReturn(new Pair<>(true, "ok")).when(extensionsManager).prepareExtensionPathOnCurrentServer(anyString(), anyBoolean(), anyString());
            doReturn(true).when(extensionsManager).prepareExtensionPathOnMSPeer(eq(ext), eq(msHost2));

            // Simulate current server is msHost1
            when(msHost1.getMsid()).thenReturn(101L);

            // Extension entry point ready state should be updated
            ExtensionVO updateExt = mock(ExtensionVO.class);
            when(extensionDao.createForUpdate(1L)).thenReturn(updateExt);
            when(extensionDao.update(1L, updateExt)).thenReturn(true);

            boolean result = extensionsManager.prepareExtensionPathAcrossServers(ext);
            assertTrue(result);
            verify(extensionDao).update(1L, updateExt);
        }
    }

    @Test
    public void prepareExtensionPathAcrossServersReturnsFalseWhenAnyServerFails() {
        Extension ext = mock(Extension.class);
        when(ext.getName()).thenReturn("ext");
        when(ext.isUserDefined()).thenReturn(true);
        when(ext.getRelativePath()).thenReturn("entry.sh");
        when(ext.getId()).thenReturn(1L);
        when(ext.isPathReady()).thenReturn(true);

        ManagementServerHostVO msHost1 = mock(ManagementServerHostVO.class);
        ManagementServerHostVO msHost2 = mock(ManagementServerHostVO.class);
        when(msHost1.getMsid()).thenReturn(101L);
        when(msHost2.getMsid()).thenReturn(200L);

        when(managementServerHostDao.listBy(any())).thenReturn(Arrays.asList(msHost1, msHost2));

        try (MockedStatic<ManagementServerNode> managementServerNodeMockedStatic = mockStatic(ManagementServerNode.class)) {
            managementServerNodeMockedStatic.when(ManagementServerNode::getManagementServerId).thenReturn(101L);
            doReturn(new Pair<>(true, "ok")).when(extensionsManager).prepareExtensionPathOnCurrentServer(anyString(), anyBoolean(), anyString());
            doReturn(false).when(extensionsManager).prepareExtensionPathOnMSPeer(eq(ext), eq(msHost2));

            ExtensionVO updateExt = mock(ExtensionVO.class);
            when(extensionDao.createForUpdate(1L)).thenReturn(updateExt);
            when(extensionDao.update(1L, updateExt)).thenReturn(true);

            boolean result = extensionsManager.prepareExtensionPathAcrossServers(ext);
            assertFalse(result);
            verify(extensionDao).update(1L, updateExt);
        }
    }

    @Test
    public void prepareExtensionPathAcrossServersDoesNotUpdateIfStateUnchanged() {
        Extension ext = mock(Extension.class);
        when(ext.getName()).thenReturn("ext");
        when(ext.isUserDefined()).thenReturn(true);
        when(ext.getRelativePath()).thenReturn("entry.sh");
        when(ext.isPathReady()).thenReturn(true);

        ManagementServerHostVO msHost = mock(ManagementServerHostVO.class);
        when(msHost.getMsid()).thenReturn(101L);

        when(managementServerHostDao.listBy(any())).thenReturn(Collections.singletonList(msHost));

        try (MockedStatic<ManagementServerNode> managementServerNodeMockedStatic = mockStatic(ManagementServerNode.class)) {
            managementServerNodeMockedStatic.when(ManagementServerNode::getManagementServerId).thenReturn(101L);
            doReturn(new Pair<>(true, "ok")).when(extensionsManager).prepareExtensionPathOnCurrentServer(anyString(), anyBoolean(), anyString());

            boolean result = extensionsManager.prepareExtensionPathAcrossServers(ext);
            assertTrue(result);
            verify(extensionDao, never()).update(anyLong(), any());
        }
    }

    @Test
    public void testListExtensionsReturnsResponses() {
        ListExtensionsCmd cmd = mock(ListExtensionsCmd.class);
        when(cmd.getExtensionId()).thenReturn(null);
        when(cmd.getName()).thenReturn(null);
        when(cmd.getKeyword()).thenReturn(null);
        when(cmd.getStartIndex()).thenReturn(0L);
        when(cmd.getPageSizeVal()).thenReturn(10L);
        when(cmd.getDetails()).thenReturn(null);

        ExtensionVO ext1 = mock(ExtensionVO.class);
        ExtensionVO ext2 = mock(ExtensionVO.class);
        List<ExtensionVO> extList = Arrays.asList(ext1, ext2);
        SearchBuilder<ExtensionVO> sb = mock(SearchBuilder.class);
        when(sb.create()).thenReturn(mock(SearchCriteria.class));
        when(sb.entity()).thenReturn(mock(ExtensionVO.class));
        when(extensionDao.createSearchBuilder()).thenReturn(sb);
        when(extensionDao.searchAndCount(any(), any())).thenReturn(new Pair<>(extList, 2));

        // Spy createExtensionResponse to return a dummy response
        ExtensionResponse resp1 = mock(ExtensionResponse.class);
        ExtensionResponse resp2 = mock(ExtensionResponse.class);
        doReturn(resp1).when(extensionsManager).createExtensionResponse(eq(ext1), any());
        doReturn(resp2).when(extensionsManager).createExtensionResponse(eq(ext2), any());

        List<ExtensionResponse> result = extensionsManager.listExtensions(cmd);

        assertEquals(2, result.size());
        assertTrue(result.contains(resp1));
        assertTrue(result.contains(resp2));
    }

    @Test
    public void testListExtensionsWithId() {
        ListExtensionsCmd cmd = mock(ListExtensionsCmd.class);
        when(cmd.getExtensionId()).thenReturn(42L);
        when(cmd.getName()).thenReturn(null);
        when(cmd.getKeyword()).thenReturn(null);
        when(cmd.getStartIndex()).thenReturn(0L);
        when(cmd.getPageSizeVal()).thenReturn(10L);
        when(cmd.getDetails()).thenReturn(null);

        ExtensionVO ext = mock(ExtensionVO.class);
        SearchBuilder<ExtensionVO> sb = mock(SearchBuilder.class);
        when(sb.create()).thenReturn(mock(SearchCriteria.class));
        when(sb.entity()).thenReturn(mock(ExtensionVO.class));
        when(extensionDao.createSearchBuilder()).thenReturn(sb);
        when(extensionDao.searchAndCount(any(), any())).thenReturn(new Pair<>(Collections.singletonList(ext), 1));
        ExtensionResponse resp = mock(ExtensionResponse.class);
        doReturn(resp).when(extensionsManager).createExtensionResponse(eq(ext), any());

        List<ExtensionResponse> result = extensionsManager.listExtensions(cmd);

        assertEquals(1, result.size());
        assertEquals(resp, result.get(0));
    }

    @Test
    public void testListExtensionsWithNameAndKeyword() {
        ListExtensionsCmd cmd = mock(ListExtensionsCmd.class);
        when(cmd.getExtensionId()).thenReturn(null);
        when(cmd.getName()).thenReturn("testName");
        when(cmd.getKeyword()).thenReturn("key");
        when(cmd.getStartIndex()).thenReturn(0L);
        when(cmd.getPageSizeVal()).thenReturn(10L);
        when(cmd.getDetails()).thenReturn(null);

        ExtensionVO ext = mock(ExtensionVO.class);
        SearchBuilder<ExtensionVO> sb = mock(SearchBuilder.class);
        when(sb.create()).thenReturn(mock(SearchCriteria.class));
        when(sb.entity()).thenReturn(mock(ExtensionVO.class));
        when(extensionDao.createSearchBuilder()).thenReturn(sb);
        when(extensionDao.searchAndCount(any(), any())).thenReturn(new Pair<>(Collections.singletonList(ext), 1));
        ExtensionResponse resp = mock(ExtensionResponse.class);
        doReturn(resp).when(extensionsManager).createExtensionResponse(eq(ext), any());

        List<ExtensionResponse> result = extensionsManager.listExtensions(cmd);

        assertEquals(1, result.size());
        assertEquals(resp, result.get(0));
    }

    @Test
    public void testUpdateExtension_SuccessfulDescriptionUpdate() {
        UpdateExtensionCmd cmd = mock(UpdateExtensionCmd.class);
        when(cmd.getId()).thenReturn(1L);
        when(cmd.getDescription()).thenReturn("new desc");
        when(cmd.isOrchestratorRequiresPrepareVm()).thenReturn(null);
        when(cmd.getState()).thenReturn(null);
        when(cmd.getDetails()).thenReturn(null);
        when(cmd.isCleanupDetails()).thenReturn(false);

        ExtensionVO ext = mock(ExtensionVO.class);
        when(ext.getDescription()).thenReturn("old desc");
        when(extensionDao.findById(1L)).thenReturn(ext);
        when(extensionDao.update(1L, ext)).thenReturn(true);

        Extension result = extensionsManager.updateExtension(cmd);

        assertEquals(ext, result);
        verify(ext).setDescription("new desc");
        verify(extensionDao, atLeastOnce()).update(1L, ext);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testUpdateExtension_NotFound() {
        UpdateExtensionCmd cmd = mock(UpdateExtensionCmd.class);
        when(cmd.getId()).thenReturn(2L);
        when(extensionDao.findById(2L)).thenReturn(null);

        extensionsManager.updateExtension(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testUpdateExtension_InvalidOrchestratorFlag() {
        UpdateExtensionCmd cmd = mock(UpdateExtensionCmd.class);
        when(cmd.getId()).thenReturn(3L);
        when(cmd.isOrchestratorRequiresPrepareVm()).thenReturn(true);

        ExtensionVO ext = mock(ExtensionVO.class);
        when(ext.getType()).thenReturn(null);
        when(extensionDao.findById(3L)).thenReturn(ext);

        extensionsManager.updateExtension(cmd);
    }

    @Test(expected = CloudRuntimeException.class)
    public void testUpdateExtension_UpdateFails() {
        UpdateExtensionCmd cmd = mock(UpdateExtensionCmd.class);
        when(cmd.getId()).thenReturn(4L);
        when(cmd.getDescription()).thenReturn("desc");
        when(cmd.isOrchestratorRequiresPrepareVm()).thenReturn(null);
        when(cmd.getState()).thenReturn(null);
        when(cmd.getDetails()).thenReturn(null);
        when(cmd.isCleanupDetails()).thenReturn(false);

        ExtensionVO ext = mock(ExtensionVO.class);
        when(ext.getDescription()).thenReturn("old");
        when(extensionDao.findById(4L)).thenReturn(ext);
        when(extensionDao.update(4L, ext)).thenReturn(false);

        extensionsManager.updateExtension(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testUpdateExtension_InvalidState() {
        UpdateExtensionCmd cmd = mock(UpdateExtensionCmd.class);
        when(cmd.getId()).thenReturn(5L);
        when(cmd.getState()).thenReturn("NonExistentState");

        ExtensionVO ext = mock(ExtensionVO.class);
        when(ext.getType()).thenReturn(Extension.Type.Orchestrator);
        when(ext.getState()).thenReturn(Extension.State.Enabled);
        when(extensionDao.findById(5L)).thenReturn(ext);

        extensionsManager.updateExtension(cmd);
    }

    @Test
    public void updateExtensionsDetails_SavesDetails_WhenDetailsProvided() {
        long extensionId = 10L;
        Map<String, String> details = Map.of("foo", "bar", "baz", "qux");
        extensionsManager.updateExtensionsDetails(false, details, null, extensionId);
        verify(extensionDetailsDao).saveDetails(any());
    }

    @Test
    public void updateExtensionsDetails_DoesNothing_WhenDetailsAndCleanupAreNull() {
        long extensionId = 11L;
        extensionsManager.updateExtensionsDetails(null, null, null, extensionId);
        verify(extensionDetailsDao, never()).removeDetails(anyLong());
        verify(extensionDetailsDao, never()).saveDetails(any());
    }

    @Test
    public void updateExtensionsDetails_RemovesDetailsOnly_WhenCleanupIsTrue() {
        long extensionId = 12L;
        extensionsManager.updateExtensionsDetails(true, null, null, extensionId);
        verify(extensionDetailsDao).removeDetails(extensionId);
        verify(extensionDetailsDao, never()).saveDetails(any());
    }

    @Test
    public void updateExtensionsDetails_PersistsOrchestratorFlag_WhenFlagIsNotNull() {
        long extensionId = 13L;
        extensionsManager.updateExtensionsDetails(false, null, true, extensionId);
        verify(extensionDetailsDao).persist(any());
    }

    @Test(expected = CloudRuntimeException.class)
    public void updateExtensionsDetails_ThrowsException_WhenPersistFails() {
        long extensionId = 14L;
        Map<String, String> details = Map.of("foo", "bar");
        doThrow(CloudRuntimeException.class).when(extensionDetailsDao).saveDetails(any());
        extensionsManager.updateExtensionsDetails(false, details, null, extensionId);
    }

    @Test
    public void testDeleteExtension_Success() {
        DeleteExtensionCmd cmd = mock(DeleteExtensionCmd.class);
        when(cmd.getId()).thenReturn(1L);
        when(cmd.isCleanup()).thenReturn(false);
        ExtensionVO ext = mock(ExtensionVO.class);
        when(ext.isUserDefined()).thenReturn(true);
        when(extensionDao.findById(1L)).thenReturn(ext);
        when(extensionResourceMapDao.listByExtensionId(1L)).thenReturn(Collections.emptyList());
        when(extensionCustomActionDao.listIdsByExtensionId(1L)).thenReturn(Collections.emptyList());
        doNothing().when(extensionDetailsDao).removeDetails(1L);
        when(extensionDao.remove(1L)).thenReturn(true);

        assertTrue(extensionsManager.deleteExtension(cmd));
        verify(extensionDao).remove(1L);
    }

    @Test
    public void testRegisterExtensionWithResource_InvalidResourceType() {
        RegisterExtensionCmd cmd = mock(RegisterExtensionCmd.class);
        when(cmd.getResourceType()).thenReturn("InvalidType");

        assertThrows(InvalidParameterValueException.class, () -> extensionsManager.registerExtensionWithResource(cmd));
    }

    @Test
    public void registerExtensionWithResourceRegistersSuccessfullyForValidResourceType() {
        RegisterExtensionCmd cmd = mock(RegisterExtensionCmd.class);
        when(cmd.getResourceType()).thenReturn(ExtensionResourceMap.ResourceType.Cluster.name());
        when(cmd.getResourceId()).thenReturn(UUID.randomUUID().toString());
        when(cmd.getExtensionId()).thenReturn(1L);
        ExtensionVO extension = mock(ExtensionVO.class);
        ClusterVO clusterVO = mock(ClusterVO.class);
        when(clusterVO.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.External);
        when(clusterDao.findByUuid(anyString())).thenReturn(clusterVO);
        ExtensionResourceMapVO resourceMap = mock(ExtensionResourceMapVO.class);
        when(extensionResourceMapDao.persist(any())).thenReturn(resourceMap);
        when(extensionDao.findById(anyLong())).thenReturn(extension);
        Extension result = extensionsManager.registerExtensionWithResource(cmd);
        assertEquals(extension, result);
        verify(extensionResourceMapDao).persist(any());
    }

    @Test(expected = InvalidParameterValueException.class)
    public void registerExtensionWithResourceThrowsForInvalidResourceType() {
        RegisterExtensionCmd cmd = mock(RegisterExtensionCmd.class);
        when(cmd.getResourceType()).thenReturn("InvalidType");

        extensionsManager.registerExtensionWithResource(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void registerExtensionWithResourceThrowsForMissingExtension() {
        RegisterExtensionCmd cmd = mock(RegisterExtensionCmd.class);
        when(cmd.getResourceType()).thenReturn(ExtensionResourceMap.ResourceType.Cluster.name());
        when(cmd.getResourceId()).thenReturn(UUID.randomUUID().toString());
        extensionsManager.registerExtensionWithResource(cmd);
    }

    @Test(expected = CloudRuntimeException.class)
    public void registerExtensionWithResourceThrowsForPersistFailure() {
        RegisterExtensionCmd cmd = mock(RegisterExtensionCmd.class);
        when(cmd.getResourceType()).thenReturn(ExtensionResourceMap.ResourceType.Cluster.name());
        when(cmd.getResourceId()).thenReturn(UUID.randomUUID().toString());
        when(cmd.getExtensionId()).thenReturn(1L);
        ClusterVO clusterVO = mock(ClusterVO.class);
        when(clusterVO.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.External);
        when(clusterDao.findByUuid(anyString())).thenReturn(clusterVO);
        ExtensionVO extension = mock(ExtensionVO.class);
        when(extensionDao.findById(1L)).thenReturn(extension);
        when(extensionResourceMapDao.persist(any())).thenThrow(CloudRuntimeException.class);
        extensionsManager.registerExtensionWithResource(cmd);
    }

    @Test
    public void registerExtensionWithClusterRegistersSuccessfullyForValidCluster() {
        Cluster cluster = mock(Cluster.class);
        when(cluster.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.External);
        Extension extension = mock(Extension.class);
        Map<String, String> details = Map.of("key1", "value1");
        ExtensionResourceMapVO resourceMap = mock(ExtensionResourceMapVO.class);
        when(extensionResourceMapDao.persist(any())).thenReturn(resourceMap);
        ExtensionResourceMap result = extensionsManager.registerExtensionWithCluster(cluster, extension, details);
        assertNotNull(result);
        verify(extensionResourceMapDao).persist(any());
    }

    @Test
    public void registerExtensionWithClusterHandlesNullDetails() {
        Cluster cluster = mock(Cluster.class);
        when(cluster.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.External);
        Extension extension = mock(Extension.class);
        ExtensionResourceMapVO resourceMap = mock(ExtensionResourceMapVO.class);
        when(extensionResourceMapDao.persist(any())).thenReturn(resourceMap);
        ExtensionResourceMap result = extensionsManager.registerExtensionWithCluster(cluster, extension, null);
        assertNotNull(result);
        verify(extensionResourceMapDao).persist(any());
    }

    @Test
    public void testUnregisterExtensionWithResource_InvalidResourceType() {
        UnregisterExtensionCmd cmd = mock(UnregisterExtensionCmd.class);
        when(cmd.getResourceType()).thenReturn("InvalidType");

        assertThrows(InvalidParameterValueException.class, () -> extensionsManager.unregisterExtensionWithResource(cmd));
    }

    @Test
    public void unregisterExtensionWithClusterRemovesMappingSuccessfully() {
        Cluster cluster = mock(Cluster.class);
        when(cluster.getId()).thenReturn(100L);
        Long extensionId = 1L;
        ExtensionResourceMapVO resourceMap = mock(ExtensionResourceMapVO.class);
        when(extensionResourceMapDao.findByResourceIdAndType(eq(100L), eq(ExtensionResourceMap.ResourceType.Cluster)))
            .thenReturn(resourceMap);
        extensionsManager.unregisterExtensionWithCluster(cluster, extensionId);
        verify(extensionResourceMapDao).remove(resourceMap.getId());
    }

    @Test
    public void unregisterExtensionWithClusterHandlesMissingMappingGracefully() {
        Cluster cluster = mock(Cluster.class);
        when(cluster.getId()).thenReturn(100L);
        Long extensionId = 1L;
        when(extensionResourceMapDao.findByResourceIdAndType(eq(100L), eq(ExtensionResourceMap.ResourceType.Cluster)))
            .thenReturn(null);
        extensionsManager.unregisterExtensionWithCluster(cluster, extensionId);
        verify(extensionResourceMapDao, never()).remove(anyLong());
    }

    @Test
    public void testCreateExtensionResponse_BasicFields() {
        Extension extension = mock(Extension.class);
        when(extension.getUuid()).thenReturn("uuid-1");
        when(extension.getName()).thenReturn("ext1");
        when(extension.getDescription()).thenReturn("desc");
        when(extension.getType()).thenReturn(Extension.Type.Orchestrator);
        when(extension.getCreated()).thenReturn(new Date());
        when(extension.getRelativePath()).thenReturn("entry.sh");
        when(extension.isPathReady()).thenReturn(true);
        when(extension.isUserDefined()).thenReturn(true);
        when(extension.getState()).thenReturn(Extension.State.Enabled);
        when(extension.getId()).thenReturn(1L);

        // Mock externalProvisioner
        when(externalProvisioner.getExtensionPath("entry.sh")).thenReturn("/some/path/entry.sh");

        // Mock detailsDao
        Pair<Map<String, String>, Map<String, String>> detailsPair = new Pair<>(Map.of("foo", "bar"),
                Map.of(ApiConstants.ORCHESTRATOR_REQUIRES_PREPARE_VM, "true"));
        when(extensionDetailsDao.listDetailsKeyPairsWithVisibility(1L)).thenReturn(detailsPair);

        EnumSet<ApiConstants.ExtensionDetails> viewDetails = EnumSet.of(ApiConstants.ExtensionDetails.all);

        ExtensionResponse response = extensionsManager.createExtensionResponse(extension, viewDetails);

        assertEquals("uuid-1", response.getId());
        assertEquals("ext1", response.getName());
        assertEquals("desc", response.getDescription());
        assertEquals("Orchestrator", response.getType());
        assertEquals("/some/path/entry.sh", response.getPath());
        assertTrue(response.isPathReady());
        assertTrue(response.isUserDefined());
        assertEquals("Enabled", response.getState());
        assertEquals("bar", response.getDetails().get("foo"));
        assertTrue(response.isOrchestratorRequiresPrepareVm());
        assertEquals("extension", response.getObjectName());
    }

    @Test
    public void testCreateExtensionResponse_HiddenDetailsOnly() {
        Extension extension = mock(Extension.class);
        when(extension.getUuid()).thenReturn("uuid-2");
        when(extension.getName()).thenReturn("ext2");
        when(extension.getDescription()).thenReturn("desc2");
        when(extension.getType()).thenReturn(Extension.Type.Orchestrator);
        when(extension.getCreated()).thenReturn(new Date());
        when(extension.getRelativePath()).thenReturn("entry2.sh");
        when(extension.isPathReady()).thenReturn(false);
        when(extension.isUserDefined()).thenReturn(false);
        when(extension.getState()).thenReturn(Extension.State.Disabled);
        when(extension.getId()).thenReturn(2L);

        when(externalProvisioner.getExtensionPath("entry2.sh")).thenReturn("/some/path/entry2.sh");

        Map<String, String> hiddenDetails = Map.of(ApiConstants.ORCHESTRATOR_REQUIRES_PREPARE_VM, "false");
        when(extensionDetailsDao.listDetailsKeyPairs(2L, List.of(ApiConstants.ORCHESTRATOR_REQUIRES_PREPARE_VM)))
                .thenReturn(hiddenDetails);

        EnumSet<ApiConstants.ExtensionDetails> viewDetails = EnumSet.noneOf(ApiConstants.ExtensionDetails.class);

        ExtensionResponse response = extensionsManager.createExtensionResponse(extension, viewDetails);

        assertEquals("uuid-2", response.getId());
        assertEquals("ext2", response.getName());
        assertEquals("desc2", response.getDescription());
        assertEquals("Orchestrator", response.getType());
        assertEquals("/some/path/entry2.sh", response.getPath());
        assertFalse(response.isPathReady());
        assertFalse(response.isUserDefined());
        assertEquals("Disabled", response.getState());
        assertFalse(response.isOrchestratorRequiresPrepareVm());
        assertEquals("extension", response.getObjectName());
    }

    @Test
    public void testAddCustomAction_Success() {
        AddCustomActionCmd cmd = mock(AddCustomActionCmd.class);
        when(cmd.getName()).thenReturn("action1");
        when(cmd.getDescription()).thenReturn("desc");
        when(cmd.getExtensionId()).thenReturn(1L);
        when(cmd.getResourceType()).thenReturn("VirtualMachine");
        when(cmd.getAllowedRoleTypes()).thenReturn(List.of("Admin"));
        when(cmd.getTimeout()).thenReturn(5);
        when(cmd.isEnabled()).thenReturn(true);
        when(cmd.getParametersMap()).thenReturn(null);
        when(cmd.getSuccessMessage()).thenReturn("ok");
        when(cmd.getErrorMessage()).thenReturn("fail");
        when(cmd.getDetails()).thenReturn(null);

        when(extensionCustomActionDao.findByNameAndExtensionId(1L, "action1")).thenReturn(null);
        ExtensionVO ext = mock(ExtensionVO.class);
        when(extensionDao.findById(1L)).thenReturn(ext);

        ExtensionCustomActionVO actionVO = mock(ExtensionCustomActionVO.class);
        when(extensionCustomActionDao.persist(any())).thenReturn(actionVO);

        ExtensionCustomAction result = extensionsManager.addCustomAction(cmd);

        assertEquals(actionVO, result);
        verify(extensionCustomActionDao).persist(any());
    }

    @Test(expected = CloudRuntimeException.class)
    public void testAddCustomAction_DuplicateName() {
        AddCustomActionCmd cmd = mock(AddCustomActionCmd.class);
        when(cmd.getName()).thenReturn("action1");
        when(cmd.getExtensionId()).thenReturn(1L);
        when(extensionCustomActionDao.findByNameAndExtensionId(1L, "action1")).thenReturn(mock(ExtensionCustomActionVO.class));

        extensionsManager.addCustomAction(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testAddCustomAction_ExtensionNotFound() {
        AddCustomActionCmd cmd = mock(AddCustomActionCmd.class);
        when(cmd.getName()).thenReturn("action1");
        when(cmd.getExtensionId()).thenReturn(2L);
        when(extensionCustomActionDao.findByNameAndExtensionId(2L, "action1")).thenReturn(null);
        when(extensionDao.findById(2L)).thenReturn(null);

        extensionsManager.addCustomAction(cmd);
    }

    @Test(expected = CloudRuntimeException.class)
    public void testAddCustomAction_InvalidResourceType() {
        AddCustomActionCmd cmd = mock(AddCustomActionCmd.class);
        when(cmd.getName()).thenReturn("action1");
        when(cmd.getExtensionId()).thenReturn(1L);
        when(cmd.getResourceType()).thenReturn("InvalidType");
        when(extensionCustomActionDao.findByNameAndExtensionId(1L, "action1")).thenReturn(null);
        ExtensionVO ext = mock(ExtensionVO.class);
        when(extensionDao.findById(1L)).thenReturn(ext);

        extensionsManager.addCustomAction(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testAddCustomAction_InvalidName() {
        AddCustomActionCmd cmd = mock(AddCustomActionCmd.class);
        when(cmd.getName()).thenReturn("action;1");
        extensionsManager.addCustomAction(cmd);
    }

    @Test
    public void deleteCustomAction_RemovesActionAndDetails_ReturnsTrue() {
        long actionId = 10L;
        DeleteCustomActionCmd cmd = mock(DeleteCustomActionCmd.class);
        when(cmd.getId()).thenReturn(actionId);

        ExtensionCustomActionVO actionVO = mock(ExtensionCustomActionVO.class);
        when(extensionCustomActionDao.findById(actionId)).thenReturn(actionVO);
        when(extensionCustomActionDao.remove(actionId)).thenReturn(true);

        boolean result = extensionsManager.deleteCustomAction(cmd);

        assertTrue(result);
        verify(extensionCustomActionDetailsDao).removeDetails(actionId);
        verify(extensionCustomActionDao).remove(actionId);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void deleteCustomAction_ActionNotFound() {
        long actionId = 20L;
        DeleteCustomActionCmd cmd = mock(DeleteCustomActionCmd.class);
        when(cmd.getId()).thenReturn(actionId);
        when(extensionCustomActionDao.findById(actionId)).thenReturn(null);
        extensionsManager.deleteCustomAction(cmd);
        verify(extensionCustomActionDetailsDao, never()).removeDetails(anyLong());
        verify(extensionCustomActionDao, never()).remove(anyLong());
    }

    @Test(expected = CloudRuntimeException.class)
    public void deleteCustomAction_RemoveFails() {
        long actionId = 30L;
        DeleteCustomActionCmd cmd = mock(DeleteCustomActionCmd.class);
        when(cmd.getId()).thenReturn(actionId);
        ExtensionCustomActionVO actionVO = mock(ExtensionCustomActionVO.class);
        when(extensionCustomActionDao.findById(actionId)).thenReturn(actionVO);
        when(extensionCustomActionDao.remove(actionId)).thenReturn(false);
        extensionsManager.deleteCustomAction(cmd);
        verify(extensionCustomActionDetailsDao).removeDetails(actionId);
        verify(extensionCustomActionDao).remove(actionId);
    }

    private void mockCallerRole(RoleType roleType) {
        CallContext callContextMock = mock(CallContext.class);
        when(CallContext.current()).thenReturn(callContextMock);
        Account accountMock = mock(Account.class);
        when(accountMock.getAccountName()).thenReturn("testAccount");
        when(accountMock.getUuid()).thenReturn(UUID.randomUUID().toString());
        when(accountMock.getType()).thenReturn(RoleType.Admin.equals(roleType) ? Account.Type.ADMIN : Account.Type.NORMAL);
        when(accountMock.getRoleId()).thenReturn(1L);
        Role role = mock(Role.class);
        when(role.getRoleType()).thenReturn(roleType);
        when(role.getUuid()).thenReturn("role-uuid-1");
        when(role.getName()).thenReturn(roleType.name() + "Role");
        when(roleService.findRole(1L)).thenReturn(role);
        when(callContextMock.getCallingAccount()).thenReturn(accountMock);
    }

    @Test
    public void testListCustomActions_ReturnsResponses() {
        ListCustomActionCmd cmd = mock(ListCustomActionCmd.class);
        when(cmd.getId()).thenReturn(null);
        when(cmd.getName()).thenReturn(null);
        when(cmd.getExtensionId()).thenReturn(1L);
        when(cmd.getKeyword()).thenReturn(null);
        when(cmd.getResourceType()).thenReturn(null);
        when(cmd.getResourceId()).thenReturn(null);
        when(cmd.isEnabled()).thenReturn(null);
        when(cmd.getStartIndex()).thenReturn(0L);
        when(cmd.getPageSizeVal()).thenReturn(10L);

        ExtensionCustomActionVO action1 = mock(ExtensionCustomActionVO.class);
        ExtensionCustomActionVO action2 = mock(ExtensionCustomActionVO.class);
        List<ExtensionCustomActionVO> actions = Arrays.asList(action1, action2);
        SearchBuilder<ExtensionCustomActionVO> sb = mock(SearchBuilder.class);
        when(sb.create()).thenReturn(mock(SearchCriteria.class));
        when(sb.entity()).thenReturn(mock(ExtensionCustomActionVO.class));
        when(extensionCustomActionDao.createSearchBuilder()).thenReturn(sb);
        when(extensionCustomActionDao.searchAndCount(any(), any())).thenReturn(new Pair<>(actions, 2));

        ExtensionCustomActionResponse resp1 = mock(ExtensionCustomActionResponse.class);
        ExtensionCustomActionResponse resp2 = mock(ExtensionCustomActionResponse.class);
        doReturn(resp1).when(extensionsManager).createCustomActionResponse(eq(action1));
        doReturn(resp2).when(extensionsManager).createCustomActionResponse(eq(action2));


        try (MockedStatic<CallContext> ignored = mockStatic(CallContext.class)) {
            mockCallerRole(RoleType.Admin);
            List<ExtensionCustomActionResponse> result = extensionsManager.listCustomActions(cmd);

            assertEquals(2, result.size());
            assertTrue(result.contains(resp1));
            assertTrue(result.contains(resp2));
        }
    }

    @Test
    public void testUpdateCustomAction_UpdatesFields() {
        long actionId = 1L;
        String newDescription = "Updated description";
        String newResourceType = "VirtualMachine";
        List<String> newRoles = List.of("Admin", "User");
        Boolean enabled = true;
        int timeout = 10;
        String successMsg = "Success!";
        String errorMsg = "Error!";
        Map<String, String> details = Map.of("key", "value");

        UpdateCustomActionCmd cmd = mock(UpdateCustomActionCmd.class);
        when(cmd.getId()).thenReturn(actionId);
        when(cmd.getDescription()).thenReturn(newDescription);
        when(cmd.getResourceType()).thenReturn(newResourceType);
        when(cmd.getAllowedRoleTypes()).thenReturn(newRoles);
        when(cmd.isEnabled()).thenReturn(enabled);
        when(cmd.getTimeout()).thenReturn(timeout);
        when(cmd.getSuccessMessage()).thenReturn(successMsg);
        when(cmd.getErrorMessage()).thenReturn(errorMsg);
        when(cmd.getParametersMap()).thenReturn(null);
        when(cmd.isCleanupParameters()).thenReturn(false);
        when(cmd.getDetails()).thenReturn(details);
        when(cmd.isCleanupDetails()).thenReturn(false);

        ExtensionCustomActionVO actionVO = new ExtensionCustomActionVO();
        ReflectionTestUtils.setField(actionVO, "id", 1L);
        when(extensionCustomActionDao.findById(actionId)).thenReturn(actionVO);
        when(extensionCustomActionDao.update(eq(actionId), any())).thenReturn(true);

        when(extensionCustomActionDetailsDao.listDetailsKeyPairs(eq(actionId), eq(false)))
                .thenReturn(new HashMap<>());

        ExtensionCustomAction result = extensionsManager.updateCustomAction(cmd);

        assertEquals(newDescription, result.getDescription());
        assertEquals(successMsg, result.getSuccessMessage());
        assertEquals(errorMsg, result.getErrorMessage());
        assertEquals(timeout, result.getTimeout());
        assertTrue(result.isEnabled());
        assertEquals(ExtensionCustomAction.ResourceType.VirtualMachine, result.getResourceType());
    }

    @Test(expected = CloudRuntimeException.class)
    public void testUpdateCustomAction_ActionNotFound_ThrowsException() {
        UpdateCustomActionCmd cmd = mock(UpdateCustomActionCmd.class);
        when(cmd.getId()).thenReturn(99L);
        when(extensionCustomActionDao.findById(99L)).thenReturn(null);

        extensionsManager.updateCustomAction(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testUpdateCustomAction_InvalidResourceType_ThrowsException() {
        UpdateCustomActionCmd cmd = mock(UpdateCustomActionCmd.class);
        when(cmd.getId()).thenReturn(1L);
        ExtensionCustomActionVO action = mock(ExtensionCustomActionVO.class);
        when(extensionCustomActionDao.findById(1L)).thenReturn(action);
        when(cmd.getResourceType()).thenReturn("InvalidType");

        extensionsManager.updateCustomAction(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testUpdateCustomAction_InvalidRoleType_ThrowsException() {
        UpdateCustomActionCmd cmd = mock(UpdateCustomActionCmd.class);
        when(cmd.getId()).thenReturn(1L);
        ExtensionCustomActionVO action = mock(ExtensionCustomActionVO.class);
        when(extensionCustomActionDao.findById(1L)).thenReturn(action);
        when(cmd.getAllowedRoleTypes()).thenReturn(List.of("NotARole"));

        extensionsManager.updateCustomAction(cmd);
    }

    @Test(expected = CloudRuntimeException.class)
    public void testUpdateCustomAction_DaoUpdateFails_ThrowsException() {
        UpdateCustomActionCmd cmd = mock(UpdateCustomActionCmd.class);
        when(cmd.getId()).thenReturn(1L);
        when(cmd.getDescription()).thenReturn("desc");
        ExtensionCustomActionVO action = mock(ExtensionCustomActionVO.class);
        when(extensionCustomActionDao.findById(1L)).thenReturn(action);
        when(extensionCustomActionDao.update(eq(1L), any())).thenReturn(false);

        extensionsManager.updateCustomAction(cmd);
    }

    @Test
    public void updatedCustomActionDetails_RemovesDetails_WhenCleanupDetailsIsTrue() {
        long actionId = 1L;
        Boolean cleanupDetails = true;
        extensionsManager.updatedCustomActionDetails(actionId, cleanupDetails, null, false, null);
        verify(extensionCustomActionDetailsDao).removeDetails(actionId);
        verify(extensionCustomActionDetailsDao, never()).saveDetails(any());
    }

    @Test
    public void updatedCustomActionDetails_SavesDetails_WhenDetailsProvided() {
        long actionId = 2L;
        Map<String, String> details = Map.of("key1", "value1", "key2", "value2");
        extensionsManager.updatedCustomActionDetails(actionId, false, details, false, null);
        verify(extensionCustomActionDetailsDao).saveDetails(any());
        verify(extensionCustomActionDetailsDao, never()).removeDetails(anyLong());
    }

    @Test
    public void updatedCustomActionDetails_DoesNothing_WhenDetailsAndCleanupDetailsAreNull() {
        long actionId = 3L;
        extensionsManager.updatedCustomActionDetails(actionId, null, null, false, null);
        verify(extensionCustomActionDetailsDao, never()).removeDetails(anyLong());
        verify(extensionCustomActionDetailsDao, never()).saveDetails(any());
    }

    @Test
    public void updatedCustomActionDetails_HandlesEmptyDetailsGracefully() {
        long actionId = 4L;
        Map<String, String> details = Collections.emptyMap();
        extensionsManager.updatedCustomActionDetails(actionId, false, details, false, null);
        verify(extensionCustomActionDetailsDao, never()).saveDetails(any());
        verify(extensionCustomActionDetailsDao, never()).removeDetails(anyLong());
    }

    @Test(expected = CloudRuntimeException.class)
    public void updatedCustomActionDetails_ThrowsException_WhenSaveDetailsFails() {
        long actionId = 5L;
        Map<String, String> details = Map.of("key1", "value1");
        doThrow(CloudRuntimeException.class).when(extensionCustomActionDetailsDao).saveDetails(any());
        extensionsManager.updatedCustomActionDetails(actionId, false, details, false, null);
    }

    @Test
    public void updatedCustomActionDetails_RemovesDetails_WhenCleanupDetailsParametersAreTrue() {
        long actionId = 1L;
        Map<String, String> hiddenDetails = new HashMap<>();
        hiddenDetails.put(ApiConstants.PARAMETERS, "Test");
        when(extensionCustomActionDetailsDao.listDetailsKeyPairs(actionId, false)).thenReturn(hiddenDetails);
        extensionsManager.updatedCustomActionDetails(actionId, true, null, true, null);
        verify(extensionCustomActionDetailsDao).removeDetails(actionId);
        verify(extensionCustomActionDetailsDao, never()).saveDetails(any());
    }

    @Test
    public void updatedCustomActionDetails_RemovesDetails_WhenCleanupDetailsTrueCleanupParametersFalse() {
        long actionId = 1L;
        Map<String, String> hiddenDetails = new HashMap<>();
        hiddenDetails.put(ApiConstants.PARAMETERS, "Test");
        when(extensionCustomActionDetailsDao.listDetailsKeyPairs(actionId, false)).thenReturn(hiddenDetails);
        extensionsManager.updatedCustomActionDetails(actionId, true, null, false, null);
        verify(extensionCustomActionDetailsDao, never()).removeDetails(actionId);
        verify(extensionCustomActionDetailsDao).saveDetails(any());
    }

    @Test
    public void updatedCustomActionDetails_RemovesDetails_WhenParameterGiven() {
        long actionId = 1L;
        extensionsManager.updatedCustomActionDetails(actionId, false, null, false,
                List.of(mock(ExtensionCustomAction.Parameter.class)));
        verify(extensionCustomActionDetailsDao, never()).removeDetails(actionId);
        verify(extensionCustomActionDetailsDao, never()).saveDetails(any());
        verify(extensionCustomActionDetailsDao).persist(any(ExtensionCustomActionDetailsVO.class));
    }

    @Test
    public void runCustomAction_SuccessfulExecution_ReturnsExpectedResult() throws Exception {
        RunCustomActionCmd cmd = mock(RunCustomActionCmd.class);
        when(cmd.getCustomActionId()).thenReturn(1L);
        when(cmd.getResourceId()).thenReturn("vm-123");
        when(cmd.getParameters()).thenReturn(Map.of("param1", "value1"));

        ExtensionCustomActionVO actionVO = mock(ExtensionCustomActionVO.class);
        when(extensionCustomActionDao.findById(1L)).thenReturn(actionVO);
        when(actionVO.isEnabled()).thenReturn(true);
        when(actionVO.getResourceType()).thenReturn(ExtensionCustomAction.ResourceType.VirtualMachine);
        when(actionVO.getAllowedRoleTypes()).thenReturn(
                RoleType.toCombinedMask(List.of(RoleType.Admin, RoleType.DomainAdmin, RoleType.User)));

        ExtensionVO extensionVO = mock(ExtensionVO.class);
        when(extensionDao.findById(anyLong())).thenReturn(extensionVO);
        when(extensionVO.getState()).thenReturn(Extension.State.Enabled);

        RunCustomActionAnswer answer = mock(RunCustomActionAnswer.class);
        when(answer.getResult()).thenReturn(true);

        VirtualMachine vm = mock(VirtualMachine.class);
        when(vm.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.External);
        when(entityManager.findByUuid(eq(VirtualMachine.class), anyString())).thenReturn(vm);
        when(virtualMachineManager.findClusterAndHostIdForVm(vm, false)).thenReturn(new Pair<>(1L, 1L));

        when(extensionResourceMapDao.findByResourceIdAndType(1L, ExtensionResourceMap.ResourceType.Cluster)).thenReturn(mock(ExtensionResourceMapVO.class));
        when(extensionCustomActionDetailsDao.listDetailsKeyPairsWithVisibility(anyLong())).thenReturn(new Pair<>(new HashMap<>(), new HashMap<>()));

        when(agentMgr.send(anyLong(), any(Command.class))).thenReturn(answer);

        try (MockedStatic<CallContext> ignored = mockStatic(CallContext.class)) {
            mockCallerRole(RoleType.User);
            CustomActionResultResponse result = extensionsManager.runCustomAction(cmd);

            assertTrue(result.getSuccess());
        }
    }

    @Test(expected = InvalidParameterValueException.class)
    public void runCustomAction_ActionNotFound_ThrowsException() {
        RunCustomActionCmd cmd = mock(RunCustomActionCmd.class);
        when(cmd.getCustomActionId()).thenReturn(99L);
        when(extensionCustomActionDao.findById(99L)).thenReturn(null);


        try (MockedStatic<CallContext> ignored = mockStatic(CallContext.class)) {
            mockCallerRole(RoleType.Admin);
            extensionsManager.runCustomAction(cmd);
        }
    }

    @Test(expected = CloudRuntimeException.class)
    public void runCustomAction_ActionNotAllowedForRole_ThrowsException() {
        RunCustomActionCmd cmd = mock(RunCustomActionCmd.class);
        when(cmd.getCustomActionId()).thenReturn(2L);

        ExtensionCustomActionVO actionVO = mock(ExtensionCustomActionVO.class);
        when(extensionCustomActionDao.findById(2L)).thenReturn(actionVO);
        when(actionVO.getAllowedRoleTypes()).thenReturn(
                RoleType.toCombinedMask(List.of(RoleType.Admin, RoleType.DomainAdmin)));

        try (MockedStatic<CallContext> ignored = mockStatic(CallContext.class)) {
            mockCallerRole(RoleType.User);
            extensionsManager.runCustomAction(cmd);
        }
    }

    @Test(expected = CloudRuntimeException.class)
    public void runCustomAction_ActionDisabled_ThrowsException() {
        RunCustomActionCmd cmd = mock(RunCustomActionCmd.class);
        when(cmd.getCustomActionId()).thenReturn(2L);

        ExtensionCustomActionVO actionVO = mock(ExtensionCustomActionVO.class);
        when(extensionCustomActionDao.findById(2L)).thenReturn(actionVO);
        when(actionVO.isEnabled()).thenReturn(false);

        try (MockedStatic<CallContext> ignored = mockStatic(CallContext.class)) {
            mockCallerRole(RoleType.Admin);
            extensionsManager.runCustomAction(cmd);
        }
    }

    @Test(expected = InvalidParameterValueException.class)
    public void runCustomAction_InvalidResourceType_ThrowsException() {
        RunCustomActionCmd cmd = mock(RunCustomActionCmd.class);
        when(cmd.getCustomActionId()).thenReturn(3L);

        ExtensionCustomActionVO actionVO = mock(ExtensionCustomActionVO.class);
        when(extensionCustomActionDao.findById(3L)).thenReturn(actionVO);
        when(actionVO.isEnabled()).thenReturn(true);
        when(actionVO.getResourceType()).thenReturn(null);
        when(actionVO.getExtensionId()).thenReturn(1L);
        ExtensionVO extensionVO = mock(ExtensionVO.class);
        when(extensionVO.getState()).thenReturn(Extension.State.Enabled);
        when(extensionDao.findById(1L)).thenReturn(extensionVO);


        try (MockedStatic<CallContext> ignored = mockStatic(CallContext.class)) {
            mockCallerRole(RoleType.Admin);
            extensionsManager.runCustomAction(cmd);
        }
    }

    @Test
    public void runCustomAction_ExecutionThrowsException() throws Exception {
        RunCustomActionCmd cmd = mock(RunCustomActionCmd.class);
        when(cmd.getCustomActionId()).thenReturn(1L);
        when(cmd.getResourceId()).thenReturn("vm-123");
        when(cmd.getParameters()).thenReturn(Map.of("param1", "value1"));

        ExtensionCustomActionVO actionVO = mock(ExtensionCustomActionVO.class);
        when(extensionCustomActionDao.findById(1L)).thenReturn(actionVO);
        when(actionVO.isEnabled()).thenReturn(true);
        when(actionVO.getResourceType()).thenReturn(ExtensionCustomAction.ResourceType.VirtualMachine);

        ExtensionVO extensionVO = mock(ExtensionVO.class);
        when(extensionDao.findById(anyLong())).thenReturn(extensionVO);
        when(extensionVO.getState()).thenReturn(Extension.State.Enabled);

        VirtualMachine vm = mock(VirtualMachine.class);
        when(vm.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.External);
        when(entityManager.findByUuid(eq(VirtualMachine.class), anyString())).thenReturn(vm);
        when(virtualMachineManager.findClusterAndHostIdForVm(vm, false)).thenReturn(new Pair<>(1L, 1L));

        when(extensionResourceMapDao.findByResourceIdAndType(1L, ExtensionResourceMap.ResourceType.Cluster)).thenReturn(mock(ExtensionResourceMapVO.class));
        when(extensionCustomActionDetailsDao.listDetailsKeyPairsWithVisibility(anyLong())).thenReturn(new Pair<>(new HashMap<>(), new HashMap<>()));

        when(agentMgr.send(anyLong(), any(Command.class))).thenThrow(OperationTimedoutException.class);

        try (MockedStatic<CallContext> ignored = mockStatic(CallContext.class)) {
            mockCallerRole(RoleType.Admin);
            CustomActionResultResponse result = extensionsManager.runCustomAction(cmd);

            assertFalse(result.getSuccess());
        }
    }

    @Test(expected = PermissionDeniedException.class)
    public void runCustomAction_CheckAccessThrowsException() throws Exception {
        RunCustomActionCmd cmd = mock(RunCustomActionCmd.class);
        when(cmd.getCustomActionId()).thenReturn(1L);
        when(cmd.getResourceId()).thenReturn("vm-123");
        when(cmd.getParameters()).thenReturn(Map.of("param1", "value1"));

        ExtensionCustomActionVO actionVO = mock(ExtensionCustomActionVO.class);
        when(extensionCustomActionDao.findById(1L)).thenReturn(actionVO);
        when(actionVO.isEnabled()).thenReturn(true);
        when(actionVO.getResourceType()).thenReturn(ExtensionCustomAction.ResourceType.VirtualMachine);
        when(actionVO.getAllowedRoleTypes()).thenReturn(RoleType.toCombinedMask(List.of(RoleType.Admin, RoleType.DomainAdmin, RoleType.User)));

        ExtensionVO extensionVO = mock(ExtensionVO.class);
        when(extensionDao.findById(anyLong())).thenReturn(extensionVO);
        when(extensionVO.getState()).thenReturn(Extension.State.Enabled);

        VirtualMachine vm = mock(VirtualMachine.class);
        when(entityManager.findByUuid(eq(VirtualMachine.class), anyString())).thenReturn(vm);
        doThrow(PermissionDeniedException.class).when(accountService).checkAccess(any(Account.class), eq(null), eq(true), eq(vm));

        try (MockedStatic<CallContext> ignored = mockStatic(CallContext.class)) {
            mockCallerRole(RoleType.User);
            CustomActionResultResponse result = extensionsManager.runCustomAction(cmd);

            assertFalse(result.getSuccess());
        }
    }

    @Test
    public void createCustomActionResponse_SetsBasicFields() {
        ExtensionCustomAction action = mock(ExtensionCustomAction.class);
        when(action.getUuid()).thenReturn("uuid-1");
        when(action.getName()).thenReturn("action1");
        when(action.getDescription()).thenReturn("desc");
        when(action.getResourceType()).thenReturn(ExtensionCustomAction.ResourceType.VirtualMachine);

        when(extensionCustomActionDetailsDao.listDetailsKeyPairsWithVisibility(anyLong()))
                .thenReturn(new Pair<>(Map.of("foo", "bar"), Map.of()));

        ExtensionCustomActionResponse response = extensionsManager.createCustomActionResponse(action);

        assertEquals("uuid-1", response.getId());
        assertEquals("action1", response.getName());
        assertEquals("desc", response.getDescription());
        assertEquals("VirtualMachine", response.getResourceType());
        assertEquals("bar", response.getDetails().get("foo"));
    }

    @Test
    public void createCustomActionResponse_HandlesNullResourceType() {
        ExtensionCustomAction action = mock(ExtensionCustomAction.class);
        when(action.getUuid()).thenReturn("uuid-2");
        when(action.getName()).thenReturn("action2");
        when(action.getDescription()).thenReturn("desc2");
        when(action.getResourceType()).thenReturn(null);

        when(extensionCustomActionDetailsDao.listDetailsKeyPairsWithVisibility(anyLong()))
                .thenReturn(new Pair<>(Collections.emptyMap(), Collections.emptyMap()));

        ExtensionCustomActionResponse response = extensionsManager.createCustomActionResponse(action);

        assertEquals("uuid-2", response.getId());
        assertNull(response.getResourceType());
        assertTrue(response.getDetails().isEmpty());
    }

    @Test
    public void createCustomActionResponse_ParametersAreSetIfPresent() {
        ExtensionCustomAction action = mock(ExtensionCustomAction.class);
        when(action.getUuid()).thenReturn("uuid-3");
        when(action.getName()).thenReturn("action3");
        when(action.getDescription()).thenReturn("desc3");
        when(action.getResourceType()).thenReturn(ExtensionCustomAction.ResourceType.VirtualMachine);

        Map<String, String> details = Map.of("foo", "bar");
        ExtensionCustomAction.Parameter param = new ExtensionCustomAction.Parameter("param1",
                ExtensionCustomAction.Parameter.Type.STRING, ExtensionCustomAction.Parameter.ValidationFormat.NONE,
                null, false);
        Map<String, String> hidden = Map.of(ApiConstants.PARAMETERS,
                ExtensionCustomAction.Parameter.toJsonFromList(List.of(param)));
        when(extensionCustomActionDetailsDao.listDetailsKeyPairsWithVisibility(anyLong()))
                .thenReturn(new Pair<>(details, hidden));

        ExtensionCustomActionResponse response = extensionsManager.createCustomActionResponse(action);

        assertEquals(ExtensionCustomAction.ResourceType.VirtualMachine.name(), response.getResourceType());
        assertEquals("bar", response.getDetails().get("foo"));
        assertNotNull(response.getParameters());
        assertFalse(response.getParameters().isEmpty());
    }

    @Test
    public void handleExtensionServerCommands_GetChecksumCommand_ReturnsChecksumAnswer() {
        GetExtensionPathChecksumCommand cmd = mock(GetExtensionPathChecksumCommand.class);
        when(cmd.getExtensionName()).thenReturn("ext");
        when(cmd.getExtensionRelativePath()).thenReturn("ext/entry.sh");
        when(extensionsManager.externalProvisioner.getChecksumForExtensionPath(anyString(), anyString()))
                .thenReturn("checksum123");
        String json = extensionsManager.handleExtensionServerCommands(cmd);
        assertTrue(json.contains("checksum123"));
        assertTrue(json.contains("\"result\":true"));
    }

    @Test
    public void handleExtensionServerCommands_PreparePathCommand_ReturnsSuccessAnswer() {
        PrepareExtensionPathCommand cmd = mock(PrepareExtensionPathCommand.class);
        when(cmd.getExtensionName()).thenReturn("ext");
        when(cmd.getExtensionRelativePath()).thenReturn("ext/entry.sh");
        when(cmd.isExtensionUserDefined()).thenReturn(true);
        doReturn(new Pair<>(true, "ok")).when(extensionsManager)
                .prepareExtensionPathOnCurrentServer(anyString(), anyBoolean(), anyString());

        String json = extensionsManager.handleExtensionServerCommands(cmd);
        assertTrue(json.contains("\"result\":true"));
        assertTrue(json.contains("ok"));
    }

    @Test
    public void handleExtensionServerCommands_CleanupFilesCommand_ReturnsSuccessAnswer() {
        CleanupExtensionFilesCommand cmd = mock(CleanupExtensionFilesCommand.class);
        when(cmd.getExtensionName()).thenReturn("ext");
        when(cmd.getExtensionRelativePath()).thenReturn("ext/entry.sh");
        doReturn(new Pair<>(true, "cleaned")).when(extensionsManager)
                .cleanupExtensionFilesOnCurrentServer(anyString(), anyString());

        String json = extensionsManager.handleExtensionServerCommands(cmd);
        assertTrue(json.contains("\"result\":true"));
        assertTrue(json.contains("cleaned"));
    }

    @Test
    public void handleExtensionServerCommands_UnsupportedCommand_ReturnsUnsupportedAnswer() {
        ExtensionServerActionBaseCommand cmd = mock(ExtensionServerActionBaseCommand.class);
        when(cmd.getExtensionName()).thenReturn("ext");
        when(cmd.getExtensionRelativePath()).thenReturn("ext/entry.sh");

        String json = extensionsManager.handleExtensionServerCommands(cmd);
        assertTrue(json.contains("Unsupported command"));
        assertTrue(json.contains("\"result\":false"));
    }

    @Test
    public void extensionResourceMapDetailsNeedUpdateReturnsTrueWhenNoResourceMapExists() {
        when(extensionResourceMapDao.findByResourceIdAndType(1L, ExtensionResourceMap.ResourceType.Cluster)).thenReturn(null);
        Map<String, String> externalDetails = Map.of("key", "value");
        Pair<Boolean, ExtensionResourceMap> result = extensionsManager.extensionResourceMapDetailsNeedUpdate(1L,
            ExtensionResourceMap.ResourceType.Cluster, externalDetails);
        assertTrue(result.first());
        assertNull(result.second());
    }

    @Test
    public void extensionResourceMapDetailsNeedUpdateReturnsFalseWhenDetailsMatch() {
        ExtensionResourceMapVO resourceMap = mock(ExtensionResourceMapVO.class);
        when(extensionResourceMapDao.findByResourceIdAndType(1L, ExtensionResourceMap.ResourceType.Cluster)).thenReturn(resourceMap);
        when(extensionResourceMapDetailsDao.listDetailsKeyPairs(resourceMap.getId())).thenReturn(Map.of("key", "value"));
        Map<String, String> externalDetails = Map.of("key", "value");
        Pair<Boolean, ExtensionResourceMap> result = extensionsManager.extensionResourceMapDetailsNeedUpdate(1L,
            ExtensionResourceMap.ResourceType.Cluster, externalDetails);
        assertFalse(result.first());
        assertEquals(resourceMap, result.second());
    }

    @Test
    public void extensionResourceMapDetailsNeedUpdateReturnsTrueWhenDetailsDiffer() {
        ExtensionResourceMapVO resourceMap = mock(ExtensionResourceMapVO.class);
        when(extensionResourceMapDao.findByResourceIdAndType(1L, ExtensionResourceMap.ResourceType.Cluster)).thenReturn(resourceMap);
        when(extensionResourceMapDetailsDao.listDetailsKeyPairs(resourceMap.getId())).thenReturn(Map.of("key", "oldValue"));
        Map<String, String> externalDetails = Map.of("key", "newValue");
        Pair<Boolean, ExtensionResourceMap> result = extensionsManager.extensionResourceMapDetailsNeedUpdate(1L,
            ExtensionResourceMap.ResourceType.Cluster, externalDetails);
        assertTrue(result.first());
        assertEquals(resourceMap, result.second());
    }

    @Test
    public void extensionResourceMapDetailsNeedUpdateReturnsTrueWhenExternalDetailsHaveExtraKeys() {
        ExtensionResourceMapVO resourceMap = mock(ExtensionResourceMapVO.class);
        when(extensionResourceMapDao.findByResourceIdAndType(1L, ExtensionResourceMap.ResourceType.Cluster)).thenReturn(resourceMap);
        when(extensionResourceMapDetailsDao.listDetailsKeyPairs(resourceMap.getId())).thenReturn(Map.of("key", "value"));
        Map<String, String> externalDetails = Map.of("key", "value", "extra", "something");
        Pair<Boolean, ExtensionResourceMap> result = extensionsManager.extensionResourceMapDetailsNeedUpdate(1L,
            ExtensionResourceMap.ResourceType.Cluster, externalDetails);
        assertTrue(result.first());
        assertEquals(resourceMap, result.second());
    }

    @Test
    public void updateExtensionResourceMapDetails_SavesDetails_WhenDetailsProvided() {
        long resourceMapId = 100L;
        Map<String, String> details = Map.of("foo", "bar", "baz", "qux");
        extensionsManager.updateExtensionResourceMapDetails(resourceMapId, details);
        verify(extensionResourceMapDetailsDao).saveDetails(any());
    }

    @Test
    public void updateExtensionResourceMapDetails_RemovesDetails_WhenDetailsIsNull() {
        long resourceMapId = 101L;
        extensionsManager.updateExtensionResourceMapDetails(resourceMapId, null);
        verify(extensionResourceMapDetailsDao, never()).saveDetails(any());
    }

    @Test
    public void updateExtensionResourceMapDetails_RemovesDetails_WhenDetailsIsEmpty() {
        long resourceMapId = 102L;
        extensionsManager.updateExtensionResourceMapDetails(resourceMapId, Collections.emptyMap());
        verify(extensionResourceMapDetailsDao, never()).saveDetails(any());
    }

    @Test(expected = CloudRuntimeException.class)
    public void updateExtensionResourceMapDetails_ThrowsException_WhenSaveFails() {
        long resourceMapId = 103L;
        Map<String, String> details = Map.of("foo", "bar");
        doThrow(CloudRuntimeException.class).when(extensionResourceMapDetailsDao).saveDetails(any());
        extensionsManager.updateExtensionResourceMapDetails(resourceMapId, details);
    }

    @Test
    public void getExtensionIdForCluster_WhenMappingExists_ReturnsExtensionId() {
        long clusterId = 1L;
        long extensionId = 100L;
        ExtensionResourceMapVO mapVO = mock(ExtensionResourceMapVO.class);
        when(extensionResourceMapDao.findByResourceIdAndType(eq(clusterId), any()))
                .thenReturn(mapVO);
        when(mapVO.getExtensionId()).thenReturn(extensionId);

        Long result = extensionsManager.getExtensionIdForCluster(clusterId);

        assertEquals(Long.valueOf(extensionId), result);
    }

    @Test
    public void getExtensionIdForCluster_WhenNoMappingExists_ReturnsNull() {
        long clusterId = 42L;
        when(extensionResourceMapDao.findByResourceIdAndType(eq(clusterId), any()))
                .thenReturn(null);

        Long result = extensionsManager.getExtensionIdForCluster(clusterId);

        assertNull(result);
    }

    @Test
    public void getExtension_WhenExtensionExists_ReturnsExtension() {
        long id = 1L;
        ExtensionVO ext = mock(ExtensionVO.class);
        when(extensionDao.findById(id)).thenReturn(ext);

        Extension result = extensionsManager.getExtension(id);

        assertEquals(ext, result);
    }

    @Test
    public void getExtension_WhenExtensionDoesNotExist_ReturnsNull() {
        long id = 2L;
        when(extensionDao.findById(id)).thenReturn(null);

        Extension result = extensionsManager.getExtension(id);

        assertNull(result);
    }

    @Test
    public void getExtensionForCluster_WhenMappingExists_ReturnsExtension() {
        long clusterId = 10L;
        long extensionId = 20L;
        ExtensionVO ext = mock(ExtensionVO.class);
        when(extensionsManager.getExtensionIdForCluster(clusterId)).thenReturn(extensionId);
        when(extensionDao.findById(extensionId)).thenReturn(ext);
        Extension result = extensionsManager.getExtensionForCluster(clusterId);
        assertEquals(ext, result);
    }

    @Test
    public void getExtensionForCluster_WhenNoMappingExists_ReturnsNull() {
        long clusterId = 10L;
        when(extensionsManager.getExtensionIdForCluster(clusterId)).thenReturn(null);
        Extension result = extensionsManager.getExtensionForCluster(clusterId);
        assertNull(result);
    }

    @Test
    public void getInstanceConsole_whenValid() {
        Extension extension = mock(Extension.class);
        when(extension.getType()).thenReturn(Extension.Type.Orchestrator);
        when(extension.getState()).thenReturn(Extension.State.Enabled);
        when(extensionsManager.getExtensionForCluster(anyLong())).thenReturn(extension);
        VirtualMachine vm = mock(VirtualMachine.class);
        Host host = mock(Host.class);
        when(host.getClusterId()).thenReturn(1L);
        Answer expectedAnswer = mock(Answer.class);
        when(virtualMachineManager.toVmTO(any())).thenReturn(mock(VirtualMachineTO.class));
        when(agentMgr.easySend(anyLong(), any())).thenReturn(expectedAnswer);
        Answer result = extensionsManager.getInstanceConsole(vm, host);
        assertNotNull(result);
        assertEquals(expectedAnswer, result);
    }

    @Test
    public void getInstanceConsole_whenNullExtension() {
        when(extensionsManager.getExtensionForCluster(anyLong())).thenReturn(null);
        VirtualMachine vm = mock(VirtualMachine.class);
        Host host = mock(Host.class);
        when(host.getClusterId()).thenReturn(1L);
        Answer result = extensionsManager.getInstanceConsole(vm, host);
        assertNotNull(result);
        assertFalse(result.getResult());
    }

    @Test
    public void getInstanceConsole_whenNullExtensionNotOrchestrator() {
        Extension extension = mock(Extension.class);
        when(extensionsManager.getExtensionForCluster(anyLong())).thenReturn(extension);
        VirtualMachine vm = mock(VirtualMachine.class);
        Host host = mock(Host.class);
        when(host.getClusterId()).thenReturn(1L);
        Answer result = extensionsManager.getInstanceConsole(vm, host);
        assertNotNull(result);
        assertFalse(result.getResult());
    }

    @Test
    public void getInstanceConsole_whenNullExtensionNotEnabled() {
        Extension extension = mock(Extension.class);
        when(extension.getType()).thenReturn(Extension.Type.Orchestrator);
        when(extension.getState()).thenReturn(Extension.State.Disabled);
        when(extensionsManager.getExtensionForCluster(anyLong())).thenReturn(extension);
        VirtualMachine vm = mock(VirtualMachine.class);
        Host host = mock(Host.class);
        when(host.getClusterId()).thenReturn(1L);
        Answer result = extensionsManager.getInstanceConsole(vm, host);
        assertNotNull(result);
        assertFalse(result.getResult());
    }

    @Test
    public void getInstanceConsole_whenAgentManagerFails() {
        Extension extension = mock(Extension.class);
        when(extension.getType()).thenReturn(Extension.Type.Orchestrator);
        when(extension.getState()).thenReturn(Extension.State.Enabled);
        when(extensionsManager.getExtensionForCluster(anyLong())).thenReturn(extension);
        VirtualMachine vm = mock(VirtualMachine.class);
        Host host = mock(Host.class);
        when(host.getClusterId()).thenReturn(1L);
        when(virtualMachineManager.toVmTO(any())).thenReturn(mock(VirtualMachineTO.class));
        when(agentMgr.easySend(anyLong(), any())).thenReturn(null);
        Answer result = extensionsManager.getInstanceConsole(vm, host);
        assertNull(result);
    }

    @Test
    public void getExternalAccessDetailsReturnsExpectedDetails() {
        Host host = mock(Host.class);
        when(host.getId()).thenReturn(100L);
        when(host.getClusterId()).thenReturn(1L);
        Map<String, String> vmDetails = Map.of("key1", "value1", "key2", "value2");
        ExtensionResourceMapVO resourceMapVO = mock(ExtensionResourceMapVO.class);
        when(extensionResourceMapDao.findByResourceIdAndType(1L, ExtensionResourceMap.ResourceType.Cluster))
                .thenReturn(resourceMapVO);
        doReturn(new HashMap<>()).when(extensionsManager).getExternalAccessDetails(null, 100L, resourceMapVO);
        Map<String, Map<String, String>> result = extensionsManager.getExternalAccessDetails(host, vmDetails);
        assertNotNull(result);
        assertNotNull(result.get(ApiConstants.VIRTUAL_MACHINE));
        assertEquals(vmDetails, result.get(ApiConstants.VIRTUAL_MACHINE));
    }

    @Test
    public void getExternalAccessDetailsReturnsExpectedNullDetails() {
        Host host = mock(Host.class);
        when(host.getId()).thenReturn(101L);
        when(host.getClusterId()).thenReturn(1L);
        Map<String, String> vmDetails = null;
        ExtensionResourceMapVO resourceMapVO = mock(ExtensionResourceMapVO.class);
        when(extensionResourceMapDao.findByResourceIdAndType(1L, ExtensionResourceMap.ResourceType.Cluster))
                .thenReturn(resourceMapVO);
        doReturn(new HashMap<>()).when(extensionsManager).getExternalAccessDetails(null, 101L, resourceMapVO);
        Map<String, Map<String, String>> result = extensionsManager.getExternalAccessDetails(host, vmDetails);
        assertNotNull(result);
        assertNull(result.get(ApiConstants.VIRTUAL_MACHINE));
    }

    @Test
    public void getCallerDetailsReturnsExpectedDetailsForValidCaller() {
        try (MockedStatic<CallContext> ignored = mockStatic(CallContext.class)) {
            mockCallerRole(RoleType.Admin);
            Map<String, String> result = extensionsManager.getCallerDetails();
            assertNotNull(result);
            assertTrue(UuidUtils.isUuid(result.get(ApiConstants.ID)));
            assertEquals("testAccount", result.get(ApiConstants.NAME));
            assertEquals("ADMIN", result.get(ApiConstants.TYPE));
            assertEquals("role-uuid-1", result.get(ApiConstants.ROLE_ID));
            assertEquals("AdminRole", result.get(ApiConstants.ROLE_NAME));
            assertEquals("Admin", result.get(ApiConstants.ROLE_TYPE));
        }
    }

    @Test
    public void getCallerDetailsReturnsNullWhenCallerIsNull() {
        CallContext callContext = mock(CallContext.class);
        when(callContext.getCallingAccount()).thenReturn(null);
        try (MockedStatic<CallContext> mockedCallContext = mockStatic(CallContext.class)) {
            mockedCallContext.when(CallContext::current).thenReturn(callContext);
            Map<String, String> result = extensionsManager.getCallerDetails();
            assertNull(result);
        }
    }

    @Test
    public void getCallerDetailsReturnsDetailsWithoutRoleWhenRoleIsNull() {
        try (MockedStatic<CallContext> ignored = mockStatic(CallContext.class)) {
            mockCallerRole(RoleType.User);
            when(roleService.findRole(1L)).thenReturn(null);
            Map<String, String> result = extensionsManager.getCallerDetails();
            assertNotNull(result);
            assertTrue(UuidUtils.isUuid(result.get(ApiConstants.ID)));
            assertEquals("testAccount", result.get(ApiConstants.NAME));
            assertEquals("NORMAL", result.get(ApiConstants.TYPE));
            assertNull(result.get(ApiConstants.ROLE_ID));
            assertNull(result.get(ApiConstants.ROLE_NAME));
            assertNull(result.get(ApiConstants.ROLE_TYPE));
        }
    }

    // -----------------------------------------------------------------------
    // Tests for ExtensionHelper methods (external network device support)
    // -----------------------------------------------------------------------

    @Test
    public void getExtensionForPhysicalNetworkReturnsExtensionWhenRegistered() {
        long physNetId = 10L;
        long extensionId = 5L;
        ExtensionResourceMapVO mapVO = mock(ExtensionResourceMapVO.class);
        when(mapVO.getExtensionId()).thenReturn(extensionId);
        when(extensionResourceMapDao.listByResourceIdAndType(physNetId,
                ExtensionResourceMap.ResourceType.PhysicalNetwork)).thenReturn(List.of(mapVO));
        ExtensionVO ext = mock(ExtensionVO.class);
        when(extensionDao.findById(extensionId)).thenReturn(ext);

        Extension result = extensionsManager.getExtensionForPhysicalNetwork(physNetId);

        assertNotNull(result);
        assertEquals(ext, result);
    }

    @Test
    public void getExtensionForPhysicalNetworkReturnsNullWhenNotRegistered() {
        long physNetId = 10L;
        when(extensionResourceMapDao.listByResourceIdAndType(physNetId,
                ExtensionResourceMap.ResourceType.PhysicalNetwork)).thenReturn(Collections.emptyList());

        Extension result = extensionsManager.getExtensionForPhysicalNetwork(physNetId);

        assertNull(result);
    }

    @Test
    public void getResourceMapIdForPhysicalNetworkReturnsIdWhenRegistered() {
        long physNetId = 10L;
        long mapId = 99L;
        ExtensionResourceMapVO mapVO = mock(ExtensionResourceMapVO.class);
        when(mapVO.getId()).thenReturn(mapId);
        when(extensionResourceMapDao.listByResourceIdAndType(physNetId,
                ExtensionResourceMap.ResourceType.PhysicalNetwork)).thenReturn(List.of(mapVO));

        Long result = extensionsManager.getResourceMapIdForPhysicalNetwork(physNetId);

        assertNotNull(result);
        assertEquals(Long.valueOf(mapId), result);
    }

    @Test
    public void getResourceMapIdForPhysicalNetworkReturnsNullWhenNotRegistered() {
        long physNetId = 10L;
        when(extensionResourceMapDao.listByResourceIdAndType(physNetId,
                ExtensionResourceMap.ResourceType.PhysicalNetwork)).thenReturn(Collections.emptyList());

        Long result = extensionsManager.getResourceMapIdForPhysicalNetwork(physNetId);

        assertNull(result);
    }

    @Test
    public void getResourceMapDetailsForPhysicalNetworkReturnsDisplayDetailsOnly() {
        long physNetId = 10L;
        long mapId = 99L;
        ExtensionResourceMapVO mapVO = mock(ExtensionResourceMapVO.class);
        when(mapVO.getId()).thenReturn(mapId);
        when(extensionResourceMapDao.listByResourceIdAndType(physNetId,
                ExtensionResourceMap.ResourceType.PhysicalNetwork)).thenReturn(List.of(mapVO));
        Map<String, String> displayDetails = Map.of("host", "192.168.1.1", "port", "22");
        when(extensionResourceMapDetailsDao.listDetailsKeyPairs(mapId, true)).thenReturn(displayDetails);

        Map<String, String> result = extensionsManager.getResourceMapDetailsForPhysicalNetwork(physNetId);

        assertNotNull(result);
        assertEquals(displayDetails, result);
        verify(extensionResourceMapDetailsDao).listDetailsKeyPairs(mapId, true);
    }

    @Test
    public void getResourceMapDetailsForPhysicalNetworkReturnsEmptyMapWhenNotRegistered() {
        long physNetId = 10L;
        when(extensionResourceMapDao.listByResourceIdAndType(physNetId,
                ExtensionResourceMap.ResourceType.PhysicalNetwork)).thenReturn(Collections.emptyList());

        Map<String, String> result = extensionsManager.getResourceMapDetailsForPhysicalNetwork(physNetId);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    public void getAllResourceMapDetailsForPhysicalNetworkReturnsAllDetails() {
        long physNetId = 10L;
        long mapId = 99L;
        ExtensionResourceMapVO mapVO = mock(ExtensionResourceMapVO.class);
        when(mapVO.getId()).thenReturn(mapId);
        when(extensionResourceMapDao.listByResourceIdAndType(physNetId,
                ExtensionResourceMap.ResourceType.PhysicalNetwork)).thenReturn(List.of(mapVO));
        Map<String, String> allDetails = Map.of("host", "192.168.1.1", "port", "22",
                "username", "root", "sshkey", "-----BEGIN RSA-----");
        when(extensionResourceMapDetailsDao.listDetailsKeyPairs(mapId)).thenReturn(allDetails);

        Map<String, String> result = extensionsManager.getAllResourceMapDetailsForPhysicalNetwork(physNetId);

        assertNotNull(result);
        assertEquals(allDetails, result);
        // Must call without display filter so hidden keys (sshkey) are included
        verify(extensionResourceMapDetailsDao).listDetailsKeyPairs(mapId);
    }

    @Test
    public void getAllResourceMapDetailsForPhysicalNetworkReturnsEmptyMapWhenNotRegistered() {
        long physNetId = 10L;
        when(extensionResourceMapDao.listByResourceIdAndType(physNetId,
                ExtensionResourceMap.ResourceType.PhysicalNetwork)).thenReturn(Collections.emptyList());

        Map<String, String> result = extensionsManager.getAllResourceMapDetailsForPhysicalNetwork(physNetId);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    public void updateResourceMapDetailsSavesDetailsWithCorrectDisplayFlags() {
        long mapId = 99L;
        Map<String, String> details = new HashMap<>();
        details.put("host", "192.168.1.1");
        details.put("port", "22");
        details.put("sshkey", "-----BEGIN RSA-----");
        // host and port are display=true; sshkey is display=false
        java.util.Set<String> displayKeys = java.util.Set.of("host", "port");

        extensionsManager.updateResourceMapDetails(mapId, details, displayKeys);

        // saveDetails must be called once with a list containing all 3 entries
        verify(extensionResourceMapDetailsDao).saveDetails(any(List.class));
    }

    @Test
    public void updateResourceMapDetailsDoesNothingForEmptyMap() {
        long mapId = 99L;
        extensionsManager.updateResourceMapDetails(mapId, new HashMap<>(),
                java.util.Set.of("host"));

        verify(extensionResourceMapDetailsDao, never()).saveDetails(any());
    }

    @Test
    public void updateResourceMapDetailsDoesNothingForNullMap() {
        long mapId = 99L;
        extensionsManager.updateResourceMapDetails(mapId, null,
                java.util.Set.of("host"));

        verify(extensionResourceMapDetailsDao, never()).saveDetails(any());
    }

    @Test
    public void removeResourceMapDetailsCallsRemoveDetailForEachKey() {
        long mapId = 99L;
        List<String> keys = List.of("sshkey", "password");

        extensionsManager.removeResourceMapDetails(mapId, keys);

        verify(extensionResourceMapDetailsDao).removeDetail(mapId, "sshkey");
        verify(extensionResourceMapDetailsDao).removeDetail(mapId, "password");
    }

    @Test
    public void removeResourceMapDetailsDoesNothingForEmptyList() {
        long mapId = 99L;
        extensionsManager.removeResourceMapDetails(mapId, Collections.emptyList());

        verify(extensionResourceMapDetailsDao, never()).removeDetail(anyLong(), anyString());
    }

    @Test
    public void removeResourceMapDetailsDoesNothingForNullList() {
        long mapId = 99L;
        extensionsManager.removeResourceMapDetails(mapId, null);

        verify(extensionResourceMapDetailsDao, never()).removeDetail(anyLong(), anyString());
    }

    @Test
    public void listPhysicalNetworkIdsWithExtensionReturnsMappedIds() {
        List<Long> expectedIds = List.of(1L, 2L, 3L);
        when(extensionResourceMapDao.listResourceIdsByType(
                ExtensionResourceMap.ResourceType.PhysicalNetwork)).thenReturn(expectedIds);

        List<Long> result = extensionsManager.listPhysicalNetworkIdsWithExtension();

        assertNotNull(result);
        assertEquals(3, result.size());
        assertEquals(expectedIds, result);
    }

    @Test
    public void listPhysicalNetworkIdsWithExtensionReturnsEmptyListWhenNone() {
        when(extensionResourceMapDao.listResourceIdsByType(
                ExtensionResourceMap.ResourceType.PhysicalNetwork)).thenReturn(Collections.emptyList());

        List<Long> result = extensionsManager.listPhysicalNetworkIdsWithExtension();

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    public void listExtensionsForPhysicalNetworkReturnsAllRegisteredExtensions() {
        long physNetId = 10L;

        ExtensionResourceMapVO map1 = mock(ExtensionResourceMapVO.class);
        when(map1.getExtensionId()).thenReturn(1L);
        ExtensionResourceMapVO map2 = mock(ExtensionResourceMapVO.class);
        when(map2.getExtensionId()).thenReturn(2L);

        when(extensionResourceMapDao.listByResourceIdAndType(physNetId,
                ExtensionResourceMap.ResourceType.PhysicalNetwork)).thenReturn(List.of(map1, map2));

        ExtensionVO ext1 = mock(ExtensionVO.class);
        ExtensionVO ext2 = mock(ExtensionVO.class);
        when(extensionDao.findById(1L)).thenReturn(ext1);
        when(extensionDao.findById(2L)).thenReturn(ext2);

        List<Extension> result = extensionsManager.listExtensionsForPhysicalNetwork(physNetId);

        assertNotNull(result);
        assertEquals(2, result.size());
        assertTrue(result.contains(ext1));
        assertTrue(result.contains(ext2));
    }

    @Test
    public void listExtensionsForPhysicalNetworkReturnsEmptyWhenNoneRegistered() {
        long physNetId = 10L;
        when(extensionResourceMapDao.listByResourceIdAndType(physNetId,
                ExtensionResourceMap.ResourceType.PhysicalNetwork)).thenReturn(Collections.emptyList());

        List<Extension> result = extensionsManager.listExtensionsForPhysicalNetwork(physNetId);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test(expected = CloudRuntimeException.class)
    public void registerExtensionWithPhysicalNetworkThrowsWhenSameExtensionRegisteredTwice() {
        long physNetId = 1L;
        long extId = 10L;
        PhysicalNetworkVO physNet = mock(PhysicalNetworkVO.class);
        when(physNet.getId()).thenReturn(physNetId);
        Extension extension = mock(Extension.class);
        when(extension.getType()).thenReturn(Extension.Type.NetworkOrchestrator);
        when(extension.getId()).thenReturn(extId);
        when(extension.getName()).thenReturn("ext-net");

        ExtensionResourceMapVO existingMap = mock(ExtensionResourceMapVO.class);
        when(existingMap.getExtensionId()).thenReturn(extId);
        when(extensionResourceMapDao.listByResourceIdAndType(physNetId,
                ExtensionResourceMap.ResourceType.PhysicalNetwork)).thenReturn(List.of(existingMap));

        extensionsManager.registerExtensionWithPhysicalNetwork(physNet, extension, null);
    }

    @Test
    public void registerExtensionWithPhysicalNetworkAllowsDifferentExtensionOnSameNetwork() {
        long physNetId = 1L;
        PhysicalNetworkVO physNet = mock(PhysicalNetworkVO.class);
        when(physNet.getId()).thenReturn(physNetId);

        Extension extension2 = mock(Extension.class);
        when(extension2.getType()).thenReturn(Extension.Type.NetworkOrchestrator);
        when(extension2.getId()).thenReturn(20L);

        // Extension 10 is already registered, but we're registering extension 20
        ExtensionResourceMapVO existingMap = mock(ExtensionResourceMapVO.class);
        when(existingMap.getExtensionId()).thenReturn(10L);
        when(extensionResourceMapDao.listByResourceIdAndType(physNetId,
                ExtensionResourceMap.ResourceType.PhysicalNetwork)).thenReturn(List.of(existingMap));

        ExtensionResourceMapVO savedMap = mock(ExtensionResourceMapVO.class);
        when(extensionResourceMapDao.persist(any())).thenReturn(savedMap);

        ExtensionResourceMap result = extensionsManager.registerExtensionWithPhysicalNetwork(physNet, extension2, null);
        assertNotNull(result);
    }

    // ---- registerExtensionWithPhysicalNetwork: type validation ----

    @Test(expected = InvalidParameterValueException.class)
    public void registerExtensionWithPhysicalNetworkThrowsForNonNetworkOrchestratorType() {
        PhysicalNetworkVO physNet = mock(PhysicalNetworkVO.class);
        Extension extension = mock(Extension.class);
        when(extension.getType()).thenReturn(Extension.Type.Orchestrator);
        when(extension.getName()).thenReturn("ext-orchestrator");

        extensionsManager.registerExtensionWithPhysicalNetwork(physNet, extension, null);
    }

    @Test
    public void registerExtensionWithPhysicalNetworkSucceedsForNetworkOrchestratorType() {
        PhysicalNetworkVO physNet = mock(PhysicalNetworkVO.class);
        when(physNet.getId()).thenReturn(1L);
        Extension extension = mock(Extension.class);
        when(extension.getType()).thenReturn(Extension.Type.NetworkOrchestrator);
        when(extension.getId()).thenReturn(10L);
        when(extensionResourceMapDao.listByResourceIdAndType(1L,
                ExtensionResourceMap.ResourceType.PhysicalNetwork)).thenReturn(Collections.emptyList());
        ExtensionResourceMapVO savedMap = mock(ExtensionResourceMapVO.class);
        when(extensionResourceMapDao.persist(any())).thenReturn(savedMap);

        ExtensionResourceMap result = extensionsManager.registerExtensionWithPhysicalNetwork(physNet, extension, null);

        assertNotNull(result);
        verify(extensionResourceMapDao).persist(any());
    }

    // ---- registerExtensionWithPhysicalNetwork: services subset validation ----

    @Test
    public void registerExtensionWithPhysicalNetworkAcceptsServicesSubset() {
        PhysicalNetworkVO physNet = mock(PhysicalNetworkVO.class);
        when(physNet.getId()).thenReturn(1L);
        Extension extension = mock(Extension.class);
        when(extension.getType()).thenReturn(Extension.Type.NetworkOrchestrator);
        when(extension.getId()).thenReturn(10L);
        when(extension.getName()).thenReturn("extnet");

        String capsJson = "{\"services\":[\"SourceNat\",\"StaticNat\",\"PortForwarding\",\"Firewall\",\"Gateway\"],"
                + "\"capabilities\":{}}";
        when(extensionDetailsDao.listDetailsKeyPairs(10L))
                .thenReturn(Map.of("network.capabilities", capsJson));

        when(extensionResourceMapDao.listByResourceIdAndType(1L,
                ExtensionResourceMap.ResourceType.PhysicalNetwork)).thenReturn(Collections.emptyList());
        ExtensionResourceMapVO savedMap = mock(ExtensionResourceMapVO.class);
        when(extensionResourceMapDao.persist(any())).thenReturn(savedMap);

        // Services detail is accepted (no longer validated against capabilities — just stored)
        Map<String, String> details = Map.of("services", "SourceNat,StaticNat");
        ExtensionResourceMap result = extensionsManager.registerExtensionWithPhysicalNetwork(physNet, extension, details);

        assertNotNull(result);
    }

    @Test
    public void registerExtensionWithPhysicalNetworkAcceptsAnyServicesNoValidation() {
        // Services are no longer validated against extension capabilities — they are just stored
        PhysicalNetworkVO physNet = mock(PhysicalNetworkVO.class);
        when(physNet.getId()).thenReturn(1L);
        Extension extension = mock(Extension.class);
        when(extension.getType()).thenReturn(Extension.Type.NetworkOrchestrator);
        when(extension.getId()).thenReturn(10L);
        when(extension.getName()).thenReturn("extnet");

        // Extension only declares SourceNat and Gateway, but any services detail is now accepted
        String capsJson = "{\"services\":[\"SourceNat\",\"Gateway\"],\"capabilities\":{}}";
        when(extensionDetailsDao.listDetailsKeyPairs(10L))
                .thenReturn(Map.of("network.capabilities", capsJson));

        when(extensionResourceMapDao.listByResourceIdAndType(1L,
                ExtensionResourceMap.ResourceType.PhysicalNetwork)).thenReturn(Collections.emptyList());
        ExtensionResourceMapVO savedMap = mock(ExtensionResourceMapVO.class);
        when(extensionResourceMapDao.persist(any())).thenReturn(savedMap);

        // No longer throws — services detail is just stored without validation
        Map<String, String> details = Map.of("services", "SourceNat,StaticNat");
        ExtensionResourceMap result = extensionsManager.registerExtensionWithPhysicalNetwork(physNet, extension, details);
        assertNotNull(result);
    }

    @Test
    public void registerExtensionWithPhysicalNetworkAcceptsAllServicesWhenNoCapabilitiesDeclared() {
        PhysicalNetworkVO physNet = mock(PhysicalNetworkVO.class);
        when(physNet.getId()).thenReturn(1L);
        Extension extension = mock(Extension.class);
        when(extension.getType()).thenReturn(Extension.Type.NetworkOrchestrator);
        when(extension.getId()).thenReturn(10L);
        when(extension.getName()).thenReturn("extnet");

        // No network.capabilities detail → all services accepted
        when(extensionDetailsDao.listDetailsKeyPairs(10L)).thenReturn(Collections.emptyMap());

        when(extensionResourceMapDao.listByResourceIdAndType(1L,
                ExtensionResourceMap.ResourceType.PhysicalNetwork)).thenReturn(Collections.emptyList());
        ExtensionResourceMapVO savedMap = mock(ExtensionResourceMapVO.class);
        when(extensionResourceMapDao.persist(any())).thenReturn(savedMap);

        Map<String, String> details = Map.of("services", "SourceNat,StaticNat,PortForwarding");
        ExtensionResourceMap result = extensionsManager.registerExtensionWithPhysicalNetwork(physNet, extension, details);

        assertNotNull(result);
    }

    @Test
    public void registerExtensionWithPhysicalNetworkStoresSensitiveKeysAsHidden() {
        PhysicalNetworkVO physNet = mock(PhysicalNetworkVO.class);
        when(physNet.getId()).thenReturn(1L);
        Extension extension = mock(Extension.class);
        when(extension.getType()).thenReturn(Extension.Type.NetworkOrchestrator);
        when(extension.getId()).thenReturn(10L);
        when(extension.getName()).thenReturn("extnet");
        when(extensionDetailsDao.listDetailsKeyPairs(10L)).thenReturn(Collections.emptyMap());

        when(extensionResourceMapDao.listByResourceIdAndType(1L,
                ExtensionResourceMap.ResourceType.PhysicalNetwork)).thenReturn(Collections.emptyList());
        ExtensionResourceMapVO savedMap = mock(ExtensionResourceMapVO.class);
        when(savedMap.getId()).thenReturn(99L);
        when(extensionResourceMapDao.persist(any())).thenReturn(savedMap);

        // sshkey and password should be stored with display=false
        Map<String, String> details = new HashMap<>();
        details.put("host", "192.168.1.10");
        details.put("sshkey", "-----BEGIN RSA PRIVATE KEY-----...");
        details.put("password", "secret");
        details.put("username", "root");
        extensionsManager.registerExtensionWithPhysicalNetwork(physNet, extension, details);

        // Verify saveDetails was called with a list containing the right display flags
        verify(extensionResourceMapDetailsDao).saveDetails(argThat(list -> {
            for (ExtensionResourceMapDetailsVO detail : list) {
                    if ("sshkey".equals(detail.getName()) && detail.isDisplay()) return false;
                    if ("password".equals(detail.getName()) && detail.isDisplay()) return false;
                    if ("host".equals(detail.getName()) && !detail.isDisplay()) return false;
                    if ("username".equals(detail.getName()) && !detail.isDisplay()) return false;
            }
            return true;
        }));
    }

    // ---- validateNetworkServicesSubset ----

    @Test
    public void validateNetworkServicesSubsetAcceptsValidSubset() {
        ExtensionVO extension = mock(ExtensionVO.class);
        when(extension.getId()).thenReturn(1L);
        when(extension.getName()).thenReturn("ext");
        String capsJson = "{\"services\":[\"SourceNat\",\"StaticNat\",\"Gateway\"],\"capabilities\":{}}";
        when(extensionDetailsDao.listDetailsKeyPairs(1L)).thenReturn(Map.of("network.capabilities", capsJson));

        // Should not throw
        extensionsManager.validateNetworkServicesSubset(extension, "SourceNat,StaticNat");
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateNetworkServicesSubsetThrowsForInvalidService() {
        ExtensionVO extension = mock(ExtensionVO.class);
        when(extension.getId()).thenReturn(1L);
        when(extension.getName()).thenReturn("ext");
        String capsJson = "{\"services\":[\"SourceNat\",\"Gateway\"],\"capabilities\":{}}";
        when(extensionDetailsDao.listDetailsKeyPairs(1L)).thenReturn(Map.of("network.capabilities", capsJson));

        extensionsManager.validateNetworkServicesSubset(extension, "SourceNat,PortForwarding");
    }

    @Test
    public void validateNetworkServicesSubsetAcceptsJsonArrayFormat() {
        ExtensionVO extension = mock(ExtensionVO.class);
        when(extension.getId()).thenReturn(1L);
        when(extension.getName()).thenReturn("ext");
        String capsJson = "{\"services\":[\"SourceNat\",\"StaticNat\",\"Gateway\"],\"capabilities\":{}}";
        when(extensionDetailsDao.listDetailsKeyPairs(1L)).thenReturn(Map.of("network.capabilities", capsJson));

        // Should not throw — JSON array format
        extensionsManager.validateNetworkServicesSubset(extension, "[\"SourceNat\",\"Gateway\"]");
    }

    @Test
    public void validateNetworkServicesSubsetSkipsWhenNoCapabilitiesDetail() {
        ExtensionVO extension = mock(ExtensionVO.class);
        when(extension.getId()).thenReturn(1L);
        when(extensionDetailsDao.listDetailsKeyPairs(1L)).thenReturn(Collections.emptyMap());

        // Should not throw — no capabilities declared means all services are allowed
        extensionsManager.validateNetworkServicesSubset(extension, "SourceNat,StaticNat,PortForwarding");
    }

    @Test
    public void validateNetworkServicesSubsetSkipsBlankValue() {
        ExtensionVO extension = mock(ExtensionVO.class);
        // Should not throw — blank services value is a no-op
        extensionsManager.validateNetworkServicesSubset(extension, "");
        extensionsManager.validateNetworkServicesSubset(extension, "   ");
        extensionsManager.validateNetworkServicesSubset(extension, null);
    }

    // -----------------------------------------------------------------------
    // Network custom action tests
    // -----------------------------------------------------------------------

    @Test
    public void getExtensionFromResourceNetworkReturnsExtensionViaProviderName() {
        // The network's service-map has provider "my-ext" which matches an extension name
        Network network = mock(Network.class);
        when(network.getPhysicalNetworkId()).thenReturn(10L);
        when(entityManager.findByUuid(eq(Network.class), eq("net-uuid"))).thenReturn(network);
        when(networkServiceMapDao.getDistinctProviders(network.getId())).thenReturn(List.of("my-ext", "VirtualRouter"));

        ExtensionResourceMapVO mapVO = mock(ExtensionResourceMapVO.class);
        when(mapVO.getExtensionId()).thenReturn(200L);
        ExtensionVO ext = mock(ExtensionVO.class);
        when(ext.getName()).thenReturn("my-ext");
        when(ext.getId()).thenReturn(200L);

        when(extensionResourceMapDao.listByResourceIdAndType(10L, ExtensionResourceMap.ResourceType.PhysicalNetwork))
                .thenReturn(List.of(mapVO));
        when(extensionDao.findById(200L)).thenReturn(ext);

        Extension result = extensionsManager.getExtensionFromResource(
                ExtensionCustomAction.ResourceType.Network, "net-uuid");
        assertEquals(ext, result);
    }

    @Test
    public void getExtensionFromResourceNetworkFallsBackToFirstExtensionWhenNoProviderMatch() {
        // No provider name matches any extension name — should fall back to first extension
        Network network = mock(Network.class);
        when(network.getPhysicalNetworkId()).thenReturn(10L);
        when(entityManager.findByUuid(eq(Network.class), eq("net-uuid"))).thenReturn(network);
        when(networkServiceMapDao.getDistinctProviders(network.getId())).thenReturn(List.of("VirtualRouter"));

        ExtensionResourceMapVO mapVO = mock(ExtensionResourceMapVO.class);
        when(mapVO.getExtensionId()).thenReturn(300L);
        ExtensionVO ext = mock(ExtensionVO.class);
        when(ext.getName()).thenReturn("some-other-ext");  // no match with "VirtualRouter"
        when(ext.getId()).thenReturn(300L);

        when(extensionResourceMapDao.listByResourceIdAndType(10L, ExtensionResourceMap.ResourceType.PhysicalNetwork))
                .thenReturn(List.of(mapVO));
        when(extensionDao.findById(300L)).thenReturn(ext);

        Extension result = extensionsManager.getExtensionFromResource(
                ExtensionCustomAction.ResourceType.Network, "net-uuid");
        // Falls back to first extension in the list
        assertEquals(ext, result);
    }

    @Test
    public void getExtensionFromResourceNetworkReturnsNullWhenNoPhysicalNetwork() {
        Network network = mock(Network.class);
        when(network.getPhysicalNetworkId()).thenReturn(null);
        when(entityManager.findByUuid(eq(Network.class), eq("net-uuid"))).thenReturn(network);

        Extension result = extensionsManager.getExtensionFromResource(
                ExtensionCustomAction.ResourceType.Network, "net-uuid");
        assertNull(result);
    }

    @Test
    public void getExtensionFromResourceNetworkReturnsNullWhenNoMapping() {
        Network network = mock(Network.class);
        when(network.getPhysicalNetworkId()).thenReturn(10L);
        when(entityManager.findByUuid(eq(Network.class), eq("net-uuid"))).thenReturn(network);
        when(networkServiceMapDao.getDistinctProviders(network.getId())).thenReturn(Collections.emptyList());
        when(extensionResourceMapDao.listByResourceIdAndType(10L, ExtensionResourceMap.ResourceType.PhysicalNetwork))
                .thenReturn(Collections.emptyList());

        Extension result = extensionsManager.getExtensionFromResource(
                ExtensionCustomAction.ResourceType.Network, "net-uuid");
        assertNull(result);
    }

    // -----------------------------------------------------------------------
    // getExtensionForPhysicalNetworkAndProvider tests
    // -----------------------------------------------------------------------

    @Test
    public void getExtensionForPhysicalNetworkAndProviderMatchesByName() {
        ExtensionResourceMapVO mapVO1 = mock(ExtensionResourceMapVO.class);
        when(mapVO1.getExtensionId()).thenReturn(10L);
        ExtensionResourceMapVO mapVO2 = mock(ExtensionResourceMapVO.class);
        when(mapVO2.getExtensionId()).thenReturn(20L);

        ExtensionVO ext1 = mock(ExtensionVO.class);
        when(ext1.getName()).thenReturn("ext-alpha");
        ExtensionVO ext2 = mock(ExtensionVO.class);
        when(ext2.getName()).thenReturn("ext-beta");

        when(extensionResourceMapDao.listByResourceIdAndType(5L, ExtensionResourceMap.ResourceType.PhysicalNetwork))
                .thenReturn(List.of(mapVO1, mapVO2));
        when(extensionDao.findById(10L)).thenReturn(ext1);
        when(extensionDao.findById(20L)).thenReturn(ext2);

        Extension result = extensionsManager.getExtensionForPhysicalNetworkAndProvider(5L, "ext-beta");
        assertEquals(ext2, result);
    }

    @Test
    public void getExtensionForPhysicalNetworkAndProviderIsCaseInsensitive() {
        ExtensionResourceMapVO mapVO = mock(ExtensionResourceMapVO.class);
        when(mapVO.getExtensionId()).thenReturn(10L);
        ExtensionVO ext = mock(ExtensionVO.class);
        when(ext.getName()).thenReturn("Ext-Alpha");

        when(extensionResourceMapDao.listByResourceIdAndType(5L, ExtensionResourceMap.ResourceType.PhysicalNetwork))
                .thenReturn(List.of(mapVO));
        when(extensionDao.findById(10L)).thenReturn(ext);

        // Provider name uses different casing
        Extension result = extensionsManager.getExtensionForPhysicalNetworkAndProvider(5L, "ext-alpha");
        assertEquals(ext, result);
    }

    @Test
    public void getExtensionForPhysicalNetworkAndProviderReturnsNullWhenNoMatch() {
        ExtensionResourceMapVO mapVO = mock(ExtensionResourceMapVO.class);
        when(mapVO.getExtensionId()).thenReturn(10L);
        ExtensionVO ext = mock(ExtensionVO.class);
        when(ext.getName()).thenReturn("ext-alpha");

        when(extensionResourceMapDao.listByResourceIdAndType(5L, ExtensionResourceMap.ResourceType.PhysicalNetwork))
                .thenReturn(List.of(mapVO));
        when(extensionDao.findById(10L)).thenReturn(ext);

        Extension result = extensionsManager.getExtensionForPhysicalNetworkAndProvider(5L, "ext-gamma");
        assertNull(result);
    }

    @Test
    public void getExtensionForPhysicalNetworkAndProviderReturnsNullForBlankName() {
        Extension result = extensionsManager.getExtensionForPhysicalNetworkAndProvider(5L, "");
        assertNull(result);
        result = extensionsManager.getExtensionForPhysicalNetworkAndProvider(5L, null);
        assertNull(result);
    }

    // -----------------------------------------------------------------------
    // getAllResourceMapDetailsForExtensionOnPhysicalNetwork tests
    // -----------------------------------------------------------------------

    @Test
    public void getAllResourceMapDetailsForExtensionReturnsCorrectDetails() {
        ExtensionResourceMapVO mapVO1 = mock(ExtensionResourceMapVO.class);
        when(mapVO1.getExtensionId()).thenReturn(10L);
        when(mapVO1.getId()).thenReturn(100L);
        ExtensionResourceMapVO mapVO2 = mock(ExtensionResourceMapVO.class);
        when(mapVO2.getExtensionId()).thenReturn(20L);
        when(mapVO2.getId()).thenReturn(200L);

        when(extensionResourceMapDao.listByResourceIdAndType(5L, ExtensionResourceMap.ResourceType.PhysicalNetwork))
                .thenReturn(List.of(mapVO1, mapVO2));

        Map<String, String> details = Map.of("host", "192.168.1.50", "port", "22");
        when(extensionResourceMapDetailsDao.listDetailsKeyPairs(200L)).thenReturn(details);

        Map<String, String> result = extensionsManager.getAllResourceMapDetailsForExtensionOnPhysicalNetwork(5L, 20L);
        assertEquals(details, result);
    }

    @Test
    public void getAllResourceMapDetailsForExtensionReturnsEmptyWhenExtensionNotOnPhysicalNetwork() {
        ExtensionResourceMapVO mapVO = mock(ExtensionResourceMapVO.class);
        when(mapVO.getExtensionId()).thenReturn(10L);

        when(extensionResourceMapDao.listByResourceIdAndType(5L, ExtensionResourceMap.ResourceType.PhysicalNetwork))
                .thenReturn(List.of(mapVO));

        // Looking for extensionId=99 which is not in the list
        Map<String, String> result = extensionsManager.getAllResourceMapDetailsForExtensionOnPhysicalNetwork(5L, 99L);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    public void runNetworkCustomActionSucceeds() {
        // Set up network
        Network network = mock(Network.class);
        when(network.getId()).thenReturn(5L);
        when(network.getName()).thenReturn("test-net");

        // Set up custom action
        ExtensionCustomActionVO actionVO = mock(ExtensionCustomActionVO.class);
        when(actionVO.getUuid()).thenReturn("action-uuid");
        when(actionVO.getName()).thenReturn("reboot-device");
        when(actionVO.getId()).thenReturn(1L);
        when(actionVO.getSuccessMessage()).thenReturn(null);
        when(actionVO.getErrorMessage()).thenReturn(null);

        ExtensionVO extensionVO = mock(ExtensionVO.class);
        when(extensionVO.getName()).thenReturn("my-extnet");

        // No action parameters
        Pair<Map<String, String>, Map<String, String>> details = new Pair<>(new HashMap<>(), new HashMap<>());
        when(extensionCustomActionDetailsDao.listDetailsKeyPairsWithVisibility(1L)).thenReturn(details);

        // Provider succeeds
        NetworkCustomActionProvider provider = mock(NetworkCustomActionProvider.class);
        when(provider.canHandleCustomAction(network)).thenReturn(true);
        when(provider.runCustomAction(eq(network), eq("reboot-device"), any())).thenReturn("OK: bridge bounced");
        ReflectionTestUtils.setField(extensionsManager, "networkCustomActionProviders", List.of(provider));

        CustomActionResultResponse resp = extensionsManager.runNetworkCustomAction(
                network, actionVO, extensionVO,
                ExtensionCustomAction.ResourceType.Network, new HashMap<>());

        assertTrue(resp.isSuccess());
        assertEquals("OK: bridge bounced", resp.getResult().get(ApiConstants.DETAILS));
    }

    @Test
    public void runNetworkCustomActionFailsWhenNoProvider() {
        Network network = mock(Network.class);
        when(network.getId()).thenReturn(5L);
        when(network.getName()).thenReturn("test-net");

        ExtensionCustomActionVO actionVO = mock(ExtensionCustomActionVO.class);
        when(actionVO.getUuid()).thenReturn("action-uuid");
        when(actionVO.getName()).thenReturn("dump-config");
        when(actionVO.getId()).thenReturn(2L);
        when(actionVO.getSuccessMessage()).thenReturn(null);
        when(actionVO.getErrorMessage()).thenReturn(null);

        ExtensionVO extensionVO = mock(ExtensionVO.class);
        when(extensionVO.getName()).thenReturn("my-extnet");

        Pair<Map<String, String>, Map<String, String>> details = new Pair<>(new HashMap<>(), new HashMap<>());
        when(extensionCustomActionDetailsDao.listDetailsKeyPairsWithVisibility(2L)).thenReturn(details);

        // No providers registered
        ReflectionTestUtils.setField(extensionsManager, "networkCustomActionProviders", Collections.emptyList());

        CustomActionResultResponse resp = extensionsManager.runNetworkCustomAction(
                network, actionVO, extensionVO,
                ExtensionCustomAction.ResourceType.Network, new HashMap<>());

        assertFalse(resp.isSuccess());
        assertTrue(resp.getResult().get(ApiConstants.DETAILS).contains("No external network provider"));
    }

    @Test
    public void runNetworkCustomActionFailsWhenProviderReturnsNull() {
        Network network = mock(Network.class);
        when(network.getId()).thenReturn(5L);
        when(network.getName()).thenReturn("test-net");

        ExtensionCustomActionVO actionVO = mock(ExtensionCustomActionVO.class);
        when(actionVO.getUuid()).thenReturn("action-uuid");
        when(actionVO.getName()).thenReturn("unknown-action");
        when(actionVO.getId()).thenReturn(3L);
        when(actionVO.getSuccessMessage()).thenReturn(null);
        when(actionVO.getErrorMessage()).thenReturn(null);

        ExtensionVO extensionVO = mock(ExtensionVO.class);
        when(extensionVO.getName()).thenReturn("my-extnet");

        Pair<Map<String, String>, Map<String, String>> details = new Pair<>(new HashMap<>(), new HashMap<>());
        when(extensionCustomActionDetailsDao.listDetailsKeyPairsWithVisibility(3L)).thenReturn(details);

        NetworkCustomActionProvider provider = mock(NetworkCustomActionProvider.class);
        when(provider.canHandleCustomAction(network)).thenReturn(true);
        when(provider.runCustomAction(eq(network), eq("unknown-action"), any())).thenReturn(null);
        ReflectionTestUtils.setField(extensionsManager, "networkCustomActionProviders", List.of(provider));

        CustomActionResultResponse resp = extensionsManager.runNetworkCustomAction(
                network, actionVO, extensionVO,
                ExtensionCustomAction.ResourceType.Network, new HashMap<>());

        assertFalse(resp.isSuccess());
        assertTrue(resp.getResult().get(ApiConstants.DETAILS).contains("Action failed"));
    }

}
