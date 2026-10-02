/*
 * Copyright contributors to the SyncWeave project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.certmgr.keystore;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration-style tests for {@link KeystoreManager}.
 *
 * <p>Each test that needs a real keystore creates one via keytool in a
 * {@code @TempDir}.  The keytool binary is resolved from {@code java.home}
 * so the tests always use the same JDK that is running them.
 *
 * <p>Test coverage:
 * <ul>
 *   <li>getStoretype() extension detection</li>
 *   <li>KeytoolResult record accessors</li>
 *   <li>validatePassword() pass and fail</li>
 *   <li>backupKeystore() creates timestamped file</li>
 *   <li>restoreKeystore() restores content</li>
 *   <li>listEntries() parses keyEntry and trustedCertEntry</li>
 *   <li>deleteEntry() removes an alias; tolerates missing alias</li>
 *   <li>importCertificate() + exportCertificate() round-trip</li>
 *   <li>showCertificateDetails() succeeds for known alias</li>
 *   <li>convertToPkcs12() produces a readable PKCS12 file</li>
 *   <li>importPkcs12() round-trips through a temporary P12</li>
 *   <li>getCertificateDn() extracts Subject DN</li>
 *   <li>changeKeyPassword() updates JKS key password</li>
 *   <li>changeKeyPassword() rejects PKCS12</li>
 *   <li>changeStorePassword() updates in-memory storepass</li>
 *   <li>generateKeypair() default params; explicit params with SAN</li>
 *   <li>runKeytool() timeout path (bad binary)</li>
 * </ul>
 */
class KeystoreManagerTest {

    // Passwords as char arrays - never String literals in fields
    private static final char[] STORE_PASS  = "Test1234!".toCharArray();
    private static final char[] KEY_PASS    = "Test1234!".toCharArray();
    private static final char[] NEW_PASS    = "NewPass5$".toCharArray();
    private static final String ALIAS       = "testkey";
    private static final String DNAME       = "CN=Test, OU=CI, O=IBM, L=Austin, ST=TX, C=US";

    private static String keytoolPath;

    // -----------------------------------------------------------------------
    // Setup
    // -----------------------------------------------------------------------

    @BeforeAll
    static void resolveKeytool() {
        // Derive keytool from the running JVM home - works on all platforms
        String javaHome = System.getProperty("java.home");
        Path kt = Path.of(javaHome, "bin", "keytool");
        if (!Files.exists(kt)) {
            kt = Path.of(javaHome, "bin", "keytool.exe");
        }
        assertTrue(Files.exists(kt),
                "keytool not found under java.home=" + javaHome);
        keytoolPath = kt.toString();
    }

    // -----------------------------------------------------------------------
    // Helper: create a JKS with one keypair entry
    // -----------------------------------------------------------------------

    private KeystoreManager createJks(Path dir) throws IOException {
        Path ks = dir.resolve("test.jks");
        KeystoreManager mgr = new KeystoreManager(keytoolPath, ks, STORE_PASS);
        boolean ok = mgr.generateKeypair(ALIAS, KEY_PASS, DNAME, 365, 2048, "RSA", null);
        assertTrue(ok, "Precondition: generateKeypair must succeed to seed test JKS");
        return mgr;
    }

    // -----------------------------------------------------------------------
    // getStoretype
    // -----------------------------------------------------------------------

    @Test
    void storetypeJks() {
        KeystoreManager m = new KeystoreManager(keytoolPath, "/tmp/foo.jks", STORE_PASS);
        assertEquals("JKS", m.getStoretype());
    }

    @Test
    void storetypeP12() {
        KeystoreManager m = new KeystoreManager(keytoolPath, "/tmp/foo.p12", STORE_PASS);
        assertEquals("PKCS12", m.getStoretype());
    }

    @Test
    void storetypePfx() {
        KeystoreManager m = new KeystoreManager(keytoolPath, "/tmp/foo.pfx", STORE_PASS);
        assertEquals("PKCS12", m.getStoretype());
    }

    @Test
    void storetypeUnknownDefaultsToJks() {
        KeystoreManager m = new KeystoreManager(keytoolPath, "/tmp/foo.ks", STORE_PASS);
        assertEquals("JKS", m.getStoretype());
    }

    // -----------------------------------------------------------------------
    // KeytoolResult record
    // -----------------------------------------------------------------------

