/*
 * Copyright contributors to the SyncWeave project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.certmgr.model;

/**
 * SDI directory operation modes.
 *
 * <ul>
 *   <li>{@link #INSTALL}  - operates on {@code etc/global.properties} inside the install dir</li>
 *   <li>{@link #SOLUTION} - operates on {@code solution.properties} inside the solution dir</li>
 * </ul>
 */
public enum DirectoryMode {

    /** Operates on &lt;install_dir&gt;/etc/global.properties. */
    INSTALL("install"),

    /** Operates on &lt;solution_dir&gt;/solution.properties. */
    SOLUTION("solution");

    private final String value;

    DirectoryMode(final String value) {
        this.value = value;
    }

    /** CLI / config value (e.g. "install" or "solution"). */
    public String getValue() {
        return value;
    }

    /** Human-readable label shown in interactive menus. */
    public String getDisplayName() {
        return this == INSTALL ? "Install Directory" : "Solution Directory";
    }

    /** Name of the properties file owned by this mode. */
    public String getPropertiesFilename() {
        return this == INSTALL ? "global.properties" : "solution.properties";
    }

    /**
     * Sub-directory (relative to base dir) that contains the properties file.
     * Returns an empty string for SOLUTION mode (file is at the base dir root).
     */
    public String getPropertiesSubdir() {
        return this == INSTALL ? "etc" : "";
    }

    @Override
    public String toString() {
        return value;
    }

    /**
     * Look up a {@code DirectoryMode} by its string value (case-insensitive).
     *
     * @param value "install" or "solution"
     * @return matching enum constant
     * @throws IllegalArgumentException if value is not recognised
     */
    public static DirectoryMode fromValue(final String value) {
        for (DirectoryMode m : values()) {
            if (m.value.equalsIgnoreCase(value)) {
                return m;
            }
        }
        throw new IllegalArgumentException("Unknown DirectoryMode value: " + value);
    }
}