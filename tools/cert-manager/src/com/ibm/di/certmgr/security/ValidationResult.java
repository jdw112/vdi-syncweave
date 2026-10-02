/*
 * Copyright contributors to the SyncWeave project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.certmgr.security;

/**
 * Immutable result from a validation check.
 *
 * @param valid   true when the input passed all checks
 * @param message empty string on success; human-readable reason on failure
 */
public record ValidationResult(boolean valid, String message) {

    /** Singleton success result. */
    public static final ValidationResult OK = new ValidationResult(true, "");

    /** Factory for a failed validation with a reason. */
    public static ValidationResult fail(final String message) {
        return new ValidationResult(false, message);
    }
}