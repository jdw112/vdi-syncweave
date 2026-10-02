/*
 * Copyright contributors to the SyncWeave project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.certmgr.properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Performs targeted password-key replacement in a decrypted SDI properties file.
 *
 * <p>Mirrors Python {@code update_solution_properties}.
 * Only the specific property keys documented in the SDI Server API trust model
 * are updated; all other lines are preserved verbatim including whitespace and
 * line endings. Lines whose values start with {@code {protect}} or {@code {encr}}
 * are skipped (still encrypted).
 */
public class PropertiesPasswordUpdater {

    private static final Logger log = LogManager.getLogger(PropertiesPasswordUpdater.class);

    /** Keys whose values map to the server keystore password. */
    private static final Set<String> SERVER_PASS_KEYS = Set.of(
            "api.keystore.password",
            "api.truststore.password",
            "com.ibm.di.server.encryption.keystore.password",
            "javax.net.ssl.keyStorePassword",
            "javax.net.ssl.trustStorePassword"
    );

    /** Keys whose values map to the admin keystore password. */
    private static final Set<String> ADMIN_PASS_KEYS = Set.of(
            "api.client.keystore.password",
            "api.client.keystore.pass",
            "api.client.truststore.password",
            "api.client.truststore.pass"
    );

    /**
     * Update keystore passwords in the given plain-text properties file.
     *
     * <p>Passwords are accepted as {@code char[]} to keep them out of the
     * String pool. They are read once and not zeroed here; callers must zero
     * them after the call returns.
     *
     * @param propertiesFile path to the decrypted properties file (modified in place)
     * @param oldServerPass  current server keystore password
     * @param newServerPass  replacement server keystore password
     * @param oldAdminPass   current admin keystore password
     * @param newAdminPass   replacement admin keystore password
     * @return number of keys updated (0 is valid when passwords are unchanged)
     * @throws IOException if the file cannot be read or written
     */
    public int update(
            final Path propertiesFile,
            final char[] oldServerPass,
            final char[] newServerPass,
            final char[] oldAdminPass,
            final char[] newAdminPass) throws IOException {

        String oldSvr = new String(oldServerPass);
        String newSvr = new String(newServerPass);
        String oldAdm = new String(oldAdminPass);
        String newAdm = new String(newAdminPass);

        List<String> lines  = Files.readAllLines(propertiesFile, StandardCharsets.UTF_8);
        List<String> output = new ArrayList<>(lines.size());
        int changes = 0;

        for (String line : lines) {
            String stripped = line.strip();

            // Preserve blank lines and comments verbatim
            if (stripped.isEmpty() || stripped.startsWith("#") || !stripped.contains("=")) {
                output.add(line);
                continue;
            }

            int eq    = stripped.indexOf('=');
            String key   = stripped.substring(0, eq).strip();
            String value = stripped.substring(eq + 1).strip();

            // Skip still-encrypted values
            if (value.startsWith("{protect}") || value.startsWith("{encr}")) {
                output.add(line);
                continue;
            }

            if (SERVER_PASS_KEYS.contains(key) && value.equals(oldSvr)) {
                String indent = line.substring(0, line.length() - line.stripLeading().length());
                String eol    = line.endsWith("\n") ? "\n" : "";
                output.add(indent + key + "=" + newSvr + eol);
                changes++;
                log.debug("Updated server password for key: {}", key);
            } else if (ADMIN_PASS_KEYS.contains(key) && value.equals(oldAdm)) {
                String indent = line.substring(0, line.length() - line.stripLeading().length());
                String eol    = line.endsWith("\n") ? "\n" : "";
                output.add(indent + key + "=" + newAdm + eol);
                changes++;
                log.debug("Updated admin password for key: {}", key);
            } else {
                output.add(line);
            }
        }

        // Write atomically via a temp file
        Path tmp = propertiesFile.resolveSibling(
                propertiesFile.getFileName().toString() + ".tmp." + ProcessHandle.current().pid());
        try {
            Files.write(tmp, output, StandardCharsets.UTF_8);
            Files.move(tmp, propertiesFile, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }

        log.info("Updated {} password value(s) in {}", changes, propertiesFile);
        return changes;
    }

    /** Expose server key set for testing. */
    static Set<String> serverPassKeys() { return SERVER_PASS_KEYS; }

    /** Expose admin key set for testing. */
    static Set<String> adminPassKeys()  { return ADMIN_PASS_KEYS; }
}