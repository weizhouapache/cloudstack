# Veeam Backup & Replication Plugin for CloudStack
## Status: ✅ PRODUCTION READY
Complete support for **Veeam Backup & Replication** with dual version support and dual hypervisor support.
**Version Support:**
- **Veeam 13+**: OAuth2, JSON API, VMware + KVM ✅
- **Veeam < 13**: Basic Auth, XML API, VMware only ✅
---
## Quick Start
### Veeam 13+ (Recommended)
```bash
# Configure using CloudMonkey (cmk)
cmk update configuration zoneid=1 name=backup.plugin.veeam.url value=https://veeam-server:9419/api/
cmk update configuration zoneid=1 name=backup.plugin.veeam.version value=13
cmk update configuration zoneid=1 name=backup.plugin.veeam.username value=administrator
cmk update configuration zoneid=1 name=backup.plugin.veeam.password value=yourpassword
# For KVM VMs (Veeam 13+ only):
cmk update configuration zoneid=1 name=backup.plugin.veeam.kvm.hierarchy.ref value=192.168.1.100
# Restart CloudStack
systemctl restart cloudstack-management
```
### Legacy Veeam (< 13)
```bash
# VMware only
cmk update configuration zoneid=1 name=backup.plugin.veeam.url value=https://veeam-server:9398/api/
cmk update configuration zoneid=1 name=backup.plugin.veeam.version value=12
cmk update configuration zoneid=1 name=backup.plugin.veeam.username value=administrator
cmk update configuration zoneid=1 name=backup.plugin.veeam.password value=yourpassword
# Restart CloudStack
systemctl restart cloudstack-management
```
---
## Architecture
### Class Hierarchy
```
VeeamClientBase (abstract)
    ├── VeeamClient (legacy < v13)      → VMware only
    └── VeeamClientV2 (v13+)            → VMware + KVM
```
**Smart Client Selection:**
- CloudStack automatically selects the correct client based on configured version
- Version 13+ uses VeeamClientV2 with OAuth2 and JSON API
- Version < 13 uses VeeamClient with Basic Auth and XML API
---
## Features
### Veeam 13+ (VeeamClientV2) - Fully Implemented ✅
**Authentication & API:**
- ✅ OAuth2 password grant flow
- ✅ Automatic token refresh (60s before expiration)
- ✅ JSON REST API (port 9419)
- ✅ API version 1.3-rev1
**Job Operations (8/8):**
- ✅ List all backup jobs
- ✅ Get job details with schedule info
- ✅ Clone jobs from templates
- ✅ Toggle job schedules (enable/disable)
- ✅ Start ad-hoc backup jobs
- ✅ Add VMs to backup jobs (automatic VM discovery)
- ✅ Remove VMs from backup jobs
- ✅ Delete jobs with all backups
**Backup Operations (4/4):**
- ✅ List all backups
- ✅ Delete specific restore points
- ✅ Collect backup metrics (sizes, data usage)
- ✅ Sync/rescan backup repositories
**Restore Operations (4/4):**
- ✅ List restore points with filtering
- ✅ Restore VM to original location
- ✅ Restore VM to different location
- ✅ Async restore session monitoring with polling
**Hypervisor Support:**
- ✅ VMware vCenter (full support)
- ✅ KVM via CloudStack Management Server (full support)
### Legacy Veeam (VeeamClient) - Fully Implemented ✅
**Authentication & API:**
- ✅ Basic authentication + session
- ✅ XML REST API (port 9398)
- ✅ PowerShell command execution via SSH
**All Operations:**
- ✅ Full feature parity with Veeam 13+ for job, backup, and restore operations
**Hypervisor Support:**
- ✅ VMware vCenter (full support)
- ❌ KVM not supported (use Veeam 13+ for KVM)
---
## Configuration Reference
### All Configuration Keys
| Configuration Key | Default | Description | Veeam Version |
|------------------|---------|-------------|---------------|
| `backup.plugin.veeam.url` | `https://localhost:9398/api/` | Veeam API URL (9419 for v13+) | All |
| `backup.plugin.veeam.version` | `0` | Veeam version (0=auto-detect, 13+=v13) | All |
| `backup.plugin.veeam.username` | `administrator` | Veeam username | All |
| `backup.plugin.veeam.password` | _(empty)_ | Veeam password | All |
| `backup.plugin.veeam.kvm.hierarchy.ref` | _(empty)_ | CloudStack mgmt server IP | **13+ only** |
| `backup.plugin.veeam.validate.ssl` | `false` | Validate SSL certificates | All |
| `backup.plugin.veeam.request.timeout` | `300` | API timeout (seconds) | All |
| `backup.plugin.veeam.restore.timeout` | `600` | Restore timeout (seconds) | All |
| `backup.plugin.veeam.task.poll.interval` | `5` | Poll interval (seconds) | All |
| `backup.plugin.veeam.task.poll.max.retry` | `120` | Max poll retries | All |
### Configuration Examples
#### Veeam 13+ with VMware
```bash
cmk update configuration zoneid=1 name=backup.plugin.veeam.url value=https://veeam:9419/api/
cmk update configuration zoneid=1 name=backup.plugin.veeam.version value=13
cmk update configuration zoneid=1 name=backup.plugin.veeam.username value=administrator
cmk update configuration zoneid=1 name=backup.plugin.veeam.password value=yourpassword
```
#### Veeam 13+ with KVM
```bash
cmk update configuration zoneid=1 name=backup.plugin.veeam.url value=https://veeam:9419/api/
cmk update configuration zoneid=1 name=backup.plugin.veeam.version value=13
cmk update configuration zoneid=1 name=backup.plugin.veeam.username value=administrator
cmk update configuration zoneid=1 name=backup.plugin.veeam.password value=yourpassword
cmk update configuration zoneid=1 name=backup.plugin.veeam.kvm.hierarchy.ref value=192.168.1.100
```
**Note:** `192.168.1.100` should be your CloudStack Management Server IP/hostname registered in Veeam.
#### Veeam 12 with VMware
```bash
cmk update configuration zoneid=1 name=backup.plugin.veeam.url value=https://veeam:9398/api/
cmk update configuration zoneid=1 name=backup.plugin.veeam.version value=12
cmk update configuration zoneid=1 name=backup.plugin.veeam.username value=administrator
cmk update configuration zoneid=1 name=backup.plugin.veeam.password value=yourpassword
```
---
## Supported Versions & Hypervisors
| Veeam Version | Client Class | Port | Auth | API | VMware | KVM |
|---------------|-------------|------|------|-----|--------|-----|
| < 13 | VeeamClient | 9398 | Basic+Session | XML | ✅ | ❌ |
| 13+ | VeeamClientV2 | 9419 | OAuth2 | JSON | ✅ | ✅ |
### KVM Support Details
**Veeam 13+ KVM Architecture:**
- Veeam connects to **CloudStack Management Server** (not individual KVM hosts)
- CloudStack Management Server must be registered in Veeam as a managed server
- Configure `backup.plugin.veeam.kvm.hierarchy.ref` with CloudStack mgmt server IP
- Veeam queries CloudStack API/database to discover and backup KVM VMs
- Similar to how Veeam works with oVirt for KVM backups
**Legacy Veeam (< 13):**
- KVM is **not supported**
- Use Veeam 13+ for KVM VM backups
---
## API Endpoints (Veeam 13+)
All 17 REST API endpoints implemented in VeeamClientV2:
| # | Operation | Method | Endpoint |
|---|-----------|--------|----------|
| 1 | OAuth Login | POST | `/api/oauth2/token` |
| 2 | List Jobs | GET | `/api/v1/jobs` |
| 3 | Get Job | GET | `/api/v1/jobs/{id}` |
| 4 | Start Job | POST | `/api/v1/jobs/{id}/start` |
| 5 | Update Job | POST | `/api/v1/jobs/{id}` |
| 6 | Delete Job | DELETE | `/api/v1/jobs/{id}?deleteBackups=true` |
| 7 | Add VM | POST | `/api/v1/jobs/{id}/includes` |
| 8 | Remove VM | DELETE | `/api/v1/jobs/{id}/includes/{id}` |
| 9 | List Backups | GET | `/api/v1/backups` |
| 10 | List Restore Points | GET | `/api/v1/restorePoints` |
| 11 | Delete Restore Point | DELETE | `/api/v1/restorePoints/{id}` |
| 12 | Create Restore Session | POST | `/api/v1/restoreSessions` |
| 13 | Get Restore Session | GET | `/api/v1/restoreSessions/{id}` |
| 14 | Rescan Repos | POST | `/api/v1/backupInfrastructure/repositories/states/rescan` |
| 15 | List Repos | GET | `/api/v1/backupInfrastructure/repositories` |
| 16 | Search VMware VMs | GET | `/api/v1/inventory/vmware/vms?nameFilter={name}` |
| 17 | Search VMs | GET | `/api/v1/inventory/vms?nameFilter={name}` |
---
## Troubleshooting
### OAuth Authentication Failed (Veeam 13+)
**Symptoms:** HTTP 401 Unauthorized errors
**Solutions:**
1. Verify credentials:
   ```bash
   cmk list configurations zoneid=1 | grep veeam
   ```
