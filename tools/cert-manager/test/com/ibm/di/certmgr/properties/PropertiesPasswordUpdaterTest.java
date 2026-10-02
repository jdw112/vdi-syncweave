/*
 * Copyright contributors to the SyncWeave project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.certmgr.properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PropertiesPasswordUpdaterTest {

    private static final String CRLF = "\r\n";
    private static final String LF   = "\n";

    private final PropertiesPasswordUpdater updater = new PropertiesPasswordUpdater();

    // ---- targeted replacement -----------------------------------------------

    @Test
    void updatesServerPasswordKey(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("solution.properties");
        Files.writeString(f, "api.keystore.password=oldServerPass\n");
        int n = updater.update(f,
                "oldServerPass".toCharArray(), "newServerPass".toCharArray(),
                "oldAdminPass".toCharArray(),  "newAdminPass".toCharArray());
        assertEquals(1, n);
        assertTrue(Files.readString(f).contains("api.keystore.password=newServerPass"));
    }

    @Test
    void updatesAdminPasswordKey(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("solution.properties");
        Files.writeString(f, "api.client.keystore.password=oldAdminPass\n");
        int n = updater.update(f,
                "oldServerPass".toCharArray(), "newServerPass".toCharArray(),
                "oldAdminPass".toCharArray(),  "newAdminPass".toCharArray());
        assertEquals(1, n);
        assertTrue(Files.readString(f).contains("api.client.keystore.password=newAdminPass"));
    }

    @Test
    void updatesMultipleKeys(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("solution.properties");
        Files.writeString(f,
                "api.keystore.password=oldS\n" +
                "api.truststore.password=oldS\n" +
                "api.client.keystore.password=oldA\n");
        int n = updater.update(f,
                "oldS".toCharArray(), "newS".toCharArray(),
                "oldA".toCharArray(), "newA".toCharArray());
        assertEquals(3, n);
        String content = Files.readString(f);
        assertTrue(content.contains("api.keystore.password=newS"));
        assertTrue(content.contains("api.truststore.password=newS"));
        assertTrue(content.contains("api.client.keystore.password=newA"));
    }

    // ---- does not touch unrelated lines ------------------------------------

    @Test
    void doesNotReplaceNonPasswordKeys(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("solution.properties");
        String original = "some.other.key=oldServerPass\n";
        Files.writeString(f, original);
        int n = updater.update(f,
                "oldServerPass".toCharArray(), "newServerPass".toCharArray(),
                "oldAdminPass".toCharArray(),  "newAdminPass".toCharArray());
        assertEquals(0, n);
        assertEquals(original, Files.readString(f));
    }

    @Test
    void doesNotReplaceWhenValueDoesNotMatch(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("solution.properties");
        String original = "api.keystore.password=differentPassword\n";
        Files.writeString(f, original);
        int n = updater.update(f,
                "oldServerPass".toCharArray(), "newServerPass".toCharArray(),
                "oldAdminPass".toCharArray(),  "newAdminPass".toCharArray());
        assertEquals(0, n);
        assertEquals(original, Files.readString(f));
    }

    // ---- encrypted values skipped ------------------------------------------

    @Test
    void skipsProtectedValues(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("solution.properties");
        String original = "api.keystore.password={protect}encryptedValue\n";
        Files.writeString(f, original);
        int n = updater.update(f,
                "{protect}encryptedValue".toCharArray(), "newPass".toCharArray(),
                "x".toCharArray(), "y".toCharArray());
        assertEquals(0, n);
        assertEquals(original, Files.readString(f));
    }

    @Test
    void skipsEncrValues(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("solution.properties");
        String original = "api.keystore.password={encr}blob\n";
        Files.writeString(f, original);
        int n = updater.update(f,
                "{encr}blob".toCharArray(), "newPass".toCharArray(),
                "x".toCharArray(), "y".toCharArray());
        assertEquals(0, n);
    }

    // ---- preserves comments and blank lines --------------------------------

    @Test
    void preservesCommentsAndBlankLines(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("solution.properties");
        String content = "# this is a comment\n\napi.keystore.password=oldS\n";
        Files.writeString(f, content);
        updater.update(f,
                "oldS".toCharArray(), "newS".toCharArray(),
                "x".toCharArray(), "y".toCharArray());
        String result = Files.readString(f);
        assertTrue(result.contains("# this is a comment"));
        assertTrue(result.contains("\n\n") || result.contains(CRLF + CRLF));
    }

    // ---- all server keys are covered ---------------------------------------

    @Test
    void allServerPassKeysAreDefined() {
        assertTrue(PropertiesPasswordUpdater.serverPassKeys().contains("api.keystore.password"));
        assertTrue(PropertiesPasswordUpdater.serverPassKeys().contains("api.truststore.password"));
        assertTrue(PropertiesPasswordUpdater.serverPassKeys().contains("com.ibm.di.server.encryption.keystore.password"));
        assertTrue(PropertiesPasswordUpdater.serverPassKeys().contains("javax.net.ssl.keyStorePassword"));
        assertTrue(PropertiesPasswordUpdater.serverPassKeys().contains("javax.net.ssl.trustStorePassword"));
    }

    @Test
    void allAdminPassKeysAreDefined() {
        assertTrue(PropertiesPasswordUpdater.adminPassKeys().contains("api.client.keystore.password"));
        assertTrue(PropertiesPasswordUpdater.adminPassKeys().contains("api.client.keystore.pass"));
        assertTrue(PropertiesPasswordUpdater.adminPassKeys().contains("api.client.truststore.password"));
        assertTrue(PropertiesPasswordUpdater.adminPassKeys().contains("api.client.truststore.pass"));
    }
}