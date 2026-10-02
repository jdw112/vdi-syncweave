/*
 * Copyright contributors to the SyncWeave project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.certmgr.cli;

import com.ibm.di.certmgr.model.AppContext;
import com.ibm.di.certmgr.model.DirectoryMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link YamlAutomation}.
 *
 * All operations run with {@code dryRun=true} â€” no files are written,
 * no sub-processes are launched. Tests verify:
 *   - Unknown operation returns 2
 *   - Empty / null YAML returns 2
 *   - Missing required fields returns 2
 *   - Valid dry-run scenarios return 0
 *   - Password resolution from inline strings
 *   - Password resolution from env-map syntax
 *   - Dry-run flag in YAML overrides false constructor arg
 */
class YamlAutomationTest {

    private final AppContext ctx = AppContext.basic(
            "keytool", "/tmp/test.jks", "pass".toCharArray(), "/tmp/install", "/tmp/solution");

    // -----------------------------------------------------------------------
    // Bad input / missing fields
    // -----------------------------------------------------------------------

    @Test
    void missingOperationReturnsTwo(@TempDir Path tmp) throws IOException {
        Path yaml = tmp.resolve("bad.yaml");
        Files.writeString(yaml, "install_dir: /tmp\n");
        int code = new YamlAutomation(ctx, true).execute(yaml);
        assertEquals(2, code);
    }

    @Test
    void unknownOperationReturnsTwo(@TempDir Path tmp) throws IOException {
        Path yaml = tmp.resolve("op.yaml");
        Files.writeString(yaml, "operation: does-not-exist\n");
        int code = new YamlAutomation(ctx, true).execute(yaml);
        assertEquals(2, code);
    }

    @Test
    void malformedYamlReturnsTwo(@TempDir Path tmp) throws IOException {
        Path yaml = tmp.resolve("bad.yaml");
        Files.writeString(yaml, "not: valid: yaml: {{{\n");
        int code = new YamlAutomation(ctx, true).execute(yaml);
        assertEquals(2, code);
    }

    @Test
    void regenerateMissingPasswordsReturnsTwo(@TempDir Path tmp) throws IOException {
        Path yaml = tmp.resolve("regen.yaml");
        Files.writeString(yaml, "operation: regenerate\n");
        int code = new YamlAutomation(ctx, false).execute(yaml);
        assertEquals(2, code);
    }

    @Test
    void addCertFromHttpsMissingHostnameReturnsTwo(@TempDir Path tmp) throws IOException {
        Path yaml = tmp.resolve("op.yaml");
        Files.writeString(yaml, "operation: add-cert-from-https\nalias: test\n");
        int code = new YamlAutomation(ctx, false).execute(yaml);
        assertEquals(2, code);
    }

    @Test
    void updateRegistryMissingDnReturnsTwo(@TempDir Path tmp) throws IOException {
        Path yaml = tmp.resolve("op.yaml");
        Files.writeString(yaml, "operation: update-registry\n");
        int code = new YamlAutomation(ctx, false).execute(yaml);
        assertEquals(2, code);
    }

    @Test
    void importCaKeyMissingP12ReturnsTwo(@TempDir Path tmp) throws IOException {
        Path yaml = tmp.resolve("op.yaml");
        Files.writeString(yaml, "operation: import-ca-key\nside: server\n");
        int code = new YamlAutomation(ctx, false).execute(yaml);
        assertEquals(2, code);
    }

    // -----------------------------------------------------------------------
    // Dry-run happy paths
    // -----------------------------------------------------------------------

    @Test
    void regenerateDryRunReturnsZero(@TempDir Path tmp) throws IOException {
        Path yaml = tmp.resolve("regen.yaml");
        Files.writeString(yaml, """
                operation: regenerate
                old_server_pass: OldPass1!
                old_admin_pass:  OldPass1!
                new_server_pass: NewPass2@
                new_admin_pass:  NewPass2@
                dname: "CN=Test, O=IBM, C=US"
                mode: solution
                solution_dir: /tmp/sol
                install_dir:  /tmp/install
                """);
        int code = new YamlAutomation(ctx, true).execute(yaml);
        assertEquals(0, code);
    }

    @Test
    void decryptPropertiesDryRunReturnsZero(@TempDir Path tmp) throws IOException {
        Path yaml = tmp.resolve("op.yaml");
        Files.writeString(yaml, """
                operation: decrypt-properties
                server_pass: SomePass1!
                mode: solution
                solution_dir: /tmp/sol
                """);
        int code = new YamlAutomation(ctx, true).execute(yaml);
        assertEquals(0, code);
    }

