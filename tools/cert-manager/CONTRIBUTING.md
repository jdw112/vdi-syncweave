# Contributing to SDI Certificate Manager

Thank you for your interest in contributing!
This guide covers the conventions specific to the `tools/cert-manager` module.
For repo-wide rules see the top-level `CONTRIBUTING.md` in the SyncWeave root.

---

## Development environment

| Requirement | Detail |
|---|---|
| JDK | IBM Semeru 21 (`adks/ibm/jdk/jdk-21.0.12+8`) or OpenJDK 21+ |
| Build | Apache Ant 1.10.x + Apache Ivy 2.5.x |
| WSL2 distro | AlmaLinux9 (used by `build-SyncWeave.ps1`) |
| Test runner | JUnit Platform ConsoleLauncher (resolved by Ivy, no extra install) |

Clone and build:

```bash
git clone https://github.com/ibm-verify/SyncWeave
cd SyncWeave
ant cert-manager-test     # must be 0 failures before any PR
```

---

## Module conventions

### Java style

- Java 21 — use records, sealed interfaces, pattern matching, and text blocks
  where they improve clarity.
- No external formatters are enforced; follow the style of the surrounding code.
- Every public class and method must have a Javadoc comment.
- Package-private helpers (e.g. `buildEntry`, `resolvePassword`) may have
  brief single-line Javadoc.

### Passwords

- **Always** `char[]`. Never `String`.
- Zero arrays with `Arrays.fill(pass, '\0')` in a `finally` block.
- Never pass passwords as CLI arguments to sub-processes; inject via
  `ProcessBuilder.environment()`.
- Never log password values at any level.

### File paths

- Validate all user-supplied paths with `PathValidator.validatePath()`
  before use.
- Use `Path.of(...)` / `Files.*` — never `java.io.File` constructors
  in new code (existing `File` uses in CLI option parsing are acceptable).

### Keytool sub-processes

- IBM Semeru keytool may write "does not exist" errors to **stdout** (not
  stderr). Always check both streams.
- Use `KeystoreManager` for all keytool interactions; do not spawn keytool
  directly from other classes.

### Logging

- Use `LogManager.getLogger(ClassName.class)` (Log4j2 API).
- Emit an `AuditLogger` entry for **every** mutating operation (keystore
  write, properties write, registry write).
- Use `log.debug` for diagnostic detail, `log.info` for lifecycle events,
  `log.warn` for recoverable anomalies, `log.error` for failures.
- No `System.out` calls in production code paths — only in CLI output
  (`InteractiveCli`, `YamlAutomation`) where user-visible text is intended.

### Dry-run contract

Any operation that accepts a `dryRun` boolean **must**:

1. Validate all required inputs (return error code 2 on missing required fields).
2. Print a `[dry-run] Operation: <name>` summary via `System.out`.
3. Return exit code 0.
4. Make **no** filesystem writes, no sub-process launches, no network calls.

The file-existence check for paths that would be *written to* must be
skipped in dry-run. The file-existence check for paths that must be *read
from* should still be performed to give early feedback.

### XML / Ant files

- No `--` inside XML comments (causes `org.xml.sax` parse failure under Ant).
- No BOM on `.java` or `.xml` files; write with
  `new System.Text.UTF8Encoding($false)` on Windows.

---

## Adding a new operation

1. **Domain model** — add a record or enum value if needed under `model/`.
2. **Engine method** — implement in the appropriate engine class
   (`RegenerationEngine`, `CaKeyImportWorkflow`, etc.).
3. **YamlAutomation** — add a `case "my-operation" -> opMyOperation(doc, dry)`
   branch and a private `opMyOperation` method following the dry-run contract.
4. **InteractiveCli** — add a menu item and dispatch case.
5. **AuditLogger** — add a new `Op` enum constant and call `AuditLogger.log`
   on success and `AuditLogger.logFailure` on failure.
6. **Tests** — add at minimum:
   - A dry-run test (must return 0, make no writes).
   - A missing-required-fields test (must return 2).
   - A happy-path integration test if the operation has real side-effects.
7. **README** — add a row to the "Supported operations" table.

---

## Running tests

```bash
# All tests (193 as of Sub-Task 10)
ant cert-manager-test

# PowerShell (Windows + WSL2)
.\build-SyncWeave.ps1 -Target cert-manager-test
```

Tests must pass with **0 failures** before submitting a PR.
All tests run under IBM Semeru JDK 21 on AlmaLinux 9 in CI.

---

## PR checklist

- [ ] `ant cert-manager-test` passes with 0 failures locally
- [ ] New public API has Javadoc
- [ ] Mutating operations emit an `AuditLogger` entry
- [ ] Passwords are `char[]` and zeroed in `finally`
- [ ] Dry-run contract satisfied for any new `YamlAutomation` operation
- [ ] `README.md` updated if a new operation or CLI option was added
- [ ] No `--` inside XML comments
- [ ] No BOM on new `.java` files
- [ ] Commit message starts with a capital letter and a short imperative
      summary, e.g. `Add LDAP certificate chain renewal operation`

---

## Filing issues

Use the GitHub issue tracker with the label `cert-manager`.
Include the Java version (`java -version`), OS, and the full error output.