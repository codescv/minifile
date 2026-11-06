# MiniFile

Minimalist, offline-first **Android File Manager** with **Google Drive & FTP Remote Servers**, a built-in **HTTP QR File Share Server**, **RFC 959 / RFC 3659 Wireless FTP Server**, **Built-in PDF Viewer**, and a zero-dependency **Python 3 PC Companion** (CLI + Dual-Pane Web & Tkinter GUI).

---

## Features

- **Minimalist Material 3 File Browser**:
  - Edge-to-edge Material 3 UI with Dynamic Color (Material You) and System / Light / Dark themes.
  - List & adaptive Grid views, breadcrumb bar with long-press path copy, natural sorting (`file2` < `file10`), instant filter & recursive subfolder search, and multi-select range gestures.
  - Non-blocking foreground file operations (`Copy`, `Move`/`Cut`, `Delete`, `Rename`, `Create Folder/File`) with live byte throughput, cancellation, and interactive conflict resolution (`Skip`, `Overwrite`, `Keep both`, `Apply to all`).
- **Wireless Transfer — HTTP QR Share & FTP PC Companion**:
  - **Built-in HTTP/1.1 File Share Server** (`HttpFileServerEngine`, default port `8080`) with instant **QR Code** for phones & browsers on the same Wi-Fi/hotspot: responsive dark/light HTML5 Web UI, `206 Partial Content` byte-range media/PDF streaming, on-the-fly `.zip` folder downloads, folder creation, and multi-file uploads with progress bar.
  - **Built-in Android foreground FTP server** (`MLSD`, `MLST`, `UTF8`, `SIZE`, `MDTM`, `PASV` / `EPSV`, `REST STREAM`, `RNFR` / `RNTO`) supporting Anonymous or Username/Password authentication, Read-Only mode, bounded passive port windows, and peer-IP data-channel validation.
  - Zero-dependency Python 3.10+ PC Companion (`minifile` / `minifile-companion` via `uv tool install`, or standalone `python3 -m minifile_companion`):
    - **CLI**: `scan`, `ls`, `tree`, `get`, `put`, `sync` (`--to-phone`, `--to-pc`, `--both`, `--delete`, `--dry-run`), `cat`, `edit`, `mkdir`, `rm`, `mv`, `nomedia`, `shell`, `gdrive-auth`, `update`.
    - **Desktop GUI**: Localhost-bound dual-pane Web UI (`127.0.0.1` with CSRF token & DNS-rebinding guards) + optional Tkinter launcher (`--tk`).
- **Remote Servers — Google Drive & FTP**:
  - Unified **Servers** card on Storage Home supporting **Google Drive** (Drive REST API v3, zero Google Play Services SDK bloat) alongside **FTP** bookmarks.
  - Browse My Drive folders, stream media/PDFs with HTTP `Range` + automatic single-reconnect recovery, upload large files via bounded 8 MiB resumable chunks, export Google Docs/Sheets/Slides/Drawings as PDFs on the fly, and perform O(1) server-side rename, move, and trash.
  - Three flexible Google Drive sign-in methods: **Sign in with Google Account** (1-tap Android AccountManager), **Sign in with Browser** (OAuth 2.0 loopback + PKCE `S256`), and **Paste Token / JSON** (`minifile gdrive-auth` or `rclone authorize "drive"`).
- **Power Tools**:
  - Global hidden dotfiles (`.hidden`) toggle.
  - **Built-in PDF Viewer** (`PdfRenderer`) supporting both local and SAF documents with crisp high-DPI page rendering, pinch-to-zoom, page jump, and external app handoff.
  - Built-in text viewer & editor (`UTF-8`, `UTF-16 LE/BE`, `ISO-8859-1`, `Windows-1252`) with BOM & `LF`/`CRLF` preservation, line numbers, undo/redo, find & replace, and JSON pretty-printing.
  - Streaming ZIP archive preview, selective/full extraction, and multi-level compression with strict **Zip-Slip** path sanitization and **zip-bomb** expansion guards.
  - `.nomedia` row badge (`Hidden from gallery`), 1-tap toggle, and bulk storage scanner.
  - File & folder Properties sheet with asynchronous `MD5` & `SHA-256` checksum calculation and hash verification.
- **Storage Volumes**:
  - Internal Storage, removable SD cards, and hot-plugged USB OTG drives via local file I/O and Storage Access Framework (SAF).
- **Favorites & 1-Tap Copy/Move to Favorite**:
  - Bookmark local, SAF, FTP, or Google Drive folders and files directly in Browse's **Recent Favorites** section (with inline Rename, Properties, Move up/down, Remove, and full Favorites screen via `See all →`), and copy or move any selection directly to a Favorite folder in one tap via `FavoritePickerSheet`.
