/*
 * Copyright contributors to the SyncWeave project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.certmgr.cli;

import com.ibm.di.certmgr.model.AppContext;
import com.ibm.di.certmgr.model.DirectoryMode;
import com.ibm.di.certmgr.model.RegenerationSpec;
import com.ibm.di.certmgr.properties.SdiPropertiesStore;
import com.ibm.di.certmgr.regen.BackupManager;
import com.ibm.di.certmgr.regen.RegenerationEngine;
import com.ibm.di.certmgr.regen.RegistryFileUpdater;
import com.ibm.di.certmgr.remote.CertChainIngester;
import com.ibm.di.certmgr.remote.RemoteCertClient;
import com.ibm.di.certmgr.workflow.CaKeyImportSpec;
import com.ibm.di.certmgr.workflow.CaKeyImportWorkflow;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * YAML-driven automation mode â€” runs a single operation without any
 * interactive prompts.
 *
 * <p>Automation YAML schema:
 * <pre>
 * operation: regenerate         # required
 * dry_run: false                # optional override
 * install_dir: /opt/ibm/sdi     # optional
 * solution_dir: /opt/ibm/sol    # optional
 * mode: solution                # install | solution
 *
 * # regenerate-specific:
 * old_server_pass: !env SDI_OLD_SERVER_PASS
 * old_admin_pass:  !env SDI_OLD_ADMIN_PASS
 * new_server_pass: !env SDI_NEW_SERVER_PASS
 * new_admin_pass:  !env SDI_NEW_ADMIN_PASS
 * dname: "CN=API Admin, OU=test, O=IBM, C=US"
 * validity: 1095
 * keysize:  2048
 * keyalg:   RSA
 *
 * # import-ca-key-specific:
 * side: server                  # server | admin
 * p12_path: /path/to/cert.p12
 * p12_pass: !env SDI_P12_PASS
 * target_pass: !env SDI_TARGET_PASS
 * dest_alias: server
 *
 * # add-cert-from-https-specific:
 * hostname: ldap.example.com
 * port: 636
 * alias: ldap-root
 *
 * # update-registry-specific:
 * admin_dn: "CN=Admin, O=IBM, C=US"
 * role: admin
 * </pre>
 *
 * <p>Password values may reference environment variables using the
 * {@code !env VAR_NAME} custom YAML tag, or be supplied inline
 * (not recommended for production).
 *
 * <p>Supported operations: {@code regenerate}, {@code decrypt-properties},
 * {@code encrypt-properties}, {@code create-stash}, {@code update-registry},
 * {@code add-cert-from-https}, {@code import-ca-key}.
 *
 * <p>When {@code dryRun=true} every step is logged but no files are written
 * and no sub-processes are launched. The tool exits 0 with a summary of
 * what would have changed.
 */
public class YamlAutomation {

    private static final Logger log = LogManager.getLogger(YamlAutomation.class);

    private final AppContext ctx;
    private final boolean dryRun;

    public YamlAutomation(final AppContext ctx, final boolean dryRun) {
        this.ctx    = ctx;
        this.dryRun = dryRun;
    }

    /**
     * Load the automation YAML file and execute the requested operation.
     *
     * @param yamlFile path to the automation YAML
     * @return exit code: 0 = success, 1 = operation failure, 2 = config/validation error
     */
    public int execute(final Path yamlFile) {
        Map<String, Object> doc;
        try (InputStream in = Files.newInputStream(yamlFile)) {
            doc = new Yaml().load(in);
        } catch (Exception e) {
            log.error("Failed to load automation YAML {}: {}", yamlFile, e.getMessage());
            System.err.println("Error loading YAML: " + e.getMessage());
            return 2;
        }

        if (doc == null || doc.isEmpty()) {
            System.err.println("Automation YAML is empty: " + yamlFile);
            return 2;
        }

        String operation = getString(doc, "operation", null);
        if (operation == null || operation.isBlank()) {
            System.err.println("'operation' field is required in automation YAML");
            return 2;
        }

        // Per-file dry-run can override the CLI flag
        boolean effectiveDryRun = dryRun || getBoolean(doc, "dry_run", false);

        if (effectiveDryRun) {
            System.out.println("[dry-run] Would execute operation: " + operation);
        } else {
            log.info("Executing automation operation: {}", operation);
        }

        return switch (operation.toLowerCase()) {
            case "regenerate"          -> opRegenerate(doc, effectiveDryRun);
            case "decrypt-properties"  -> opDecryptProperties(doc, effectiveDryRun);
            case "encrypt-properties"  -> opEncryptProperties(doc, effectiveDryRun);
            case "create-stash"        -> opCreateStash(doc, effectiveDryRun);
            case "update-registry"     -> opUpdateRegistry(doc, effectiveDryRun);
            case "add-cert-from-https" -> opAddCertFromHttps(doc, effectiveDryRun);
            case "import-ca-key"       -> opImportCaKey(doc, effectiveDryRun);
            default -> {
                System.err.println("Unknown operation: " + operation);
                yield 2;
            }
        };
    }

