/*
 * Copyright contributors to the SyncWeave project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.certmgr.exception;

/**
 * Base checked exception for all SDI Certificate Manager errors.
 */
public class CertManagerException extends Exception {

    private static final long serialVersionUID = 1L;

    public CertManagerException(final String message) {
        super(message);
    }

    public CertManagerException(final String message, final Throwable cause) {
        super(message, cause);
    }
}