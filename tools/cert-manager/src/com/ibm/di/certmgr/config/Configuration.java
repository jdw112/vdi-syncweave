/*
 * Copyright contributors to the SyncWeave project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.certmgr.config;

import com.ibm.di.certmgr.exception.ConfigurationException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages application configuration from multiple sources.
 *
 * <p>Priority order (highest to lowest):
 * <ol>
 *   <li>Values set programmatically via {@link #set(String, Object)}</li>
 *   <li>Environment variables prefixed with {@code SDI_CERT_MGR_}</li>
 *   <li>YAML configuration file</li>
 *   <li>Built-in defaults</li>
 * </ol>
 *
 * <p>Values are accessed with dot-notation key paths, e.g.
 * {@code logging.max_bytes} or {@code certificates.default_dname.CN}.
 */
public class Configuration {

    private static final Logger log = LogManager.getLogger(Configuration.class);

    /** Environment variable prefix for overrides. */
    public static final String ENV_PREFIX = "SDI_CERT_MGR_";

    /** Default config file name resolved relative to the working directory. */
    public static final String DEFAULT_CONFIG_FILE = "cert-manager.yaml";

    /**
     * Mapping from environment variable suffix to config dot-path.
     * E.g. SDI_CERT_MGR_LOG_LEVEL -> application.log_level
     */
    private static final Map<String, String> ENV_MAPPING = Map.of(
            "LOG_LEVEL",                   "application.log_level",
            "DEFAULT_PORT",                "network.default_port",
            "MIN_PASSWORD_LENGTH",         "security.min_password_length",
            "REQUIRE_PASSWORD_COMPLEXITY", "security.require_password_complexity",
            "DEFAULT_VALIDITY",            "certificates.default_validity",
            "DEFAULT_KEYSIZE",             "certificates.default_keysize"
    );

    private final String configFile;
    private Map<String, Object> config;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Load configuration from the default file location.
     * Falls back to built-in defaults if the file does not exist.
     */
    public Configuration() {
        this(DEFAULT_CONFIG_FILE);
    }

    /**
     * Load configuration from the specified file path.
     *
     * @param configFile path to a YAML configuration file (may not exist)
     */
    public Configuration(final String configFile) {
        this.configFile = configFile;
        this.config = buildDefaults();
        loadConfig();
    }

    // -------------------------------------------------------------------------
    // Built-in defaults (mirrors Python _get_builtin_defaults)
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> buildDefaults() {
        Map<String, Object> d = new LinkedHashMap<>();

        Map<String, Object> app = new LinkedHashMap<>();
        app.put("name", "SDI Certificate Manager");
        app.put("version", "1.0.0");
        app.put("log_level", "INFO");
        d.put("application", app);

        Map<String, Object> logging = new LinkedHashMap<>();
        logging.put("directory", "./logs");
        logging.put("max_bytes", 10_485_760);
        logging.put("backup_count", 5);
        logging.put("audit_enabled", true);
        d.put("logging", logging);

        Map<String, Object> certs = new LinkedHashMap<>();
        certs.put("default_validity", 1095);
        certs.put("default_keysize", 2048);
        certs.put("default_keyalg", "RSA");
        certs.put("default_storetype", "JKS");
        Map<String, Object> dname = new LinkedHashMap<>();
        dname.put("CN", "API Admin");
        dname.put("OU", "test");
        dname.put("O",  "test");
        dname.put("L",  "test");
        dname.put("ST", "test");
        dname.put("C",  "US");
        certs.put("default_dname", dname);
        d.put("certificates", certs);

        Map<String, Object> network = new LinkedHashMap<>();
        network.put("default_port", 443);
        network.put("connection_timeout", 10);
        network.put("allow_self_signed", false);
        d.put("network", network);

        Map<String, Object> security = new LinkedHashMap<>();
        security.put("min_password_length", 8);
        security.put("require_password_complexity", true);
        security.put("password_history", 3);
        security.put("session_timeout", 3600);
        d.put("security", security);

        Map<String, Object> backup = new LinkedHashMap<>();
        backup.put("enabled", true);
        backup.put("directory", "./backups");
        backup.put("retention_days", 30);
        backup.put("auto_backup_before_changes", true);
        d.put("backup", backup);

        Map<String, Object> features = new LinkedHashMap<>();
        features.put("enable_registry_update", true);
        features.put("enable_san_support", true);
        features.put("enable_pkcs12_conversion", true);
        features.put("enable_quick_import", true);
        d.put("features", features);

        Map<String, Object> ui = new LinkedHashMap<>();
        ui.put("show_progress_bars", true);
        ui.put("confirm_destructive_actions", true);
        ui.put("tab_completion", true);
        ui.put("color_output", true);
        d.put("ui", ui);

        Map<String, Object> platformPaths = new LinkedHashMap<>();
        Map<String, Object> win = new LinkedHashMap<>();
        win.put("default_install_dir", "C:/Program Files/IBM/SVDI");
        win.put("default_solution_dir", null);
        Map<String, Object> linux = new LinkedHashMap<>();
        linux.put("default_install_dir", "/opt/IBM/TDI/V7.2");
        linux.put("default_solution_dir", null);
        Map<String, Object> aix = new LinkedHashMap<>();
        aix.put("default_install_dir", "/opt/IBM/TDI/V7.2");
        aix.put("default_solution_dir", null);
        platformPaths.put("windows", win);
        platformPaths.put("linux",   linux);
        platformPaths.put("aix",     aix);
        d.put("platform_paths", platformPaths);

        return d;
    }

