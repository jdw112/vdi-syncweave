/*
 * Copyright contributors to the SyncWeave project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.certmgr.workflow;

import com.ibm.di.certmgr.keystore.KeystoreManager;
import com.ibm.di.certmgr.regen.RegistryFileUpdater;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pure execution engine for importing a CA-issued private key and certificate
 * chain from a PKCS12 / PFX file into an SDI keystore pair.
 *
 * <p>Mirrors Python {@code import_ca_key_workflow}. Steps:
 * <ol>
 *   <li>Backup target keystore (and other keystore if present)</li>
 *   <li>Delete existing alias in target if present</li>
 *   <li>Import private key + chain from PKCS12 into target</li>
 *   <li>Export new public certificate and cross-import into other keystore</li>
 *   <li>If admin side: read Subject DN and update {@code serverapi/registry.txt}</li>
 * </ol>
 *
 * <p>This class is intentionally UI-free. The caller supplies a fully-populated
 * {@link CaKeyImportSpec} and receives a {@link CaKeyImportResult}.
 *
 * <p>Password arrays are used as-is; zeroing is the caller's responsibility.
 */
public class CaKeyImportWorkflow {

    private static final Logger log = LogManager.getLogger(CaKeyImportWorkflow.class);

    private final RegistryFileUpdater registryUpdater;

    public CaKeyImportWorkflow() {
        this(new RegistryFileUpdater());
    }

    /** Testable constructor accepting a custom {@link RegistryFileUpdater}. */
    CaKeyImportWorkflow(final RegistryFileUpdater registryUpdater) {
        this.registryUpdater = registryUpdater;
    }

    /**
     * Execute the CA key import workflow.
     *
     * @param spec  fully-populated import specification
     * @return result record; never null
     */
    public CaKeyImportResult execute(final CaKeyImportSpec spec) {
        List<String> changes = new ArrayList<>();

        // ── Step 1: build KeystoreManager instances ──────────────────────────
        KeystoreManager targetKs = new KeystoreManager(
                spec.keytoolPath(), spec.targetJks(), spec.targetStorepass());

        // Validate the target keystore is accessible
        if (!Files.exists(spec.targetJks())) {
            return CaKeyImportResult.failure(changes,
                    "Target keystore not found: " + spec.targetJks());
        }

        // ── Step 2: backup ───────────────────────────────────────────────────
        Optional<Path> backup = targetKs.backupKeystore();
        if (backup.isEmpty()) {
            return CaKeyImportResult.failure(changes,
                    "Failed to backup target keystore: " + spec.targetJks());
        }
        changes.add("Backed up target keystore: " + backup.get());

        // ── Step 3: delete existing alias if present ─────────────────────────
        Map<String, List<String>> existing = targetKs.listEntries();
        List<String> allExisting = new ArrayList<>();
        allExisting.addAll(existing.get("keyEntry"));
        allExisting.addAll(existing.get("trustedCertEntry"));

        if (allExisting.contains(spec.destAlias())) {
            log.info("Deleting existing alias '{}' from target keystore", spec.destAlias());
            if (!targetKs.deleteEntry(spec.destAlias())) {
                return CaKeyImportResult.failure(changes,
                        "Could not delete existing alias '" + spec.destAlias() + "'");
            }
            changes.add("Deleted existing alias '" + spec.destAlias() + "'");
        }

        // ── Step 4: import PKCS12 ────────────────────────────────────────────
        log.info("Importing PKCS12 into {} as alias '{}'",
                spec.targetJks().getFileName(), spec.destAlias());
        if (!targetKs.importPkcs12(spec.p12Path(), spec.p12Pass(),
                spec.destAlias(), spec.srcAlias())) {
            return CaKeyImportResult.failure(changes, "PKCS12 import failed");
        }
        changes.add("Imported PKCS12 '" + spec.p12Path().getFileName()
                + "' as alias '" + spec.destAlias() + "'");

        // ── Step 5: cross-import public cert into other keystore ─────────────
        if (spec.otherJks() != null && Files.exists(spec.otherJks())) {
            Path tempCert = null;
            try {
                tempCert = Files.createTempFile("certmgr-ca-import-", ".pem");
                if (targetKs.exportCertificate(spec.destAlias(), tempCert)) {
                    String crossAlias = spec.destAlias() + "-signer";

                    // Build other-side manager using same password (SDI uses shared store pass)
                    KeystoreManager otherKs = new KeystoreManager(
                            spec.keytoolPath(), spec.otherJks(),
                            spec.otherStorepass() != null ? spec.otherStorepass() : spec.targetStorepass());
                    otherKs.deleteEntry(crossAlias);  // remove stale if present

                    if (otherKs.importCertificate(crossAlias, tempCert)) {
                        changes.add("Cross-imported public cert as '" + crossAlias
                                + "' into " + spec.otherJks().getFileName());
                        log.info("Cross-imported '{}' into {}", crossAlias,
                                spec.otherJks().getFileName());
                    } else {
                        log.warn("Cross-import into {} failed — mutual TLS trust may be broken",
                                spec.otherJks().getFileName());
                    }
                } else {
                    log.warn("Could not export certificate '{}' for cross-import", spec.destAlias());
                }
            } catch (IOException e) {
                log.warn("IO error during cross-import: {}", e.getMessage());
            } finally {
                if (tempCert != null) {
                    try { Files.deleteIfExists(tempCert); } catch (IOException ignored) { }
                }
            }
        } else {
            log.info("Other keystore not present at {} — skipping cross-import",
                    spec.otherJks());
        }

        // ── Step 6: admin side — read DN and update registry.txt ─────────────
        String adminDn = null;
        boolean regUpdated = false;

        if (spec.isAdminSide()) {
            Optional<String> dn = targetKs.getCertificateDn(spec.destAlias());
            if (dn.isPresent()) {
                adminDn = dn.get();
                log.info("Admin Subject DN: {}", adminDn);
                if (spec.baseDir() != null) {
                    regUpdated = registryUpdater.update(spec.baseDir(), adminDn, "admin");
                    if (regUpdated) {
                        changes.add("Updated registry.txt with DN: " + adminDn);
                    } else {
                        log.warn("registry.txt update failed — update manually");
                    }
                }
            } else {
                log.warn("Could not read Subject DN from '{}' — update registry.txt manually",
                        spec.destAlias());
            }
        }

        log.info("CA key import complete ({} steps)", changes.size());
        return CaKeyImportResult.success(changes, adminDn, regUpdated);
    }
}
