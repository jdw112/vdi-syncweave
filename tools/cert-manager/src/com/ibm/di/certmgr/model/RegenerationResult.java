/*
 * Copyright contributors to the SyncWeave project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.certmgr.model;

import java.nio.file.Path;
import java.util.List;

/**
 * Immutable result and audit record from the certificate regeneration engine.
 *
 * <p>Mirrors the Python {@code SdiCertRegenerationResult} dataclass.</p>
 *
 * @param success              true when all steps completed without error
 * @param mode                 the directory mode that was used
 * @param dryRun               true when the engine ran in dry-run mode
 * @param backupPath           path to the created tar.gz backup archive, or null
 * @param propertiesFile       path to the (encrypted) properties file, or null
 * @param propertiesFilePlain  path to the decrypted properties file, or null
 * @param stashFile            path to the stash file, or null
 * @param changes              ordered list of change descriptions for audit log
 * @param errorMessage         failure description when success is false, else null
 */
public record RegenerationResult(
        boolean success,
        DirectoryMode mode,
        boolean dryRun,
        String backupPath,
        Path propertiesFile,
        Path propertiesFilePlain,
        Path stashFile,
        List<String> changes,
        String errorMessage
) {
    /** Convenience factory for a successful result. */
    public static RegenerationResult success(
            final DirectoryMode mode,
            final boolean dryRun,
            final String backupPath,
            final Path propertiesFile,
            final Path propertiesFilePlain,
            final Path stashFile,
            final List<String> changes) {
        return new RegenerationResult(true, mode, dryRun, backupPath,
                propertiesFile, propertiesFilePlain, stashFile,
                List.copyOf(changes), null);
    }

    /** Convenience factory for a failed result. */
    public static RegenerationResult failure(
            final DirectoryMode mode,
            final String errorMessage,
            final List<String> changes) {
        return new RegenerationResult(false, mode, false, null,
                null, null, null,
                List.copyOf(changes), errorMessage);
    }
}