    // -----------------------------------------------------------------------
    // Operations
    // -----------------------------------------------------------------------

    private int opRegenerate(final Map<String, Object> doc, final boolean dry) {
        char[] oldServer = resolvePassword(doc, "old_server_pass");
        char[] oldAdmin  = resolvePassword(doc, "old_admin_pass");
        char[] newServer = resolvePassword(doc, "new_server_pass");
        char[] newAdmin  = resolvePassword(doc, "new_admin_pass");

        if (oldServer == null || oldAdmin == null || newServer == null || newAdmin == null) {
            System.err.println("regenerate: old_server_pass, old_admin_pass, new_server_pass, new_admin_pass are required");
            return 2;
        }

        try {
            String baseDir = resolveBaseDir(doc);
            Map<String, Object> certParams = new HashMap<>();
            certParams.put("dname",    getString(doc, "dname",   "CN=API Admin, OU=test, O=test, L=test, ST=test, C=US"));
            certParams.put("validity", getInt(doc, "validity", 1095));
            certParams.put("keysize",  getInt(doc, "keysize",  2048));
            certParams.put("keyalg",   getString(doc, "keyalg", "RSA"));

            if (dry) {
                printDryRunSummary("regenerate", List.of(
                    "baseDir=" + baseDir,
                    "dname=" + certParams.get("dname"),
                    "validity=" + certParams.get("validity")));
                return 0;
            }

            var spec = RegenerationSpec.of(
                    resolveMode(doc), baseDir, resolveInstallDir(doc), resolveSolutionDir(doc),
                    oldServer, oldAdmin, newServer, newAdmin, certParams, ctx.keytoolPath());
            var result = new RegenerationEngine(ctx).execute(spec,
                    (step, n) -> System.out.printf("[%d/10] %s%n", n, step));
            if (result.success()) {
                result.changes().forEach(c -> System.out.println("  + " + c));
                return 0;
            } else {
                System.err.println("Regeneration failed: " + result.errorMessage());
                return 1;
            }
        } finally {
            if (oldServer != null) Arrays.fill(oldServer, '\0');
            if (oldAdmin  != null) Arrays.fill(oldAdmin,  '\0');
            if (newServer != null) Arrays.fill(newServer, '\0');
            if (newAdmin  != null) Arrays.fill(newAdmin,  '\0');
        }
    }

    private int opDecryptProperties(final Map<String, Object> doc, final boolean dry) {
        char[] pass = resolvePassword(doc, "server_pass");
        if (pass == null) { System.err.println("decrypt-properties: server_pass is required"); return 2; }
        try {
            if (dry) { printDryRunSummary("decrypt-properties", List.of("mode=" + resolveMode(doc))); return 0; }
            SdiPropertiesStore store = buildStore(doc);
            Path serverJks = store.getKeystorePaths().get("server");
            boolean ok = store.decrypt(serverJks, pass, "server");
            if (ok) System.out.println("Decrypted: " + store.getPlainPropertiesFile());
            return ok ? 0 : 1;
        } catch (Exception e) { System.err.println("Error: " + e.getMessage()); return 1; }
        finally { Arrays.fill(pass, '\0'); }
    }

