/*
 * Copyright contributors to the SyncWeave project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.certmgr.remote;

import com.ibm.di.certmgr.keystore.KeystoreManager;
import com.ibm.di.certmgr.model.CertChainDownloadResult;
import com.ibm.di.certmgr.model.ChainImportResult;
import com.ibm.di.certmgr.model.DownloadedCertInfo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RemoteCertClient} (pure helpers) and
 * {@link CertChainIngester} (integration tests against real keytool).
 *
 * Network-dependent tests ({@code fetchChain}, {@code fetchChainWithOpenssl})
 * are excluded from this suite to keep CI offline-capable.
 *
 * Coverage:
 *   RemoteCertClient:
 *     - parsePemBlocks: single cert, multiple certs, no certs, malformed input
 *     - fingerprintSha256: deterministic output, colon-separated uppercase
 *     - derToPem / pemToDer round-trip
 *   CertChainIngester:
 *     - buildChainAliases: 0, 1, 3 certs
 *     - deleteAliasChain: removes base + numbered aliases, leaves others
 *     - ingest: empty chain rejected, single cert imported, multi-cert chain,
 *               replaceExisting backup+restore on failure
 *     - ingest(CertChainDownloadResult): failed download propagated
 */
class RemoteCertClientTest {

    private static final char[] STORE_PASS = "Test1234!".toCharArray();
    private static final char[] KEY_PASS   = "Test1234!".toCharArray();
    private static final String DNAME      = "CN=Test, O=IBM, C=US";

    private static String keytoolPath;

    private final RemoteCertClient client   = new RemoteCertClient();
    private final CertChainIngester ingester = new CertChainIngester();

    @BeforeAll
    static void resolveKeytool() {
        String javaHome = System.getProperty("java.home");
        Path kt = Path.of(javaHome, "bin", "keytool");
        if (!Files.exists(kt)) kt = Path.of(javaHome, "bin", "keytool.exe");
        assertTrue(Files.exists(kt), "keytool not found under java.home=" + javaHome);
        keytoolPath = kt.toString();
    }

    // -----------------------------------------------------------------------
    // Helper: create a seeded JKS with one keypair
    // -----------------------------------------------------------------------

    private KeystoreManager createJks(Path dir, String alias) {
        Path ks = dir.resolve(alias + ".jks");
        KeystoreManager mgr = new KeystoreManager(keytoolPath, ks, STORE_PASS);
        assertTrue(mgr.generateKeypair(alias, KEY_PASS, DNAME, 365, 2048, "RSA", null),
                "Precondition: generateKeypair for " + alias);
        return mgr;
    }

    // -----------------------------------------------------------------------
    // parsePemBlocks
    // -----------------------------------------------------------------------

    @Test
    void parsePemBlocksSingleCert() {
        String input = "some garbage\n"
                + "-----BEGIN CERTIFICATE-----\n"
                + "AAAA\n"
                + "-----END CERTIFICATE-----\n"
                + "more garbage";
        List<byte[]> blocks = client.parsePemBlocks(input);
        assertEquals(1, blocks.size());
        String result = new String(blocks.get(0), StandardCharsets.US_ASCII);
        assertTrue(result.contains("-----BEGIN CERTIFICATE-----"));
        assertTrue(result.contains("AAAA"));
        assertTrue(result.contains("-----END CERTIFICATE-----"));
    }

    @Test
    void parsePemBlocksMultipleCerts() {
        String input = "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n"
                     + "-----BEGIN CERTIFICATE-----\nBBBB\n-----END CERTIFICATE-----\n";
        assertEquals(2, client.parsePemBlocks(input).size());
    }

    @Test
    void parsePemBlocksNoCerts() {
        assertTrue(client.parsePemBlocks("no certificates here").isEmpty());
    }

    @Test
    void parsePemBlocksEmptyInput() {
        assertTrue(client.parsePemBlocks("").isEmpty());
    }

    @Test
    void parsePemBlocksIgnoresPartialBlock() {
        String input = "-----BEGIN CERTIFICATE-----\nAAA\n(no end marker)";
        assertTrue(client.parsePemBlocks(input).isEmpty());
    }

    // -----------------------------------------------------------------------
    // fingerprintSha256
    // -----------------------------------------------------------------------

