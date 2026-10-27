#!/usr/bin/env bash
# FYT 7870 HU: use the same persistent setting as the factory climate bar's Hide button.
# Supply the HU explicitly; Crimson can have several ADB devices connected.
set -euo pipefail
serial=${1:?Usage: setup_launcher_ui.sh HEAD_UNIT_ADB_SERIAL}
adb -s "$serial" shell settings put system show_navigationbar 0
adb -s "$serial" shell appops set com.ramphoenix.launcher SYSTEM_ALERT_WINDOW allow
adb -s "$serial" shell am broadcast -a com.fyt.action.hide_statusbar \
    -p com.android.systemui --ez visibe false
