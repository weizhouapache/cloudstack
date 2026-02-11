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

package org.apache.cloudstack.backup.veeam;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.backup.Backup;
import org.apache.cloudstack.backup.BackupOffering;
import org.apache.cloudstack.backup.veeam.api.Job;
import org.apache.cloudstack.backup.veeam.api.Ref;
import org.apache.cloudstack.utils.security.SSLUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;

import com.cloud.hypervisor.Hypervisor;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.nio.TrustAllManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Veeam Backup & Replication REST API Client for Veeam 13+ (port 9419)
 * Uses OAuth2 authentication and JSON-based REST API
 * API Reference: https://helpcenter.veeam.com/references/vbr/13/rest/1.3-rev1/
 */
public class VeeamClientV2 extends VeeamClientBase {

    private static final String API_VERSION = "1.3-rev1";
    private static final String OAUTH_TOKEN_ENDPOINT = "/oauth2/token";

    private final URI apiURI;
    private final HttpClient httpClient;
    private final String username;
    private final String password;
    private final int timeout;

    private String bearerToken = null;
    private long tokenExpirationTime = 0;

    public VeeamClientV2(final String url, final Integer version, final String username, final String password,
                        final boolean validateCertificate, final int timeout,
                        final int restoreTimeout, final int taskPollInterval, final int taskPollMaxRetry)
                        throws URISyntaxException, NoSuchAlgorithmException, KeyManagementException {
        super(version != null && version != 0 ? version : 13);
        this.apiURI = new URI(url);
        this.username = username;
        this.password = password;
        this.timeout = timeout;

        final RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(timeout * 1000)
                .setConnectionRequestTimeout(timeout * 1000)
                .setSocketTimeout(timeout * 1000)
                .build();

        if (!validateCertificate) {
            final SSLContext sslcontext = SSLUtils.getSSLContext();
            sslcontext.init(null, new X509TrustManager[]{new TrustAllManager()}, new SecureRandom());
            final SSLConnectionSocketFactory factory = new SSLConnectionSocketFactory(sslcontext, NoopHostnameVerifier.INSTANCE);
            this.httpClient = HttpClientBuilder.create()
                    .setDefaultRequestConfig(config)
                    .setSSLSocketFactory(factory)
                    .build();
        } else {
            this.httpClient = HttpClientBuilder.create()
                    .setDefaultRequestConfig(config)
                    .build();
        }

        authenticate();
    }

    /**
     * OAuth2 authentication for Veeam 13+
     * https://helpcenter.veeam.com/references/vbr/13/rest/1.3-rev1/tag/Login/operation/CreateToken
     */
    private void authenticate() {
        logger.debug("Authenticating to Veeam 13+ using OAuth2");
        final HttpPost request = new HttpPost(apiURI.toString() + OAUTH_TOKEN_ENDPOINT);
        request.setHeader("Content-Type", "application/x-www-form-urlencoded");
        request.setHeader("x-api-version", API_VERSION);

        try {
            String body = String.format("grant_type=password&username=%s&password=%s", username, password);
            request.setEntity(new StringEntity(body));

            final HttpResponse response = httpClient.execute(request);

            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_UNAUTHORIZED) {
                throw new ServerApiException(ApiErrorCode.UNAUTHORIZED,
                    "Veeam 13+ OAuth authentication failed, please check credentials");
            }

            if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                throw new CloudRuntimeException("Failed to authenticate with Veeam 13+ OAuth API, status: " +
                        response.getStatusLine().getStatusCode());
            }

            ObjectMapper jsonMapper = new ObjectMapper();
            JsonNode jsonResponse = jsonMapper.readTree(response.getEntity().getContent());

            bearerToken = jsonResponse.get("access_token").asText();
            if (StringUtils.isEmpty(bearerToken)) {
                throw new CloudRuntimeException("Veeam OAuth access token is not available");
            }

            // Calculate token expiration time (expires_in is in seconds)
            int expiresIn = jsonResponse.has("expires_in") ? jsonResponse.get("expires_in").asInt() : 900;
            tokenExpirationTime = System.currentTimeMillis() + (expiresIn * 1000L) - 60000; // Subtract 1 minute for safety

