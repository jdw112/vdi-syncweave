/*
 * Copyright contributors to the SyncWeave project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.certmgr.regen;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes the {@code serverapi/registry.txt} file used by SDI Server API
 * to authorise the admin client certificate.
 *
 * <p>File format (minimal valid entry):
 * <pre>
 * [USER]
 * [ID]:CN=API Admin, OU=test, O=test, L=test, ST=test, C=US
 * [ROLE]:admin
 * [ENDUSER]
 * </pre>
 *
 * <p>Behaviour:
 * <ul>
 *   <li>If the file already has a {@code [USER]…[ENDUSER]} block the entire
 *       block is replaced in-place, preserving surrounding content.</li>
 *   <li>If no such block exists a new one is appended (with a blank separator
 *       line if the file was non-empty).</li>
 *   <li>The {@code serverapi/} sub-directory is created if absent.</li>
 * </ul>
 */
public class RegistryFileUpdater {

    private static final Logger log = LogManager.getLogger(RegistryFileUpdater.class);

    private static final String SECTION_USER    = "[USER]";
    private static final String SECTION_ENDUSER = "[ENDUSER]";

    /**
     * Update (or create) the {@code serverapi/registry.txt} under {@code baseDir}.
     *
     * @param baseDir   root directory (solution or install dir) that contains {@code serverapi/}
     * @param adminDn   Subject DN of the admin certificate (e.g. {@code "CN=Admin, O=IBM, C=US"})
     * @param role      SDI role string to assign (typically {@code "admin"})
     * @return {@code true} if the file was written successfully
     */
    public boolean update(final Path baseDir, final String adminDn, final String role) {
        if (adminDn == null || adminDn.isBlank()) {
            log.error("Admin DN must not be blank");
            return false;
        }

        try {
            Path serverapiDir = baseDir.resolve("serverapi");
            Files.createDirectories(serverapiDir);

            Path registryFile = serverapiDir.resolve("registry.txt");

            List<String> existing = new ArrayList<>();
            if (Files.exists(registryFile)) {
                existing.addAll(Files.readAllLines(registryFile, StandardCharsets.UTF_8));
            }

            List<String> newContent = buildContent(existing, adminDn.strip(), role);
            Files.write(registryFile, newContent, StandardCharsets.UTF_8);

            log.info("Updated registry.txt with admin DN: {}", adminDn);
            return true;

        } catch (IOException e) {
            log.error("Failed to update registry.txt: {}", e.getMessage());
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Build the new file content, replacing or appending the USER block.
     */
    List<String> buildContent(final List<String> existing,
                              final String adminDn,
                              final String role) {

        int sectionStart = -1;
        int sectionEnd   = -1;

        for (int i = 0; i < existing.size(); i++) {
            String stripped = existing.get(i).strip();
            if (SECTION_USER.equals(stripped) && sectionStart < 0) {
                sectionStart = i;
            } else if (SECTION_ENDUSER.equals(stripped) && sectionStart >= 0 && sectionEnd < 0) {
                sectionEnd = i;
            }
        }

        List<String> result = new ArrayList<>();

        if (sectionStart >= 0 && sectionEnd >= 0) {
            // Replace existing [USER]...[ENDUSER] block in-place
            result.addAll(existing.subList(0, sectionStart));
            appendUserBlock(result, adminDn, role);
            if (sectionEnd + 1 < existing.size()) {
                result.addAll(existing.subList(sectionEnd + 1, existing.size()));
            }
        } else {
            // Append new block
            result.addAll(existing);
            if (!existing.isEmpty()) {
                String last = existing.get(existing.size() - 1);
                if (!last.isEmpty()) result.add("");   // blank separator line
            }
            appendUserBlock(result, adminDn, role);
        }

        return result;
    }

    private static void appendUserBlock(final List<String> out,
                                        final String adminDn,
                                        final String role) {
        out.add(SECTION_USER);
        out.add("[ID]:" + adminDn);
        out.add("[ROLE]:" + role);
        out.add(SECTION_ENDUSER);
    }
}
