/*
 * Copyright contributors to the SyncWeave project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.certmgr.properties;

import com.ibm.di.certmgr.model.DirectoryMode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves and validates SDI directory structure, and provides paths to the
 * properties file, decrypted plain file, stash file and keystores.
 *
 * <p>Mirrors Python {@code PropertiesFileHandler}.
 */
public class PropertiesFileHandler {

    private static final Logger log = LogManager.getLogger(PropertiesFileHandler.class);

    /** Property keys consulted for the server-side keystore (first match wins). */
    private static final List<String> SERVER_KEYS = List.of(
            "api.keystore",
            "api.truststore",
            "javax.net.ssl.keyStore",
            "javax.net.ssl.trustStore"
    );

    /** Property keys consulted for the admin-side keystore (first match wins). */
    private static final List<String> ADMIN_KEYS = List.of(
            "api.client.keystore",
            "api.client.truststore"
    );

    private final DirectoryMode mode;
    private final Path baseDir;

    /**
     * Create a handler and validate that the directory has the expected structure.
     *
     * @param mode    INSTALL or SOLUTION
     * @param baseDir base directory for the selected mode
     * @throws IllegalArgumentException when the directory is missing or lacks required subdirs
     */
    public PropertiesFileHandler(final DirectoryMode mode, final Path baseDir) {
        this.mode    = mode;
        this.baseDir = baseDir.toAbsolutePath().normalize();
        validateDirectory();
    }

