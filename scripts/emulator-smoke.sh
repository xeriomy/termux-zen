#!/usr/bin/env bash
# Emulator smoke test for the declarative plugin build.
# Coverage: fresh install, first launch + bootstrap, one terminal command with zero
# plugins, Settings -> Plugins, bundled sample install, and restart persistence.
# Theme/extra-keys/preset application, uninstall safety, and invalid-state recovery
# remain manual checks in docs/plugins/TESTING.md section 6.2.
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

ui_tap_text() {
    local wanted=$1 attempts=${2:-1} coords=""
    for _ in $(seq 1 "$attempts"); do
        ui_dump || true
        coords=$(python3 - "$OUT/window.xml" "$wanted" <<'PY'
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
)
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
say "device API $(adb shell getprop ro.build.version.sdk | tr -d '\r'), $(adb shell getprop ro.product.cpu.abi | tr -d '\r')"

say "fresh install"
adb uninstall "$PKG" >/dev/null 2>&1 || true
adb install -r -g "$APK" | tee "$OUT/install.txt"
adb shell pm path "$PKG" | tee "$OUT/package-path.txt"

adb logcat -c || true
say "first launch"
adb shell am start -W -n "$TERMUX_ACTIVITY" | tee "$OUT/launch.txt"

bootstrap_ok=0
bootstrap_start=$SECONDS
bootstrap_deadline=$((SECONDS + 900))
bootstrap_next_report=$((SECONDS + 60))
while [ "$SECONDS" -lt "$bootstrap_deadline" ]; do
    if adb shell run-as "$PKG" test -x files/usr/bin/login 2>/dev/null; then
        bootstrap_ok=1
        break
    fi
    if [ "$SECONDS" -ge "$bootstrap_next_report" ]; then
        say "bootstrap still installing ($((SECONDS - bootstrap_start))s)"
        bootstrap_next_report=$((SECONDS + 60))
    fi
    sleep 2
done
[ "$bootstrap_ok" = 1 ] || die "bootstrap did not become executable within 900s"
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
adb shell input tap 540 1000
sleep 1
adb shell input text 'touch%s.smoke-terminal-ok'
adb shell input keyevent 66
terminal_ok=0
for _ in $(seq 1 20); do
    if adb shell run-as "$PKG" test -f files/home/.smoke-terminal-ok 2>/dev/null; then
        terminal_ok=1
        break
    fi
    sleep 1
done
[ "$terminal_ok" = 1 ] || die "terminal command did not run"
say "terminal command ran"

say "Settings -> Plugins"
adb shell am start -W -n "$SETTINGS_ACTIVITY" | tee "$OUT/settings-launch.txt"
sleep 2
ui_tap_text "Plugins" 3
sleep 2
ui_dump || die "could not dump the plugin manager UI"
grep -q 'text="Install bundled samples"' "$OUT/window.xml" \
    || die "plugin manager did not open"
grep -q 'text="No plugins installed."' "$OUT/window.xml" \
    || die "fresh install already has plugins"
grep -q 'text="No plugin repository is configured in this build."' "$OUT/window.xml" \
    || die "remote catalog is unexpectedly configured"
say "plugin manager opened with zero installed plugins and no remote catalog"

say "install bundled samples"
ui_tap_text "Install bundled samples"
samples_ok=0
for _ in $(seq 1 20); do
    ui_dump || true
    if grep -q 'text="Midnight 1.0.0"' "$OUT/window.xml" \
        && grep -q 'text="Developer keys 1.0.0"' "$OUT/window.xml" \
        && grep -q 'text="Home session 1.0.0"' "$OUT/window.xml"; then
        samples_ok=1
        break
    fi
    sleep 1
done
[ "$samples_ok" = 1 ] || die "bundled samples did not appear"
disabled=$(grep -o 'Disabled' "$OUT/window.xml" || true)
disabled=$(printf '%s' "$disabled" | wc -l)
[ "$disabled" -ge 3 ] || die "expected three disabled samples, found $disabled"
screenshot bundled-samples
adb shell run-as "$PKG" cat files/plugins/registry.json > "$OUT/registry-after-install.json" 2>/dev/null || true
say "bundled samples installed and disabled"

say "restart persistence"
adb shell am force-stop "$PKG"
adb shell am start -W -n "$TERMUX_ACTIVITY" >/dev/null
sleep 8
adb shell am start -W -n "$SETTINGS_ACTIVITY" >/dev/null
sleep 2
ui_tap_text "Plugins" 3
sleep 2
ui_dump || die "could not dump the plugin manager UI after restart"
for name in "Midnight 1.0.0" "Developer keys 1.0.0" "Home session 1.0.0"; do
    grep -q "text=\"$name\"" "$OUT/window.xml" || die "not persisted after restart: $name"
done
screenshot after-restart
say "plugin registrations persisted across restart"

adb logcat -d -b crash > "$OUT/crash.log" 2>/dev/null || true
if grep -A 25 'FATAL EXCEPTION' "$OUT/crash.log" | grep -q "Process: $PKG"; then
    die "app crashed; see crash.log"
fi
adb logcat -d > "$OUT/logcat.txt" 2>/dev/null || true
screenshot final

say "PASS: install, first launch, terminal, Settings -> Plugins, bundled install, restart persistence"
