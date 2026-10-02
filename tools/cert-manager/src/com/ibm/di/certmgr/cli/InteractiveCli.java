/*
 * Copyright contributors to the SyncWeave project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.certmgr.cli;

import com.ibm.di.certmgr.keystore.KeystoreManager;
import com.ibm.di.certmgr.model.AppContext;
import com.ibm.di.certmgr.model.DirectoryMode;
import com.ibm.di.certmgr.properties.PropertiesFileHandler;
import com.ibm.di.certmgr.properties.PropertiesFileHandler.ActiveKeystoreInfo;
import com.ibm.di.certmgr.properties.SdiPropertiesStore;
import com.ibm.di.certmgr.regen.RegistryFileUpdater;
import com.ibm.di.certmgr.remote.CertChainIngester;
import com.ibm.di.certmgr.remote.RemoteCertClient;
import com.ibm.di.certmgr.workflow.CaKeyImportSpec;
import com.ibm.di.certmgr.workflow.CaKeyImportWorkflow;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.Console;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * Interactive 15-item menu loop.
 *
 * <p>On startup, {@link #initialize()} runs a setup wizard that mirrors the
 * Python {@code initialize_application()} function:
 * <ol>
 *   <li>Auto-detect the SDI installation directory.</li>
 *   <li>Prompt for install dir, confirming the detected default with Enter.</li>
 *   <li>Select directory mode (install / solution).</li>
 *   <li>Prompt for solution dir (reads default from defaultSolDir.bat/.sh).</li>
 *   <li>Prompt for the server identity JKS keystore path and password.</li>
 *   <li>Validate the password against the keystore (up to 3 attempts).</li>
 *   <li>Resolve the active trust keystore from
 *       {@code api.client.ssl.custom.properties.on}.</li>
 * </ol>
 *
 * <p><b>Keystore targeting rules:</b>
 * <ul>
 *   <li>Items 4, 5, 8 (add/import trusted certs) always target the
 *       <em>trust keystore</em> ({@code api.client.keystore} when
 *       {@code api.client.ssl.custom.properties.on=true}, else the server
 *       keystore).</li>
 *   <li>Items 1-3, 6, 7, 9 use a keystore picker when the server and client
 *       trust keystores differ; otherwise they operate on the server keystore
 *       directly (no extra prompt needed).</li>
 *   <li>Items 3, 6, 7 present a numbered alias list so the user never has to
 *       type alias names from memory.</li>
 * </ul>
 */
public class InteractiveCli {

    private static final Logger log = LogManager.getLogger(InteractiveCli.class);
    private static final int    SEP = 62; // separator width matches Python output

    private AppContext ctx;
    private final Scanner scanner;

    public InteractiveCli(final AppContext ctx) {
        this.ctx     = ctx;
        this.scanner = new Scanner(System.in);
    }

    /** Testable constructor accepting an injected Scanner. */
    InteractiveCli(final AppContext ctx, final Scanner scanner) {
        this.ctx     = ctx;
        this.scanner = scanner;
    }

    // -----------------------------------------------------------------------
    // Main loop
    // -----------------------------------------------------------------------

    /** Run the startup wizard then the menu loop until the user selects 0 (Exit). */
    public void run() {
        System.out.println("=".repeat(70));
        System.out.println("SDI Certificate Manager");
        System.out.println("=".repeat(70));
        System.out.println();

        ctx = initialize();

        while (true) {
            printMenu();
            String choice = prompt("\nEnter your choice: ").strip();

            if ("0".equals(choice)) {
                System.out.println("\nGoodbye!");
                break;
            }

            dispatch(choice);
            prompt("\nPress Enter to continue...");
        }
    }

    // -----------------------------------------------------------------------
    // Startup wizard  (mirrors Python initialize_application())
    // -----------------------------------------------------------------------

