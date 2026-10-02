/*
 * Copyright contributors to the SyncWeave project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.certmgr.model;

/**
 * Metadata and raw PEM bytes for a single certificate downloaded from a remote host.
 *
 * <p>Mirrors the Python {@code DownloadedCertInfo} dataclass.</p>
 *
 * @param index              0-based position in the downloaded chain
 * @param pemBytes           raw PEM-encoded certificate bytes
 * @param fingerprintSha256  hex SHA-256 fingerprint string
 * @param subject            certificate subject DN, or "N/A"
 * @param issuer             certificate issuer DN, or "N/A"
 */
public record DownloadedCertInfo(
        int index,
        byte[] pemBytes,
        String fingerprintSha256,
        String subject,
        String issuer
) {
    /** Convenience factory with default subject/issuer values. */
    public static DownloadedCertInfo of(
            final int index,
            final byte[] pemBytes,
            final String fingerprintSha256) {
        return new DownloadedCertInfo(index, pemBytes, fingerprintSha256, "N/A", "N/A");
    }
}