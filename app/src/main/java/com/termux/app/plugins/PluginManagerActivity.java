package com.termux.app.plugins;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.tabs.TabLayout;
import com.termux.R;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.settings.properties.SharedProperties;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.termux.settings.properties.TermuxSharedProperties;
import com.termux.shared.theme.NightMode;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Material manager for installed, bundled, and repository declarative plugins. */
public class PluginManagerActivity extends AppCompatActivity implements PluginCardAdapter.Listener {

    private enum Tab {
        INSTALLED,
        BUNDLED,
        REPOSITORY
    }

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final List<PluginCard> mInstalledCards = new ArrayList<>();
    private final List<PluginCard> mBundledCards = new ArrayList<>();
    private final List<PluginCard> mRepositoryCards = new ArrayList<>();
    private final Map<String, PluginRepositoryClient.CatalogEntry> mCatalogEntries = new LinkedHashMap<>();
    private final Map<String, PluginInstaller.BundledPlugin> mBundledPlugins = new HashMap<>();
    private final Map<String, PluginRegistry.Entry> mInstalledEntries = new HashMap<>();

    /** Last fetched catalog, published to the worker thread for post-action refreshes. */
    private volatile List<PluginRepositoryClient.CatalogEntry> mCatalog = Collections.emptyList();

    private PluginCardAdapter mAdapter;
    private RecyclerView mRecyclerView;
    private View mStateContainer;
    private TextView mStateText;
    private MaterialButton mRetryButton;
    private CircularProgressIndicator mProgress;
    private TextView mSummaryText;
    private TabLayout mTabs;
    private Tab mSelectedTab = Tab.INSTALLED;
    private String mSearchQuery = "";
    private boolean mRegistryLoading = true;
    private boolean mBundledLoading = true;
    private boolean mRepositoryLoading;
    private String mRegistryError;
    private String mBundledError;
    private String mRepositoryError;
    private boolean mRepositoryConfigured;
    private boolean mCatalogLoaded;

