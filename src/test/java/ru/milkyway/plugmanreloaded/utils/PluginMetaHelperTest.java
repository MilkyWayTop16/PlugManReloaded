package ru.milkyway.plugmanreloaded.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class PluginMetaHelperTest {

    @Test
    public void testCleanVersion() {
        assertEquals("1.0", PluginMetaHelper.cleanVersion(null));
        assertEquals("1.0", PluginMetaHelper.cleanVersion(""));
        assertEquals("1.0", PluginMetaHelper.cleanVersion("   "));
        assertEquals("2.4", PluginMetaHelper.cleanVersion("v2.4"));
        assertEquals("2.4", PluginMetaHelper.cleanVersion("V2.4"));
        assertEquals("2.4", PluginMetaHelper.cleanVersion("vv2.4"));
        assertEquals("2.4", PluginMetaHelper.cleanVersion("vV2.4"));
        assertEquals("2.4", PluginMetaHelper.cleanVersion("2.4"));
        assertEquals("1.20.4-R0.1", PluginMetaHelper.cleanVersion("v1.20.4-R0.1"));
        assertEquals("7.0.17+2370-e42d8bc", PluginMetaHelper.cleanVersion("v7.0.17+2370-e42d8bc"));
    }

    @Test
    public void testIsVersionKey() {
        assertTrue(PluginMetaHelper.isVersionKey("version"));
        assertTrue(PluginMetaHelper.isVersionKey("ver"));
        assertTrue(PluginMetaHelper.isVersionKey("current"));
        assertTrue(PluginMetaHelper.isVersionKey("latest"));
        assertTrue(PluginMetaHelper.isVersionKey("old-version"));
        assertTrue(PluginMetaHelper.isVersionKey("new_version"));
        assertFalse(PluginMetaHelper.isVersionKey("plugin"));
        assertFalse(PluginMetaHelper.isVersionKey("author"));
        assertFalse(PluginMetaHelper.isVersionKey(null));
    }

    @Test
    public void testCleanupDoubleV() {
        assertEquals("v1.0", PluginMetaHelper.cleanupDoubleV("vv1.0"));
        assertEquals("&#FFFF00v2.0", PluginMetaHelper.cleanupDoubleV("&#FFFF00vv2.0"));
        assertEquals("Version 3.0", PluginMetaHelper.cleanupDoubleV("Version 3.0"));
    }
}
