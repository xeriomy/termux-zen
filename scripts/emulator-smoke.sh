#!/usr/bin/env bash
# Emulator smoke test for the declarative plugin build.
# Coverage: fresh install, first launch + bootstrap, one terminal command with zero
# plugins, Settings -> Plugins, one bundled plugin install, and restart persistence.
# Theme/extra-keys application, uninstall safety, search, accessibility, and
# invalid-state recovery remain manual checks in docs/plugins/TESTING.md section 6.2.
set -euo pipefail

APK_DIR=${1:?usage: emulator-smoke.sh <apk-dir> [output-dir]}
OUT=${2:-"$PWD/emulator-smoke"}
PKG=com.termux
TERMUX_ACTIVITY="$PKG/.app.TermuxActivity"
SETTINGS_ACTIVITY="$PKG/.app.activities.SettingsActivity"

mkdir -p "$OUT"

say() { printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*" | tee -a "$OUT/steps.log"; }
die() { say "FAIL: $*"; exit 1; }
screenshot() { adb exec-out screencap -p > "$OUT/$1.png"; }

ui_dump() {
    adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 || return 1
    adb shell cat /sdcard/window.xml > "$OUT/window.xml"
}

ui_coords() {
    python3 - "$OUT/window.xml" "$1" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

try:
    root = ET.parse(sys.argv[1]).getroot()
except Exception:
    raise SystemExit(0)
for node in root.iter("node"):
    if node.get("text") == sys.argv[2] or node.get("content-desc") == sys.argv[2]:
        values = [int(value) for value in re.findall(r"-?\d+", node.get("bounds", ""))]
        if len(values) == 4:
            print((values[0] + values[2]) // 2, (values[1] + values[3]) // 2)
            break
PY
}

# Checks whether a node with the given text or content description is present.
# TabLayout renders its titles uppercased in "text" and keeps the original in
# "content-desc", so both are checked and the comparison ignores case.
ui_has() {
    python3 - "$OUT/window.xml" "$1" <<'PY'
import sys
import xml.etree.ElementTree as ET

wanted = sys.argv[2].lower()
try:
    root = ET.parse(sys.argv[1]).getroot()
except Exception:
    raise SystemExit(1)
for node in root.iter("node"):
    for value in (node.get("text"), node.get("content-desc")):
        if value and value.lower() == wanted:
            raise SystemExit(0)
raise SystemExit(1)
PY
}

ui_tap_text() {
    local wanted=$1 attempts=${2:-1} coords=""
    for _ in $(seq 1 "$attempts"); do
        ui_dump || true
        coords=$(ui_coords "$wanted")
        [ -n "$coords" ] && break
        adb shell input swipe 540 1600 540 600 300 || true
        sleep 1
    done
    [ -n "$coords" ] || die "UI text not found: $wanted"
    say "tap '$wanted' at $coords"
    read -r x y <<< "$coords"
    adb shell input tap "$x" "$y"
    sleep 1
}

# A sleeping screen or an app-not-responding dialog swallows synthetic input,
# so both are cleared before the checks that type or tap.
wake_device() {
    adb shell svc power stayon true >/dev/null 2>&1 || true
    adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
    adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
}

dismiss_anr() {
    ui_dump || return 0
    grep -q "isn't responding" "$OUT/window.xml" || return 0
    local coords
    coords=$(ui_coords "Wait")
    [ -n "$coords" ] || return 0
    say "dismissing the app-not-responding dialog"
    read -r x y <<< "$coords"
    adb shell input tap "$x" "$y"
    sleep 2
}

# Keep evidence when a check fails so a red CI run is diagnosable.
capture_failure() {
    local status=$?
    if [ "$status" -ne 0 ]; then
        say "capturing failure diagnostics"
        timeout 30 adb logcat -d > "$OUT/logcat.txt" 2>/dev/null || true
        timeout 30 adb exec-out screencap -p > "$OUT/failure.png" 2>/dev/null || true
        ui_dump || true
    fi
    exit "$status"
}
trap capture_failure EXIT

APK=$(find "$APK_DIR" -maxdepth 1 -name '*_x86_64.apk' -print -quit)
[ -n "$APK" ] || APK=$(find "$APK_DIR" -maxdepth 1 -name '*_universal.apk' -print -quit)
[ -n "$APK" ] || die "no x86_64 or universal APK in $APK_DIR"
say "APK: $APK"

adb wait-for-device
wake_device
say "device API $(adb shell getprop ro.build.version.sdk | tr -d '\r'), $(adb shell getprop ro.product.cpu.abi | tr -d '\r')"

say "fresh install"
adb uninstall "$PKG" >/dev/null 2>&1 || true
adb install -r -g "$APK" | tee "$OUT/install.txt"
adb shell pm path "$PKG" | tee "$OUT/package-path.txt"

adb logcat -c || true
say "first launch"
adb shell am start -W -n "$TERMUX_ACTIVITY" | tee "$OUT/launch.txt"

wait_for_bootstrap() {
    local deadline=$((SECONDS + 720)) next_report=$((SECONDS + 60)) ticks=0
    while [ "$SECONDS" -lt "$deadline" ]; do
        if adb shell run-as "$PKG" test -x files/usr/bin/login 2>/dev/null; then
            return 0
        fi
        ticks=$((ticks + 1))
        # A recreated activity can start a second install that corrupts the staging
        # tree; detect its error dialog instead of waiting out the deadline.
        if [ "$ticks" -ge 6 ]; then
            ticks=0
            if ui_dump && grep -q 'Unable to install bootstrap' "$OUT/window.xml"; then
                say "bootstrap error dialog detected"
                return 1
            fi
        fi
        if [ "$SECONDS" -ge "$next_report" ]; then
            say "bootstrap still installing ($((SECONDS - bootstrap_start))s)"
            next_report=$((SECONDS + 60))
        fi
        sleep 2
    done
    return 1
}

bootstrap_start=$SECONDS
bootstrap_ok=0
if wait_for_bootstrap; then
    bootstrap_ok=1
else
    say "wiping the prefix and retrying bootstrap once"
    adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
    adb shell run-as "$PKG" rm -rf files/usr files/usr-staging >/dev/null 2>&1 || true
    adb shell am start -W -n "$TERMUX_ACTIVITY" > "$OUT/launch-retry.txt" 2>&1 || true
    bootstrap_start=$SECONDS
    if wait_for_bootstrap; then bootstrap_ok=1; fi
fi
[ "$bootstrap_ok" = 1 ] || die "bootstrap did not become executable"
say "bootstrap installed"

focused=0
for _ in $(seq 1 30); do
    if adb shell dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | grep -q "$PKG"; then
        focused=1
        break
    fi
    sleep 1
done
[ "$focused" = 1 ] || die "Termux activity did not gain focus"
sleep 10
screenshot first-launch

[ -n "$(adb shell pidof "$PKG" | tr -d '\r')" ] || die "app process is not running"

say "terminal command with zero plugins"
terminal_ok=0
for _ in 1 2 3; do
    wake_device
    dismiss_anr
    adb shell input tap 540 1000
    sleep 2
    adb shell input text 'touch%s.smoke-terminal-ok'
    adb shell input keyevent 66
    for _ in $(seq 1 15); do
        if adb shell run-as "$PKG" test -f files/home/.smoke-terminal-ok 2>/dev/null; then
            terminal_ok=1
            break
        fi
        sleep 1
    done
    [ "$terminal_ok" = 1 ] && break
    say "terminal command did not run yet, retrying"
done
[ "$terminal_ok" = 1 ] || die "terminal command did not run"
say "terminal command ran"

say "Settings -> Plugins"
adb shell am start -W -n "$SETTINGS_ACTIVITY" | tee "$OUT/settings-launch.txt"
sleep 2
wake_device
dismiss_anr
ui_tap_text "Plugins" 3
sleep 2
ui_dump || die "could not dump the plugin manager UI"
ui_has "Installed" || die "plugin manager did not open"
ui_has "No plugins are installed." || die "fresh install already has plugins"
say "plugin manager opened with zero installed plugins"

say "check repository state"
ui_tap_text "Repository" 3
sleep 1
ui_dump || die "could not dump repository state"
ui_has "No plugin repository is configured in this build." \
    || die "remote catalog is unexpectedly configured"
say "remote catalog is disabled in this build"

say "open bundled plugins"
ui_tap_text "Bundled" 3
sleep 1
ui_dump || die "could not dump bundled plugin list"

# The Material cards are taller than the emulator viewport, so uiautomator only
# reports the laid-out children. Scroll through the list and assert each card as
# it becomes visible instead of expecting every row in one dump. The bundled
# assets are sorted by file name, so the cards appear in this order.
assert_bundled_card() {
    local wanted=$1
    for _ in $(seq 1 8); do
        ui_dump || true
        if ui_has "$wanted"; then
            say "found bundled plugin '$wanted'"
            return 0
        fi
        adb shell input swipe 540 1500 540 700 300 || true
        sleep 1
    done
    die "$wanted is missing from bundled plugins"
}

# Start at the top of the list.
for _ in $(seq 1 4); do
    adb shell input swipe 540 700 540 1500 300 || true
    sleep 1
done
assert_bundled_card "Coding keys"
assert_bundled_card "Paper Light"
assert_bundled_card "Solarized Dark"
screenshot bundled-plugins

say "install Coding keys"
# Return to the top of the list, then scroll just enough to reveal the first
# card's action row. The button's accessibility label includes the plugin name.
for _ in $(seq 1 6); do
    adb shell input swipe 540 700 540 1500 300 || true
    sleep 1
done
install_coords=""
for _ in $(seq 1 8); do
    ui_dump || true
    install_coords=$(ui_coords "Coding keys Install")
    [ -n "$install_coords" ] && break
    adb shell input swipe 540 1500 540 700 300 || true
    sleep 1
done
[ -n "$install_coords" ] || die "could not find the Coding keys install button"
say "tap 'Coding keys Install' at $install_coords"
read -r ix iy <<< "$install_coords"
adb shell input tap "$ix" "$iy"
sleep 1
ui_dump || die "could not dump install confirmation"
ui_has "Install Coding keys?" || die "install confirmation did not appear"
ui_tap_text "Install" 2
install_ok=0
for _ in $(seq 1 20); do
    adb shell run-as "$PKG" cat files/plugins/registry.json > "$OUT/registry-after-install.json" 2>/dev/null || true
    if grep -q 'com.termux.bundled.coding-keys' "$OUT/registry-after-install.json"; then
        install_ok=1
        break
    fi
    sleep 1
done
[ "$install_ok" = 1 ] || die "bundled plugin did not install"
screenshot bundled-installed
say "bundled plugin installed"

say "restart persistence"
adb shell am force-stop "$PKG"
adb shell am start -W -n "$TERMUX_ACTIVITY" >/dev/null
sleep 8
adb shell am start -W -n "$SETTINGS_ACTIVITY" >/dev/null
sleep 2
ui_tap_text "Plugins" 3
sleep 2
ui_dump || die "could not dump the plugin manager UI after restart"
ui_has "Coding keys" || die "installed plugin was not persisted after restart"
screenshot after-restart
say "plugin registration persisted across restart"

adb logcat -d -b crash > "$OUT/crash.log" 2>/dev/null || true
if grep -A 25 'FATAL EXCEPTION' "$OUT/crash.log" | grep -q "Process: $PKG"; then
    die "app crashed; see crash.log"
fi
adb logcat -d > "$OUT/logcat.txt" 2>/dev/null || true
screenshot final

say "PASS: install, first launch, terminal, Settings -> Plugins, bundled install, restart persistence"
