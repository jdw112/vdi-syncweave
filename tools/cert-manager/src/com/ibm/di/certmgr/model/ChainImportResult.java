/*
 * Copyright contributors to the SyncWeave project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.certmgr.model;

import java.util.List;

/**
 * Structured result from importing a certificate chain into a keystore.
 *
 * <p>Mirrors the Python {@code ChainImportResult} dataclass.</p>
 *
 * @param success         true when all certificates were imported successfully
 * @param importedAliases aliases that were successfully imported
 * @param failedAlias     the alias that caused failure, or null
 * @param errorMessage    failure description when success is false, else null
 * @param backupPath      path to the keystore backup created before import, or null
 */
public record ChainImportResult(
        boolean success,
        List<String> importedAliases,
        String failedAlias,
        String errorMessage,
        String backupPath
) {
    /** Convenience factory for a successful import. */
    public static ChainImportResult success(
            final List<String> importedAliases,
            final String backupPath) {
        return new ChainImportResult(true, List.copyOf(importedAliases),
                null, null, backupPath);
    }

    /** Convenience factory for a failed import. */
    public static ChainImportResult failure(
            final List<String> importedAliases,
            final String failedAlias,
            final String errorMessage) {
        return new ChainImportResult(false, List.copyOf(importedAliases),
                failedAlias, errorMessage, null);
    }
}