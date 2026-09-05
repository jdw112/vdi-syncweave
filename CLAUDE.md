# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

SyncWeave is the open-source edition of IBM Verify Directory Integrator (VDI), formerly Tivoli/IBM Directory Integrator (TDI). It is an enterprise identity-data synchronization and transformation engine. The runtime model is the **AssemblyLine (AL)**: read from a source connector, apply attribute-mapping and scripted (Java/JavaScript) transformations, write to a target connector. Only on-premises deployments are in scope here; container docs on ibm.com apply to the commercial VDI product, not this project.

Package namespace is still `com.ibm.di.*` throughout (source, JARs, config XML) - do not "rebrand" it.

## Build system

The build is **Ant + Ivy + Eclipse PDE**, targeting **IBM Semeru OpenJDK 21**. Full, authoritative instructions live in `docs/syncweave_local_build_steps.md`; the test procedure in `docs/syncweave_sanity_unit_tests.md`. Read those before doing any build/test work - they contain the exact staging steps and known-failure notes that this summary does not repeat.

Required environment before any `ant` command:
```bash
export JAVA_HOME=<repo>/adks/ibm/jdk/jdk-21.0.12+8   # from setup-jdk.sh
export TOOLS_HOME=<repo>/tools                        # Eclipse PDE tools tree
export ANT_HOME=<ant>/apache-ant-1.10.17
export PATH=$JAVA_HOME/bin:$ANT_HOME/bin:$PATH
```
`non_docker_build.sh` is a template for these exports (edit the paths, then `source` it). `setup-jdk.sh` downloads the JDK/JREs into `adks/`.

One-time: copy `ivy/ivy.jar` into `$ANT_HOME/lib/` (must load via Ant's system classloader, not a `<taskdef>`, or `resolve` running twice throws `ClassCastException: DefaultModuleDescriptor incompatible`).

Common targets (run from repo root):
```bash
ant resolve rename_jars   # download 3rd-party JARs into lib/ivy/ (needs network; cache lib/ivy/ for offline)
ant package               # developer build: compile Java + OSGi bundles + CE RCP zips (no javadoc/installers)
ant images                # full release build: package + javadoc + installer/ship zips
ant clean
```
Check results with `grep "BUILD SUCCESSFUL\|BUILD FAILED" build.log`. Outputs land in `export/` (`export/jars/`, `export/osgi/embedded/`, `export/ce_eclipse/`; `ant images` adds `export/zip_bundles/`, `ship/`).

## Testing

```bash
ant package package_unit_tests    # build product + test JARs (test.jar, boot.jar)
```
The JUnit suite does **not** run from the source tree. `unit_tests/` must be physically copied (not symlinked) into a product install root, and several runtime JARs staged into `<install>/jars/` - see `docs/syncweave_sanity_unit_tests.md` Step B for the exact copy list. Then:
```bash
<install>/unit_tests/bin/runTestSuite.sh 2>&1 | tee run.log
grep -c "<TestHeader>" run.log        # 0 = all passed; each <TestHeader> is a failure
runTestSuite.sh -suite com.ibm.di.server.ConnectorComponentTest   # single test class
```
The runner is a custom `TestFrameworkRunner` (not `mvn`/`gradle`); all args after the script are forwarded to it. Other tiers: `runcvt.sh` (CVT functional tests, start an embedded server, discover `@CVTComponent` classes; needs `unit_tests/tdi_install_dir.properties`), `runIntPerfSuite.sh` (throughput). Known-expected failures in a bare environment: `DISBConnectorTest` (needs ActiveMQ on :61616).

After changing any product OSGi bundle, re-run `ant package` and re-copy the affected `export/osgi/embedded/<bundle>.jar` into `<install>/jars/` before re-testing - stale bundles cause `NoClassDefFoundError`.

Static analysis baselines (FindBugs, PMD) are under `sca/` - new code should not add findings beyond these baselines.

## Source layout & the autogen build model

`src/com/ibm/di/` is organized by subsystem: `server` (AssemblyLine engine, component wiring), `connector` (all connectors), `fc` (Function Components), `parser`, `script` (JS/scripting hooks), `entry` (Entry/Attribute data model), `config` (config model + binding), `api` (REST + RMI remote API), `tp` (Touchpoint server), plus `store`, `event`, `log`, `security`, `oidc`, etc.

**Critical: `build.xml` is generated, not hand-edited.** The autogen system (`autogen/ant.pl`, a Perl generator) expands `autogen/main.xml` plus every component's `autogen/components/<kind>/<Name>/tdi.xml` into the ~550KB root `build.xml`. Edit `autogen/main.xml` / the per-component `tdi.xml`, then regenerate (`autogen.bat`: `perl ant.pl > ../build.xml`) rather than editing `build.xml` directly.

Each shipped component (connector, function, parser, ...) has an `autogen/components/<kind>/<Name>/` directory containing:
- `tdi.xml` - registers the component as `ibmdi.<Name>`: maps the logical name to its `connectorType` (fully-qualified Java class), declares `<SupportedModes>`, default config parameters, `<Reconnect>` rules, and the Config Editor form layout.
- `NLS/` - translated form labels for the Config Editor UI.
- `tmsfiles/<locale>/` - runtime message catalogs (the `<Name>_<locale>.properties`).

## Connector/component architecture

Connectors extend `com.ibm.di.connector.Connector` (abstract, implements `ConnectorInterface`) and are driven by the AL engine through a fixed lifecycle. The constructor declares supported modes via `setModes(...)`; the engine then calls the methods for the configured mode:
- `initialize(Object)` - open the connection / read config params via `getParam(...)`, `hasConfigValue(...)`.
- Iterator mode: `selectEntries()` then repeated `getNextEntry()` (returns `null` at end).
- `putEntry(Entry)` (AddOnly), `modEntry(...)` (Update), `deleteEntry(...)` (Delete), `findEntry(SearchCriteria)` (Lookup).
- `terminate()` - close resources.
- `isExceptionFatal(Exception)` decides whether the engine triggers the reconnect rules from `tdi.xml`.

Data flows as `com.ibm.di.entry.Entry` objects (attribute name -> `com.ibm.di.entry.Attribute` with values). The `$dn` attribute carries the distinguished name for directory connectors. Delta mode uses `AttributeValue` operation codes (`AV_ADD`/`AV_DELETE`/`AV_UNCHANGED`) to express per-value changes.

Runtime user-facing strings are **never hardcoded**: a connector holds a `static ResourceHash sResHash = new ResourceHash("<propertiesbase>")` and calls `sResHash.getString("KEY", args...)`. The `<propertiesbase>` maps to the component's `tmsfiles` catalog. When adding a message, add the key to those `.properties` files, don't inline the literal.

## Conventions

- Every source file needs the Apache-2.0 SPDX header (see any existing `.java`).
- Sign commits off (DCO): `git commit -s` adding `Signed-off-by:`.
- Commit message prefixes (from CONTRIBUTING.md): `feat`, `fix`, `revert`, `docs`, `style`, `refactor`, `test`, `build`, `autogen`, `security`, `ci`, `chore`.
- PRs target `main`.