    // -------------------------------------------------------------------------
    // Loading
    // -------------------------------------------------------------------------

    private void loadConfig() {
        // 1. Try user-supplied / classpath config file
        Path filePath = Paths.get(configFile);
        if (Files.exists(filePath)) {
            try (InputStream is = Files.newInputStream(filePath)) {
                Yaml yaml = new Yaml();
                Map<String, Object> fileConfig = yaml.load(is);
                if (fileConfig != null) {
                    config = mergeConfigs(config, fileConfig);
                }
                log.info("Loaded configuration from {}", filePath.toAbsolutePath());
            } catch (Exception e) {
                // Catches both IOException and SnakeYAML unchecked exceptions
                // (e.g. ScannerException on malformed YAML) - fall back to defaults
                log.warn("Failed to load config file {}: {} - using defaults", filePath, e.getMessage());
            }
        } else {
            // Try classpath resource (bundled default inside the JAR)
            try (InputStream is = Configuration.class.getClassLoader()
                    .getResourceAsStream("cert-manager.yaml")) {
                if (is != null) {
                    Yaml yaml = new Yaml();
                    Map<String, Object> bundled = yaml.load(is);
                    if (bundled != null) {
                        config = mergeConfigs(config, bundled);
                    }
                    log.debug("Loaded bundled default configuration from classpath");
                } else {
                    log.info("Config file not found: {} - using built-in defaults", configFile);
                }
            } catch (IOException e) {
                log.warn("Could not read classpath config: {}", e.getMessage());
            }
        }

        // 2. Apply environment variable overrides
        applyEnvOverrides();
    }

    /**
     * Recursively merge {@code override} into {@code base}, returning a new map.
     * Nested maps are merged; all other values are replaced.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mergeConfigs(
            final Map<String, Object> base,
            final Map<String, Object> override) {
        Map<String, Object> result = new LinkedHashMap<>(base);
        for (Map.Entry<String, Object> entry : override.entrySet()) {
            String key = entry.getKey();
            Object overrideVal = entry.getValue();
            Object baseVal = result.get(key);
            if (baseVal instanceof Map && overrideVal instanceof Map) {
                result.put(key, mergeConfigs(
                        (Map<String, Object>) baseVal,
                        (Map<String, Object>) overrideVal));
            } else {
                result.put(key, overrideVal);
            }
        }
        return result;
    }

    private void applyEnvOverrides() {
        for (Map.Entry<String, String> env : System.getenv().entrySet()) {
            String key = env.getKey();
            if (key.startsWith(ENV_PREFIX)) {
                String suffix = key.substring(ENV_PREFIX.length());
                String configPath = ENV_MAPPING.get(suffix);
                if (configPath != null) {
                    set(configPath, env.getValue());
                    log.debug("Applied env override: {} -> {}", key, configPath);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Get a configuration value using dot-notation.
     *
     * @param keyPath dot-separated path, e.g. "logging.max_bytes"
     * @return value, or null if not found
     */
    public Object get(final String keyPath) {
        return get(keyPath, null);
    }

