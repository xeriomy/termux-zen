package com.termux.app.plugins;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PluginInstallerTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File pluginsDir;
    private PluginRegistry registry;
    private PluginInstaller installer;

    @Before
    public void setUp() throws Exception {
        pluginsDir = folder.newFolder("plugins");
        registry = new PluginRegistry(pluginsDir);
        installer = new PluginInstaller(pluginsDir, registry, PluginTestUtils.CORE);
    }

    @Test
    public void installsEnablesDisablesAndUninstalls() throws Exception {
        PluginRegistry.Entry entry = installTheme("com.termux.sample.theme", "1.0.0", false);
        assertFalse(entry.enabled);

        installer.setEnabled(entry.id, true);
        assertTrue(registry.find(entry.id).enabled);
        assertEquals(1, installer.getValidatedEnabled().size());

        installer.setEnabled(entry.id, false);
        assertTrue(installer.getValidatedEnabled().isEmpty());

        installer.uninstall(entry.id);
        assertNull(registry.find(entry.id));
        assertFalse(new File(registry.getInstalledDir(), entry.id).exists());
    }

    @Test
    public void allowsOnlyOneActiveThemeAndExtraKeys() throws Exception {
        installTheme("com.termux.sample.theme.a", "1.0.0", true);
        installTheme("com.termux.sample.theme.b", "1.0.0", true);
        assertFalse(registry.find("com.termux.sample.theme.a").enabled);
        assertTrue(registry.find("com.termux.sample.theme.b").enabled);

        installer.install(PluginTestUtils.zip(
            PluginTestUtils.manifest("com.termux.sample.keys", "1.0.0",
                PluginApi.TYPE_EXTRA_KEYS, ""),
            PluginTestUtils.extraKeysPayload()), null, -1,
            PluginInstaller.SOURCE_BUNDLED, true);
        assertTrue(registry.find("com.termux.sample.theme.b").enabled);
        assertTrue(registry.find("com.termux.sample.keys").enabled);
    }

    @Test
    public void enforcesDependencyState() throws Exception {
        installTheme("com.termux.base", "1.0.0", true);
        installer.install(PluginTestUtils.zip(
            PluginTestUtils.presetManifest("com.termux.sample.preset", "1.0.0",
                "\"com.termux.base@1.0.0\""),
            PluginTestUtils.presetPayload("Home", "")), null, -1,
            PluginInstaller.SOURCE_BUNDLED, true);
        assertTrue(registry.find("com.termux.sample.preset").enabled);

        installer.setEnabled("com.termux.base", false);
        assertFalse(registry.find("com.termux.sample.preset").enabled);
        assertThrows(PluginException.class,
            () -> installer.setEnabled("com.termux.sample.preset", true));

        installer.setEnabled("com.termux.base", true);
        installer.setEnabled("com.termux.sample.preset", true);
        assertTrue(registry.find("com.termux.sample.preset").enabled);
    }

    @Test
    public void uninstallDisablesDependents() throws Exception {
        installTheme("com.termux.base", "1.0.0", true);
        installer.install(PluginTestUtils.zip(
            PluginTestUtils.presetManifest("com.termux.sample.preset", "1.0.0",
                "\"com.termux.base@1.0.0\""),
            PluginTestUtils.presetPayload("Home", "")), null, -1,
            PluginInstaller.SOURCE_BUNDLED, true);

        installer.uninstall("com.termux.base");
        assertNull(registry.find("com.termux.base"));
        assertFalse(registry.find("com.termux.sample.preset").enabled);
    }

    @Test
    public void rejectsMissingDependencies() {
        PluginException error = assertThrows(PluginException.class, () ->
            installer.install(PluginTestUtils.zip(
                PluginTestUtils.presetManifest("com.termux.sample.preset", "1.0.0",
                    "\"com.termux.missing@1.0.0\""),
                PluginTestUtils.presetPayload("Home", "")), null, -1,
                PluginInstaller.SOURCE_BUNDLED, true));
        assertTrue(error.getMessage().contains("Missing dependency"));
    }

    @Test
    public void rejectsDependencyCyclesOnUpdate() throws Exception {
        installer.install(PluginTestUtils.zip(
            PluginTestUtils.presetManifest("com.termux.sample.a", "1.0.0", ""),
            PluginTestUtils.presetPayload("A", "")), null, -1,
            PluginInstaller.SOURCE_BUNDLED, true);
        installer.install(PluginTestUtils.zip(
            PluginTestUtils.presetManifest("com.termux.sample.b", "1.0.0",
                "\"com.termux.sample.a@1.0.0\""),
            PluginTestUtils.presetPayload("B", "")), null, -1,
            PluginInstaller.SOURCE_BUNDLED, true);

        assertThrows(PluginException.class, () -> installer.install(
            PluginTestUtils.zip(
                PluginTestUtils.presetManifest("com.termux.sample.a", "2.0.0",
                    "\"com.termux.sample.b@1.0.0\""),
                PluginTestUtils.presetPayload("A", "")), null, -1,
            PluginInstaller.SOURCE_BUNDLED, true));
        assertEquals("1.0.0", registry.find("com.termux.sample.a").version);
    }

    @Test
    public void updateKeepsEnabledStateAndRemovesOldVersion() throws Exception {
        installTheme("com.termux.sample.theme", "1.0.0", true);
        PluginRegistry.Entry updated = installTheme("com.termux.sample.theme", "2.0.0", false);
        assertTrue(updated.enabled);
        assertEquals("2.0.0", updated.version);
        assertFalse(new File(registry.getInstalledDir(),
            "com.termux.sample.theme/1.0.0").exists());
        assertTrue(new File(registry.getInstalledDir(),
            "com.termux.sample.theme/2.0.0").exists());
    }

    @Test
    public void updatingEnabledPluginRejectsDisabledDependency() throws Exception {
        installTheme("com.termux.base", "1.0.0", false);
        installer.install(PluginTestUtils.zip(
            PluginTestUtils.presetManifest("com.termux.sample.preset", "1.0.0", ""),
            PluginTestUtils.presetPayload("Home", "")), null, -1,
            PluginInstaller.SOURCE_BUNDLED, true);

        assertThrows(PluginException.class, () -> installer.install(
            PluginTestUtils.zip(
                PluginTestUtils.presetManifest("com.termux.sample.preset", "2.0.0",
                    "\"com.termux.base@1.0.0\""),
                PluginTestUtils.presetPayload("Home", "")), null, -1,
            PluginInstaller.SOURCE_BUNDLED, false));

        PluginRegistry.Entry entry = registry.find("com.termux.sample.preset");
        assertEquals("1.0.0", entry.version);
        assertTrue(entry.enabled);
    }

    @Test
    public void rejectsDowngrades() throws Exception {
        installTheme("com.termux.sample.theme", "2.0.0", false);
        assertThrows(PluginException.class,
            () -> installTheme("com.termux.sample.theme", "1.0.0", false));
        assertEquals("2.0.0", registry.find("com.termux.sample.theme").version);
    }

    @Test
    public void verifiesChecksumAndSizeFromRepository() throws Exception {
        byte[] archive = PluginTestUtils.zip(
            PluginTestUtils.themeManifest("com.termux.sample.theme", "1.0.0"),
            PluginTestUtils.themePayload());
        assertThrows(PluginException.class, () -> installer.install(archive,
            "0000000000000000000000000000000000000000000000000000000000000000",
            archive.length, PluginInstaller.SOURCE_REPOSITORY, false));
        assertThrows(PluginException.class, () -> installer.install(archive,
            PluginArchive.sha256(archive), archive.length + 1,
            PluginInstaller.SOURCE_REPOSITORY, false));
        assertNull(registry.find("com.termux.sample.theme"));
    }

    @Test
    public void detectsTamperedInstalledFiles() throws Exception {
        PluginRegistry.Entry entry = installTheme("com.termux.sample.theme", "1.0.0", true);
        File manifest = new File(registry.getInstalledDir(),
            entry.id + "/" + entry.version + "/manifest.json");
        try (FileOutputStream out = new FileOutputStream(manifest)) {
            out.write("{}".getBytes(PluginTestUtils.UTF_8));
        }
        assertThrows(PluginException.class, () -> installer.validate(entry));
        assertTrue(installer.getValidatedEnabled().isEmpty());
    }

    @Test
    public void uninstallLeavesUnrelatedFilesAlone() throws Exception {
        File marker = new File(pluginsDir, "user-marker.txt");
        try (FileOutputStream out = new FileOutputStream(marker)) {
            out.write("keep".getBytes(PluginTestUtils.UTF_8));
        }
        PluginRegistry.Entry entry = installTheme("com.termux.sample.theme", "1.0.0", false);
        installer.uninstall(entry.id);
        assertTrue(marker.isFile());
    }

    @Test
    public void installBundledSamplesOnce() throws Exception {
        // AssetManager is not available in a JVM test; the bundled loop is covered by manual
        // device verification and by validating each bundled ZIP through the same installer path.
        File[] assets = new File("src/main/assets/plugins").listFiles();
        assertNotNull(assets);
        for (File asset : assets) {
            byte[] bytes = java.nio.file.Files.readAllBytes(asset.toPath());
            PluginArchive archive = PluginArchive.read(bytes);
            PluginManifest manifest = PluginManifest.parse(
                new String(archive.manifestJson, PluginTestUtils.UTF_8), PluginTestUtils.CORE);
            PluginPayload.parse(new String(archive.payloadJson, PluginTestUtils.UTF_8), manifest);
            installer.install(bytes, null, -1, PluginInstaller.SOURCE_BUNDLED, false);
            assertNotNull(registry.find(manifest.id));
        }
    }

    private PluginRegistry.Entry installTheme(String id, String version, boolean enable)
        throws Exception {
        return installer.install(PluginTestUtils.zip(
            PluginTestUtils.themeManifest(id, version),
            PluginTestUtils.themePayload()), null, -1,
            PluginInstaller.SOURCE_BUNDLED, enable);
    }

}
