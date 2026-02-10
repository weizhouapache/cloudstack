# Veeam Migration Guide
Complete guide for upgrading from Veeam 12 to Veeam 13+ and enabling KVM support.
---
## Table of Contents
1. [Upgrading from Veeam 12 to Veeam 13](#upgrading-from-veeam-12-to-veeam-13)
2. [Enabling KVM Support](#enabling-kvm-support)
3. [Rollback Procedures](#rollback-procedures)
4. [Troubleshooting](#troubleshooting)
---
## Upgrading from Veeam 12 to Veeam 13
### Prerequisites
- CloudStack 4.22.0.0 or later
- Existing Veeam 12 installation working with VMware VMs
- Veeam 13 upgrade package
- Backup of current configuration
- Maintenance window for upgrade
### Step-by-Step Upgrade
#### 1. Backup Current Configuration
```bash
# Save CloudStack Veeam configuration
cmk list configurations zoneid=1 | grep veeam > veeam-config-backup-$(date +%Y%m%d).txt
# Document current jobs and VMs
# Take screenshots of Veeam B&R console showing:
# - Active jobs
# - VM assignments
# - Backup repositories
# - Schedules
```
#### 2. Upgrade Veeam Server
Follow Veeam's official upgrade documentation:
1. **Download Veeam 13:**
   - Get the upgrade package from Veeam website
   - Review release notes and system requirements
2. **Run Veeam Upgrade:**
   - Stop all backup jobs
   - Run Veeam 13 installer
   - Choose "Upgrade" option
   - Follow wizard instructions
3. **Verify Veeam Upgrade:**
   - Open Veeam B&R console
   - Verify version in Help → About
   - Check all jobs are intact
   - Verify backup repositories are accessible
#### 3. Update CloudStack Configuration
```bash
# Update Veeam API URL (port changes from 9398 to 9419)
cmk update configuration zoneid=1 \
  name=backup.plugin.veeam.url \
  value=https://veeam-server:9419/api/
# Set version to 13
cmk update configuration zoneid=1 \
  name=backup.plugin.veeam.version \
  value=13
# Credentials remain the same (OAuth2 uses same username/password)
# No need to update username/password unless they changed
```
#### 4. Restart CloudStack Management Server
```bash
# Restart to apply new configuration
systemctl restart cloudstack-management
# Wait for CloudStack to fully start
sleep 30
# Verify CloudStack is running
systemctl status cloudstack-management
```
#### 5. Verify the Upgrade
```bash
# Check CloudStack logs for successful connection
tail -100 /var/log/cloudstack/management/management-server.log | grep -i veeam
# Look for:
# - "Creating VeeamClientV2 for Veeam version 13"
# - "Successfully authenticated to Veeam 13+ via OAuth2"
# - No authentication errors
```
**Test basic operations:**
1. List backup offerings: Should see existing jobs
2. Trigger a manual backup: Should complete successfully
3. List restore points: Should show existing backups
#### 6. Monitor Initial Operations
For the first 24-48 hours after upgrade:
```bash
# Monitor logs continuously
tail -f /var/log/cloudstack/management/management-server.log | grep -i veeam
# Watch for:
# - OAuth token refreshes (every 15 minutes)
# - Backup job execution
# - Any error messages
```
### What Changes After Upgrade
| Aspect | Veeam 12 | Veeam 13 |
|--------|----------|----------|
| **Port** | 9398 | 9419 |
| **Authentication** | Basic + Session | OAuth2 Bearer Token |
| **API Format** | XML | JSON |
| **Token Lifetime** | Session-based | 15 minutes (auto-refresh) |
| **Client Class** | VeeamClient | VeeamClientV2 |
| **Features** | Same | Same + KVM support |
### Expected Downtime
- **Veeam Upgrade:** 30-60 minutes
- **CloudStack Restart:** 2-5 minutes
- **Total:** Approximately 1 hour
**Note:** During this time:
- Scheduled backups will not run
- Manual backups cannot be triggered
- Restore operations are unavailable
- Existing backups remain safe
---
## Enabling KVM Support
KVM support is **only available with Veeam 13+**. Legacy Veeam versions do not support KVM.
### Prerequisites for KVM
1. **Veeam 13+ must be installed and working** (see upgrade guide above)
2. **CloudStack Management Server must be registered in Veeam** as a managed server
3. **Network connectivity** from Veeam to CloudStack Management Server
4. **CloudStack API accessible** from Veeam server
### Step 1: Register CloudStack in Veeam
Open Veeam Backup & Replication console:
1. Navigate to **Backup Infrastructure** → **Managed Servers**
2. Click **Add Server** → **Linux**
3. Enter CloudStack Management Server details:
   - **DNS Name or IP:** Your CloudStack mgmt server IP
   - **Description:** CloudStack Management Server
4. Configure credentials:
   - SSH credentials with root or sudo access
   - Or use Veeam agent credentials
5. Complete the wizard and verify connection
### Step 2: Configure CloudStack
```bash
# Set KVM hierarchy reference to CloudStack Management Server IP
cmk update configuration zoneid=1 \
  name=backup.plugin.veeam.kvm.hierarchy.ref \
  value=192.168.1.100
# Replace 192.168.1.100 with your actual CloudStack Management Server IP
```
**Important:** Use the exact IP/hostname that was registered in Veeam in Step 1.
### Step 3: Verify KVM Support
```bash
# Restart CloudStack to apply changes
systemctl restart cloudstack-management
# Check logs
tail -f /var/log/cloudstack/management/management-server.log | grep -i veeam
# Verify configuration
cmk list configurations zoneid=1 name=backup.plugin.veeam.kvm.hierarchy.ref
```
### Step 4: Test KVM Backup
1. **Assign a KVM VM to a backup offering:**
   ```bash
   # Via CloudStack UI:
   # Infrastructure → Virtual Machines → Select KVM VM
   # → Backup → Select backup offering
   ```
2. **Trigger a manual backup:**
   ```bash
   # CloudStack UI → VM → Backup → Backup Now
   ```
3. **Verify in Veeam console:**
   - Check job was created
   - Verify VM was added to job
   - Confirm backup completed
### KVM Architecture
```
Veeam B&R Server
        ↓
   (REST API)
        ↓
CloudStack Management Server (192.168.1.100)
        ↓
   (Queries VM info)
        ↓
    KVM Hosts
        ↓
   KVM VMs (backed up)
```
**Key Points:**
- Veeam connects to CloudStack Management Server, not individual KVM hosts
- CloudStack Management Server provides VM metadata to Veeam
- Similar architecture to Veeam + oVirt integration
- Backup data flows directly from KVM hosts to Veeam repositories
---
## Rollback Procedures
### Rollback from Veeam 13 to Veeam 12
**Warning:** Rollback may cause data loss. Only perform if absolutely necessary.
#### 1. Before Rollback
```bash
# Document current state
cmk list configurations zoneid=1 | grep veeam > pre-rollback-config.txt
# List all VMs with backups
# Document all backup jobs in Veeam console
```
#### 2. Rollback Veeam Server
Follow Veeam's rollback procedures:
- Uninstall Veeam 13
- Reinstall Veeam 12 from backup
- Restore Veeam database if needed
- Verify all jobs and backups are intact
#### 3. Update CloudStack Configuration
```bash
# Change URL back to port 9398
cmk update configuration zoneid=1 \
  name=backup.plugin.veeam.url \
  value=https://veeam-server:9398/api/
# Set version back to 12
cmk update configuration zoneid=1 \
  name=backup.plugin.veeam.version \
  value=12
# Remove KVM hierarchy reference (not supported in Veeam 12)
cmk update configuration zoneid=1 \
  name=backup.plugin.veeam.kvm.hierarchy.ref \
  value=""
```
#### 4. Restart and Verify
```bash
# Restart CloudStack
systemctl restart cloudstack-management
# Verify connection
tail -f /var/log/cloudstack/management/management-server.log | grep -i veeam
# Should see: "Creating legacy VeeamClient for Veeam version 12"
```
#### 5. Handle KVM VMs
**Important:** KVM VMs assigned to backup offerings will fail after rollback.
Options:
1. **Remove KVM VMs from backup offerings** (recommended)
2. **Keep KVM VMs** but they will show backup errors until Veeam 13 is reinstalled
### Disabling KVM Support
If you want to keep Veeam 13 but disable KVM temporarily:
```bash
# Remove KVM hierarchy reference
cmk update configuration zoneid=1 \
  name=backup.plugin.veeam.kvm.hierarchy.ref \
  value=""
# Restart CloudStack
systemctl restart cloudstack-management
```
---
## Troubleshooting
### Migration Issues
#### Issue: CloudStack still using old port after upgrade
**Symptoms:**
- Connection errors to port 9398
- Authentication failures
**Solution:**
```bash
# Verify configuration was updated
cmk list configurations zoneid=1 name=backup.plugin.veeam.url
# If still showing 9398, update again
cmk update configuration zoneid=1 \
  name=backup.plugin.veeam.url \
  value=https://veeam-server:9419/api/
# Restart CloudStack
systemctl restart cloudstack-management
```
#### Issue: OAuth authentication failing after upgrade
**Symptoms:**
- HTTP 401 errors
- "OAuth authentication failed" messages
**Solutions:**
1. **Verify Veeam 13 is fully installed:**
   ```bash
   # Test API endpoint
   curl -k https://veeam-server:9419/api/
   ```
2. **Check credentials:**
   ```bash
   cmk list configurations zoneid=1 | grep veeam.username
   cmk list configurations zoneid=1 | grep veeam.password
   ```
3. **Verify user has admin rights in Veeam**
4. **Check CloudStack logs for detailed error:**
   ```bash
   grep -i "oauth\|veeam" /var/log/cloudstack/management/management-server.log | tail -50
   ```
#### Issue: Existing backup jobs not visible
**Symptoms:**
- List backup offerings shows no jobs
- VMs show "no backup offering assigned"
**Solutions:**
1. **Check Veeam console** - verify jobs exist
2. **Verify network connectivity:**
   ```bash
   telnet veeam-server 9419
   ```
3. **Check API version setting:**
   ```bash
   cmk list configurations zoneid=1 name=backup.plugin.veeam.version
   ```
4. **Enable debug logging and check:**
   ```bash
   vi /etc/cloudstack/management/log4j-cloud.xml
   # Add: <logger name="org.apache.cloudstack.backup.veeam" level="DEBUG"/>
   systemctl restart cloudstack-management
   ```
### KVM-Specific Issues
#### Issue: KVM VMs not found in Veeam inventory
**Symptoms:**
- "Failed to find VM reference" errors
- VM not added to backup job
**Solutions:**
1. **Verify hierarchy reference is set:**
   ```bash
   cmk list configurations zoneid=1 name=backup.plugin.veeam.kvm.hierarchy.ref
   ```
2. **Check CloudStack mgmt server is registered in Veeam:**
   - Open Veeam console
   - Backup Infrastructure → Managed Servers
   - Look for CloudStack server entry
3. **Test connectivity from Veeam to CloudStack:**
   ```bash
   # From Veeam server:
   curl -k https://cloudstack-mgmt-server:8080/client/api
   ```
4. **Verify VM name matches:**
   - VM name in CloudStack must match what Veeam sees
   - Check for special characters or spaces
#### Issue: KVM backup job fails
**Symptoms:**
- Job starts but fails immediately
- "Access denied" or "Cannot connect" errors
**Solutions:**
1. **Check Veeam agent on CloudStack mgmt server:**
   ```bash
   # On CloudStack management server:
   systemctl status veeamservice
   ```
2. **Verify firewall rules:**
   - CloudStack API port (8080/443) accessible from Veeam
   - SSH port (22) accessible from Veeam
3. **Check Veeam job logs:**
   - Open Veeam console
   - View job details and session logs
   - Look for specific error messages
### Performance Issues
#### Issue: Token refresh causing delays
**Symptoms:**
- Operations slow every 15 minutes
- Timeout errors during token refresh
**Solution:**
- This is expected behavior (15-minute token lifetime)
- Increase API timeout if needed:
  ```bash
  cmk update configuration zoneid=1 \
    name=backup.plugin.veeam.request.timeout \
    value=600
  ```
#### Issue: Restore operations timing out
**Symptoms:**
- Restore starts but times out
- "Task poll max retry exceeded" errors
**Solution:**
```bash
# Increase poll retries (default: 120 = 10 minutes)
cmk update configuration zoneid=1 \
  name=backup.plugin.veeam.task.poll.max.retry \
  value=240
# This extends timeout to 20 minutes (240 × 5 seconds)
```
---
## Post-Migration Checklist
### Immediate (Day 1)
- [ ] Verify all backup offerings are visible
- [ ] Test manual backup on VMware VM
- [ ] Test manual backup on KVM VM (if applicable)
- [ ] Verify scheduled backups run successfully
- [ ] Check OAuth token refresh works (wait 15 minutes)
- [ ] Test listing restore points
- [ ] Review CloudStack logs for errors
### Short-term (Week 1)
- [ ] Monitor all scheduled backup jobs
- [ ] Verify backup repositories are not filling up unexpectedly
- [ ] Test full VM restore (in test environment)
- [ ] Test restore to different location
- [ ] Validate backup metrics are updating
- [ ] Check performance impact (if any)
### Long-term (Month 1)
- [ ] Review backup success rate
- [ ] Optimize backup schedules if needed
- [ ] Document any new procedures
- [ ] Train operations team on new features
- [ ] Plan for future KVM VM onboarding
---
## Getting Help
If you encounter issues during migration:
1. **Check this guide** for common issues and solutions
2. **Review CloudStack logs:**
   ```bash
   tail -f /var/log/cloudstack/management/management-server.log | grep -i veeam
   ```
3. **Check Veeam B&R logs** in Veeam console
4. **Verify configuration:**
   ```bash
   cmk list configurations zoneid=1 | grep veeam
   ```
5. **Enable debug logging** for detailed troubleshooting
6. **Review** [README.md](README.md) for configuration reference
---
**Migration Guide Version:** 1.0  
**Last Updated:** February 10, 2026  
**CloudStack Version:** 4.22.0.0