    /**
     * Interactive setup wizard.  Returns a fully-populated {@link AppContext}
     * including the resolved trust keystore path and a validated password.
     */
    AppContext initialize() {

        // --- 1. Install directory -------------------------------------------
        String detectedInstall = detectSdiInstallDir();
        String defaultInstall  = detectedInstall != null ? detectedInstall
                : (isWindows() ? "C:\\Program Files\\IBM\\TDI\\V11"
                               : "/opt/IBM/TDI/V11");

        if (detectedInstall != null) {
            System.out.println("[OK] Auto-detected SDI installation: " + defaultInstall);
        }

        String currentInstall = ctx.installDir() != null && !ctx.installDir().isBlank()
                ? ctx.installDir() : null;

        String installDir;
        if (currentInstall != null) {
            System.out.println("Using install directory from --install-dir: " + currentInstall);
            installDir = currentInstall;
        } else {
            String raw = prompt("Enter SDI installation directory [" + defaultInstall + "]: ").strip();
            installDir = raw.isBlank() ? defaultInstall : raw;
        }
        log.info("Install directory: {}", installDir);

        // --- 2. Keytool location -------------------------------------------
        String keytool = ctx.keytoolPath() != null && !ctx.keytoolPath().isBlank()
                && !ctx.keytoolPath().equals("keytool")
                ? ctx.keytoolPath()
                : resolveKeytool(installDir);
        System.out.println("[OK] Using keytool at: " + keytool);

        // --- 3. Directory mode -----------------------------------------------
        System.out.println();
        System.out.println("Directory mode:");
        System.out.println("  1. Solution directory (recommended for most deployments)");
        System.out.println("  2. Install directory");
        String modeChoice = prompt("Select mode [1]: ").strip();
        DirectoryMode mode = "2".equals(modeChoice) ? DirectoryMode.INSTALL : DirectoryMode.SOLUTION;
        System.out.printf("[OK] Selected mode: %s  (properties file: %s)%n",
                mode.getValue(), mode.getPropertiesFilename());

        // --- 4. Solution / base directory ------------------------------------
        System.out.println();
        String solutionDir;
        if (mode == DirectoryMode.INSTALL) {
            solutionDir = installDir;
            System.out.println("Using install directory: " + installDir);
        } else {
            String currentSol = ctx.solutionDir() != null && !ctx.solutionDir().isBlank()
                    ? ctx.solutionDir() : null;
            if (currentSol != null) {
                System.out.println("Using solution directory from --solution-dir: " + currentSol);
                solutionDir = currentSol;
            } else {
                String defaultSol = readDefaultSolutionDir(Path.of(installDir));
                if (defaultSol == null) defaultSol = installDir;
                else System.out.println("[OK] Default solution directory from defaultSolDir script: " + defaultSol);
                String rawSol = prompt("Enter solution directory [" + defaultSol + "]: ").strip();
                solutionDir = rawSol.isBlank() ? defaultSol : rawSol;
            }
        }
        log.info("Solution directory: {}", solutionDir);

        // --- 5. Server identity JKS path ------------------------------------
        System.out.println();
        String baseDir = mode == DirectoryMode.INSTALL ? installDir : solutionDir;
        String defaultJks = resolveDefaultJks(mode, baseDir);

        String currentJks = ctx.jksPath() != null && !ctx.jksPath().isBlank()
                ? ctx.jksPath() : null;
        String jksPath;
        if (currentJks != null) {
            System.out.println("Using keystore from --jks: " + currentJks);
            jksPath = currentJks;
        } else {
            String rawJks = prompt("Enter server keystore path [" + defaultJks + "]: ").strip();
            jksPath = rawJks.isBlank() ? defaultJks : rawJks;
        }
        log.info("Server keystore path: {}", jksPath);

        // --- 6. Server keystore password (with validation, up to 3 attempts) -
        boolean jksExists = Files.exists(Path.of(jksPath));
        char[] storepass = promptValidatedPassword(
                "server keystore", keytool, jksPath, jksExists);

        // --- 7. Active trust keystore  (api.client.ssl.custom.properties.on) -
        System.out.println();
        String trustJksPath;
        char[] trustStorepass;
        String trustLabel;

        ActiveKeystoreInfo activeKs = resolveActiveKeystoreInfo(mode, baseDir);

        if (activeKs.customClientSsl()) {
            String clientJksStr = activeKs.primaryJks() != null
                    ? activeKs.primaryJks().toString()
                    : Path.of(baseDir, "serverapi", "testadmin.jks").toString();
            System.out.println("[OK] api.client.ssl.custom.properties.on=true detected.");
            System.out.println("     Trusted certificates (menu items 4, 5, 8) will be added to");
            System.out.println("     the CLIENT trust keystore: " + clientJksStr);
            String rawTrustJks = prompt("Enter client trust keystore path [" + clientJksStr + "]: ").strip();
            trustJksPath = rawTrustJks.isBlank() ? clientJksStr : rawTrustJks;
            boolean trustJksExists = Files.exists(Path.of(trustJksPath));
            trustStorepass = promptValidatedPassword(
                    "client trust keystore", keytool, trustJksPath, trustJksExists);
            trustLabel     = "client";
        } else {
            trustJksPath   = jksPath;
            trustStorepass = storepass;
            trustLabel     = "server";
            System.out.println("[OK] api.client.ssl.custom.properties.on=false (default).");
            System.out.println("     Trusted certificates will be added to the server keystore: " + jksPath);
        }
        log.info("Active trust keystore: {} (label={})", trustJksPath, trustLabel);

        // --- Summary --------------------------------------------------------
        System.out.println();
        System.out.println("=".repeat(70));
        System.out.printf("  Install dir        : %s%n", installDir);
        System.out.printf("  Solution dir       : %s%n", solutionDir);
        System.out.printf("  Mode               : %s%n", mode.getValue());
        System.out.printf("  Server keystore    : %s  %s%n", jksPath,
                jksExists ? "[exists]" : "[NOT FOUND - will need creating]");
        if (!trustJksPath.equals(jksPath)) {
            boolean trustExists = Files.exists(Path.of(trustJksPath));
            System.out.printf("  Client trust KS    : %s  %s%n", trustJksPath,
                    trustExists ? "[exists]" : "[NOT FOUND]");
        }
        System.out.println("=".repeat(70));

        return new AppContext(keytool, jksPath, storepass, installDir, solutionDir, mode, null,
                trustJksPath, trustStorepass, trustLabel);
    }

    // -----------------------------------------------------------------------
    // Auto-detection helpers
    // -----------------------------------------------------------------------