    @Test
    void keytoolResultAccessors() {
        KeystoreManager.KeytoolResult r = new KeystoreManager.KeytoolResult(true, "out", "err");
        assertTrue(r.success());
        assertEquals("out", r.stdout());
        assertEquals("err", r.stderr());
    }

    // -----------------------------------------------------------------------
    // validatePassword
    // -----------------------------------------------------------------------

    @Test
    void validatePasswordCorrect(@TempDir Path tmp) throws IOException {
        KeystoreManager mgr = createJks(tmp);
        assertTrue(mgr.validatePassword());
    }

    @Test
    void validatePasswordWrong(@TempDir Path tmp) throws IOException {
        createJks(tmp);
        // Point to same JKS but with wrong password
        Path ks = tmp.resolve("test.jks");
        KeystoreManager bad = new KeystoreManager(keytoolPath, ks, "WrongPass9!".toCharArray());
        assertFalse(bad.validatePassword());
    }

    // -----------------------------------------------------------------------
    // backupKeystore / restoreKeystore
    // -----------------------------------------------------------------------

    @Test
    void backupCreatesTimestampedFile(@TempDir Path tmp) throws IOException {
        KeystoreManager mgr = createJks(tmp);
        Optional<Path> backup = mgr.backupKeystore();
        assertTrue(backup.isPresent(), "Backup should be present");
        assertTrue(Files.exists(backup.get()), "Backup file should exist");
        assertTrue(backup.get().getFileName().toString().contains("backup_"),
                "Backup filename should contain 'backup_'");
    }

    @Test
    void backupFailsForMissingKeystore(@TempDir Path tmp) {
        Path missing = tmp.resolve("nonexistent.jks");
        KeystoreManager mgr = new KeystoreManager(keytoolPath, missing, STORE_PASS);
        Optional<Path> backup = mgr.backupKeystore();
        assertTrue(backup.isEmpty(), "Backup of missing file should return empty");
    }

    @Test
    void restoreKeystore(@TempDir Path tmp) throws IOException {
        KeystoreManager mgr = createJks(tmp);
        Optional<Path> backup = mgr.backupKeystore();
        assertTrue(backup.isPresent());

        // Corrupt the keystore
        Path ks = tmp.resolve("test.jks");
        Files.writeString(ks, "CORRUPTED");

        boolean restored = mgr.restoreKeystore(backup.get());
        assertTrue(restored, "Restore should succeed");

        // After restore the password should validate again
        assertTrue(mgr.validatePassword(), "Password should validate after restore");
    }

    // -----------------------------------------------------------------------
    // listEntries
    // -----------------------------------------------------------------------

    @Test
    void listEntriesContainsKeyEntry(@TempDir Path tmp) throws IOException {
        KeystoreManager mgr = createJks(tmp);
        Map<String, List<String>> entries = mgr.listEntries();
        assertTrue(entries.containsKey("keyEntry"), "Map must have keyEntry key");
        assertTrue(entries.get("keyEntry").contains(ALIAS),
                "keyEntry list must contain alias '" + ALIAS + "'");
    }

    @Test
    void listEntriesEmptyForMissingKeystore(@TempDir Path tmp) {
        Path missing = tmp.resolve("nonexistent.jks");
        KeystoreManager mgr = new KeystoreManager(keytoolPath, missing, STORE_PASS);
        Map<String, List<String>> entries = mgr.listEntries();
        assertTrue(entries.get("keyEntry").isEmpty());
        assertTrue(entries.get("trustedCertEntry").isEmpty());
    }

    // -----------------------------------------------------------------------
    // deleteEntry
    // -----------------------------------------------------------------------

    @Test
    void deleteEntryRemovesAlias(@TempDir Path tmp) throws IOException {
        KeystoreManager mgr = createJks(tmp);
        assertTrue(mgr.deleteEntry(ALIAS), "Delete should succeed");
        assertFalse(mgr.listEntries().get("keyEntry").contains(ALIAS),
                "Alias should be gone after delete");
    }

    @Test
    void deleteEntryToleratesMissingAlias(@TempDir Path tmp) throws IOException {
        KeystoreManager mgr = createJks(tmp);
        // Delete non-existent alias must not throw or return false
        assertTrue(mgr.deleteEntry("ghost-alias"),
                "Delete of absent alias should still return true");
    }

