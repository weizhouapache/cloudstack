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
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

import com.cloud.vm.VirtualMachine;
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
import org.apache.http.client.methods.HttpPut;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;

import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.nio.TrustAllManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Veeam Backup & Replication REST API Client for Veeam 13+ (port 9419)
 * Uses OAuth2 authentication and JSON-based REST API
 * API Reference: https://helpcenter.veeam.com/references/vbr/13/rest/1.3-rev1/
 */
public class VeeamClientV2 extends VeeamClientBase {

    private static final String API_VERSION = "1.3-rev1";
    private static final String OAUTH_TOKEN_ENDPOINT = "/oauth2/token";
    private static final String TYPE_VIRTUAL_MACHINE = "VirtualMachine";
    private static final String TYPE_DATASTORE= "Datastore";
    private static final String TYPE_FOLDER= "Folder";
    private static final String PLATFORM_VSPHERE = "VSphere";
    private static final String PLATFORM_VMWARE = "VMware";
    private static final String PLATFORM_CUSTOM = "CustomPlatform";

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
        String fixedUrl = url;
        if (fixedUrl != null && fixedUrl.endsWith("/")) {
            fixedUrl = fixedUrl.substring(0, fixedUrl.length() - 1);
        }
        this.apiURI = new URI(fixedUrl);
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

    /**
    * Execute HTTP PUT request with JSON body
    */
    protected HttpResponse put(final String path, final String jsonBody) throws IOException {
        String url = apiURI.toString() + path;
        final HttpPut request = new HttpPut(url);
        setAuthHeader(request);
        request.setHeader("Content-Type", "application/json");

        if (StringUtils.isNotBlank(jsonBody)) {
            request.setEntity(new StringEntity(jsonBody));
        }

        final HttpResponse response = httpClient.execute(request);

        logger.debug(String.format("Response received in PUT request for URL [%s]: status=[%s]",
            url, response.getStatusLine().getStatusCode()));
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
        return true;
    }

    @Override
    public boolean startBackupJob(String jobId) {
        logger.debug("Starting backup job: " + jobId);
        try {
            final HttpResponse response = post("/v1/jobs/" + jobId + "/start", null);
            return response.getStatusLine().getStatusCode() == HttpStatus.SC_CREATED;
        } catch (IOException e) {
            logger.error("Failed to start backup job due to:", e);
        }
        return false;
    }

    @Override
    public BackupOffering cloneVeeamJob(Job parentJob, String clonedJobName) {
        logger.debug("Cloning job: {} to {}", parentJob.getName(), clonedJobName);
        try {
            // Veeam 13+ API: Clone job by creating a new job based on existing one
            // First get the parent job details
            final HttpResponse getResponse = get("/v1/jobs/" + parentJob.getId());
            if (getResponse.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                logger.error("Failed to get parent job details");
                return null;
            }

            final HttpResponse newResponse = post(String.format("/v1/jobs/%s/clone", parentJob.getId()), null);
            int statusCode = newResponse.getStatusLine().getStatusCode();
            if (statusCode != HttpStatus.SC_CREATED) {
                return null;
            }

            ObjectMapper mapper = new ObjectMapper();
            JsonNode newJobData = mapper.readTree(newResponse.getEntity().getContent());
            String newJobId = newJobData.get("id").asText();
            String newJobName = newJobData.get("name").asText();
            logger.debug("Created new job with ID: {} and name: {}", newJobId, newJobName);
            return new VeeamBackupOffering(newJobName, newJobId);
        } catch (IOException e) {
            logger.error("Failed to clone job due to:", e);
        }
        return null;
    }

