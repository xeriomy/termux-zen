package com.termux.app.terminal.io;

import android.annotation.SuppressLint;
import android.view.Gravity;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.drawerlayout.widget.DrawerLayout;

import com.termux.app.TermuxActivity;
import com.termux.app.plugins.PluginManager;
import com.termux.app.plugins.PluginRuntime;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.extrakeys.ExtraKeysConstants;
import com.termux.shared.termux.extrakeys.ExtraKeysInfo;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.termux.settings.properties.TermuxSharedProperties;
import com.termux.shared.termux.terminal.io.TerminalExtraKeys;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import org.json.JSONException;

import java.util.Properties;

public class TermuxTerminalExtraKeys extends TerminalExtraKeys {

    private ExtraKeysInfo mExtraKeysInfo;

    final TermuxActivity mActivity;
    final TermuxTerminalViewClient mTermuxTerminalViewClient;
    final TermuxTerminalSessionActivityClient mTermuxTerminalSessionActivityClient;

    private static final String LOG_TAG = "TermuxTerminalExtraKeys";

    public TermuxTerminalExtraKeys(TermuxActivity activity, @NonNull TerminalView terminalView,
                                   TermuxTerminalViewClient termuxTerminalViewClient,
                                   TermuxTerminalSessionActivityClient termuxTerminalSessionActivityClient) {
        super(terminalView);

        mActivity = activity;
        mTermuxTerminalViewClient = termuxTerminalViewClient;
        mTermuxTerminalSessionActivityClient = termuxTerminalSessionActivityClient;

        setExtraKeys();
    }

    /** Re-reads properties and active plugin state, then rebuilds the extra keys. */
    public void reload() {
        setExtraKeys();
    }


    /**
     * Set the terminal extra keys and style.
     */
    private void setExtraKeys() {
        mExtraKeysInfo = null;

        try {
            // The mMap stores the extra key and style string values while loading properties
            // Check {@link #getExtraKeysInternalPropertyValueFromValue(String)} and
            // {@link #getExtraKeysStyleInternalPropertyValueFromValue(String)}
            String extrakeys = (String) mActivity.getProperties().getInternalPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS, true);
            String extraKeysStyle = (String) mActivity.getProperties().getInternalPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE, true);

            // A plugin is a fallback only. Explicit user values for the keys or the style win.
            // Use the raw file properties: the internal map contains defaults for every known key.
            Properties userProperties = mActivity.getProperties().getProperties(true);
            if (!userProperties.containsKey(TermuxPropertyConstants.KEY_EXTRA_KEYS)) {
                PluginRuntime runtime = PluginManager.getRuntime();
                if (runtime != null && runtime.getExtraKeysJson() != null) {
                    extrakeys = runtime.getExtraKeysJson();
                    if (!userProperties.containsKey(TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE))
                        extraKeysStyle = runtime.getExtraKeysStyle();
                }
            }

            ExtraKeysConstants.ExtraKeyDisplayMap extraKeyDisplayMap = ExtraKeysInfo.getCharDisplayMapForStyle(extraKeysStyle);
            if (ExtraKeysConstants.EXTRA_KEY_DISPLAY_MAPS.DEFAULT_CHAR_DISPLAY.equals(extraKeyDisplayMap) && !TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE.equals(extraKeysStyle)) {
                Logger.logError(TermuxSharedProperties.LOG_TAG, "The style \"" + extraKeysStyle + "\" for the key \"" + TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE + "\" is invalid. Using default style instead.");
                extraKeysStyle = TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE;
            }

            mExtraKeysInfo = new ExtraKeysInfo(extrakeys, extraKeysStyle, ExtraKeysConstants.CONTROL_CHARS_ALIASES);
        } catch (JSONException e) {
            Logger.showToast(mActivity, "Could not load and set the \"" + TermuxPropertyConstants.KEY_EXTRA_KEYS + "\" property from the properties file: " + e.toString(), true);
            Logger.logStackTraceWithMessage(LOG_TAG, "Could not load and set the \"" + TermuxPropertyConstants.KEY_EXTRA_KEYS + "\" property from the properties file: ", e);

            try {
                mExtraKeysInfo = new ExtraKeysInfo(TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS, TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE, ExtraKeysConstants.CONTROL_CHARS_ALIASES);
            } catch (JSONException e2) {
                Logger.showToast(mActivity, "Can't create default extra keys",true);
                Logger.logStackTraceWithMessage(LOG_TAG, "Could create default extra keys: ", e);
                mExtraKeysInfo = null;
            }
        }
    }

    public ExtraKeysInfo getExtraKeysInfo() {
        return mExtraKeysInfo;
    }

    @SuppressLint("RtlHardcoded")
    @Override
    public void onTerminalExtraKeyButtonClick(View view, String key, boolean ctrlDown, boolean altDown, boolean shiftDown, boolean fnDown) {
        if ("KEYBOARD".equals(key)) {
            if(mTermuxTerminalViewClient != null)
                mTermuxTerminalViewClient.onToggleSoftKeyboardRequest();
        } else if ("DRAWER".equals(key)) {
            DrawerLayout drawerLayout = mTermuxTerminalViewClient.getActivity().getDrawer();
            if (drawerLayout.isDrawerOpen(Gravity.LEFT))
                drawerLayout.closeDrawer(Gravity.LEFT);
            else
                drawerLayout.openDrawer(Gravity.LEFT);
        } else if ("COPY".equals(key)) {
            TerminalView terminalView = mTermuxTerminalViewClient.getActivity().getTerminalView();
            String selectedText = terminalView == null ? null : terminalView.getSelectedText();
            if (selectedText == null || selectedText.isEmpty()) {
                Logger.showToast(mActivity,
                    mActivity.getString(com.termux.R.string.plugins_clipboard_copy_selection_required),
                    true);
            } else {
                TerminalSession session = terminalView.getCurrentSession();
                if (session != null) {
                    session.onCopyTextToClipboard(selectedText);
                } else {
                    Logger.showToast(mActivity,
                        mActivity.getString(com.termux.R.string.plugins_clipboard_no_session),
                        true);
                }
                terminalView.stopTextSelectionMode();
            }
        } else if ("PASTE".equals(key)) {
            if(mTermuxTerminalSessionActivityClient != null)
                mTermuxTerminalSessionActivityClient.onPasteTextFromClipboard(null);
        }  else if ("SCROLL".equals(key)) {
            TerminalView terminalView = mTermuxTerminalViewClient.getActivity().getTerminalView();
            if (terminalView != null && terminalView.mEmulator != null)
                terminalView.mEmulator.toggleAutoScrollDisabled();
        } else {
            super.onTerminalExtraKeyButtonClick(view, key, ctrlDown, altDown, shiftDown, fnDown);
        }
    }

}
