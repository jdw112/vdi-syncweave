/*
 * Copyright contributors to the SyncWeave project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.certmgr.security;

import com.ibm.di.certmgr.config.Configuration;

/**
 * Validates that a password meets the configured security requirements.
 *
 * <p>Rules (mirrors Python {@code validate_password_strength}):
 * <ul>
 *   <li>Minimum length (default 8, configurable via {@code security.min_password_length})</li>
 *   <li>When complexity is enabled (default true):
 *     <ul>
 *       <li>At least one uppercase letter</li>
 *       <li>At least one lowercase letter</li>
 *       <li>At least one digit</li>
 *       <li>At least one special character</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>Passwords are accepted as {@code char[]} to avoid leaving them in the
 * String pool. The array is read but never zeroed here; callers are
 * responsible for zeroing after use.
 */
public class PasswordValidator {

    private final int minLength;
    private final boolean requireComplexity;

    /**
     * Create a validator using values from the supplied configuration.
     *
     * @param config application configuration (may be null - uses hard-coded defaults)
     */
    public PasswordValidator(final Configuration config) {
        if (config != null) {
            this.minLength        = config.getInt("security.min_password_length", 8);
            this.requireComplexity = config.getBoolean("security.require_password_complexity", true);
        } else {
            this.minLength        = 8;
            this.requireComplexity = true;
        }
    }

    /**
     * Create a validator with explicit settings (useful for tests).
     *
     * @param minLength         minimum number of characters required
     * @param requireComplexity whether to enforce uppercase/lowercase/digit/special rules
     */
    public PasswordValidator(final int minLength, final boolean requireComplexity) {
        this.minLength        = minLength;
        this.requireComplexity = requireComplexity;
    }

    /**
     * Validate a password supplied as a char array.
     *
     * @param password the password to validate (not zeroed by this method)
     * @return {@link ValidationResult#OK} on success, or a result with the
     *         first violated rule as the message
     */
    public ValidationResult validate(final char[] password) {
        if (password == null || password.length == 0) {
            return ValidationResult.fail("Password cannot be empty");
        }
        if (password.length < minLength) {
            return ValidationResult.fail(
                    "Password must be at least " + minLength + " characters");
        }
        if (requireComplexity) {
            boolean hasUpper   = false;
            boolean hasLower   = false;
            boolean hasDigit   = false;
            boolean hasSpecial = false;
            for (char c : password) {
                if (Character.isUpperCase(c)) hasUpper   = true;
                if (Character.isLowerCase(c)) hasLower   = true;
                if (Character.isDigit(c))     hasDigit   = true;
                if (!Character.isLetterOrDigit(c)) hasSpecial = true;
            }
            if (!hasUpper)   return ValidationResult.fail("Password must contain at least one uppercase letter");
            if (!hasLower)   return ValidationResult.fail("Password must contain at least one lowercase letter");
            if (!hasDigit)   return ValidationResult.fail("Password must contain at least one digit");
            if (!hasSpecial) return ValidationResult.fail("Password must contain at least one special character");
        }
        return ValidationResult.OK;
    }

    /**
     * Convenience overload that accepts a String.
     * The string is converted to a char array; the array is zeroed after validation.
     *
     * @param password password string
     * @return validation result
     */
    public ValidationResult validate(final String password) {
        if (password == null) {
            return ValidationResult.fail("Password cannot be empty");
        }
        char[] chars = password.toCharArray();
        try {
            return validate(chars);
        } finally {
            java.util.Arrays.fill(chars, '\0');
        }
    }

    public int getMinLength() { return minLength; }
    public boolean isRequireComplexity() { return requireComplexity; }
}