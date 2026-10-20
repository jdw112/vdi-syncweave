# Punch List: `feature/add-egit`

**Goal:** Bundle Git support (EGit) into the SyncWeave Eclipse IDE, so users get Git team support without installing anything. End-user plug-in installs are disabled in the product (`SoftwareUpdateHandler` shows `CTGDIC117W`).

**Branch:** `feature/add-egit` (based on `main` @ `7fc662cb`)
**Commits:**
- `1367098d` feat: bundle EGit 7.6.0 (Git integration) into the SyncWeave Eclipse IDE
- `7b2e7ba3` revert: restore generated `equinox.server.p2` build.xml that a local test build changed by accident

**PR:** not opened yet

---

## 1. Source changes (2 files)

- [x] **`ce_rcp/plugins/com.ibm.tdi.rcp/tdieclipse.product`**: added 5 features after `org.eclipse.equinox.server.p2`:
  - `org.eclipse.egit`
  - `org.eclipse.jgit`
  - `org.eclipse.jgit.gpg.bc`
  - `org.eclipse.jgit.http.apache`
  - `org.eclipse.jgit.ssh.apache`
- [x] **`ce_rcp/features/com.ibm.tdi.feature/feature.xml`**: added 8 `<plugin>` entries for EGit's third-party dependencies (`version="0.0.0"`, the same style as the existing entries):
  - `bcpkix` (added to the existing Bouncy Castle block)
  - `com.googlecode.javaewah.JavaEWAH`
  - `org.apache.commons.commons-codec`
  - `org.apache.commons.commons-compress`
  - `org.apache.httpcomponents.httpclient`
  - `org.apache.httpcomponents.httpcore`
  - `org.apache.sshd.osgi`
  - `org.apache.sshd.sftp`

  > Why this is needed: the EGit features only include EGit's own bundles. With `useFeatures="true"`, PDE Build packages only bundles that some feature includes, and `com.ibm.tdi.feature` is where SyncWeave already lists its third-party bundles (bcprov, felix.scr, jna, and so on).

## 2. Target platform additions (`tools/eclipse/eclipse_runtime/4.39.0/eclipse/`)

All files below are new. Nothing that already existed in the runtime was changed. Total size is about 22 MB.

### Features (5)
- [x] `features/org.eclipse.egit_7.6.0.202603022253-r/`
- [x] `features/org.eclipse.jgit_7.6.0.202603022253-r/`
- [x] `features/org.eclipse.jgit.gpg.bc_7.6.0.202603022253-r/`
- [x] `features/org.eclipse.jgit.http.apache_7.6.0.202603022253-r/`
- [x] `features/org.eclipse.jgit.ssh.apache_7.6.0.202603022253-r/`

### EGit / JGit plugins (10)
- [x] `org.eclipse.egit_7.6.0.202603022253-r.jar`
- [x] `org.eclipse.egit.core_7.6.0.202603022253-r.jar`
- [x] `org.eclipse.egit.ui_7.6.0.202603022253-r.jar`
- [x] `org.eclipse.egit.doc_7.6.0.202603022253-r.jar`
- [x] `org.eclipse.jgit_7.6.0.202603022253-r.jar`
- [x] `org.eclipse.jgit.archive_7.6.0.202603022253-r.jar`
- [x] `org.eclipse.jgit.gpg.bc_7.6.0.202603022253-r.jar`
- [x] `org.eclipse.jgit.http.apache_7.6.0.202603022253-r.jar`
- [x] `org.eclipse.jgit.ssh.apache_7.6.0.202603022253-r.jar`
- [x] `org.eclipse.jgit.ssh.apache.agent_7.6.0.202603022253-r.jar`

### Third-party plugins (8)
- [x] `bcpkix_1.85.0.jar` (see the note below)
- [x] `com.googlecode.javaewah.JavaEWAH_1.2.3.jar`
- [x] `org.apache.commons.commons-codec_1.21.0.jar`
- [x] `org.apache.commons.commons-compress_1.28.0.jar`
- [x] `org.apache.httpcomponents.httpclient_4.5.14.jar`
- [x] `org.apache.httpcomponents.httpcore_4.4.16.jar`
- [x] `org.apache.sshd.osgi_2.17.1.jar`
- [x] `org.apache.sshd.sftp_2.17.1.jar`