    private static final String STATE_SELECTED_TAB = "plugin.selected_tab";
    private static final String STATE_SEARCH_QUERY = "plugin.search_query";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);
        PluginManager.init(getApplicationContext());
        setContentView(R.layout.activity_plugin_manager);

        MaterialToolbar toolbar = findViewById(R.id.plugin_toolbar);
        setSupportActionBar(toolbar);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setHomeActionContentDescription(R.string.plugins_navigate_up);
        }
        toolbar.setNavigationOnClickListener(view -> finish());

        mSummaryText = findViewById(R.id.plugin_summary_text);
        mRecyclerView = findViewById(R.id.plugin_recycler);
        mStateContainer = findViewById(R.id.plugin_state_container);
        mStateText = findViewById(R.id.plugin_state_text);
        mRetryButton = findViewById(R.id.plugin_state_retry);
        mProgress = findViewById(R.id.plugin_progress);
        mTabs = findViewById(R.id.plugin_tabs);

        mAdapter = new PluginCardAdapter(this);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        mRecyclerView.setAdapter(mAdapter);

        mTabs.addTab(mTabs.newTab().setText(R.string.plugins_installed_title));
        mTabs.addTab(mTabs.newTab().setText(R.string.plugins_bundled_title));
        mTabs.addTab(mTabs.newTab().setText(R.string.plugins_catalog_title));
        mTabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                mSelectedTab = Tab.values()[tab.getPosition()];
                if (mSelectedTab == Tab.REPOSITORY) loadRepository(false);
                renderCurrentTab();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        mRetryButton.setOnClickListener(view -> {
            if (mSelectedTab == Tab.REPOSITORY) loadRepository(true);
            else loadInstalledAndBundled();
        });

        mRepositoryConfigured = PluginRepositoryClient.isConfigured();
        if (savedInstanceState != null) {
            mSearchQuery = savedInstanceState.getString(STATE_SEARCH_QUERY, "");
            int selectedTab = savedInstanceState.getInt(STATE_SELECTED_TAB, 0);
            if (selectedTab >= 0 && selectedTab < mTabs.getTabCount()) mTabs.getTabAt(selectedTab).select();
        }
        renderCurrentTab();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt(STATE_SELECTED_TAB, mSelectedTab.ordinal());
        outState.putString(STATE_SEARCH_QUERY, mSearchQuery);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadInstalledAndBundled();
        // A restored Repository tab can be selected before the catalog was ever fetched. Retry
        // whenever the catalog is missing, and after a failed fetch.
        if (mSelectedTab == Tab.REPOSITORY && mRepositoryConfigured
            && (!mCatalogLoaded || mRepositoryError != null)) {
            loadRepository(mRepositoryError != null);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.plugin_menu_refresh) {
            if (mSelectedTab == Tab.REPOSITORY) loadRepository(true);
            else loadInstalledAndBundled();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mExecutor.shutdownNow();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.plugin_manager, menu);
        MenuItem searchItem = menu.findItem(R.id.plugin_menu_search);
        SearchView searchView = (SearchView) searchItem.getActionView();
        searchView.setQueryHint(getString(R.string.plugins_search));
        if (!mSearchQuery.isEmpty()) searchView.setQuery(mSearchQuery, false);
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                mSearchQuery = newText == null ? "" : newText;
                renderCurrentTab();
                return true;
            }
        });
        return true;
    }

    private void loadInstalledAndBundled() {
        mRegistryLoading = true;
        mBundledLoading = true;
        mRegistryError = null;
        mBundledError = null;
        renderCurrentTab();

        mExecutor.execute(() -> {
            final PluginSnapshot snapshot = computeSnapshot();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                applySnapshot(snapshot);
                updateSummary();
                renderCurrentTab();
            });
        });
    }

    /**
     * Reads the registry, validates installed plugins and reads the bundled assets. Must only be
     * called from the background executor: it performs file I/O.
     */
    private PluginSnapshot computeSnapshot() {
        PluginManager manager = PluginManager.getInstance();
        if (manager == null) {
            return new PluginSnapshot(Collections.emptyList(), Collections.emptyList(),
                Collections.emptyMap(), Collections.emptyMap(),
                getString(R.string.plugins_error_not_initialized), null);
        }

        manager.getRegistry().load();
        String registryError = manager.getRegistry().getLastError();
        List<PluginRegistry.Entry> entries = manager.getRegistry().entries();

        Map<String, PluginInstaller.ValidatedPlugin> validatedById = new HashMap<>();
        Map<String, String> validationErrors = new HashMap<>();
        Map<String, PluginRegistry.Entry> installedEntries = new HashMap<>();
        for (PluginRegistry.Entry entry : entries) {
            installedEntries.put(entry.id, entry);
            try {
                PluginInstaller.ValidatedPlugin plugin = manager.getInstaller().validate(entry);
                validatedById.put(plugin.manifest.id, plugin);
            } catch (PluginException e) {
                validationErrors.put(entry.id, errorMessage(e));
            }
        }

        List<PluginCard> installedCards = buildInstalledCards(entries, validatedById,
            validationErrors);

        List<PluginInstaller.BundledPlugin> bundled = Collections.emptyList();
        String bundledError = null;
        try {
            bundled = manager.getInstaller().getBundledPlugins(getAssets());
        } catch (Exception e) {
            bundledError = errorMessage(e);
        }
        List<PluginCard> bundledCards = buildBundledCards(bundled, validatedById);
        Map<String, PluginInstaller.BundledPlugin> bundledPlugins = new HashMap<>();
        for (PluginInstaller.BundledPlugin plugin : bundled) bundledPlugins.put(plugin.manifest.id, plugin);

        return new PluginSnapshot(installedCards, bundledCards, bundledPlugins, installedEntries,
            registryError, bundledError);
    }

    /** Applies a worker-computed snapshot to the UI state. Must only be called on the UI thread. */
    private void applySnapshot(PluginSnapshot snapshot) {
        mRegistryLoading = false;
        mBundledLoading = false;
        mRegistryError = snapshot.registryError;
        mBundledError = snapshot.bundledError;
        mInstalledCards.clear();
        mInstalledCards.addAll(snapshot.installedCards);
        mBundledCards.clear();
        mBundledCards.addAll(snapshot.bundledCards);
        mBundledPlugins.clear();
        mBundledPlugins.putAll(snapshot.bundledPlugins);
        mInstalledEntries.clear();
        mInstalledEntries.putAll(snapshot.installedEntries);
    }

    private void loadRepository(boolean force) {
        if (mRepositoryLoading) return;
        if (mCatalogLoaded && !force) return;
        mRepositoryConfigured = PluginRepositoryClient.isConfigured();
        if (!mRepositoryConfigured) {
            mRepositoryError = null;
            mRepositoryLoading = false;
            renderCurrentTab();
            return;
        }
        mRepositoryLoading = true;
        mRepositoryError = null;
        renderCurrentTab();
        mExecutor.execute(() -> {
            List<PluginRepositoryClient.CatalogEntry> catalog = null;
            String error = null;
            try {
                catalog = PluginRepositoryClient.fetchCatalog();
            } catch (Exception e) {
                error = errorMessage(e);
            }
            final List<PluginRepositoryClient.CatalogEntry> loadedCatalog = catalog;
            final String loadedError = error;
            final RepositoryCards cards = catalog == null ? null
                : buildRepositoryCards(catalog, loadInstalledEntries());
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                mRepositoryLoading = false;
                mRepositoryError = loadedError;
                mCatalogLoaded = loadedCatalog != null;
                if (loadedCatalog != null) mCatalog = loadedCatalog;
                applyRepositoryCards(cards);
                updateSummary();
                renderCurrentTab();
            });
        });
    }

    /**
     * Reads the installed registrations from the registry. Runs on the background executor.
     */
    private Map<String, PluginRegistry.Entry> loadInstalledEntries() {
        Map<String, PluginRegistry.Entry> installed = new HashMap<>();
        PluginManager manager = PluginManager.getInstance();
        if (manager == null) return installed;
        manager.getRegistry().load();
        for (PluginRegistry.Entry entry : manager.getRegistry().entries()) {
            installed.put(entry.id, entry);
        }
        return installed;
    }

    /**
     * Builds the installed-plugin cards. Runs on the background executor because it checks user
     * configuration files to determine whether a plugin is overridden.
     */
    private List<PluginCard> buildInstalledCards(List<PluginRegistry.Entry> entries,
                                                 Map<String, PluginInstaller.ValidatedPlugin> validatedById,
                                                 Map<String, String> validationErrors) {
        List<PluginCard> cards = new ArrayList<>();
        for (PluginRegistry.Entry entry : entries) {
            PluginInstaller.ValidatedPlugin plugin = validatedById.get(entry.id);
            if (plugin == null) {
                String invalidDetail = validationErrors.containsKey(entry.id)
                    ? validationErrors.get(entry.id) : getString(R.string.plugins_unknown_error);
                cards.add(new PluginCard(PluginCard.Kind.INSTALLED, entry.id, entry.id,
                    entry.version, typeLabel(entry.type), "", sourceMetadata(entry.source),
                    getString(R.string.plugins_status_invalid, invalidDetail),
                    Collections.emptyList(), Collections.emptyList(), PluginCard.Status.NEEDS_ATTENTION,
                    PluginCard.PrimaryAction.NONE, true, 0));
                continue;
            }

            PluginManifest manifest = plugin.manifest;
            String statusDetail = "";
            PluginCard.Status status;
            PluginCard.PrimaryAction action;
            if (!entry.enabled) {
                status = PluginCard.Status.DISABLED;
                action = PluginCard.PrimaryAction.ENABLE;
            } else {
                String dependencyProblem = dependencyProblem(manifest, entries, validatedById);
                if (dependencyProblem != null) {
                    status = PluginCard.Status.NEEDS_ATTENTION;
                    statusDetail = dependencyProblem;
                } else {
                    String override = overrideDetail(manifest, plugin);
                    if (override != null) {
                        status = PluginCard.Status.OVERRIDDEN;
                        statusDetail = override;
                    } else if (PluginApi.TYPE_SESSION_PRESET.equals(manifest.type)
                        && !presetDirectoryExists(plugin)) {
                        status = PluginCard.Status.INERT;
                        statusDetail = getString(R.string.plugins_status_preset_inert);
                    } else {
                        status = PluginCard.Status.ENABLED;
                    }
                }
                action = PluginCard.PrimaryAction.DISABLE;
            }

            String metadata = metadata(entry.source, manifest.apiVersion);
            if (PluginApi.TYPE_SESSION_PRESET.equals(manifest.type)) {
                metadata = metadata + "\n" + getString(R.string.plugins_preset_directory,
                    plugin.payload.presetWorkingDirectory.isEmpty()
                        ? getString(R.string.plugins_home_directory)
                        : getString(R.string.plugins_preset_path,
                            plugin.payload.presetWorkingDirectory));
            }
            cards.add(new PluginCard(PluginCard.Kind.INSTALLED, manifest.id,
                manifest.name, manifest.version, typeLabel(manifest.type), manifest.description,
                metadata, statusDetail, permissionLabels(manifest.capabilities),
                dependencyLabelsFromManifest(manifest.dependencies), status, action, true, manifest.apiVersion));
        }
        return cards;
    }

    /** Builds the bundled-plugin cards. Runs on the background executor. */
    private List<PluginCard> buildBundledCards(List<PluginInstaller.BundledPlugin> bundled,
                                                Map<String, PluginInstaller.ValidatedPlugin> validatedById) {
        List<PluginCard> cards = new ArrayList<>();
        if (bundled == null) return cards;
        Set<String> installedIds = new HashSet<>();
        for (PluginInstaller.ValidatedPlugin plugin : validatedById.values()) {
            installedIds.add(plugin.manifest.id);
        }
        for (PluginInstaller.BundledPlugin plugin : bundled) {
            PluginManifest manifest = plugin.manifest;
            boolean installed = installedIds.contains(manifest.id);
            PluginCard.Status status = installed ? PluginCard.Status.INSTALLED
                : PluginCard.Status.AVAILABLE;
            PluginCard.PrimaryAction action = installed ? PluginCard.PrimaryAction.NONE
                : PluginCard.PrimaryAction.INSTALL;
            String bundledMetadata = getString(R.string.plugins_source_bundled) + "\n"
                + getString(R.string.plugins_api_version, manifest.apiVersion);
            if (installed) {
                PluginInstaller.ValidatedPlugin installedPlugin = validatedById.get(manifest.id);
                if (installedPlugin != null) {
                    bundledMetadata = bundledMetadata + "\n" + getString(
                        R.string.plugins_already_installed, installedPlugin.manifest.version);
                }
            }
            cards.add(new PluginCard(PluginCard.Kind.BUNDLED, manifest.id, manifest.name,
                manifest.version, typeLabel(manifest.type), manifest.description, bundledMetadata,
                "", permissionLabels(manifest.capabilities), dependencyLabelsFromManifest(manifest.dependencies),
                status, action, true, manifest.apiVersion));
        }
        return cards;
    }

    /**
     * Builds the repository cards. Runs on the background executor. The installed entries are
     * passed in so the registry does not have to be read again here.
     */
    @Nullable
    private RepositoryCards buildRepositoryCards(List<PluginRepositoryClient.CatalogEntry> catalog,
                                                 Map<String, PluginRegistry.Entry> installedEntries) {
        if (catalog == null) return null;
        List<PluginCard> cards = new ArrayList<>();
        Map<String, PluginRepositoryClient.CatalogEntry> entriesById = new LinkedHashMap<>();
        for (PluginRepositoryClient.CatalogEntry entry : catalog) {
            entriesById.put(entry.id, entry);
            PluginRegistry.Entry installed = installedEntries.get(entry.id);
            PluginCard.Status status;
            PluginCard.PrimaryAction action;
            String metadata = getString(R.string.plugins_source_repository) + "\n"
                + (entry.compatible ? getString(R.string.plugins_compatible)
                : getString(R.string.plugins_requires_core, entry.minCoreVersion));
            if (!entry.compatible) {
                status = PluginCard.Status.INCOMPATIBLE;
                action = PluginCard.PrimaryAction.NONE;
            } else if (installed == null) {
                status = PluginCard.Status.AVAILABLE;
                action = PluginCard.PrimaryAction.INSTALL;
            } else if (PluginApi.compareVersions(entry.version, installed.version) > 0) {
                status = PluginCard.Status.UPDATE_AVAILABLE;
                action = PluginCard.PrimaryAction.UPDATE;
                metadata = metadata + "\n" + getString(R.string.plugins_already_installed,
                    installed.version);
            } else {
                status = PluginCard.Status.UP_TO_DATE;
                action = PluginCard.PrimaryAction.NONE;
                metadata = metadata + "\n" + getString(R.string.plugins_already_installed,
                    installed.version);
            }
            cards.add(new PluginCard(PluginCard.Kind.REPOSITORY, entry.id, entry.name,
                entry.version, typeLabel(entry.type), entry.description, metadata, "",
                permissionLabels(entry.type), dependencyLabels(entry.dependencies), status, action,
                true, PluginApi.VERSION));
        }
        return new RepositoryCards(cards, entriesById);
    }

    /** Applies worker-computed repository cards to the UI state. UI thread only. */
    private void applyRepositoryCards(@Nullable RepositoryCards cards) {
        mRepositoryCards.clear();
        mCatalogEntries.clear();
        if (cards == null) return;
        mRepositoryCards.addAll(cards.cards);
        mCatalogEntries.putAll(cards.entriesById);
    }

    private void renderCurrentTab() {
        if (mAdapter == null) return;
        List<PluginCard> source;
        boolean loading;
        String error = null;
        String emptyText;
        switch (mSelectedTab) {
            case INSTALLED:
                source = mInstalledCards;
                loading = mRegistryLoading;
                error = mRegistryError;
                emptyText = getString(R.string.plugins_none_installed);
                break;
            case BUNDLED:
                source = mBundledCards;
                loading = mBundledLoading;
                error = mBundledError;
                emptyText = getString(R.string.plugins_none_bundled);
                break;
            default:
                source = mRepositoryCards;
                loading = mRepositoryLoading;
                error = mRepositoryError;
                emptyText = getString(R.string.plugins_none_repository);
                break;
        }

        List<PluginCard> filtered = new ArrayList<>();
        for (PluginCard card : source) if (card.matches(mSearchQuery)) filtered.add(card);
        mAdapter.setCards(filtered);

        boolean showState = loading || error != null || filtered.isEmpty();
        mStateContainer.setVisibility(showState ? View.VISIBLE : View.GONE);
        mRecyclerView.setVisibility(showState ? View.GONE : View.VISIBLE);
        mProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
        mRetryButton.setVisibility(error != null ? View.VISIBLE : View.GONE);
        if (loading) {
            mStateText.setText(mSelectedTab == Tab.REPOSITORY
                ? R.string.plugins_loading_catalog : R.string.plugins_loading);
        } else if (error != null) {
            mStateText.setText(mSelectedTab == Tab.REPOSITORY
                ? getString(R.string.plugins_repository_failed, error) : error);
        } else if (mSelectedTab == Tab.REPOSITORY && !mRepositoryConfigured) {
            mStateText.setText(R.string.plugins_repository_not_configured);
            mRetryButton.setVisibility(View.GONE);
        } else {
            mStateText.setText(TextUtils.isEmpty(mSearchQuery) ? emptyText
                : getString(R.string.plugins_none_search));
        }
    }

    private void updateSummary() {
        if (mSummaryText == null) return;
        int enabled = 0;
        for (PluginCard card : mInstalledCards) {
            if (card.status == PluginCard.Status.ENABLED) enabled++;
        }
        String summary = getResources().getQuantityString(R.plurals.plugins_summary_installed,
            mInstalledCards.size(), mInstalledCards.size()) + " · " +
            getResources().getQuantityString(R.plurals.plugins_summary_active, enabled, enabled);
        if (!mBundledCards.isEmpty()) {
            summary = summary + "\n" + getResources().getQuantityString(
                R.plurals.plugins_summary_bundled, mBundledCards.size(), mBundledCards.size());
        }
        int updates = 0;
        for (PluginCard card : mRepositoryCards) {
            if (card.status == PluginCard.Status.UPDATE_AVAILABLE) updates++;
        }
        if (updates > 0) {
            summary = summary + "\n" + getResources().getQuantityString(
                R.plurals.plugins_summary_updates, updates, updates);
        }
        mSummaryText.setText(summary);
    }

    @Override
    public void onPrimaryAction(PluginCard card) {
        switch (card.primaryAction) {
            case ENABLE:
                confirmEnable(card);
                break;
            case DISABLE:
                confirmDisable(card);
                break;
            case INSTALL:
                if (card.kind == PluginCard.Kind.BUNDLED) confirmBundledInstall(card);
                else confirmRepositoryInstall(card);
                break;
            case UPDATE:
                confirmRepositoryUpdate(card);
                break;
            default:
                break;
        }
    }

    @Override
    public void onDetails(PluginCard card) {
        StringBuilder message = new StringBuilder();
        message.append(getString(R.string.plugins_details_id, card.id)).append('\n');
        message.append(getString(R.string.plugins_details_version, card.version)).append('\n');
        message.append(getString(R.string.plugins_details_type, card.type)).append('\n');
        message.append(getString(R.string.plugins_details_status, statusLabel(card.status))).append('\n');
        if (!card.description.isEmpty()) {
            message.append(getString(R.string.plugins_details_description, card.description)).append('\n');
        }
        message.append(getString(R.string.plugins_details_permissions,
            card.permissions.isEmpty() ? getString(R.string.plugins_none)
                : TextUtils.join("\n", card.permissions))).append('\n');
        message.append(getString(R.string.plugins_details_dependencies,
            card.dependencies.isEmpty() ? getString(R.string.plugins_dependencies_none)
                : TextUtils.join(", ", card.dependencies))).append('\n');
        message.append(getString(R.string.plugins_details_source, sourceLabel(card)));
        new MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.plugins_details_title, card.name))
            .setMessage(message.toString())
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    @Override
    public void onUninstall(PluginCard card) {
        confirmUninstall(card);
    }

    private void confirmEnable(PluginCard card) {
        new MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.plugins_confirm_enable_title, card.name))
            .setMessage(getString(R.string.plugins_confirm_enable_message,
                permissionText(card.permissions), dependencyText(card.dependencies)))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.plugins_enable, (dialog, which) ->
                runAction(getString(R.string.plugins_enabled), () -> {
                    PluginManager manager = PluginManager.getInstance();
                    if (manager == null) throw new PluginException(
                        getString(R.string.plugins_error_not_initialized));
                    manager.getInstaller().setEnabled(card.id, true);
                }))
            .show();
    }

    private void confirmDisable(PluginCard card) {
        new MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.plugins_confirm_disable_title, card.name))
            .setMessage(R.string.plugins_confirm_disable_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.plugins_disable, (dialog, which) ->
                runAction(getString(R.string.plugins_disabled), () -> {
                    PluginManager manager = PluginManager.getInstance();
                    if (manager == null) throw new PluginException(
                        getString(R.string.plugins_error_not_initialized));
                    manager.getInstaller().setEnabled(card.id, false);
                }))
            .show();
    }

    private void confirmUninstall(PluginCard card) {
        new MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.plugins_confirm_uninstall_title, card.name))
            .setMessage(R.string.plugins_confirm_uninstall_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.plugins_uninstall, (dialog, which) ->
                runAction(getString(R.string.plugins_uninstalled), () -> {
                    PluginManager manager = PluginManager.getInstance();
                    if (manager == null) throw new PluginException(
                        getString(R.string.plugins_error_not_initialized));
                    manager.getInstaller().uninstall(card.id);
                }))
            .show();
    }

    private void confirmBundledInstall(PluginCard card) {
        new MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.plugins_confirm_install_title, card.name))
            .setMessage(getString(R.string.plugins_confirm_bundled_install_message, card.version,
                card.type, getString(R.string.plugins_source_bundled), artifactSize(card.id),
                permissionText(card.permissions), dependencyText(card.dependencies)))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.plugins_install, (dialog, which) ->
                runAction(getString(R.string.plugins_install_success), () -> {
                    PluginInstaller.BundledPlugin bundled = findBundled(card.id);
                    if (bundled == null) throw new PluginException(
                        getString(R.string.plugins_unknown_error));
                    PluginManager manager = PluginManager.getInstance();
                    if (manager == null) throw new PluginException(
                        getString(R.string.plugins_error_not_initialized));
                    manager.getInstaller().install(bundled.archiveBytes, null, -1,
                        PluginInstaller.SOURCE_BUNDLED, true);
                }))
            .show();
    }

    private void confirmRepositoryInstall(PluginCard card) {
        PluginRepositoryClient.CatalogEntry entry = findCatalogEntry(card.id);
        if (entry == null) {
            showError(getString(R.string.plugins_unknown_error));
            return;
        }
        new MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.plugins_confirm_install_title, card.name))
            .setMessage(getString(R.string.plugins_confirm_install_message, card.version,
                card.type, getString(R.string.plugins_source_repository), artifactSize(entry.size),
                permissionText(card.permissions), dependencyText(card.dependencies)))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.plugins_install, (dialog, which) ->
                runAction(getString(R.string.plugins_install_success_disabled), () -> {
                    byte[] bytes = PluginRepositoryClient.download(entry);
                    PluginManager manager = PluginManager.getInstance();
                    if (manager == null) throw new PluginException(
                        getString(R.string.plugins_error_not_initialized));
                    manager.getInstaller().install(bytes, entry.sha256,
                        entry.size, PluginInstaller.SOURCE_REPOSITORY, false,
                        new PluginInstaller.ExpectedArtifact(entry.id, entry.version, entry.type));
                }))
            .show();
    }

    private void confirmRepositoryUpdate(PluginCard card) {
        PluginRepositoryClient.CatalogEntry entry = findCatalogEntry(card.id);
        if (entry == null) {
            showError(getString(R.string.plugins_unknown_error));
            return;
        }
        // The installed version comes from the snapshot loaded on the worker thread; reading the
        // registry here would do disk I/O on the UI thread.
        PluginRegistry.Entry installed = mInstalledEntries.get(card.id);
        String oldVersion = installed == null ? "" : installed.version;
        new MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.plugins_confirm_update_title, card.name))
            .setMessage(getString(R.string.plugins_confirm_update_message, oldVersion,
                card.version, card.type, getString(R.string.plugins_source_repository),
                artifactSize(entry.size),
                permissionText(card.permissions), dependencyText(card.dependencies)))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.plugins_update, (dialog, which) ->
                runAction(getString(R.string.plugins_update_success), () -> {
                    byte[] bytes = PluginRepositoryClient.download(entry);
                    PluginManager currentManager = PluginManager.getInstance();
                    if (currentManager == null) throw new PluginException(
                        getString(R.string.plugins_error_not_initialized));
                    currentManager.getInstaller().install(bytes, entry.sha256,
                        entry.size, PluginInstaller.SOURCE_REPOSITORY, false,
                        new PluginInstaller.ExpectedArtifact(entry.id, entry.version, entry.type));
                }))
            .show();
    }

    private void runAction(String successMessage, PluginAction action) {
        mExecutor.execute(() -> {
            try {
                action.run();
                PluginManager manager = PluginManager.getInstance();
                if (manager != null) manager.refresh();
                final PluginSnapshot snapshot = computeSnapshot();
                final RepositoryCards repositoryCards = mCatalog.isEmpty() ? null
                    : buildRepositoryCards(mCatalog, snapshot.installedEntries);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, successMessage, Toast.LENGTH_SHORT).show();
                    applySnapshot(snapshot);
                    applyRepositoryCards(repositoryCards);
                    updateSummary();
                    renderCurrentTab();
                });
            } catch (Exception e) {
                // An action can fail after the registry changed (for example when installed files
                // could not be deleted). Re-read the durable state so the runtime and the cards
                // match what the app will actually load.
                PluginManager manager = PluginManager.getInstance();
                if (manager != null) manager.refresh();
                final String message = errorMessage(e);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    loadInstalledAndBundled();
                    showError(message);
                });
            }
        });
    }

    private void showError(String message) {
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.plugins_action_failed)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    @Nullable
    private PluginInstaller.BundledPlugin findBundled(String id) {
        return mBundledPlugins.get(id);
    }

    private int bundledSize(String id) {
        PluginInstaller.BundledPlugin bundled = findBundled(id);
        return bundled == null ? 0 : bundled.archiveBytes.length;
    }

    /**
     * Formats an artifact size as a plural-aware line, so "1 byte" and "2 bytes" both read
     * correctly in the confirmation dialogs.
     */
    private String artifactSize(int bytes) {
        return getResources().getQuantityString(R.plurals.plugins_artifact_size, bytes, bytes);
    }

    private String artifactSize(String bundledId) {
        return artifactSize(bundledSize(bundledId));
    }

    @Nullable
    private PluginRepositoryClient.CatalogEntry findCatalogEntry(String id) {
        return mCatalogEntries.get(id);
    }

    /**
     * Resolves dependency problems from the loaded snapshot. Runs on the background executor; the
     * registry snapshot and validation results are passed in so no file is read here.
     */
    private String dependencyProblem(PluginManifest manifest,
                                     List<PluginRegistry.Entry> entries,
                                     Map<String, PluginInstaller.ValidatedPlugin> validatedById) {
        Map<String, PluginRegistry.Entry> installedById = new HashMap<>();
        for (PluginRegistry.Entry entry : entries) installedById.put(entry.id, entry);
        for (PluginManifest.Dependency dependency : manifest.dependencies) {
            PluginRegistry.Entry installed = installedById.get(dependency.id);
            if (installed == null) {
                return getString(R.string.plugins_status_missing_dependency, dependency.id);
            }
            if (PluginApi.compareVersions(installed.version, dependency.minVersion) < 0) {
                return getString(R.string.plugins_status_missing_dependency, dependency.id);
            }
            if (!installed.enabled) {
                return getString(R.string.plugins_status_disabled_dependency, dependency.id);
            }
            if (!validatedById.containsKey(dependency.id)) {
                return getString(R.string.plugins_status_invalid_dependency, dependency.id);
            }
        }
        return null;
    }

    @Nullable
    private String overrideDetail(PluginManifest manifest,
                                  PluginInstaller.ValidatedPlugin plugin) {
        if (PluginApi.TYPE_THEME.equals(manifest.type)
            && TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE.isFile()) {
            return getString(R.string.plugins_status_theme_overridden);
        }
        if (PluginApi.TYPE_EXTRA_KEYS.equals(manifest.type) && hasUserExtraKeys()) {
            return getString(R.string.plugins_status_keys_overridden);
        }
        return null;
    }

    private boolean hasUserExtraKeys() {
        // Read the properties file directly instead of using the shared cache: the cache may
        // predate an edit made in the terminal. This runs on the background executor, so the file
        // read is safe. The shared loader is not used because it shows a toast on read errors,
        // and a toast must not be posted from a background thread.
        File propertiesFile = SharedProperties.getPropertiesFileFromList(
            TermuxConstants.TERMUX_PROPERTIES_FILE_PATHS_LIST, TermuxSharedProperties.LOG_TAG);
        if (propertiesFile == null) return false;
        Properties raw = new Properties();
        try (InputStream in = new FileInputStream(propertiesFile)) {
            raw.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            Logger.logStackTraceWithMessage("PluginManager", "Could not read " + propertiesFile, e);
            return false;
        }
        return raw.containsKey(TermuxPropertyConstants.KEY_EXTRA_KEYS);
    }

    private boolean presetDirectoryExists(PluginInstaller.ValidatedPlugin plugin) {
        String relative = plugin.payload.presetWorkingDirectory;
        File directory = relative.isEmpty() || ".".equals(relative)
            ? new File(TermuxConstants.TERMUX_HOME_DIR_PATH)
            : new File(TermuxConstants.TERMUX_HOME_DIR_PATH, relative);
        return directory.isDirectory();
    }

    private String metadata(String source, int apiVersion) {
        return sourceMetadata(source) + "\n" + getString(R.string.plugins_api_version, apiVersion);
    }

    private String sourceMetadata(String source) {
        return PluginInstaller.SOURCE_BUNDLED.equals(source)
            ? getString(R.string.plugins_source_bundled) : getString(R.string.plugins_source_repository);
    }

    private String sourceLabel(PluginCard card) {
        if (card.kind == PluginCard.Kind.BUNDLED) return getString(R.string.plugins_source_bundled);
        if (card.kind == PluginCard.Kind.REPOSITORY) return getString(R.string.plugins_source_repository);
        PluginRegistry.Entry entry = mInstalledEntries.get(card.id);
        return entry != null && PluginInstaller.SOURCE_BUNDLED.equals(entry.source)
            ? getString(R.string.plugins_source_bundled) : getString(R.string.plugins_source_repository);
    }

    private String typeLabel(String type) {
        if (PluginApi.TYPE_THEME.equals(type)) return getString(R.string.plugins_type_theme);
        if (PluginApi.TYPE_EXTRA_KEYS.equals(type)) return getString(R.string.plugins_type_extra_keys);
        if (PluginApi.TYPE_SESSION_PRESET.equals(type)) {
            return getString(R.string.plugins_type_session_preset);
        }
        return type;
    }

    private List<String> permissionLabels(String type) {
        if (PluginApi.TYPE_THEME.equals(type)) {
            return Collections.singletonList(getString(R.string.plugins_permission_theme));
        }
        if (PluginApi.TYPE_EXTRA_KEYS.equals(type)) {
            return Collections.singletonList(getString(R.string.plugins_permission_extra_keys));
        }
        if (PluginApi.TYPE_SESSION_PRESET.equals(type)) {
            return Collections.singletonList(getString(R.string.plugins_permission_session_preset));
        }
        return Collections.emptyList();
    }

    private List<String> permissionLabels(List<String> capabilities) {
        List<String> labels = new ArrayList<>();
        for (String capability : capabilities) {
            if (PluginApi.CAPABILITY_THEME.equals(capability)) {
                labels.add(getString(R.string.plugins_permission_theme));
            } else if (PluginApi.CAPABILITY_EXTRA_KEYS.equals(capability)) {
                labels.add(getString(R.string.plugins_permission_extra_keys));
            } else if (PluginApi.CAPABILITY_SESSION_PRESET.equals(capability)) {
                labels.add(getString(R.string.plugins_permission_session_preset));
            }
        }
        return labels;
    }

    private List<String> dependencyLabelsFromManifest(List<PluginManifest.Dependency> dependencies) {
        List<String> labels = new ArrayList<>();
        for (PluginManifest.Dependency dependency : dependencies) {
            labels.add(getString(R.string.plugins_dependency_version, dependency.id,
                dependency.minVersion));
        }
        return labels;
    }

    private List<String> dependencyLabels(List<String> dependencies) {
        return new ArrayList<>(dependencies);
    }

    private String permissionText(List<String> permissions) {
        return permissions.isEmpty() ? getString(R.string.plugins_none)
            : TextUtils.join("\n", permissions);
    }

    private String dependencyText(List<String> dependencies) {
        return dependencies.isEmpty() ? getString(R.string.plugins_dependencies_none)
            : TextUtils.join(", ", dependencies);
    }

    private String statusLabel(PluginCard.Status status) {
        switch (status) {
            case ENABLED:
                return getString(R.string.plugins_status_enabled);
            case DISABLED:
                return getString(R.string.plugins_status_disabled);
            case NEEDS_ATTENTION:
                return getString(R.string.plugins_status_needs_attention);
            case OVERRIDDEN:
                return getString(R.string.plugins_status_overridden);
            case INERT:
                return getString(R.string.plugins_status_inert);
            case INSTALLED:
                return getString(R.string.plugins_status_installed);
            case AVAILABLE:
                return getString(R.string.plugins_status_available);
            case UPDATE_AVAILABLE:
                return getString(R.string.plugins_status_update_available);
            case UP_TO_DATE:
                return getString(R.string.plugins_status_up_to_date);
            case INCOMPATIBLE:
                return getString(R.string.plugins_status_incompatible);
            default:
                return "";
        }
    }

    private String errorMessage(Exception exception) {
        String message = exception.getMessage();
        return TextUtils.isEmpty(message) ? getString(R.string.plugins_unknown_error) : message;
    }

    private interface PluginAction {
        void run() throws Exception;
    }

    /** Immutable worker-computed state for the installed and bundled tabs. */
    private static final class PluginSnapshot {
        final List<PluginCard> installedCards;
        final List<PluginCard> bundledCards;
        final Map<String, PluginInstaller.BundledPlugin> bundledPlugins;
        final Map<String, PluginRegistry.Entry> installedEntries;
        final String registryError;
        final String bundledError;

        PluginSnapshot(List<PluginCard> installedCards, List<PluginCard> bundledCards,
                       Map<String, PluginInstaller.BundledPlugin> bundledPlugins,
                       Map<String, PluginRegistry.Entry> installedEntries,
                       String registryError, String bundledError) {
            this.installedCards = installedCards;
            this.bundledCards = bundledCards;
            this.bundledPlugins = bundledPlugins;
            this.installedEntries = installedEntries;
            this.registryError = registryError;
            this.bundledError = bundledError;
        }
    }

    /** Immutable worker-computed state for the repository tab. */
    private static final class RepositoryCards {
        final List<PluginCard> cards;
        final Map<String, PluginRepositoryClient.CatalogEntry> entriesById;

        RepositoryCards(List<PluginCard> cards,
                        Map<String, PluginRepositoryClient.CatalogEntry> entriesById) {
            this.cards = cards;
            this.entriesById = entriesById;
        }
    }
}