    // -----------------------------------------------------------------------
    // exportCertificate / importCertificate round-trip
    // -----------------------------------------------------------------------

    @Test
    void exportThenImportCertificate(@TempDir Path tmp) throws IOException {
        KeystoreManager mgr = createJks(tmp);
        Path pemFile = tmp.resolve("cert.pem");

        assertTrue(mgr.exportCertificate(ALIAS, pemFile), "Export should succeed");
        assertTrue(Files.exists(pemFile), "PEM file should be created");
        assertTrue(Files.size(pemFile) > 0, "PEM file should not be empty");

        // Import back under a different alias
        String importAlias = "imported-cert";
        assertTrue(mgr.importCertificate(importAlias, pemFile), "Import should succeed");
        assertTrue(mgr.listEntries().get("trustedCertEntry").contains(importAlias),
                "Imported alias should appear as trustedCertEntry");
    }

    @Test
    void exportCertificateFailsForMissingAlias(@TempDir Path tmp) throws IOException {
        KeystoreManager mgr = createJks(tmp);
        Path out = tmp.resolve("out.pem");
        assertFalse(mgr.exportCertificate("no-such-alias", out));
    }

    // -----------------------------------------------------------------------
    // showCertificateDetails
    // -----------------------------------------------------------------------

    @Test
    void showCertificateDetailsSucceeds(@TempDir Path tmp) throws IOException {
        KeystoreManager mgr = createJks(tmp);
        assertTrue(mgr.showCertificateDetails(ALIAS));
    }

    @Test
    void showCertificateDetailsFailsForMissingAlias(@TempDir Path tmp) throws IOException {
        KeystoreManager mgr = createJks(tmp);
        assertFalse(mgr.showCertificateDetails("no-such-alias"));
    }

    // -----------------------------------------------------------------------
    // convertToPkcs12
    // -----------------------------------------------------------------------

    @Test
    void convertToPkcs12ProducesReadableFile(@TempDir Path tmp) throws IOException {
        KeystoreManager mgr = createJks(tmp);
        Path p12 = tmp.resolve("output.p12");

        assertTrue(mgr.convertToPkcs12(p12, NEW_PASS), "Conversion should succeed");
        assertTrue(Files.exists(p12), "P12 output file should exist");
        assertTrue(Files.size(p12) > 0, "P12 file should not be empty");

        // Validate the P12 is readable with the destination password
        KeystoreManager p12Mgr = new KeystoreManager(keytoolPath, p12, NEW_PASS);
        assertTrue(p12Mgr.validatePassword(), "P12 should be readable with dest password");
    }

    // -----------------------------------------------------------------------
    // importPkcs12
    // -----------------------------------------------------------------------

    @Test
    void importPkcs12RoundTrip(@TempDir Path tmp) throws IOException {
        // 1. Create source JKS and export to P12
        KeystoreManager src = createJks(tmp);
        Path p12 = tmp.resolve("src.p12");
        assertTrue(src.convertToPkcs12(p12, NEW_PASS));

        // 2. Create a fresh empty JKS destination (generateKeypair creates it)
        //    Actually we need an empty JKS - create by generating and then deleting
        Path destKs = tmp.resolve("dest.jks");
        KeystoreManager dest = new KeystoreManager(keytoolPath, destKs, STORE_PASS);
        dest.generateKeypair("placeholder", KEY_PASS, DNAME, 365, 2048, "RSA", null);
        dest.deleteEntry("placeholder");

        // 3. Import from P12
        assertTrue(dest.importPkcs12(p12, NEW_PASS, "imported-from-p12", ALIAS),
                "importPkcs12 should succeed");
        assertTrue(dest.listEntries().get("keyEntry").contains("imported-from-p12"),
                "Imported key should appear in destination keystore");
    }

    // -----------------------------------------------------------------------
    // getCertificateDn
    // -----------------------------------------------------------------------

    @Test
    void getCertificateDnReturnsOwner(@TempDir Path tmp) throws IOException {
        KeystoreManager mgr = createJks(tmp);
        Optional<String> dn = mgr.getCertificateDn(ALIAS);
        assertTrue(dn.isPresent(), "DN should be present");
        assertTrue(dn.get().contains("CN=Test"), "DN should contain the CN we set");
    }

