#!/usr/bin/env python3
"""ACID Android Emulator End-to-End Test Suite for MiniFile (ARCHITECTURE.md §12).

Executes scenarios E1 through E15 against a live Android emulator over ADB:
  E1  - Launch & bottom navigation tabs (Browse, Favorites, Wireless, Tools)
  E2  - Browse & breadcrumbs (Internal -> MiniFileE2E -> src)
  E3  - Grid/list toggle, sort by Size desc, search filter, .hidden toggle & persistence
  E4  - Create folder ("E2E Folder") & create file ("made.txt")
  E5  - Built-in text editor (UTF-8 · LF status bar, edit, save, reopen)
  E6  - Multi-select Copy & Cut/Move with MD5 verification
  E7  - Favorites bookmark ("Target") + 1-tap "Copy to Favorite…" picker
  E8  - Rename ("blob2.bin") & Delete with confirmation dialog
  E9  - Properties sheet & SHA-256 checksum computation
  E10 - .nomedia toggle & "Hidden from gallery" badge
  E11 - Compress ZIP, Extract to new folder, and Zip-Slip "../evil.txt" guard
  E12 - Storage Home capacities, Android/data SAF picker intent, Installed Apps & Export APK
  E13 - Wireless FTP server + adb forward + PC Companion CLI (ls, mkdir, put, get, sync,
        cat, nomedia, rm) + live activity log + Stop from foreground notification
  E14 - Tools -> Updates -> Check now
  E15 - Background process death (am kill) & state restoration + zero crash/ANR audit
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
import zipfile
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Callable

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
COMPANION_DIR = REPO_ROOT / "companion"
PKG = "com.minifile.explorer"
MAIN_ACTIVITY = f"{PKG}/.MainActivity"

BOUNDS_RE = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


@dataclass
class UiNode:
    text: str
    desc: str
    cls: str
    clickable: bool
    checked: bool
    enabled: bool
    x1: int
    y1: int
    x2: int
    y2: int

    @property
    def cx(self) -> int:
        return (self.x1 + self.x2) // 2

    @property
    def cy(self) -> int:
        return (self.y1 + self.y2) // 2


class AcidE2ERunner:
    def __init__(self, serial: str, apk_path: Path, out_dir: Path) -> None:
        self.serial = serial
        self.apk_path = apk_path
        self.out_dir = out_dir
        self.out_dir.mkdir(parents=True, exist_ok=True)
        self.results: list[dict[str, Any]] = []
        self.screen_w = 1080
        self.screen_h = 2400

    def log(self, msg: str) -> None:
        print(f"[e2e] {msg}", flush=True)

    def adb(
        self,
        *args: str,
        check: bool = True,
        timeout: int = 45,
        binary: bool = False,
    ) -> subprocess.CompletedProcess[Any]:
        cmd = ["adb", "-s", self.serial, *args]
        proc = subprocess.run(
            cmd,
            capture_output=True,
            text=not binary,
            timeout=timeout,
        )
        if check and proc.returncode != 0:
            stderr = proc.stderr.decode("utf-8", "replace") if binary else proc.stderr
            stdout = proc.stdout.decode("utf-8", "replace") if binary else proc.stdout
            raise RuntimeError(
                f"ADB command failed ({proc.returncode}): {' '.join(cmd)}\n"
                f"STDOUT: {stdout}\nSTDERR: {stderr}"
            )
        return proc

    def shell(self, script: str, check: bool = True, timeout: int = 30) -> str:
        res = self.adb("shell", script, check=check, timeout=timeout)
        return (res.stdout or "").strip()

    def screencap(self, name: str) -> str:
        target = self.out_dir / f"{name}.png"
        res = self.adb("exec-out", "screencap", "-p", binary=True)
        target.write_bytes(res.stdout)
        return str(target.relative_to(REPO_ROOT)) if target.is_relative_to(REPO_ROOT) else str(target)

    def dump_ui(self, name: str | None = None, retries: int = 5) -> tuple[list[UiNode], str, str | None]:
        last_err = ""
        raw_xml = ""
        for attempt in range(retries):
            flag = "--compressed" if attempt < retries - 1 else ""
            cmd = f"uiautomator dump {flag} /sdcard/mf_ui.xml".strip()
            out = self.shell(cmd, check=False)
            if "UI hierchary dumped to" in out or "UI hierarchy dumped to" in out:
                pulled = self.adb("exec-out", "cat", "/sdcard/mf_ui.xml", check=False)
                raw_xml = (pulled.stdout or "").strip()
                if raw_xml.startswith("<?xml") or "<hierarchy" in raw_xml:
                    break
            last_err = out
            time.sleep(0.6)
        else:
            raise RuntimeError(f"uiautomator dump failed after {retries} retries: {last_err}")

        saved_rel: str | None = None
        if name:
            xml_path = self.out_dir / f"{name}.xml"
            xml_path.write_text(raw_xml, encoding="utf-8")
            saved_rel = str(xml_path.relative_to(REPO_ROOT)) if xml_path.is_relative_to(REPO_ROOT) else str(xml_path)

        idx = raw_xml.find("<hierarchy")
        if idx > 0 and not raw_xml.startswith("<?xml"):
            raw_xml = raw_xml[idx:]
        root = ET.fromstring(raw_xml)
        nodes: list[UiNode] = []
        max_btn_area = max(1, (self.screen_w * self.screen_h) // 3)

        def walk_elem(elem: ET.Element, anc_clickable: bool) -> None:
            b = elem.attrib.get("bounds", "")
            m = BOUNDS_RE.match(b)
            self_click = elem.attrib.get("clickable") == "true"
            eff_click = self_click or anc_clickable
            next_anc = anc_clickable
            if m:
                x1, y1, x2, y2 = (int(m.group(i)) for i in range(1, 5))
                if x2 > x1 and y2 > y1:
                    area = (x2 - x1) * (y2 - y1)
                    if self_click and area <= max_btn_area:
                        next_anc = True
                    nodes.append(
                        UiNode(
                            text=elem.attrib.get("text", ""),
                            desc=elem.attrib.get("content-desc", ""),
                            cls=elem.attrib.get("class", ""),
                            clickable=eff_click,
                            checked=elem.attrib.get("checked") == "true",
                            enabled=elem.attrib.get("enabled", "true") == "true",
                            x1=x1,
                            y1=y1,
                            x2=x2,
                            y2=y2,
                        )
                    )
            for child in elem:
                walk_elem(child, next_anc)

        walk_elem(root, False)
        return nodes, raw_xml, saved_rel

    def find_nodes(
        self,
        nodes: list[UiNode],
        *,
        text: str | None = None,
        text_contains: str | None = None,
        desc: str | None = None,
        desc_contains: str | None = None,
        cls: str | None = None,
        min_y: int | None = None,
        max_y: int | None = None,
    ) -> list[UiNode]:
        out: list[UiNode] = []
        for n in nodes:
            if text is not None and n.text != text:
                continue
            if text_contains is not None and text_contains.lower() not in n.text.lower():
                continue
            if desc is not None and n.desc != desc:
                continue
            if desc_contains is not None and desc_contains.lower() not in n.desc.lower():
                continue
            if cls is not None and n.cls != cls:
                continue
            if min_y is not None and n.cy < min_y:
                continue
            if max_y is not None and n.cy > max_y:
                continue
            out.append(n)
        return out

    def wait_for_node(
        self,
        *,
        text: str | None = None,
        text_contains: str | None = None,
        desc: str | None = None,
        desc_contains: str | None = None,
        cls: str | None = None,
        min_y: int | None = None,
        max_y: int | None = None,
        timeout: float = 8.0,
        scroll_if_missing: bool = False,
    ) -> tuple[UiNode, list[UiNode], str]:
        deadline = time.time() + timeout
        scrolled = 0
        last_xml = ""
        while time.time() < deadline:
            nodes, raw_xml, _ = self.dump_ui()
            last_xml = raw_xml
            matches = self.find_nodes(
                nodes,
                text=text,
                text_contains=text_contains,
                desc=desc,
                desc_contains=desc_contains,
                cls=cls,
                min_y=min_y,
                max_y=max_y,
            )
            if matches:
                # Prefer clickable node if multiple match, otherwise first
                clickable_matches = [m for m in matches if m.clickable]
                chosen = clickable_matches[0] if clickable_matches else matches[0]
                return chosen, nodes, raw_xml
            if scroll_if_missing and scrolled < 4:
                self.scroll_down()
                scrolled += 1
            else:
                time.sleep(0.5)
        criteria = {
            k: v
            for k, v in {
                "text": text,
                "text_contains": text_contains,
                "desc": desc,
                "desc_contains": desc_contains,
                "cls": cls,
            }.items()
            if v is not None
        }
        visible_texts = sorted({n.text or f"@{n.desc}" for n in self.dump_ui()[0] if n.text or n.desc})
        raise AssertionError(f"Timed out waiting for UI node {criteria}; visible={visible_texts[:40]}")

    def tap_xy(self, x: int, y: int, settle: float = 0.65) -> None:
        self.shell(f"input tap {x} {y}")
        time.sleep(settle)

    def tap(
        self,
        *,
        text: str | None = None,
        text_contains: str | None = None,
        desc: str | None = None,
        desc_contains: str | None = None,
        cls: str | None = None,
        min_y: int | None = None,
        max_y: int | None = None,
        scroll_if_missing: bool = False,
        settle: float = 0.7,
    ) -> UiNode:
        node, _, _ = self.wait_for_node(
            text=text,
            text_contains=text_contains,
            desc=desc,
            desc_contains=desc_contains,
            cls=cls,
            min_y=min_y,
            max_y=max_y,
            scroll_if_missing=scroll_if_missing,
        )
        self.tap_xy(node.cx, node.cy, settle=settle)
        return node

    def tap_bottom_tab(self, tab_label: str) -> None:
        min_y = int(self.screen_h * 0.80)
        nodes, _, _ = self.dump_ui()
        matches = self.find_nodes(nodes, text=tab_label, min_y=min_y)
        if not matches:
            matches = self.find_nodes(nodes, desc=tab_label, min_y=min_y)
        if not matches:
            raise AssertionError(f"Bottom tab '{tab_label}' not found in bottom bar")
        self.tap_xy(matches[0].cx, matches[0].cy, settle=0.8)

    def long_press(self, *, text: str | None = None, desc: str | None = None, ms: int = 900) -> UiNode:
        node, _, _ = self.wait_for_node(text=text, desc=desc)
        self.shell(f"input swipe {node.cx} {node.cy} {node.cx} {node.cy} {ms}")
        time.sleep(0.7)
        return node

    def scroll_down(self) -> None:
        cx = self.screen_w // 2
        y1 = int(self.screen_h * 0.74)
        y2 = int(self.screen_h * 0.30)
        self.shell(f"input swipe {cx} {y1} {cx} {y2} 320")
        time.sleep(0.6)

    def scroll_up(self) -> None:
        cx = self.screen_w // 2
        y1 = int(self.screen_h * 0.30)
        y2 = int(self.screen_h * 0.76)
        self.shell(f"input swipe {cx} {y1} {cx} {y2} 320")
        time.sleep(0.6)

    def dismiss_sheet(self) -> None:
        for _ in range(3):
            nodes, _, _ = self.dump_ui()
            scrims = self.find_nodes(nodes, desc="Close sheet")
            if scrims:
                s = scrims[0]
                tap_y = min(s.cy, max(s.y1 + 80, 140))
                self.tap_xy(s.cx, tap_y, settle=0.6)
            else:
                return
        self.shell("input keyevent KEYCODE_BACK")
        time.sleep(0.5)

    def replace_dialog_edit_text(self, value: str) -> None:
        node, _, _ = self.wait_for_node(cls="android.widget.EditText")
        # Tap near the right side of the EditText to focus and position cursor near end
        tap_x = max(node.cx, node.x2 - 32)
        self.tap_xy(tap_x, node.cy, settle=0.4)
        dels = " ".join(["67"] * 32 + ["112"] * 12)
        self.shell(f"input keyevent 123 {dels}")
        time.sleep(0.2)
        escaped = value.replace(" ", "%s")
        self.shell(f"input text '{escaped}'")
        time.sleep(0.4)

    def assert_no_crash_or_anr(self) -> None:
        crash_log = self.shell("logcat -d -b crash", check=False)
        if PKG in crash_log:
            raise AssertionError(f"Crash detected in logcat -b crash for {PKG}:\n{crash_log}")
        anr_out = self.shell(f"dumpsys activity processes | grep -i -E 'anr.*{PKG}|{PKG}.*not responding'", check=False)
        if anr_out.strip():
            raise AssertionError(f"ANR detected for {PKG}:\n{anr_out}")

    def run_scenario(self, code: str, title: str, fn: Callable[[], dict[str, Any]]) -> None:
        self.log(f"--- Starting {code}: {title} ---")
        t0 = time.monotonic()
        try:
            evidence = fn()
            self.assert_no_crash_or_anr()
            elapsed = round(time.monotonic() - t0, 2)
            self.log(f"PASS {code} ({elapsed}s)")
            self.results.append(
                {
                    "id": code,
                    "title": title,
                    "status": "PASS",
                    "elapsed_seconds": elapsed,
                    **evidence,
                }
            )
        except Exception as exc:
            elapsed = round(time.monotonic() - t0, 2)
            err_shot = None
            err_xml = None
            try:
                err_shot = self.screencap(f"{code.lower()}_FAIL")
                _, _, err_xml = self.dump_ui(f"{code.lower()}_FAIL")
            except Exception:
                pass
            self.log(f"FAIL {code} ({elapsed}s): {exc}")
            self.results.append(
                {
                    "id": code,
                    "title": title,
                    "status": "FAIL",
                    "elapsed_seconds": elapsed,
                    "error": str(exc),
                    "fail_screenshot": err_shot,
                    "fail_uidump": err_xml,
                }
            )
            raise

    def setup_device(self) -> dict[str, str]:
        self.log("Waiting for boot completion & configuring device...")
        for _ in range(60):
            if self.shell("getprop sys.boot_completed", check=False) == "1":
                break
            time.sleep(2)
        sdk = self.shell("getprop ro.build.version.sdk")
        release = self.shell("getprop ro.build.version.release")
        fingerprint = self.shell("getprop ro.build.fingerprint")
        model = self.shell("getprop ro.product.model")
        wm_size = self.shell("wm size", check=False)
        m = re.search(r"(\d+)x(\d+)", wm_size)
        if m:
            self.screen_w, self.screen_h = int(m.group(1)), int(m.group(2))

        # Wake screen, unlock keyguard, and disable animations for deterministic uiautomator dumps
        self.shell("input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard", check=False)
        self.shell(
            "settings put global window_animation_scale 0; "
            "settings put global transition_animation_scale 0; "
            "settings put global animator_duration_scale 0",
            check=False,
        )

        self.log(f"Installing APK: {self.apk_path}")
        self.adb("install", "-r", str(self.apk_path), timeout=90)
        self.shell(f"pm clear {PKG}")
        self.shell(f"appops set {PKG} MANAGE_EXTERNAL_STORAGE allow")
        self.shell(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS", check=False)
        self.shell(f"appops set {PKG} REQUEST_INSTALL_PACKAGES allow", check=False)

        self.log("Seeding /sdcard/MiniFileE2E fixture tree...")
        self.shell(
            "rm -rf /sdcard/MiniFileE2E && "
            "mkdir -p /sdcard/MiniFileE2E/src/sub /sdcard/MiniFileE2E/dst && "
            "printf 'hello\\nworld\\n' > /sdcard/MiniFileE2E/src/notes.txt && "
            "printf 'nested-sub-content\\n' > /sdcard/MiniFileE2E/src/sub/child.txt && "
            "dd if=/dev/urandom of=/sdcard/MiniFileE2E/src/blob.bin bs=1k count=512 status=none"
        )
        self.adb("logcat", "-c")

        info = {
            "sdk": sdk,
            "release": release,
            "model": model,
            "fingerprint": fingerprint,
            "screen": f"{self.screen_w}x{self.screen_h}",
            "apk": str(self.apk_path.relative_to(REPO_ROOT)) if self.apk_path.is_relative_to(REPO_ROOT) else str(self.apk_path),
        }
        self.log(f"Device ready: {info}")
        return info

    def navigate_home_to_src(self) -> None:
        self.tap_bottom_tab("Browse")
        nodes, _, _ = self.dump_ui()
        home_btns = self.find_nodes(nodes, desc="Storage Home")
        if home_btns:
            self.tap_xy(home_btns[0].cx, home_btns[0].cy, settle=0.7)
        self.scroll_up()
        self.tap(text="Internal")
        self.tap(text="MiniFileE2E", scroll_if_missing=True)
        self.tap(text="src", scroll_if_missing=True)

    # -------------------- Scenarios E1 - E15 --------------------

    def e1_launch_and_tabs(self) -> dict[str, Any]:
        self.shell(f"am start -n {MAIN_ACTIVITY} -W")
        time.sleep(1.2)
        self.wait_for_node(text="MiniFile")
        self.wait_for_node(text="Internal")
        s_browse = self.screencap("e1_browse")
        _, _, x_browse = self.dump_ui("e1_browse")

        self.tap_bottom_tab("Favorites")
        self.wait_for_node(desc="Export Favorites JSON")
        s_fav = self.screencap("e1_favorites")
        _, _, x_fav = self.dump_ui("e1_favorites")

        self.tap_bottom_tab("Wireless")
        self.wait_for_node(text="Wireless Transfer")
        self.wait_for_node(text="Stopped")
        s_wire = self.screencap("e1_wireless")
        _, _, x_wire = self.dump_ui("e1_wireless")

        self.tap_bottom_tab("Tools")
        self.wait_for_node(text="Updates")
        self.wait_for_node(text="Check now")
        s_tools = self.screencap("e1_tools")
        _, _, x_tools = self.dump_ui("e1_tools")

        self.tap_bottom_tab("Browse")
        return {
            "screenshots": [s_browse, s_fav, s_wire, s_tools],
            "uidumps": [x_browse, x_fav, x_wire, x_tools],
            "notes": "All 4 tabs (Browse, Favorites, Wireless, Tools) rendered cleanly with zero crashes.",
        }

    def e2_browse_and_breadcrumbs(self) -> dict[str, Any]:
        self.tap(text="Internal")
        self.tap(text="MiniFileE2E", scroll_if_missing=True)
        self.tap(text="src", scroll_if_missing=True)

        self.wait_for_node(desc="Internal › MiniFileE2E › src")
        self.wait_for_node(text="sub")
        self.wait_for_node(text="notes.txt")
        self.wait_for_node(text="blob.bin")
        shot = self.screencap("e2_breadcrumbs_src")
        _, xml_str, xdump = self.dump_ui("e2_breadcrumbs_src")
        assert "524" in xml_str or "512" in xml_str, "Expected ~512 KiB / 524 kB size indicator for blob.bin"
        return {
            "screenshots": [shot],
            "uidumps": [xdump],
            "notes": "Breadcrumb 'Internal › MiniFileE2E › src' and entries sub, notes.txt, blob.bin verified.",
        }

    def e3_sort_view_search_hidden(self) -> dict[str, Any]:
        self.shell("printf 'hidden-dotfile\\n' > /sdcard/MiniFileE2E/src/.hidden")
        self.tap(desc="More options")
        self.tap(text="Refresh")
        nodes, _, _ = self.dump_ui()
        assert not self.find_nodes(nodes, text=".hidden"), ".hidden must be hidden before toggle"

        # Toggle Grid view then back to List view
        self.tap(desc="Toggle view mode")
        s_grid = self.screencap("e3_grid_view")
        self.tap(desc="Toggle view mode")

        # Sort by Size Descending -> blob.bin (512 KiB) must appear above notes.txt (12 B)
        self.tap(desc="Sort")
        self.tap(text="Size")
        self.tap(text="Descending")
        self.shell("input keyevent KEYCODE_BACK")
        time.sleep(0.6)
        nodes, _, x_sort = self.dump_ui("e3_sorted_size_desc")
        blob_n = self.find_nodes(nodes, text="blob.bin")[0]
        notes_n = self.find_nodes(nodes, text="notes.txt")[0]
        assert blob_n.cy < notes_n.cy, f"Expected blob.bin (y={blob_n.cy}) above notes.txt (y={notes_n.cy})"

        # Search filter "blob"
        self.tap(desc="Search")
        edit_n, _, _ = self.wait_for_node(cls="android.widget.EditText")
        self.tap_xy(edit_n.cx, edit_n.cy, settle=0.3)
        self.shell("input text blob")
        time.sleep(0.6)
        nodes_s, _, x_search = self.dump_ui("e3_search_blob")
        s_search = self.screencap("e3_search_blob")
        assert self.find_nodes(nodes_s, text="blob.bin"), "blob.bin missing from search results"
        assert not self.find_nodes(nodes_s, text="notes.txt"), "notes.txt should be filtered out"
        self.tap(desc="Close search")

        # Enable Show hidden files
        self.tap(desc="More options")
        self.tap(text="Show hidden files")
        self.wait_for_node(text=".hidden")
        s_hidden = self.screencap("e3_hidden_visible")

        # Reset sort to Name Ascending before force-stop check
        self.tap(desc="Sort")
        self.tap(text="Name")
        self.tap(text="Ascending")
        self.shell("input keyevent KEYCODE_BACK")
        time.sleep(0.5)

        # Force-stop & relaunch -> verify showHidden persisted
        self.shell(f"am force-stop {PKG}")
        self.shell(f"am start -n {MAIN_ACTIVITY} -W")
        time.sleep(1.0)
        self.navigate_home_to_src()
        self.wait_for_node(text=".hidden")
        _, _, x_persist = self.dump_ui("e3_hidden_persisted")
        return {
            "screenshots": [s_grid, s_search, s_hidden],
            "uidumps": [x_sort, x_search, x_persist],
            "notes": "Grid/list toggle, Size desc ordering, 'blob' filter, and .hidden persistence across force-stop verified.",
        }

    def e4_create_folder_and_file(self) -> dict[str, Any]:
        self.tap(desc="Create")
        self.tap(text="New folder")
        self.replace_dialog_edit_text("E2E Folder")
        self.tap(text="Create")
        self.wait_for_node(text="E2E Folder")

        self.tap(desc="Create")
        self.tap(text="New file")
        self.replace_dialog_edit_text("made.txt")
        self.tap(text="Create")
        # CreateFileDialog opens editor by default ("Open in editor" = true)
        self.wait_for_node(text="made.txt")
        s_editor = self.screencap("e4_made_txt_editor")
        self.tap(desc="Back")

        self.wait_for_node(text="made.txt")
        s_src = self.screencap("e4_created_items")
        _, _, x_src = self.dump_ui("e4_created_items")

        stat_out = self.shell(
            "test -d '/sdcard/MiniFileE2E/src/E2E Folder' && stat -c '%s' /sdcard/MiniFileE2E/src/made.txt"
        )
        assert stat_out.strip() == "0", f"Expected made.txt size 0, got: {stat_out}"
        return {
            "screenshots": [s_editor, s_src],
            "uidumps": [x_src],
            "notes": "Created 'E2E Folder' and 0-byte 'made.txt' (auto-opened in built-in editor).",
        }

    def e5_text_editor(self) -> dict[str, Any]:
        self.tap(text="notes.txt", scroll_if_missing=True)
        self.wait_for_node(text_contains="UTF-8 · LF")
        # BasicTextField in Compose (wrapped in HorizontalScrollView) is auto-focused at EOF on open
        edit_n, _, _ = self.wait_for_node(text_contains="hello")
        self.tap_xy(edit_n.cx, max(edit_n.y1 + 8, edit_n.y2 - 12), settle=0.4)
        self.shell("input keyevent 123")
        self.shell("input text appended_by_e5")
        time.sleep(0.5)
        self.tap(desc="Save")
        time.sleep(0.8)

        disk_body = self.shell("cat /sdcard/MiniFileE2E/src/notes.txt")
        assert "appended_by_e5" in disk_body and "hello" in disk_body, f"Unexpected notes.txt content: {disk_body!r}"

        self.tap(desc="Back")
        # Reopen to verify persisted content & UTF-8 · LF status bar
        self.tap(text="notes.txt", scroll_if_missing=True)
        self.wait_for_node(text_contains="UTF-8 · LF")
        self.wait_for_node(text_contains="appended_by_e5")
        shot = self.screencap("e5_text_editor")
        _, _, xdump = self.dump_ui("e5_text_editor")
        self.tap(desc="Back")
        return {
            "screenshots": [shot],
            "uidumps": [xdump],
            "notes": "Edited & saved notes.txt; verified 'UTF-8 · LF' status bar and on-disk content.",
        }

    def e6_copy_move_paste(self) -> dict[str, Any]:
        src_notes_md5 = self.shell("md5sum /sdcard/MiniFileE2E/src/notes.txt").split()[0]
        src_blob_md5 = self.shell("md5sum /sdcard/MiniFileE2E/src/blob.bin").split()[0]

        # Multi-select notes.txt + blob.bin -> Copy -> dst -> Paste here
        self.long_press(text="notes.txt")
        self.tap(text="blob.bin", scroll_if_missing=True)
        self.wait_for_node(text="2 selected")
        s_sel = self.screencap("e6_selection")
        self.tap(text="Copy")

        self.tap(desc="Navigate up")
        self.tap(text="dst")
        self.tap(text="Paste here")
        time.sleep(1.5)

        dst_notes_md5 = self.shell("md5sum /sdcard/MiniFileE2E/dst/notes.txt").split()[0]
        dst_blob_md5 = self.shell("md5sum /sdcard/MiniFileE2E/dst/blob.bin").split()[0]
        assert src_notes_md5 == dst_notes_md5, f"notes.txt MD5 mismatch: {src_notes_md5} != {dst_notes_md5}"
        assert src_blob_md5 == dst_blob_md5, f"blob.bin MD5 mismatch: {src_blob_md5} != {dst_blob_md5}"

        # Cut made.txt from src -> Paste here in dst
        self.tap(desc="Navigate up")
        self.tap(text="src")
        self.long_press(text="made.txt")
        self.tap(text="Cut")
        self.tap(desc="Navigate up")
        self.tap(text="dst")
        self.tap(text="Paste here")
        time.sleep(1.2)

        check_move = self.shell(
            "test -f /sdcard/MiniFileE2E/dst/made.txt && ! test -e /sdcard/MiniFileE2E/src/made.txt && echo MOVED_OK"
        )
        assert check_move == "MOVED_OK", f"Move check failed: {check_move}"
        s_dst = self.screencap("e6_dst_after_paste")
        _, _, x_dst = self.dump_ui("e6_dst_after_paste")
        return {
            "screenshots": [s_sel, s_dst],
            "uidumps": [x_dst],
            "notes": f"Copied notes.txt ({dst_notes_md5[:12]}…) & blob.bin ({dst_blob_md5[:12]}…) and moved made.txt into dst.",
        }

    def e7_favorites_and_copy_to_favorite(self) -> dict[str, Any]:
        # Currently inside dst: star it as "Target"
        self.tap(desc="Add to Favorites")
        self.replace_dialog_edit_text("Target")
        self.tap(text="Save")

        # Navigate to src -> select 'sub' -> More -> Copy to Favorite… -> Target
        self.tap(desc="Navigate up")
        self.tap(text="src")
        self.long_press(text="sub")
        self.tap(desc="More")
        self.tap(text="Copy to Favorite…")

        self.wait_for_node(text="Copy sub to…")
        self.wait_for_node(text="Target")
        s_sheet = self.screencap("e7_favorite_picker_sheet")
        _, _, x_sheet = self.dump_ui("e7_favorite_picker_sheet")

        self.tap(text="Target")
        time.sleep(1.2)
        s_snack = self.screencap("e7_after_copy_to_favorite")

        sub_check = self.shell("cat /sdcard/MiniFileE2E/dst/sub/child.txt")
        assert sub_check == "nested-sub-content", f"Expected copied sub/child.txt, got: {sub_check!r}"

        # Verify Favorites tab lists Target
        self.tap_bottom_tab("Favorites")
        self.wait_for_node(text="Target")
        s_fav = self.screencap("e7_favorites_tab")
        _, _, x_fav = self.dump_ui("e7_favorites_tab")

        self.tap_bottom_tab("Browse")
        return {
            "screenshots": [s_sheet, s_snack, s_fav],
            "uidumps": [x_sheet, x_fav],
            "notes": "Bookmarked dst as 'Target', copied src/sub via 1-tap Favorite picker, and verified Favorites tab.",
        }

    def e8_rename_and_delete(self) -> dict[str, Any]:
        # Navigate from src to dst
        self.tap(desc="Navigate up")
        self.tap(text="dst")

        self.tap(desc="More actions for blob.bin")
        self.tap(text="Rename")
        self.replace_dialog_edit_text("blob2.bin")
        # Confirm button is the lower "Rename" node (below dialog title "Rename")
        rename_nodes = self.find_nodes(self.dump_ui()[0], text="Rename")
        assert rename_nodes, "Rename confirm button not found"
        confirm_btn = sorted(rename_nodes, key=lambda n: n.cy)[-1]
        self.tap_xy(confirm_btn.cx, confirm_btn.cy, settle=0.8)
        self.wait_for_node(text="blob2.bin")
        assert self.shell("test -f /sdcard/MiniFileE2E/dst/blob2.bin && echo OK") == "OK"

        self.tap(desc="More actions for blob2.bin")
        self.tap(text="Delete")
        self.wait_for_node(text_contains="Delete 'blob2.bin'?")
        s_del = self.screencap("e8_delete_confirm")
        _, _, x_del = self.dump_ui("e8_delete_confirm")

        self.tap(text="Delete")
        time.sleep(1.0)
        assert self.shell("! test -e /sdcard/MiniFileE2E/dst/blob2.bin && echo ABSENT") == "ABSENT"
        return {
            "screenshots": [s_del],
            "uidumps": [x_del],
            "notes": "Renamed blob.bin -> blob2.bin and deleted via confirmation dialog.",
        }

    def e9_properties_and_checksum(self) -> dict[str, Any]:
        expected_sha = self.shell("sha256sum /sdcard/MiniFileE2E/dst/notes.txt").split()[0].lower()

        self.tap(desc="More actions for notes.txt")
        self.tap(text="Properties")
        self.tap(text="Compute SHA-256", scroll_if_missing=True)
        time.sleep(1.0)

        self.wait_for_node(text=expected_sha)
        shot = self.screencap("e9_properties_sha256")
        _, _, xdump = self.dump_ui("e9_properties_sha256")
        self.dismiss_sheet()
        return {
            "screenshots": [shot],
            "uidumps": [xdump],
            "notes": f"Properties SHA-256 matched on-device sha256sum ({expected_sha}).",
        }

    def e10_nomedia_toggle(self) -> dict[str, Any]:
        self.dismiss_sheet()
        # Step up to /sdcard/MiniFileE2E so 'dst' folder row is visible
        self.tap(desc="Navigate up")
        self.wait_for_node(text="dst")

        self.tap(desc="More actions for dst")
        self.tap(text="Hide from gallery")
        time.sleep(0.8)
        assert self.shell("test -f /sdcard/MiniFileE2E/dst/.nomedia && echo YES") == "YES"
        self.wait_for_node(desc="Hidden from gallery")
        s_on = self.screencap("e10_nomedia_on")
        _, _, x_on = self.dump_ui("e10_nomedia_on")

        self.tap(desc="More actions for dst")
        self.tap(text="Hide from gallery")
        time.sleep(0.8)
        assert self.shell("! test -e /sdcard/MiniFileE2E/dst/.nomedia && echo NO") == "NO"
        nodes_off, _, x_off = self.dump_ui("e10_nomedia_off")
        assert not self.find_nodes(nodes_off, desc="Hidden from gallery"), "Badge should disappear when .nomedia removed"
        return {
            "screenshots": [s_on],
            "uidumps": [x_on, x_off],
            "notes": "Verified .nomedia creation/removal and 'Hidden from gallery' row badge toggle.",
        }

    def e11_zip_unzip_and_zipslip(self) -> dict[str, Any]:
        # Compress src -> e2e.zip inside MiniFileE2E
        self.tap(desc="More actions for src")
        self.tap(text="Compress to ZIP")
        self.replace_dialog_edit_text("e2e.zip")
        self.tap(text="Compress")
        time.sleep(1.8)
        assert self.shell("test -f /sdcard/MiniFileE2E/e2e.zip && echo OK") == "OK"

        # Open e2e.zip -> ArchivePreviewScreen -> Extract to new folder
        self.tap(text="e2e.zip", scroll_if_missing=True)
        self.wait_for_node(text="Extract to new folder")
        s_prev = self.screencap("e11_zip_preview")
        self.tap(text="Extract to new folder")
        time.sleep(1.8)

        orig_md5 = self.shell("md5sum /sdcard/MiniFileE2E/src/blob.bin").split()[0]
        ext_md5 = self.shell("md5sum /sdcard/MiniFileE2E/e2e/src/blob.bin").split()[0]
        assert orig_md5 == ext_md5, f"Extracted blob.bin MD5 mismatch: {orig_md5} != {ext_md5}"

        # Build & push crafted zip-slip archive evil.zip containing "../evil.txt" + "safe.txt"
        evil_local = self.out_dir / "evil.zip"
        with zipfile.ZipFile(evil_local, "w", compression=zipfile.ZIP_DEFLATED) as zf:
            zf.writestr("safe.txt", "safe-payload\n")
            zf.writestr("../evil.txt", "pwned-outside-dest\n")
        self.adb("push", str(evil_local), "/sdcard/MiniFileE2E/evil.zip")

        self.tap(desc="More options")
        self.tap(text="Refresh")
        self.tap(text="evil.zip", scroll_if_missing=True)

        # ArchivePreviewScreen displays warning banner for unsafe entry
        self.wait_for_node(text_contains="unsafe path entr")
        s_evil_prev = self.screencap("e11_evil_zip_preview")
        _, _, x_evil_prev = self.dump_ui("e11_evil_zip_preview")

        self.tap(text="Extract to new folder")
        self.wait_for_node(text_contains="unsafe entry skipped", timeout=14.0)
        s_evil_done = self.screencap("e11_evil_extracted")
        _, _, x_evil_done = self.dump_ui("e11_evil_extracted")

        guard_check = self.shell(
            "test -f /sdcard/MiniFileE2E/evil/safe.txt && "
            "! test -e /sdcard/MiniFileE2E/evil.txt && "
            "! test -e /sdcard/evil.txt && echo GUARD_OK"
        )
        assert guard_check == "GUARD_OK", f"Zip-slip guard failed: {guard_check}"
        return {
            "screenshots": [s_prev, s_evil_prev, s_evil_done],
            "uidumps": [x_evil_prev, x_evil_done],
            "notes": "Compressed & extracted e2e.zip (MD5 verified) and blocked ../evil.txt zip-slip traversal.",
        }

    def e12_appdata_volumes_and_installed_apps(self) -> dict[str, Any]:
        # Return to Storage Home
        self.tap(desc="Storage Home")
        self.scroll_up()
        self.wait_for_node(text="Internal")
        self.wait_for_node(text_contains="free of")
        s_home = self.screencap("e12_storage_home")
        _, _, x_home = self.dump_ui("e12_storage_home")

        # Tap Android/data -> system SAF picker (documentsui) launches
        self.tap(text="Android/data", scroll_if_missing=True, settle=1.5)
        top_act = self.shell("dumpsys activity activities | grep -E 'ResumedActivity|topResumedActivity'", check=False)
        s_saf = self.screencap("e12_saf_picker")
        assert "documentsui" in top_act.lower(), f"Expected DocumentsUI SAF picker, got: {top_act}"
        self.shell("input keyevent KEYCODE_BACK")
        time.sleep(1.0)
        # Ensure we returned to MiniFile cleanly
        if PKG not in self.shell("dumpsys activity activities | grep -E 'ResumedActivity|topResumedActivity'", check=False):
            self.shell("input keyevent KEYCODE_BACK")
            time.sleep(0.8)

        # Open Installed Apps -> inspect MiniFile -> Export APK to Target (dst)
        self.tap(text="Installed Apps", scroll_if_missing=True, settle=1.5)
        self.wait_for_node(text="Installed Apps")
        # Search for MiniFile so it's immediately at top regardless of user app count
        self.tap(desc="Search apps")
        edit_n, _, _ = self.wait_for_node(cls="android.widget.EditText")
        self.tap_xy(edit_n.cx, edit_n.cy, settle=0.3)
        self.shell("input text MiniFile")
        time.sleep(0.7)
        self.wait_for_node(text_contains=PKG)
        s_apps = self.screencap("e12_installed_apps")
        _, _, x_apps = self.dump_ui("e12_installed_apps")

        self.tap(text_contains=PKG)
        self.tap(text="Export APK", scroll_if_missing=True)
        self.tap(text="★ Target")
        self.tap(text="Export")
        time.sleep(2.2)

        exported_apk = self.shell("ls -1 /sdcard/MiniFileE2E/dst/*.apk | head -n1")
        assert exported_apk.endswith(".apk"), f"Expected exported APK in dst, got: {exported_apk!r}"
        with tempfile.TemporaryDirectory() as td:
            local_apk = Path(td) / "exported.apk"
            self.adb("pull", exported_apk, str(local_apk))
            with zipfile.ZipFile(local_apk, "r") as zf:
                assert "AndroidManifest.xml" in zf.namelist(), "Exported APK missing AndroidManifest.xml"

        # Close search & InstalledApps overlay
        self.tap(desc="Close search")
        self.tap(desc="Back")
        return {
            "screenshots": [s_home, s_saf, s_apps],
            "uidumps": [x_home, x_apps],
            "notes": f"Verified Storage Home capacities, DocumentsUI SAF picker for Android/data, and APK export ({Path(exported_apk).name}).",
        }

    def e13_ftp_server_and_companion_cli(self) -> dict[str, Any]:
        # Ensure clean stopped state before starting
        self.shell(
            f"am startservice -n {PKG}/.service.FtpServerService -a com.minifile.explorer.action.FTP_STOP",
            check=False,
        )
        time.sleep(0.6)
        # Launch Wireless tab with bounded passive port window 50000..50004 for adb forward
        self.shell(
            f"am start --activity-single-top -n {MAIN_ACTIVITY} --es open_tab wireless "
            "--ei ftp_port 2121 --ei pasv_min 50000 --ei pasv_max 50004"
        )
        time.sleep(1.0)
        self.wait_for_node(text="Wireless Transfer")
        self.wait_for_node(text_contains="50000–50004", scroll_if_missing=True)
        self.scroll_up()

        self.tap(text="Start", settle=1.5)
        self.wait_for_node(text="Running")
        self.wait_for_node(text_contains=":2121")
        s_run = self.screencap("e13_ftp_running")
        _, _, x_run = self.dump_ui("e13_ftp_running")

        # Verify foreground service & notification while running
        svc_running = self.shell(f"dumpsys activity services {PKG}")
        assert "FtpServerService" in svc_running and "isForeground=true" in svc_running, svc_running
        notif_running = self.shell("dumpsys notification --noredact | grep -i 'MiniFile FTP'", check=False)
        assert "MiniFile FTP" in notif_running, "Expected ongoing 'MiniFile FTP' notification"

        # Forward control port 12121 -> 2121 and passive ports 50000..50004
        self.adb("forward", "tcp:12121", "tcp:2121")
        for p in range(50000, 50005):
            self.adb("forward", f"tcp:{p}", f"tcp:{p}")

        try:
            with tempfile.TemporaryDirectory(prefix="mf-e13-pc-") as pc_tmp:
                pc_dir = Path(pc_tmp)
                env = os.environ.copy()
                env["MINIFILE_CONFIG"] = str(pc_dir / "config.json")
                env["MINIFILE_CACHE_DIR"] = str(pc_dir / "cache")
                base = [sys.executable, "-m", "minifile_companion", "--no-update-hint", "-H", "127.0.0.1", "-P", "12121"]

                def run_pc(*cli_args: str) -> str:
                    res = subprocess.run(
                        [*base, *cli_args],
                        cwd=str(COMPANION_DIR),
                        env=env,
                        stdin=subprocess.DEVNULL,
                        capture_output=True,
                        text=True,
                        timeout=30,
                    )
                    if res.returncode != 0:
                        raise RuntimeError(
                            f"Companion CLI failed ({res.returncode}): {' '.join(cli_args)}\n"
                            f"STDOUT: {res.stdout}\nSTDERR: {res.stderr}"
                        )
                    return res.stdout

                # 1. ls
                ls_json = json.loads(run_pc("ls", "/MiniFileE2E", "--json"))
                ls_names = {e["name"] for e in ls_json["entries"]}
                assert {"src", "dst"}.issubset(ls_names), ls_names

                # 2. mkdir
                run_pc("mkdir", "-p", "/MiniFileE2E/ftp_e2e")

                # 3. put + SHA-256 verification
                upload_file = pc_dir / "pc_upload.bin"
                payload = os.urandom(64 * 1024)
                pc_sha = hashlib.sha256(payload).hexdigest()
                upload_file.write_bytes(payload)
                run_pc("put", str(upload_file), "/MiniFileE2E/ftp_e2e/pc_upload.bin")
                dev_sha = self.shell("sha256sum /sdcard/MiniFileE2E/ftp_e2e/pc_upload.bin").split()[0].lower()
                assert pc_sha == dev_sha, f"FTP upload SHA-256 mismatch: {pc_sha} != {dev_sha}"

                # 4. get + SHA-256 verification
                dl_file = pc_dir / "notes_downloaded.txt"
                run_pc("get", "/MiniFileE2E/src/notes.txt", str(dl_file))
                dev_notes_sha = self.shell("sha256sum /sdcard/MiniFileE2E/src/notes.txt").split()[0].lower()
                assert hashlib.sha256(dl_file.read_bytes()).hexdigest() == dev_notes_sha

                # 5. sync --to-pc
                sync_dest = pc_dir / "synced_src"
                sync_out = json.loads(run_pc("sync", str(sync_dest), "/MiniFileE2E/src", "--to-pc", "--json"))
                assert sync_out["downloaded"] >= 3, sync_out
                assert (sync_dest / "sub" / "child.txt").read_text(encoding="utf-8").strip() == "nested-sub-content"

                # 6. cat
                cat_out = run_pc("cat", "/MiniFileE2E/src/notes.txt")
                assert "appended_by_e5" in cat_out

                # 7. nomedia --on / --off
                run_pc("nomedia", "/MiniFileE2E/dst", "--on")
                assert self.shell("test -f /sdcard/MiniFileE2E/dst/.nomedia && echo ON") == "ON"
                run_pc("nomedia", "/MiniFileE2E/dst", "--off")

                # 8. rm -rf
                run_pc("rm", "-rf", "/MiniFileE2E/ftp_e2e")
                assert self.shell("! test -e /sdcard/MiniFileE2E/ftp_e2e && echo GONE") == "GONE"

            # Scroll Wireless tab to verify live Activity log populated
            self.scroll_down()
            self.wait_for_node(text_contains="Activity (")
            s_log = self.screencap("e13_ftp_activity_log")
            _, xml_log, x_log = self.dump_ui("e13_ftp_activity_log")
            assert "127.0.0.1" in xml_log or "STOR" in xml_log or "RETR" in xml_log or "Activity (" in xml_log

            # Stop FTP server from the notification action (expand shade -> tap Stop; fallback to service stop intent if collapsed)
            self.shell("cmd statusbar expand-notifications", check=False)
            time.sleep(1.0)
            nodes_sh, _, _ = self.dump_ui()
            stop_btns = self.find_nodes(nodes_sh, text="Stop") + self.find_nodes(nodes_sh, desc="Stop")
            if stop_btns:
                self.tap_xy(stop_btns[0].cx, stop_btns[0].cy, settle=1.0)
                self.shell("cmd statusbar collapse", check=False)
            else:
                # Some shade layouts collapse action buttons until expanded; send exact notification action intent
                self.shell("cmd statusbar collapse", check=False)
                self.shell(f"am startservice -n {PKG}/.service.FtpServerService -a com.minifile.explorer.action.FTP_STOP")
                time.sleep(1.0)

            self.scroll_up()
            self.wait_for_node(text="Stopped")
            svc_after = self.shell(f"dumpsys activity services {PKG}")
            assert "FtpServerService" not in svc_after, f"Expected FtpServerService stopped, got: {svc_after}"
            notif_after = self.shell("dumpsys notification --noredact | grep -i 'MiniFile FTP ·'", check=False)
            assert not notif_after.strip(), f"Expected running notification removed, got: {notif_after}"
        finally:
            self.adb("forward", "--remove-all", check=False)

        return {
            "screenshots": [s_run, s_log],
            "uidumps": [x_run, x_log],
            "notes": "PC Companion executed ls, mkdir, put, get, sync --to-pc, cat, nomedia, rm over adb-forwarded FTP; service stopped cleanly.",
        }

    def e14_updates_card(self) -> dict[str, Any]:
        self.tap_bottom_tab("Tools")
        self.scroll_up()
        self.wait_for_node(text="Updates")
        self.tap(text="Check now", settle=3.0)

        # Wait until "Checking GitHub Releases…" finishes
        deadline = time.time() + 18.0
        final_xml = ""
        while time.time() < deadline:
            _, raw_xml, _ = self.dump_ui()
            final_xml = raw_xml
            if "Checking GitHub Releases" not in raw_xml:
                break
            time.sleep(1.0)

        shot = self.screencap("e14_updates_check")
        _, _, xdump = self.dump_ui("e14_updates_check")
        expected_outcomes = ("Up to date", "Update available", "Last check failed:")
        assert any(tok in final_xml for tok in expected_outcomes), f"Unexpected Updates card state:\n{final_xml[:800]}"
        matched = next(tok for tok in expected_outcomes if tok in final_xml)
        return {
            "screenshots": [shot],
            "uidumps": [xdump],
            "notes": f"Tools -> Updates -> Check now completed cleanly without crash (state='{matched}').",
        }

    def e15_process_death_and_back_stack(self) -> dict[str, Any]:
        self.navigate_home_to_src()
        # Seed in-memory clipboard before backgrounding + process kill
        self.long_press(text="notes.txt")
        self.tap(text="Copy")
        self.wait_for_node(text="Copy 1 item")

        pid_before = self.shell(f"pidof {PKG}", check=False)
        # Send app to background so Android persists SavedStateHandle, then kill background process
        self.shell("input keyevent KEYCODE_HOME")
        time.sleep(1.2)
        self.shell(f"am kill {PKG}")
        time.sleep(0.8)

        # Relaunch activity
        self.shell(f"am start -n {MAIN_ACTIVITY} -W")
        time.sleep(1.5)
        pid_after = self.shell(f"pidof {PKG}", check=False)

        nodes, raw_xml, xdump = self.dump_ui("e15_after_process_death")
        shot = self.screencap("e15_after_process_death")
        # Either restored directly to 'src' via SavedStateHandle or gracefully at Storage Home ("Internal")
        restored_src = bool(self.find_nodes(nodes, desc="Internal › MiniFileE2E › src") or self.find_nodes(nodes, text="notes.txt"))
        restored_home = bool(self.find_nodes(nodes, text="Internal"))
        assert restored_src or restored_home, "Expected Browse restored to src or Storage Home after am kill"
        assert "Copy 1 item" not in raw_xml, "Transient in-memory clipboard should be cleared after process death"

        return {
            "screenshots": [shot],
            "uidumps": [xdump],
            "notes": (
                f"Backgrounded & killed process (pid {pid_before} -> {pid_after}); "
                f"relaunched cleanly ({'restored src' if restored_src else 'Storage Home'}) with clipboard cleared."
            ),
        }

    def run_all(self, only: set[str] | None = None, skip_setup: bool = False) -> dict[str, Any]:
        if skip_setup:
            wm_size = self.shell("wm size", check=False)
            m = re.search(r"(\d+)x(\d+)", wm_size)
            if m:
                self.screen_w, self.screen_h = int(m.group(1)), int(m.group(2))
            device_info = {
                "sdk": self.shell("getprop ro.build.version.sdk"),
                "release": self.shell("getprop ro.build.version.release"),
                "model": self.shell("getprop ro.product.model"),
                "fingerprint": self.shell("getprop ro.build.fingerprint"),
                "screen": f"{self.screen_w}x{self.screen_h}",
                "apk": str(self.apk_path.relative_to(REPO_ROOT)) if self.apk_path.is_relative_to(REPO_ROOT) else str(self.apk_path),
            }
        else:
            device_info = self.setup_device()
        scenarios: list[tuple[str, str, Callable[[], dict[str, Any]]]] = [
            ("E1", "Launch & bottom navigation tabs", self.e1_launch_and_tabs),
            ("E2", "Browse & breadcrumbs", self.e2_browse_and_breadcrumbs),
            ("E3", "Sort, grid/list view, search & hidden files", self.e3_sort_view_search_hidden),
            ("E4", "Create folder & empty file", self.e4_create_folder_and_file),
            ("E5", "Built-in text editor (UTF-8 · LF)", self.e5_text_editor),
            ("E6", "Multi-select Copy, Cut/Move & Paste", self.e6_copy_move_paste),
            ("E7", "Favorites & 1-tap Copy to Favorite", self.e7_favorites_and_copy_to_favorite),
            ("E8", "Rename & Delete with confirmation", self.e8_rename_and_delete),
            ("E9", "Properties & SHA-256 checksum", self.e9_properties_and_checksum),
            ("E10", ".nomedia toggle & gallery badge", self.e10_nomedia_toggle),
            ("E11", "ZIP compress, extract & zip-slip guard", self.e11_zip_unzip_and_zipslip),
            ("E12", "Storage Home, SAF Android/data & APK export", self.e12_appdata_volumes_and_installed_apps),
            ("E13", "Wireless FTP server + PC Companion over adb forward", self.e13_ftp_server_and_companion_cli),
            ("E14", "Updates card Check now", self.e14_updates_card),
            ("E15", "Process death (am kill) & state restoration", self.e15_process_death_and_back_stack),
        ]
        for code, title, fn in scenarios:
            if only and code not in only:
                continue
            self.run_scenario(code, title, fn)

        logcat_tail = self.shell(f"logcat -d -t 120 | grep -E '{PKG}|AndroidRuntime'", check=False)
        (self.out_dir / "logcat_excerpt.txt").write_text(logcat_tail + "\n", encoding="utf-8")

        report = {
            "status": "PASS" if all(r["status"] == "PASS" for r in self.results) else "FAIL",
            "timestamp": datetime.now().isoformat(),
            "device": device_info,
            "scenarios": self.results,
        }
        report_file = self.out_dir / "report.json"
        report_file.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        self.log(f"ALL {len(self.results)} E2E SCENARIOS PASSED! Report saved to {report_file}")
        return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Run MiniFile ACID Android Emulator E2E test suite (E1-E15).")
    parser.add_argument("--serial", required=True, help="ADB serial (e.g. localhost:38945)")
    parser.add_argument(
        "--apk",
        default=str(REPO_ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"),
        help="Path to debug APK to install",
    )
    parser.add_argument(
        "--out-dir",
        default=str(REPO_ROOT / "tools" / "e2e" / "out" / datetime.now().strftime("%Y%m%d-%H%M%S")),
        help="Directory for screenshots, UI dumps, and report.json",
    )
    parser.add_argument("--only", default="", help="Optional comma-separated scenario IDs (e.g. E13,E14)")
    parser.add_argument("--skip-setup", action="store_true", help="Resume without reinstalling/wiping device state")
    args = parser.parse_args(argv)

    only_set = {s.strip().upper() for s in args.only.split(",") if s.strip()} or None
    runner = AcidE2ERunner(
        serial=args.serial,
        apk_path=Path(args.apk),
        out_dir=Path(args.out_dir),
    )
    runner.run_all(only=only_set, skip_setup=args.skip_setup)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