### Provenance
- EGit/JGit **7.6.0.202603022253-r** is the version in the Eclipse **2026-03 (4.39)** release train.
- The dependency set was resolved with the p2 director from `https://download.eclipse.org/egit/updates-7.6/` + `https://download.eclipse.org/releases/2026-03/`.
- **bcpkix 1.85** came from Maven Central (`org.bouncycastle:bcpkix-jdk18on:1.85`, SHA-1 `b33d047adc6801a1cfc8ffe1634b23b306a670c1`, verified). p2 picked bcpkix 1.83, but that build only accepts bcprov/bcutil 1.83 (version range `[1.83,1.84)`), and the runtime already ships 1.85 of both.
- **Deliberately not added:**
  - bcprov/bcpg/bcutil 1.83 (the runtime's 1.85 copies satisfy EGit)
  - `org.eclipse.ui.workbench_3.138.0` (the runtime's patched 3.138.1 satisfies EGit)

## 3. Verification done

- [x] Isolated PDE product build against the updated runtime, using the same flags as `rules_mk/eclipse_rules.xml` (linux.gtk.x86_64 only, SyncWeave-compiled `com.ibm.*` bundles left out): **BUILD SUCCESSFUL**
- [x] All EGit/JGit bundles and their dependencies are in the packaged product
- [x] Headless launch with the OSGi console: every EGit/JGit/dependency bundle resolves; `org.eclipse.egit.core`, `org.eclipse.egit.ui` and `org.eclipse.jgit` reach ACTIVE
- [x] Regression check: 22 bundles fail to resolve (PDE, Help, e4 tools, `ant.ui`, `jdt.junit`), and a baseline build **without** EGit fails on the same 22. That problem already exists and EGit doesn't add to it.

## 4. Still to do

- [ ] **Full build** with the real toolchain (`TOOLS_HOME`, adks, JDK) via `build.xml` / `compile_ce_rcp`. This could not be run in the cloud session.
- [ ] Check `export/ce_rcp` build logs for new "Unable to find plug-in" or resolution errors
- [ ] **GUI smoke test (Linux):**
  - [ ] Git perspective and views open (Git Repositories, Staging, History)
  - [ ] Share a SyncWeave project with Git (Team > Share Project > Git)
  - [ ] Commit, then compare/diff an `.assemblyline` / `.connector` file (these are already registered as text in `com.ibm.tdi.rcp/plugin.xml`)
  - [ ] Clone and push over **HTTPS** (JGit http.apache transport)
  - [ ] Clone and push over **SSH** (Apache MINA SSHD; check that key/agent handling works)
  - [ ] Check that the SyncWeave classloader hook (`com.ibm.tdi.loader`) doesn't interfere with EGit
- [ ] **Windows x86_64** and **macOS (x86_64 + aarch64)** builds and a quick smoke test (EGit is pure Java, but only Linux was built)
- [ ] Installer: confirm `install/build.xml` picks up the new `eclipsece-*.zip` and the installed IDE launches
- [ ] Fix pack / update path: confirm `UpdateInstaller` / `applyUpdates.*` handle the extra bundles
- [ ] License / compliance review for the new third-party components:
  - EGit/JGit: EPL-2.0 / EDL-1.0
  - Apache SSHD, HttpComponents, Commons: Apache-2.0
  - BouncyCastle: MIT-style
  - JavaEWAH: Apache-2.0
- [ ] Update the SBOM / SPDX data if it's maintained (see `tools/spdx_to_cyclonedx.py`)
- [ ] Optional: add a short note to `docs/syncweave_local_build_steps.md` on how to upgrade EGit later (repeat the p2 director resolution and recheck BouncyCastle versions)
- [ ] Open the PR and get review

## 5. Out of scope / open decisions

- **SVN support** was not added. Subversive is archived. Subclipse needs native JavaHL libraries on each OS and its compatibility with 4.39 is unconfirmed. The recommendation is to migrate SVN repositories to Git (`git svn` / `svn2git`) unless direct SVN access is required.
- **End-user plug-in installs stay disabled.** If they're ever needed, the safer option is to allow installs only from an internal, approved p2 update site instead of re-enabling installs from anywhere.
