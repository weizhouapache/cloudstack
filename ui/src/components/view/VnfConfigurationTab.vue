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
    {{ $t('label.select.vnf.service') + ':' }}
    <a-select
      v-focus="true"
      style="width: 40%; margin-left: 15px;margin-bottom: 15px"
      :loading="fetchLoading"
      defaultActiveFirstOption
      v-model:value="vnfServiceName"
      @change="handleVnfServiceSelect"
      showSearch
      optionFilterProp="label"
      :filterOption="(input, option) => {
            return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
          }" >
      <a-select-option v-for="service in vnfServices" :key="service.name" :value="service.name" :label="service.name" style="width: 90%;">
        {{ service.name }}
      </a-select-option>
    </a-select>
  </div>
  <div>
    {{ $t('label.select.vnf.operation') + ':' }}
    <a-select
      v-focus="true"
      style="width: 40%; margin-left: 15px;margin-bottom: 15px"
      :loading="fetchLoading"
      defaultActiveFirstOption
      v-model:value="vnfOperationName"
      @change="handleVnfOperationSelect"
      showSearch
      optionFilterProp="label"
      :filterOption="(input, option) => {
            return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
          }" >
      <a-select-option v-for="operation in vnfOperations" :key="operation.name" :value="operation.name" :label="operation.description" style="width: 90%;">
        {{ operation.description }}
      </a-select-option>
    </a-select>
  </div>
  <a-alert type="info" :showIcon="true" v-if="this.vnfOperation && this.vnfOperation.requiredParameters && !this.vnfOperationResponse">
    <template #description>
      <div>
        <strong>{{ $t('label.vnf.operation.parameters') + " : " + this.vnfOperation.name }}</strong>
      </div>
      <br>
      <a-form layout="vertical" style="margin-top: 10px">
        <a-row
          v-for="param in vnfOperation.requiredParameters"
          :key="param.name"
          gutter="12"
          style="margin-bottom: 1rem; align-items: center;"
        >
          <a-col :span="12">
            {{ param.description }}
          </a-col>

          <a-col :span="6">
            <a-input
              v-if="param.type === 'string'"
              v-model:value="formData[param.name]"
              :placeholder="param.name"
            />
          </a-col>
        </a-row>
        <a-button ref="submit" type="primary" @click="handleSubmit">{{ $t('label.submit') }}</a-button>
      </a-form>
    </template>
  </a-alert>
  <a-alert v-if="vnfOperationResponseNode" type="success" :showIcon="true">
    <template #description>
      <component :is="vnfOperationResponseNode" />
    </template>
  </a-alert>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import Status from '@/components/widgets/Status'
import { h } from 'vue'
import yaml from 'js-yaml'

