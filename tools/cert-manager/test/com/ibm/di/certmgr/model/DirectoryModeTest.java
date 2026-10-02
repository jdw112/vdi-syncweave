/*
 * Copyright contributors to the SyncWeave project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.ibm.di.certmgr.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DirectoryModeTest {

    @Test
    void installModeProperties() {
        DirectoryMode m = DirectoryMode.INSTALL;
        assertEquals("install",           m.getValue());
        assertEquals("Install Directory", m.getDisplayName());
        assertEquals("global.properties", m.getPropertiesFilename());
        assertEquals("etc",               m.getPropertiesSubdir());
        assertEquals("install",           m.toString());
    }

    @Test
    void solutionModeProperties() {
        DirectoryMode m = DirectoryMode.SOLUTION;
        assertEquals("solution",            m.getValue());
        assertEquals("Solution Directory",  m.getDisplayName());
        assertEquals("solution.properties", m.getPropertiesFilename());
        assertEquals("",                    m.getPropertiesSubdir());
        assertEquals("solution",            m.toString());
    }

    @Test
    void fromValueCaseInsensitive() {
        assertEquals(DirectoryMode.INSTALL,  DirectoryMode.fromValue("install"));
        assertEquals(DirectoryMode.INSTALL,  DirectoryMode.fromValue("INSTALL"));
        assertEquals(DirectoryMode.SOLUTION, DirectoryMode.fromValue("Solution"));
    }

    @Test
    void fromValueUnknownThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> DirectoryMode.fromValue("bogus"));
    }

    @Test
    void onlyTwoVariants() {
        assertEquals(2, DirectoryMode.values().length);
    }
}