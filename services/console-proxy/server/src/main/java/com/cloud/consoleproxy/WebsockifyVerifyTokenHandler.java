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
package com.cloud.consoleproxy;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import com.cloud.consoleproxy.util.Logger;

public class WebsockifyVerifyTokenHandler implements HttpHandler {
    private static final Logger s_logger = Logger.getLogger(WebsockifyVerifyTokenHandler.class);

    @Override
    public void handle(HttpExchange t) throws IOException {
        String response = "";
        try {
            response = doHandle(t);
        } catch (Exception e) {
            s_logger.error(e.toString(), e);
            response = "Unauthorized";
            t.sendResponseHeaders(401, response.length());
            OutputStream os = t.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }
        t.sendResponseHeaders(200, response.length());
        OutputStream os = t.getResponseBody();
        os.write(response.getBytes());
        os.close();
    }

    private String doHandle(HttpExchange t) throws Exception {
        String queries = t.getRequestURI().getQuery();
        if (queries.endsWith("/websockify")) {
            queries = queries.substring(0, queries.length() - 11);
        }
        Map<String, String> queryMap = ConsoleProxyHttpHandlerHelper.getQueryMap(queries);

        String host = queryMap.get("host");
        String portStr = queryMap.get("port");
        String sid = queryMap.get("sid");
        String tag = queryMap.get("tag");
        String ticket = queryMap.get("ticket");
        String ajaxSessionIdStr = queryMap.get("sess");
        String console_url = queryMap.get("consoleurl");
        String console_host_session = queryMap.get("sessionref");
        String vm_locale = queryMap.get("locale");
        String hypervHost = queryMap.get("hypervHost");
        String username = queryMap.get("username");
        String password = queryMap.get("password");
        String sourceIP = queryMap.get("sourceIP");

        if (tag == null)
            tag = "";

        int port;

        if (host == null || portStr == null || sid == null)
            throw new IllegalArgumentException();

        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            s_logger.warn("Invalid number parameter in query string: " + portStr);
            throw new IllegalArgumentException(e);
        }

        ConsoleProxyClientParam param = new ConsoleProxyClientParam();
        param.setClientHostAddress(host);
        param.setClientHostPort(port);
        param.setClientHostPassword(sid);
        param.setClientTag(tag);
        param.setTicket(ticket);
        param.setClientTunnelUrl(console_url);
        param.setClientTunnelSession(console_host_session);
        param.setLocale(vm_locale);
        param.setHypervHost(hypervHost);
        param.setUsername(username);
        param.setPassword(password);
        ConsoleProxy.authenticationExternally(param);

        if (console_url != null && ! console_url.isEmpty()) {
            return host + "," + port + "," + console_url + "," + console_host_session;
        }
        return host + ":" + port;
    }
}