    private int opEncryptProperties(final Map<String, Object> doc, final boolean dry) {
        char[] pass = resolvePassword(doc, "server_pass");
        if (pass == null) { System.err.println("encrypt-properties: server_pass is required"); return 2; }
        try {
            if (dry) { printDryRunSummary("encrypt-properties", List.of("mode=" + resolveMode(doc))); return 0; }
            SdiPropertiesStore store = buildStore(doc);
            Path serverJks = store.getKeystorePaths().get("server");
            boolean ok = store.encrypt(serverJks, pass, "server");
            if (ok) System.out.println("Encrypted: " + store.getPropertiesFile());
            return ok ? 0 : 1;
        } catch (Exception e) { System.err.println("Error: " + e.getMessage()); return 1; }
        finally { Arrays.fill(pass, '\0'); }
    }

    private int opCreateStash(final Map<String, Object> doc, final boolean dry) {
        char[] pass = resolvePassword(doc, "server_pass");
        if (pass == null) { System.err.println("create-stash: server_pass is required"); return 2; }
        try {
            if (dry) { printDryRunSummary("create-stash", List.of("mode=" + resolveMode(doc))); return 0; }
            SdiPropertiesStore store = buildStore(doc);
            boolean ok = store.createStash(pass);
            if (ok) System.out.println("Stash created: " + store.getStashFile());
            return ok ? 0 : 1;
        } catch (Exception e) { System.err.println("Error: " + e.getMessage()); return 1; }
        finally { Arrays.fill(pass, '\0'); }
    }

    private int opUpdateRegistry(final Map<String, Object> doc, final boolean dry) {
        String dn   = getString(doc, "admin_dn", null);
        String role = getString(doc, "role", "admin");
        if (dn == null || dn.isBlank()) { System.err.println("update-registry: admin_dn is required"); return 2; }
        if (dry) { printDryRunSummary("update-registry", List.of("dn=" + dn, "role=" + role)); return 0; }
        boolean ok = new RegistryFileUpdater().update(Path.of(resolveBaseDir(doc)), dn, role);
        if (ok) System.out.println("registry.txt updated with DN: " + dn);
        return ok ? 0 : 1;
    }

    private int opAddCertFromHttps(final Map<String, Object> doc, final boolean dry) {
        String host  = getString(doc, "hostname", null);
        int    port  = getInt(doc, "port", 443);
        String alias = getString(doc, "alias", null);
        if (host == null || alias == null) {
            System.err.println("add-cert-from-https: hostname and alias are required"); return 2;
        }
        if (dry) { printDryRunSummary("add-cert-from-https", List.of("host=" + host + ":" + port, "alias=" + alias)); return 0; }

        var result = new RemoteCertClient().fetchChain(host, port, true);
        if (!result.success()) { System.err.println("Download failed: " + result.errorMessage()); return 1; }

        var jksMgr = new com.ibm.di.certmgr.keystore.KeystoreManager(
                ctx.keytoolPath(), ctx.jksPath(), ctx.storepass());
        var ir = new CertChainIngester().ingest(result.pemList(), jksMgr, alias, false);
        if (ir.success()) {
            System.out.println("Imported: " + ir.importedAliases());
            return 0;
        }
        System.err.println("Import failed: " + ir.errorMessage());
        return 1;
    }

