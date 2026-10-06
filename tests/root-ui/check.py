#!/usr/bin/env python3
"""On an unlocked device with the APK installed: check.py granted|denied.

Set the app's Magisk policy beforehand. For the denied check, set airplay_2=true
via adb with the receiver stopped: the UI must turn it off, not merely disable it.
Uses adb's ANDROID_SERIAL when set. Never changes the root policy or plays audio.
"""
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

assert len(sys.argv) == 2 and sys.argv[1] in ("granted", "denied")
granted = sys.argv[1] == "granted"


def adb(*args):
    return subprocess.check_output(["adb", *args], text=True, timeout=30)


adb("shell", "input", "keyevent", "KEYCODE_HOME")
adb("shell", "am", "start", "-n", "com.hkfuertes.shairport/.MainActivity")
for _ in range(10):
    time.sleep(1)
    adb("shell", "uiautomator", "dump", "/data/local/tmp/shairport-root-ui.xml")
    root = ET.fromstring(adb("shell", "cat", "/data/local/tmp/shairport-root-ui.xml"))
    parents = {child: parent for parent in root.iter() for child in parent}
    row = next((e for e in root.iter("node") if e.get("text") == "AirPlay 2"), None)
    assert row is not None, "Unlock the device and leave the top of Shairport settings visible"
    while row is not None:
        switches = [e for e in row.iter("node") if e.get("class") == "android.widget.Switch"]
        if len(switches) == 1:
            break
        row = parents.get(row)
    assert row is not None, "AirPlay 2 switch not found"
    switch = switches[0]
    if (switch.get("enabled") == "true") == granted and (granted or switch.get("checked") == "false"):
        assert any(e.get("text") == "Multiroom, buffered audio (requires root)." for e in row.iter("node")), "Wrong subtitle"
        print("AirPlay 2 root gating and subtitle: OK (" + sys.argv[1] + ")")
        break
else:
    raise AssertionError("AirPlay 2 was not enabled with root, or off and disabled without root")
