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
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.InputStream;

public class VnfSshConnector implements VnfConnector {
    private Session session;

    @Override
    public String execute(VnfConfig config, VnfService.DataFormat dataFormat, String formattedData) {
        try {
            JSch jsch = new JSch();

            // Setup private key if provided
            if (config.getSshPrivateKey() != null) {
                jsch.addIdentity("vnf-private-key", config.getSshPrivateKey().getBytes(), null, null);
            } else if (config.getSshPrivateKeyPath() != null) {
                jsch.addIdentity(config.getSshPrivateKeyPath());
            }

            session = jsch.getSession(config.getSshUsername(), config.getSshHost(), config.getSshPort());

            if (config.getSshPassword() != null) {
                session.setPassword(config.getSshPassword());
            }

            session.setConfig("StrictHostKeyChecking", "no");
            session.connect();

            ChannelExec channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(formattedData);

            InputStream in = channel.getInputStream();
            channel.connect();

            StringBuilder result = new StringBuilder();
            byte[] buffer = new byte[1024];
            while (true) {
                while (in.available() > 0) {
                    int bytesRead = in.read(buffer, 0, 1024);
                    if (bytesRead < 0) break;
                    result.append(new String(buffer, 0, bytesRead));
                }
                if (channel.isClosed()) break;
                Thread.sleep(100);
            }

            channel.disconnect();
            return result.toString();

        } catch (Exception e) {
            throw new CloudRuntimeException("SSH execution failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
    }
}