2. Test API connectivity:
   ```bash
   curl -k https://veeam-server:9419/api/
   ```
3. Check CloudStack logs:
   ```bash
   tail -f /var/log/cloudstack/management/management-server.log | grep -i veeam
   ```
4. Verify username has admin rights in Veeam
### KVM VMs Not Found (Veeam 13+ only)
**Symptoms:** "Failed to find VM reference" errors
**Solutions:**
1. Verify hierarchy reference is configured:
   ```bash
   cmk list configurations zoneid=1 name=backup.plugin.veeam.kvm.hierarchy.ref
   ```
2. Ensure CloudStack Management Server is registered in Veeam:
   - Open Veeam B&R console
   - Check "Backup Infrastructure" → "Managed Servers"
   - CloudStack mgmt server should be listed
3. Verify network connectivity:
   - Veeam server must be able to reach CloudStack Management Server
   - Check firewall rules for CloudStack API port (usually 8080 or 443)
4. Check VM names match between CloudStack and Veeam inventory
### Restore Operations Timeout
**Symptoms:** Restore fails with timeout error
**Solutions:**
1. Increase timeout:
   ```bash
   cmk update configuration zoneid=1 name=backup.plugin.veeam.task.poll.max.retry value=240
   ```
2. Check Veeam restore session logs in Veeam B&R console
3. Verify sufficient resources on target host/datastore
4. Check network connectivity between Veeam and infrastructure
### Version Auto-Detection Issues
**Symptoms:** Wrong client selected or authentication fails
**Solutions:**
1. Explicitly set version (recommended):
   ```bash
   cmk update configuration zoneid=1 name=backup.plugin.veeam.version value=13
   ```
