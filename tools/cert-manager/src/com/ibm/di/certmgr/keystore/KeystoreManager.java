/*
 * Copyright contributors to the SyncWeave project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.certmgr.keystore;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Manages Java Keystore operations via keytool sub-process invocations.
 *
 * <p>Mirrors Python {@code KeystoreManager}. Key security constraints:
 * <ul>
 *   <li>All keystore passwords are passed via environment variables — never
 *       as command-line arguments (would be visible in {@code ps} output).</li>
 *   <li>Password arrays are accepted as {@code char[]}; callers must zero them
 *       after use. This class does not zero arrays it receives.</li>
 * </ul>
 *
 * <p>Environment variable names used:
 * <pre>
 *   SVDI_KEYSTORE_PASS   - current store password  (always set)
 *   SVDI_KEYPASS         - key entry password       (genkeypair)
 *   SVDI_OLD_KEYPASS     - old key password         (keypasswd)
 *   SVDI_NEW_KEYPASS     - new key password         (keypasswd)
 *   SVDI_NEW_STOREPASS   - new store password       (storepasswd)
 *   SVDI_P12_PASS        - destination P12 password (importkeystore to P12)
 *   SVDI_P12_SRC_PASS    - source P12 password      (importkeystore from P12)
 * </pre>
 */
public class KeystoreManager {

    private static final Logger log = LogManager.getLogger(KeystoreManager.class);

    // Environment variable names — must match keytool :env argument names
    public static final String ENV_STOREPASS     = "SVDI_KEYSTORE_PASS";
    public static final String ENV_KEYPASS       = "SVDI_KEYPASS";
    public static final String ENV_OLD_KEYPASS   = "SVDI_OLD_KEYPASS";
    public static final String ENV_NEW_KEYPASS   = "SVDI_NEW_KEYPASS";
    public static final String ENV_NEW_STOREPASS = "SVDI_NEW_STOREPASS";
    public static final String ENV_P12_PASS      = "SVDI_P12_PASS";
    public static final String ENV_P12_SRC_PASS  = "SVDI_P12_SRC_PASS";

    private static final DateTimeFormatter BACKUP_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    // Operation-specific timeouts in seconds
    private static final Map<String, Integer> TIMEOUTS = Map.of(
            "list",            30,
            "delete",          30,
            "import",          45,
            "export",          45,
            "generate",        60,
            "convert",         60,
            "change_password", 30
    );
    private static final int DEFAULT_TIMEOUT = 45;

    private final String keytoolPath;
    private final Path keystorePath;
    private char[] storepass;       // mutable: updated by changeStorePassword

    /**
     * Create a manager for the given keystore.
     *
     * @param keytoolPath  absolute path to the keytool executable
     * @param keystorePath path to the JKS or PKCS12 keystore file
     * @param storepass    keystore password (not zeroed by this class)
     */
    public KeystoreManager(final String keytoolPath,
                           final Path keystorePath,
                           final char[] storepass) {
        this.keytoolPath  = keytoolPath;
        this.keystorePath = keystorePath.toAbsolutePath().normalize();
        this.storepass    = storepass;
    }

    public KeystoreManager(final String keytoolPath,
                           final String keystorePath,
                           final char[] storepass) {
        this(keytoolPath, Path.of(keystorePath), storepass);
    }

    // -------------------------------------------------------------------------
    // Store type
    // -------------------------------------------------------------------------

    /**
     * Determine keystore type from file extension.
     * Defaults to JKS for unknown extensions.
     */
    public String getStoretype() {
        String name = keystorePath.getFileName().toString().toLowerCase();
        if (name.endsWith(".p12") || name.endsWith(".pfx")) return "PKCS12";
        return "JKS";
    }

    // -------------------------------------------------------------------------
    // Core command runner
    // -------------------------------------------------------------------------

    /**
     * Result triple from a keytool invocation.
     *
     * @param success    true when keytool exited 0 (or alias-missing was tolerated)
     * @param stdout     captured standard output
     * @param stderr     captured standard error
     */
    public record KeytoolResult(boolean success, String stdout, String stderr) { }

    /**
     * Run keytool with the given arguments.
     *
     * <p>The current store password is always injected as
     * {@value #ENV_STOREPASS} in the process environment.
     * Additional environment variables can be supplied via {@code extraEnv}.
     *
     * @param args             keytool arguments (without the keytool binary itself)
     * @param operationType    key into {@link #TIMEOUTS} (e.g. "list"); null uses default
     * @param allowAliasMissing when true a "does not exist" keytool error is treated as success
     * @param extraEnv         additional env vars to inject (passwords etc.)
     * @return result triple
     */
    public KeytoolResult runKeytool(
            final List<String> args,
            final String operationType,
            final boolean allowAliasMissing,
            final Map<String, String> extraEnv) {

        List<String> cmd = new ArrayList<>();
        cmd.add(keytoolPath);
        cmd.addAll(args);

        int timeout = Optional.ofNullable(TIMEOUTS.get(operationType))
                .orElse(DEFAULT_TIMEOUT);

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd)
                    .redirectErrorStream(false);

