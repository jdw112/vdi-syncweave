/*
 * Copyright contributors to the SyncWeave project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.certmgr.remote;

import com.ibm.di.certmgr.keystore.KeystoreManager;
import com.ibm.di.certmgr.model.CertChainDownloadResult;
import com.ibm.di.certmgr.model.ChainImportResult;
import com.ibm.di.certmgr.model.DownloadedCertInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Imports a downloaded certificate chain into a {@link KeystoreManager}-managed keystore.
 *
 * <p>Mirrors Python {@code SdiRemoteCertClient.ingest_chain()} and the
 * standalone helpers {@code build_chain_aliases()} and {@code delete_alias_chain()}.
 *
 * <p>Alias naming scheme (mirrors Python):
 * <pre>
 *   cert_count=1  → ["myalias"]
 *   cert_count=3  → ["myalias", "myalias-1", "myalias-2"]
 * </pre>
 *
 * <p>When {@code replaceExisting=true} a keystore backup is created first.
 * If any import fails, the original keystore is restored from the backup.
 */
public class CertChainIngester {

    private static final Logger log = LogManager.getLogger(CertChainIngester.class);

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Import a certificate chain into the keystore managed by {@code ksManager}.
     *
     * @param pemChain        ordered list of PEM-encoded certificate bytes (leaf first)
     * @param ksManager       target keystore manager
     * @param baseAlias       base alias name; chain entries are named
     *                        {@code baseAlias}, {@code baseAlias-1}, …
     * @param replaceExisting when true delete any existing alias/chain entries first
     *                        (with prior backup and rollback on failure)
     * @return import result
     */
    public ChainImportResult ingest(
            final List<byte[]> pemChain,
            final KeystoreManager ksManager,
            final String baseAlias,
            final boolean replaceExisting) {

        if (pemChain == null || pemChain.isEmpty()) {
            return ChainImportResult.failure(List.of(), null, "No certificates to import");
        }

        // Optionally backup + delete existing chain
        String backupPath = null;
        if (replaceExisting) {
            Optional<Path> bp = ksManager.backupKeystore();
            if (bp.isEmpty()) {
                return ChainImportResult.failure(List.of(), null,
                        "Failed to backup keystore before replacement");
            }
            backupPath = bp.get().toString();
            log.info("Deleting existing alias '{}' and chain entries...", baseAlias);
            deleteAliasChain(ksManager, baseAlias);
        }

        List<String> chainAliases = buildChainAliases(baseAlias, pemChain.size());
        List<String> imported     = new ArrayList<>();

        for (int i = 0; i < pemChain.size(); i++) {
            Path tempFile = null;
            try {
                tempFile = Files.createTempFile("certmgr-chain-", ".crt");
                Files.write(tempFile, pemChain.get(i));

                String alias = chainAliases.get(i);
                if (ksManager.importCertificate(alias, tempFile)) {
                    imported.add(alias);
                    log.info("Imported certificate as alias '{}'", alias);
                } else {
                    String err = "Failed to import certificate as '" + alias + "'";
                    log.error(err);
                    if (replaceExisting && backupPath != null) {
                        ksManager.restoreKeystore(Path.of(backupPath));
                    }
                    return ChainImportResult.failure(imported, alias, err);
                }
            } catch (IOException e) {
                String err = "IO error saving certificate " + (i + 1) + ": " + e.getMessage();
                log.error(err);
                if (replaceExisting && backupPath != null) {
                    ksManager.restoreKeystore(Path.of(backupPath));
                }
                return ChainImportResult.failure(imported, chainAliases.get(i), err);
            } finally {
                if (tempFile != null) {
                    try { Files.deleteIfExists(tempFile); } catch (IOException ignored) { }
                }
            }
        }

        return ChainImportResult.success(imported, backupPath);
    }

    /**
     * Convenience overload accepting a {@link CertChainDownloadResult}.
     */
    public ChainImportResult ingest(
            final CertChainDownloadResult download,
            final KeystoreManager ksManager,
            final String baseAlias,
            final boolean replaceExisting) {
        if (!download.success()) {
            return ChainImportResult.failure(List.of(), null,
                    "Download failed: " + download.errorMessage());
        }
        return ingest(download.pemList(), ksManager, baseAlias, replaceExisting);
    }

    // -----------------------------------------------------------------------
    // Static helpers (package-visible for tests)
    // -----------------------------------------------------------------------

    /**
     * Build alias names for a certificate chain.
     * First cert gets {@code baseAlias}; subsequent certs get {@code baseAlias-N}.
     *
     * @param baseAlias  base alias name
     * @param certCount  number of certificates in the chain
     * @return ordered list of alias names; empty when certCount &lt;= 0
     */
    static List<String> buildChainAliases(final String baseAlias, final int certCount) {
        if (certCount <= 0) return List.of();
        List<String> aliases = new ArrayList<>(certCount);
        aliases.add(baseAlias);
        for (int i = 1; i < certCount; i++) {
            aliases.add(baseAlias + "-" + i);
        }
        return aliases;
    }

    /**
     * Delete all aliases matching {@code baseAlias} or {@code baseAlias-N}
     * from the keystore (both key entries and trusted certificate entries).
     *
     * @param ksManager keystore manager
     * @param baseAlias base alias pattern
     */
    static void deleteAliasChain(final KeystoreManager ksManager, final String baseAlias) {
        Map<String, List<String>> entries = ksManager.listEntries();
        Pattern pattern = Pattern.compile(
                "^" + Pattern.quote(baseAlias) + "(?:-(\\d+))?$");

        List<String> toDelete = new ArrayList<>();
        for (String alias : entries.get("keyEntry"))         { if (pattern.matcher(alias).matches()) toDelete.add(alias); }
        for (String alias : entries.get("trustedCertEntry")) { if (pattern.matcher(alias).matches()) toDelete.add(alias); }

        for (String alias : toDelete) {
            ksManager.deleteEntry(alias);
        }
    }
}