    @Override
    public boolean addVMToVeeamJob(String jobId, String jobName, String parentJobId, String vmInstanceName, String hierarchyRef,
                                   VirtualMachine vm) {

        logger.debug("Adding VM {} to job {}", vmInstanceName, jobId);
        try {
            ObjectMapper mapper = new ObjectMapper();

            // Find VM reference in Veeam inventory
            Pair<String, String> vmReference = findVMReference(hierarchyRef, vmInstanceName);
            if (vmReference == null) {
                logger.error("Failed to find VM reference for {}", vmInstanceName);
                return false;
            }

            // Get parent job details to copy settings and find VM reference
            final HttpResponse getResponse = get("/v1/jobs/" + parentJobId);
            if (getResponse.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                logger.error("Failed to get parent job details: {}", parentJobId);
                return false;
            }
            JsonNode parentJobData = mapper.readTree(getResponse.getEntity().getContent());

            // Create a job to update the name and description of the cloned job
            ObjectNode updateJobData = mapper.createObjectNode();
            updateJobData.put("id", jobId);
            updateJobData.put("name", jobName);
            updateJobData.put("isDisabled", false);
            updateJobData.put("description", jobName);

            // Set to an array with a single InventoryObjectModel for the VM (from includes)
            ObjectNode virtualMachinesData = mapper.createObjectNode();
            ArrayNode includesArray = (ArrayNode) parentJobData.get("virtualMachines").get("includes");
            if (includesArray != null && includesArray.isArray() && !includesArray.isEmpty()) {
                // Use only the first include as the VM for the new job
                JsonNode firstInclude = includesArray.get(0);

                ObjectNode inventoryObject = mapper.createObjectNode();
                inventoryObject.put("name", vmInstanceName);
                inventoryObject.put("platform", firstInclude.get("platform").asText());
                inventoryObject.put("type", TYPE_VIRTUAL_MACHINE);
                inventoryObject.put("hostName", firstInclude.get("hostName").asText());
                String vmUrn = vmReference.second();
                inventoryObject.put("objectId", vmUrn.substring(vmUrn.lastIndexOf(":") + 1)); // Extract object ID from URN
                inventoryObject.put("urn", vmUrn);

                includesArray.removeAll();
                includesArray.add(inventoryObject);
            }
            virtualMachinesData.set("includes", includesArray);
            updateJobData.set("virtualMachines", virtualMachinesData);

            // Copy essential settings from parent
            if (parentJobData.has("repositoryId")) {
                updateJobData.put("repositoryId", parentJobData.get("repositoryId").asText());
            }

            // Copy other necessary fields from parentJobData if required by API (e.g. schedule, storage, etc.)
            // See https://helpcenter.veeam.com/references/vbr/13/rest/1.3-rev1/tag/Jobs#operation/UpdateJob
            String[] fieldsToCopy = new String[] {
                    "type", "guestProcessing", "isHighPriority", "schedule", "storage"
            };
            for (String field : fieldsToCopy) {
                if (parentJobData.has(field)) {
                    if ("guestProcessing".equals(field)) {
                        ObjectNode parentJobDataField = parentJobData.get(field).deepCopy();
                        parentJobDataField.remove("guestCredentials");
                        updateJobData.set(field, parentJobDataField);
                    } else {
                        updateJobData.set(field, parentJobData.get(field));
                    }
                }
            }

            String jsonBody = mapper.writeValueAsString(updateJobData);
            final HttpResponse updateResponse = put(String.format("/v1/jobs/%s", jobId), jsonBody);

            int statusCode = updateResponse.getStatusLine().getStatusCode();
            logResponseBody(updateResponse, jsonBody);
            return statusCode == HttpStatus.SC_OK;
        } catch (IOException e) {
            logger.error("Failed to clone job due to:", e);
        }
        return false;
    }