            logger.debug("Successfully authenticated to Veeam 13+ via OAuth2, token expires in " + expiresIn + " seconds");
        } catch (final IOException e) {
            throw new CloudRuntimeException("Failed to authenticate Veeam 13+ OAuth API service due to: " + e.getMessage());
        }
    }

    /**
     * Refresh OAuth token if it's about to expire or has expired
     */
    private void refreshTokenIfNeeded() {
        if (System.currentTimeMillis() >= tokenExpirationTime) {
            logger.debug("OAuth token expired or about to expire, re-authenticating");
            authenticate();
        }
    }

    /**
     * Set authentication header for API requests
     */
    private void setAuthHeader(org.apache.http.HttpRequest request) {
        refreshTokenIfNeeded();
        request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
        request.setHeader("x-api-version", API_VERSION);
    }

    /**
     * Execute HTTP GET request
     */
    protected HttpResponse get(final String path) throws IOException {
        String url = apiURI.toString() + path;
        final HttpGet request = new HttpGet(url);
        setAuthHeader(request);
        final HttpResponse response = httpClient.execute(request);

        logger.debug(String.format("Response received in GET request is: [%s] for URL: [%s].",
            response.getStatusLine().getStatusCode(), url));
        return response;
    }

    /**
     * Execute HTTP POST request with JSON body
     */
    protected HttpResponse post(final String path, final String jsonBody) throws IOException {
        String url = apiURI.toString() + path;
        final HttpPost request = new HttpPost(url);
        setAuthHeader(request);
        request.setHeader("Content-Type", "application/json");

        if (StringUtils.isNotBlank(jsonBody)) {
            request.setEntity(new StringEntity(jsonBody));
        }

        final HttpResponse response = httpClient.execute(request);

        logger.debug(String.format("Response received in POST request for URL [%s]: status=[%s]",
            url, response.getStatusLine().getStatusCode()));
        return response;
    }

    /**
     * Execute HTTP DELETE request
     */
    protected HttpResponse delete(final String path) throws IOException {
        String url = apiURI.toString() + path;
        final HttpDelete request = new HttpDelete(url);
        setAuthHeader(request);
        final HttpResponse response = httpClient.execute(request);

        logger.debug(String.format("Response received in DELETE request is: [%s] for URL [%s].",
            response.getStatusLine().getStatusCode(), url));
        return response;
    }

    // ========================================================================
    // Abstract method implementations
    // ========================================================================

    @Override
    public List<BackupOffering> listJobs() {
        logger.debug("Listing backup jobs from Veeam 13+ API");
        try {
            final HttpResponse response = get("/v1/jobs");
            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(response.getEntity().getContent());
                JsonNode data = root.get("data");

                List<BackupOffering> jobs = new ArrayList<>();
                if (data != null && data.isArray()) {
                    for (JsonNode jobNode : data) {
                        String id = jobNode.get("id").asText();
                        String name = jobNode.get("name").asText();
                        jobs.add(new VeeamBackupOffering(name, id));
                    }
                }
                return jobs;
            }
        } catch (IOException e) {
            logger.error("Failed to list Veeam 13+ jobs due to:", e);
        }
        return new ArrayList<>();
    }

    @Override
    public Job listJob(String jobId) {
        logger.debug("Getting job details for: " + jobId);
        try {
            final HttpResponse response = get("/v1/jobs/" + jobId);
            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode jobNode = mapper.readTree(response.getEntity().getContent());

                // Convert JSON to Job object (simplified mapping)
                Job job = new Job();
                job.setUid("urn:veeam:Job:" + jobNode.get("id").asText());
                job.setName(jobNode.get("name").asText());

                if (jobNode.has("scheduleConfigured")) {
                    job.setScheduleConfigured(String.valueOf(jobNode.get("scheduleConfigured").asBoolean()));
                }
                if (jobNode.has("scheduleEnabled")) {
                    job.setScheduleEnabled(String.valueOf(jobNode.get("scheduleEnabled").asBoolean()));
                }

                return job;
            }
        } catch (IOException e) {
            logger.error("Failed to get job details due to:", e);
        }
        return null;
    }

    @Override
    public boolean toggleJobSchedule(String jobId) {
        logger.debug("Toggling schedule for job: " + jobId);
        try {
            // First get current state
            Job job = listJob(jobId);
            if (job == null) {
                logger.error("Job not found: " + jobId);
                return false;
            }

            boolean newState = !job.getScheduleEnabled();

            // Prepare JSON to update schedule
            ObjectMapper mapper = new ObjectMapper();
            com.fasterxml.jackson.databind.node.ObjectNode requestBody = mapper.createObjectNode();
            requestBody.put("scheduleEnabled", newState);

            String jsonBody = mapper.writeValueAsString(requestBody);
            final HttpResponse response = post("/v1/jobs/" + jobId, jsonBody);

            return response.getStatusLine().getStatusCode() == HttpStatus.SC_OK ||
                   response.getStatusLine().getStatusCode() == HttpStatus.SC_NO_CONTENT;
        } catch (IOException e) {
            logger.error("Failed to toggle job schedule due to:", e);
        }
        return false;
    }

    @Override
    public boolean startBackupJob(String jobId) {
        logger.debug("Starting backup job: " + jobId);
        try {
            final HttpResponse response = post("/v1/jobs/" + jobId + "/start", null);
            return response.getStatusLine().getStatusCode() == HttpStatus.SC_ACCEPTED ||
                   response.getStatusLine().getStatusCode() == HttpStatus.SC_OK;
        } catch (IOException e) {
            logger.error("Failed to start backup job due to:", e);
        }
        return false;
    }

    @Override
    public boolean cloneVeeamJob(Job parentJob, String clonedJobName) {
        logger.debug("Cloning job: " + parentJob.getName() + " to " + clonedJobName);
        try {
            // Veeam 13+ API: Clone job by creating a new job based on existing one
            // First get the parent job details
            final HttpResponse getResponse = get("/v1/jobs/" + parentJob.getId());
            if (getResponse.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                logger.error("Failed to get parent job details");
                return false;
            }

            ObjectMapper mapper = new ObjectMapper();
            JsonNode parentJobData = mapper.readTree(getResponse.getEntity().getContent());

            // Create a new job with modified name
            com.fasterxml.jackson.databind.node.ObjectNode newJobData = mapper.createObjectNode();
            newJobData.put("name", clonedJobName);
            newJobData.put("type", parentJobData.get("type").asText());

            // Copy essential settings from parent
            if (parentJobData.has("repositoryId")) {
                newJobData.put("repositoryId", parentJobData.get("repositoryId").asText());
            }
            if (parentJobData.has("description")) {
                newJobData.put("description", "Cloned from " + parentJob.getName());
            }

            String jsonBody = mapper.writeValueAsString(newJobData);
            final HttpResponse response = post("/v1/jobs", jsonBody);

            int statusCode = response.getStatusLine().getStatusCode();
            return statusCode == HttpStatus.SC_CREATED || statusCode == HttpStatus.SC_OK;
        } catch (IOException e) {
            logger.error("Failed to clone job due to:", e);
        }
        return false;
    }

    @Override
    public boolean addVMToVeeamJob(String jobId, String vmInstanceName, String hierarchyRef,
                                   Hypervisor.HypervisorType hypervisorType) {
        logger.debug("Adding VM " + vmInstanceName + " to job " + jobId);
        try {
            // Get managed servers/hierarchy to find VM
            String vmRef = findVMReference(vmInstanceName, hierarchyRef, hypervisorType);
            if (vmRef == null) {
                logger.error("Failed to find VM reference for: " + vmInstanceName);
                return false;
            }

            // Add VM to job includes
            ObjectMapper mapper = new ObjectMapper();
            com.fasterxml.jackson.databind.node.ObjectNode requestBody = mapper.createObjectNode();
            requestBody.put("objectRef", vmRef);
            requestBody.put("objectName", vmInstanceName);

            String jsonBody = mapper.writeValueAsString(requestBody);
            final HttpResponse response = post("/v1/jobs/" + jobId + "/includes", jsonBody);

            int statusCode = response.getStatusLine().getStatusCode();
            return statusCode == HttpStatus.SC_CREATED || statusCode == HttpStatus.SC_OK;
        } catch (IOException e) {
            logger.error("Failed to add VM to job due to:", e);
        }
        return false;
    }

    @Override
    public boolean removeVMFromVeeamJob(String jobId, String vmInstanceName, String hierarchyRef,
                                       Hypervisor.HypervisorType hypervisorType) {
        logger.debug("Removing VM " + vmInstanceName + " from job " + jobId);
        try {
            // List job includes to find the VM
            final HttpResponse getResponse = get("/v1/jobs/" + jobId + "/includes");
            if (getResponse.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                logger.error("Failed to list job includes");
                return false;
            }

            ObjectMapper mapper = new ObjectMapper();
            JsonNode includesData = mapper.readTree(getResponse.getEntity().getContent());
            JsonNode dataArray = includesData.get("data");

            if (dataArray == null || !dataArray.isArray()) {
                logger.warn("No includes found in job");
                return false;
            }

            // Find the include entry for this VM
            String includeId = null;
            for (JsonNode include : dataArray) {
                if (include.has("objectName") && include.get("objectName").asText().equals(vmInstanceName)) {
                    includeId = include.get("id").asText();
                    break;
                }
            }

            if (includeId == null) {
                logger.warn("VM " + vmInstanceName + " not found in job includes");
                return false;
            }

            // Remove the include
            final HttpResponse response = delete("/v1/jobs/" + jobId + "/includes/" + includeId);
            int statusCode = response.getStatusLine().getStatusCode();
            return statusCode == HttpStatus.SC_NO_CONTENT || statusCode == HttpStatus.SC_OK;
        } catch (IOException e) {
            logger.error("Failed to remove VM from job due to:", e);
        }
        return false;
    }

    @Override
    public boolean deleteJobAndBackup(String jobName) {
        logger.debug("Deleting job and backup: " + jobName);
        try {
            // Find job by name
            final HttpResponse listResponse = get("/v1/jobs?nameFilter=" + java.net.URLEncoder.encode(jobName, "UTF-8"));
            if (listResponse.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                logger.error("Failed to list jobs");
                return false;
            }

            ObjectMapper mapper = new ObjectMapper();
            JsonNode jobsData = mapper.readTree(listResponse.getEntity().getContent());
            JsonNode dataArray = jobsData.get("data");

            if (dataArray == null || !dataArray.isArray() || dataArray.size() == 0) {
                logger.warn("Job not found: " + jobName);
                return false;
            }

            String jobId = dataArray.get(0).get("id").asText();

            // Delete job (this also removes associated backups)
            final HttpResponse response = delete("/v1/jobs/" + jobId + "?deleteBackups=true");
            int statusCode = response.getStatusLine().getStatusCode();
            return statusCode == HttpStatus.SC_NO_CONTENT || statusCode == HttpStatus.SC_OK;
        } catch (IOException e) {
            logger.error("Failed to delete job due to:", e);
        }
        return false;
    }

    @Override
    public void listAllBackups() {
        logger.debug("Listing all backups from Veeam 13+ API");
        try {
            final HttpResponse response = get("/v1/backups");
            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(response.getEntity().getContent());
                logger.debug("Backups response: " + root.toString());
            }
        } catch (IOException e) {
            logger.error("Failed to list backups due to:", e);
        }
    }

    @Override
    public boolean deleteBackup(String restorePointId) {
        logger.debug("Deleting restore point: " + restorePointId);
        try {
            final HttpResponse response = delete("/v1/restorePoints/" + restorePointId);
            return response.getStatusLine().getStatusCode() == HttpStatus.SC_NO_CONTENT ||
                   response.getStatusLine().getStatusCode() == HttpStatus.SC_OK;
        } catch (IOException e) {
            logger.error("Failed to delete restore point due to:", e);
        }
        return false;
    }

    @Override
    public boolean syncBackupRepository() {
        logger.debug("Syncing backup repository");
        try {
            // Veeam 13+ API: Rescan repositories
            final HttpResponse response = post("/v1/backupInfrastructure/repositories/states/rescan", null);
            int statusCode = response.getStatusLine().getStatusCode();
            return statusCode == HttpStatus.SC_ACCEPTED || statusCode == HttpStatus.SC_OK;
        } catch (IOException e) {
            logger.error("Failed to sync repository due to:", e);
        }
        return false;
    }

    @Override
    public Map<String, Backup.Metric> getBackupMetrics() {
        logger.debug("Getting backup metrics");
        Map<String, Backup.Metric> metrics = new java.util.HashMap<>();

        try {
            final HttpResponse response = get("/v1/backups");
            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(response.getEntity().getContent());
                JsonNode dataArray = root.get("data");

                if (dataArray != null && dataArray.isArray()) {
                    for (JsonNode backup : dataArray) {
                        String backupId = backup.get("id").asText();
                        Long backupSize = backup.has("backupSize") ? backup.get("backupSize").asLong() : 0L;
                        Long dataSize = backup.has("dataSize") ? backup.get("dataSize").asLong() : backupSize;

                        metrics.put(backupId, new Backup.Metric(backupSize, dataSize));
                    }
                }
            }
        } catch (IOException e) {
            logger.error("Failed to get backup metrics due to:", e);
        }

        return metrics;
    }

    @Override
    public boolean restoreFullVM(String vmInstanceName, String restorePointId) {
        logger.debug("Restoring full VM: " + vmInstanceName + " from restore point: " + restorePointId);
        try {
            // Create restore session
            ObjectMapper mapper = new ObjectMapper();
            com.fasterxml.jackson.databind.node.ObjectNode requestBody = mapper.createObjectNode();
            requestBody.put("restorePointId", restorePointId);
            requestBody.put("restoreMode", "OriginalLocation");
            requestBody.put("powerOnAfterRestore", false);

            String jsonBody = mapper.writeValueAsString(requestBody);
            final HttpResponse response = post("/v1/restoreSessions", jsonBody);

            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_ACCEPTED ||
                response.getStatusLine().getStatusCode() == HttpStatus.SC_CREATED) {

                // Get session ID to monitor
                JsonNode sessionData = mapper.readTree(response.getEntity().getContent());
                String sessionId = sessionData.get("id").asText();

                // Wait for restore to complete
                return waitForRestoreCompletion(sessionId);
            }
        } catch (IOException e) {
            logger.error("Failed to restore full VM due to:", e);
        }
        return false;
    }

    @Override
    public Pair<Boolean, String> restoreVMToDifferentLocation(String restorePointId, String restoreLocation,
                                                              String hostIp, String dataStoreUuid) {
        logger.debug("Restoring VM to different location: " + restoreLocation);
        try {
            // Create restore session with different location
            ObjectMapper mapper = new ObjectMapper();
            com.fasterxml.jackson.databind.node.ObjectNode requestBody = mapper.createObjectNode();
            requestBody.put("restorePointId", restorePointId);
            requestBody.put("restoreMode", "DifferentLocation");

            if (restoreLocation != null) {
                requestBody.put("vmName", restoreLocation);
            }

            // Add destination details
            com.fasterxml.jackson.databind.node.ObjectNode destination = requestBody.putObject("destination");
            if (hostIp != null) {
                destination.put("hostReference", hostIp);
            }
            if (dataStoreUuid != null) {
                destination.put("datastoreReference", dataStoreUuid);
            }

            String jsonBody = mapper.writeValueAsString(requestBody);
            final HttpResponse response = post("/v1/restoreSessions", jsonBody);

            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_ACCEPTED ||
                response.getStatusLine().getStatusCode() == HttpStatus.SC_CREATED) {

                JsonNode sessionData = mapper.readTree(response.getEntity().getContent());
                String sessionId = sessionData.get("id").asText();

                boolean success = waitForRestoreCompletion(sessionId);
                String resultLocation = restoreLocation != null ? restoreLocation : "restored-" + restorePointId;
                return new Pair<>(success, resultLocation);
            }
        } catch (IOException e) {
            logger.error("Failed to restore VM to different location due to:", e);
        }
        return new Pair<>(false, "Restore failed");
    }

    @Override
    public List<Backup.RestorePoint> listRestorePoints(String backupName, String hierarchyRef,
                                                       String vmInternalName, Map<String, Backup.Metric> metricsMap,
                                                       Hypervisor.HypervisorType hypervisorType) {
        logger.debug("Listing restore points for VM: " + vmInternalName);
        List<Backup.RestorePoint> restorePoints = new ArrayList<>();

        try {
            // Get restore points filtered by VM name
            final HttpResponse response = get("/v1/restorePoints?vmNameFilter=" +
                java.net.URLEncoder.encode(vmInternalName, "UTF-8"));

            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(response.getEntity().getContent());
                JsonNode dataArray = root.get("data");

                if (dataArray != null && dataArray.isArray()) {
                    for (JsonNode rpNode : dataArray) {
                        String id = rpNode.get("id").asText();
                        String type = rpNode.has("type") ? rpNode.get("type").asText() : "Full";

                        // Parse creation date
                        java.util.Date created = null;
                        if (rpNode.has("creationTime")) {
                            String dateStr = rpNode.get("creationTime").asText();
                            try {
                                created = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").parse(dateStr);
                            } catch (java.text.ParseException e) {
                                logger.warn("Failed to parse date: " + dateStr);
                                created = new java.util.Date();
                            }
                        } else {
                            created = new java.util.Date();
                        }

                        // Get metrics if available
                        Long backupSize = null;
                        Long dataSize = null;
                        if (metricsMap != null && metricsMap.containsKey(id)) {
                            Backup.Metric metric = metricsMap.get(id);
                            backupSize = metric.getBackupSize();
                            dataSize = metric.getDataSize();
                        } else if (rpNode.has("backupSize")) {
                            backupSize = rpNode.get("backupSize").asLong();
                            dataSize = rpNode.has("dataSize") ? rpNode.get("dataSize").asLong() : backupSize;
                        }

                        restorePoints.add(new Backup.RestorePoint(id, created, type, backupSize, dataSize));
                    }
                }
            }
        } catch (IOException e) {
            logger.error("Failed to list restore points due to:", e);
        }

        return restorePoints;
    }

    @Override
    public Ref listBackupRepository(String backupServerId, String backupName) {
        logger.debug("Listing backup repository for server: " + backupServerId);
        try {
            final HttpResponse response = get("/v1/backupInfrastructure/repositories");
            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(response.getEntity().getContent());
                JsonNode dataArray = root.get("data");

                if (dataArray != null && dataArray.isArray()) {
                    for (JsonNode repo : dataArray) {
                        // Create a Ref object from repository data
                        Ref ref = new Ref();
                        ref.setName(repo.get("name").asText());
                        ref.setUid(repo.get("id").asText());
                        ref.setType("RepositoryReference");
                        return ref; // Return first matching repository
                    }
                }
            }
        } catch (IOException e) {
            logger.error("Failed to list backup repository due to:", e);
        }
        return null;
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    /**
     * Find VM reference in Veeam inventory
     */
    private String findVMReference(String vmName, String hierarchyRef, Hypervisor.HypervisorType hypervisorType) {
        logger.debug("Finding VM reference for: " + vmName);
        try {
            // Search for VM in inventory
            String searchPath = "/v1/inventory/vmware/vms";
            if (hypervisorType == Hypervisor.HypervisorType.KVM) {
                searchPath = "/v1/inventory/vms"; // Generic VM endpoint
            }

            final HttpResponse response = get(searchPath + "?nameFilter=" +
                java.net.URLEncoder.encode(vmName, "UTF-8"));

            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(response.getEntity().getContent());
                JsonNode dataArray = root.get("data");

                if (dataArray != null && dataArray.isArray() && dataArray.size() > 0) {
                    return dataArray.get(0).get("id").asText();
                }
            }
        } catch (IOException e) {
            logger.error("Failed to find VM reference due to:", e);
        }
        return null;
    }

    /**
     * Wait for restore session to complete
     */
    private boolean waitForRestoreCompletion(String sessionId) {
        logger.debug("Waiting for restore session to complete: " + sessionId);
        int maxRetries = 120; // 10 minutes with 5 second intervals
        int interval = 5000; // 5 seconds

        for (int i = 0; i < maxRetries; i++) {
            try {
                final HttpResponse response = get("/v1/restoreSessions/" + sessionId);
                if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                    ObjectMapper mapper = new ObjectMapper();
                    JsonNode sessionData = mapper.readTree(response.getEntity().getContent());

                    String state = sessionData.get("state").asText();
                    if ("Finished".equalsIgnoreCase(state) || "Success".equalsIgnoreCase(state)) {
                        logger.debug("Restore completed successfully");
                        return true;
                    } else if ("Failed".equalsIgnoreCase(state) || "Error".equalsIgnoreCase(state)) {
                        logger.error("Restore failed with state: " + state);
                        return false;
                    }

                    // Still in progress, wait and retry
                    Thread.sleep(interval);
                } else {
                    logger.warn("Failed to get restore session status, retrying...");
                    Thread.sleep(interval);
                }
            } catch (IOException | InterruptedException e) {
                logger.error("Error while waiting for restore completion: " + e.getMessage());
            }
        }

        logger.error("Restore session timed out");
        return false;
    }
}