    /**
     * Get a configuration value with a fallback default.
     *
     * @param keyPath dot-separated path
     * @param defaultValue returned when the path does not exist
     * @return resolved value or defaultValue
     */
    @SuppressWarnings("unchecked")
    public Object get(final String keyPath, final Object defaultValue) {
        String[] keys = keyPath.split("\\.");
        Object current = config;
        for (String key : keys) {
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(key);
                if (current == null) {
                    return defaultValue;
                }
            } else {
                return defaultValue;
            }
        }
        return current;
    }

    /** Typed convenience getter — returns a String or the supplied default. */
    public String getString(final String keyPath, final String defaultValue) {
        Object v = get(keyPath);
        return v != null ? String.valueOf(v) : defaultValue;
    }

    /** Typed convenience getter — returns an int or the supplied default. */
    public int getInt(final String keyPath, final int defaultValue) {
        Object v = get(keyPath);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) { }
        }
        return defaultValue;
    }

    /** Typed convenience getter — returns a boolean or the supplied default. */
    public boolean getBoolean(final String keyPath, final boolean defaultValue) {
        Object v = get(keyPath);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        return defaultValue;
    }

    /**
     * Set a configuration value using dot-notation.
     * Creates intermediate maps as needed.
     *
     * @param keyPath dot-separated path
     * @param value   value to set
     */
    @SuppressWarnings("unchecked")
    public void set(final String keyPath, final Object value) {
        String[] keys = keyPath.split("\\.");
        Map<String, Object> current = config;
        for (int i = 0; i < keys.length - 1; i++) {
            Object next = current.get(keys[i]);
            if (!(next instanceof Map)) {
                next = new LinkedHashMap<String, Object>();
                current.put(keys[i], next);
            }
            current = (Map<String, Object>) next;
        }
        current.put(keys[keys.length - 1], value);
    }

    /**
     * Returns the platform-specific default path for the given path type.
     *
     * @param pathType "install_dir" or "solution_dir"
     * @return configured path or null
     */
    public String getPlatformPath(final String pathType) {
        String os = System.getProperty("os.name", "").toLowerCase();
        String platformKey;
        if (os.contains("win")) {
            platformKey = "windows";
        } else if (os.contains("aix")) {
            platformKey = "aix";
        } else {
            platformKey = "linux";
        }
        Object v = get("platform_paths." + platformKey + ".default_" + pathType);
        return v != null ? String.valueOf(v) : null;
    }

    /**
     * Validate the current configuration.
     *
     * @throws ConfigurationException if any value is out of range or invalid
     */
    public void validate() throws ConfigurationException {
        List<String> validLogLevels = Arrays.asList("DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL");
        String logLevel = getString("application.log_level", "INFO");
        if (!validLogLevels.contains(logLevel.toUpperCase())) {
            throw new ConfigurationException("Invalid log level: " + logLevel);
        }
        if (getInt("certificates.default_validity", 0) <= 0) {
            throw new ConfigurationException("Certificate validity must be positive");
        }
        if (getInt("certificates.default_keysize", 0) < 1024) {
            throw new ConfigurationException("Key size must be at least 1024");
        }
        int port = getInt("network.default_port", 0);
        if (port < 1 || port > 65535) {
            throw new ConfigurationException("Port must be between 1 and 65535");
        }
    }

    /** Returns the config file path this instance was initialised with. */
    public String getConfigFile() {
        return configFile;
    }

    @Override
    public String toString() {
        return "Configuration(file=" + configFile + ", keys=" + config.keySet() + ")";
    }
}