- **Verified Updates via GitHub Releases**:
  - Opt-out GitHub Releases checker (`https://github.com/codescv/minifile/releases`) enforcing HTTPS-only manual redirect validation, `SHA256SUMS.txt` verification (PC Companion and Android APK, `v1.0.16`), and APK package + `versionCode` + signing-certificate verification (Android).

---

## Download & Install

### Android App & Release Archives

Grab the latest release from [**GitHub Releases**](https://github.com/codescv/minifile/releases/latest):

| Asset | Description |
|-------|-------------|
| `minifile-v1.0.17-release.apk` | Signed Android APK (`minSdk 26` / Android 8.0+, `targetSdk 35`) |
| `minifile-companion-v1.0.17.zip` | Zero-dependency Python 3 PC Companion (`cli` + `gui`) |
| `SHA256SUMS.txt` | SHA-256 checksums for release assets |

### Install PC Companion with `uv` (Recommended)

Install the `minifile` (and `minifile-companion`) command globally in an isolated environment using [`uv`](https://docs.astral.sh/uv/) — zero third-party Python dependencies required:

```bash
# Install directly from GitHub
uv tool install git+https://github.com/codescv/minifile.git

# Or install from a local clone
uv tool install /path/to/minifile
```

Run the CLI or Web/Desktop GUI from anywhere once installed:

```bash
minifile --help
minifile scan --save
minifile web
minifile gui

# Check for or apply updates directly via uv
minifile update --check
minifile update --apply
```

---

## PC Companion Quick Start

With `minifile` installed via `uv tool install`:

```bash
# Discover your phone running MiniFile -> Transfer -> FTP Server -> Start on the same Wi-Fi LAN
minifile scan --save

# List, transfer, or synchronize folders
minifile ls /Download -la
minifile put ./slides.pdf /Documents/
minifile get /DCIM/Camera ./Camera -r --resume
minifile sync ./Notes /Documents/Notes --both

# Launch the localhost Dual-Pane Web Desktop GUI
minifile web
minifile gui

# Over USB (ADB port forwarding with passive range 50000-50004):
adb forward tcp:12121 tcp:2121
for p in $(seq 50000 50004); do adb forward tcp:$p tcp:$p; done
minifile -H 127.0.0.1 -P 12121 ls /
```

Or run directly from source without installing (Python 3.10+ stdlib only):

```bash
cd companion
python3 -m minifile_companion scan --save
python3 -m minifile_companion gui
```

See [`companion/README.md`](companion/README.md) for the full CLI & Web API reference.

---

## Connecting Google Drive (`+ Add Server -> Google Drive`)

Open **Browse → Servers → `+ Add Server` → Google Drive** and pick any of the three sign-in modes:

1. **Sign in with Google Account (Android System Account — 1-tap)**
   - Uses Android's built-in `AccountManager` (`com.google` authenticator, no `GET_ACCOUNTS` permission needed).
   - **GCP Android OAuth Client registration**: Google Play Services only issues `https://www.googleapis.com/auth/drive` tokens when the APK's package name and signing SHA-1 are registered as an **Android** OAuth 2.0 Client ID in a Google Cloud project with the **Google Drive API** enabled:
     - **Package name**: `com.minifile.explorer`
     - **Certificate SHA-1** (official release & debug signing key): `F5:D0:CC:CF:DA:49:44:0B:BB:54:22:CB:88:0F:C8:C6:81:66:A6:54`
   - *Note*: Self-built APKs signed with your own key (unless you register your own key's SHA-1) or devices without Google Play Services should use **Sign in with Browser** or **Paste Token / JSON**.
2. **Sign in with Browser (OAuth 2.0 Loopback + PKCE `S256`)**
   - Works on every Android device (including de-Googled ROMs) with your own Google Cloud OAuth client:
     1. In [Google Cloud Console](https://console.cloud.google.com/), create/select a project and enable the **Google Drive API**.
     2. Under **Google Auth Platform / OAuth consent screen**, set publishing status to **"In production"** (an unverified personal app is fine for your own account; leaving it in *"Testing"* mode causes Google to expire refresh tokens after 7 days).
     3. Under **Credentials → Create Credentials → OAuth client ID**, choose **Desktop app** to obtain `YOUR_CLIENT_ID.apps.googleusercontent.com` and `YOUR_CLIENT_SECRET`.
     4. Enter both in **Sign in with Browser** and complete authorization in the device's browser.
3. **Paste Token / JSON (`minifile gdrive-auth` or `rclone`)**
   - Authorize on your PC with the companion CLI and paste the resulting JSON into the app:
     ```bash
     minifile gdrive-auth \
       --client-id YOUR_CLIENT_ID.apps.googleusercontent.com \
       --client-secret YOUR_CLIENT_SECRET
     ```
   - Also accepts `rclone authorize "drive"` JSON output, an `rclone.conf` `[remote]` block, or a bare OAuth2 access token.

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
