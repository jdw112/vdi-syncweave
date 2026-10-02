/*
 * Copyright contributors to the SyncWeave project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.certmgr.regen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RegistryFileUpdater}.
 *
 * Tests cover:
 *   - Creating a brand-new registry.txt in a non-existent serverapi dir
 *   - Replacing an existing USER block in-place
 *   - Appending a USER block when none exists
 *   - Preserving content before and after an existing USER block
 *   - Blank-line separator when appending to non-empty file
 *   - Blank admin DN is rejected
 *   - null admin DN is rejected
 *   - Custom role string
 *   - buildContent helper tested directly for in-place replace and append
 */
class RegistryFileUpdaterTest {

    private final RegistryFileUpdater updater = new RegistryFileUpdater();

    // -----------------------------------------------------------------------
    // update() - file system tests
    // -----------------------------------------------------------------------

    @Test
    void createsNewFileAndDirectory(@TempDir Path tmp) {
        boolean ok = updater.update(tmp, "CN=Admin, O=IBM, C=US", "admin");
        assertTrue(ok);
        Path registry = tmp.resolve("serverapi").resolve("registry.txt");
        assertTrue(Files.exists(registry));
    }

    @Test
    void newFileContainsCorrectBlock(@TempDir Path tmp) throws IOException {
        updater.update(tmp, "CN=Admin, O=IBM, C=US", "admin");
        List<String> lines = Files.readAllLines(tmp.resolve("serverapi").resolve("registry.txt"));
        assertTrue(lines.contains("[USER]"));
        assertTrue(lines.contains("[ID]:CN=Admin, O=IBM, C=US"));
        assertTrue(lines.contains("[ROLE]:admin"));
        assertTrue(lines.contains("[ENDUSER]"));
    }

    @Test
    void replacesExistingBlock(@TempDir Path tmp) throws IOException {
        Path serverapiDir = tmp.resolve("serverapi");
        Files.createDirectories(serverapiDir);
        Path registry = serverapiDir.resolve("registry.txt");
        Files.writeString(registry, """
                [USER]
                [ID]:CN=Old, O=IBM, C=US
                [ROLE]:admin
                [ENDUSER]
                """);

        updater.update(tmp, "CN=New, O=IBM, C=US", "admin");

        List<String> lines = Files.readAllLines(registry);
        assertFalse(lines.stream().anyMatch(l -> l.contains("CN=Old")),
                "Old DN must be removed");
        assertTrue(lines.stream().anyMatch(l -> l.contains("CN=New")),
                "New DN must be present");
    }

    @Test
    void preservesContentOutsideBlock(@TempDir Path tmp) throws IOException {
        Path serverapiDir = tmp.resolve("serverapi");
        Files.createDirectories(serverapiDir);
        Path registry = serverapiDir.resolve("registry.txt");
        Files.writeString(registry, """
                # registry header comment
                [USER]
                [ID]:CN=Old, O=IBM, C=US
                [ROLE]:admin
                [ENDUSER]
                # footer
                """);

        updater.update(tmp, "CN=New, O=IBM, C=US", "admin");

        String content = Files.readString(registry);
        assertTrue(content.contains("# registry header comment"), "Header comment must survive");
        assertTrue(content.contains("# footer"), "Footer comment must survive");
    }

    @Test
    void appendsBlockToNonEmptyFileWithSeparator(@TempDir Path tmp) throws IOException {
        Path serverapiDir = tmp.resolve("serverapi");
        Files.createDirectories(serverapiDir);
        Path registry = serverapiDir.resolve("registry.txt");
        Files.writeString(registry, "# existing content");

        updater.update(tmp, "CN=Admin, O=IBM, C=US", "admin");

        List<String> lines = Files.readAllLines(registry);
        assertTrue(lines.contains("[USER]"));
        // Verify a blank separator line exists before [USER]
        int userIdx = lines.indexOf("[USER]");
        assertTrue(userIdx > 0, "[USER] must not be the first line");
        assertTrue(lines.get(userIdx - 1).isBlank(),
                "There should be a blank separator line before [USER]");
    }

    @Test
    void rejectsBlankDn(@TempDir Path tmp) {
        assertFalse(updater.update(tmp, "", "admin"));
        assertFalse(updater.update(tmp, "   ", "admin"));
    }

    @Test
    void rejectsNullDn(@TempDir Path tmp) {
        assertFalse(updater.update(tmp, null, "admin"));
    }

    @Test
    void usesCustomRole(@TempDir Path tmp) throws IOException {
        updater.update(tmp, "CN=Admin, O=IBM, C=US", "superadmin");
        List<String> lines = Files.readAllLines(tmp.resolve("serverapi").resolve("registry.txt"));
        assertTrue(lines.contains("[ROLE]:superadmin"));
    }

    // -----------------------------------------------------------------------
    // buildContent() - pure logic tests (no file system)
    // -----------------------------------------------------------------------

    @Test
    void buildContentReplacesExistingBlock() {
        List<String> existing = List.of(
                "# header",
                "[USER]",
                "[ID]:CN=Old, C=US",
                "[ROLE]:admin",
                "[ENDUSER]",
                "# footer"
        );
        List<String> result = updater.buildContent(existing, "CN=New, C=US", "admin");
        assertFalse(result.stream().anyMatch(l -> l.contains("CN=Old")));
        assertTrue(result.contains("[ID]:CN=New, C=US"));
        assertTrue(result.contains("# header"), "Header must be preserved");
        assertTrue(result.contains("# footer"), "Footer must be preserved");
    }

    @Test
    void buildContentAppendsWhenNoBlockExists() {
        List<String> existing = List.of("# just a comment");
        List<String> result = updater.buildContent(existing, "CN=Admin, C=US", "admin");
        assertTrue(result.contains("[USER]"));
        assertTrue(result.contains("[ID]:CN=Admin, C=US"));
        assertTrue(result.contains("[ROLE]:admin"));
        assertTrue(result.contains("[ENDUSER]"));
    }

    @Test
    void buildContentHandlesEmptyExistingFile() {
        List<String> result = updater.buildContent(List.of(), "CN=Admin, C=US", "admin");
        assertEquals("[USER]",              result.get(0));
        assertEquals("[ID]:CN=Admin, C=US", result.get(1));
        assertEquals("[ROLE]:admin",         result.get(2));
        assertEquals("[ENDUSER]",            result.get(3));
        assertEquals(4, result.size());
    }

    @Test
    void buildContentPreservesBlockOrderRelativeToSurroundings() {
        List<String> existing = List.of(
                "line1",
                "[USER]",
                "[ID]:CN=Old",
                "[ROLE]:admin",
                "[ENDUSER]",
                "line2",
                "line3"
        );
        List<String> result = updater.buildContent(existing, "CN=New", "admin");
        assertEquals("line1",  result.get(0));
        assertEquals("[USER]", result.get(1));
        // line2, line3 should still be at the end
        assertTrue(result.contains("line2"));
        assertTrue(result.contains("line3"));
        int endUserIdx = result.indexOf("[ENDUSER]");
        assertTrue(result.indexOf("line2") > endUserIdx);
    }
}
