package com.termux.app.plugins;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Minimal installed/catalog manager. Installation always asks for confirmation first. */
public class PluginManagerActivity extends AppCompatActivity {

    private LinearLayout mContent;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private List<PluginRepositoryClient.CatalogEntry> mCatalog;
    private String mCatalogError;
    private boolean mCatalogLoading;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PluginManager.init(getApplicationContext());
        setTitle(R.string.title_activity_plugin_manager);

        ScrollView scroll = new ScrollView(this);
        mContent = new LinearLayout(this);
        mContent.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(16);
        mContent.setPadding(padding, padding, padding, padding);
        scroll.addView(mContent, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        refresh();
        loadCatalogIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        loadCatalogIfNeeded();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mExecutor.shutdownNow();
    }

    private void refresh() {
        if (mContent == null) return;
        PluginManager manager = PluginManager.getInstance();
        mContent.removeAllViews();
        if (manager == null) {
            addText(getString(R.string.plugins_error_not_initialized));
            return;
        }

        PluginRegistry registry = manager.getRegistry();
        registry.load();
        if (registry.getLastError() != null) addText(registry.getLastError());

        addSection(getString(R.string.plugins_installed_title));
        List<PluginRegistry.Entry> entries = registry.entries();
        if (entries.isEmpty()) addText(getString(R.string.plugins_none_installed));
        for (PluginRegistry.Entry entry : entries) addInstalledPlugin(manager, entry);

        addButton(getString(R.string.plugins_install_bundled), () -> installBundled(manager));
        addSection(getString(R.string.plugins_catalog_title));
        addCatalog(manager, registry);
    }

    private void addInstalledPlugin(PluginManager manager, PluginRegistry.Entry entry) {
        try {
            PluginInstaller.ValidatedPlugin plugin = manager.getInstaller().validate(entry);
            addText(plugin.manifest.name + " " + plugin.manifest.version, 16, true);
            addText(getString(R.string.plugins_plugin_summary, plugin.manifest.id,
                PluginPermissionManager.typeLabel(plugin.manifest.type),
                entry.enabled ? getString(R.string.plugins_status_enabled)
                    : getString(R.string.plugins_status_disabled)));
            addText(getString(R.string.plugins_permissions,
                TextUtils.join("\n  ", PluginPermissionManager.describeAll(plugin.manifest.capabilities))));
            addText(getString(R.string.plugins_dependencies,
                plugin.manifest.dependencies.isEmpty()
                    ? getString(R.string.plugins_dependencies_none)
                    : TextUtils.join(", ", plugin.manifest.dependencies)));
            if (!TextUtils.isEmpty(plugin.manifest.description))
                addText(plugin.manifest.description);
            if (PluginApi.TYPE_SESSION_PRESET.equals(plugin.manifest.type)) {
                addText(getString(R.string.plugins_preset_directory,
                    plugin.payload.presetName, "~/" + plugin.payload.presetWorkingDirectory));
            }

            LinearLayout row = buttonRow();
            if (entry.enabled) {
                addButton(row, getString(R.string.plugins_disable),
                    () -> changeEnabled(manager, entry.id, false));
            } else {
                addButton(row, getString(R.string.plugins_enable),
                    () -> confirmEnable(manager, plugin, entry.id));
            }
            addButton(row, getString(R.string.plugins_uninstall),
                () -> confirmUninstall(manager, plugin.manifest.name, entry.id));
            addView(row);
        } catch (PluginException e) {
            addText(getString(R.string.plugins_invalid_name, entry.id, entry.version), 16, true);
            addText(getString(R.string.plugins_invalid_message, e.getMessage()));
            LinearLayout row = buttonRow();
            addButton(row, getString(R.string.plugins_uninstall),
                () -> confirmUninstall(manager, entry.id, entry.id));
            addView(row);
        }
    }

    private void addCatalog(PluginManager manager, PluginRegistry registry) {
        if (!PluginRepositoryClient.isConfigured()) {
            addText(getString(R.string.plugins_repository_not_configured));
            return;
        }
        if (mCatalogLoading) {
            addText(getString(R.string.plugins_loading_catalog));
            return;
        }
        if (mCatalogError != null) {
            addText(getString(R.string.plugins_repository_failed, mCatalogError));
            addButton(getString(R.string.plugins_repository_refresh), this::loadCatalogIfNeeded);
            return;
        }
        if (mCatalog == null) {
            addButton(getString(R.string.plugins_repository_refresh), this::loadCatalogIfNeeded);
            return;
        }
        if (mCatalog.isEmpty()) {
            addText(getString(R.string.plugins_repository_empty));
            return;
        }

        for (PluginRepositoryClient.CatalogEntry entry : mCatalog) {
            addText(entry.name + " " + entry.version, 16, true);
            addText(getString(R.string.plugins_plugin_summary, entry.id,
                PluginPermissionManager.typeLabel(entry.type),
                entry.compatible ? getString(R.string.plugins_compatible)
                    : getString(R.string.plugins_requires_core, entry.minCoreVersion)));
            addText(getString(R.string.plugins_permissions,
                TextUtils.join("\n  ", PluginPermissionManager.describe(entry.type))));
            addText(getString(R.string.plugins_dependencies,
                entry.dependencies.isEmpty() ? getString(R.string.plugins_dependencies_none)
                    : TextUtils.join(", ", entry.dependencies)));
            if (!TextUtils.isEmpty(entry.description)) addText(entry.description);

            PluginRegistry.Entry installed = registry.find(entry.id);
            if (installed != null) {
                addText(getString(R.string.plugins_already_installed, installed.version));
            } else if (!entry.compatible) {
                addText(getString(R.string.plugins_incompatible));
            } else {
                addButton(getString(R.string.plugins_install),
                    () -> confirmInstallRepository(manager, entry));
            }
        }
    }

