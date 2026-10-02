/*
 * Copyright contributors to the SyncWeave project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.certmgr.workflow;

import java.util.List;

/**
 * Result from the CA key import workflow.
 *
 * @param success          true when all mandatory steps completed without error
 * @param changes          ordered audit log of completed steps
 * @param errorMessage     failure description when success is false, else null
 * @param adminDn          Subject DN read from the imported cert (admin side only)
 * @param registryUpdated  true when registry.txt was successfully updated
 */
public record CaKeyImportResult(
        boolean success,
        List<String> changes,
        String errorMessage,
        String adminDn,
        boolean registryUpdated
) {
    public static CaKeyImportResult success(final List<String> changes,
                                            final String adminDn,
                                            final boolean registryUpdated) {
        return new CaKeyImportResult(true, List.copyOf(changes), null, adminDn, registryUpdated);
    }

    public static CaKeyImportResult failure(final List<String> changes, final String message) {
        return new CaKeyImportResult(false, List.copyOf(changes), message, null, false);
    }
}
