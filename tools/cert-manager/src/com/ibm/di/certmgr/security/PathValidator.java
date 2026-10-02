/*
 * Copyright contributors to the SyncWeave project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.certmgr.security;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Validates that file-system paths are safe to use.
 *
 * <p>Mirrors Python {@code validate_safe_path} and {@code sanitize_input}.
 * Checks performed:
 * <ul>
 *   <li>Rejects null or blank paths</li>
 *   <li>Rejects paths containing {@code ..} (traversal)</li>
 *   <li>When a base directory is supplied, ensures the resolved path
 *       is a descendant of that directory</li>
 * </ul>
 */
public class PathValidator {

    /**
     * Validate a path string.
     *
     * @param path    the path to validate
     * @param baseDir optional base directory; when non-null the path must resolve
     *                to a location inside this directory
     * @return {@link ValidationResult#OK} or a failure result with a reason
     */
    public ValidationResult validate(final String path, final Path baseDir) {
        if (path == null || path.isBlank()) {
            return ValidationResult.fail("Path cannot be empty");
        }
        // Reject explicit traversal sequences in the raw input
        if (path.contains("..")) {
            return ValidationResult.fail("Path traversal detected (.. not allowed)");
        }
        try {
            Path resolved = Paths.get(path).toAbsolutePath().normalize();
            if (baseDir != null) {
                Path absBase = baseDir.toAbsolutePath().normalize();
                if (!resolved.startsWith(absBase)) {
                    return ValidationResult.fail("Path must be within " + baseDir);
                }
            }
            return ValidationResult.OK;
        } catch (Exception e) {
            return ValidationResult.fail("Invalid path: " + e.getMessage());
        }
    }

    /**
     * Convenience overload with no base directory restriction.
     *
     * @param path the path to validate
     * @return validation result
     */
    public ValidationResult validate(final String path) {
        return validate(path, null);
    }

    /**
     * Sanitize a user-supplied string by removing characters that could
     * cause command injection when passed to a shell or sub-process.
     *
     * <p>Mirrors Python {@code sanitize_input}.
     * Removed when {@code allowSpecialChars} is false:
     * {@code | & ; $ ` \n \r < >}
     *
     * @param input            the raw user input
     * @param allowSpecialChars when true no characters are removed
     * @return trimmed, sanitized string
     */
    public String sanitize(final String input, final boolean allowSpecialChars) {
        if (input == null) {
            return "";
        }
        String result = input;
        if (!allowSpecialChars) {
            // Characters that must not reach a shell or ProcessBuilder command list
            for (char c : new char[]{'|', '&', ';', '$', '`', '\n', '\r', '<', '>'}) {
                result = result.replace(String.valueOf(c), "");
            }
        }
        return result.strip();
    }

    /**
     * Sanitize with special characters disallowed (safe default).
     *
     * @param input raw user input
     * @return sanitized string
     */
    public String sanitize(final String input) {
        return sanitize(input, false);
    }
}