# MiniFile

Minimalist, offline-first **Android File Manager** with a built-in **RFC 959 / RFC 3659 Wireless FTP Server** and a zero-dependency **Python 3 PC Companion** (CLI + Dual-Pane Web & Tkinter GUI).

---

## Features

- **Minimalist Material 3 File Browser**:
  - Edge-to-edge Material 3 UI with Dynamic Color (Material You) and System / Light / Dark themes.
  - List & adaptive Grid views, breadcrumb bar with long-press path copy, natural sorting (`file2` < `file10`), instant filter & recursive subfolder search, and multi-select range gestures.
  - Non-blocking foreground file operations (`Copy`, `Move`/`Cut`, `Delete`, `Rename`, `Create Folder/File`) with live byte throughput, cancellation, and interactive conflict resolution (`Skip`, `Overwrite`, `Keep both`, `Apply to all`).
- **Wireless FTP Server & PC Companion**:
  - Built-in Android foreground FTP server (`MLSD`, `MLST`, `UTF8`, `SIZE`, `MDTM`, `PASV` / `EPSV`, `REST STREAM`, `RNFR` / `RNTO`) supporting Anonymous or Username/Password authentication, Read-Only mode, bounded passive port windows, peer-IP data-channel validation, and live activity logs.
  - Zero-dependency Python 3.10+ PC Companion (`companion/minifile.py` / `python3 -m minifile_companion`):
    - **CLI**: `scan`, `ls`, `tree`, `get`, `put`, `sync` (`--to-phone`, `--to-pc`, `--both`, `--delete`, `--dry-run`), `cat`, `edit`, `mkdir`, `rm`, `mv`, `nomedia`, `shell`, `update`.
    - **Desktop GUI**: Localhost-bound dual-pane Web UI (`127.0.0.1` with CSRF token & DNS-rebinding guards) + optional Tkinter launcher (`--tk`).
- **Power Tools**:
  - Global hidden dotfiles (`.hidden`) toggle.
  - Built-in text viewer & editor (`UTF-8`, `UTF-16 LE/BE`, `ISO-8859-1`, `Windows-1252`) with BOM & `LF`/`CRLF` preservation, line numbers, undo/redo, find & replace, and JSON pretty-printing.
  - Streaming ZIP archive preview, selective/full extraction, and multi-level compression with strict **Zip-Slip** path sanitization and **zip-bomb** expansion guards.
  - `.nomedia` row badge (`Hidden from gallery`), 1-tap toggle, and bulk storage scanner.
  - File & folder Properties sheet with asynchronous `MD5` & `SHA-256` checksum calculation and hash verification.
- **Storage Volumes & App Data**:
  - Internal Storage, removable SD cards, and hot-plugged USB OTG drives.
  - Guided Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE`) mounting for `Android/data`, `Android/obb`, and arbitrary folders when All-files access is not granted.
  - Installed Apps Inspector with package storage inspection and 1-tap APK backup/export.
- **Favorites & 1-Tap Copy/Move to Favorite**:
  - Bookmark local or SAF directories, reorder/rename, export/import Favorites JSON, and copy or move any selection directly to a Favorite folder in one tap via `FavoritePickerSheet`.
- **Verified Updates via GitHub Releases**:
  - Opt-out GitHub Releases checker (`https://github.com/codescv/minifile/releases`) enforcing HTTPS-only manual redirect validation, `SHA256SUMS.txt` verification (PC Companion), and APK package + `versionCode` + signing-certificate verification (Android).

---

## Download & Install

Grab the latest release from [**GitHub Releases**](https://github.com/codescv/minifile/releases/latest):

| Asset | Description |
|-------|-------------|
| `minifile-v1.0.0-release.apk` | Signed Android APK (`minSdk 26` / Android 8.0+, `targetSdk 35`) |
| `minifile-companion-v1.0.0.zip` | Zero-dependency Python 3 PC Companion (`cli` + `gui`) |
| `SHA256SUMS.txt` | SHA-256 checksums for release assets |

---

## PC Companion Quick Start

Requires Python 3.10+ (stdlib only — no `pip install` needed):

```bash
cd companion

# Discover your phone running MiniFile -> Wireless -> Start on the same Wi-Fi LAN
python3 -m minifile_companion scan --save

# List, transfer, or synchronize folders
python3 -m minifile_companion ls /Download -la
python3 -m minifile_companion put ./slides.pdf /Documents/
python3 -m minifile_companion get /DCIM/Camera ./Camera -r --resume
python3 -m minifile_companion sync ./Notes /Documents/Notes --both

# Launch the localhost Dual-Pane Web Desktop GUI
python3 -m minifile_companion gui

# Over USB (ADB port forwarding with passive range 50000-50004):
adb forward tcp:12121 tcp:2121
for p in $(seq 50000 50004); do adb forward tcp:$p tcp:$p; done
python3 -m minifile_companion -H 127.0.0.1 -P 12121 ls /
```

See [`companion/README.md`](companion/README.md) for the full CLI & Web API reference.

---

## Building & Testing from Source

### Prerequisites
- **JDK 21** (`JAVA_HOME` pointing to JDK 21)
- **Android SDK Platform 35** & Build-Tools `35.0.0` (`ANDROID_HOME`)
- **Python 3.10+**

### Build APKs & Run Unit Tests
```bash
# Build debug & release APKs and run pure-JVM Android unit tests + Lint
./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug

# Run PC Companion unit test suite (43 tests)
python3 -m unittest discover -s companion/tests -v

# Run cross-component Kotlin FtpServerEngine <-> Python PC Companion integration suite
python3 tools/run_interop_tests.py
```

Output APKs:
- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release.apk`

---

## Documentation

- [`DESIGN.md`](DESIGN.md) — Product & UX specification
- [`ARCHITECTURE.md`](ARCHITECTURE.md) — VFS, FTP protocol, security invariants & module architecture
- [`TEST_REPORT.md`](TEST_REPORT.md) — Full unit, JVM interop, security, and Android 15 emulator E2E verification report
