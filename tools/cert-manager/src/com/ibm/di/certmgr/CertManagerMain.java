/*
 * Copyright contributors to the SyncWeave project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.certmgr;

import com.ibm.di.certmgr.cli.InteractiveCli;
import com.ibm.di.certmgr.cli.YamlAutomation;
import com.ibm.di.certmgr.config.Configuration;
import com.ibm.di.certmgr.model.AppContext;
import com.ibm.di.certmgr.model.DirectoryMode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Picocli entry-point for the SDI Certificate Manager.
 *
 * <p>Two operating modes:
 * <ul>
 *   <li><b>Interactive</b> (default) -- launches the 15-item menu loop</li>
 *   <li><b>YAML automation</b> ({@code --automate <file>}) -- reads an
 *       automation YAML file and executes the requested operation without
 *       any prompts; {@code --dry-run} validates but writes nothing</li>
 * </ul>
 *
 * <p>Common options ({@code --install-dir}, {@code --solution-dir},
 * {@code --jks}, {@code --mode}, {@code --config}, {@code --log-level})
 * are passed to {@link Configuration} and assembled into an
 * {@link AppContext} before either mode runs.
 */
@Command(
    name        = "certmgr",
    description = "SDI Certificate Manager -- JKS / PKCS12 lifecycle tool for IBM VDI / SyncWeave",
    mixinStandardHelpOptions = true,
    version     = "SDI Certificate Manager 1.0.0"
)
public class CertManagerMain implements Callable<Integer> {

    private static final Logger log = LogManager.getLogger(CertManagerMain.class);

    // -----------------------------------------------------------------------
    // Options
    // -----------------------------------------------------------------------

    @Option(names = {"--config", "-c"},
            description = "Path to configuration YAML file (default: cert-manager.yaml)",
            defaultValue = "cert-manager.yaml")
    private File configFile;

    @Option(names = {"--log-level"},
            description = "Override log level: TRACE DEBUG INFO WARN ERROR",
            defaultValue = "INFO")
    private String logLevel;

    @Option(names = {"--install-dir", "-i"},
            description = "SDI installation directory")
    private String installDir;

    @Option(names = {"--solution-dir", "-s"},
            description = "SDI solution directory")
    private String solutionDir;

    @Option(names = {"--jks"},
            description = "Path to the JKS / PKCS12 keystore file")
    private String jksPath;

    @Option(names = {"--mode"},
            description = "Directory mode: install or solution",
            defaultValue = "solution")
    private String mode;

    @Option(names = {"--automate", "-a"},
            description = "Run in YAML automation mode using the given file")
    private File automateFile;

    @Option(names = {"--dry-run"},
            description = "Validate inputs but make no changes (automation mode only)")
    private boolean dryRun;

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------

    public static void main(final String[] args) {
        int exit = new CommandLine(new CertManagerMain()).execute(args);
        System.exit(exit);
    }

    @Override
    public Integer call() {
        // Load configuration
        Configuration cfg = loadConfiguration();

        // Apply CLI overrides into config
        if (installDir != null) cfg.set("directories.install_dir", installDir);
        if (solutionDir != null) cfg.set("directories.solution_dir", solutionDir);
        if (jksPath != null)     cfg.set("keystore.path", jksPath);
        cfg.set("application.log_level", logLevel);
        cfg.set("directories.mode", mode);

        log.info("SDI Certificate Manager starting -- mode={}, dryRun={}", automateFile != null ? "automation" : "interactive", dryRun);

        if (automateFile != null) {
            return runAutomation(cfg);
        } else {
            return runInteractive(cfg);
        }
    }

    // -----------------------------------------------------------------------
    // Mode dispatch
    // -----------------------------------------------------------------------

    private int runAutomation(final Configuration cfg) {
        if (!automateFile.exists()) {
            System.err.println("Automation file not found: " + automateFile);
            return 2;
        }
        try {
            AppContext ctx = buildContext(cfg);
            YamlAutomation automation = new YamlAutomation(ctx, dryRun);
            return automation.execute(automateFile.toPath());
        } catch (Exception e) {
            log.error("Automation failed: {}", e.getMessage(), e);
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private int runInteractive(final Configuration cfg) {
        try {
            AppContext ctx = buildContext(cfg);
            InteractiveCli cli = new InteractiveCli(ctx);
            cli.run();
            return 0;
        } catch (Exception e) {
            log.error("Interactive mode failed: {}", e.getMessage(), e);
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    // -----------------------------------------------------------------------
    // Context construction
    // -----------------------------------------------------------------------

    private Configuration loadConfiguration() {
        if (configFile != null && configFile.exists()) {
            return new Configuration(configFile.getAbsolutePath());
        }
        return new Configuration();
    }

    /**
     * Build an {@link AppContext} from resolved configuration.
     * Keytool is located from {@code java.home}; JKS path falls back
     * to the server keystore from configuration.
     */
    AppContext buildContext(final Configuration cfg) {
        String resolvedInstallDir  = cfg.getString("directories.install_dir",  "");
        String resolvedSolutionDir = cfg.getString("directories.solution_dir", "");
        String resolvedJks         = cfg.getString("keystore.path",            "");
        String resolvedMode        = cfg.getString("directories.mode",         "solution");

        DirectoryMode dirMode = DirectoryMode.fromValue(resolvedMode);

        // Resolve keytool from java.home
        String kt = resolveKeytool(resolvedInstallDir);

        return new AppContext(kt, resolvedJks, new char[0],
                resolvedInstallDir, resolvedSolutionDir, dirMode, null);
    }

    private static String resolveKeytool(final String installDir) {
        // 1. Try SDI bundled JVM
        if (installDir != null && !installDir.isBlank()) {
            for (String rel : new String[]{"jvm/jre/bin/keytool", "jvm/bin/keytool",
                                           "jvm/jre/bin/keytool.exe", "jvm/bin/keytool.exe"}) {
                Path candidate = Path.of(installDir, rel);
                if (Files.exists(candidate)) return candidate.toString();
            }
        }
        // 2. Running JVM's java.home
        String javaHome = System.getProperty("java.home");
        for (String rel : new String[]{"bin/keytool", "bin/keytool.exe"}) {
            Path candidate = Path.of(javaHome, rel);
            if (Files.exists(candidate)) return candidate.toString();
        }
        // 3. Fall back to PATH
        return "keytool";
    }
}