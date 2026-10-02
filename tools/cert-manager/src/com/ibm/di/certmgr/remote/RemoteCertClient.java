/*
 * Copyright contributors to the SyncWeave project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.certmgr.remote;

import com.ibm.di.certmgr.model.CertChainDownloadResult;
import com.ibm.di.certmgr.model.DownloadedCertInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloads an HTTPS certificate chain from a remote host.
 *
 * <p>Mirrors Python {@code SdiRemoteCertClient}. Retrieval strategy:
 * <ol>
 *   <li><b>SSLSocket (primary)</b> — connects with a trust-all or default
 *       {@link SSLContext} and reads the peer's certificate chain via
 *       {@link SSLSocket#getSession()}.</li>
 *   <li><b>OpenSSL s_client (fallback)</b> — spawned via {@link ProcessBuilder}
 *       when the SSL socket approach fails; PEM blocks are extracted from stdout
 *       with a regex.</li>
 * </ol>
 *
 * <p>Security note: when {@code allowSelfSigned=true} a trust-all
 * {@link X509TrustManager} is used so the caller can inspect self-signed
 * certificates. When {@code false} the JVM default trust store is used.
 */
public class RemoteCertClient {

    private static final Logger log = LogManager.getLogger(RemoteCertClient.class);

    private static final Pattern PEM_PATTERN = Pattern.compile(
            "-----BEGIN CERTIFICATE-----\\s*(.+?)\\s*-----END CERTIFICATE-----",
            Pattern.DOTALL);

    private final int socketTimeoutSeconds;
    private final int opensslTimeoutSeconds;

    public RemoteCertClient() {
        this(10, 15);
    }

    public RemoteCertClient(final int socketTimeoutSeconds, final int opensslTimeoutSeconds) {
        this.socketTimeoutSeconds  = socketTimeoutSeconds;
        this.opensslTimeoutSeconds = opensslTimeoutSeconds;
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Download the certificate chain from {@code hostname:port}.
     *
     * @param hostname         target hostname
     * @param port             target TCP port (typically 443)
     * @param allowSelfSigned  when true skip TLS verification so self-signed
     *                         certificates can be retrieved
     * @return download result; never null
     */
    public CertChainDownloadResult fetchChain(
            final String hostname,
            final int port,
            final boolean allowSelfSigned) {

        log.info("Connecting to {}:{}...", hostname, port);
        try {
            List<byte[]> derChain = fetchDerChainViaSsl(hostname, port, allowSelfSigned);
            if (derChain.isEmpty()) {
                log.debug("SSL chain empty, trying OpenSSL s_client fallback");
                List<byte[]> pemChain = fetchPemChainViaOpenssl(hostname, port);
                if (pemChain.isEmpty()) {
                    return CertChainDownloadResult.failure(hostname, port,
                            "No certificates retrieved via SSL or OpenSSL");
                }
                return buildResult(hostname, port, pemsToDerList(pemChain), true);
            }
            return buildResult(hostname, port, derChain, false);

        } catch (Exception e) {
            log.error("Error connecting to {}:{}: {}", hostname, port, e.getMessage());
            return CertChainDownloadResult.failure(hostname, port, e.getMessage());
        }
    }

    /**
     * Download certificate chain using {@code openssl s_client -showcerts}.
     * Returns raw PEM bytes for each certificate; returns empty list on any error.
     */
    public List<byte[]> fetchChainWithOpenssl(
            final String hostname,
            final int port,
            final boolean allowSelfSigned) {
        return fetchPemChainViaOpenssl(hostname, port);
    }

    // -----------------------------------------------------------------------
    // SSL socket strategy
    // -----------------------------------------------------------------------

    private List<byte[]> fetchDerChainViaSsl(
            final String hostname, final int port, final boolean allowSelfSigned)
            throws Exception {

        SSLSocketFactory factory = allowSelfSigned
                ? trustAllFactory()
                : (SSLSocketFactory) SSLSocketFactory.getDefault();

        try (Socket raw = new Socket()) {
            raw.connect(new InetSocketAddress(hostname, port),
                    (int) TimeUnit.SECONDS.toMillis(socketTimeoutSeconds));
            try (SSLSocket ssl = (SSLSocket) factory.createSocket(
                    raw, hostname, port, true)) {
                ssl.setSoTimeout((int) TimeUnit.SECONDS.toMillis(socketTimeoutSeconds));
                ssl.startHandshake();

                Certificate[] chain = ssl.getSession().getPeerCertificates();
                List<byte[]> ders = new ArrayList<>();
                for (Certificate c : chain) {
                    try {
                        ders.add(c.getEncoded());
                    } catch (CertificateEncodingException e) {
                        log.warn("Skipping certificate that could not be DER-encoded: {}", e.getMessage());
                    }
                }
                log.info("Retrieved {} certificate(s) from {} via SSL", ders.size(), hostname);
                return ders;
            }
        }
    }

    // -----------------------------------------------------------------------
    // OpenSSL s_client fallback
    // -----------------------------------------------------------------------

    private List<byte[]> fetchPemChainViaOpenssl(final String hostname, final int port) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "openssl", "s_client",
                    "-connect", hostname + ":" + port,
                    "-showcerts");
            pb.redirectErrorStream(false);
            Process proc = pb.start();