    String detectSdiInstallDir() {
        try {
            Path javaHome = Path.of(System.getProperty("java.home")).toRealPath();
            Path candidate = javaHome;
            for (int i = 0; i < 4; i++) {
                candidate = candidate.getParent();
                if (candidate == null) break;
                if (isSdiRoot(candidate)) {
                    log.debug("detectSdiInstallDir: found SDI root at {}", candidate);
                    return candidate.toString();
                }
            }
        } catch (Exception e) {
            log.debug("detectSdiInstallDir: {}", e.getMessage());
        }
        return null;
    }

    private static boolean isSdiRoot(final Path p) {
        for (String sub : new String[]{"bin", "etc", "serverapi"}) {
            if (!Files.isDirectory(p.resolve(sub))) return false;
        }
        return true;
    }

    String readDefaultSolutionDir(final Path installPath) {
        if (!Files.isDirectory(installPath)) return null;
        String scriptName = "defaultSolDir" + (isWindows() ? ".bat" : ".sh");
        Path script = installPath.resolve("bin").resolve(scriptName);
        if (!Files.exists(script)) return null;
        try {
            for (String line : Files.readAllLines(script, StandardCharsets.UTF_8)) {
                String stripped = line.strip();
                if (stripped.toUpperCase().startsWith("SET ")) stripped = stripped.substring(4).strip();
                if (!stripped.contains("TDI_SOLDIR=")) continue;
                String value = stripped.split("=", 2)[1].strip().replaceAll("^[\"']|[\"']$", "");
                if (!value.isBlank()) return value;
            }
        } catch (IOException e) {
            log.debug("readDefaultSolutionDir: {}", e.getMessage());
        }
        return null;
    }

    private String resolveDefaultJks(final DirectoryMode mode, final String baseDir) {
        try {
            PropertiesFileHandler ph = new PropertiesFileHandler(mode, Path.of(baseDir));
            Map<String, Path> ks = ph.readKeystoreNames();
            Path server = ks.get("server");
            if (server != null && Files.exists(server)) return server.toString();
            if (server != null) log.debug("resolveDefaultJks: properties says {} but not found", server);
        } catch (Exception ignored) { /* fall through */ }
        Path[] candidates = {
            Path.of(baseDir, "testserver.jks"),
            Path.of(baseDir, "serverapi", "testserver.jks"),
            Path.of(baseDir, "testadmin.jks"),
            Path.of(baseDir, "serverapi", "testadmin.jks")
        };
        for (Path c : candidates) {
            if (Files.exists(c)) return c.toString();
        }
        return Path.of(baseDir, "serverapi", "testserver.jks").toString();
    }

    private ActiveKeystoreInfo resolveActiveKeystoreInfo(final DirectoryMode mode,
                                                          final String baseDir) {
        try {
            PropertiesFileHandler ph = new PropertiesFileHandler(mode, Path.of(baseDir));
            return ph.resolveActiveKeystores();
        } catch (Exception e) {
            log.debug("resolveActiveKeystoreInfo: {}", e.getMessage());
            return new ActiveKeystoreInfo(
                    Path.of(baseDir, "testserver.jks"), null, false, "server");
        }
    }

    private String resolveKeytool(final String installDir) {
        if (installDir != null && !installDir.isBlank()) {
            for (String rel : new String[]{
                    "jvm/jre/bin/keytool", "jvm/bin/keytool",
                    "jvm/jre/bin/keytool.exe", "jvm/bin/keytool.exe"}) {
                Path c = Path.of(installDir, rel);
                if (Files.exists(c)) return c.toString();
            }
        }
        String javaHome = System.getProperty("java.home");
        for (String rel : new String[]{"bin/keytool", "bin/keytool.exe"}) {
            Path c = Path.of(javaHome, rel);
            if (Files.exists(c)) return c.toString();
        }
        return "keytool";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    // -----------------------------------------------------------------------
    // Menu  (matches Python output format exactly)
    // -----------------------------------------------------------------------

    private void printMenu() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("SDI Certificate Manager - Main Menu");
        System.out.println("=".repeat(70));
        System.out.println("Certificate Management:");
        System.out.println("  1. Backup keystore");
        System.out.println("  2. List all entries (keyEntries and trustedCertEntries)");
        System.out.println("  3. View certificate details");
        System.out.println("  4. Add certificate from HTTPS/LDAPS endpoint");
        System.out.println("  5. Add certificate from file (.crt, .cer, .pem, .der)");
        System.out.println("  6. Delete entry (keyEntry or trustedCertEntry)");
        System.out.println("  7. Export certificate to file");
        System.out.println("  8. Quick import from HTTPS/LDAPS (delete old + import new)");
        System.out.println("  9. Convert JKS to PKCS12");
        System.out.println("\nSDI Certificate Regeneration:");
        System.out.println(" 10. Change keystore passwords and regenerate certificates");
        System.out.println(" 11. Decrypt properties file");
        System.out.println(" 12. Encrypt properties file");
        System.out.println(" 13. Create stash file");
        System.out.println(" 14. Update registry.txt (admin certificate DN / Layer-2 auth)");
        System.out.println(" 15. Import CA-issued certificate (PKCS#12 / PFX)");
        System.out.println("\n  0. Exit");
        System.out.println("=".repeat(70));
    }

