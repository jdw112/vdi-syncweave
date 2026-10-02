/*
 * Copyright contributors to the SyncWeave project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.certmgr.properties;

import com.ibm.di.certmgr.model.DirectoryMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PropertiesFileHandlerTest {

    // ── helper: build a minimal INSTALL directory ───────────────────────────
    private Path makeInstallDir(Path tmp) throws IOException {
        Files.createDirectories(tmp.resolve("etc"));
        Files.createDirectories(tmp.resolve("bin"));
        Files.createDirectories(tmp.resolve("serverapi"));
        return tmp;
    }

    // ── helper: build a minimal SOLUTION directory ──────────────────────────
    private Path makeSolutionDir(Path tmp) throws IOException {
        Files.createDirectories(tmp.resolve("serverapi"));
        return tmp;
    }

    // ---- construction / validation -----------------------------------------

    @Test
    void installModeValidDirCreates(@TempDir Path tmp) throws IOException {
        makeInstallDir(tmp);
        assertDoesNotThrow(() -> new PropertiesFileHandler(DirectoryMode.INSTALL, tmp));
    }

    @Test
    void solutionModeValidDirCreates(@TempDir Path tmp) throws IOException {
        makeSolutionDir(tmp);
        assertDoesNotThrow(() -> new PropertiesFileHandler(DirectoryMode.SOLUTION, tmp));
    }

    @Test
    void missingDirThrows(@TempDir Path tmp) {
        Path missing = tmp.resolve("nonexistent");
        assertThrows(IllegalArgumentException.class,
                () -> new PropertiesFileHandler(DirectoryMode.INSTALL, missing));
    }

    @Test
    void installModeMissingEtcThrows(@TempDir Path tmp) throws IOException {
        Files.createDirectories(tmp.resolve("bin"));
        Files.createDirectories(tmp.resolve("serverapi"));
        // no etc/
        assertThrows(IllegalArgumentException.class,
                () -> new PropertiesFileHandler(DirectoryMode.INSTALL, tmp));
    }

    @Test
    void solutionModeMissingServerapiThrows(@TempDir Path tmp) {
        // empty dir
        assertThrows(IllegalArgumentException.class,
                () -> new PropertiesFileHandler(DirectoryMode.SOLUTION, tmp));
    }

    // ---- path accessors ----------------------------------------------------

    @Test
    void installModePropertiesFilePath(@TempDir Path tmp) throws IOException {
        makeInstallDir(tmp);
        PropertiesFileHandler h = new PropertiesFileHandler(DirectoryMode.INSTALL, tmp);
        assertTrue(h.getPropertiesFilePath().toString().endsWith("global.properties"));
        assertTrue(h.getPropertiesFilePath().toString().contains("etc"));
    }

    @Test
    void solutionModePropertiesFilePath(@TempDir Path tmp) throws IOException {
        makeSolutionDir(tmp);
        PropertiesFileHandler h = new PropertiesFileHandler(DirectoryMode.SOLUTION, tmp);
        assertTrue(h.getPropertiesFilePath().toString().endsWith("solution.properties"));
    }

    @Test
    void plainFilePathIsPropertiesFilePlusPlain(@TempDir Path tmp) throws IOException {
        makeSolutionDir(tmp);
        PropertiesFileHandler h = new PropertiesFileHandler(DirectoryMode.SOLUTION, tmp);
        assertTrue(h.getPropertiesFilePlainPath().toString().endsWith("solution.properties_plain"));
    }

    @Test
    void stashFilePathIsIdisrvSth(@TempDir Path tmp) throws IOException {
        makeSolutionDir(tmp);
        PropertiesFileHandler h = new PropertiesFileHandler(DirectoryMode.SOLUTION, tmp);
        assertTrue(h.getStashFilePath().toString().endsWith("idisrv.sth"));
    }

    // ---- keystore resolution: defaults -------------------------------------

    @Test
    void defaultServerJksWhenNoPropertiesFile(@TempDir Path tmp) throws IOException {
        makeSolutionDir(tmp);
        PropertiesFileHandler h = new PropertiesFileHandler(DirectoryMode.SOLUTION, tmp);
        Map<String, Path> ks = h.readKeystoreNames();
        assertTrue(ks.get("server").toString().endsWith("testserver.jks"));
        assertTrue(ks.get("admin").toString().contains("serverapi"));
        assertTrue(ks.get("admin").toString().endsWith("testadmin.jks"));
    }

    // ---- keystore resolution: from properties file -------------------------

    @Test
    void customServerJksReadFromPropertiesFile(@TempDir Path tmp) throws IOException {
        makeSolutionDir(tmp);
        Path propsFile = tmp.resolve("solution.properties");
        Files.writeString(propsFile, "api.keystore=custom_server.jks\n");
        PropertiesFileHandler h = new PropertiesFileHandler(DirectoryMode.SOLUTION, tmp);
        Map<String, Path> ks = h.readKeystoreNames();
        assertTrue(ks.get("server").toString().endsWith("custom_server.jks"));
    }

    @Test
    void customAdminJksBarenameGoesUnderServerapi(@TempDir Path tmp) throws IOException {
        makeSolutionDir(tmp);
        Path propsFile = tmp.resolve("solution.properties");
        Files.writeString(propsFile, "api.client.keystore=myadmin.jks\n");
        PropertiesFileHandler h = new PropertiesFileHandler(DirectoryMode.SOLUTION, tmp);
        Map<String, Path> ks = h.readKeystoreNames();
        assertTrue(ks.get("admin").toString().contains("serverapi"));
        assertTrue(ks.get("admin").toString().endsWith("myadmin.jks"));
    }

    @Test
    void encryptedValuesAreSkipped(@TempDir Path tmp) throws IOException {
        makeSolutionDir(tmp);
        Path propsFile = tmp.resolve("solution.properties");
        Files.writeString(propsFile, "api.keystore={protect}encrypted_value\n");
        PropertiesFileHandler h = new PropertiesFileHandler(DirectoryMode.SOLUTION, tmp);
        Map<String, Path> ks = h.readKeystoreNames();
        // Falls back to default when value is encrypted
        assertTrue(ks.get("server").toString().endsWith("testserver.jks"));
    }

    @Test
    void plainFilePreferredOverEncryptedFile(@TempDir Path tmp) throws IOException {
        makeSolutionDir(tmp);
        // Encrypted file has {protect} value; plain file has a real path
        Files.writeString(tmp.resolve("solution.properties"),
                "api.keystore={protect}enc\n");
        Files.writeString(tmp.resolve("solution.properties_plain"),
                "api.keystore=real_server.jks\n");
        PropertiesFileHandler h = new PropertiesFileHandler(DirectoryMode.SOLUTION, tmp);
        Map<String, Path> ks = h.readKeystoreNames();
        assertTrue(ks.get("server").toString().endsWith("real_server.jks"));
    }

    // ---- backup files -------------------------------------------------------

    @Test
    void backupFilesIncludesPropertiesFile(@TempDir Path tmp) throws IOException {
        makeSolutionDir(tmp);
        PropertiesFileHandler h = new PropertiesFileHandler(DirectoryMode.SOLUTION, tmp);
        assertTrue(h.getBackupFiles().contains(h.getPropertiesFilePath()));
    }

    @Test
    void backupFilesIncludesStashWhenPresent(@TempDir Path tmp) throws IOException {
        makeSolutionDir(tmp);
        Path stash = tmp.resolve("idisrv.sth");
        Files.writeString(stash, "stash");
        PropertiesFileHandler h = new PropertiesFileHandler(DirectoryMode.SOLUTION, tmp);
        assertTrue(h.getBackupFiles().contains(stash));
    }

    @Test
    void backupFilesOmitsStashWhenAbsent(@TempDir Path tmp) throws IOException {
        makeSolutionDir(tmp);
        PropertiesFileHandler h = new PropertiesFileHandler(DirectoryMode.SOLUTION, tmp);
        assertTrue(h.getBackupFiles().stream()
                .noneMatch(p -> p.toString().endsWith("idisrv.sth")));
    }
}