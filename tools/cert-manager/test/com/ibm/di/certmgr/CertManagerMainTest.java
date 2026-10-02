/*
 * Copyright contributors to the SyncWeave project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.certmgr;

import com.ibm.di.certmgr.model.DirectoryMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CertManagerMain} Picocli option parsing and context building.
 *
 * No SDI binary invocations are made — these tests verify that:
 *   - Help exits 0
 *   - Version exits 0
 *   - Unknown options exit 2
 *   - buildContext correctly applies config overrides
 *   - Mode resolution delegates to DirectoryMode
 */
class CertManagerMainTest {

    // -----------------------------------------------------------------------
    // Picocli parse / exit code tests
    // -----------------------------------------------------------------------

    @Test
    void helpExitsZero() {
        int code = new CommandLine(new CertManagerMain()).execute("--help");
        assertEquals(0, code);
    }

    @Test
    void versionExitsZero() {
        int code = new CommandLine(new CertManagerMain()).execute("--version");
        assertEquals(0, code);
    }

    @Test
    void unknownOptionExitsTwo() {
        int code = new CommandLine(new CertManagerMain()).execute("--no-such-option");
        assertEquals(2, code);
    }

    // -----------------------------------------------------------------------
    // buildContext
    // -----------------------------------------------------------------------

    @Test
    void buildContextUsesDefaultMode() {
        CertManagerMain main = new CertManagerMain();
        var cfg = new com.ibm.di.certmgr.config.Configuration();
        var ctx = main.buildContext(cfg);
        // Default mode from config is "solution"
        assertNotNull(ctx);
        assertNotNull(ctx.keytoolPath());
    }

    @Test
    void buildContextAppliesInstallDirOverride() {
        CertManagerMain main = new CertManagerMain();
        var cfg = new com.ibm.di.certmgr.config.Configuration();
        cfg.set("directories.install_dir", "/opt/ibm/sdi");
        cfg.set("directories.mode", "install");
        var ctx = main.buildContext(cfg);
        assertEquals("/opt/ibm/sdi", ctx.installDir());
    }

    @Test
    void buildContextAppliesSolutionDirOverride() {
        CertManagerMain main = new CertManagerMain();
        var cfg = new com.ibm.di.certmgr.config.Configuration();
        cfg.set("directories.solution_dir", "/opt/ibm/sol");
        var ctx = main.buildContext(cfg);
        assertEquals("/opt/ibm/sol", ctx.solutionDir());
    }

    @Test
    void buildContextInstallModeSetsDirMode() {
        CertManagerMain main = new CertManagerMain();
        var cfg = new com.ibm.di.certmgr.config.Configuration();
        cfg.set("directories.mode", "install");
        var ctx = main.buildContext(cfg);
        assertEquals(DirectoryMode.INSTALL, ctx.directoryMode());
    }

    @Test
    void buildContextSolutionModeSetsDirMode() {
        CertManagerMain main = new CertManagerMain();
        var cfg = new com.ibm.di.certmgr.config.Configuration();
        cfg.set("directories.mode", "solution");
        var ctx = main.buildContext(cfg);
        assertEquals(DirectoryMode.SOLUTION, ctx.directoryMode());
    }

    @Test
    void buildContextKeytoolPathIsNonNull() {
        CertManagerMain main = new CertManagerMain();
        var ctx = main.buildContext(new com.ibm.di.certmgr.config.Configuration());
        assertNotNull(ctx.keytoolPath());
        assertFalse(ctx.keytoolPath().isBlank());
    }

    // -----------------------------------------------------------------------
    // automateFile missing returns 2
    // -----------------------------------------------------------------------

    @Test
    void automateWithMissingFileExitsTwo(@TempDir Path tmp) {
        Path missing = tmp.resolve("nonexistent.yaml");
        int code = new CommandLine(new CertManagerMain())
                .execute("--automate", missing.toString(), "--dry-run");
        // Missing file should produce exit code 2
        assertEquals(2, code);
    }
}
