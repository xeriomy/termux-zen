package com.termux.app.plugins;

import android.content.res.AssetManager;

import com.termux.shared.termux.extrakeys.ExtraKeysConstants;
import com.termux.shared.termux.extrakeys.ExtraKeysInfo;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The bundled plugins ship in the APK, so their payloads must be accepted by the real host code:
 * the extra-keys definition by {@link ExtraKeysInfo} and the theme colors by the terminal color
 * scheme parser. A payload that only passes the plugin validator but not the host parser would
 * silently do nothing at runtime.
 */
@RunWith(RobolectricTestRunner.class)
public class BundledPayloadHostTest {

    @Test
    public void shippedExtraKeysPayloadParsesInTheHost() throws Exception {
        String name = "coding-keys.zip";
        PluginManifest manifest = PluginManifest.parse(readAssetMember(name, "manifest.json"), "0.118.0");
        PluginPayload parsed = PluginPayload.parse(readAssetMember(name, "payload.json"), manifest);

        // The real host parser must accept the plugin's extra-keys definition.
        ExtraKeysInfo info = new ExtraKeysInfo(parsed.extraKeysJson, parsed.extraKeysStyle,
            ExtraKeysConstants.CONTROL_CHARS_ALIASES);
        Assert.assertNotNull("host must build the extra keys info", info);
        Assert.assertTrue("host must build at least one extra keys row", info.getMatrix().length > 0);
    }

    @Test
    public void shippedThemePayloadParsesInTheHost() throws Exception {
        for (String name : new String[]{"paper-light.zip", "solarized-dark.zip"}) {
            PluginManifest manifest = PluginManifest.parse(readAssetMember(name, "manifest.json"), "0.118.0");
            PluginPayload parsed = PluginPayload.parse(readAssetMember(name, "payload.json"), manifest);

            Properties props = parsed.themeProperties;
            Assert.assertNotNull("missing foreground in " + name, props.getProperty("foreground"));
            Assert.assertNotNull("missing background in " + name, props.getProperty("background"));
            Assert.assertNotNull("missing cursor in " + name, props.getProperty("cursor"));
            for (int i = 0; i <= 15; i++) {
                Assert.assertNotNull("missing color" + i + " in " + name, props.getProperty("color" + i));
            }
        }
    }

    private static String readAssetMember(String name, String member) throws Exception {
        AssetManager assets = RuntimeEnvironment.getApplication().getAssets();
        try (InputStream in = assets.open("plugins/" + name);
             ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals(member)) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = zip.read(buffer)) > 0) out.write(buffer, 0, read);
                    return new String(out.toByteArray(), "UTF-8");
                }
            }
        }
        throw new AssertionError("missing member " + member + " in " + name);
    }
}
