package com.termux.app.plugins;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PluginApiTest {

    @Test
    public void comparesSemverPrecedence() {
        assertTrue(PluginApi.compareVersions("1.0.0", "0.9.9") > 0);
        assertTrue(PluginApi.compareVersions("1.0.0", "1.0.0-rc.1") > 0);
        assertTrue(PluginApi.compareVersions("1.0.0-rc.2", "1.0.0-rc.1") > 0);
        assertTrue(PluginApi.compareVersions("1.0.0-alpha", "1.0.0-alpha.1") < 0);
        assertTrue(PluginApi.compareVersions("1.0.0+build.5", "1.0.0+build.6") == 0);
        assertEquals(0, PluginApi.compareVersions("0.118.0", "0.118.0"));
    }

    @Test
    public void checksCoreCompatibility() {
        assertTrue(PluginApi.isCoreVersionSupported("0.118.0", "0.118.0"));
        assertTrue(PluginApi.isCoreVersionSupported("0.100.0", "0.118.0"));
        assertFalse(PluginApi.isCoreVersionSupported("0.119.0", "0.118.0"));
    }

    @Test
    public void validatesIdsAndVersions() {
        assertTrue(PluginApi.isValidId("com.termux.sample.theme"));
        assertFalse(PluginApi.isValidId("com"));
        assertFalse(PluginApi.isValidId("../escape"));
        assertFalse(PluginApi.isValidId("Com.Termux"));
        assertTrue(PluginApi.isValidVersion("1.2.3-rc.1+build.4"));
        assertFalse(PluginApi.isValidVersion("1.2"));
        assertFalse(PluginApi.isValidVersion("01.2.3"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsComparisonsWithInvalidVersion() {
        PluginApi.compareVersions("1.0", "1.0.0");
    }

}