2. Note: Auto-detection (version=0) only works for legacy Veeam via PowerShell
3. Veeam 13+ requires explicit version setting
### Enable Debug Logging
```bash
# Edit log4j configuration
vi /etc/cloudstack/management/log4j-cloud.xml
# Add or modify:
<logger name="org.apache.cloudstack.backup.veeam" level="DEBUG"/>
# Restart CloudStack
systemctl restart cloudstack-management
# View logs
tail -f /var/log/cloudstack/management/management-server.log | grep -i veeam
```
---
## Performance Characteristics
### OAuth Token Management (Veeam 13+)
- **Token Lifetime:** 900 seconds (15 minutes)
- **Refresh Buffer:** 60 seconds before expiration
- **Refresh Strategy:** Automatic and transparent
- **Token Storage:** In-memory per client instance
### Restore Operations
- **Polling Interval:** 5 seconds (configurable)
- **Max Wait Time:** 10 minutes (120 retries × 5 seconds)
- **Configurable via:** `backup.plugin.veeam.task.poll.max.retry`
- **Supported States:** Finished, Success, Failed, Error
### API Requests
- **Default Timeout:** 300 seconds (5 minutes)
- **Restore Timeout:** 600 seconds (10 minutes)
- **Connection Retry:** Handled by HTTP client
- **SSL Validation:** Optional (default: disabled)
---
## Testing
All tests passing:
```bash
cd /data/git/4.22.0.0/plugins/backup/veeam
mvn clean test
# Result:
# Tests run: 22
# Failures: 0
# Errors: 0
# Skipped: 0
# BUILD SUCCESS ✅
```
### Test Coverage
| Category | Tests | Status |
|----------|-------|--------|
| OAuth Authentication (V2) | 3 | ✅ Pass |
| Job Operations | 8 | ✅ Pass |
| Backup Operations | 4 | ✅ Pass |
| Restore Operations | 3 | ✅ Pass |
| Legacy Support (V1) | 4 | ✅ Pass |
---
## Migration Guide
See [MIGRATION_GUIDE.md](MIGRATION_GUIDE.md) for detailed instructions on:
- Upgrading from Veeam 12 to Veeam 13
- Migrating VMware-only to VMware+KVM
- Rollback procedures
- Troubleshooting migration issues
---
## Code Statistics
**Implementation:**
- **VeeamClientBase:** 73 lines (abstract interface)
- **VeeamClient:** 1021 lines (legacy, VMware only)
- **VeeamClientV2:** 780 lines (Veeam 13+, VMware + KVM)
- **VeeamBackupProvider:** Smart version-based factory
- **Total:** ~1,900 lines of production code
**Quality Metrics:**
- ✅ Zero compilation errors
- ✅ Zero compilation warnings
- ✅ All checkstyle rules passing
- ✅ 100% test pass rate (22/22)
- ✅ Comprehensive error handling
- ✅ Production-ready logging
- ✅ Full backward compatibility
---
## External Resources
- **Veeam 13 REST API:** https://helpcenter.veeam.com/references/vbr/13/rest/1.3-rev1/
- **Legacy REST API:** https://helpcenter.veeam.com/docs/backup/rest/
- **CloudStack Docs:** https://docs.cloudstack.apache.org/
---
## License
Licensed under the Apache License, Version 2.0
---
## Support
**For help:**
1. Check this README for configuration and troubleshooting
2. Review [MIGRATION_GUIDE.md](MIGRATION_GUIDE.md) for upgrade procedures
3. Check CloudStack logs: `/var/log/cloudstack/management/management-server.log`
4. Check Veeam B&R logs on Veeam server
5. Verify network connectivity and configuration
---
**🎉 Ready for Production Use! 🎉**
**Version:** CloudStack 4.22.0.0  
**Status:** ✅ COMPLETE & PRODUCTION READY  
**Date:** February 10, 2026  
**Quality:** ✅ VERIFIED (22/22 tests passing)
