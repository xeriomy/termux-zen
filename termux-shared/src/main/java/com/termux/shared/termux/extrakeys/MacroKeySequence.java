package com.termux.shared.termux.extrakeys;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure-Java decomposition of an extra-keys macro into an ordered list of key actions.
 *
 * <p>Macros keep the existing grammar: tokens are separated by spaces, and a leading
 * modifier token ({@code CTRL}, {@code ALT}, {@code SHIFT}, {@code FN}) applies to the key
 * that follows it. Nothing here executes a command; the host dispatches each action through
 * the ordinary virtual-key path.</p>
 *
 * <p>A macro whose first token is a modifier or a named key (for example {@code CTRL f d})
 * is a key sequence: spaces are separators only. Any other macro is typed as text, so the
 * spaces between its text tokens are literal ({@code echo hello world} types exactly that).
 * Named keys embedded in a text macro still dispatch as key events, and surrounding spaces
 * are not duplicated around them.</p>
 */
public final class MacroKeySequence {

    public static final String CTRL = "CTRL";
    public static final String ALT = "ALT";
    public static final String SHIFT = "SHIFT";
    public static final String FN = "FN";
    public static final String ENTER = "ENTER";

    private static final Set<String> MODIFIERS = new HashSet<>(Arrays.asList(CTRL, ALT, SHIFT, FN));

    /** One ordered virtual-key action. Text actions carry the modifier state of their run. */
    public static final class KeyAction {
        @NonNull public final String key;
        public final boolean ctrlDown;
        public final boolean altDown;
        public final boolean shiftDown;
        public final boolean fnDown;

        public KeyAction(@NonNull String key, boolean ctrlDown, boolean altDown, boolean shiftDown, boolean fnDown) {
            this.key = key;
            this.ctrlDown = ctrlDown;
            this.altDown = altDown;
            this.shiftDown = shiftDown;
            this.fnDown = fnDown;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof KeyAction)) return false;
            KeyAction that = (KeyAction) other;
            return ctrlDown == that.ctrlDown && altDown == that.altDown
                && shiftDown == that.shiftDown && fnDown == that.fnDown && key.equals(that.key);
        }

        @Override
        public int hashCode() {
            int result = key.hashCode();
            result = 31 * result + (ctrlDown ? 1 : 0);
            result = 31 * result + (altDown ? 1 : 0);
            result = 31 * result + (shiftDown ? 1 : 0);
            result = 31 * result + (fnDown ? 1 : 0);
            return result;
        }

        @Override
        public String toString() {
            return "KeyAction{" + key + ", CTRL=" + ctrlDown + ", ALT=" + altDown
                + ", SHIFT=" + shiftDown + ", FN=" + fnDown + "}";
        }
    }

    private MacroKeySequence() {}

    /** True when a token only changes modifier state and produces no output of its own. */
    public static boolean isModifier(@NonNull String token) {
        return MODIFIERS.contains(token);
    }

    /**
     * Splits [macro] into actions. [namedKeys] is the host's set of key-name tokens
     * (special keys plus app-specific buttons); it decides which tokens dispatch as key
     * events instead of literal text. When [sendOnPress] is true a final ENTER action is
     * appended so the typed command runs immediately.
     */
    @NonNull
    public static List<KeyAction> parse(@NonNull String macro, boolean sendOnPress, @NonNull Set<String> namedKeys) {
        // -1 keeps empty tokens, so author-written repeated spaces are not collapsed.
        String[] tokens = macro.split(" ", -1);
        boolean keyNameMacro = tokens.length > 0 && (isModifier(tokens[0]) || namedKeys.contains(tokens[0]));
        List<KeyAction> actions = new ArrayList<>();

        boolean ctrlDown = false, altDown = false, shiftDown = false, fnDown = false;
        if (keyNameMacro) {
            for (String token : tokens) {
                if (token.isEmpty()) continue;
                if (isModifier(token)) {
                    if (CTRL.equals(token)) ctrlDown = true;
                    else if (ALT.equals(token)) altDown = true;
                    else if (SHIFT.equals(token)) shiftDown = true;
                    else fnDown = true;
                    continue;
                }
                actions.add(new KeyAction(token, ctrlDown, altDown, shiftDown, fnDown));
                ctrlDown = altDown = shiftDown = fnDown = false;
            }
        } else {
            StringBuilder text = new StringBuilder();
            int pendingSpaces = 0;
            for (String token : tokens) {
                if (token.isEmpty()) {
                    pendingSpaces++;
                    continue;
                }
                if (isModifier(token)) {
                    flushText(actions, text, ctrlDown, altDown, shiftDown, fnDown);
                    if (CTRL.equals(token)) ctrlDown = true;
                    else if (ALT.equals(token)) altDown = true;
                    else if (SHIFT.equals(token)) shiftDown = true;
                    else fnDown = true;
                    pendingSpaces = 0;
                    continue;
                }
                if (namedKeys.contains(token)) {
                    flushText(actions, text, ctrlDown, altDown, shiftDown, fnDown);
                    actions.add(new KeyAction(token, ctrlDown, altDown, shiftDown, fnDown));
                    ctrlDown = altDown = shiftDown = fnDown = false;
                    pendingSpaces = 0;
                    continue;
                }
                if (text.length() > 0) {
                    // N authored spaces produce N-1 empty tokens, so always add the
                    // separating space itself plus any extra ones the author wrote.
                    for (int i = 0; i <= pendingSpaces; i++) text.append(' ');
                }
                text.append(token);
                pendingSpaces = 0;
            }
            flushText(actions, text, ctrlDown, altDown, shiftDown, fnDown);
        }

        if (sendOnPress) actions.add(new KeyAction(ENTER, false, false, false, false));
        return actions;
    }

    private static void flushText(List<KeyAction> actions, StringBuilder text,
                                  boolean ctrlDown, boolean altDown, boolean shiftDown, boolean fnDown) {
        if (text.length() == 0) return;
        actions.add(new KeyAction(text.toString(), ctrlDown, altDown, shiftDown, fnDown));
        text.setLength(0);
    }
}
