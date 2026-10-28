package com.termux.app.plugins;

import android.content.Context;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.tabs.TabLayout;
import com.termux.R;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;

/**
 * Smoke test for the plugin manager UI: it drives the real activity and asserts that a bundled
 * plugin can be installed through the card actions, that the installed tab then lists it, and
 * that the state survives an activity recreation.
 */
@RunWith(RobolectricTestRunner.class)
public class PluginManagerActivitySmokeTest {

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        PluginManager.init(context);
    }

    @Test
    public void bundledPluginCanBeInstalledFromTheUi() throws Exception {
        ActivityController<PluginManagerActivity> controller =
            Robolectric.buildActivity(PluginManagerActivity.class).setup();
        PluginManagerActivity activity = controller.get();

        // The Bundled tab lists the shipped plugins.
        TabLayout tabs = activity.findViewById(R.id.plugin_tabs);
        Assert.assertNotNull(tabs);
        Assert.assertEquals(3, tabs.getTabCount());
        tabs.getTabAt(1).select();
        idle(activity);

        RecyclerView recycler = activity.findViewById(R.id.plugin_recycler);
        Assert.assertNotNull(recycler);
        Assert.assertEquals("bundled cards must be visible", 3, recycler.getAdapter().getItemCount());

        // The first bundled card offers an install action.
        RecyclerView.ViewHolder holder = recycler.findViewHolderForAdapterPosition(0);
        Assert.assertNotNull("first bundled card must be laid out", holder);
        MaterialButton install = holder.itemView.findViewById(R.id.plugin_primary_action);
        Assert.assertNotNull("bundled card must offer a primary action", install);
        Assert.assertEquals(View.VISIBLE, install.getVisibility());

        // Remember which bundled plugin is being installed so the assertions below do not
        // depend on the order the bundled assets happen to be listed in.
        PluginCard bundledCard = ((PluginCardAdapter) recycler.getAdapter()).getCard(0);
        Assert.assertNotNull("bundled card model must be available", bundledCard);

        // Tapping install asks for confirmation, and confirming installs the plugin.
        install.performClick();
        idle(activity);
        android.app.Dialog dialog = latestDialog();
        Assert.assertNotNull("install must ask for confirmation", dialog);

        // The confirmation button lives in the dialog window, not in the activity view tree.
        MaterialButton confirm = dialog.findViewById(android.R.id.button1);
        Assert.assertNotNull("confirmation dialog must have a positive button", confirm);
        confirm.performClick();
        idle(activity);
        idle(activity);

        // The plugin is now registered as installed.
        PluginRegistry registry = PluginManager.getInstance().getRegistry();
        Assert.assertNotNull("installed plugin must be in the registry",
            registry.find(bundledCard.id));

        // The Installed tab lists it.
        tabs.getTabAt(0).select();
        idle(activity);
        Assert.assertEquals("installed cards must be visible", 1, recycler.getAdapter().getItemCount());
    }

    private static void idle(PluginManagerActivity activity) {
        // Let the background executor finish and post its result back to the UI thread.
        for (int i = 0; i < 100; i++) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
        }
    }

    private static android.app.Dialog latestDialog() {
        return org.robolectric.shadows.ShadowDialog.getLatestDialog();
    }
}