            // Inject passwords via environment variables
            Map<String, String> env = pb.environment();
            env.put(ENV_STOREPASS, new String(storepass));
            if (extraEnv != null) env.putAll(extraEnv);

            Process proc = pb.start();
            boolean finished = proc.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return new KeytoolResult(false, "",
                        "Command timed out after " + timeout + " seconds");
            }

            String stdout = new String(proc.getInputStream().readAllBytes());
            String stderr = new String(proc.getErrorStream().readAllBytes());

            if (proc.exitValue() == 0) {
                return new KeytoolResult(true, stdout, stderr);
            }
            if (allowAliasMissing &&
                    (stderr.contains("does not exist") ||
                     stderr.contains("Alias does not exist") ||
                     stdout.contains("does not exist") ||
                     stdout.contains("Alias does not exist"))) {
                return new KeytoolResult(true, stdout, stderr);
            }
            return new KeytoolResult(false, stdout, stderr);

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new KeytoolResult(false, "", e.getMessage());
        }
    }

    // Convenience overload with no extra env and alias-missing disallowed
    private KeytoolResult run(final List<String> args, final String opType) {
        return runKeytool(args, opType, false, null);
    }

    // -------------------------------------------------------------------------
    // Password validation
    // -------------------------------------------------------------------------

    /** Validate the store password by running keytool -list. Returns true on success. */
    public boolean validatePassword() {
        KeytoolResult r = run(List.of(
                "-list",
                "-keystore", keystorePath.toString(),
                "-storetype", getStoretype(),
                "-storepass:env", ENV_STOREPASS
        ), "list");
        return r.success();
    }

    // -------------------------------------------------------------------------
    // Backup / restore
    // -------------------------------------------------------------------------

    /**
     * Copy the keystore to a timestamped backup file.
     *
     * @return path to the backup, or empty on failure
     */
    public Optional<Path> backupKeystore() {
        String ts = LocalDateTime.now().format(BACKUP_TS);
        Path backup = keystorePath.resolveSibling(
                keystorePath.getFileName() + ".backup_" + ts);
        try {
            Files.copy(keystorePath, backup, StandardCopyOption.COPY_ATTRIBUTES);
            log.info("Backup created: {}", backup);
            return Optional.of(backup);
        } catch (IOException e) {
            log.error("Failed to create backup: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Restore the keystore from a previously created backup.
     *
     * @param backupPath path to the backup file
     * @return true on success
     */
    public boolean restoreKeystore(final Path backupPath) {
        try {
            Files.copy(backupPath, keystorePath, StandardCopyOption.REPLACE_EXISTING);
            log.info("Keystore restored from backup: {}", backupPath);
            return true;
        } catch (IOException e) {
            log.error("Failed to restore keystore: {}", e.getMessage());
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // List entries
    // -------------------------------------------------------------------------

    /**
     * List all entries in the keystore.
     *
     * @return map with keys {@code "keyEntry"} and {@code "trustedCertEntry"}
     */
    public Map<String, List<String>> listEntries() {
        Map<String, List<String>> result = new HashMap<>();
        result.put("keyEntry", new ArrayList<>());
        result.put("trustedCertEntry", new ArrayList<>());

        KeytoolResult r = run(List.of(
                "-list", "-v",
                "-keystore", keystorePath.toString(),
                "-storetype", getStoretype(),
                "-storepass:env", ENV_STOREPASS
        ), "list");

        if (!r.success()) {
            log.error("Failed to list keystore entries: {}", r.stderr());
            return result;
        }

        String currentAlias = null;
        for (String line : r.stdout().split("\n")) {
            String s = line.strip();
            if (s.startsWith("Alias name:")) {
                currentAlias = s.substring("Alias name:".length()).strip();
            } else if (s.startsWith("Entry type:") && currentAlias != null) {
                String entryType = s.substring("Entry type:".length()).strip();
                if (entryType.contains("PrivateKeyEntry") || entryType.contains("keyEntry")) {
                    result.get("keyEntry").add(currentAlias);
                } else if (entryType.contains("trustedCertEntry")) {
                    result.get("trustedCertEntry").add(currentAlias);
                }
                currentAlias = null;
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Delete / import / export
    // -------------------------------------------------------------------------

    /** Delete an alias from the keystore (success even if alias absent). */
    public boolean deleteEntry(final String alias) {
        KeytoolResult r = runKeytool(List.of(
                "-delete",
                "-keystore", keystorePath.toString(),
                "-storetype", getStoretype(),
                "-storepass:env", ENV_STOREPASS,
                "-alias", alias
        ), "delete", true, null);

        if (r.success()) {
            if (r.stderr().contains("does not exist") || r.stdout().contains("does not exist")) {
                log.info("Alias '{}' does not exist (skipped)", alias);
            } else {
                log.info("Deleted alias '{}'", alias);
            }
        } else {
            log.error("Failed to delete alias '{}': {}", alias, r.stderr());
        }
        return r.success();
    }

    /** Import a PEM or DER certificate file under the given alias. */
    public boolean importCertificate(final String alias, final Path certFile) {
        KeytoolResult r = run(List.of(
                "-importcert", "-noprompt",
                "-keystore", keystorePath.toString(),
                "-storetype", getStoretype(),
                "-storepass:env", ENV_STOREPASS,
                "-alias", alias,
                "-file", certFile.toString()
        ), "import");
        if (!r.success()) log.error("importCertificate failed: {}", r.stderr());
        return r.success();
    }

    /** Export the certificate for the given alias to a PEM file. */
    public boolean exportCertificate(final String alias, final Path outputFile) {
        KeytoolResult r = run(List.of(
                "-exportcert",
                "-keystore", keystorePath.toString(),
                "-storetype", getStoretype(),
                "-storepass:env", ENV_STOREPASS,
                "-alias", alias,
                "-file", outputFile.toString(),
                "-rfc"
        ), "export");
        if (!r.success()) log.error("exportCertificate failed: {}", r.stderr());
        return r.success();
    }

    /** Print verbose certificate details for an alias to the log. */
    public boolean showCertificateDetails(final String alias) {
        KeytoolResult r = run(List.of(
                "-list", "-v",
                "-keystore", keystorePath.toString(),
                "-storetype", getStoretype(),
                "-storepass:env", ENV_STOREPASS,
                "-alias", alias
        ), "list");
        if (r.success()) log.info("{}", r.stdout().strip());
        else log.error("Failed to read certificate details: {}", r.stderr());
        return r.success();
    }

    // -------------------------------------------------------------------------
    // Keystore conversion
    // -------------------------------------------------------------------------

    /**
     * Convert this keystore to a PKCS12 file.
     *
     * @param outputPath destination .p12 path
     * @param destPass   destination keystore password
     * @return true on success
     */
    public boolean convertToPkcs12(final Path outputPath, final char[] destPass) {
        KeytoolResult r = runKeytool(List.of(
                "-importkeystore",
                "-srckeystore",    keystorePath.toString(),
                "-srcstoretype",   getStoretype(),
                "-destkeystore",   outputPath.toString(),
                "-deststoretype",  "PKCS12",
                "-srcstorepass:env",  ENV_STOREPASS,
                "-deststorepass:env", ENV_P12_PASS
        ), "convert", false, Map.of(ENV_P12_PASS, new String(destPass)));

        if (!r.success()) log.error("convertToPkcs12 failed: {}", r.stderr());
        return r.success();
    }

    /**
     * Import a private key and certificate chain from a PKCS12/PFX file.
     *
     * @param srcP12     source .p12 / .pfx file
     * @param srcPass    source file password
     * @param destAlias  alias to use in this keystore
     * @param srcAlias   alias inside the source P12 (null = let keytool pick the first)
     * @return true on success
     */
    public boolean importPkcs12(final Path srcP12, final char[] srcPass,
                                final String destAlias, final String srcAlias) {
        List<String> args = new ArrayList<>(List.of(
                "-importkeystore",
                "-srckeystore",      srcP12.toString(),
                "-srcstoretype",     "PKCS12",
                "-srcstorepass:env", ENV_P12_SRC_PASS,
                "-destkeystore",     keystorePath.toString(),
                "-deststoretype",    getStoretype(),
                "-deststorepass:env", ENV_STOREPASS,
                "-destalias",        destAlias,
                "-noprompt"
        ));
        if (srcAlias != null) { args.add("-srcalias"); args.add(srcAlias); }

        KeytoolResult r = runKeytool(args, "import", false,
                Map.of(ENV_P12_SRC_PASS, new String(srcPass)));
        if (!r.success()) log.error("importPkcs12 failed: {}", r.stderr());
        return r.success();
    }

    // -------------------------------------------------------------------------
    // Certificate DN
    // -------------------------------------------------------------------------

    /**
     * Return the Subject DN of the certificate stored under an alias.
     *
     * @param alias keystore alias to inspect
     * @return Subject DN string, or empty if not found
     */
    public Optional<String> getCertificateDn(final String alias) {
        KeytoolResult r = run(List.of(
                "-list", "-v",
                "-keystore", keystorePath.toString(),
                "-storetype", getStoretype(),
                "-storepass:env", ENV_STOREPASS,
                "-alias", alias
        ), "list");
        if (!r.success()) return Optional.empty();
        for (String line : r.stdout().lines().toList()) {
            String s = line.strip();
            if (s.startsWith("Owner:")) return Optional.of(s.substring("Owner:".length()).strip());
        }
        return Optional.empty();
    }

    // -------------------------------------------------------------------------
    // Password changes
    // -------------------------------------------------------------------------

    /**
     * Change the password for a private key entry (JKS only).
     * PKCS12 does not support separate key passwords.
     *
     * @param alias      key alias
     * @param oldKeypass current key password
     * @param newKeypass new key password
     * @return true on success
     */
    public boolean changeKeyPassword(final String alias,
                                     final char[] oldKeypass,
                                     final char[] newKeypass) {
        if ("PKCS12".equals(getStoretype())) {
            log.error("PKCS12 keystores do not support separate key passwords");
            return false;
        }
        KeytoolResult r = runKeytool(List.of(
                "-keypasswd",
                "-alias", alias,
                "-keystore", keystorePath.toString(),
                "-storetype", getStoretype(),
                "-storepass:env", ENV_STOREPASS,
                "-keypass:env",   ENV_OLD_KEYPASS,
                "-new:env",       ENV_NEW_KEYPASS
        ), "change_password", false,
                Map.of(ENV_OLD_KEYPASS, new String(oldKeypass),
                       ENV_NEW_KEYPASS, new String(newKeypass)));

        if (r.success()) log.info("Changed key password for alias '{}'", alias);
        else log.error("Failed to change key password for alias '{}': {}", alias, r.stderr());
        return r.success();
    }

    /**
     * Change the keystore store password.
     * On success, updates the in-memory storepass for subsequent operations.
     *
     * @param newStorepass new keystore password
     * @return true on success
     */
    public boolean changeStorePassword(final char[] newStorepass) {
        KeytoolResult r = runKeytool(List.of(
                "-storepasswd",
                "-keystore", keystorePath.toString(),
                "-storetype", getStoretype(),
                "-storepass:env", ENV_STOREPASS,
                "-new:env",       ENV_NEW_STOREPASS
        ), "change_password", false,
                Map.of(ENV_NEW_STOREPASS, new String(newStorepass)));

        if (r.success()) {
            this.storepass = Arrays.copyOf(newStorepass, newStorepass.length);
            log.info("Changed store password");
        } else {
            log.error("Failed to change store password: {}", r.stderr());
        }
        return r.success();
    }

    // -------------------------------------------------------------------------
    // Key pair generation
    // -------------------------------------------------------------------------

    /**
     * Generate a new RSA key pair and self-signed certificate.
     *
     * @param alias      alias for the new entry
     * @param keypass    private key password
     * @param dname      X.500 distinguished name string (null = SDI default)
     * @param validity   certificate validity in days (null = 1095)
     * @param keysize    key size in bits (null = 2048)
     * @param keyalg     key algorithm (null = "RSA")
     * @param sanValues  SAN entries e.g. "dns:host1", "ip:1.2.3.4" (null/empty = no SAN)
     * @return true on success
     */
    public boolean generateKeypair(
            final String alias,
            final char[] keypass,
            final String dname,
            final Integer validity,
            final Integer keysize,
            final String keyalg,
            final List<String> sanValues) {

        String effectiveDname   = dname    != null ? dname    : "CN=API Admin, OU=test, O=test, L=test, ST=test, C=US";
        int    effectiveValidity = validity != null ? validity : 1095;
        int    effectiveKeysize  = keysize  != null ? keysize  : 2048;
        String effectiveKeyalg   = keyalg   != null ? keyalg   : "RSA";

        List<String> args = new ArrayList<>(List.of(
                "-genkeypair",
                "-dname",         effectiveDname,
                "-alias",         alias,
                "-keypass:env",   ENV_KEYPASS,
                "-keystore",      keystorePath.toString(),
                "-storepass:env", ENV_STOREPASS,
                "-validity",      String.valueOf(effectiveValidity),
                "-keyalg",        effectiveKeyalg,
                "-keysize",       String.valueOf(effectiveKeysize),
                "-storetype",     getStoretype(),
                "-noprompt"
        ));

        if (sanValues != null && !sanValues.isEmpty()) {
            args.add("-ext");
            args.add("SAN=" + String.join(",", sanValues));
        }

        KeytoolResult r = runKeytool(args, "generate", false,
                Map.of(ENV_KEYPASS, new String(keypass)));

        if (r.success()) log.info("Generated key pair for alias '{}'", alias);
        else log.error("Failed to generate key pair: {}", r.stderr());
        return r.success();
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public Path getKeystorePath()  { return keystorePath; }
    public String getKeytoolPath() { return keytoolPath; }
}