    @Test
    void getCertificateDnEmptyForMissingAlias(@TempDir Path tmp) throws IOException {
        KeystoreManager mgr = createJks(tmp);
        Optional<String> dn = mgr.getCertificateDn("ghost");
        assertTrue(dn.isEmpty(), "DN for absent alias should be empty");
    }

    // -----------------------------------------------------------------------
    // changeKeyPassword
    // -----------------------------------------------------------------------

    @Test
    void changeKeyPasswordSucceeds(@TempDir Path tmp) throws IOException {
        KeystoreManager mgr = createJks(tmp);
        assertTrue(mgr.changeKeyPassword(ALIAS, KEY_PASS, NEW_PASS),
                "changeKeyPassword should succeed on JKS");
    }

    @Test
    void changeKeyPasswordRejectsPkcs12(@TempDir Path tmp) throws IOException {
        // Build a P12 from the JKS
        KeystoreManager jks = createJks(tmp);
        Path p12 = tmp.resolve("ks.p12");
        jks.convertToPkcs12(p12, STORE_PASS);

        KeystoreManager p12Mgr = new KeystoreManager(keytoolPath, p12, STORE_PASS);
        assertFalse(p12Mgr.changeKeyPassword(ALIAS, STORE_PASS, NEW_PASS),
                "changeKeyPassword must refuse on PKCS12");
    }

    // -----------------------------------------------------------------------
    // changeStorePassword
    // -----------------------------------------------------------------------

    @Test
    void changeStorePasswordUpdatesInMemoryPass(@TempDir Path tmp) throws IOException {
        KeystoreManager mgr = createJks(tmp);
        assertTrue(mgr.changeStorePassword(NEW_PASS), "changeStorePassword should succeed");

        // After the change, the manager must use the new password for further ops
        assertTrue(mgr.validatePassword(),
                "validatePassword should succeed with new in-memory password");
    }

    // -----------------------------------------------------------------------
    // generateKeypair
    // -----------------------------------------------------------------------

    @Test
    void generateKeypairDefaultParams(@TempDir Path tmp) {
        Path ks = tmp.resolve("new.jks");
        KeystoreManager mgr = new KeystoreManager(keytoolPath, ks, STORE_PASS);
        assertTrue(mgr.generateKeypair("mykey", KEY_PASS, null, null, null, null, null),
                "generateKeypair with all-default params should succeed");
        assertTrue(Files.exists(ks), "Keystore file should be created");
    }

    @Test
    void generateKeypairWithSan(@TempDir Path tmp) {
        Path ks = tmp.resolve("san.jks");
        KeystoreManager mgr = new KeystoreManager(keytoolPath, ks, STORE_PASS);
        boolean ok = mgr.generateKeypair(
                "sankey", KEY_PASS,
                "CN=SAN Test, O=IBM, C=US",
                365, 2048, "RSA",
                List.of("dns:localhost", "ip:127.0.0.1"));
        assertTrue(ok, "generateKeypair with SAN should succeed");
        assertTrue(mgr.listEntries().get("keyEntry").contains("sankey"));
    }

    @Test
    void generateKeypairFailsWithBadKeytool(@TempDir Path tmp) {
        Path ks = tmp.resolve("bad.jks");
        KeystoreManager mgr = new KeystoreManager("/nonexistent/keytool", ks, STORE_PASS);
        assertFalse(mgr.generateKeypair("k", KEY_PASS, null, null, null, null, null),
                "Bad keytool path must return false");
    }

    // -----------------------------------------------------------------------
    // runKeytool - bad binary path (error path)
    // -----------------------------------------------------------------------

    @Test
    void runKeytoolBadBinaryReturnsFailure(@TempDir Path tmp) {
        KeystoreManager mgr = new KeystoreManager("/no/such/keytool", tmp.resolve("k.jks"), STORE_PASS);
        KeystoreManager.KeytoolResult r = mgr.runKeytool(
                List.of("-list"), "list", false, null);
        assertFalse(r.success());
        assertFalse(r.stderr().isBlank(), "stderr should contain error message");
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    @Test
    void accessorsReturnConstructorValues(@TempDir Path tmp) {
        Path ks = tmp.resolve("acc.jks");
        KeystoreManager mgr = new KeystoreManager(keytoolPath, ks, STORE_PASS);
        assertEquals(keytoolPath, mgr.getKeytoolPath());
        assertEquals(ks.toAbsolutePath().normalize(), mgr.getKeystorePath());
    }
}
