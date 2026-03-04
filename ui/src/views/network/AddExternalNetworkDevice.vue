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
  <div class="form-layout" @keyup.ctrl.enter="handleSubmit">
    <a-spin :spinning="loading">
      <a-form
        :ref="formRef"
        :model="form"
        :rules="rules"
        @finish="handleSubmit"
        layout="vertical">

        <a-form-item name="physicalnetworkid" ref="physicalnetworkid">
          <template #label>
            <tooltip-label :title="$t('label.physicalnetworkid')" :tooltip="$t('label.physicalnetworkid')"/>
          </template>
          <a-select
            v-model:value="form.physicalnetworkid"
            v-focus="true"
            showSearch
            optionFilterProp="label"
            :loading="physicalNetworkLoading"
            :filterOption="(input, option) => option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0">
            <a-select-option
              v-for="pn in physicalNetworks"
              :key="pn.id"
              :value="pn.id"
              :label="pn.name || pn.id">
              {{ pn.name || pn.id }} ({{ pn.zonename }})
            </a-select-option>
          </a-select>
        </a-form-item>

        <a-form-item name="host" ref="host">
          <template #label>
            <tooltip-label :title="$t('label.host')" :tooltip="$t('label.host')"/>
          </template>
          <a-input v-model:value="form.host" :placeholder="$t('label.host')" />
        </a-form-item>

        <a-form-item name="port" ref="port">
          <template #label>
            <tooltip-label :title="$t('label.port')" :tooltip="$t('label.port')"/>
          </template>
          <a-input-number v-model:value="form.port" :min="1" :max="65535" style="width: 100%" />
        </a-form-item>

        <a-divider>{{ $t('label.details') }}</a-divider>
        <div v-for="(detail, index) in details" :key="index" style="display: flex; gap: 8px; margin-bottom: 8px;">
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
          <a-button type="link" danger @click="removeDetail(index)">
            <delete-outlined />
          </a-button>
        </div>
        <a-button type="dashed" style="width: 100%; margin-bottom: 16px;" @click="addDetail">
          <template #icon><plus-outlined /></template>
          {{ $t('label.add.detail') }}
        </a-button>

        <div :span="24" class="action-button">
          <a-button @click="closeAction">{{ $t('label.cancel') }}</a-button>
          <a-button type="primary" ref="submit" @click="handleSubmit">{{ $t('label.ok') }}</a-button>
        </div>
      </a-form>
    </a-spin>
  </div>
</template>

<script>
import { ref, reactive, toRaw } from 'vue'
import { getAPI, postAPI } from '@/api'
import TooltipLabel from '@/components/widgets/TooltipLabel'

export default {
  name: 'AddExternalNetworkDevice',
  components: { TooltipLabel },
  props: {
    resource: {
      type: Object,
      default: () => ({})
    },
    action: {
      type: Object,
      default: () => ({})
    }
  },
  emits: ['refresh-data', 'close-action'],
  data () {
    return {
      loading: false,
      physicalNetworkLoading: false,
      physicalNetworks: [],
      details: [{ key: 'username', value: '' }]
    }
  },
  created () {
    this.formRef = ref()
    this.form = reactive({
      physicalnetworkid: null,
      host: '',
      port: 22
    })
    this.rules = reactive({
      physicalnetworkid: [{ required: true, message: this.$t('label.required') }],
      host: [{ required: true, message: this.$t('label.required') }]
    })
    this.fetchPhysicalNetworks()
  },
  methods: {
    fetchPhysicalNetworks () {
      this.physicalNetworkLoading = true
      getAPI('listPhysicalNetworks', {}).then(json => {
        this.physicalNetworks = (json.listphysicalnetworksresponse && json.listphysicalnetworksresponse.physicalnetwork) || []
        if (this.physicalNetworks.length > 0) {
          this.form.physicalnetworkid = this.physicalNetworks[0].id
        }
      }).catch(() => {
        this.physicalNetworks = []
      }).finally(() => {
        this.physicalNetworkLoading = false
      })
    },
    addDetail () {
      this.details.push({ key: '', value: '' })
    },
    removeDetail (index) {
      this.details.splice(index, 1)
    },
    handleSubmit () {
      if (this.loading) return
      this.formRef.value.validate().then(() => {
        this.loading = true
        const values = toRaw(this.form)
        const params = {
          physicalnetworkid: values.physicalnetworkid,
          host: values.host
        }
        if (values.port) params.port = values.port
        this.details.forEach((d, i) => {
          if (d.key && d.value !== undefined && d.value !== null) {
            params['details[' + i + '].key'] = d.key
            params['details[' + i + '].value'] = d.value
          }
        })
        postAPI('addExternalNetworkDevice', params).then(() => {
          this.$message.success(this.$t('label.add.external.network.device'))
          this.$emit('refresh-data')
          this.closeAction()
        }).catch(error => {
          this.$notifyError(error)
        }).finally(() => {
          this.loading = false
        })
      }).catch(error => {
        if (error.errorFields && error.errorFields.length > 0) {
          this.formRef.value.scrollToField(error.errorFields[0].name)
        }
      })
    },
    closeAction () {
      this.$emit('close-action')
    }
  }
}
</script>