    public PropertiesFileHandler(final DirectoryMode mode, final String baseDir) {
        this(mode, Path.of(baseDir));
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    private void validateDirectory() {
        if (!Files.exists(baseDir)) {
            throw new IllegalArgumentException("Directory does not exist: " + baseDir);
        }
        if (mode == DirectoryMode.INSTALL) {
            for (String sub : List.of("etc", "bin", "serverapi")) {
                if (!Files.exists(baseDir.resolve(sub))) {
                    throw new IllegalArgumentException(
                            "Invalid install directory: missing " + sub +
                            ". Expected structure: bin/, etc/, serverapi/");
                }
            }
        } else {
            if (!Files.exists(baseDir.resolve("serverapi"))) {
                throw new IllegalArgumentException(
                        "Invalid solution directory: missing serverapi/. " +
                        "Expected structure: serverapi/, <server>.jks");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Path accessors
    // -------------------------------------------------------------------------

    /** Full path to the properties file (may be encrypted). */
    public Path getPropertiesFilePath() {
        if (mode == DirectoryMode.INSTALL) {
            return baseDir.resolve("etc").resolve("global.properties");
        }
        return baseDir.resolve("solution.properties");
    }

    /** Path to the temporary decrypted copy of the properties file. */
    public Path getPropertiesFilePlainPath() {
        Path p = getPropertiesFilePath();
        return p.getParent().resolve(p.getFileName().toString() + "_plain");
    }

    /** Path to the stash file ({@code idisrv.sth}). */
    public Path getStashFilePath() {
        return baseDir.resolve("idisrv.sth");
    }

    /** The base directory this handler was created with. */
    public Path getBaseDir() { return baseDir; }

    /** The mode this handler was created with. */
    public DirectoryMode getMode() { return mode; }

    // -------------------------------------------------------------------------
    // Keystore path resolution (mirrors read_keystore_names)
    // -------------------------------------------------------------------------

    /**
     * Parse the properties file and return resolved absolute paths for the
     * server-side and admin-side keystores.
     *
     * <p>Reads the plain (decrypted) file first; falls back to the encrypted
     * file; falls back to SDI defaults when neither is readable.
     *
     * @return map with keys {@code "server"} and {@code "admin"}
     */
    public Map<String, Path> readKeystoreNames() {
        Map<String, String> props = loadProps();

        Path serverPath = null;
        for (String key : SERVER_KEYS) {
            if (props.containsKey(key)) {
                serverPath = resolveServer(props.get(key));
                break;
            }
        }
        if (serverPath == null) {
            serverPath = baseDir.resolve("testserver.jks");
        }

        Path adminPath = null;
        for (String key : ADMIN_KEYS) {
            if (props.containsKey(key)) {
                adminPath = resolveAdmin(props.get(key));
                break;
            }
        }
        if (adminPath == null) {
            adminPath = baseDir.resolve("serverapi").resolve("testadmin.jks");
        }

        Map<String, Path> result = new HashMap<>();
        result.put("server", serverPath);
        result.put("admin",  adminPath);
        return result;
    }

    // -------------------------------------------------------------------------
    // Active keystore resolution  (mirrors Python SSL trust model)
    // -------------------------------------------------------------------------

    /**
     * Result of {@link #resolveActiveKeystores()}: the keystore(s) that
     * trusted certificates should be added to.
     *
     * <p>SDI SSL trust model summary:
     * <ul>
     *   <li>When {@code api.client.ssl.custom.properties.on} is <b>absent or
     *       false</b>: outbound connections from SDI (server-side and client-side)
     *       use the same JSSE keystore as the server TLS identity.  Adding a
     *       trusted certificate means adding it to the <em>server</em> keystore
     *       ({@code api.keystore} / {@code javax.net.ssl.keyStore}).
     *       {@code primaryJks} == server JKS; {@code secondaryJks} == null.</li>
     *   <li>When {@code api.client.ssl.custom.properties.on=true}: the SDI
     *       client (LDAP, HTTP connector) uses a <em>separate</em> trust store
     *       ({@code api.client.truststore} / {@code api.client.keystore}).
     *       Adding a trusted certificate means adding it to the
     *       <em>client</em> keystore.
     *       {@code primaryJks} == client JKS; {@code secondaryJks} == server JKS
     *       (so the user can optionally cross-import).</li>
     * </ul>
     */
    public record ActiveKeystoreInfo(
            /** The primary keystore to which trusted certs should be added. */
            Path primaryJks,
            /**
             * The secondary keystore (cross-import candidate), or null when
             * {@code api.client.ssl.custom.properties.on} is absent/false.
             */
            Path secondaryJks,
            /**
             * True when {@code api.client.ssl.custom.properties.on=true};
             * false otherwise.
             */
            boolean customClientSsl,
            /** Display label for the primary keystore (e.g. "server" or "client"). */
            String primaryLabel
    ) {}

    /**
     * Determine which keystore(s) trusted certificates should be added to,
     * based on the {@code api.client.ssl.custom.properties.on} flag.
     *
     * <p>This mirrors the Python comment block in {@code update_solution_properties}
     * and the {@code read_keystore_names} docstring regarding the SSL trust model.
     *
     * @return populated {@link ActiveKeystoreInfo}
     */
    public ActiveKeystoreInfo resolveActiveKeystores() {
        Map<String, String> props = loadProps();
        Map<String, Path> ks = readKeystoreNames();

        boolean customClientSsl = Boolean.parseBoolean(
                props.getOrDefault("api.client.ssl.custom.properties.on", "false").strip());

        if (customClientSsl) {
            // Client has its own trust store -- trusted certs go into api.client.keystore /
            // api.client.truststore.  The server keystore is a secondary cross-import target.
            log.debug("resolveActiveKeystores: api.client.ssl.custom.properties.on=true -- primary=client");
            return new ActiveKeystoreInfo(ks.get("admin"), ks.get("server"), true, "client");
        } else {
            // Default / single-keystore layout: all trust anchors live in the server keystore.
            log.debug("resolveActiveKeystores: api.client.ssl.custom.properties.on=false -- primary=server");
            return new ActiveKeystoreInfo(ks.get("server"), null, false, "server");
        }
    }

    /**
     * Files to include in a backup for this mode.
     * Includes both keystores, the properties file, and the stash file if present.
     */
    public List<Path> getBackupFiles() {
        Map<String, Path> ks = readKeystoreNames();
        java.util.ArrayList<Path> files = new java.util.ArrayList<>();
        files.add(ks.get("admin"));
        files.add(ks.get("server"));
        files.add(getPropertiesFilePath());
        Path stash = getStashFilePath();
        if (Files.exists(stash)) {
            files.add(stash);
        }
        return List.copyOf(files);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /** Read and parse the best available properties file. */
    private Map<String, String> loadProps() {
        for (Path candidate : List.of(getPropertiesFilePlainPath(), getPropertiesFilePath())) {
            if (!Files.exists(candidate)) continue;
            try {
                Map<String, String> props = new HashMap<>();
                for (String raw : Files.readAllLines(candidate, StandardCharsets.UTF_8)) {
                    String line = raw.strip();
                    if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) continue;
                    int eq = line.indexOf('=');
                    String key   = line.substring(0, eq).strip();
                    String value = line.substring(eq + 1).strip();
                    if (value.startsWith("{protect}") || value.startsWith("{encr}")) continue;
                    props.put(key, value);
                }
                return props;
            } catch (IOException e) {
                log.debug("Could not read {}: {}", candidate, e.getMessage());
            }
        }
        return Map.of();
    }

    private Path resolveServer(final String value) {
        Path p = Path.of(value);
        return p.isAbsolute() ? p : baseDir.resolve(p);
    }

    private Path resolveAdmin(final String value) {
        Path p = Path.of(value);
        if (p.isAbsolute()) return p;
        if (!p.getName(0).toString().equals("serverapi") && p.getNameCount() == 1) {
            return baseDir.resolve("serverapi").resolve(value);
        }
        return baseDir.resolve(p);
    }
}