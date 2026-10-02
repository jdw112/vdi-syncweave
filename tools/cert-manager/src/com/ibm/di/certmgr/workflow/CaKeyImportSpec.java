/*
 * Copyright contributors to the SyncWeave project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.certmgr.workflow;

import java.nio.file.Path;

/**
 * Input specification for the CA key import workflow.
 *
 * @param targetJks       path to the target keystore (server or admin side)
 * @param otherJks        path to the other keystore for cross-import (may not exist)
 * @param isAdminSide     true when the target is the admin keystore
 * @param p12Path         path to the source PKCS12 / PFX file
 * @param p12Pass         password protecting the PKCS12 file
 * @param targetStorepass password of the target JKS (may differ from p12Pass)
 * @param otherStorepass  password of the other JKS for cross-import
 * @param destAlias       alias to use in the target keystore
 * @param srcAlias        alias inside the PKCS12 to import (null = auto-detect)
 * @param baseDir         base directory used for registry.txt lookup
 * @param keytoolPath     absolute path to the keytool executable
 */
public record CaKeyImportSpec(
        Path targetJks,
        Path otherJks,
        boolean isAdminSide,
        Path p12Path,
        char[] p12Pass,
        char[] targetStorepass,
        char[] otherStorepass,
        String destAlias,
        String srcAlias,
        Path baseDir,
        String keytoolPath
) { }
