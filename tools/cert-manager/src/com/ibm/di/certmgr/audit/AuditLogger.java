/*
 * Copyright contributors to the SyncWeave project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.certmgr.audit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;

/**
 * Structured audit logger for the SDI Certificate Manager.
 *
 * <p>Every mutating operation emits a single JSON-lines entry to the
 * {@code audit} Log4j2 logger, which is wired exclusively to
 * {@code logs/cert-manager-audit.log} (see {@code log4j2.xml}).
 * The console and main rolling log are not affected.
 *
 * <p>Entry format (one line, newline-terminated):
 * <pre>
 * {"ts":"2026-01-15T12:34:56.789Z","op":"REGEN_COMPLETE","mode":"solution",
 *  "detail":"CN=API Admin, OU=test","dryRun":false,"result":"SUCCESS"}
 * </pre>
 *
 * <p>All methods are null-safe; null strings are serialised as {@code ""}.
 */
public final class AuditLogger {

    private static final Logger AUDIT = LogManager.getLogger("audit");

    /** Operations tracked in the audit log. */
    public enum Op {
        REGEN_START,
        REGEN_COMPLETE,
        REGEN_FAILED,
        DECRYPT_PROPS,
        ENCRYPT_PROPS,
        CREATE_STASH,
        UPDATE_REGISTRY,
        ADD_CERT_HTTPS,
        IMPORT_CA_KEY,
        KEYSTORE_BACKUP,
        KEYSTORE_RESTORE,
        KEYSTORE_PASSWD_CHANGE,
        KEYSTORE_KEY_DELETE,
        KEYSTORE_IMPORT_P12,
        KEYSTORE_EXPORT_CERT,
        CERT_CHAIN_INGEST,
        CERT_CHAIN_ROLLBACK,
        CA_KEY_IMPORT,
        CA_KEY_CROSS_IMPORT,
    }

    /** Outcome of the audited operation. */
    public enum Result { SUCCESS, FAILURE, DRY_RUN }

    private AuditLogger() {}

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Emit an audit entry for a completed (or dry-run) operation.
     *
     * @param op      the operation type
     * @param mode    directory mode string ({@code "install"} or
     *                {@code "solution"}), may be null
     * @param detail  free-text detail (alias, DN, path, etc.), may be null
     * @param dryRun  whether this was a dry-run execution
     * @param result  outcome
     */
    public static void log(final Op op,
                           final String mode,
                           final String detail,
                           final boolean dryRun,
                           final Result result) {
        AUDIT.info(buildEntry(op, mode, detail, dryRun, result, null));
    }

    /**
     * Emit an audit entry that carries an error message.
     *
     * @param op      the operation type
     * @param mode    directory mode string, may be null
     * @param detail  free-text detail, may be null
     * @param error   error message to include, may be null
     */
    public static void logFailure(final Op op,
                                  final String mode,
                                  final String detail,
                                  final String error) {
        AUDIT.info(buildEntry(op, mode, detail, false, Result.FAILURE, error));
    }

    // -----------------------------------------------------------------------
    // Entry builder
    // -----------------------------------------------------------------------

    /** Package-private for unit testing. */
    static String buildEntry(final Op op,
                             final String mode,
                             final String detail,
                             final boolean dryRun,
                             final Result result,
                             final String error) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"ts\":\"").append(Instant.now()).append('"');
        sb.append(",\"op\":\"").append(op.name()).append('"');
        if (mode != null && !mode.isBlank()) {
            sb.append(",\"mode\":\"").append(jsonEscape(mode)).append('"');
        }
        if (detail != null && !detail.isBlank()) {
            sb.append(",\"detail\":\"").append(jsonEscape(detail)).append('"');
        }
        sb.append(",\"dryRun\":").append(dryRun);
        sb.append(",\"result\":\"").append(result.name()).append('"');
        if (error != null && !error.isBlank()) {
            sb.append(",\"error\":\"").append(jsonEscape(error)).append('"');
        }
        sb.append('}');
        return sb.toString();
    }

    /** Minimal JSON string escaping (backslash, double-quote, control chars). */
    private static String jsonEscape(final String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default   -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}