    @Override
    public boolean removeVMFromVeeamJob(String jobId, String vmInstanceName, String hierarchyRef) {
        logger.debug("Removing VM {} from job {}", vmInstanceName, jobId);
        try {
            // Find VM reference in Veeam inventory
            Pair<String, String> vmReference = findVMReference(hierarchyRef, vmInstanceName);
            if (vmReference == null) {
                logger.error("Failed to find VM reference for {}", vmInstanceName);
                return false;
            }

            // List job to find the include ID for the VM
            final HttpResponse getResponse = get("/v1/jobs/" + jobId);
            if (getResponse.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                logger.error("Failed to list job details for job ID: {}", jobId);
                return false;
            }

            ObjectMapper mapper = new ObjectMapper();
            JsonNode jobData = mapper.readTree(getResponse.getEntity().getContent());
            if (jobData.has("virtualMachines") && jobData.get("virtualMachines").has("includes")) {
                JsonNode includesData = jobData.get("virtualMachines").get("includes");
                if (includesData == null || !includesData.isArray() || includesData.isEmpty()) {
                    logger.warn("No includes data found in job");
                    return false;
                }
                JsonNode virtualMachineData = includesData.get(0);
                if (virtualMachineData == null) {
                    logger.warn("No virtual machine data found in job data");
                    return false;
                }
                if (!TYPE_VIRTUAL_MACHINE.equals(virtualMachineData.get("type").asText()) || !vmReference.equals(virtualMachineData.get("urn").asText())) {
                    logger.warn("VM reference in job does not match the VM to remove");
                    return false;
                }
            } else {
                logger.warn("No virtual machines found in job data");
                return false;
            }

            // Remove the VM from veeam job
            // Create a job to update the name and description of the cloned job
            ObjectNode updateJobData = mapper.createObjectNode();
            updateJobData.put("id", jobId);
            updateJobData.put("name", jobData.get("name").asText());
            updateJobData.put("isDisabled", false);
            updateJobData.put("description", jobData.get("description").asText());

            // Set to an array with a single InventoryObjectModel for the VM (from includes)
            ObjectNode virtualMachinesData = mapper.createObjectNode();
            ArrayNode includesArray = (ArrayNode) jobData.get("virtualMachines").get("includes");
            if (includesArray != null && includesArray.isArray() && !includesArray.isEmpty()) {
                for (int i = 0; i < includesArray.size(); i++) {
                    JsonNode include = includesArray.get(i);
                    if (TYPE_VIRTUAL_MACHINE.equals(include.get("type").asText()) && vmReference.equals(include.get("urn").asText())) {
                        includesArray.remove(i);
                        break;
                    }
                }
            }
            virtualMachinesData.set("includes", includesArray);
            updateJobData.set("virtualMachines", virtualMachinesData);

            // Copy essential settings from parent
            if (jobData.has("repositoryId")) {
                updateJobData.put("repositoryId", jobData.get("repositoryId").asText());
            }

            // Copy other necessary fields from parentJobData if required by API (e.g. schedule, storage, etc.)
            // See https://helpcenter.veeam.com/references/vbr/13/rest/1.3-rev1/tag/Jobs#operation/UpdateJob
            String[] fieldsToCopy = new String[] {
                    "type", "guestProcessing", "isHighPriority", "schedule", "storage"
            };
            for (String field : fieldsToCopy) {
                if (jobData.has(field)) {
                    if ("guestProcessing".equals(field)) {
                        ObjectNode jobDataField = jobData.get(field).deepCopy();
                        jobDataField.remove("guestCredentials");
                        updateJobData.set(field, jobDataField);
                    } else {
                        updateJobData.set(field, jobData.get(field));
                    }
                }
            }

            String jsonBody = mapper.writeValueAsString(updateJobData);
            final HttpResponse updateResponse = put(String.format("/v1/jobs/%s", jobId), jsonBody);
            int statusCode = updateResponse.getStatusLine().getStatusCode();
            return statusCode == HttpStatus.SC_OK;
        } catch (IOException e) {
            logger.error("Failed to remove VM from job due to:", e);
        }
        return false;
    }

