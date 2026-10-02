/*
 * Copyright contributors to the SyncWeave project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.certmgr.regen;

import com.ibm.di.certmgr.keystore.KeystoreManager;
import com.ibm.di.certmgr.model.AppContext;
import com.ibm.di.certmgr.model.RegenerationResult;
import com.ibm.di.certmgr.model.RegenerationSpec;
import com.ibm.di.certmgr.properties.SdiPropertiesStore;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * Pure execution engine for the 10-step SDI certificate regeneration workflow.
 *
 * <p>Mirrors Python {@code execute_sdi_cert_regeneration}. This class is
 * intentionally free of I/O prompting: callers supply a fully-populated
 * {@link RegenerationSpec}. An optional {@code progressCallback} is invoked
 * before each step with {@code (stepName, stepNumber)}.
 *
 * <p>Steps executed:
 * <ol>
 *   <li>Backup keystores + properties to a timestamped {@code .tar.gz}</li>
 *   <li>Decrypt {@code solution.properties} via {@code cryptoutils}</li>
 *   <li>Change keystore passwords</li>
 *   <li>Delete old certificate aliases</li>
 *   <li>Generate new self-signed certificates</li>
 *   <li>Update {@code serverapi/registry.txt} with admin DN</li>
 *   <li>Export and cross-import certificates between keystores</li>
 *   <li>Update decrypted properties file with new passwords</li>
 *   <li>Re-encrypt properties file</li>
 *   <li>Create new {@code idisrv.sth} stash file</li>
 * </ol>
 *
 * <p>On any failure the engine logs the error and returns a failed
 * {@link RegenerationResult}. All password arrays are used as received;
 * zeroing is the caller's responsibility.
 */
public class RegenerationEngine {

    private static final Logger log = LogManager.getLogger(RegenerationEngine.class);

    private final AppContext ctx;

