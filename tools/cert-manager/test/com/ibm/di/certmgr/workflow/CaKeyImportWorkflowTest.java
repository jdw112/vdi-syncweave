/*
 * Copyright contributors to the SyncWeave project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.certmgr.workflow;

import com.ibm.di.certmgr.keystore.KeystoreManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link CaKeyImportWorkflow}.
 *
 * Uses copies of the real SDI test keystores shipped with SyncWeave:
 *   serverapi/testadmin.jks  password="administrator"  alias=admin (PrivateKeyEntry)
 *   serverapi/testserver.jks password="server"         alias=server (PrivateKeyEntry)
 *
 * Each test works on copies so the originals are never modified.
 *
 * Coverage:
 *   - Missing target keystore returns failure
 *   - Server-side import succeeds
 *   - Admin-side: registry.txt created with correct DN
 *   - Admin-side: adminDn field populated
 *   - Cross-import creates signer alias in other keystore
 *   - Existing dest alias is deleted before import
 *   - Missing other keystore gracefully skipped
 *   - Changes list non-empty on success
 */
class CaKeyImportWorkflowTest {

    // Passwords matching the shipped test keystores
    private static final char[] ADMIN_PASS  = "administrator".toCharArray();
    private static final char[] SERVER_PASS = "server".toCharArray();

    // P12 export password (used only for the intermediate P12 file)
    private static final char[] P12_PASS = "P12Pass9x".toCharArray();

    // Alias names inside the shipped keystores
    private static final String ADMIN_ALIAS  = "admin";
    private static final String SERVER_ALIAS = "server";

    private static String keytoolPath;

    // Path to the repo's serverapi directory (WSL native FS copy is at /root/sb/...)
    // Resolved at runtime from java.home so it works wherever the JVM lives.
    private static Path serverapiDir;

    private final CaKeyImportWorkflow workflow = new CaKeyImportWorkflow();

    // -----------------------------------------------------------------------
    // Setup
    // -----------------------------------------------------------------------