    private int opImportCaKey(final Map<String, Object> doc, final boolean dry) {
        String sideStr    = getString(doc, "side", "server");
        String p12PathStr = getString(doc, "p12_path", null);
        char[] p12Pass    = resolvePassword(doc, "p12_pass");
        char[] targetPass = resolvePassword(doc, "target_pass");
        String destAlias  = getString(doc, "dest_alias", "server".equals(sideStr) ? "server" : "admin");
        String srcAlias   = getString(doc, "src_alias", null);

        if (dry) {
            if (p12PathStr == null || p12Pass == null || targetPass == null) {
                System.err.println("import-ca-key: p12_path, p12_pass, target_pass are required"); return 2;
            }
            printDryRunSummary("import-ca-key", List.of("side=" + sideStr, "p12=" + p12PathStr, "alias=" + destAlias));
            return 0;
        }
        if (p12PathStr == null || p12Pass == null || targetPass == null) {
            System.err.println("import-ca-key: p12_path, p12_pass, target_pass are required"); return 2;
        }
        try {
            Path p12 = Path.of(p12PathStr);
            if (!Files.exists(p12)) { System.err.println("P12 not found: " + p12PathStr); return 2; }
            boolean isAdmin = "admin".equalsIgnoreCase(sideStr);
            var store = buildStore(doc);
            Map<String, Path> ks = store.getKeystorePaths();
            Path targetJks = isAdmin ? ks.get("admin") : ks.get("server");
            Path otherJks  = isAdmin ? ks.get("server") : ks.get("admin");

            char[] otherPass = targetPass;  // use same if not specified separately
            var spec = new CaKeyImportSpec(targetJks, otherJks, isAdmin, p12, p12Pass,
                    targetPass, otherPass, destAlias, srcAlias,
                    Path.of(resolveBaseDir(doc)), ctx.keytoolPath());
            var result = new CaKeyImportWorkflow().execute(spec);
            if (result.success()) {
                result.changes().forEach(c -> System.out.println("  + " + c));
                return 0;
            }
            System.err.println("Import failed: " + result.errorMessage());
            return 1;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        } finally {
            if (p12Pass    != null) Arrays.fill(p12Pass,    '\0');
            if (targetPass != null) Arrays.fill(targetPass, '\0');
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void printDryRunSummary(final String op, final List<String> details) {
        System.out.println("[dry-run] Operation: " + op);
        details.forEach(d -> System.out.println("[dry-run]   " + d));
        System.out.println("[dry-run] No changes made.");
    }

    /**
     * Resolve a password value from the YAML doc.
     * Supports plain strings or {@code {env: VAR_NAME}} maps.
     */
    char[] resolvePassword(final Map<String, Object> doc, final String key) {
        Object val = doc.get(key);
        if (val == null) return null;
        if (val instanceof String s) return s.toCharArray();
        if (val instanceof Map<?, ?> m) {
            Object envKey = m.get("env");
            if (envKey instanceof String envName) {
                String envVal = System.getenv(envName);
                return envVal != null ? envVal.toCharArray() : null;
            }
        }
        return val.toString().toCharArray();
    }

    private SdiPropertiesStore buildStore(final Map<String, Object> doc) {
        return new SdiPropertiesStore(resolveMode(doc), resolveBaseDir(doc), resolveInstallDir(doc));
    }

    private DirectoryMode resolveMode(final Map<String, Object> doc) {
        String m = getString(doc, "mode", null);
        if (m == null) m = ctx.directoryMode() != null ? ctx.directoryMode().getValue() : "solution";
        return DirectoryMode.fromValue(m);
    }

    private String resolveBaseDir(final Map<String, Object> doc) {
        DirectoryMode m = resolveMode(doc);
        return m == DirectoryMode.INSTALL ? resolveInstallDir(doc) : resolveSolutionDir(doc);
    }

    private String resolveInstallDir(final Map<String, Object> doc) {
        String v = getString(doc, "install_dir", null);
        return v != null ? v : (ctx.installDir() != null ? ctx.installDir() : "");
    }

    private String resolveSolutionDir(final Map<String, Object> doc) {
        String v = getString(doc, "solution_dir", null);
        return v != null ? v : (ctx.solutionDir() != null ? ctx.solutionDir() : "");
    }

    private static String getString(final Map<String, Object> doc, final String key, final String def) {
        Object v = doc.get(key);
        return v instanceof String s ? s : def;
    }

    private static int getInt(final Map<String, Object> doc, final String key, final int def) {
        Object v = doc.get(key);
        if (v instanceof Integer i) return i;
        if (v instanceof Number n) return n.intValue();
        return def;
    }

    private static boolean getBoolean(final Map<String, Object> doc, final String key, final boolean def) {
        Object v = doc.get(key);
        if (v instanceof Boolean b) return b;
        return def;
    }
}
