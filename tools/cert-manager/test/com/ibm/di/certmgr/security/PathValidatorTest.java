/*
 * Copyright contributors to the SyncWeave project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.certmgr.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PathValidatorTest {

    private PathValidator validator;

    @BeforeEach
    void setUp() {
        validator = new PathValidator();
    }

    // ---- null / blank -------------------------------------------------------

    @Test
    void nullPathFails() {
        assertFalse(validator.validate(null).valid());
    }

    @Test
    void blankPathFails() {
        assertFalse(validator.validate("   ").valid());
    }

    @Test
    void emptyPathFails() {
        assertFalse(validator.validate("").valid());
    }

    // ---- traversal prevention -----------------------------------------------

    @Test
    void dotDotInPathFails() {
        ValidationResult r = validator.validate("/some/path/../etc/passwd");
        assertFalse(r.valid());
        assertTrue(r.message().contains(".."));
    }

    @Test
    void dotDotAtStartFails() {
        assertFalse(validator.validate("../../etc/shadow").valid());
    }

    @Test
    void dotDotAsComponentFails() {
        assertFalse(validator.validate("/safe/dir/..").valid());
    }

    @Test
    void normalPathPasses() {
        assertTrue(validator.validate("/opt/IBM/TDI/V7.2").valid());
    }

    @Test
    void relativePathWithoutTraversalPasses() {
        assertTrue(validator.validate("subdir/file.jks").valid());
    }

    // ---- base dir containment -----------------------------------------------

    @Test
    void pathInsideBaseDirPasses(@TempDir Path tmp) {
        Path child = tmp.resolve("subdir/file.jks");
        assertTrue(validator.validate(child.toString(), tmp).valid());
    }

    @Test
    void pathOutsideBaseDirFails(@TempDir Path tmp) {
        ValidationResult r = validator.validate("/etc/passwd", tmp);
        assertFalse(r.valid());
        assertTrue(r.message().contains("within"));
    }

    @Test
    void baseDirItselfPasses(@TempDir Path tmp) {
        assertTrue(validator.validate(tmp.toString(), tmp).valid());
    }

    // ---- sanitize -----------------------------------------------------------

    @Test
    void sanitizeRemovesDangerousChars() {
        String result = validator.sanitize("hello|world;&$`<>");
        assertEquals("helloworld", result);
    }

    @Test
    void sanitizeRemovesNewlines() {
        String result = validator.sanitize("line1\nline2\rend");
        assertEquals("line1line2end", result);
    }

    @Test
    void sanitizeTrimsWhitespace() {
        assertEquals("hello", validator.sanitize("  hello  "));
    }

    @Test
    void sanitizeAllowsSpecialCharsWhenEnabled() {
        String input = "hello|world";
        assertEquals("hello|world", validator.sanitize(input, true));
    }

    @Test
    void sanitizeNullReturnsEmpty() {
        assertEquals("", validator.sanitize(null));
    }

    @Test
    void sanitizeSafeInputUnchanged() {
        assertEquals("testserver.jks", validator.sanitize("testserver.jks"));
    }

    @Test
    void sanitizeCommandInjectionAttempt() {
        // Typical shell injection attempt
        String malicious = "valid; rm -rf /";
        String result = validator.sanitize(malicious);
        assertFalse(result.contains(";"));
        assertTrue(result.contains("valid"));
    }
}