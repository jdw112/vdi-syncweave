/*
 * Copyright contributors to the SyncWeave project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.certmgr.model;

import java.util.List;

/**
 * Structured result from a remote certificate chain download.
 *
 * <p>Mirrors the Python {@code CertChainDownloadResult} dataclass.</p>
 *
 * @param success       true when the download completed without error
 * @param hostname      the target hostname
 * @param port          the target port
 * @param certificates  ordered list of downloaded certificates
 * @param errorMessage  failure description when success is false, else null
 */
public record CertChainDownloadResult(
        boolean success,
        String hostname,
        int port,
        List<DownloadedCertInfo> certificates,
        String errorMessage
) {
    /**
     * Returns the raw PEM bytes for each certificate in chain order.
     * Mirrors Python {@code pem_list} property.
     */
    public List<byte[]> pemList() {
        return certificates.stream()
                .map(DownloadedCertInfo::pemBytes)
                .toList();
    }

    /** Convenience factory for a successful download. */
    public static CertChainDownloadResult success(
            final String hostname,
            final int port,
            final List<DownloadedCertInfo> certs) {
        return new CertChainDownloadResult(true, hostname, port,
                List.copyOf(certs), null);
    }

    /** Convenience factory for a failed download. */
    public static CertChainDownloadResult failure(
            final String hostname,
            final int port,
            final String errorMessage) {
        return new CertChainDownloadResult(false, hostname, port,
                List.of(), errorMessage);
    }
}