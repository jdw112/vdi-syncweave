/*
 * Copyright contributors to the SyncWeave project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.certmgr.model;

import java.util.List;
import java.util.Map;

/**
 * Immutable input specification for the SDI certificate regeneration engine.
 *
 * <p>Mirrors the Python {@code SdiCertRegenerationSpec} dataclass.
 * Passwords are accepted as {@code char[]} so callers can zero them after use;
 * the engine zeroes them on completion.</p>
 *
 * @param mode           directory mode (INSTALL or SOLUTION)
 * @param baseDir        base directory for the selected mode
 * @param installDir     SDI install directory (needed for cryptoutils/createstash)
 * @param solutionDir    SDI solution directory
 * @param oldServerPass  current server keystore password
 * @param oldAdminPass   current admin keystore password
 * @param newServerPass  replacement server keystore password
 * @param newAdminPass   replacement admin keystore password
 * @param certParams     certificate parameters (cn, ou, o, l, st, c, validity, keysize, san)
 * @param keytoolPath    absolute path to the keytool executable
 * @param dryRun         when true the engine validates inputs but makes no changes
 */
public record RegenerationSpec(
        DirectoryMode mode,
        String baseDir,
        String installDir,
        String solutionDir,
        char[] oldServerPass,
        char[] oldAdminPass,
        char[] newServerPass,
        char[] newAdminPass,
        Map<String, Object> certParams,
        String keytoolPath,
        boolean dryRun
) {
    /**
     * Convenience factory for non-dry-run invocations.
     */
    public static RegenerationSpec of(
            final DirectoryMode mode,
            final String baseDir,
            final String installDir,
            final String solutionDir,
            final char[] oldServerPass,
            final char[] oldAdminPass,
            final char[] newServerPass,
            final char[] newAdminPass,
            final Map<String, Object> certParams,
            final String keytoolPath) {
        return new RegenerationSpec(mode, baseDir, installDir, solutionDir,
                oldServerPass, oldAdminPass, newServerPass, newAdminPass,
                certParams, keytoolPath, false);
    }

    // -------------------------------------------------------------------------
    // Typed accessors for certParams map entries
    // -------------------------------------------------------------------------

    /** Distinguished name string, e.g. {@code "CN=API Admin, OU=test, O=IBM, C=US"}. */
    public String dname() {
        return certParams != null ? (String) certParams.get("dname") : null;
    }

    /** Certificate validity period in days (null = use keytool default 1095). */
    public Integer validityDays() {
        if (certParams == null) return null;
        Object v = certParams.get("validity");
        if (v instanceof Integer i) return i;
        if (v instanceof Number n) return n.intValue();
        return null;
    }

    /** Key size in bits (null = use keytool default 2048). */
    public Integer keysize() {
        if (certParams == null) return null;
        Object v = certParams.get("keysize");
        if (v instanceof Integer i) return i;
        if (v instanceof Number n) return n.intValue();
        return null;
    }

    /** Key algorithm, e.g. {@code "RSA"} (null = use keytool default). */
    public String keyalg() {
        return certParams != null ? (String) certParams.get("keyalg") : null;
    }

    /** Subject Alternative Name values, e.g. {@code ["dns:host1","ip:1.2.3.4"]}. */
    @SuppressWarnings("unchecked")
    public List<String> sanValues() {
        if (certParams == null) return null;
        Object v = certParams.get("san_values");
        if (v instanceof List<?>) return (List<String>) v;
        return null;
    }
}