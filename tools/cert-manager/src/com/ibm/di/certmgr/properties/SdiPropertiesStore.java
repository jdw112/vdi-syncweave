/*
 * Copyright contributors to the SyncWeave project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.certmgr.properties;

import com.ibm.di.certmgr.model.DirectoryMode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Unified properties and cryptographic configuration store for SDI.
 *
 * <p>Facade over {@link PropertiesFileHandler} and {@link PropertiesPasswordUpdater}
 * that also delegates to the external SDI scripts ({@code cryptoutils},
 * {@code createstash}) via {@link SdiScriptRunner}.
 *
 * <p>Mirrors Python {@code SdiPropertiesStore}.
 */
public class SdiPropertiesStore {

    private static final Logger log = LogManager.getLogger(SdiPropertiesStore.class);

    private final PropertiesFileHandler handler;
    private final DirectoryMode mode;
    private final Path baseDir;
    private final String installDir;

    /**
     * @param mode       INSTALL or SOLUTION
     * @param baseDir    base directory for the selected mode
     * @param installDir SDI install directory (required for cryptoutils/createstash);
     *                   defaults to baseDir when mode is INSTALL
     */
    public SdiPropertiesStore(
            final DirectoryMode mode,
            final Path baseDir,
            final String installDir) {
        this.handler    = new PropertiesFileHandler(mode, baseDir);
        this.mode       = mode;
        this.baseDir    = baseDir.toAbsolutePath().normalize();
        this.installDir = (installDir != null && !installDir.isBlank())
                ? installDir
                : (mode == DirectoryMode.INSTALL ? baseDir.toString() : "");
    }

    public SdiPropertiesStore(final DirectoryMode mode, final String baseDir, final String installDir) {
        this(mode, Path.of(baseDir), installDir);
    }

    // -------------------------------------------------------------------------
    // Path accessors
    // -------------------------------------------------------------------------

    /** Path to the main (encrypted) properties file. */
    public Path getPropertiesFile()      { return handler.getPropertiesFilePath(); }

    /** Path to the temporary decrypted copy. */
    public Path getPlainPropertiesFile() { return handler.getPropertiesFilePlainPath(); }

    /** Path to the stash file ({@code idisrv.sth}). */
    public Path getStashFile()           { return baseDir.resolve("idisrv.sth"); }

    /** Resolved keystore paths ({@code "server"} and {@code "admin"}). */
    public Map<String, Path> getKeystorePaths() { return handler.readKeystoreNames(); }

    /** Files to include in a backup. */
    public List<Path> getBackupFiles() { return handler.getBackupFiles(); }

    // -------------------------------------------------------------------------
    // Decrypt / encrypt via cryptoutils
    // -------------------------------------------------------------------------

    /**
     * Decrypt the properties file to the plain file using {@code cryptoutils}.
     *
     * @param keystorePath path to the server keystore
     * @param storepass    keystore password (caller zeros after use)
     * @param alias        key alias (default "server")
     * @return true on success
     */
    public boolean decrypt(final Path keystorePath, final char[] storepass,
                           final String alias) {
        return SdiScriptRunner.runCryptoutil(
                installDir, "decrypt_props",
                getPropertiesFile(), getPlainPropertiesFile(),
                keystorePath, storepass, alias);
    }

    /**
     * Encrypt the plain file back to the properties file using {@code cryptoutils}.
     *
     * @param keystorePath path to the server keystore
     * @param storepass    keystore password (caller zeros after use)
     * @param alias        key alias (default "server")
     * @return true on success
     */
    public boolean encrypt(final Path keystorePath, final char[] storepass,
                           final String alias) {
        return SdiScriptRunner.runCryptoutil(
                installDir, "encrypt_props",
                getPlainPropertiesFile(), getPropertiesFile(),
                keystorePath, storepass, alias);
    }

    // -------------------------------------------------------------------------
    // Password update
    // -------------------------------------------------------------------------

    /**
     * Update keystore passwords in the plain properties file.
     *
     * @return number of keys updated
     * @throws IOException if the file cannot be read or written
     */
    public int updatePasswords(
            final char[] oldServerPass, final char[] newServerPass,
            final char[] oldAdminPass,  final char[] newAdminPass) throws IOException {
        return updatePasswords(getPlainPropertiesFile(),
                oldServerPass, newServerPass, oldAdminPass, newAdminPass);
    }

    /**
     * Update keystore passwords in an explicit properties file path.
     */
    public int updatePasswords(
            final Path target,
            final char[] oldServerPass, final char[] newServerPass,
            final char[] oldAdminPass,  final char[] newAdminPass) throws IOException {
        PropertiesPasswordUpdater updater = new PropertiesPasswordUpdater();
        return updater.update(target, oldServerPass, newServerPass, oldAdminPass, newAdminPass);
    }

    // -------------------------------------------------------------------------
    // Stash file
    // -------------------------------------------------------------------------

    /**
     * Create a new stash file using the {@code createstash} script.
     *
     * @param password keystore password (caller zeros after use)
     * @return true on success
     */
    public boolean createStash(final char[] password) {
        return SdiScriptRunner.runCreateStash(installDir, baseDir.toString(), password);
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    /**
     * Delete the temporary plain properties file if it exists.
     *
     * @return true when the file was deleted or did not exist; false on error
     */
    public boolean cleanupPlainFile() {
        Path plain = getPlainPropertiesFile();
        if (!Files.exists(plain)) return true;
        try {
            Files.delete(plain);
            log.info("Removed temporary decrypted file: {}", plain);
            return true;
        } catch (IOException e) {
            log.warn("Could not remove temporary file {}: {}", plain, e.getMessage());
            return false;
        }
    }

    public PropertiesFileHandler getHandler() { return handler; }
}