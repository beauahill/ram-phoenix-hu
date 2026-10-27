#!/usr/bin/env python3
"""Pull the installed WirelessOne APK set and basic capabilities. No app launch/BLE writes."""
import argparse
import hashlib
import json
import pathlib
import subprocess

PACKAGE = "com.airlift.wirelessone"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("serial", help="Explicit HU ADB serial, e.g. 192.168.0.225:5555")
    parser.add_argument("output", type=pathlib.Path, help="New evidence directory")
    args = parser.parse_args()
    if args.output.exists():
        parser.error("Choose a new evidence directory; existing files are not overwritten")

    def adb(*parts):
        result = subprocess.run(["adb", "-s", args.serial, *parts], check=True,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=90)
        return result.stdout.decode("utf-8", errors="replace").strip()

    if adb("get-state") != "device":
        parser.error("The specified HU is not connected")
    paths = adb("shell", "pm", "path", PACKAGE).splitlines()
    packages = [line.removeprefix("package:").strip() for line in paths if line.startswith("package:")]
    if not packages or any(not path.startswith("/data/app/") or not path.endswith(".apk") for path in packages):
        parser.error("The HU did not return the expected installed WirelessOne APK paths")
    args.output.mkdir(parents=True)
    evidence = {"serial": args.serial, "package": PACKAGE, "apks": [], "operations": "package reads and APK pull only"}
    features = adb("shell", "pm", "list", "features")
    evidence["bluetooth_features"] = [line for line in features.splitlines() if "bluetooth" in line.lower()]
    evidence["bluetooth_on"] = adb("shell", "settings", "get", "global", "bluetooth_on")
    package = adb("shell", "dumpsys", "package", PACKAGE)
    evidence["version"] = [line.strip() for line in package.splitlines()
                           if "versionCode=" in line or "versionName=" in line]
    for index, remote in enumerate(packages):
        # Names returned by Android are not used as local paths.
        destination = args.output / f"{index:02d}.apk"
        adb("pull", remote, str(destination))
        evidence["apks"].append({"file": destination.name, "remote": remote,
                                 "sha256": hashlib.sha256(destination.read_bytes()).hexdigest()})
    (args.output / "capture.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    print(f"Saved {len(packages)} installed APKs and capture.json in {args.output}")
    print("No app was started and no suspension commands were sent.")


if __name__ == "__main__":
    main()