    @Test
    void fingerprintSha256IsDeterministic() {
        byte[] der = "fake-der-data".getBytes(StandardCharsets.UTF_8);
        String fp1 = client.fingerprintSha256(der);
        String fp2 = client.fingerprintSha256(der);
        assertEquals(fp1, fp2);
    }

    @Test
    void fingerprintSha256FormatIsColonSeparatedUppercase() {
        byte[] der = "fake-der-data".getBytes(StandardCharsets.UTF_8);
        String fp = client.fingerprintSha256(der);
        // Should be XX:XX:XX... pattern
        assertTrue(fp.matches("[0-9A-F]{2}(:[0-9A-F]{2})+"),
                "Fingerprint should be colon-separated uppercase hex, was: " + fp);
        // SHA-256 = 32 bytes = 64 hex chars + 31 colons = 95 chars
        assertEquals(95, fp.length());
    }

    @Test
    void fingerprintSha256DiffersForDifferentInput() {
        byte[] der1 = "data1".getBytes(StandardCharsets.UTF_8);
        byte[] der2 = "data2".getBytes(StandardCharsets.UTF_8);
        assertNotEquals(client.fingerprintSha256(der1), client.fingerprintSha256(der2));
    }

    // -----------------------------------------------------------------------
    // derToPem / pemToDer round-trip
    // -----------------------------------------------------------------------

    @Test
    void derToPemRoundTrip() {
        byte[] originalDer = "fake-der-bytes-1234567890".getBytes(StandardCharsets.UTF_8);
        byte[] pem = RemoteCertClient.derToPem(originalDer);
        byte[] recoveredDer = RemoteCertClient.pemToDer(pem);
        assertArrayEquals(originalDer, recoveredDer);
    }

    @Test
    void derToPemContainsHeaders() {
        byte[] pem = RemoteCertClient.derToPem(new byte[]{1, 2, 3});
        String s = new String(pem, StandardCharsets.US_ASCII);
        assertTrue(s.contains("-----BEGIN CERTIFICATE-----"));
        assertTrue(s.contains("-----END CERTIFICATE-----"));
    }

    // -----------------------------------------------------------------------
    // buildChainAliases
    // -----------------------------------------------------------------------

    @Test
    void buildChainAliasesZero() {
        assertTrue(CertChainIngester.buildChainAliases("alias", 0).isEmpty());
    }

    @Test
    void buildChainAliasesOne() {
        assertEquals(List.of("myalias"),
                CertChainIngester.buildChainAliases("myalias", 1));
    }

    @Test
    void buildChainAliasesThree() {
        assertEquals(List.of("myalias", "myalias-1", "myalias-2"),
                CertChainIngester.buildChainAliases("myalias", 3));
    }

    @Test
    void buildChainAliasesNegativeReturnsEmpty() {
        assertTrue(CertChainIngester.buildChainAliases("alias", -1).isEmpty());
    }

    // -----------------------------------------------------------------------
    // deleteAliasChain
    // -----------------------------------------------------------------------

    @Test
    void deleteAliasChainRemovesBaseAndNumbered(@TempDir Path tmp) throws IOException {
        KeystoreManager mgr = createJks(tmp, "server");
        // Export and import chain certs under "chain", "chain-1", "chain-2"
        Path pem = tmp.resolve("cert.pem");
        mgr.exportCertificate("server", pem);
        mgr.importCertificate("chain",   pem);
        mgr.importCertificate("chain-1", pem);
        mgr.importCertificate("chain-2", pem);

        CertChainIngester.deleteAliasChain(mgr, "chain");

        List<String> trusted = mgr.listEntries().get("trustedCertEntry");
        assertFalse(trusted.contains("chain"));
        assertFalse(trusted.contains("chain-1"));
        assertFalse(trusted.contains("chain-2"));
    }

    @Test
    void deleteAliasChainLeavesOtherAliases(@TempDir Path tmp) throws IOException {
        KeystoreManager mgr = createJks(tmp, "server");
        Path pem = tmp.resolve("cert.pem");
        mgr.exportCertificate("server", pem);
        mgr.importCertificate("keep-this", pem);
        mgr.importCertificate("chain",     pem);

        CertChainIngester.deleteAliasChain(mgr, "chain");

        assertTrue(mgr.listEntries().get("trustedCertEntry").contains("keep-this"),
                "Unrelated alias 'keep-this' must survive");
    }