    public RegenerationEngine(final AppContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Execute the full 10-step regeneration workflow.
     *
     * @param spec             all inputs for the regeneration
     * @param progressCallback optional callback invoked as
     *                         {@code (stepName, stepNumber)} before each step;
     *                         pass {@code null} to skip
     * @return result record describing success/failure and produced artifacts
     */
    public RegenerationResult execute(
            final RegenerationSpec spec,
            final BiConsumer<String, Integer> progressCallback) {

        List<String> changes = new ArrayList<>();
        Path backupPath      = null;

        SdiPropertiesStore store;
        try {
            store = new SdiPropertiesStore(spec.mode(), spec.baseDir(), spec.installDir());
        } catch (Exception e) {
            return failed(spec, changes, "Failed to initialise properties store: " + e.getMessage());
        }

        Map<String, Path> ksNames = store.getKeystorePaths();
        Path serverJks            = ksNames.get("server");
        Path adminJks             = ksNames.get("admin");
        Path propertiesFile       = store.getPropertiesFile();
        Path propertiesFilePlain  = store.getPlainPropertiesFile();

        if (!Files.exists(serverJks)) {
            return failed(spec, changes, "Server keystore not found: " + serverJks);
        }
        if (!Files.exists(adminJks)) {
            return failed(spec, changes, "Admin keystore not found: " + adminJks);
        }

        // ---------------------------------------------------------------
        // Step 1: Backup
        // ---------------------------------------------------------------
        notify(progressCallback, "Backing up resources", 1);
        try {
            BackupManager bm = new BackupManager(Path.of(spec.baseDir()), spec.installDir() != null ? Path.of(spec.installDir()) : null);
            Optional<Path> bp = bm.createArchive(List.of(serverJks, adminJks, propertiesFile));
            if (bp.isEmpty()) {
                return failed(spec, changes, "Failed to create backup archive");
            }
            backupPath = bp.get();
            changes.add("Created backup archive: " + backupPath);
            log.info("Backup created: {}", backupPath);
        } catch (IOException e) {
            return failed(spec, changes, "Backup failed: " + e.getMessage());
        }

        // ---------------------------------------------------------------
        // Step 2: Decrypt properties
        // ---------------------------------------------------------------
        notify(progressCallback, "Decrypting " + spec.mode().getPropertiesFilename(), 2);
        try {
            boolean decOk = store.decrypt(serverJks, spec.oldServerPass(), "server");
            if (!decOk) {
                return failed(spec, changes, "Failed to decrypt " + spec.mode().getPropertiesFilename());
            }
            if (!Files.exists(propertiesFilePlain)) {
                return failed(spec, changes,
                        "Decrypt reported success but plain file was not created: " + propertiesFilePlain);
            }
            changes.add("Decrypted: " + propertiesFilePlain);
        } catch (Exception e) {
            return failed(spec, changes, "Decrypt error: " + e.getMessage());
        }

        // ---------------------------------------------------------------
        // Step 3: Change passwords
        // ---------------------------------------------------------------
        notify(progressCallback, "Changing keystore passwords", 3);
        try {
            KeystoreManager serverKs = new KeystoreManager(
                    ctx.keytoolPath(), serverJks,
                    Arrays.copyOf(spec.oldServerPass(), spec.oldServerPass().length));
            KeystoreManager adminKs  = new KeystoreManager(
                    ctx.keytoolPath(), adminJks,
                    Arrays.copyOf(spec.oldAdminPass(),  spec.oldAdminPass().length));

            Map<String, List<String>> adminEntries  = adminKs.listEntries();
            Map<String, List<String>> serverEntries = serverKs.listEntries();
            List<String> adminAliases  = allAliases(adminEntries);
            List<String> serverAliases = allAliases(serverEntries);

            if (adminEntries.get("keyEntry").size() > 1) {
                log.warn("testadmin.jks contains more than one private key: {}. "
                       + "Set api.key.alias in your properties file.",
                         adminEntries.get("keyEntry"));
            }
            if (serverEntries.get("keyEntry").size() > 1) {
                log.warn("testserver.jks contains more than one private key: {}. "
                       + "Set api.key.alias in your properties file.",
                         serverEntries.get("keyEntry"));
            }

            if (adminAliases.contains("admin")) {
                if (!adminKs.changeKeyPassword("admin", spec.oldAdminPass(), spec.newAdminPass())) {
                    return failed(spec, changes, "Failed to change admin key password");
                }
            }
            if (serverAliases.contains("server")) {
                if (!serverKs.changeKeyPassword("server", spec.oldServerPass(), spec.newServerPass())) {
                    return failed(spec, changes, "Failed to change server key password");
                }
            }
            if (!adminKs.changeStorePassword(spec.newAdminPass())) {
                return failed(spec, changes, "Failed to change testadmin.jks store password");
            }
            if (!serverKs.changeStorePassword(spec.newServerPass())) {
                return failed(spec, changes, "Failed to change testserver.jks store password");
            }
            changes.add("Changed keystore passwords");

            // ---------------------------------------------------------------
            // Step 4: Delete old aliases
            // ---------------------------------------------------------------
            notify(progressCallback, "Deleting old certificate aliases", 4);
            serverKs.deleteEntry("server");
            serverKs.deleteEntry("admin");
            adminKs.deleteEntry("admin");
            adminKs.deleteEntry("server");
            changes.add("Deleted old certificate aliases");

            // ---------------------------------------------------------------
            // Step 5: Generate new certificates
            // ---------------------------------------------------------------
            notify(progressCallback, "Generating new self-signed certificates", 5);
            String  dname    = spec.dname();
            Integer validity = spec.validityDays();
            Integer keysize  = spec.keysize();
            String  keyalg   = spec.keyalg();
            List<String> san = spec.sanValues();

            if (!serverKs.generateKeypair("server", spec.newServerPass(),
                    dname, validity, keysize, keyalg, san)) {
                return failed(spec, changes, "Failed to generate server certificate");
            }
            if (!adminKs.generateKeypair("admin", spec.newAdminPass(),
                    dname, validity, keysize, keyalg, san)) {
                return failed(spec, changes, "Failed to generate admin certificate");
            }
            changes.add("Generated new self-signed certificates");

            // ---------------------------------------------------------------
            // Step 6: Update registry.txt
            // ---------------------------------------------------------------
            notify(progressCallback, "Updating serverapi/registry.txt", 6);
            Optional<String> dn = adminKs.getCertificateDn("admin");
            String adminDn = dn.orElse(dname != null ? dname : "");
            RegistryFileUpdater rfu = new RegistryFileUpdater();
            if (rfu.update(Path.of(spec.baseDir()), adminDn, "admin")) {
                changes.add("Updated registry.txt with admin DN: " + adminDn);
            } else {
                log.warn("Failed to update registry.txt — continuing");
            }

            // ---------------------------------------------------------------
            // Step 7: Export and cross-import certificates
            // ---------------------------------------------------------------
            notify(progressCallback, "Exporting and cross-importing certificates", 7);
            Path adminCert  = Files.createTempFile("certmgr-admin-",  ".der");
            Path serverCert = Files.createTempFile("certmgr-server-", ".der");
            try {
                if (!adminKs.exportCertificate("admin", adminCert)) {
                    return failed(spec, changes, "Failed to export admin certificate");
                }
                if (!serverKs.exportCertificate("server", serverCert)) {
                    return failed(spec, changes, "Failed to export server certificate");
                }
                if (!serverKs.importCertificate("admin", adminCert)) {
                    return failed(spec, changes, "Failed to import admin cert into testserver.jks");
                }
                if (!adminKs.importCertificate("server", serverCert)) {
                    return failed(spec, changes, "Failed to import server cert into testadmin.jks");
                }
            } finally {
                Files.deleteIfExists(adminCert);
                Files.deleteIfExists(serverCert);
            }
            changes.add("Cross-imported certificates");

            // ---------------------------------------------------------------
            // Step 8: Update plain properties file
            // ---------------------------------------------------------------
            notify(progressCallback, "Updating " + spec.mode().getPropertiesFilename(), 8);
            try {
                store.updatePasswords(
                        spec.oldServerPass(), spec.newServerPass(),
                        spec.oldAdminPass(),  spec.newAdminPass());
            } catch (IOException ioe) {
                return failed(spec, changes, "Failed to update " + spec.mode().getPropertiesFilename() + ": " + ioe.getMessage());
            }
            changes.add("Updated " + spec.mode().getPropertiesFilename() + " with new passwords");

            // ---------------------------------------------------------------
            // Step 9: Re-encrypt properties file
            // ---------------------------------------------------------------
            notify(progressCallback, "Encrypting " + spec.mode().getPropertiesFilename(), 9);
            if (!store.encrypt(serverJks, spec.newServerPass(), "server")) {
                return failed(spec, changes, "Failed to re-encrypt " + spec.mode().getPropertiesFilename());
            }
            changes.add("Re-encrypted " + spec.mode().getPropertiesFilename());

            // ---------------------------------------------------------------
            // Step 10: Create stash file
            // ---------------------------------------------------------------
            notify(progressCallback, "Creating stash file", 10);
            Path stashFile = store.getStashFile();
            if (!store.createStash(spec.newServerPass())) {
                log.warn("Failed to create idisrv.sth stash file — continuing");
            } else {
                changes.add("Created idisrv.sth stash file");
            }

            // Cleanup plaintext properties
            store.cleanupPlainFile();

            log.info("Certificate regeneration completed ({} changes)", changes.size());
            return RegenerationResult.success(
                    spec.mode(), spec.dryRun(),
                    backupPath != null ? backupPath.toString() : null,
                    propertiesFile, propertiesFilePlain,
                    stashFile, changes);

        } catch (IOException e) {
            log.error("Operation failed: {}", e.getMessage());
            return failed(spec, changes, e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error during regeneration", e);
            return failed(spec, changes, "Unexpected error: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static void notify(final BiConsumer<String, Integer> cb,
                                final String name, final int step) {
        log.info("[Step {}/10] {}", step, name);
        if (cb != null) cb.accept(name, step);
    }

    private static List<String> allAliases(final Map<String, List<String>> entries) {
        List<String> all = new ArrayList<>();
        all.addAll(entries.get("keyEntry"));
        all.addAll(entries.get("trustedCertEntry"));
        return all;
    }

    private RegenerationResult failed(final RegenerationSpec spec,
                                      final List<String> changes,
                                      final String message) {
        log.error("Regeneration failed: {}", message);
        return RegenerationResult.failure(spec.mode(), message, changes);
    }
}