export default {
  name: 'VnfAppliancesTab',
  components: {
    Status
  },
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
      fetchLoading: false,
      vnfService: null,
      vnfServiceName: '',
      vnfServices: [],
      vnfOperation: null,
      vnfOperationName: '',
      vnfOperations: [],
      formData: {},
      vnfOperationResponse: '',
      vnfOperationResponseNode: null
    }
  },
  created () {
    this.fetchData()
  },
  watch: {
    resource: {
      deep: true,
      handler (newItem) {
        if (!newItem || !newItem.id) {
          return
        }
        this.fetchData()
      }
    }
  },
  methods: {
    fetchData () {
      var params = {
        virtualmachineid: this.resource.id
      }
      this.fetchLoading = true
      getAPI('vnfListProviders', params).then(json => {
        this.vnfServices = json.vnflistprovidersresponse.vnfprovider?.[0]?.service || []
        this.vnfServiceName = this.vnfServices?.[0]?.name || ''
        if (this.vnfServiceName) {
          this.handleVnfServiceSelect(this.vnfServiceName)
        }
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.fetchLoading = false
      })
    },
    resetVnfOperationRequestResponse () {
      this.formData = {}
      this.vnfOperationResponse = null
      this.vnfOperationResponseNode = null
    },
    handleVnfServiceSelect (service) {
      this.resetVnfOperationRequestResponse()
      this.vnfServiceName = service
      this.vnfService = this.vnfServices.find(vnfService => vnfService.name === service)
      this.vnfOperations = this.vnfService.operations
      this.vnfOperationName = this.vnfOperations?.[0]?.name || ''
      if (this.vnfOperationName) {
        this.handleVnfOperationSelect(this.vnfOperationName)
      }
    },
    handleVnfOperationSelect (operation) {
      this.resetVnfOperationRequestResponse()
      this.vnfOperationName = operation
      this.vnfOperation = this.vnfOperations.find(vnfOperation => vnfOperation.name === operation)
      if (['FIREWALL_RULE_LIST'].includes(this.vnfOperationName)) {
        this.vnfOperation.autoSubmit = true
      } else if (this.vnfOperationName === 'FIREWALL_RULE_CREATE') {
        this.vnfOperation.requiredParameters = JSON.parse('[{"name":"action","type":"string","description":"Action to perform: pass or block"},' +
          '{"name":"interface","type":"string","description":"Interface where the rule applies, e.g., lan, wan"},' +
          '{"name":"ipprotocol","type":"string","description":"IP protocol: inet (IPv4), inet6 (IPv6), or any"},' +
          '{"name":"protocol","type":"string","description":"Transport protocol: tcp, udp, icmp, or any"},' +
          '{"name":"source_net","type":"string","description":"Source network address or subnet"},' +
          '{"name":"source_port","type":"string","description":"Source port number or any"},' +
          '{"name":"destination_net","type":"string","description":"Destination network address or subnet"},' +
          '{"name":"destination_port","type":"string","description":"Destination port number or any"},' +
          '{"name":"descr","type":"string","description":"Description of the firewall rule"},' +
          '{"name":"vnf_rule_id","type":"string","description":"Unique identifier for the VNF firewall rule"}]')
        console.log('yaml 1 = ' + yaml.dump(this.vnfOperation.requiredParameters))
        this.vnfOperation.optionalParameters = yaml.load('firewall: {action: pass, interface: lan, protocol: tcp}')
        console.log('yaml 2 = ' + yaml.dump(this.vnfOperation.optionalParameters))
      }
      if (this.vnfOperation.autoSubmit) {
        this.handleSubmit()
      }
    },
    handleSubmit () {
      if (this.formData) {
        console.log('data = ' + JSON.stringify(this.formData))
      }
      var params = {
        service: this.vnfServiceName,
        operation: this.vnfOperationName,
        data: JSON.stringify(this.formData)
      }
      postAPI('performVnfAction', params).then(json => {
        const response = json.performvnfactionresponse.response || null
        if (response) {
          console.log('response = ' + JSON.stringify(response))
        }
      }).finally(() => {
        this.fetchLoading = false
        this.vnfOperationResponse = '{}'
        if (this.vnfOperationName === 'FIREWALL_RULE_LIST') {
          this.vnfOperationResponse = '{"result":[' +
            '{"uuid":"123e4567-e89b-12d3-a456-426614174000","id":1,"action":"pass","enabled":true,"interface":"lan","direction":"in","ipprotocol":"inet","protocol":"tcp","source_net":"192.168.1.0/24","source_port":"any","destination_net":"any","destination_port":443,"description":"Allow LAN → any on HTTPS"},' +
            '{"uuid":"123e4567-e89b-12d3-a456-426614174001","id":2,"action":"block","enabled":true,"interface":"lan","direction":"in","ipprotocol":"inet","protocol":"any","source_net":"any","source_port":"any","destination_net":"192.168.1.0/24","destination_port":"any","description":"Block any inbound to LAN subnet"},' +
            '{"uuid":"123e4567-e89b-12d3-a456-426614174002","id":3,"action":"pass","enabled":true,"interface":"wan","direction":"in","ipprotocol":"inet","protocol":"tcp","source_net":"any","source_port":"any","destination_net":"203.0.113.10","destination_port":22,"description":"Allow SSH access to firewall host"}]}'
        } else if (this.vnfOperationName === 'FIREWALL_RULE_CREATE') {
          this.vnfOperationResponse = '{"result":' +
            '{"uuid":"123e4567-e89b-12d3-a456-426614174002","id":3,"action":"pass","enabled":true,"interface":"wan","direction":"in","ipprotocol":"inet","protocol":"tcp","source_net":"any","source_port":"any","destination_net":"203.0.113.10","destination_port":22,"description":"Allow SSH access to firewall host"}}'
        }
        this.processVnfOperationResponse()
      })
    },
    processVnfOperationResponse () {
      const message = this.$t('label.vnf.operation.response') + ' : ' + this.vnfOperationName
      this.vnfOperationResponseNode = [h('p', `${message}`)]
      const parsedVnfOperationResponse = JSON.parse(this.vnfOperationResponse)?.result
      if (!parsedVnfOperationResponse) {
        this.vnfOperationResponseNode = h('div', this.vnfOperationResponseNode)
        return
      }
      if (!Array.isArray(parsedVnfOperationResponse) && typeof parsedVnfOperationResponse === 'object' && Object.keys(parsedVnfOperationResponse).length > 0) {
        this.vnfOperationResponseNode.push(
          h('div', {
            style: {
              marginTop: '1em',
              maxHeight: '50vh',
              maxWidth: '100%',
              overflow: 'auto',
              backgroundColor: '#f6f6f6',
              border: '1px solid #ddd',
              borderRadius: '4px',
              display: 'block'
            }
          }, [
            h('table', {
              style: {
                width: '100%',
                minWidth: 'max-content',
                borderCollapse: 'collapse',
                whiteSpace: 'pre-wrap'
              }
            }, [
              h('tbody',
                Object.keys(parsedVnfOperationResponse).map(key =>
                  h('tr', [
                    h('td', {
                      style: {
                        padding: '8px',
                        border: '1px solid #ddd',
                        textAlign: 'left',
                        fontWeight: 'bold',
                        backgroundColor: '#fafafa'
                      }
                    }, key),
                    h('td', {
                      style: {
                        padding: '8px',
                        border: '1px solid #ddd',
                        textAlign: 'left'
                      }
                    }, String(parsedVnfOperationResponse[key]))
                  ])
                )
              )
            ])
          ])
        )
      }

      if (Array.isArray(parsedVnfOperationResponse) && parsedVnfOperationResponse.length > 0) {
        this.vnfOperationResponseNode.push(
          h('div', {
            style: {
              marginTop: '1em',
              maxHeight: '50vh',
              maxWidth: '100%',
              overflow: 'auto',
              backgroundColor: '#f6f6f6',
              border: '1px solid #ddd',
              borderRadius: '4px',
              display: 'block'
            }
          }, [
            h('table', {
              style: {
                width: '100%',
                minWidth: 'max-content',
                borderCollapse: 'collapse',
                whiteSpace: 'pre-wrap'
              }
            }, [
              h('thead', [
                h('tr', Object.keys(parsedVnfOperationResponse[0]).map(key =>
                  h('th', {
                    style: {
                      padding: '8px',
                      border: '1px solid #ddd',
                      textAlign: 'left',
                      fontWeight: 'bold',
                      backgroundColor: '#fafafa'
                    }
                  }, key)
                ))
              ]),
              h('tbody', parsedVnfOperationResponse.map(row =>
                h('tr', Object.values(row).map(value =>
                  h('td', {
                    style: {
                      padding: '8px',
                      border: '1px solid #ddd',
                      fontFamily: 'monospace'
                    }
                  }, String(value))
                ))
              ))
            ])
          ])
        )
      }
      this.vnfOperationResponseNode = h('div', this.vnfOperationResponseNode)
    }
  }
}
</script>
<style lang="scss" scoped>
.status {
  margin-top: -5px;

  &--end {
    margin-left: 5px;
  }
}
</style>
