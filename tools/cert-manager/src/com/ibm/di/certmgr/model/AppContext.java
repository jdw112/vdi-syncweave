/*
 * Copyright contributors to the SyncWeave project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.certmgr.model;

/**
 * Holds resolved runtime state shared across all CLI commands.
 *
 * <p>Mirrors the Python {@code AppContext} class.  Two keystore paths are
 * tracked:
 * <ul>
 *   <li>{@code jksPath} / {@code storepass} -- the <em>server identity</em>
 *       keystore used for regeneration, CA-key import, encrypt/decrypt, etc.
 *       This is always {@code testserver.jks} (or whatever {@code api.keystore}
 *       points to).</li>
 *   <li>{@code trustJksPath} / {@code trustStorepass} -- the keystore to which
 *       <em>trusted certificates</em> should be added (menu items 4, 5, 8).
 *       When {@code api.client.ssl.custom.properties.on=false} (default) this
 *       is the same as the server keystore.  When
 *       {@code api.client.ssl.custom.properties.on=true} this is
 *       {@code api.client.keystore} / {@code api.client.truststore}.</li>
 * </ul>
 *
 * @param keytoolPath        absolute path to the keytool executable
 * @param jksPath            absolute path to the server identity JKS/PKCS12 keystore
 * @param storepass          server keystore password (zero after use)
 * @param installDir         SDI install directory
 * @param solutionDir        SDI solution directory
 * @param directoryMode      active directory mode, or null if not yet selected
 * @param propertiesHandler  resolved properties file handler, or null
 * @param trustJksPath       active trust keystore path (may equal jksPath); null falls back to jksPath
 * @param trustStorepass     active trust keystore password; null falls back to storepass
 * @param trustLabel         display label for the trust keystore, e.g. "server" or "client"
 */
public record AppContext(
        String keytoolPath,
        String jksPath,
        char[] storepass,
        String installDir,
        String solutionDir,
        DirectoryMode directoryMode,
        Object propertiesHandler,
        String trustJksPath,
        char[] trustStorepass,
        String trustLabel
) {
    /**
     * Creates a context without a directory mode or properties handler.
     * Used when only basic keystore operations are needed.
     */
    public static AppContext basic(
            final String keytoolPath,
            final String jksPath,
            final char[] storepass,
            final String installDir,
            final String solutionDir) {
        return new AppContext(keytoolPath, jksPath, storepass,
                installDir, solutionDir, null, null, null, null, "server");
    }

    /**
     * Compatibility factory -- creates context with no separate trust keystore
     * (trustJksPath falls back to jksPath at call sites that use
     * {@link #effectiveTrustJksPath()} / {@link #effectiveTrustStorepass()}).
     */
    public AppContext(
            final String keytoolPath,
            final String jksPath,
            final char[] storepass,
            final String installDir,
            final String solutionDir,
            final DirectoryMode directoryMode,
            final Object propertiesHandler) {
        this(keytoolPath, jksPath, storepass, installDir, solutionDir,
             directoryMode, propertiesHandler, null, null, "server");
    }

    /**
     * Returns the effective path of the trust keystore.
     * Falls back to {@link #jksPath()} when no separate trust keystore is set.
     */
    public String effectiveTrustJksPath() {
        return (trustJksPath != null && !trustJksPath.isBlank()) ? trustJksPath : jksPath;
    }

    /**
     * Returns the effective password of the trust keystore.
     * Falls back to {@link #storepass()} when no separate trust password is set.
     */
    public char[] effectiveTrustStorepass() {
        return (trustStorepass != null && trustStorepass.length > 0) ? trustStorepass : storepass;
    }
}