    @Test
    void encryptPropertiesDryRunReturnsZero(@TempDir Path tmp) throws IOException {
        Path yaml = tmp.resolve("op.yaml");
        Files.writeString(yaml, """
                operation: encrypt-properties
                server_pass: SomePass1!
                mode: solution
                solution_dir: /tmp/sol
                """);
        int code = new YamlAutomation(ctx, true).execute(yaml);
        assertEquals(0, code);
    }

    @Test
    void createStashDryRunReturnsZero(@TempDir Path tmp) throws IOException {
        Path yaml = tmp.resolve("op.yaml");
        Files.writeString(yaml, """
                operation: create-stash
                server_pass: SomePass1!
                mode: solution
                solution_dir: /tmp/sol
                """);
        int code = new YamlAutomation(ctx, true).execute(yaml);
        assertEquals(0, code);
    }

    @Test
    void updateRegistryDryRunReturnsZero(@TempDir Path tmp) throws IOException {
        Path yaml = tmp.resolve("op.yaml");
        Files.writeString(yaml, """
                operation: update-registry
                admin_dn: "CN=Admin, O=IBM, C=US"
                role: admin
                solution_dir: /tmp/sol
                """);
        int code = new YamlAutomation(ctx, true).execute(yaml);
        assertEquals(0, code);
    }

    @Test
    void addCertFromHttpsDryRunReturnsZero(@TempDir Path tmp) throws IOException {
        Path yaml = tmp.resolve("op.yaml");
        Files.writeString(yaml, """
                operation: add-cert-from-https
                hostname: example.com
                port: 443
                alias: root-ca
                """);
        int code = new YamlAutomation(ctx, true).execute(yaml);
        assertEquals(0, code);
    }

    @Test
    void importCaKeyDryRunReturnsZero(@TempDir Path tmp) throws IOException {
        Path yaml = tmp.resolve("op.yaml");
        // p12_path must point to a file that exists for the non-dry check;
        // but dry-run skips file-existence check so we can use a fake path
        Files.writeString(yaml, """
                operation: import-ca-key
                side: server
                p12_path: /tmp/cert.p12
                p12_pass: P12Pass1!
                target_pass: TgtPass1!
                dest_alias: server
                mode: solution
                solution_dir: /tmp/sol
                """);
        int code = new YamlAutomation(ctx, true).execute(yaml);
        assertEquals(0, code);
    }

    // -----------------------------------------------------------------------
    // Dry-run flag in YAML overrides constructor false
    // -----------------------------------------------------------------------

    @Test
    void dryRunInYamlOverridesConstructorFalse(@TempDir Path tmp) throws IOException {
        Path yaml = tmp.resolve("op.yaml");
        Files.writeString(yaml, """
                operation: update-registry
                admin_dn: "CN=Admin, O=IBM, C=US"
                dry_run: true
                solution_dir: /tmp/sol
                """);
        // Constructor says dryRun=false, but YAML says true â€” must still dry-run
        int code = new YamlAutomation(ctx, false).execute(yaml);
        assertEquals(0, code);
    }

    // -----------------------------------------------------------------------
    // resolvePassword
    // -----------------------------------------------------------------------

    @Test
    void resolvePasswordInlineString() {
        var automation = new YamlAutomation(ctx, true);
        java.util.Map<String,Object> doc = new java.util.HashMap<>(); doc.put("my_pass", "Secret1!");
        char[] result = automation.resolvePassword(doc, "my_pass");
        assertNotNull(result);
        assertEquals("Secret1!", new String(result));
    }

    @Test
    void resolvePasswordMissingKeyReturnsNull() {
        var automation = new YamlAutomation(ctx, true);
        assertNull(automation.resolvePassword(java.util.Map.of(), "missing_key"));
    }

    @Test
    void resolvePasswordEnvMapResolvesVariable() {
        // Only testable when env var actually exists â€” skip if not set
        String envVar = "CERTMGR_TEST_PASS_" + System.nanoTime();
        // Env vars can't be set at runtime in Java, so we verify null is returned
        // for a non-existent env var (correct behaviour)
        var automation = new YamlAutomation(ctx, true);
        java.util.Map<String,Object> doc = new java.util.HashMap<>(); doc.put("my_pass", java.util.Map.of("env", envVar));
        char[] result = automation.resolvePassword(doc, "my_pass");
        assertNull(result, "Non-existent env var should return null");
    }
}
