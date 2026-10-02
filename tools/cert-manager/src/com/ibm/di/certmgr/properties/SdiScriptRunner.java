/*
 * Copyright contributors to the SyncWeave project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.certmgr.properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs the SDI external scripts ({@code cryptoutils}, {@code createstash})
 * via {@link ProcessBuilder}.
 *
 * <p>Mirrors the Python {@code decrypt_properties_file},
 * {@code encrypt_properties_file}, and {@code create_stash_file} functions.
 *
 * <p>Passwords are passed as {@code char[]} and converted to String only
 * immediately before the {@code ProcessBuilder} call; the array is zeroed
 * by the caller.
 */
public class SdiScriptRunner {

    private static final Logger log = LogManager.getLogger(SdiScriptRunner.class);

    private static final int CRYPTOUTIL_TIMEOUT_SECONDS = 30;
    private static final int CREATESTASH_TIMEOUT_SECONDS = 30;

    private SdiScriptRunner() { }

    // -------------------------------------------------------------------------
    // cryptoutils
    // -------------------------------------------------------------------------

    /**
     * Run cryptoutils to decrypt or encrypt a properties file.
     *
     * @param installDir  SDI install directory
     * @param mode        "decrypt_props" or "encrypt_props"
     * @param inputFile   source file
     * @param outputFile  destination file
     * @param keystorePath keystore path
     * @param storepass   keystore password
     * @param alias       key alias
     * @return true on success
     */
    public static boolean runCryptoutil(
            final String installDir,
            final String mode,
            final Path inputFile,
            final Path outputFile,
            final Path keystorePath,
            final char[] storepass,
            final String alias) {

        Path script = findScript(installDir, "serverapi", "cryptoutils");
        if (script == null) {
            log.error("cryptoutils script not found in {}/serverapi/", installDir);
            return false;
        }

        List<String> cmd = new ArrayList<>(List.of(
                script.toString(),
                "-input",     inputFile.toString(),
                "-output",    outputFile.toString(),
                "-mode",      mode,
                "-keystore",  keystorePath.toString(),
                "-storepass", new String(storepass),
                "-alias",     alias
        ));

        try {
            Process proc = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = proc.waitFor(CRYPTOUTIL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                log.error("cryptoutils timed out after {}s", CRYPTOUTIL_TIMEOUT_SECONDS);
                return false;
            }
            if (proc.exitValue() != 0) {
                String out = new String(proc.getInputStream().readAllBytes());
                log.error("cryptoutils failed (exit {}): {}", proc.exitValue(), out);
                return false;
            }
            if (!Files.exists(outputFile)) {
                log.error("cryptoutils exited 0 but output file was not created: {}", outputFile);
                return false;
            }
            log.info("cryptoutils {} succeeded: {} -> {}", mode, inputFile, outputFile);
            return true;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.error("Error running cryptoutils: {}", e.getMessage());
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // createstash
    // -------------------------------------------------------------------------

    /**
     * Run the createstash script to generate {@code idisrv.sth}.
     *
     * @param installDir  SDI install directory
     * @param solutionDir solution directory (working directory for the script)
     * @param password    keystore password (same password passed twice, as required)
     * @return true on success
     */
    public static boolean runCreateStash(
            final String installDir,
            final String solutionDir,
            final char[] password) {

        Path script = findScript(installDir, "bin", "createstash");
        if (script == null) {
            log.error("createstash script not found in {}/bin/", installDir);
            return false;
        }

        String pass = new String(password);
        List<String> cmd = List.of(script.toString(), pass, pass);

        try {
            Process proc = new ProcessBuilder(cmd)
                    .directory(Path.of(solutionDir).toFile())
                    .redirectErrorStream(true)
                    .start();
            boolean finished = proc.waitFor(CREATESTASH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                log.error("createstash timed out after {}s", CREATESTASH_TIMEOUT_SECONDS);
                return false;
            }
            if (proc.exitValue() != 0) {
                String out = new String(proc.getInputStream().readAllBytes());
                log.error("createstash failed (exit {}): {}", proc.exitValue(), out);
                return false;
            }
            log.info("createstash succeeded in {}", solutionDir);
            return true;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.error("Error running createstash: {}", e.getMessage());
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Script discovery
    // -------------------------------------------------------------------------

    /**
     * Find the platform-appropriate script (e.g. cryptoutils.sh / cryptoutils.bat).
     *
     * @param installDir base install directory
     * @param subDir     subdirectory under install dir (e.g. "serverapi", "bin")
     * @param scriptBase script name without extension
     * @return path to the script, or null if not found
     */
    static Path findScript(final String installDir, final String subDir,
                           final String scriptBase) {
        if (installDir == null || installDir.isBlank()) return null;
        Path dir = Path.of(installDir).resolve(subDir);
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String[] extensions = isWindows ? new String[]{".bat", ".cmd"} : new String[]{".sh", ""};
        for (String ext : extensions) {
            Path candidate = dir.resolve(scriptBase + ext);
            if (Files.isExecutable(candidate) || Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}