    private void confirmEnable(PluginManager manager, PluginInstaller.ValidatedPlugin plugin,
                               String id) {
        String dependencies = plugin.manifest.dependencies.isEmpty()
            ? getString(R.string.plugins_dependencies_none)
            : TextUtils.join(", ", plugin.manifest.dependencies);
        String message = getString(R.string.plugins_confirm_enable_message,
            TextUtils.join("\n  ", PluginPermissionManager.describeAll(plugin.manifest.capabilities)),
            dependencies);
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.plugins_confirm_enable_title, plugin.manifest.name))
            .setMessage(message)
            .setPositiveButton(R.string.plugins_enable, (dialog, which) ->
                changeEnabled(manager, id, true))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void confirmUninstall(PluginManager manager, String name, String id) {
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.plugins_confirm_uninstall_title, name))
            .setMessage(R.string.plugins_confirm_uninstall_message)
            .setPositiveButton(R.string.plugins_uninstall, (dialog, which) ->
                runPluginAction(manager, getString(R.string.plugins_uninstalled),
                    () -> manager.getInstaller().uninstall(id)))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void confirmInstallRepository(PluginManager manager,
                                          PluginRepositoryClient.CatalogEntry entry) {
        String message = getString(R.string.plugins_confirm_install_message,
            entry.version,
            PluginPermissionManager.typeLabel(entry.type),
            TextUtils.join("\n  ", PluginPermissionManager.describe(entry.type)),
            entry.size);
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.plugins_confirm_install_title, entry.name))
            .setMessage(message)
            .setPositiveButton(R.string.plugins_install, (dialog, which) ->
                runPluginAction(manager, getString(R.string.plugins_install_success), () -> {
                    byte[] bytes = PluginRepositoryClient.download(entry);
                    manager.getInstaller().install(bytes, entry.sha256, entry.size,
                        PluginInstaller.SOURCE_REPOSITORY, false);
                }))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void changeEnabled(PluginManager manager, String id, boolean enabled) {
        runPluginAction(manager,
            getString(enabled ? R.string.plugins_enabled : R.string.plugins_disabled),
            () -> manager.getInstaller().setEnabled(id, enabled));
    }

    private void installBundled(PluginManager manager) {
        runPluginAction(manager, getString(R.string.plugins_bundled_installed), () -> {
            int count = manager.getInstaller().installBundledSamples(getAssets());
            if (count == 0) throw new PluginException(getString(R.string.plugins_bundled_none));
        });
    }

    private void loadCatalogIfNeeded() {
        if (!PluginRepositoryClient.isConfigured() || mCatalogLoading || mCatalog != null) return;
        mCatalogLoading = true;
        mContent.removeAllViews();
        addText(getString(R.string.plugins_loading_catalog));
        mExecutor.execute(() -> {
            List<PluginRepositoryClient.CatalogEntry> catalog = null;
            String error = null;
            try {
                catalog = PluginRepositoryClient.fetchCatalog();
            } catch (Exception e) {
                error = e.getMessage();
            }
            List<PluginRepositoryClient.CatalogEntry> result = catalog;
            String resultError = error;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                mCatalogLoading = false;
                mCatalog = result;
                mCatalogError = resultError;
                refresh();
            });
        });
    }

    private void runPluginAction(PluginManager manager, String successMessage, PluginAction action) {
        mExecutor.execute(() -> {
            try {
                action.run();
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    manager.refresh();
                    refresh();
                    Toast.makeText(this, successMessage, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    new AlertDialog.Builder(this)
                        .setTitle(R.string.plugins_action_failed)
                        .setMessage(e.getMessage())
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                });
            }
        });
    }

    private void addSection(String text) {
        TextView view = addText(text, 20, true);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) view.getLayoutParams();
        params.topMargin = dp(20);
        params.bottomMargin = dp(4);
        view.setLayoutParams(params);
    }

    private void addText(String text) {
        addText(text, 14, false);
    }

    private TextView addText(String text, float size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setPadding(0, dp(2), 0, dp(2));
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        mContent.addView(view);
        return view;
    }

    private LinearLayout buttonRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(4), 0, dp(4));
        return row;
    }

    private void addButton(LinearLayout row, String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(v -> action.run());
        row.addView(button);
    }

    private void addButton(String text, Runnable action) {
        LinearLayout row = buttonRow();
        addButton(row, text, action);
        addView(row);
    }

    private void addView(android.view.View view) {
        mContent.addView(view, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private interface PluginAction {
        void run() throws Exception;
    }

}
