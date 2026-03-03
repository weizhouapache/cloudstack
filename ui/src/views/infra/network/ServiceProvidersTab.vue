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

<template>
  <div>
    <a-spin :spinning="fetchLoading">
      <!-- Add ExternalNetwork provider button: shown on extension tabs when ExternalNetwork NSP not yet added -->
      <a-button
        v-if="isExtensionTab && !nsps['ExternalNetwork']"
        :disabled="!('addNetworkServiceProvider' in $store.getters.apis)"
        type="dashed"
        style="width: 100%; margin-bottom: 12px;"
        @click="handleAddExternalNetworkProvider">
        <template #icon><plus-outlined /></template>
        {{ $t('label.add.external.network.provider') }}
      </a-button>
      <!-- Enable/Disable buttons for extension provider tab -->
      <a-button
        v-if="isExtensionTab && nsps['ExternalNetwork'] && nsps['ExternalNetwork'].state === 'Disabled'"
        type="primary"
        style="margin-bottom: 12px; margin-right: 8px;"
        @click="handleEnableExtensionProvider">
        <template #icon><play-circle-outlined /></template>
        {{ $t('label.enable.provider') }}
      </a-button>
      <a-button
        v-if="isExtensionTab && nsps['ExternalNetwork'] && nsps['ExternalNetwork'].state === 'Enabled'"
        danger
        style="margin-bottom: 12px; margin-right: 8px;"
        @click="handleDisableExtensionProvider">
        <template #icon><stop-outlined /></template>
        {{ $t('label.disable.provider') }}
      </a-button>
      <!-- Add External Network Device button (only when extension tab is active and provider is added) -->
      <a-button
        v-if="isExtensionTab && nsps['ExternalNetwork'] && nsps['ExternalNetwork'].id"
        :disabled="!('addExternalNetworkDevice' in $store.getters.apis)"
        type="dashed"
        style="width: 100%; margin-bottom: 12px;"
        @click="handleOpenAddDeviceModal">
        <template #icon><plus-outlined /></template>
        {{ $t('label.add.external.network.device') }}
      </a-button>
      <a-tabs
        :tabPosition="device === 'mobile' ? 'top' : 'left'"
        :animated="false"
        @change="onTabChange">
        <!-- Hardcoded NSP tabs -->
        <a-tab-pane
          class="custom-tab-pane"
          v-for="item in hardcodedNsps"
          :key="item.title">
          <template #tab>
            <span>
              {{ $t(item.title) }}
              <status :text="item.title in nsps ? nsps[item.title].state : $t('label.disabled')" style="margin-bottom: 6px; margin-left: 6px" />
            </span>
          </template>
          <provider-item
            v-if="tabKey===item.title"
            :loading="loading"
            :itemNsp="item"
            :nsp="nsps[item.title]"
            :resourceId="resource.id"
            :zoneId="resource.zoneid"
            :tabKey="tabKey"/>
        </a-tab-pane>
        <!-- Dynamic extension-based provider tabs (one per registered NetworkOrchestrator extension) -->
        <a-tab-pane
          class="custom-tab-pane"
          v-for="ext in registeredExtensions"
          :key="ext.name">
          <template #tab>
            <span>
              {{ ext.name }}
              <status :text="nsps['ExternalNetwork'] ? nsps['ExternalNetwork'].state : $t('label.not.added')" style="margin-bottom: 6px; margin-left: 6px" />
            </span>
          </template>
          <div v-if="tabKey === ext.name">
            <a-descriptions bordered size="small" :column="1" style="margin-bottom: 16px;">
              <a-descriptions-item :label="$t('label.name')">{{ ext.name }}</a-descriptions-item>
              <a-descriptions-item :label="$t('label.state')">
                <status :text="nsps['ExternalNetwork'] ? nsps['ExternalNetwork'].state : $t('label.not.added')" />
              </a-descriptions-item>
              <a-descriptions-item v-if="nsps['ExternalNetwork']" :label="$t('label.servicelist')">
                {{ nsps['ExternalNetwork'] && nsps['ExternalNetwork'].servicelist ? nsps['ExternalNetwork'].servicelist.join(', ') : '-' }}
              </a-descriptions-item>
            </a-descriptions>
            <!-- External network devices for this extension -->
            <div v-if="nsps['ExternalNetwork'] && nsps['ExternalNetwork'].id">
              <a-divider>{{ $t('label.external.network.devices') }}</a-divider>
              <a-list
                :loading="deviceListLoading"
                :dataSource="extensionDevices[ext.name] || []"
                size="small"
                bordered>
                <template #renderItem="{ item }">
                  <a-list-item>
                    <a-list-item-meta>
                      <template #title>{{ item.host }}:{{ item.port }}</template>
                      <template #description v-if="item.details && Object.keys(item.details).length">
                        <span v-for="(v, k) in item.details" :key="k">{{ k }}={{ v }} &nbsp;</span>
                      </template>
                    </a-list-item-meta>
                    <template #actions>
                      <a-popconfirm
                        :title="$t('message.confirm.delete.external.network.device')"
                        @confirm="handleDeleteDevice(item)">
                        <a-button type="link" danger size="small">
                          <delete-outlined />
                        </a-button>
                      </a-popconfirm>
                    </template>
                  </a-list-item>
                </template>
                <template #footer>
                  <a-button type="dashed" size="small" @click="loadDevicesForExtension(ext.name)">
                    <template #icon><reload-outlined /></template>
                    {{ $t('label.refresh') }}
                  </a-button>
                </template>
              </a-list>
            </div>
          </div>
        </a-tab-pane>
      </a-tabs>
    </a-spin>

    <!-- Add External Network Device modal -->
    <a-modal
      :visible="showAddDeviceModal"
      :title="$t('label.add.external.network.device')"
      :maskClosable="false"
      :footer="null"
      @cancel="showAddDeviceModal = false">
      <a-spin :spinning="deviceFormLoading" v-ctrl-enter="handleAddDevice">
        <a-form
          :ref="deviceFormRef"
          :model="deviceForm"
          :rules="deviceRules"
          @finish="handleAddDevice"
          layout="vertical">
          <a-form-item name="host" ref="host" :label="$t('label.host')">
            <a-input v-model:value="deviceForm.host" v-focus="true" :placeholder="$t('label.host')" />
          </a-form-item>
          <a-form-item name="port" ref="port" :label="$t('label.port')">
            <a-input-number v-model:value="deviceForm.port" :placeholder="22" style="width: 100%" />
          </a-form-item>
          <a-divider>{{ $t('label.details') }}</a-divider>
          <div v-for="(detail, index) in deviceDetails" :key="index" style="display: flex; gap: 8px; margin-bottom: 8px;">
            <a-input v-model:value="detail.key" :placeholder="$t('label.name')" style="flex: 1" />
            <a-input-password
              v-if="detail.key === 'password' || detail.key === 'sshkey'"
              v-model:value="detail.value"
              :placeholder="$t('label.value')"
              style="flex: 2" />
            <a-input
              v-else
              v-model:value="detail.value"
              :placeholder="$t('label.value')"
              style="flex: 2" />
            <a-button type="link" danger @click="removeDeviceDetail(index)">
              <delete-outlined />
            </a-button>
          </div>
          <a-button type="dashed" style="width: 100%; margin-bottom: 12px;" @click="addDeviceDetail">
            <template #icon><plus-outlined /></template>
            {{ $t('label.add.detail') }}
          </a-button>
          <div :span="24" class="action-button">
            <a-button @click="showAddDeviceModal = false">{{ $t('label.cancel') }}</a-button>
            <a-button type="primary" ref="submitDevice" @click="handleAddDevice">{{ $t('label.ok') }}</a-button>
          </div>
        </a-form>
      </a-spin>
    </a-modal>

    <!-- Add External Network Provider modal: selects extension (services come from extension capabilities) -->
    <a-modal
      :visible="showAddExtNetProviderModal"
      :title="$t('label.add.external.network.provider')"
      :maskClosable="false"
      :footer="null"
      @cancel="showAddExtNetProviderModal = false">
      <a-spin :spinning="extensionProviderLoading">
        <a-form layout="vertical">
          <a-form-item :label="$t('label.extension')">
            <a-select
              v-model:value="extNetProviderForm.extensionId"
              showSearch
              optionFilterProp="label"
              :filterOption="(input, option) => option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0">
              <a-select-option
                v-for="ext in availableExtensions"
                :key="ext.id"
                :value="ext.id"
                :label="ext.name">
                {{ ext.name }} <span style="color: #aaa">({{ ext.state }})</span>
              </a-select-option>
            </a-select>
            <div v-if="availableExtensions.length === 0" style="color: #faad14; margin-top: 4px;">
              {{ $t('message.no.network.orchestrator.extensions') }}
            </div>
            <div v-else style="color: #8c8c8c; font-size: 12px; margin-top: 4px;">
              {{ $t('message.extension.services.from.capabilities') }}
            </div>
          </a-form-item>
          <div class="action-button">
            <a-button @click="showAddExtNetProviderModal = false">{{ $t('label.cancel') }}</a-button>
            <a-button type="primary" :disabled="!extNetProviderForm.extensionId" @click="handleAddExtNetProvider">{{ $t('label.ok') }}</a-button>
          </div>
        </a-form>
      </a-spin>
    </a-modal>

    <div v-if="showFormAction">
      <keep-alive v-if="currentAction.component">
        <a-modal
          :title="$t(currentAction.label)"
          :visible="showFormAction"
          :closable="true"
          :maskClosable="false"
          style="top: 20px;"
          @cancel="onCloseAction"
          :confirmLoading="actionLoading"
          :footer="null"
          centered>
          <keep-alive>
            <component
              :is="currentAction.component"
              :resource="nsp"
              :action="currentAction" />
          </keep-alive>
        </a-modal>
      </keep-alive>
      <a-modal
        v-else
        :title="$t(currentAction.label)"
        :visible="showFormAction"
        :confirmLoading="actionLoading"
        :closable="true"
        :maskClosable="false"
        :footer="null"
        @cancel="onCloseAction"
        style="top: 20px;"
        centered
      >
        <a-form
          :ref="formRef"
          :model="form"
          :rules="rules"
          @finish="handleSubmit"
          v-ctrl-enter="handleSubmit"
          layout="vertical"
         >
          <a-form-item
            :name="field.name"
            :ref="field.name"
            v-for="(field, index) in currentAction.fieldParams"
            :key="index"
            :label="$t('label.' + field.name)">
            <span v-if="field.name==='password'">
              <a-input-password
                v-focus="index===0"
                v-model:value="form[field.name]"
                :placeholder="field.description" />
            </span>
            <span v-else-if="field.type==='boolean'">
              <a-switch
                v-focus="index===0"
                v-model:checked="form[field.name]"
                :placeholder="field.description"
              />
            </span>
            <span v-else-if="field.type==='uuid'">
              <a-select
                v-focus="index===0"
                v-model:value="form[field.name]"
                :loading="field.loading"
                :placeholder="field.description"
                showSearch
                optionFilterProp="label"
                :filterOption="(input, option) => {
                  return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
                }" >
                <a-select-option
                  v-for="(opt, idx) in field.opts"
                  :key="idx"
                  :label="opt.name || opt.description">{{ opt.name || opt.description }}</a-select-option>
              </a-select>
            </span>
            <span v-else>
              <a-input
                v-focus="index===0"
                v-model:value="form[field.name]"
                :placeholder="field.description" />
            </span>
          </a-form-item>

          <div :span="24" class="action-button">
            <a-button @click="onCloseAction">{{ $t('label.cancel') }}</a-button>
            <a-button type="primary" ref="submit" @click="handleSubmit">{{ $t('label.ok') }}</a-button>
          </div>
        </a-form>
      </a-modal>
    </div>
  </div>
