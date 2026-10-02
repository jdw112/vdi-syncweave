/*
 * Copyright contributors to the SyncWeave project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.certmgr.audit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuditLoggerTest {

    @Test
    void buildEntryContainsTimestamp() {
        String entry = AuditLogger.buildEntry(
                AuditLogger.Op.REGEN_COMPLETE, "solution", "CN=Test", false,
                AuditLogger.Result.SUCCESS, null);
        assertTrue(entry.contains("\"ts\":"), "entry must contain ts field");
    }

    @Test
    void buildEntryContainsOp() {
        String entry = AuditLogger.buildEntry(
                AuditLogger.Op.IMPORT_CA_KEY, null, null, false,
                AuditLogger.Result.SUCCESS, null);
        assertTrue(entry.contains("\"op\":\"IMPORT_CA_KEY\""));
    }

    @Test
    void buildEntryContainsMode() {
        String entry = AuditLogger.buildEntry(
                AuditLogger.Op.DECRYPT_PROPS, "install", null, false,
                AuditLogger.Result.SUCCESS, null);
        assertTrue(entry.contains("\"mode\":\"install\""));
    }

    @Test
    void buildEntryOmitsModeWhenNull() {
        String entry = AuditLogger.buildEntry(
                AuditLogger.Op.KEYSTORE_BACKUP, null, null, false,
                AuditLogger.Result.SUCCESS, null);
        assertFalse(entry.contains("\"mode\""), "null mode must be omitted");
    }

    @Test
    void buildEntryContainsDetail() {
        String entry = AuditLogger.buildEntry(
                AuditLogger.Op.UPDATE_REGISTRY, "solution", "CN=Admin, O=IBM", false,
                AuditLogger.Result.SUCCESS, null);
        assertTrue(entry.contains("\"detail\":\"CN=Admin, O=IBM\""));
    }

    @Test
    void buildEntryOmitsDetailWhenNull() {
        String entry = AuditLogger.buildEntry(
                AuditLogger.Op.CREATE_STASH, "solution", null, false,
                AuditLogger.Result.SUCCESS, null);
        assertFalse(entry.contains("\"detail\""), "null detail must be omitted");
    }

    @Test
    void buildEntryDryRunTrue() {
        String entry = AuditLogger.buildEntry(
                AuditLogger.Op.REGEN_START, "solution", null, true,
                AuditLogger.Result.DRY_RUN, null);
        assertTrue(entry.contains("\"dryRun\":true"));
        assertTrue(entry.contains("\"result\":\"DRY_RUN\""));
    }

    @Test
    void buildEntryDryRunFalse() {
        String entry = AuditLogger.buildEntry(
                AuditLogger.Op.REGEN_COMPLETE, "solution", null, false,
                AuditLogger.Result.SUCCESS, null);
        assertTrue(entry.contains("\"dryRun\":false"));
    }

    @Test
    void buildEntryContainsErrorOnFailure() {
        String entry = AuditLogger.buildEntry(
                AuditLogger.Op.REGEN_FAILED, "solution", null, false,
                AuditLogger.Result.FAILURE, "keytool exited 1");
        assertTrue(entry.contains("\"error\":\"keytool exited 1\""));
    }

    @Test
    void buildEntryOmitsErrorWhenNull() {
        String entry = AuditLogger.buildEntry(
                AuditLogger.Op.REGEN_COMPLETE, "solution", null, false,
                AuditLogger.Result.SUCCESS, null);
        assertFalse(entry.contains("\"error\""), "null error must be omitted");
    }

    @Test
    void buildEntryIsValidJsonShape() {
        String entry = AuditLogger.buildEntry(
                AuditLogger.Op.ADD_CERT_HTTPS, "solution", "example.com:443", false,
                AuditLogger.Result.SUCCESS, null);
        assertTrue(entry.startsWith("{"), "must start with {");
        assertTrue(entry.endsWith("}"), "must end with }");
    }

    @Test
    void buildEntryEscapesDoubleQuoteInDetail() {
        String entry = AuditLogger.buildEntry(
                AuditLogger.Op.CA_KEY_IMPORT, "solution", "CN=\"Test\"", false,
                AuditLogger.Result.SUCCESS, null);
        assertTrue(entry.contains("CN=\\\"Test\\\""), "double quotes must be escaped");
    }

    @Test
    void buildEntryEscapesBackslashInDetail() {
        String entry = AuditLogger.buildEntry(
                AuditLogger.Op.KEYSTORE_BACKUP, "solution", "C:\\path\\file", false,
                AuditLogger.Result.SUCCESS, null);
        assertTrue(entry.contains("C:\\\\path\\\\file"), "backslashes must be escaped");
    }

    @Test
    void buildEntryEscapesNewlineInDetail() {
        String entry = AuditLogger.buildEntry(
                AuditLogger.Op.ENCRYPT_PROPS, "solution", "line1\nline2", false,
                AuditLogger.Result.SUCCESS, null);
        assertTrue(entry.contains("\\n"), "newline must be escaped as \\n");
        assertFalse(entry.contains("\n"), "raw newline must not appear in entry");
    }

    @Test
    void allOpsAreLoggable() {
        for (AuditLogger.Op op : AuditLogger.Op.values()) {
            String entry = AuditLogger.buildEntry(op, null, null, false,
                    AuditLogger.Result.SUCCESS, null);
            assertTrue(entry.contains(op.name()),
                    "op " + op + " must appear in entry");
        }
    }

    @Test
    void allResultsAreLoggable() {
        for (AuditLogger.Result r : AuditLogger.Result.values()) {
            String entry = AuditLogger.buildEntry(AuditLogger.Op.REGEN_COMPLETE,
                    null, null, false, r, null);
            assertTrue(entry.contains(r.name()),
                    "result " + r + " must appear in entry");
        }
    }
}