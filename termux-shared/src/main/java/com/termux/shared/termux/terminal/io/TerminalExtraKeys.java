package com.termux.shared.termux.terminal.io;

import android.os.Build;
import android.view.KeyEvent;
import android.view.View;

import androidx.annotation.NonNull;

import com.google.android.material.button.MaterialButton;
import com.termux.shared.termux.extrakeys.ExtraKeyButton;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.termux.extrakeys.MacroKeySequence;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.termux.shared.termux.extrakeys.ExtraKeysConstants.PRIMARY_KEY_CODES_FOR_STRINGS;


public class TerminalExtraKeys implements ExtraKeysView.IExtraKeysView {

    /** Named keys that dispatch as key events, not literal text, inside a macro. */
    private static final Set<String> MACRO_KEY_NAMES = new HashSet<>(PRIMARY_KEY_CODES_FOR_STRINGS.keySet());
    static {
        MACRO_KEY_NAMES.addAll(Arrays.asList("KEYBOARD", "DRAWER", "COPY", "PASTE", "SCROLL"));
    }

    private final TerminalView mTerminalView;

    public TerminalExtraKeys(@NonNull TerminalView terminalView) {
        mTerminalView = terminalView;
    }

    @Override
    public void onExtraKeyButtonClick(View view, ExtraKeyButton buttonInfo, MaterialButton button) {
        if (buttonInfo.isMacro()) {
            List<MacroKeySequence.KeyAction> actions = MacroKeySequence.parse(
                buttonInfo.getKey(), buttonInfo.isSendOnPress(), MACRO_KEY_NAMES);
            for (MacroKeySequence.KeyAction action : actions) {
                onTerminalExtraKeyButtonClick(view, action.key,
                    action.ctrlDown, action.altDown, action.shiftDown, action.fnDown);
            }
        } else {
            onTerminalExtraKeyButtonClick(view, buttonInfo.getKey(), false, false, false, false);
        }
    }

    protected void onTerminalExtraKeyButtonClick(View view, String key, boolean ctrlDown, boolean altDown, boolean shiftDown, boolean fnDown) {
        if (PRIMARY_KEY_CODES_FOR_STRINGS.containsKey(key)) {
            Integer keyCode = PRIMARY_KEY_CODES_FOR_STRINGS.get(key);
            if (keyCode == null) return;
            int metaState = 0;
            if (ctrlDown) metaState |= KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
            if (altDown) metaState |= KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
            if (shiftDown) metaState |= KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
            if (fnDown) metaState |= KeyEvent.META_FUNCTION_ON;

            KeyEvent keyEvent = new KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, metaState);
            mTerminalView.onKeyDown(keyCode, keyEvent);
        } else {
            // not a control char
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                key.codePoints().forEach(codePoint -> {
                    mTerminalView.inputCodePoint(TerminalView.KEY_EVENT_SOURCE_VIRTUAL_KEYBOARD, codePoint, ctrlDown, altDown);
                });
            } else {
                TerminalSession session = mTerminalView.getCurrentSession();
                if (session != null && key.length() > 0)
                    session.write(key);
            }
        }
    }

    @Override
    public boolean performExtraKeyButtonHapticFeedback(View view, ExtraKeyButton buttonInfo, MaterialButton button) {
        return false;
    }

}