    @Override
    public boolean deleteJobAndBackup(String jobName) {
        logger.debug("Deleting job and backup: {}", jobName);
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

            if (dataArray == null || !dataArray.isArray() || dataArray.isEmpty()) {
                logger.warn("Job not found: {}", jobName);
                return false;
            }

            String jobId = dataArray.get(0).get("id").asText();

            // Delete job (this also removes associated backups)
            final HttpResponse response = delete("/v1/jobs/" + jobId + "?deleteBackups=true");      // TODO: is deleteBackups needed ?
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
        Map<String, Backup.Metric> metrics = new HashMap<>();

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
            ObjectNode requestBody = mapper.createObjectNode();
            requestBody.put("restorePointId", restorePointId);
            requestBody.put("type", "OriginalLocation");
            requestBody.put("powerUp", false);
            requestBody.put("overwrite", true);

            String jsonBody = mapper.writeValueAsString(requestBody);
            final HttpResponse response = post("/v1/restore/vmRestore/" + PLATFORM_VSPHERE, jsonBody);

            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_CREATED) {
                // Get session ID to monitor
                JsonNode sessionData = mapper.readTree(response.getEntity().getContent());
                logger.debug("Restore session response: {}", sessionData.toString());
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
                                                              String hostIp, String dataStoreUuid, String hierarchyRef) {
        if (restoreLocation == null) {
            restoreLocation = RESTORE_VM_SUFFIX + UUID.randomUUID().toString();
        }
        logger.debug("Restoring VM from restore point {} to different location: {}", restorePointId, restoreLocation);
        try {
            // Create restore session with different location
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode requestBody = mapper.createObjectNode();
            requestBody.put("restorePointId", restorePointId);
            requestBody.put("type", "Customized");
            requestBody.put("powerUp", false);

            // Add datastore details
            Pair<String, String> datastoreRef = findDatastoreReference(hierarchyRef, dataStoreUuid);
            if (datastoreRef == null) {
                logger.error("Failed to find datastore reference for UUID: {}", dataStoreUuid);
                return new Pair<>(false, "Failed to find datastore reference");
            }
            ObjectNode datastoreObject = requestBody.putObject("datastore");
            datastoreObject.put("diskType", "Source");
            ObjectNode configurationFileDatastoreObject = datastoreObject.putObject("configurationFileDatastore");
            configurationFileDatastoreObject.put("platform", PLATFORM_VSPHERE);
            configurationFileDatastoreObject.put("type", TYPE_DATASTORE);
            configurationFileDatastoreObject.put("hostName", hierarchyRef);
            String datastoreUrn = datastoreRef.second();
            configurationFileDatastoreObject.put("objectId", datastoreUrn.substring(datastoreUrn.lastIndexOf(":") + 1)); // Extract object ID from URN
            configurationFileDatastoreObject.put("name", datastoreRef.first());
            configurationFileDatastoreObject.put("urn", datastoreUrn);

            // Add folder details
            Pair<String, String> folderRef = findFolderReference(hierarchyRef);
            if (folderRef == null) {
                logger.error("Failed to find folder reference for name: {}", restoreLocation);
                return new Pair<>(false, "Failed to find folder reference");
            }
            ObjectNode folderObject = requestBody.putObject("folder");
            folderObject.put("vmName", restoreLocation);
            folderObject.put("restoreVmTags", false);
            ObjectNode folderInFolderObject = folderObject.putObject("folder");
            folderInFolderObject.put("platform", PLATFORM_VSPHERE);   // TODO: determine platform dynamically if needed
            folderInFolderObject.put("type", TYPE_FOLDER);
            folderInFolderObject.put("hostName", hierarchyRef);
            String folderUrn = folderRef.second();
            folderInFolderObject.put("objectId", folderUrn.substring(folderUrn.lastIndexOf(":") + 1)); // Extract object ID from URN
            folderInFolderObject.put("name", folderRef.first());

            String jsonBody = mapper.writeValueAsString(requestBody);
            final HttpResponse response = post("/v1/restore/vmRestore/" + PLATFORM_VSPHERE, jsonBody);

            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_CREATED) {
                JsonNode sessionData = mapper.readTree(response.getEntity().getContent());
                logger.debug("Restore session response: {}", sessionData.toString());
                String sessionId = sessionData.get("id").asText();

                boolean success = waitForRestoreCompletion(sessionId);
                return new Pair<>(success, restoreLocation);
            }
            logResponseBody(response, jsonBody);
        } catch (IOException e) {
            logger.error("Failed to restore VM to different location due to:", e);
        }
        return new Pair<>(false, "Restore failed");
    }

    @Override
    public List<Backup.RestorePoint> listRestorePoints(String backupName, String hierarchyRef,
                                                       String vmInternalName, Map<String, Backup.Metric> metricsMap) {
        logger.debug("Listing restore points for VM: " + vmInternalName);
        List<Backup.RestorePoint> restorePoints = new ArrayList<>();

        try {
            // Get backup reference by backup name
            Pair<String, String> backupRef = findBackupReference(hierarchyRef, backupName);
            if (backupRef == null) {
                logger.error("Failed to find backup reference for backup name: {}", backupName);
                return restorePoints;
            }

            // Get restore points filtered by VM name
            final HttpResponse response = get(String.format("/v1/restorePoints?nameFilter=%s&platformIdFilter=%s", vmInternalName, backupRef.second()));

            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(response.getEntity().getContent());
                JsonNode dataArray = root.get("data");

                if (dataArray != null && dataArray.isArray() && !dataArray.isEmpty()) {
                    for (JsonNode rpNode : dataArray) {
                        String restorePointId = rpNode.get("id").asText();
                        String type = rpNode.has("type") ? rpNode.get("type").asText() : "Full";

                        // Parse creation date
                        Date created = null;
                        if (rpNode.has("creationTime")) {
                            String dateStr = rpNode.get("creationTime").asText();
                            try {
                                // the creationTime is like "2026-02-12T07:23:02.304046-08:00" which contains the timezone offset
                                OffsetDateTime dateTime = OffsetDateTime.parse(dateStr);
                                created = Date.from(dateTime.toInstant());
                            } catch (DateTimeParseException e) {
                                logger.warn("Failed to parse date: " + dateStr);
                                created = new Date();
                            }
                        } else {
                            created = new Date();
                        }

                        // Get metrics if available
                        Long backupSize = 0L;
                        Long dataSize = 0L;
                        if (metricsMap != null && metricsMap.containsKey(restorePointId)) {
                            Backup.Metric metric = metricsMap.get(restorePointId);
                            backupSize = metric.getBackupSize();
                            dataSize = metric.getDataSize();
                        } else if (rpNode.has("originalSize")) {
                            dataSize = rpNode.get("originalSize").asLong(); // TODO: double-check if this is the correct field for data size
                            backupSize = dataSize;
                        }

                        restorePoints.add(new Backup.RestorePoint(restorePointId, created, type, backupSize, dataSize));
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
     * Get inventory reference from Veeam based on hierarchy type and filter criteria
     */

    private JsonNode getInventoryFromHierarchy(String hierarchyRef, String hierarchyType, ObjectNode filterData) {
        logger.debug("Requesting inventory from {} for type: {}", hierarchyRef, hierarchyType);
        try {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode request = mapper.createObjectNode();
            if (StringUtils.isNotBlank(hierarchyType)) {
                request.put("hierarchyType", hierarchyType);
            }
            if (filterData != null) {
                request.set("filter", filterData);
            }

            String jsonBody = mapper.writeValueAsString(request);
            final HttpResponse response = post("/v1/inventory/" + hierarchyRef, jsonBody);

            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode == HttpStatus.SC_OK || statusCode == HttpStatus.SC_CREATED) {
                return mapper.readTree(response.getEntity().getContent());
            }
            logResponseBody(response, jsonBody);
        } catch (IOException e) {
            logger.error("Failed to get inventory due to:", e);
        }
        return null;
    }

    /**
     * Find VM reference in Veeam inventory
     * Returns a pair of VM name and URN if found, otherwise null
     */
    private Pair<String, String> findVMReference(String hierarchyRef, String vmName) {
        logger.debug("Finding VM reference for: {}", vmName);
        // Search for VM in inventory
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode filterData = mapper.createObjectNode();
        filterData.put("type", "PredicateExpression");
        filterData.put("property", "name");
        filterData.put("operation", "Equals");
        filterData.put("value", vmName);

        JsonNode inventoryData = getInventoryFromHierarchy(hierarchyRef, "VmsAndTemplates", filterData);

        if (inventoryData != null) {
            JsonNode dataArray = inventoryData.get("data");
            if (dataArray != null && dataArray.isArray() && !dataArray.isEmpty()) {
                JsonNode vmNode = dataArray.get(0);
                if (TYPE_VIRTUAL_MACHINE.equals(vmNode.get("type").asText()) && vmName.equals(vmNode.get("name").asText())) {
                    return new Pair<>(vmNode.get("name").asText(), vmNode.get("urn").asText());
                }
            }
        }
        return null;
    }



    /**
     * Find Datastore reference in Veeam inventory
     * Returns a pair of datastore name and URN if found, otherwise null
     */
    private Pair<String, String> findDatastoreReference(String hierarchyRef, String datastoreName) {
        if (datastoreName.contains("-")) {
            datastoreName = datastoreName.replace("-","");  // UUIDs without dashes
        }
        logger.debug("Finding datastore reference for: {}", datastoreName);
        // Search for VM in inventory
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode filterData = mapper.createObjectNode();
        filterData.put("type", "PredicateExpression");
        filterData.put("property", "name");
        filterData.put("operation", "Equals");
        filterData.put("value", datastoreName);

        JsonNode inventoryData = getInventoryFromHierarchy(hierarchyRef, "DatastoresAndVms", filterData);

        if (inventoryData != null) {
            JsonNode dataArray = inventoryData.get("data");
            if (dataArray != null && dataArray.isArray() && !dataArray.isEmpty()) {
                JsonNode dataNode = dataArray.get(0);
                if (TYPE_DATASTORE.equals(dataNode.get("type").asText()) && datastoreName.equals(dataNode.get("name").asText())) {
                    return new Pair<>(dataNode.get("name").asText(), dataNode.get("urn").asText());
                }
            }
        }
        return null;
    }

    /**
     * Find Folder reference in Veeam inventory
     * Returns a pair of folder name and URN if found, otherwise null
     */
    private Pair<String, String> findFolderReference(String hierarchyRef) {
        logger.debug("Finding folder reference from: {}", hierarchyRef);

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode filterData = mapper.createObjectNode();
        filterData.put("type", "PredicateExpression");
        filterData.put("property", "type");
        filterData.put("operation", "Equals");
        filterData.put("value", "Folder");

        JsonNode inventoryData = getInventoryFromHierarchy(hierarchyRef, "VmsAndTemplates", filterData);
        if (inventoryData != null) {
            JsonNode dataArray = inventoryData.get("data");
            for (JsonNode dataNode : dataArray) {
                if (TYPE_FOLDER.equals(dataNode.get("type").asText()) && !"vCLS".equals(dataNode.get("name").asText())) {
                    return new Pair<>(dataNode.get("name").asText(), dataNode.get("urn").asText());
                }
            }
        }
        return null;
    }

    /**
     * Find Backup reference in Veeam
     * Returns a pair of backup name and platform ID if found, otherwise null
     */
    private Pair<String, String> findBackupReference(String hierarchyRef, String backupName) {
        logger.debug("Finding backup reference for: {} from {}", backupName, hierarchyRef);
        try {
            final HttpResponse response = get("/v1/backups?nameFilter=" + backupName);
            if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(response.getEntity().getContent());
                JsonNode dataArray = root.get("data");

                if (dataArray != null && dataArray.isArray() && !dataArray.isEmpty()) {
                    JsonNode backupNode = dataArray.get(0);
                    if (backupName.equals(backupNode.get("name").asText())) {
                        return new Pair<>(backupNode.get("id").asText(), backupNode.get("platformId").asText());
                    }
                }
            }
        } catch (IOException e) {
            logger.error("Failed to find backup reference due to:", e);
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
                final HttpResponse response = get("/v1/sessions/" + sessionId);
                if (response.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                    ObjectMapper mapper = new ObjectMapper();
                    JsonNode sessionData = mapper.readTree(response.getEntity().getContent());

                    String state = sessionData.get("state").asText();
                    JsonNode result = sessionData.get("result");
                    logger.debug("Restore session state: " + state + ", result: " + (result != null ? result.asText() : "N/A"));
                    if ("Stopped".equalsIgnoreCase(state) && result != null) {
                        if ("Success".equals(result.get("result").asText())) {
                            logger.debug("Restore completed successfully");
                            return true;
                        } else {
                            logger.error("Restore failed with result: {}", result.get("result").asText());
                            return false;
                        }
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

    void logResponseBody(HttpResponse response, String jsonBody) {
        try {
            logger.debug("Request body: " + jsonBody);
            if (response.getEntity() != null) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode jsonResponse = mapper.readTree(response.getEntity().getContent());
                logger.debug("Response body: " + jsonResponse.toString());
            }
        } catch (IOException e) {
            logger.error("Failed to read response body due to:", e);
        }
    }
}

