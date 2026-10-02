/*
 * Copyright contributors to the SyncWeave project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.certmgr.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordValidatorTest {

    private PasswordValidator validator;

    @BeforeEach
    void setUp() {
        // complexity on, min length 8 - matches production defaults
        validator = new PasswordValidator(8, true);
    }

    // ---- null / empty -------------------------------------------------------

    @Test
    void nullPasswordFails() {
        assertFalse(validator.validate((char[]) null).valid());
    }

    @Test
    void emptyPasswordFails() {
        assertFalse(validator.validate(new char[0]).valid());
    }

    @Test
    void emptyStringPasswordFails() {
        assertFalse(validator.validate("").valid());
    }

    // ---- length -------------------------------------------------------------

    @Test
    void tooShortPasswordFails() {
        ValidationResult r = validator.validate("Ab1!");
        assertFalse(r.valid());
        assertTrue(r.message().contains("8 characters"));
    }

    @Test
    void exactMinLengthPasses() {
        // 8 chars with all required character classes
        assertTrue(validator.validate("Abcdef1!").valid());
    }

    @Test
    void longPasswordPasses() {
        assertTrue(validator.validate("ThisIsALong1Password!").valid());
    }

    // ---- complexity ---------------------------------------------------------

    @Test
    void missingUppercaseFails() {
        ValidationResult r = validator.validate("abcdef1!");
        assertFalse(r.valid());
        assertTrue(r.message().contains("uppercase"));
    }

    @Test
    void missingLowercaseFails() {
        ValidationResult r = validator.validate("ABCDEF1!");
        assertFalse(r.valid());
        assertTrue(r.message().contains("lowercase"));
    }

    @Test
    void missingDigitFails() {
        ValidationResult r = validator.validate("Abcdefg!");
        assertFalse(r.valid());
        assertTrue(r.message().contains("digit"));
    }

    @Test
    void missingSpecialCharFails() {
        ValidationResult r = validator.validate("Abcdef12");
        assertFalse(r.valid());
        assertTrue(r.message().contains("special"));
    }

    @Test
    void allComplexityRequirementsMet() {
        assertTrue(validator.validate("Password1!").valid());
    }

    // ---- complexity disabled ------------------------------------------------

    @Test
    void complexityDisabledAcceptsSimplePassword() {
        PasswordValidator simple = new PasswordValidator(8, false);
        assertTrue(simple.validate("password").valid());
    }

    @Test
    void complexityDisabledStillEnforcesLength() {
        PasswordValidator simple = new PasswordValidator(8, false);
        assertFalse(simple.validate("pass").valid());
    }

    // ---- custom min length --------------------------------------------------

    @Test
    void customMinLengthOf12() {
        PasswordValidator v = new PasswordValidator(12, true);
        assertFalse(v.validate("Password1!").valid());
        assertTrue(v.validate("LongPassword1!").valid());
    }

    // ---- success message ----------------------------------------------------

    @Test
    void successResultHasEmptyMessage() {
        ValidationResult r = validator.validate("Password1!");
        assertTrue(r.valid());
        assertEquals("", r.message());
    }

    // ---- ValidationResult factories -----------------------------------------

    @Test
    void okSingletonIsValid() {
        assertTrue(ValidationResult.OK.valid());
        assertEquals("", ValidationResult.OK.message());
    }

    @Test
    void failFactoryIsInvalid() {
        ValidationResult r = ValidationResult.fail("reason");
        assertFalse(r.valid());
        assertEquals("reason", r.message());
    }
}