    @BeforeAll
    static void setup() {
        String javaHome = System.getProperty("java.home");
        Path kt = Path.of(javaHome, "bin", "keytool");
        if (!Files.exists(kt)) kt = Path.of(javaHome, "bin", "keytool.exe");
        assertTrue(Files.exists(kt), "keytool not found under java.home=" + javaHome);
        keytoolPath = kt.toString();

        // Locate serverapi/ relative to the running JVM — on the WSL native FS
        // it is always at /root/sb/SyncWeave/serverapi/
        serverapiDir = Path.of("/root/sb/SyncWeave/serverapi");
        assertTrue(Files.isDirectory(serverapiDir),
                "serverapi dir not found at " + serverapiDir
                + " — tests must run inside the AlmaLinux9 WSL build container");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Copy a keystore to a temp dir so tests never modify originals. */
    private Path copyJks(Path tmp, String filename) throws IOException {
        Path src  = serverapiDir.resolve(filename);
        Path dest = tmp.resolve(filename);
        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
        return dest;
    }

    /**
     * Build a PKCS12 file from a JKS containing a single alias.
     * Returns the path to the created P12 file.
     */
    private Path buildP12(Path tmp, Path jksPath, char[] jksPass,
                          String srcAlias, String p12Name) {
        Path p12 = tmp.resolve(p12Name);
        // Export single alias to P12 via KeystoreManager
        KeystoreManager mgr = new KeystoreManager(keytoolPath, jksPath, jksPass);
        // Use convertToPkcs12 which exports the whole JKS; we accept that
        assertTrue(mgr.convertToPkcs12(p12, P12_PASS),
                "convertToPkcs12 to " + p12Name + " must succeed");
        return p12;
    }

    /** Spec for server-side import (no registry update). */
    private CaKeyImportSpec serverSpec(Path targetJks, Path otherJks,
                                       Path p12, Path baseDir) {
        return new CaKeyImportSpec(
                targetJks, otherJks, false, p12, P12_PASS,
                SERVER_PASS, ADMIN_PASS,
                SERVER_ALIAS, SERVER_ALIAS,   // destAlias, srcAlias
                baseDir, keytoolPath);
    }

    /** Spec for admin-side import (with registry update). */
    private CaKeyImportSpec adminSpec(Path targetJks, Path otherJks,
                                      Path p12, Path baseDir) {
        return new CaKeyImportSpec(
                targetJks, otherJks, true, p12, P12_PASS,
                ADMIN_PASS, SERVER_PASS,
                ADMIN_ALIAS, ADMIN_ALIAS,     // destAlias, srcAlias
                baseDir, keytoolPath);
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    void missingTargetKeystoreReturnsFailure(@TempDir Path tmp) throws IOException {
        Path missingJks = tmp.resolve("nonexistent.jks");
        Path p12        = tmp.resolve("dummy.p12");          // also missing
        CaKeyImportSpec spec = new CaKeyImportSpec(
                missingJks, null, false, p12, P12_PASS,
                SERVER_PASS, null, SERVER_ALIAS, SERVER_ALIAS, tmp, keytoolPath);

        CaKeyImportResult r = workflow.execute(spec);
        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("not found"),
                "Error message should mention 'not found': " + r.errorMessage());
    }

    @Test
    void serverSideImportSucceeds(@TempDir Path tmp) throws IOException {
        // Build a P12 from the admin JKS (contains the private key we'll import)
        Path adminCopy = copyJks(tmp, "testadmin.jks");
        Path p12 = buildP12(tmp, adminCopy, ADMIN_PASS, ADMIN_ALIAS, "src.p12");

        // Target is a copy of testserver.jks — we'll import the admin private key into it
        Path serverCopy = copyJks(tmp, "testserver.jks");

        CaKeyImportSpec spec = new CaKeyImportSpec(
                serverCopy, null, false, p12, P12_PASS,
                SERVER_PASS, null,
                "imported-admin", ADMIN_ALIAS,
                tmp, keytoolPath);

        CaKeyImportResult r = workflow.execute(spec);
        assertTrue(r.success(), "Server-side import should succeed: " + r.errorMessage());
        assertFalse(r.registryUpdated());
        assertNull(r.adminDn());
        assertTrue(r.changes().stream().anyMatch(c -> c.contains("Imported PKCS12")));
    }

    @Test
    void adminSideUpdatesRegistryTxt(@TempDir Path tmp) throws IOException {
        Path serverCopy = copyJks(tmp, "testserver.jks");
        Path p12 = buildP12(tmp, serverCopy, SERVER_PASS, SERVER_ALIAS, "src.p12");
        Path adminCopy  = copyJks(tmp, "testadmin.jks");

        CaKeyImportSpec spec = new CaKeyImportSpec(
                adminCopy, null, true, p12, P12_PASS,
                ADMIN_PASS, null,
                "imported-server", SERVER_ALIAS,
                tmp, keytoolPath);

        CaKeyImportResult r = workflow.execute(spec);
        assertTrue(r.success(), r.errorMessage());
        assertTrue(r.registryUpdated(), "registry.txt must be updated for admin side");

        Path registry = tmp.resolve("serverapi").resolve("registry.txt");
        assertTrue(Files.exists(registry));
        String content = Files.readString(registry);
        assertTrue(content.contains("[USER]"));
        assertTrue(content.contains("[ROLE]:admin"));
    }

    @Test
    void adminSideResultContainsAdminDn(@TempDir Path tmp) throws IOException {
        Path serverCopy = copyJks(tmp, "testserver.jks");
        Path p12 = buildP12(tmp, serverCopy, SERVER_PASS, SERVER_ALIAS, "src.p12");
        Path adminCopy = copyJks(tmp, "testadmin.jks");

        CaKeyImportSpec spec = new CaKeyImportSpec(
                adminCopy, null, true, p12, P12_PASS,
                ADMIN_PASS, null,
                "imported-server", SERVER_ALIAS,
                tmp, keytoolPath);

        CaKeyImportResult r = workflow.execute(spec);
        assertTrue(r.success(), r.errorMessage());
        assertNotNull(r.adminDn(), "adminDn should be set for admin-side import");
        assertTrue(r.adminDn().contains("CN="), "adminDn should contain CN=: " + r.adminDn());
    }

    @Test
    void crossImportCreatesSignerAliasInOtherKeystore(@TempDir Path tmp) throws IOException {
        Path adminCopy  = copyJks(tmp, "testadmin.jks");
        Path serverCopy = copyJks(tmp, "testserver.jks");
        Path p12 = buildP12(tmp, adminCopy, ADMIN_PASS, ADMIN_ALIAS, "src.p12");

        CaKeyImportSpec spec = new CaKeyImportSpec(
                serverCopy, adminCopy, false, p12, P12_PASS,
                SERVER_PASS, ADMIN_PASS,
                "imported-admin", ADMIN_ALIAS,
                tmp, keytoolPath);

        CaKeyImportResult r = workflow.execute(spec);
        assertTrue(r.success(), r.errorMessage());

        // Cross-import alias should be "imported-admin-signer" in adminCopy
        KeystoreManager adminCheck = new KeystoreManager(keytoolPath, adminCopy, ADMIN_PASS);
        assertTrue(adminCheck.listEntries().get("trustedCertEntry")
                .contains("imported-admin-signer"),
                "Cross-import alias 'imported-admin-signer' should appear in other keystore");
    }

    @Test
    void existingDestAliasIsDeletedBeforeImport(@TempDir Path tmp) throws IOException {
        // testserver.jks already has alias "server" — try overwriting it
        Path serverCopy = copyJks(tmp, "testserver.jks");
        Path adminCopy  = copyJks(tmp, "testadmin.jks");
        Path p12 = buildP12(tmp, adminCopy, ADMIN_PASS, ADMIN_ALIAS, "src.p12");

        CaKeyImportSpec spec = new CaKeyImportSpec(
                serverCopy, null, false, p12, P12_PASS,
                SERVER_PASS, null,
                SERVER_ALIAS, ADMIN_ALIAS,   // overwrite existing "server" alias
                tmp, keytoolPath);

        CaKeyImportResult r = workflow.execute(spec);
        assertTrue(r.success(), r.errorMessage());
        assertTrue(r.changes().stream().anyMatch(c -> c.contains("Deleted existing alias")),
                "Audit log should mention alias deletion");
    }

    @Test
    void missingOtherKeystoreIsSkippedGracefully(@TempDir Path tmp) throws IOException {
        Path adminCopy = copyJks(tmp, "testadmin.jks");
        Path p12 = buildP12(tmp, adminCopy, ADMIN_PASS, ADMIN_ALIAS, "src.p12");
        Path serverCopy = copyJks(tmp, "testserver.jks");

        // Point otherJks to a path that doesn't exist
        Path missingOther = tmp.resolve("other-nonexistent.jks");
        CaKeyImportSpec spec = new CaKeyImportSpec(
                serverCopy, missingOther, false, p12, P12_PASS,
                SERVER_PASS, SERVER_PASS,
                "imported-admin", ADMIN_ALIAS,
                tmp, keytoolPath);

        CaKeyImportResult r = workflow.execute(spec);
        assertTrue(r.success(), "Should succeed even when other JKS is absent: " + r.errorMessage());
        assertFalse(r.changes().stream().anyMatch(c -> c.contains("Cross-imported")),
                "No cross-import should occur when other JKS is absent");
    }

    @Test
    void changesListIsNonEmptyOnSuccess(@TempDir Path tmp) throws IOException {
        Path adminCopy  = copyJks(tmp, "testadmin.jks");
        Path serverCopy = copyJks(tmp, "testserver.jks");
        Path p12 = buildP12(tmp, adminCopy, ADMIN_PASS, ADMIN_ALIAS, "src.p12");

        CaKeyImportSpec spec = new CaKeyImportSpec(
                serverCopy, null, false, p12, P12_PASS,
                SERVER_PASS, null,
                "imported-admin", ADMIN_ALIAS,
                tmp, keytoolPath);

        CaKeyImportResult r = workflow.execute(spec);
        assertTrue(r.success(), r.errorMessage());
        assertFalse(r.changes().isEmpty(), "Changes list should not be empty on success");
    }
}
