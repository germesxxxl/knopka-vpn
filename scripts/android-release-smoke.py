#!/usr/bin/env python3
"""Record the real phone app and exercise the disclosures without starting a tunnel."""

import argparse
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET


PACKAGE = "app.butone.vpn.adstest"
VPN_DIALOG_PACKAGE = "com.android.vpndialogs"
MAIN_TITLE = "Нажмите, чтобы подключиться"
DISCLOSURE_TITLE = "Как butone использует VPN"
SCREEN_XML = "/data/local/tmp/butone-window.xml"
VIDEO = "/sdcard/butone-consent-smoke.mp4"


def adb(*args, timeout=20):
    result = subprocess.run(["adb", *args], capture_output=True, timeout=timeout)
    if result.returncode:
        print((result.stdout + result.stderr).decode(errors="replace"), flush=True)
        result.check_returncode()
    return result.stdout


def nodes_on_screen():
    adb("shell", "uiautomator", "dump", SCREEN_XML, timeout=12)
    return list(ET.fromstring(adb("exec-out", "cat", SCREEN_XML)).iter("node"))


def wait_for(predicate, label, timeout=20):
    deadline = time.monotonic() + timeout
    last_nodes = []
    while time.monotonic() < deadline:
        try:
            last_nodes = nodes_on_screen()
            match = next((node for node in last_nodes if predicate(node)), None)
            if match is not None:
                return match
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired, ET.ParseError):
            pass
        time.sleep(0.3)
    labels = [node.get("text") or node.get("content-desc") for node in last_nodes]
    raise AssertionError(f"Missing {label!r}. Visible labels: {list(filter(None, labels))}")


def by_label(label):
    return wait_for(lambda node: label in (node.get("text"), node.get("content-desc")), label)


def tap(node):
    bounds = node.get("bounds", "")
    coordinates = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
    if coordinates is None:
        raise AssertionError(f"No visible bounds: {bounds!r}")
    x1, y1, x2, y2 = map(int, coordinates.groups())
    if x2 <= x1 or y2 <= y1:
        raise AssertionError(f"Empty visible bounds: {bounds!r}")
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def screenshot(destination):
    image = adb("exec-out", "screencap", "-p")
    if not image.startswith(b"\x89PNG\r\n\x1a\n"):
        raise AssertionError("Emulator did not return a PNG screenshot")
    destination.write_bytes(image)


def assert_no_vpn_service():
    services = adb("shell", "dumpsys", "activity", "services", PACKAGE).decode(errors="replace")
    if re.search(r"ServiceRecord\{[^\n]*ButoneVpnService", services):
        adb("shell", "am", "force-stop", PACKAGE)
        raise AssertionError("VPN service started during a permission-only test")


def finish_recording(recording, destination):
    try:
        pid = adb("shell", "pidof", "screenrecord").decode().strip().split()
        if len(pid) == 1 and pid[0].isdigit():
            adb("shell", "kill", "-2", pid[0])
    except subprocess.CalledProcessError:
        # A recording that hits its own time limit will already have exited.
        pass
    _, stderr = recording.communicate(timeout=95)
    if recording.returncode != 0:
        print(stderr.decode(errors="replace"))
    adb("pull", VIDEO, str(destination))
    if destination.stat().st_size < 4096:
        raise AssertionError("Emulator screen recording is empty")


def run_smoke(apk, output):
    serial = adb("get-serialno").decode().strip()
    sdk = adb("shell", "getprop", "ro.build.version.sdk").decode().strip()
    if not serial.startswith("emulator-") or sdk != "36":
        raise AssertionError(f"Expected Android API 36 emulator, got SDK {sdk} on {serial}")

    adb("install", "-r", str(apk), timeout=90)
    adb("shell", "am", "start", "-W", "-n",
        f"{PACKAGE}/app.butone.vpn.PhoneLauncher", timeout=30)
    by_label(MAIN_TITLE)
    by_label("Конфиденциальность")
    screenshot(output / "01-main.png")
    assert_no_vpn_service()

    recording = subprocess.Popen(
        ["adb", "shell", "screenrecord", "--time-limit", "90", "--bit-rate", "4000000", VIDEO],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.PIPE,
    )
    try:
        tap(by_label("Включить VPN"))
        by_label(DISCLOSURE_TITLE)
        by_label("Разрешить VPN")
        screenshot(output / "02-vpn-disclosure.png")
        tap(by_label("Не сейчас"))
        by_label(MAIN_TITLE)
        assert_no_vpn_service()

        # Refusal must not be persisted as consent. Approve the app's notice,
        # verify Android's real permission prompt, then select Cancel.
        tap(by_label("Включить VPN"))
        by_label(DISCLOSURE_TITLE)
        tap(by_label("Разрешить VPN"))
        wait_for(lambda node: node.get("package") == VPN_DIALOG_PACKAGE, "Android VPN permission dialog")
        assert_no_vpn_service()
        cancel = wait_for(
            lambda node: node.get("package") == VPN_DIALOG_PACKAGE
            and node.get("resource-id") == "android:id/button2",
            "Android VPN Cancel button",
        )
        tap(cancel)
        by_label(MAIN_TITLE)
        assert_no_vpn_service()
    finally:
        finish_recording(recording, output / "vpn-consent-emulator.mp4")

    crashes = adb("logcat", "-d", "-b", "crash").decode(errors="replace")
    if f"Process: {PACKAGE}," in crashes:
        raise AssertionError("Android logged a butone crash")
    (output / "result.txt").write_text(
        "Passed on Android API 36 emulator: launch, VPN disclosure, refusal, repeat prompt, "
        "Android permission prompt and cancellation. VPN service never ran.\n",
        encoding="utf-8",
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    try:
        run_smoke(args.apk, args.output)
    except Exception:
        try:
            screenshot(args.output / "failure-screen.png")
            (args.output / "crashes.txt").write_bytes(adb("logcat", "-d", "-b", "crash"))
        except (subprocess.SubprocessError, AssertionError):
            pass
        raise


if __name__ == "__main__":
    main()
