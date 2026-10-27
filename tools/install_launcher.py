#!/usr/bin/env python3
"""Parked HU update: retire the old window companion before replacing the APK."""
import argparse
import subprocess
import time
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("serial", help="Explicit head-unit ADB serial")
parser.add_argument("apk", type=Path)
args = parser.parse_args()
if not args.apk.is_file():
    parser.error("APK does not exist")
adb = ["adb", "-s", args.serial]

def run(*command):
    return subprocess.check_output(adb + list(command), text=True).strip()

if run("get-state") != "device":
    raise SystemExit("Head unit is not connected")
if "mState=ON" not in run("shell", "dumpsys", "display"):
    raise SystemExit("Parked ACC/Run with the display on is required")
# Stop the client, then let its native task lease expire before stopping the server.
run("shell", "am", "force-stop", "com.ramphoenix.launcher")
time.sleep(2.2)
pids = subprocess.run(adb + ["shell", "pidof", "phoenix-window-bridge"],
                      capture_output=True, text=True).stdout.split()
for pid in pids:
    if not pid.isdecimal():
        raise SystemExit("Unexpected companion process ID")
    status = run("shell", "cat", f"/proc/{pid}/status")
    command = subprocess.check_output(adb + ["shell", "cat", f"/proc/{pid}/cmdline"])
    uid = next((line.split()[1] for line in status.splitlines() if line.startswith("Uid:")), "")
    if uid != "2000" or command.split(b"\0")[0] != b"phoenix-window-bridge":
        raise SystemExit("Companion identity changed; not stopping it")
    run("shell", "kill", "-TERM", pid)
print(run("install", "-r", str(args.apk.resolve())))
run("shell", "appops", "set", "com.ramphoenix.launcher", "SYSTEM_ALERT_WINDOW", "allow")
run("shell", "settings", "put", "system", "show_navigationbar", "0")
run("shell", "am", "broadcast", "-a", "com.fyt.action.hide_statusbar", "-p",
    "com.android.systemui", "--ez", "visibe", "false")
print(run("shell", "am", "start", "-n", "com.ramphoenix.launcher/.LauncherActivity"))
print("If requested, finish Enable split app windows on the parked head unit.")