    // -----------------------------------------------------------------------
    // CertChainIngester.ingest
    // -----------------------------------------------------------------------

    @Test
    void ingestEmptyChainReturnsFailure(@TempDir Path tmp) {
        KeystoreManager mgr = createJks(tmp, "server");
        ChainImportResult r = ingester.ingest(List.of(), mgr, "imported", false);
        assertFalse(r.success());
        assertNotNull(r.errorMessage());
    }

    @Test
    void ingestNullChainReturnsFailure(@TempDir Path tmp) {
        KeystoreManager mgr = createJks(tmp, "server");
        ChainImportResult r = ingester.ingest((List<byte[]>) null, mgr, "imported", false);
        assertFalse(r.success());
    }

    @Test
    void ingestSingleCertSucceeds(@TempDir Path tmp) throws IOException {
        KeystoreManager mgr = createJks(tmp, "server");
        // Export the server cert as PEM and re-import it under a new alias
        Path pem = tmp.resolve("server.pem");
        mgr.exportCertificate("server", pem);
        byte[] pemBytes = Files.readAllBytes(pem);

        ChainImportResult r = ingester.ingest(List.of(pemBytes), mgr, "imported", false);

        assertTrue(r.success(), "Single cert import should succeed");
        assertEquals(List.of("imported"), r.importedAliases());
        assertTrue(mgr.listEntries().get("trustedCertEntry").contains("imported"));
    }

    @Test
    void ingestMultiCertChainNamesAliasesCorrectly(@TempDir Path tmp) throws IOException {
        KeystoreManager mgr = createJks(tmp, "server");
        Path pem = tmp.resolve("server.pem");
        mgr.exportCertificate("server", pem);
        byte[] pemBytes = Files.readAllBytes(pem);

        // Ingest same cert three times (simulates a chain)
        ChainImportResult r = ingester.ingest(
                List.of(pemBytes, pemBytes, pemBytes), mgr, "chain", false);

        assertTrue(r.success());
        assertEquals(List.of("chain", "chain-1", "chain-2"), r.importedAliases());
    }

    @Test
    void ingestReplaceExistingCreatesBackup(@TempDir Path tmp) throws IOException {
        KeystoreManager mgr = createJks(tmp, "server");
        Path pem = tmp.resolve("server.pem");
        mgr.exportCertificate("server", pem);
        byte[] pemBytes = Files.readAllBytes(pem);

        // First import
        ingester.ingest(List.of(pemBytes), mgr, "chain", false);

        // Replace — should backup first
        ChainImportResult r = ingester.ingest(List.of(pemBytes), mgr, "chain", true);

        assertTrue(r.success());
        assertNotNull(r.backupPath(), "Backup path should be set when replaceExisting=true");
        assertTrue(Files.exists(Path.of(r.backupPath())), "Backup file should exist");
    }

    @Test
    void ingestFromFailedDownloadReturnsFailure(@TempDir Path tmp) {
        KeystoreManager mgr = createJks(tmp, "server");
        CertChainDownloadResult failed = CertChainDownloadResult.failure(
                "example.com", 443, "Connection refused");

        ChainImportResult r = ingester.ingest(failed, mgr, "chain", false);

        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("Download failed"));
    }

    @Test
    void ingestFromSuccessfulDownloadResult(@TempDir Path tmp) throws IOException {
        KeystoreManager mgr = createJks(tmp, "server");
        Path pem = tmp.resolve("server.pem");
        mgr.exportCertificate("server", pem);
        byte[] pemBytes = Files.readAllBytes(pem);

        DownloadedCertInfo info = DownloadedCertInfo.of(1, pemBytes, "fp");
        CertChainDownloadResult download = CertChainDownloadResult.success(
                "example.com", 443, List.of(info));

        ChainImportResult r = ingester.ingest(download, mgr, "dl-cert", false);

        assertTrue(r.success());
        assertEquals(List.of("dl-cert"), r.importedAliases());
    }
}