    // -----------------------------------------------------------------------
    // Dispatch
    // -----------------------------------------------------------------------

    @SuppressWarnings("java:S1479")   // switch with many cases is intentional
    private void dispatch(final String choice) {
        try {
            switch (choice) {
                case "1"  -> cmdBackupKeystore();
                case "2"  -> cmdListEntries();
                case "3"  -> cmdViewCertDetails();
                case "4"  -> cmdAddFromHttps();
                case "5"  -> cmdAddFromFile();
                case "6"  -> cmdDeleteEntry();
                case "7"  -> cmdExportCert();
                case "8"  -> cmdQuickImportHttps();
                case "9"  -> cmdConvertToP12();
                case "10" -> cmdRegenerateCerts();
                case "11" -> cmdDecryptProperties();
                case "12" -> cmdEncryptProperties();
                case "13" -> cmdCreateStash();
                case "14" -> cmdUpdateRegistry();
                case "15" -> cmdImportCaKey();
                default   -> System.out.println("Invalid choice. Please try again.");
            }
        } catch (Exception e) {
            log.error("Command failed: {}", e.getMessage(), e);
            System.out.println("Error: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Keystore picker
    // -----------------------------------------------------------------------

    /**
     * Return a {@link KeystoreManager} for the operation.
     *
     * <p>When server and trust keystores are the same (the common case) there
     * is nothing to ask -- returns the server manager directly.  When they
     * differ, presents a numbered list of known keystores plus an "Other"
     * option.
     */
    private KeystoreManager pickKeystoreManager(final String actionLabel) {
        String serverJks = ctx.jksPath();
        String trustJks  = ctx.effectiveTrustJksPath();
        boolean hasSeparateTrust = !trustJks.equals(serverJks);

        if (!hasSeparateTrust) {
            // Only one keystore known -- use it without prompting
            if (!Files.exists(Path.of(serverJks))) {
                System.out.println("Keystore not found: " + serverJks);
                return null;
            }
            return serverKeystoreManager();
        }

        System.out.println("\nSelect keystore for: " + actionLabel);

        // Build option list dynamically
        List<String> labels   = new ArrayList<>();
        List<String> paths    = new ArrayList<>();
        List<char[]> passes   = new ArrayList<>();

        labels.add(Path.of(serverJks).getFileName() + "  [server]");
        paths.add(serverJks);
        passes.add(ctx.storepass());

        labels.add(Path.of(trustJks).getFileName() + "  [" + ctx.trustLabel() + "]");
        paths.add(trustJks);
        passes.add(ctx.effectiveTrustStorepass());

        labels.add("Other keystore (enter path manually)");
        paths.add(null);
        passes.add(null);

        for (int i = 0; i < labels.size(); i++) {
            System.out.printf("  %d. %s%n", i + 1, labels.get(i));
        }

        String raw = prompt("Choice [1]: ").strip();
        int choice;
        try {
            choice = raw.isBlank() ? 1 : Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            choice = -1;
        }

        if (choice >= 1 && choice <= 2) {
            String ksPath = paths.get(choice - 1);
            char[] ksPass = passes.get(choice - 1);
            if (!Files.exists(Path.of(ksPath))) {
                System.out.println("Keystore not found: " + ksPath);
                return null;
            }
            return new KeystoreManager(ctx.keytoolPath(), ksPath, ksPass);
        } else if (choice == 3) {
            String manualPath = prompt("Enter keystore path: ").strip();
            if (manualPath.isBlank()) return null;
            if (!Files.exists(Path.of(manualPath))) {
                System.out.println("File not found: " + manualPath);
                return null;
            }
            char[] manualPass = promptPassword("Enter keystore password: ");
            return new KeystoreManager(ctx.keytoolPath(), manualPath, manualPass);
        } else {
            System.out.println("Cancelled.");
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Alias picker  (matches Python "Available entries" format)
    // -----------------------------------------------------------------------

    /**
     * Show all aliases in the keystore as a numbered list and let the user
     * pick one by number or by typing the alias name.
     *
     * <p>Output format mirrors the Python {@code show_cert_details_menu}:
     * <pre>
     * Available entries:
     *   1. admin (keyEntry)
     *   2. google (trustedCertEntry)
     *   ...
     * </pre>
     *
     * @param mgr   keystore to enumerate
     * @param label action name shown in the heading
     * @return selected alias string, or null if cancelled / empty keystore
     */
    private String pickAlias(final KeystoreManager mgr, final String label) {
        Map<String, List<String>> entries = mgr.listEntries();
        List<String> keys  = entries.get("keyEntry");
        List<String> certs = entries.get("trustedCertEntry");

        // key entries first, then trusted cert entries (same order as Python)
        List<String> all   = new ArrayList<>();
        List<String> types = new ArrayList<>();
        for (String a : keys)  { all.add(a); types.add("keyEntry"); }
        for (String a : certs) { all.add(a); types.add("trustedCertEntry"); }

        if (all.isEmpty()) {
            System.out.println("No entries found in keystore.");
            return null;
        }

        System.out.println();
        System.out.println("Available entries:");
        for (int i = 0; i < all.size(); i++) {
            System.out.printf("  %d. %s (%s)%n", i + 1, all.get(i), types.get(i));
        }

        String raw = prompt("\nEnter number or alias name (Enter=cancel): ").strip();
        if (raw.isBlank()) return null;

        // Try as a number first
        try {
            int n = Integer.parseInt(raw);
            if (n >= 1 && n <= all.size()) return all.get(n - 1);
            System.out.println("Number out of range.");
            return null;
        } catch (NumberFormatException ignored) { /* fall through */ }

        // Case-insensitive match on typed alias
        for (String a : all) {
            if (a.equalsIgnoreCase(raw)) return a;
        }

        // Accept as-is (user may know an alias not yet reflected in the list)
        System.out.println("Note: '" + raw + "' not in listed entries (proceeding anyway).");
        return raw;
    }

    // -----------------------------------------------------------------------
    // Command implementations
    // -----------------------------------------------------------------------

    /** 1 -- Backup keystore */
    private void cmdBackupKeystore() {
        System.out.println("\n--- Backup Keystore ---");
        KeystoreManager mgr = pickKeystoreManager("backup");
        if (mgr == null) return;
        mgr.backupKeystore().ifPresentOrElse(
            p -> System.out.println("Backup created: " + p),
            ()  -> System.out.println("Backup failed -- check logs"));
    }

    /** 2 -- List all entries  (Python-style output) */
    private void cmdListEntries() {
        System.out.println("\n--- List Keystore Entries ---");
        KeystoreManager mgr = pickKeystoreManager("list entries");
        if (mgr == null) return;

        Map<String, List<String>> entries = mgr.listEntries();
        List<String> keys  = entries.get("keyEntry");
        List<String> certs = entries.get("trustedCertEntry");
        int total = keys.size() + certs.size();

        System.out.println();
        System.out.println("-".repeat(SEP));
        System.out.println("Keystore Entries");
        System.out.println("-".repeat(SEP));

        if (total == 0) {
            System.out.println("\nNo entries found. Check the keystore path and password are correct.");
            System.out.println("-".repeat(SEP));
            return;
        }

        System.out.printf("%nKey Entries (%d):%n", keys.size());
        for (int i = 0; i < keys.size(); i++) {
            System.out.printf("  %d. %s%n", i + 1, keys.get(i));
        }

        System.out.printf("%nTrusted Certificate Entries (%d):%n", certs.size());
        for (int i = 0; i < certs.size(); i++) {
            System.out.printf("  %d. %s%n", i + 1, certs.get(i));
        }

        System.out.println("-".repeat(SEP));
    }

    /** 3 -- View certificate details  (alias picker + keytool output to console) */
    private void cmdViewCertDetails() {
        System.out.println("\n--- Certificate Details ---");
        KeystoreManager mgr = pickKeystoreManager("view certificate details");
        if (mgr == null) return;

        String alias = pickAlias(mgr, "view details");
        if (alias == null) return;

        System.out.println("\nDetails for alias: '" + alias + "'");
        System.out.println("-".repeat(SEP));

        // Invoke keytool directly so the full verbose output goes to the console
        KeystoreManager.KeytoolResult r = mgr.runKeytool(
                List.of("-list", "-v",
                        "-keystore", mgr.getKeystorePath().toString(),
                        "-storetype", mgr.getStoretype(),
                        "-storepass:env", KeystoreManager.ENV_STOREPASS,
                        "-alias", alias),
                "list", false, null);
        if (r.success()) {
            System.out.println(r.stdout().strip());
        } else {
            System.out.println("Failed to retrieve details: " + r.stderr());
        }
    }

    /** 4 -- Add certificate from HTTPS/LDAPS  --> trust keystore */
    private void cmdAddFromHttps() {
        System.out.println("\n--- Add Certificate from HTTPS/LDAPS ---");
        System.out.println("Target trust keystore: " + ctx.effectiveTrustJksPath()
                + "  [" + ctx.trustLabel() + "]");

        String host    = prompt("Hostname: ").strip();
        String portStr = prompt("Port [443]: ").strip();
        int port       = portStr.isBlank() ? 443 : Integer.parseInt(portStr);
        String alias   = prompt("Alias to use: ").strip();
        if (host.isBlank() || alias.isBlank()) return;

        RemoteCertClient client = new RemoteCertClient();
        var result = client.fetchChain(host, port, true);
        if (!result.success()) { System.out.println("Download failed: " + result.errorMessage()); return; }

        System.out.println("Downloaded " + result.certificates().size() + " certificate(s):");
        result.certificates().forEach(c ->
            System.out.printf("  [%d] %s  %s%n", c.index(), c.subject(), c.fingerprintSha256()));

        if (!confirm("Import these certificates into " + ctx.trustLabel() + " keystore?")) return;
        var ir = new CertChainIngester().ingest(result.pemList(), trustKeystoreManager(), alias, false);
        if (ir.success()) System.out.println("Imported: " + ir.importedAliases());
        else System.out.println("Import failed: " + ir.errorMessage());
    }

    /** 5 -- Add certificate from file  --> trust keystore */
    private void cmdAddFromFile() {
        System.out.println("\n--- Add Certificate from File (.crt, .cer, .pem, .der) ---");
        System.out.println("Target trust keystore: " + ctx.effectiveTrustJksPath()
                + "  [" + ctx.trustLabel() + "]");

        String filePath = prompt("Enter certificate file path: ").strip();
        String alias    = prompt("Enter alias name: ").strip();
        if (filePath.isBlank() || alias.isBlank()) return;
        Path certFile = Path.of(filePath);
        if (!Files.exists(certFile)) { System.out.println("Certificate file not found: " + filePath); return; }
        boolean ok = trustKeystoreManager().importCertificate(alias, certFile);
        System.out.println(ok
                ? "[OK] Successfully imported certificate as '" + alias + "'"
                : "[!!] Failed to import certificate");
    }

    /** 6 -- Delete entry  (alias picker) */
    private void cmdDeleteEntry() {
        System.out.println("\n--- Delete Entry ---");
        KeystoreManager mgr = pickKeystoreManager("delete entry");
        if (mgr == null) return;

        String alias = pickAlias(mgr, "delete");
        if (alias == null) return;

        if (!confirm("Are you sure you want to delete '" + alias + "'?")) {
            System.out.println("Cancelled");
            return;
        }
        boolean ok = mgr.deleteEntry(alias);
        System.out.println(ok ? "[OK] Deleted '" + alias + "'" : "[!!] Delete failed -- check logs");
    }

    /** 7 -- Export certificate to file  (alias picker + suggested filename) */
    private void cmdExportCert() {
        System.out.println("\n--- Export Certificate to File ---");
        KeystoreManager mgr = pickKeystoreManager("export certificate");
        if (mgr == null) return;

        String alias = pickAlias(mgr, "export");
        if (alias == null) return;

        String defaultOut = alias.replaceAll("[^a-zA-Z0-9._-]", "_") + ".pem";
        String rawOut = prompt("Output file path [" + defaultOut + "]: ").strip();
        String output = rawOut.isBlank() ? defaultOut : rawOut;

        boolean ok = mgr.exportCertificate(alias, Path.of(output));
        System.out.println(ok
                ? "[OK] Exported '" + alias + "' to " + output
                : "[!!] Export failed -- check logs");
    }

    /** 8 -- Quick import from HTTPS/LDAPS (delete old + import new)  --> trust keystore */
    private void cmdQuickImportHttps() {
        System.out.println("\n--- Quick Import from HTTPS (Delete + Import) ---");
        System.out.println("Target trust keystore: " + ctx.effectiveTrustJksPath()
                + "  [" + ctx.trustLabel() + "]");

        String host    = prompt("Hostname: ").strip();
        String portStr = prompt("Port [443]: ").strip();
        int port       = portStr.isBlank() ? 443 : Integer.parseInt(portStr);
        String alias   = prompt("Base alias: ").strip();
        if (host.isBlank() || alias.isBlank()) return;

        RemoteCertClient client = new RemoteCertClient();
        var result = client.fetchChain(host, port, true);
        if (!result.success()) { System.out.println("Download failed: " + result.errorMessage()); return; }

        if (!confirm("Proceed to import these certificates?")) {
            System.out.println("Cancelled");
            return;
        }
        var ir = new CertChainIngester().ingest(result.pemList(), trustKeystoreManager(), alias, true);
        if (ir.success()) System.out.println("[OK] Successfully quick-imported " + ir.importedAliases().size() + " certificate(s)");
        else System.out.println("[!!] Import failed; " + ir.errorMessage());
    }

    /** 9 -- Convert JKS to PKCS12  (keystore picker) */
    private void cmdConvertToP12() {
        System.out.println("\n--- Convert JKS to PKCS12 ---");
        KeystoreManager mgr = pickKeystoreManager("convert to PKCS12");
        if (mgr == null) return;

        String srcName   = mgr.getKeystorePath().toString();
        String defaultOut = srcName.replaceAll("(?i)\\.jks$", "") + ".p12";
        String rawOut    = prompt("Output .p12 path [" + defaultOut + "]: ").strip();
        String output    = rawOut.isBlank() ? defaultOut : rawOut;

        char[] destPass = promptPassword("PKCS12 destination password: ");
        try {
            boolean ok = mgr.convertToPkcs12(Path.of(output), destPass);
            System.out.println(ok ? "[OK] Converted to " + output : "[!!] Conversion failed -- check logs");
        } finally {
            Arrays.fill(destPass, '\0');
        }
    }

    /** 10 -- Change keystore passwords and regenerate certificates */
    private void cmdRegenerateCerts() {
        System.out.println("\n--- SDI Certificate Regeneration ---");
        System.out.println("Use --automate with a regeneration YAML for non-interactive use.");
        System.out.println("Interactive regeneration requires: install-dir, solution-dir, current + new passwords.");

        String installDirIn  = prompt("Install dir [" + ctx.installDir()  + "]: ").strip();
        String solutionDirIn = prompt("Solution dir [" + ctx.solutionDir() + "]: ").strip();
        String resolvedInstall  = installDirIn.isBlank()  ? ctx.installDir()  : installDirIn;
        String resolvedSolution = solutionDirIn.isBlank() ? ctx.solutionDir() : solutionDirIn;

        char[] oldServer = promptPassword("Current testserver.jks password: ");
        char[] oldAdmin  = promptPassword("Current testadmin.jks password: ");
        char[] newServer = promptPassword("New testserver.jks password: ");
        char[] newAdmin  = promptPassword("New testadmin.jks password: ");

        try {
            if (!confirm("Proceed with certificate regeneration? This is destructive.")) return;
            var spec = com.ibm.di.certmgr.model.RegenerationSpec.of(
                    ctx.directoryMode(),
                    ctx.directoryMode() == DirectoryMode.INSTALL ? resolvedInstall : resolvedSolution,
                    resolvedInstall, resolvedSolution,
                    oldServer, oldAdmin, newServer, newAdmin,
                    Map.of("dname", "CN=API Admin, OU=test, O=test, L=test, ST=test, C=US",
                           "validity", 1095, "keysize", 2048, "keyalg", "RSA"),
                    ctx.keytoolPath());
            var result = new com.ibm.di.certmgr.regen.RegenerationEngine(ctx).execute(
                    spec, (step, n) -> System.out.printf("[%d/10] %s%n", n, step));
            if (result.success()) {
                System.out.println("\nRegeneration complete. Changes:");
                result.changes().forEach(c -> System.out.println("  * " + c));
            } else {
                System.out.println("\nRegeneration FAILED: " + result.errorMessage());
            }
        } finally {
            Arrays.fill(oldServer, '\0'); Arrays.fill(oldAdmin, '\0');
            Arrays.fill(newServer, '\0'); Arrays.fill(newAdmin, '\0');
        }
    }

    /** 11 -- Decrypt properties file */
    private void cmdDecryptProperties() {
        System.out.println("\n--- Decrypt Properties File ---");
        try {
            SdiPropertiesStore store = new SdiPropertiesStore(
                    ctx.directoryMode(), baseDirForMode(), ctx.installDir());
            char[] pass = promptPassword("Server keystore password: ");
            try {
                Path serverJks = store.getKeystorePaths().get("server");
                boolean ok = store.decrypt(serverJks, pass, "server");
                System.out.println(ok ? "Decrypted to: " + store.getPlainPropertiesFile() : "Decrypt failed -- check logs");
            } finally { Arrays.fill(pass, '\0'); }
        } catch (Exception e) { System.out.println("Error: " + e.getMessage()); }
    }

    /** 12 -- Encrypt properties file */
    private void cmdEncryptProperties() {
        System.out.println("\n--- Encrypt Properties File ---");
        try {
            SdiPropertiesStore store = new SdiPropertiesStore(
                    ctx.directoryMode(), baseDirForMode(), ctx.installDir());
            char[] pass = promptPassword("Server keystore password: ");
            try {
                Path serverJks = store.getKeystorePaths().get("server");
                boolean ok = store.encrypt(serverJks, pass, "server");
                System.out.println(ok ? "Encrypted: " + store.getPropertiesFile() : "Encrypt failed -- check logs");
            } finally { Arrays.fill(pass, '\0'); }
        } catch (Exception e) { System.out.println("Error: " + e.getMessage()); }
    }

    /** 13 -- Create stash file */
    private void cmdCreateStash() {
        System.out.println("\n--- Create Stash File ---");
        try {
            SdiPropertiesStore store = new SdiPropertiesStore(
                    ctx.directoryMode(), baseDirForMode(), ctx.installDir());
            char[] pass = promptPassword("Server keystore password: ");
            try {
                boolean ok = store.createStash(pass);
                System.out.println(ok ? "Stash created: " + store.getStashFile() : "Stash creation failed -- check logs");
            } finally { Arrays.fill(pass, '\0'); }
        } catch (Exception e) { System.out.println("Error: " + e.getMessage()); }
    }

    /** 14 -- Update registry.txt */
    private void cmdUpdateRegistry() {
        System.out.println("\n--- Update registry.txt (admin certificate DN / Layer-2 auth) ---");
        String dn   = prompt("Admin certificate DN: ").strip();
        String role = prompt("Role [admin]: ").strip();
        if (dn.isBlank()) return;
        if (role.isBlank()) role = "admin";
        boolean ok = new RegistryFileUpdater().update(Path.of(baseDirForMode()), dn, role);
        System.out.println(ok ? "registry.txt updated" : "Update failed -- check logs");
    }

    /** 15 -- Import CA-issued certificate (PKCS#12 / PFX) */
    private void cmdImportCaKey() {
        System.out.println("\n--- Import CA-Issued Certificate (PKCS#12 / PFX) ---");
        System.out.println("Import a CA-issued key/certificate (PKCS12/PFX) into one of the SDI keystores.");
        System.out.println();
        System.out.println("Which keystore should receive the imported key?");
        System.out.println("  1. Server keystore  (testserver.jks  - used for TLS/LDAPS connections)");
        System.out.println("  2. Admin  keystore  (testadmin.jks   - used for administration/signing)");
        String side = prompt("Choice [1]: ").strip();
        boolean isAdmin = "2".equals(side);

        String p12PathStr = prompt("Path to PKCS12 / PFX file: ").strip();
        if (p12PathStr.isBlank()) return;
        Path p12Path = Path.of(p12PathStr);
        if (!Files.exists(p12Path)) { System.out.println("File not found: " + p12PathStr); return; }

        char[] p12Pass    = promptPassword("PKCS12 password: ");
        char[] targetPass = promptPassword("Target keystore password: ");

        String destAliasDefault = isAdmin ? "admin" : "server";
        String destAlias = prompt("Destination alias [" + destAliasDefault + "]: ").strip();
        if (destAlias.isBlank()) destAlias = destAliasDefault;
        String srcAlias = prompt("Source alias inside PKCS12 (Enter=auto): ").strip();

        try {
            PropertiesFileHandler handler = new PropertiesFileHandler(
                    ctx.directoryMode(), Path.of(baseDirForMode()));
            Map<String, Path> ks = handler.readKeystoreNames();
            Path targetJks = isAdmin ? ks.get("admin") : ks.get("server");
            Path otherJks  = isAdmin ? ks.get("server") : ks.get("admin");

            char[] otherPass = promptPassword("Other keystore password (for cross-import): ");
            try {
                CaKeyImportSpec spec = new CaKeyImportSpec(
                        targetJks, otherJks, isAdmin, p12Path, p12Pass,
                        targetPass, otherPass,
                        destAlias, srcAlias.isBlank() ? null : srcAlias,
                        Path.of(baseDirForMode()), ctx.keytoolPath());

                if (!confirm("Proceed with import?")) return;
                var result = new CaKeyImportWorkflow().execute(spec);
                if (result.success()) {
                    System.out.println("\nImport complete:");
                    result.changes().forEach(c -> System.out.println("  * " + c));
                } else {
                    System.out.println("\nImport FAILED: " + result.errorMessage());
                }
            } finally { Arrays.fill(otherPass, '\0'); }
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        } finally {
            Arrays.fill(p12Pass, '\0');
            Arrays.fill(targetPass, '\0');
        }
    }

    // -----------------------------------------------------------------------
    // Password validation helper
    // -----------------------------------------------------------------------

    /**
     * Prompt for a keystore password and validate it against the actual file,
     * allowing up to 3 attempts.
     *
     * <p>If the file does not yet exist the password is accepted immediately
     * (nothing to validate against). After 3 failed attempts the user is
     * given one final prompt with no further validation and a warning.
     *
     * @param label    human-readable label, e.g. "server keystore"
     * @param keytool  path to keytool executable
     * @param ksPath   path to the keystore file
     * @param ksExists whether the file currently exists on disk
     * @return accepted (and when possible validated) password char array
     */
    private char[] promptValidatedPassword(final String label, final String keytool,
                                            final String ksPath, final boolean ksExists) {
        final int MAX_ATTEMPTS = 3;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            char[] candidate = promptPassword("Enter " + label + " password: ");
            if (!ksExists) {
                System.out.println("[OK] " + label + " not found yet -- password accepted"
                        + " (will be validated on first use).");
                return candidate;
            }
            System.out.println("Validating " + label + " password...");
            KeystoreManager tmp = new KeystoreManager(keytool, ksPath, candidate);
            if (tmp.validatePassword()) {
                System.out.println("[OK] " + label + " password validated successfully.");
                return candidate;
            }
            Arrays.fill(candidate, (char) 0);
            System.out.printf("[!!] Invalid %s password (attempt %d/%d)%n",
                    label, attempt, MAX_ATTEMPTS);
            if (attempt < MAX_ATTEMPTS) {
                System.out.println("Please try again.");
            } else {
                System.out.println("[!!] Maximum attempts reached. Continuing without validation.");
                return promptPassword("Enter " + label + " password (no further validation): ");
            }
        }
        // Unreachable, but required for compilation
        return new char[0];
    }

    // -----------------------------------------------------------------------
    // I/O helpers
    // -----------------------------------------------------------------------

    String prompt(final String message) {
        System.out.print(message);
        System.out.flush();
        return scanner.hasNextLine() ? scanner.nextLine() : "";
    }

    char[] promptPassword(final String message) {
        Console console = System.console();
        if (console != null) {
            return console.readPassword(message);
        }
        System.out.print(message);
        System.out.flush();
        String line = scanner.hasNextLine() ? scanner.nextLine() : "";
        return line.toCharArray();
    }

    boolean confirm(final String question) {
        String answer = prompt(question + " [y/N]: ").strip().toLowerCase();
        return "y".equals(answer) || "yes".equals(answer);
    }

    // -----------------------------------------------------------------------
    // KeystoreManager factories
    // -----------------------------------------------------------------------

    /**
     * Server identity keystore -- used by regeneration (10), decrypt/encrypt
     * (11/12), stash (13), registry (14), import-CA-key (15).
     */
    private KeystoreManager serverKeystoreManager() {
        return new KeystoreManager(ctx.keytoolPath(), ctx.jksPath(), ctx.storepass());
    }

    /**
     * Active trust keystore -- used by add-from-HTTPS (4), add-from-file (5),
     * quick-import (8).  Equals {@link #serverKeystoreManager()} unless
     * {@code api.client.ssl.custom.properties.on=true}.
     */
    private KeystoreManager trustKeystoreManager() {
        return new KeystoreManager(ctx.keytoolPath(),
                ctx.effectiveTrustJksPath(), ctx.effectiveTrustStorepass());
    }

    private String baseDirForMode() {
        return ctx.directoryMode() == DirectoryMode.INSTALL
                ? ctx.installDir() : ctx.solutionDir();
    }
}
