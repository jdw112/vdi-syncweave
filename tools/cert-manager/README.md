# SDI Certificate Manager

A Java 21 command-line tool for managing JKS / PKCS12 keystore lifecycles
in IBM Security Directory Integrator (SDI / VDI) deployments.
Part of the [SyncWeave](https://github.com/ibm-verify/SyncWeave) community project.

---

## Features

| Category | Capability |
|---|---|
| **Keystore lifecycle** | Generate RSA/EC key pairs, rotate store and key passwords, convert JKS to PKCS12 |
| **Certificate ingestion** | Download and import full TLS certificate chains from live HTTPS/LDAPS endpoints |
| **CA key import** | Import PKCS12 CA keys into server and/or admin keystores with automatic cross-import |
| **Properties management** | Decrypt, encrypt, and create SDI `.properties` / `.sth` stash files via `idisrv.sth` |
| **Registry update** | Maintain `registry.txt` admin DN entries for SDI solution directories |
| **Automation** | YAML-driven headless mode for CI/CD pipelines with `--dry-run` support |
| **Interactive CLI** | 15-item menu for guided operations on a TTY |
| **Audit logging** | Structured JSON-lines audit trail in `logs/cert-manager-audit.log` |
| **Backup** | Automatic tar.gz backups of keystores and properties files before every mutation |

---

## Requirements

| Component | Version |
|---|---|
| Java | IBM Semeru 21 (OpenJ9) or any OpenJDK 21+ |
| Ant | 1.10.x (build only) |
| Ivy | 2.5.x (dependency resolution, build only) |
| OS | Linux (AlmaLinux 9 validated), macOS, Windows |

---

## Build

```bash
# From SyncWeave repo root
ant cert-manager           # compile + package JAR
ant cert-manager-test      # compile + run all 193 tests
```

Or via the PowerShell helper (Windows + WSL2):

```powershell
.\build-SyncWeave.ps1 -Target cert-manager
.\build-SyncWeave.ps1 -Target cert-manager-test
```

The assembled JAR and dependency JARs are written to
`tools/cert-manager/build/lib/`.

---

## Quick start

### Interactive mode (TTY)

```bash
bin/certmgr.sh \
  --install-dir /opt/ibm/sdi \
  --solution-dir /opt/ibm/sdi/solution
```

### YAML automation mode

```bash
bin/certmgr.sh --automate operations.yaml --dry-run
```

Sample `operations.yaml`:

```yaml
operation: regenerate
mode: solution
solution_dir: /opt/ibm/sdi/solution
old_server_pass: !env SDI_OLD_SERVER_PASS
old_admin_pass:  !env SDI_OLD_ADMIN_PASS
new_server_pass: !env SDI_NEW_SERVER_PASS
new_admin_pass:  !env SDI_NEW_ADMIN_PASS
dname: "CN=API Admin, OU=Ops, O=IBM, C=US"
validity: 1095
```

Password values may reference environment variables using `!env VAR_NAME`
to avoid storing credentials in YAML files.

---

## Supported operations (YAML `operation` field)

| Value | Description |
|---|---|
| `regenerate` | Full keystore regeneration (server + admin) |
| `decrypt-properties` | Decrypt SDI `.properties` file in-place |
| `encrypt-properties` | Encrypt SDI `.properties` file in-place |
| `create-stash` | Generate a new `.sth` password stash |
| `update-registry` | Write or replace the admin DN block in `registry.txt` |
| `add-cert-from-https` | Download and import a certificate chain from a TLS endpoint |
| `import-ca-key` | Import a PKCS12 CA key into the server or admin keystore |

---

## CLI options

```
certmgr [-hV] [--dry-run] [-a=<automateFile>] [-c=<configFile>]
        [-i=<installDir>] [--jks=<jksPath>] [--log-level=<logLevel>]
        [--mode=<mode>] [-s=<solutionDir>]

  -a, --automate=<automateFile>   YAML automation file
  -c, --config=<configFile>       Configuration YAML (default: cert-manager.yaml)
      --dry-run                   Validate inputs, make no changes
  -h, --help                      Show help and exit
  -i, --install-dir=<installDir>  SDI installation directory
      --jks=<jksPath>             Keystore file path
      --log-level=<logLevel>      TRACE | DEBUG | INFO | WARN | ERROR
      --mode=<mode>               install | solution (default: solution)
  -s, --solution-dir=<dir>        SDI solution directory
  -V, --version                   Print version and exit
```

---

## Logging

| File | Content | Rotation |
|---|---|---|
| `logs/cert-manager.log` | Full application log | 10 MB / keep 5 |
| `logs/cert-manager-audit.log` | JSON-lines audit trail (mutations only) | Daily / keep 30 days |

Audit entries are structured JSON lines, for example:

```json
{"ts":"2026-09-21T17:34:56.123Z","op":"REGEN_COMPLETE","mode":"solution","detail":"CN=API Admin, OU=Ops","dryRun":false,"result":"SUCCESS"}
```

---

## Security notes

- Passwords are held in `char[]` and zeroed immediately after use.
- Passwords must never be passed as CLI arguments; use `!env VAR_NAME`
  in YAML or the interactive prompt.
- All file paths are validated against a base directory to prevent
  path-traversal attacks.
- Sub-processes (`keytool`) receive passwords via environment variables,
  never via command-line arguments.

---

## Project layout

```
tools/cert-manager/
  bin/              certmgr.sh / certmgr.bat launchers
  build/            compiled output (git-ignored)
  resources/        log4j2.xml, cert-manager.yaml defaults
  src/              Java 21 source
    com/ibm/di/certmgr/
      audit/        AuditLogger
      cli/          InteractiveCli, YamlAutomation
      config/       Configuration
      exception/    CertManagerException, ConfigurationException
      keystore/     KeystoreManager
      model/        AppContext, DirectoryMode, result records
      properties/   PropertiesFileHandler, SdiPropertiesStore, ...
      regen/        BackupManager, RegenerationEngine, RegistryFileUpdater
      remote/       RemoteCertClient, CertChainIngester
      security/     PasswordValidator, PathValidator, ValidationResult
      workflow/     CaKeyImportWorkflow, CaKeyImportSpec, CaKeyImportResult
  test/             JUnit 5 tests (193 tests, all green)
  build.xml         Ant module build
  ivy.xml           Ivy dependency manifest
```

---

## License

Apache-2.0 — see `LICENSE` in the SyncWeave repository root.