</template>

<script>
import { ref, reactive, toRaw, shallowRef, defineAsyncComponent } from 'vue'
import store from '@/store'
import { getAPI, postAPI } from '@/api'
import { mixinDevice } from '@/utils/mixin.js'
import Status from '@/components/widgets/Status'
import ProviderItem from '@/views/infra/network/providers/ProviderItem'

export default {
  name: 'ServiceProvidersTab',
  components: {
    Status,
    ProviderItem
  },
  mixins: [mixinDevice],
  props: {
    resource: {
      type: Object,
      required: true
    },
    loading: {
      type: Boolean,
      default: false
    }
  },
  data () {
    return {
      nsps: {},
      nsp: {},
      fetchLoading: false,
      actionLoading: false,
      showFormAction: false,
      currentAction: {},
      tabKey: 'BaremetalDhcpProvider',
      showAddDeviceModal: false,
      deviceFormLoading: false,
      deviceDetails: [],
      showAddExtNetProviderModal: false,
      extensionProviderLoading: false,
      availableExtensions: [],
      extNetProviderForm: {
        extensionId: null,
        services: ''
      },
      registeredExtensions: [],
      extensionDevices: {},
      deviceListLoading: false
    }
  },
  computed: {
    isExtensionTab () {
      return this.registeredExtensions.some(ext => ext.name === this.tabKey)
    },
    hardcodedNsps () {
      return [
        {
          title: 'BaremetalDhcpProvider',
          actions: [
            {
              api: 'addBaremetalDhcp',
              listView: true,
              icon: 'plus-outlined',
              label: 'label.add.baremetal.dhcp.device',
              args: ['url', 'username', 'password'],
              mapping: {
                dhcpservertype: {
                  value: (record) => { return 'DHCPD' }
                }
              }
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'stop-outlined',
              listView: true,
              label: 'label.disable.provider',
              confirm: 'message.confirm.disable.provider',
              show: (record) => { return (record && record.id && record.state === 'Enabled') },
              mapping: {
                state: {
                  value: (record) => { return 'Disabled' }
                }
              }
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'play-circle-outlined',
              listView: true,
              label: 'label.enable.provider',
              confirm: 'message.confirm.enable.provider',
              show: (record) => { return (record && record.id && record.state === 'Disabled') },
              mapping: {
                state: {
                  value: (record) => { return 'Enabled' }
                }
              }
            },
            {
              api: 'deleteNetworkServiceProvider',
              listView: true,
              icon: 'poweroff-outlined',
              label: 'label.shutdown.provider',
              confirm: 'message.confirm.delete.provider',
              show: (record) => { return record && record.id }
            }
          ],
          details: ['name', 'state', 'id', 'servicelist'],
          lists: [
            {
              title: 'label.baremetal.dhcp.devices',
              api: 'listBaremetalDhcp',
              mapping: {
                physicalnetworkid: {
                  value: (record) => { return record.physicalnetworkid }
                }
              },
              columns: ['url']
            }
          ]
        },
        {
          title: 'BaremetalPxeProvider',
          actions: [
            {
              api: 'addBaremetalPxeKickStartServer',
              listView: true,
              icon: 'plus-outlined',
              label: 'label.baremetal.pxe.device',
              args: ['url', 'username', 'password', 'tftpdir'],
              mapping: {
                pxeservertype: {
                  value: (record) => { return 'KICK_START' }
                }
              }
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'stop-outlined',
              listView: true,
              label: 'label.disable.provider',
              confirm: 'message.confirm.disable.provider',
              show: (record) => { return (record && record.id && record.state === 'Enabled') },
              mapping: {
                state: {
                  value: (record) => { return 'Disabled' }
                }
              }
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'play-circle-outlined',
              listView: true,
              label: 'label.enable.provider',
              confirm: 'message.confirm.enable.provider',
              show: (record) => { return (record && record.id && record.state === 'Disabled') },
              mapping: {
                state: {
                  value: (record) => { return 'Enabled' }
                }
              }
            },
            {
              api: 'deleteNetworkServiceProvider',
              listView: true,
              icon: 'poweroff-outlined',
              label: 'label.shutdown.provider',
              confirm: 'message.confirm.delete.provider',
              show: (record) => { return record && record.id }
            }
          ],
          details: ['name', 'state', 'id', 'servicelist'],
          lists: [
            {
              title: 'label.baremetal.pxe.devices',
              api: 'listBaremetalPxeServers',
              mapping: {
                physicalnetworkid: {
                  value: (record) => { return record.physicalnetworkid }
                }
              },
              columns: ['url']
            }
          ]
        },
        {
          title: 'BigSwitchBcf',
          actions: [
            {
              api: 'addBigSwitchBcfDevice',
              listView: true,
              icon: 'plus-outlined',
              label: 'label.add.bigswitchbcf.device',
              args: ['hostname', 'username', 'password', 'nat']
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'stop-outlined',
              listView: true,
              label: 'label.disable.provider',
              confirm: 'message.confirm.disable.provider',
              show: (record) => { return record && record.id && record.state === 'Enabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Disabled' }
                }
              }
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'play-circle-outlined',
              listView: true,
              label: 'label.enable.provider',
              confirm: 'message.confirm.enable.provider',
              show: (record) => { return record && record.id && record.state === 'Disabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Enabled' }
                }
              }
            },
            {
              api: 'deleteNetworkServiceProvider',
              listView: true,
              icon: 'poweroff-outlined',
              label: 'label.shutdown.provider',
              confirm: 'message.confirm.delete.provider',
              show: (record) => { return record && record.id }
            }
          ],
          details: ['name', 'state', 'id', 'servicelist'],
          lists: [
            {
              title: 'label.devices',
              api: 'listBigSwitchBcfDevices',
              mapping: {
                physicalnetworkid: {
                  value: (record) => { return record.physicalnetworkid }
                }
              },
              columns: ['hostname', 'actions']
            }
          ]
        },
        {
          title: 'BrocadeVcs',
          actions: [
            {
              api: 'addBrocadeVcsDevice',
              listView: true,
              icon: 'plus-outlined',
              label: 'label.add.brocadevcs.device',
              args: ['hostname', 'username', 'password']
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'stop-outlined',
              listView: true,
              label: 'label.disable.provider',
              confirm: 'message.confirm.disable.provider',
              show: (record) => { return record && record.id && record.state === 'Enabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Disabled' }
                }
              }
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'play-circle-outlined',
              listView: true,
              label: 'label.enable.provider',
              confirm: 'message.confirm.enable.provider',
              show: (record) => { return record && record.id && record.state === 'Disabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Enabled' }
                }
              }
            },
            {
              api: 'deleteNetworkServiceProvider',
              listView: true,
              icon: 'poweroff-outlined',
              label: 'label.shutdown.provider',
              confirm: 'message.confirm.delete.provider',
              show: (record) => { return record && record.id }
            }
          ],
          details: ['name', 'state', 'id', 'servicelist'],
          lists: [
            {
              title: 'label.devices',
              api: 'listBrocadeVcsDevices',
              mapping: {
                physicalnetworkid: {
                  value: (record) => { return record.physicalnetworkid }
                }
              },
              columns: ['hostname', 'actions']
            }
          ]
        },
        {
          title: 'CiscoVnmc',
          actions: [
            {
              api: 'addCiscoVnmcResource',
              listView: true,
              icon: 'plus-outlined',
              label: 'label.add.vnmc.device',
              args: ['hostname', 'username', 'password']
            },
            {
              api: 'addCiscoAsa1000vResource',
              listView: true,
              icon: 'plus-circle-outlined',
              label: 'label.add.ciscoasa1000v',
              args: ['hostname', 'insideportprofile', 'clusterid'],
              mapping: {
                zoneid: {
                  params: (record) => { return record.zoneid }
                }
              }
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'stop-outlined',
              listView: true,
              label: 'label.disable.provider',
              confirm: 'message.confirm.disable.provider',
              show: (record) => { return record && record.id && record.state === 'Enabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Disabled' }
                }
              }
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'play-circle-outlined',
              listView: true,
              label: 'label.enable.provider',
              confirm: 'message.confirm.enable.provider',
              show: (record) => { return record && record.id && record.state === 'Disabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Enabled' }
                }
              }
            },
            {
              api: 'deleteNetworkServiceProvider',
              listView: true,
              icon: 'poweroff-outlined',
              label: 'label.shutdown.provider',
              confirm: 'message.confirm.delete.provider',
              show: (record) => { return record && record.id }
            }
          ],
          details: ['name', 'state', 'id', 'servicelist'],
          lists: [
            {
              title: 'label.list.ciscovnmc',
              api: 'listCiscoVnmcResources',
              mapping: {
                physicalnetworkid: {
                  value: (record) => { return record.physicalnetworkid }
                }
              },
              columns: ['resource', 'provider']
            },
            {
              title: 'label.list.ciscoasa1000v',
              api: 'listCiscoAsa1000vResources',
              mapping: {
                physicalnetworkid: {
                  value: (record) => { return record.physicalnetworkid }
                }
              },
              columns: ['hostname', 'insideportprofile', 'actions']
            }
          ]
        },
        {
          title: 'ConfigDrive',
          actions: [
            {
              api: 'updateNetworkServiceProvider',
              icon: 'stop-outlined',
              listView: true,
              label: 'label.disable.provider',
              confirm: 'message.confirm.disable.provider',
              show: (record) => { return record && record.id && record.state === 'Enabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Disabled' }
                }
              }
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'play-circle-outlined',
              listView: true,
              label: 'label.enable.provider',
              confirm: 'message.confirm.enable.provider',
              show: (record) => { return record && record.id && record.state === 'Disabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Enabled' }
                }
              }
            }
          ],
          details: ['name', 'state', 'id', 'servicelist', 'physicalnetworkid']
        },
        {
          title: 'GloboDns',
          actions: [
            {
              api: 'addGloboDnsHost',
              listView: true,
              icon: 'plus-outlined',
              label: 'label.globo.dns.configuration',
              args: ['url', 'username', 'password']
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'stop-outlined',
              listView: true,
              label: 'label.disable.provider',
              confirm: 'message.confirm.disable.provider',
              show: (record) => { return record && record.id && record.state === 'Enabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Disabled' }
                }
              }
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'play-circle-outlined',
              listView: true,
              label: 'label.enable.provider',
              confirm: 'message.confirm.enable.provider',
              show: (record) => { return record && record.id && record.state === 'Disabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Enabled' }
                }
              }
            },
            {
              api: 'deleteNetworkServiceProvider',
              listView: true,
              icon: 'poweroff-outlined',
              label: 'label.shutdown.provider',
              confirm: 'message.confirm.delete.provider',
              show: (record) => { return record && record.id }
            }
          ],
          details: ['name', 'state', 'id', 'servicelist']
        },
        {
          title: 'InternalLbVm',
          actions: [
            {
              api: 'updateNetworkServiceProvider',
              icon: 'stop-outlined',
              listView: true,
              label: 'label.disable.provider',
              confirm: 'message.confirm.disable.provider',
              show: (record) => { return record && record.id && record.state === 'Enabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Disabled' }
                }
              }
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'play-circle-outlined',
              listView: true,
              label: 'label.enable.provider',
              confirm: 'message.confirm.enable.provider',
              show: (record) => { return record && record.id && record.state === 'Disabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Enabled' }
                }
              }
            }
          ],
          details: ['name', 'state', 'id', 'physicalnetworkid', 'destinationphysicalnetworkid', 'servicelist'],
          lists: [
            {
              title: 'label.instances',
              api: 'listInternalLoadBalancerVMs',
              mapping: {
                zoneid: {
                  value: (record) => { return record.zoneid }
                }
              },
              columns: ['name', 'zonename', 'type', 'state']
            }
          ]
        },
        {
          title: 'Netscaler',
          actions: [
            {
              api: 'addNetscalerLoadBalancer',
              icon: 'plus-outlined',
              listView: true,
              label: 'label.add.netscaler.device',
              component: shallowRef(defineAsyncComponent(() => import('@/views/infra/network/providers/AddNetscalerLoadBalancer.vue')))
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'stop-outlined',
              listView: true,
              label: 'label.disable.provider',
              confirm: 'message.confirm.disable.provider',
              show: (record) => { return record && record.id && record.state === 'Enabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Disabled' }
                }
              }
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'play-circle-outlined',
              listView: true,
              label: 'label.enable.provider',
              confirm: 'message.confirm.enable.provider',
              show: (record) => { return record && record.id && record.state === 'Disabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Enabled' }
                }
              }
            },
            {
              api: 'deleteNetworkServiceProvider',
              listView: true,
              icon: 'poweroff-outlined',
              label: 'label.shutdown.provider',
              confirm: 'message.confirm.delete.provider',
              show: (record) => { return record && record.id }
            }
          ],
          details: ['name', 'state', 'id', 'servicelist'],
          lists: [
            {
              title: 'label.devices',
              api: 'listNetscalerLoadBalancers',
              mapping: {
                physicalnetworkid: {
                  value: (record) => { return record.physicalnetworkid }
                }
              },
              columns: ['ipaddress', 'lbdevicestate', 'actions']
            }
          ]
        },
        {
          title: 'NiciraNvp',
          actions: [
            {
              api: 'addNiciraNvpDevice',
              icon: 'plus-outlined',
              listView: true,
              label: 'label.add.niciranvp.device',
              component: shallowRef(defineAsyncComponent(() => import('@/views/infra/network/providers/AddNiciraNvpDevice.vue')))
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'stop-outlined',
              listView: true,
              label: 'label.disable.provider',
              confirm: 'message.confirm.disable.provider',
              show: (record) => { return record && record.id && record.state === 'Enabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Disabled' }
                }
              }
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'play-circle-outlined',
              listView: true,
              label: 'label.enable.provider',
              confirm: 'message.confirm.enable.provider',
              show: (record) => { return record && record.id && record.state === 'Disabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Enabled' }
                }
              }
            },
            {
              api: 'deleteNetworkServiceProvider',
              listView: true,
              icon: 'poweroff-outlined',
              label: 'label.shutdown.provider',
              confirm: 'message.confirm.delete.provider',
              show: (record) => { return record && record.id }
            }
          ],
          details: ['name', 'state', 'id', 'servicelist'],
          lists: [
            {
              title: 'label.devices',
              api: 'listNiciraNvpDevices',
              mapping: {
                physicalnetworkid: {
                  value: (record) => { return record.physicalnetworkid }
                }
              },
              columns: ['hostname', 'transportzoneuuid', 'l3gatewayserviceuuid', 'l2gatewayserviceuuid', 'actions']
            }
          ]
        },
        {
          title: 'Opendaylight',
          actions: [
            {
              api: 'addOpenDaylightController',
              listView: true,
              icon: 'plus-outlined',
              label: 'label.add.opendaylight.device',
              args: ['url', 'username', 'password']
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'stop-outlined',
              listView: true,
              label: 'label.disable.provider',
              confirm: 'message.confirm.disable.provider',
              show: (record) => { return record && record.id && record.state === 'Enabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Disabled' }
                }
              }
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'play-circle-outlined',
              listView: true,
              label: 'label.enable.provider',
              confirm: 'message.confirm.enable.provider',
              show: (record) => { return record && record.id && record.state === 'Disabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Enabled' }
                }
              }
            },
            {
              api: 'deleteNetworkServiceProvider',
              listView: true,
              icon: 'poweroff-outlined',
              label: 'label.shutdown.provider',
              confirm: 'message.confirm.delete.provider',
              show: (record) => { return record && record.id }
            }
          ],
          details: ['name', 'state', 'id', 'servicelist'],
          lists: [
            {
              title: 'label.opendaylight.controllers',
              api: 'listOpenDaylightControllers',
              mapping: {
                physicalnetworkid: {
                  value: (record) => { return record.physicalnetworkid }
                }
              },
              columns: ['name', 'url', 'username', 'actions']
            }
          ]
        },
        {
          title: 'Ovs',
          details: ['name', 'state', 'id', 'servicelist'],
          lists: [
            {
              title: 'listOvsElements',
              api: 'listOvsElements',
              mapping: {
                nspid: {
                  value: (record) => { return record.id }
                }
              },
              columns: ['account', 'domain', 'enabled', 'project', 'actions']
            }
          ]
        },
        {
          title: 'PaloAlto',
          actions: [
            {
              api: 'addPaloAltoFirewall',
              listView: true,
              icon: 'plus-outlined',
              label: 'label.add.pa.device',
              component: shallowRef(defineAsyncComponent(() => import('@/views/infra/network/providers/AddPaloAltoFirewall.vue')))
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'stop-outlined',
              listView: true,
              label: 'label.disable.provider',
              confirm: 'message.confirm.disable.provider',
              show: (record) => { return (record && record.id && record.state === 'Enabled') },
              mapping: {
                state: {
                  value: (record) => { return 'Disabled' }
                }
              }
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'play-circle-outlined',
              listView: true,
              label: 'label.enable.provider',
              confirm: 'message.confirm.enable.provider',
              show: (record) => { return (record && record.id && record.state === 'Disabled') },
              mapping: {
                state: {
                  value: (record) => { return 'Enabled' }
                }
              }
            },
            {
              api: 'deleteNetworkServiceProvider',
              listView: true,
              icon: 'poweroff-outlined',
              label: 'label.shutdown.provider',
              confirm: 'message.confirm.delete.provider',
              show: (record) => { return record && record.id }
            }
          ],
          details: ['name', 'state', 'id', 'servicelist'],
          lists: [
            {
              title: 'label.devices',
              api: 'listPaloAltoFirewalls',
              mapping: {
                physicalnetworkid: {
                  value: (record) => { return record.physicalnetworkid }
                }
              },
              columns: ['ipaddress', 'fwdevicestate', 'type', 'actions']
            }
          ]
        },
        {
          title: 'SecurityGroupProvider',
          details: ['name', 'state', 'id', 'servicelist'],
          actions: [
            {
              api: 'updateNetworkServiceProvider',
              icon: 'stop-outlined',
              listView: true,
              label: 'label.disable.provider',
              confirm: 'message.confirm.disable.provider',
              show: (record) => { return record && record.id && record.state === 'Enabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Disabled' }
                }
              }
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'play-circle-outlined',
              listView: true,
              label: 'label.enable.provider',
              confirm: 'message.confirm.enable.provider',
              show: (record) => { return record && record.id && record.state === 'Disabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Enabled' }
                }
              }
            }
          ]
        },
        {
          title: 'VirtualRouter',
          actions: [
            {
              api: 'updateNetworkServiceProvider',
              icon: 'stop-outlined',
              listView: true,
              label: 'label.disable.provider',
              confirm: 'message.confirm.disable.provider',
              show: (record) => { return record && record.id && record.state === 'Enabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Disabled' }
                }
              }
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'play-circle-outlined',
              listView: true,
              label: 'label.enable.provider',
              confirm: 'message.confirm.enable.provider',
              show: (record) => { return record && record.id && record.state === 'Disabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Enabled' }
                }
              }
            }
          ],
          details: ['name', 'state', 'id', 'servicelist'],
          lists: [
            {
              title: 'label.instances',
              api: 'listRouters',
              mapping: {
                listAll: {
                  value: (record) => { return true }
                },
                zoneid: {
                  value: (record) => { return record.zoneid }
                },
                forvpc: {
                  value: (record) => { return false }
                }
              },
              columns: ['name', 'state', 'hostname', 'zonename']
            }
          ]
        },
        {
          title: 'VpcVirtualRouter',
          actions: [
            {
              api: 'updateNetworkServiceProvider',
              icon: 'stop-outlined',
              listView: true,
              label: 'label.disable.provider',
              confirm: 'message.confirm.disable.provider',
              show: (record) => { return record && record.id && record.state === 'Enabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Disabled' }
                }
              }
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'play-circle-outlined',
              listView: true,
              label: 'label.enable.provider',
              confirm: 'message.confirm.enable.provider',
              show: (record) => { return record && record.id && record.state === 'Disabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Enabled' }
                }
              }
            }
          ],
          details: ['name', 'state', 'id', 'servicelist'],
          lists: [
            {
              title: 'label.instances',
              api: 'listRouters',
              mapping: {
                forvpc: {
                  value: (record) => { return true }
                },
                zoneid: {
                  value: (record) => { return record.zoneid }
                },
                listAll: {
                  value: () => { return true }
                }
              },
              columns: ['name', 'state', 'hostname', 'zonename']
            }
          ]
        },
        {
          title: 'Tungsten',
          details: ['name', 'state', 'id', 'physicalnetworkid', 'servicelist'],
          lists: [
            {
              title: 'label.tungsten.fabric.provider',
              api: 'listTungstenFabricProviders',
              mapping: {
                zoneid: {
                  value: (record) => { return record.zoneid }
                }
              },
              columns: ['name', 'tungstenproviderhostname', 'tungstenproviderport', 'tungstengateway', 'tungstenprovidervrouterport', 'tungstenproviderintrospectport']
            }
          ]
        },
        {
          title: 'Nsx',
          details: ['name', 'state', 'id', 'physicalnetworkid', 'servicelist'],
          actions: [
            {
              api: 'updateNetworkServiceProvider',
              icon: 'stop-outlined',
              listView: true,
              label: 'label.disable.provider',
              confirm: 'message.confirm.disable.provider',
              // show: (record) => { return record && record.id && record.state === 'Enabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Disabled' }
                }
              }
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'play-circle-outlined',
              listView: true,
              label: 'label.enable.provider',
              confirm: 'message.confirm.enable.provider',
              // show: (record) => { return record && record.id && record.state === 'Disabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Enabled' }
                }
              }
            }
          ],
          lists: [
            {
              title: 'label.nsx.provider',
              api: 'listNsxControllers',
              mapping: {
                zoneid: {
                  value: (record) => { return record.zoneid }
                }
              },
              columns: ['name', 'hostname', 'port', 'tier0gateway', 'edgecluster', 'transportzone']
            }
          ]
        },
        {
          title: 'Netris',
          details: ['name', 'state', 'id', 'physicalnetworkid', 'servicelist'],
          actions: [
            {
              api: 'updateNetworkServiceProvider',
              icon: 'stop-outlined',
              listView: true,
              label: 'label.disable.provider',
              confirm: 'message.confirm.disable.provider',
              show: (record) => { return (record && record.id && record.state === 'Enabled') },
              mapping: {
                state: {
                  value: (record) => { return 'Disabled' }
                }
              }
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'play-circle-outlined',
              listView: true,
              label: 'label.enable.provider',
              confirm: 'message.confirm.enable.provider',
              show: (record) => { return (record && record.id && record.state === 'Disabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Enabled' }
                }
              }
            }
          ],
          lists: [
            {
              title: 'label.netris.provider',
              api: 'listNetrisProviders',
              mapping: {
                zoneid: {
                  value: (record) => { return record.zoneid }
                }
              },
              columns: ['name', 'netrisurl', 'site', 'tenantname', 'netristag']
            }
          ]
        },
        {
          title: 'ExternalNetwork',
          details: ['name', 'state', 'id', 'physicalnetworkid', 'servicelist'],
          actions: [
            {
              api: 'updateNetworkServiceProvider',
              icon: 'play-circle-outlined',
              listView: true,
              label: 'label.enable.provider',
              confirm: 'message.confirm.enable.provider',
              show: (record) => { return record && record.id && record.state === 'Disabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Enabled' }
                }
              }
            },
            {
              api: 'updateNetworkServiceProvider',
              icon: 'stop-outlined',
              listView: true,
              label: 'label.disable.provider',
              confirm: 'message.confirm.disable.provider',
              show: (record) => { return record && record.id && record.state === 'Enabled' },
              mapping: {
                state: {
                  value: (record) => { return 'Disabled' }
                }
              }
            },
            {
              api: 'deleteNetworkServiceProvider',
              listView: true,
              icon: 'poweroff-outlined',
              label: 'label.shutdown.provider',
              confirm: 'message.confirm.delete.provider',
              show: (record) => { return record && record.id }
            }
          ],
          lists: [
            {
              title: 'label.external.network.devices',
              api: 'listExternalNetworkDevices',
              mapping: {
                physicalnetworkid: {
                  value: (record) => { return record.physicalnetworkid }
                }
              },
              columns: ['host', 'port', 'details', 'actions']
            }
          ]
        }
      ]
    }
  },
  created () {
    this.initForm()
    this.fetchData()
  },
  watch: {
    loading (newData, oldData) {
      if (!newData && this.resource.id) {
        this.fetchData()
      }
    }
  },
  inject: ['parentPollActionCompletion'],
  provide () {
    return {
      provideSetNsp: this.setNsp,
      provideExecuteAction: this.executeAction,
      provideCloseAction: this.onCloseAction,
      provideReload: this.fetchData
    }
  },
  methods: {
    initForm () {
      this.formRef = ref()
      this.form = reactive({})
      this.rules = reactive({})
      this.deviceFormRef = ref()
      this.deviceForm = reactive({ host: '', port: 22 })
      this.deviceRules = reactive({
        host: [{ required: true, message: this.$t('label.required') }]
      })
    },
    handleAddExternalNetworkProvider () {
      // Open the extension picker modal — services come from extension capabilities
      this.extNetProviderForm = { extensionId: null, services: '' }
      this.extensionProviderLoading = true
      this.showAddExtNetProviderModal = true
      getAPI('listExtensions', { type: 'NetworkOrchestrator' }).then(json => {
        this.availableExtensions = (json.listextensionsresponse && json.listextensionsresponse.extension) || []
        if (this.availableExtensions.length > 0) {
          this.extNetProviderForm.extensionId = this.availableExtensions[0].id
        }
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.extensionProviderLoading = false
      })
    },
    _updateServicesFromExtension (extensionId) {
      if (!extensionId) {
        this.extNetProviderForm.services = ''
        return
      }
      const ext = this.availableExtensions.find(e => e.id === extensionId)
      if (ext && ext.details && ext.details['network.capabilities']) {
        try {
          const caps = JSON.parse(ext.details['network.capabilities'])
          if (caps && caps.services) {
            this.extNetProviderForm.services = caps.services.join(',')
          }
        } catch (e) {
          this.extNetProviderForm.services = ''
        }
      }
    },
    async handleAddExtNetProvider () {
      if (this.extensionProviderLoading) return
      const extensionId = this.extNetProviderForm.extensionId
      if (!extensionId) {
        this.$message.error(this.$t('message.select.extension'))
        return
      }
      // Get extension name for display, but NSP is always registered as 'ExternalNetwork'
      const ext = this.availableExtensions.find(e => e.id === extensionId)
      const extName = ext ? ext.name : 'ExternalNetwork'

      this.extensionProviderLoading = true
      try {
        // Step 1: registerExtension with physical network
        await postAPI('registerExtension', {
          extensionid: extensionId,
          resourceid: this.resource.id,
          resourcetype: 'PhysicalNetwork'
        })

        // Step 2: addNetworkServiceProvider — always use 'ExternalNetwork' as the provider name
        // The UI displays the extension name as the tab label, but the underlying
        // provider must be 'ExternalNetwork' so the NetworkElement.canHandle() works.
        const existingNsp = this.nsps['ExternalNetwork']
        if (!existingNsp) {
          const nspJson = await postAPI('addNetworkServiceProvider', {
            name: 'ExternalNetwork',
            physicalnetworkid: this.resource.id
          })
          const jobId = nspJson.addnetworkserviceproviderresponse.jobid
          if (jobId) {
            const result = await this.pollJob(jobId)
            if (result.jobstatus === 2) {
              this.$notifyError(result.jobresult.errortext)
              return
            }
          }
        }
        this.$message.success(this.$t('label.add.external.network.provider') + ': ' + extName)
        this.showAddExtNetProviderModal = false
        this.fetchData()
      } catch (error) {
        this.$notifyError(error)
      } finally {
        this.extensionProviderLoading = false
      }
    },
    handleOpenAddDeviceModal () {
      this.deviceDetails = [{ key: 'username', value: '' }]
      this.deviceForm = reactive({ host: '', port: 22 })
      this.showAddDeviceModal = true
    },
    addDeviceDetail () {
      this.deviceDetails.push({ key: '', value: '' })
    },
    removeDeviceDetail (index) {
      this.deviceDetails.splice(index, 1)
    },
    handleAddDevice () {
      if (this.deviceFormLoading) return
      this.deviceFormRef.value.validate().then(() => {
        this.deviceFormLoading = true
        const values = toRaw(this.deviceForm)
        const params = {
          physicalnetworkid: this.resource.id,
          host: values.host
        }
        if (values.port) {
          params.port = values.port
        }
        // Build details map from deviceDetails rows
        const details = {}
        this.deviceDetails.forEach((d, i) => {
          if (d.key && d.value !== undefined && d.value !== null) {
            details['details[' + i + '].key'] = d.key
            details['details[' + i + '].value'] = d.value
          }
        })
        Object.assign(params, details)
        postAPI('addExternalNetworkDevice', params).then(() => {
          this.$message.success(this.$t('label.add.external.network.device'))
          this.showAddDeviceModal = false
          // Reload the ExternalNetwork provider tab list
          this.fetchData()
        }).catch(error => {
          this.$notifyError(error)
        }).finally(() => {
          this.deviceFormLoading = false
        })
      }).catch(error => {
        if (error.errorFields && error.errorFields.length > 0) {
          this.deviceFormRef.value.scrollToField(error.errorFields[0].name)
        }
      })
    },
    fetchData () {
      if (!this.resource || !('id' in this.resource)) {
        return
      }
      this.fetchServiceProvider()
      this.fetchRegisteredExtensions()
    },
    fetchRegisteredExtensions () {
      // Load NetworkOrchestrator extensions registered to this physical network
      getAPI('listExtensions', {
        type: 'NetworkOrchestrator',
        resourceid: this.resource.id,
        resourcetype: 'PhysicalNetwork'
      }).then(json => {
        this.registeredExtensions = (json.listextensionsresponse && json.listextensionsresponse.extension) || []
        // Load NSP state for each extension tab and devices
        for (const ext of this.registeredExtensions) {
          this.fetchServiceProvider(ext.name)
          this.loadDevicesForExtension(ext.name)
        }
      }).catch(() => {
        this.registeredExtensions = []
      })
    },
    loadDevicesForExtension (extName) {
      if (!this.resource || !this.resource.id) return
      this.deviceListLoading = true
      getAPI('listExternalNetworkDevices', { physicalnetworkid: this.resource.id }).then(json => {
        const devices = (json.listexternalnetworkdevicesresponse && json.listexternalnetworkdevicesresponse.externalnetworkdevice) || []
        this.extensionDevices = { ...this.extensionDevices, [extName]: devices }
      }).catch(() => {
        this.extensionDevices = { ...this.extensionDevices, [extName]: [] }
      }).finally(() => {
        this.deviceListLoading = false
      })
    },
    handleEnableExtensionProvider () {
      const nsp = this.nsps['ExternalNetwork']
      if (!nsp || !nsp.id) return
      postAPI('updateNetworkServiceProvider', { id: nsp.id, state: 'Enabled' }).then(() => {
        this.$message.success(this.$t('label.enable.provider'))
        this.fetchData()
      }).catch(error => this.$notifyError(error))
    },
    handleDisableExtensionProvider () {
      const nsp = this.nsps['ExternalNetwork']
      if (!nsp || !nsp.id) return
      postAPI('updateNetworkServiceProvider', { id: nsp.id, state: 'Disabled' }).then(() => {
        this.$message.success(this.$t('label.disable.provider'))
        this.fetchData()
      }).catch(error => this.$notifyError(error))
    },
    handleDeleteDevice (device) {
      postAPI('deleteExternalNetworkDevice', { physicalnetworkid: this.resource.id }).then(() => {
        this.$message.success(this.$t('label.delete.external.network.device'))
        this.loadDevicesForExtension(this.tabKey)
      }).catch(error => this.$notifyError(error))
    },
    fetchServiceProvider (name) {
      this.fetchLoading = true
      getAPI('listNetworkServiceProviders', { physicalnetworkid: this.resource.id, name: name }).then(json => {
        const sps = json.listnetworkserviceprovidersresponse.networkserviceprovider || []
        if (sps.length > 0) {
          for (const sp of sps) {
            this.nsps[sp.name] = sp
          }
        }
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.fetchLoading = false
      })
    },
    onTabChange (tabKey) {
      this.tabKey = tabKey
    },
    setNsp (nsp) {
      this.nsp = nsp
    },
    handleSubmit () {
      if (this.currentAction.confirm) {
        this.executeConfirmAction()
        return
      }

      this.formRef.value.validate().then(async () => {
        const values = toRaw(this.form)
        const params = {}
        params.physicalnetworkid = this.nsp.physicalnetworkid
        for (const key in values) {
          const input = values[key]
          for (const param of this.currentAction.fieldParams) {
            if (param.name !== key) {
              continue
            }
            if (param.type === 'uuid') {
              params[key] = param.opts[input].id
            } else if (param.type === 'list') {
              params[key] = input.map(e => { return param.opts[e].id }).reduce((str, name) => { return str + ',' + name })
            } else {
              params[key] = input
            }
          }
        }
        if (this.currentAction.mapping) {
          for (const key in this.currentAction.mapping) {
            if (!this.currentAction.mapping[key].value) {
              continue
            }
            params[key] = this.currentAction.mapping[key].value(this.resource, params)
          }
        }
        this.actionLoading = true

        try {
          if (!this.nsp.id) {
            const serviceParams = {}
            serviceParams.name = this.nsp.name
            serviceParams.physicalnetworkid = this.nsp.physicalnetworkid
            const networkServiceProvider = await this.addNetworkServiceProvider(serviceParams)
            this.nsp = { ...this.nsp, ...networkServiceProvider }
          }
          params.id = this.nsp.id
          const hasJobId = await this.executeApi(this.currentAction.api, params, this.currentAction.method)
          if (!hasJobId) {
            await this.$message.success('Success')
            await this.fetchData()
          }
          this.actionLoading = false
          this.onCloseAction()
        } catch (error) {
          this.actionLoading = false
          this.$notification.error({
            message: this.$t('message.request.failed'),
            description: error
          })
        }
      }).catch(error => {
        this.formRef.value.scrollToField(error.errorFields[0].name)
      })
    },
    onCloseAction () {
      this.currentAction = {}
      this.showFormAction = false
    },
    addNetworkServiceProvider (args) {
      return new Promise((resolve, reject) => {
        let message = ''
        postAPI('addNetworkServiceProvider', args).then(async json => {
          const jobId = json.addnetworkserviceproviderresponse.jobid
          if (jobId) {
            const result = await this.pollJob(jobId)
            if (result.jobstatus === 2) {
              message = result.jobresult.errortext
              reject(message)
              return
            }
            resolve(result.jobresult.networkserviceprovider)
          }
        }).catch(error => {
          message = (error.response && error.response.headers && error.response.headers['x-description']) || error.message
          reject(message)
        })
      })
    },
    async pollJob (jobId) {
      return new Promise(resolve => {
        const asyncJobInterval = setInterval(() => {
          getAPI('queryAsyncJobResult', { jobId }).then(async json => {
            const result = json.queryasyncjobresultresponse
            if (result.jobstatus === 0) {
              return
            }

            clearInterval(asyncJobInterval)
            resolve(result)
          })
        }, 1000)
      })
    },
    executeAction (action) {
      this.currentAction = action
      if (this.currentAction.confirm) {
        this.$confirm({
          title: this.$t('label.confirmation'),
          content: this.$t(action.confirm),
          onOk: this.handleSubmit
        })
      } else {
        this.showFormAction = true
        if (!action.component) {
          const apiParams = store.getters.apis[action.api].params || []
          this.currentAction.fieldParams = action.args.map(arg => {
            const field = apiParams.filter(param => param.name === arg)[0]
            if (field.type === 'uuid') {
              this.listFieldOpts(field)
            }
            return field
          }) || []
          if (this.currentAction.api === 'addCiscoVnmcResource') {
            this.currentAction.method = 'POST'
          }
          this.setFormRules()
        }
      }
    },
    setFormRules () {
      this.form = reactive({})
      this.rules = reactive({})
      this.currentAction.fieldParams.forEach(field => {
        this.rules[field.name] = []
        const rule = {}
        rule.required = field.required
        if (field.type === 'uuid') {
          rule.message = this.$t('message.error.select')
        } else {
          rule.message = this.$t('message.error.required.input')
        }
        this.rules[field.name].push(rule)
      })
    },
    listFieldOpts (field) {
      const paramName = field.name
      const params = { listall: true }
      const possibleName = 'list' + paramName.replace('ids', '').replace('id', '').toLowerCase() + 's'
      let possibleApi
      for (const api in store.getters.apis) {
        if (api.toLowerCase().startsWith(possibleName)) {
          possibleApi = api
          break
        }
      }
      if (this.currentAction.mapping) {
        Object.keys(this.currentAction.mapping).forEach(key => {
          if (this.currentAction.mapping[key].params) {
            params[key] = this.currentAction.mapping[key].params(this.resource)
          }
        })
      }
      if (!possibleApi) {
        return
      }
      field.loading = true
      field.opts = []
      postAPI(possibleApi, params).then(json => {
        field.loading = false
        for (const obj in json) {
          if (obj.includes('response')) {
            for (const res in json[obj]) {
              if (res === 'count') {
                continue
              }
              field.opts = json[obj][res]
              break
            }
            break
          }
        }
      }).catch(error => {
        console.log(error.stack)
        field.loading = false
      })
    },
    async executeConfirmAction () {
      const params = {}
      params.id = this.nsp.id
      if (this.currentAction.mapping) {
        for (const key in this.currentAction.mapping) {
          if (!this.currentAction.mapping[key].value) {
            continue
          }
          params[key] = this.currentAction.mapping[key].value(this.resource, params)
        }
      }
      this.actionLoading = true

      try {
        const hasJobId = await this.executeApi(this.currentAction.api, params)
        if (!hasJobId) {
          await this.fetchData()
        }
        this.actionLoading = false
        this.onCloseAction()
      } catch (message) {
        this.actionLoading = false
        this.$notification.error({
          message: this.$t('message.request.failed'),
          description: message
        })
      }
    },
    executeApi (apiName, args, method) {
      return new Promise((resolve, reject) => {
        let hasJobId = false
        let message = ''
        const promise = postAPI(apiName, args)
        promise.then(json => {
          for (const obj in json) {
            if (obj.includes('response') || obj.includes(apiName)) {
              for (const res in json[obj]) {
                if (res === 'jobid') {
                  this.parentPollActionCompletion(json[obj][res], this.currentAction, this.$t(this.nsp.name))
                  hasJobId = true
                  break
                }
              }
              break
            }
          }

          resolve(hasJobId)
        }).catch(error => {
          message = (error.response && error.response.headers && error.response.headers['x-description']) || error.message
          reject(message)
        })
      })
    }
  }
}
</script>

<style scoped lang="less">
:deep(.ant-tabs) {
  &-left-bar {
    .ant-tabs-tab {
      display: flex;
      justify-content: flex-end;

      .ant-badge {
        margin-left: 10px;
      }
    }
  }

  &-tab {
    justify-content: end;
  }

  &-tab-btn {
    span {
      display: flex;
    }
  }
}
</style>
