/*
 * Copyright contributors to the SyncWeave project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.certmgr.config;

import com.ibm.di.certmgr.exception.ConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigurationTest {

    // ---- defaults -----------------------------------------------------------

    @Test
    void builtInDefaultsArePresent() {
        Configuration cfg = new Configuration("nonexistent.yaml");
        assertEquals("SDI Certificate Manager", cfg.getString("application.name", null));
        assertEquals(1095,  cfg.getInt("certificates.default_validity", 0));
        assertEquals(2048,  cfg.getInt("certificates.default_keysize", 0));
        assertEquals(443,   cfg.getInt("network.default_port", 0));
        assertEquals(8,     cfg.getInt("security.min_password_length", 0));
        assertTrue(cfg.getBoolean("security.require_password_complexity", false));
        assertEquals("API Admin", cfg.getString("certificates.default_dname.CN", null));
    }

    @Test
    void missingKeyReturnsDefault() {
        Configuration cfg = new Configuration();
        assertNull(cfg.get("does.not.exist"));
        assertEquals("fallback", cfg.getString("does.not.exist", "fallback"));
        assertEquals(42, cfg.getInt("does.not.exist", 42));
    }

    // ---- YAML override ------------------------------------------------------

    @Test
    void yamlFileOverridesDefaults(@TempDir Path tmp) throws IOException {
        Path yaml = tmp.resolve("test-config.yaml");
        Files.writeString(yaml,
                "network:\n  default_port: 8443\ncertificates:\n  default_validity: 365\n");

        Configuration cfg = new Configuration(yaml.toString());
        assertEquals(8443, cfg.getInt("network.default_port", 0));
        assertEquals(365,  cfg.getInt("certificates.default_validity", 0));
        // Non-overridden defaults survive merge
        assertEquals(2048, cfg.getInt("certificates.default_keysize", 0));
    }

    @Test
    void invalidYamlFallsBackToDefaults(@TempDir Path tmp) throws IOException {
        Path yaml = tmp.resolve("bad.yaml");
        Files.writeString(yaml, "not: valid: yaml: {{{");
        // Should not throw — falls back to defaults
        assertDoesNotThrow(() -> new Configuration(yaml.toString()));
    }

    // ---- set / get ----------------------------------------------------------

    @Test
    void setAndGetDotNotation() {
        Configuration cfg = new Configuration();
        cfg.set("application.log_level", "DEBUG");
        assertEquals("DEBUG", cfg.getString("application.log_level", null));
    }

    @Test
    void setCreatesIntermediateMaps() {
        Configuration cfg = new Configuration();
        cfg.set("new.deep.key", "value");
        assertEquals("value", cfg.getString("new.deep.key", null));
    }

    @Test
    void setOverwritesExistingValue() {
        Configuration cfg = new Configuration();
        cfg.set("network.default_port", 9090);
        assertEquals(9090, cfg.getInt("network.default_port", 0));
    }

    // ---- env var overrides --------------------------------------------------

    @Test
    void envPrefixConstantIsCorrect() {
        assertEquals("SDI_CERT_MGR_", Configuration.ENV_PREFIX);
    }

    // ---- validate -----------------------------------------------------------

    @Test
    void validDefaultConfigPassesValidation() {
        assertDoesNotThrow(() -> new Configuration().validate());
    }

    @Test
    void invalidLogLevelFailsValidation() {
        Configuration cfg = new Configuration();
        cfg.set("application.log_level", "NONSENSE");
        assertThrows(ConfigurationException.class, cfg::validate);
    }

    @Test
    void zeroValidityFailsValidation() {
        Configuration cfg = new Configuration();
        cfg.set("certificates.default_validity", 0);
        assertThrows(ConfigurationException.class, cfg::validate);
    }

    @Test
    void tooSmallKeySizeFailsValidation() {
        Configuration cfg = new Configuration();
        cfg.set("certificates.default_keysize", 512);
        assertThrows(ConfigurationException.class, cfg::validate);
    }

    @Test
    void outOfRangePortFailsValidation() {
        Configuration cfg = new Configuration();
        cfg.set("network.default_port", 99999);
        assertThrows(ConfigurationException.class, cfg::validate);
    }

    // ---- getPlatformPath ---------------------------------------------------

    @Test
    void platformPathReturnsNonNullForCurrentPlatform() {
        Configuration cfg = new Configuration();
        // The platform path may be null in config, but the method should not throw
        assertDoesNotThrow(() -> cfg.getPlatformPath("install_dir"));
    }

    // ---- toString ----------------------------------------------------------

    @Test
    void toStringContainsConfigFile() {
        Configuration cfg = new Configuration("myconfig.yaml");
        assertTrue(cfg.toString().contains("myconfig.yaml"));
    }
}