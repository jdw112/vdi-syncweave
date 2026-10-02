/*
 * Copyright contributors to the SyncWeave project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.certmgr.exception;

/**
 * Thrown when configuration is missing, invalid, or cannot be loaded.
 */
public class ConfigurationException extends CertManagerException {

    private static final long serialVersionUID = 1L;

    public ConfigurationException(final String message) {
        super(message);
    }

    public ConfigurationException(final String message, final Throwable cause) {
        super(message, cause);
    }
}