            // Feed empty stdin so openssl doesn't wait for input
            proc.getOutputStream().close();

            boolean finished = proc.waitFor(opensslTimeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                log.error("OpenSSL connection to {}:{} timed out", hostname, port);
                return List.of();
            }

            String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            List<byte[]> pems = parsePemBlocks(output);
            if (!pems.isEmpty()) {
                log.info("Retrieved {} certificate(s) from {} via OpenSSL", pems.size(), hostname);
            } else {
                log.warn("No certificates found in OpenSSL output for {}", hostname);
            }
            return pems;

        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("No such file")) {
                log.debug("openssl not found on PATH, skipping fallback");
            } else {
                log.error("Error using OpenSSL to download certificates: {}", e.getMessage());
            }
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    // -----------------------------------------------------------------------
    // PEM parsing (package-visible for tests)
    // -----------------------------------------------------------------------

    /**
     * Extract all PEM certificate blocks from a string (e.g. openssl s_client output).
     * Returns raw PEM bytes (with headers) for each certificate found.
     */
    List<byte[]> parsePemBlocks(final String output) {
        List<byte[]> result = new ArrayList<>();
        Matcher m = PEM_PATTERN.matcher(output);
        while (m.find()) {
            String pem = "-----BEGIN CERTIFICATE-----\n"
                    + m.group(1).strip()
                    + "\n-----END CERTIFICATE-----\n";
            result.add(pem.getBytes(StandardCharsets.US_ASCII));
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Result construction
    // -----------------------------------------------------------------------

    private CertChainDownloadResult buildResult(
            final String hostname, final int port,
            final List<byte[]> derList,
            final boolean fromOpenssl) {

        List<DownloadedCertInfo> infos = new ArrayList<>();
        for (int i = 0; i < derList.size(); i++) {
            byte[] der = derList.get(i);
            byte[] pem = derToPem(der);
            String fp  = fingerprintSha256(der);
            String subj = i == 0 ? extractCnFromDer(der) : "Intermediate/Root CA";
            String iss  = i == 0 ? "N/A" : "CA";
            infos.add(new DownloadedCertInfo(i + 1, pem, fp, subj, iss));
        }
        log.info("Successfully retrieved {} certificate(s) from {}", infos.size(), hostname);
        return CertChainDownloadResult.success(hostname, port, infos);
    }

    // -----------------------------------------------------------------------
    // Crypto helpers (package-visible for tests)
    // -----------------------------------------------------------------------

    /**
     * Compute a colon-separated uppercase SHA-256 fingerprint of a DER-encoded certificate.
     */
    String fingerprintSha256(final byte[] der) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(der);
            StringBuilder sb = new StringBuilder(digest.length * 3 - 1);
            for (int i = 0; i < digest.length; i++) {
                if (i > 0) sb.append(':');
                sb.append(String.format("%02X", digest[i] & 0xFF));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "<sha256-unavailable>";
        }
    }

    /** Convert DER bytes to a PEM block (with header/footer). */
    static byte[] derToPem(final byte[] der) {
        String b64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der);
        String pem = "-----BEGIN CERTIFICATE-----\n" + b64 + "\n-----END CERTIFICATE-----\n";
        return pem.getBytes(StandardCharsets.US_ASCII);
    }

    /** Convert a list of PEM byte arrays to DER byte arrays. */
    private static List<byte[]> pemsToDerList(final List<byte[]> pems) {
        List<byte[]> ders = new ArrayList<>();
        for (byte[] pem : pems) {
            ders.add(pemToDer(pem));
        }
        return ders;
    }

    /** Decode a PEM block to DER bytes. */
    static byte[] pemToDer(final byte[] pem) {
        String s = new String(pem, StandardCharsets.US_ASCII);
        s = s.replaceAll("-----BEGIN CERTIFICATE-----", "")
             .replaceAll("-----END CERTIFICATE-----", "")
             .replaceAll("\\s+", "");
        return Base64.getDecoder().decode(s);
    }

    /** Best-effort CN extraction from DER (returns "N/A" on any parse failure). */
    private static String extractCnFromDer(final byte[] der) {
        try {
            java.security.cert.CertificateFactory cf =
                    java.security.cert.CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf.generateCertificate(
                    new java.io.ByteArrayInputStream(der));
            String dn = cert.getSubjectX500Principal().getName();
            // Extract CN= value
            for (String part : dn.split(",")) {
                String trimmed = part.trim();
                if (trimmed.startsWith("CN=")) return trimmed.substring(3);
            }
            return dn;
        } catch (Exception e) {
            return "N/A";
        }
    }

    /** Build a trust-all SSLSocketFactory (for self-signed cert retrieval). */
    private static SSLSocketFactory trustAllFactory() throws Exception {
        TrustManager[] trustAll = new TrustManager[]{
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] c, String a) { }
                public void checkServerTrusted(X509Certificate[] c, String a) { }
            }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, trustAll, new java.security.SecureRandom());
        return ctx.getSocketFactory();
    }
}
