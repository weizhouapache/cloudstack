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

package org.apache.cloudstack.vnf;

import com.cloud.utils.exception.CloudRuntimeException;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.util.Base64;

public class VnfHttpConnector implements VnfConnector {
    private CloseableHttpClient httpClient;

    public VnfHttpConnector() {
        this.httpClient = HttpClients.createDefault();
    }

    @Override
    public String execute(VnfConfig config, VnfService.DataFormat dataFormat, String formattedData) {
        try {
            HttpPost httpPost = new HttpPost(config.getHttpEndpoint());

            // Add authentication
            if (config.getHttpToken() != null) {
                httpPost.setHeader("Authorization", "Bearer " + config.getHttpToken());
            } else if (config.getHttpUsername() != null && config.getHttpPassword() != null) {
                String auth = config.getHttpUsername() + ":" + config.getHttpPassword();
                String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());
                httpPost.setHeader("Authorization", "Basic " + encodedAuth);
            }

            // Set Content type
            switch (dataFormat) {
                case PLAINTEXT:
                    httpPost.setHeader("Content-Type", "text/plain");
                    break;
                case JSON:
                    httpPost.setHeader("Content-Type", "application/json");
                    break;
                case YAML:
                    httpPost.setHeader("Content-Type", "application/x-yaml");
                    break;
                case XML:
                    httpPost.setHeader("Content-Type", "application/xml");
                    break;
                default:
                    throw new CloudRuntimeException("Unsupported data format: " + dataFormat);
            }
            httpPost.setEntity(new StringEntity(formattedData));

            HttpResponse response = httpClient.execute(httpPost);
            String responseBody = EntityUtils.toString(response.getEntity());

            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode >= 200 && statusCode < 300) {
                return responseBody;
            } else {
                throw new CloudRuntimeException("HTTP request failed with status: " + statusCode + ", response: " + responseBody);
            }

        } catch (Exception e) {
            throw new CloudRuntimeException("HTTP execution failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        try {
            if (httpClient != null) {
                httpClient.close();
            }
        } catch (Exception e) {
            System.err.println("Warning: Failed to close HTTP client: " + e.getMessage());
        }
    }
}
