# MiniFile — Product, UX/UI & Feature Specification

> **Status:** v1.0 spec (authoritative for the first release).
> **Companion doc:** [ARCHITECTURE.md](./ARCHITECTURE.md) (modules, data models, interfaces, verification plan).
> **Inspiration:** FE File Explorer (two-pane transfers, favorites-first home), Android native Files / DocumentsUI (breadcrumbs, SAF volumes, Material 3 restraint).

---

## 0. Product Summary

**MiniFile** is a minimalist, privacy-respecting Android file manager (package `com.minifile.explorer`, `minSdk 26`, `targetSdk 35`) plus a **PC Companion** (pure-Python 3 CLI + local Web GUI) that talks to the phone over the app's **built-in FTP server**. It has no ads, no analytics, no cloud account, and no network access except the LAN FTP server the user explicitly starts and an HTTPS update check against GitHub Releases (`codescv/minifile`, §11) that can be switched off.

### 0.1 Design Principles

| # | Principle | Consequence |
|---|-----------|-------------|
| P1 | **One screen, one job** | Each tab does one thing. No nested settings labyrinths. |
| P2 | **Content first** | File list is the hero; chrome (app bar, path bar, nav bar) is thin and recedes while scrolling. |
| P3 | **Direct manipulation** | Long-press → multi-select; actions appear in a contextual bottom action bar, not hidden menus. |
| P4 | **Zero surprise destruction** | Delete, overwrite, move-out-of-SAF always confirm; operations are cancellable and report per-item results. |
| P5 | **Works everywhere** | Same UI for `java.io.File` paths and SAF `DocumentFile` trees; the user never needs to know which backend is in use. |
| P6 | **Offline, local, yours** | Only permission dialogs the feature actually needs; FTP server is opt-in, foreground-notified, and stops when asked. |

### 0.2 Target Users
- Power users managing `Android/data`, OTG drives, and `.nomedia` hygiene.
- Developers copying logs/configs between PC and phone over Wi-Fi without a cable or vendor app.
- Minimalists who want a 3 MB file manager with a text editor and zip support, nothing else.

### 0.3 Non-Goals (v1.0)
- Cloud providers (Drive, SMB, WebDAV client), root access, media players, image editing, dark-pattern "cleaners".
- Multi-window drag-and-drop between two in-app panes on phone (we use clipboard bar + Favorites picker instead).

---

## 1. Information Architecture & Navigation

### 1.1 Top-Level Navigation (Material 3 `NavigationBar`, 4 destinations)

```
┌──────────────────────────────────────────────────┐
│  MiniFile                               🔍  ⋮    │  ← TopAppBar (title / search / overflow)
│  Internal › Download › Telegram                  │  ← Breadcrumb path bar (scrollable)
│──────────────────────────────────────────────────│
│  📁 Images                     12 items  Oct 29  │
│  📁 Documents  🚫              3 items   Oct 20  │  ← 🚫 = folder contains .nomedia
│  📄 notes.md                   4.2 KB    Oct 30  │
│  🗜 backup.zip                 18 MB     Oct 12  │
│  ...                                             │
│                                           (+)    │  ← FAB: New folder / New file
│──────────────────────────────────────────────────│
│  [Browse]   [Favorites]   [Wireless]   [Tools]   │  ← NavigationBar
└──────────────────────────────────────────────────┘
```

| Tab | Icon | Purpose |
|-----|------|---------|
| **Browse** | `Folder` | Storage home (volumes & App Data entries) → directory browser. |
| **Favorites** | `Star` | Bookmarked folders; manage (rename/reorder/remove); one tap opens. |
| **Wireless** | `Wifi` | FTP server control panel: start/stop, LAN URL, settings, live log. |
| **Tools** | `Build` | Settings & utilities: hidden files, theme, default sort, Apps Inspector, archive tasks, About. |

Navigation is **single-Activity, state-driven** (see ARCHITECTURE §3.4). Back behavior:
1. If multi-select active → exit selection.
2. If search active → close search.
3. If inside a directory → go to parent (until the volume root), then to Storage Home.
4. On Storage Home / other tabs → system back (exit).

Tab state is retained when switching tabs (Browse remembers its current directory and scroll position).

### 1.2 Storage Home (Browse tab root)

Material 3 cards/list sections:

1. **Storage** — one row per volume from `StorageManager.storageVolumes`:
   - `Internal Storage` (primary) — linear progress bar "58.2 GB free of 128 GB".
   - `SD Card` / `USB Drive (SanDisk)` — removable volumes; show `Mount` button if SAF access not yet granted (see §6).
   - Unmounted/ejected volumes disappear automatically (`ACTION_MEDIA_MOUNTED/UNMOUNTED` receiver).
2. **App Data** — `Android/data`, `Android/obb` (SAF tree entries, with a lock badge until granted) and **Installed Apps** (Apps Inspector, §6.3).
3. **Mounted folders** — every SAF tree the user has granted via **"Mount a folder…"** (generic `ACTION_OPEN_DOCUMENT_TREE`; any folder the system picker allows becomes a browsable volume with full read/write). This is the **fallback when All-files access is denied** on API 30+ (the picker refuses the primary root and `Download` root, but allows e.g. `Documents`, `Pictures/MyFolder`, or an SD/USB root). Rows show display name, provider, and an *Unmount* (revoke) action in the row menu.
4. **Quick Access** — `Download`, `DCIM`, `Pictures`, `Documents`, `Movies`, `Music` (standard public dirs).
5. **Recent Favorites** — up to 4 most recently used favorites (chips), "See all →" to Favorites tab.

### 1.3 Directory Browser

**TopAppBar**
- Title = current folder name (Storage Home shows "MiniFile").
- Actions: `Search` (toggles inline filter field), overflow `⋮`: *Sort…*, *View: List/Grid*, *Show hidden files* (checkbox), *Select all*, *New folder*, *New file*, *Add to Favorites / Remove from Favorites* (star state), *Toggle .nomedia*, *Properties* (of current folder), *Refresh*.

**Breadcrumb path bar** (`LazyRow` of chips separated by `›`)
- Tapping a crumb jumps to that ancestor. Long-press a crumb → copy path.
- Root crumb shows the volume name (`Internal`, `SD Card`, `USB`, `Android/data`).
- For SAF trees the breadcrumb shows display names of `DocumentFile` ancestors within the granted tree.
- Horizontal auto-scroll keeps the last crumb visible.

**List row** (56 dp, `ListItem`)
- Leading: icon by type (folder, image, video, audio, document, archive, apk, code/text, generic). Images/videos may show a thumbnail (optional, Coil) — fallback icon if loading fails.
- Headline: name (single line, middle-ellipsis).
- Supporting: `size · modified` for files; `N items · modified` for folders (item count lazily computed; "—" for SAF trees where it is expensive).
- Trailing: badges `🚫` (`.nomedia`), `★` (favorite), `🔒` (read-only), then a `⋮` per-row overflow (Open with, Rename, Copy, Cut, Delete, Properties, Compress, Add to favorites [folders], Toggle .nomedia [folders]).

**Grid cell** (adaptive 96–120 dp)
- Large icon/thumbnail, name below (2 lines max), small badges top-right.

**Empty states**
- Empty folder: outline icon + "Nothing here yet" + hint "Tap + to create a folder or file".
- No permission: illustration + "Allow MiniFile to manage storage" + `Grant access` button.
- SAF not granted: "Tap *Mount* to choose this folder in the system picker".

### 1.4 Sorting, View Mode, Search, Hidden Files

| Control | Behavior | Persistence |
|---------|----------|-------------|
| **Sort** | Bottom sheet with radio: *Name, Size, Date modified, Type*; toggle *Ascending/Descending*; switch *Folders first* (default on). Name sort is natural/case-insensitive (`file2` < `file10`). | Global default in Tools; per-session override remembered until app restart. |
| **View** | List ⇄ Grid toggle (icon button in overflow and in Sort sheet). | Global (`DataStore/SharedPreferences`). |
| **Search** | Inline filter field in the app bar; filters the *current directory* instantly (case-insensitive substring) as you type. Chip "Search subfolders" switches to recursive search (coroutine, cancellable, streaming results with relative paths, max depth 12, respects the hidden-files preference; shows a linear progress indicator). | Not persisted. |
| **Hidden files** | Toggle shows/hides entries whose name starts with `.`. Appears in overflow menu, in Tools, and as a chip in the Sort sheet. When hidden files are hidden, `.nomedia` is still *detected* for badges. | Persisted (`show_hidden`, default **off**). |

### 1.5 Multi-Select Mode

- Enter: long-press any row, or tap "Select" in overflow, or tap the leading icon (icon flips to a checkbox).
- App bar transforms to a **contextual bar**: `✕ 3 selected` · *Select all* · *Invert*.
- **Bottom action bar** (M3 `BottomAppBar`, fixed actions): **Copy · Cut · Delete · Share · More ⋮** (More → Rename [single], Compress to ZIP, Extract [single zip], Properties, Copy/Move to Favorite…, Add to Favorites [folders]).
- Tapping a row toggles selection; tapping a folder in selection mode toggles it (does not navigate). Range-select: long-press a second item to select the range between it and the last toggled item.
- Selection survives sort/filter changes; cleared on directory change (with a snackbar "Selection cleared").

---

## 2. Core File Operations

All operations run in a `FileOperationService` (foreground service with progress notification for long tasks), are cancellable, and report per-item results. UI shows a **progress bottom sheet** (current file, bytes/sec, overall %) for operations > 500 ms.

| Operation | Entry points | UX details |
|-----------|-------------|------------|
| **Copy** | Selection bar, row menu | Puts items on the **Clipboard** (mode = COPY) and exits selection. Shows the **Clipboard Bar** (§2.1). |
| **Cut / Move** | Selection bar, row menu | Same as Copy with mode = MOVE. Cut items are rendered at 50% alpha in their source dir until pasted/cancelled. |
| **Paste** | Clipboard Bar "Paste here", or FAB long-press | Pastes into the current directory. Same-dir paste in COPY mode auto-renames (`name (1).ext`). Cross-backend (File ⇄ SAF) copies via streams; move is copy+delete with verification. **Conflict dialog** per conflict: *Skip · Overwrite · Keep both · Apply to all*. |
| **Rename** | Row menu, selection (single) | Dialog with name field prefilled; extension *not* selected by default for files; validates illegal chars (`/`, NUL; on FAT volumes also `\ : * ? " < > |`), empty, duplicates. |
| **Delete** | Selection bar, row menu | **Confirmation dialog**: "Delete 3 items? This can't be undone." lists first 5 names + "and N more"; folder counts items recursively. Red destructive button. Then runs recursively with progress. |
| **Create Folder** | FAB menu, overflow | Dialog "New folder" (default name `New folder`, auto-unique). |
| **Create File** | FAB menu, overflow | Dialog "New file" with name field (default `untitled.txt`) and an *Open in editor* checkbox (default on). Creates an empty 0-byte file. |
| **Open** | Tap file | Text-like → built-in editor (§3.2); zip → Archive preview (§3.3); apk → Properties with *Install* via `ACTION_VIEW` (FileProvider); else `ACTION_VIEW` chooser with correct MIME via `FileProvider` (File) or the SAF URI directly. "Open with…" always available in row menu. |
| **Share** | Selection bar | `ACTION_SEND(_MULTIPLE)` with FileProvider/SAF URIs. |
| **Properties / Details** | Row menu, overflow (current folder) | Dialog/bottom sheet (§2.2). |

### 2.1 Clipboard Bar (floating "Paste Here")

When the clipboard is non-empty, a persistent **floating bar** appears above the NavigationBar on the Browse tab (and as a compact chip on other tabs):

```
┌────────────────────────────────────────────────────────┐
│ 📋 Move 3 items        [To Favorite ▾]  [Paste here] ✕ │
└────────────────────────────────────────────────────────┘
```
- **Paste here** — pastes into the directory currently shown (disabled on Storage Home and in read-only locations, with tooltip explaining why).
- **To Favorite ▾** — opens the **Copy/Move to Favorite** bottom sheet (§7.2) — one-tap destination choice without navigating.
- **✕** — clears the clipboard (cut items return to normal alpha).
- Tapping the label shows the list of clipboard items.
- The clipboard persists across tabs and directory changes but not across process death.

### 2.2 Properties / Details Dialog

Fields:
- Name (editable inline → rename), Type/MIME, Size (human + exact bytes; folders: computed asynchronously with item/folder counts and a spinner), Location (full path or SAF tree URI + display path; tap to copy), Last modified (local time, ISO on long-press), Readable/Writable/Hidden flags, POSIX permissions (`rwxr-xr-x` + owner when `java.io.File` backend; "—" on SAF), Symlink target (if any).
- **Checksums** section (files only): `MD5` and `SHA-256` rows with *Compute* button each (streamed, cancellable, shows progress for > 10 MB); result is monospaced with *Copy* icon; *Verify…* lets user paste an expected hash → ✓/✗.
- Folder extras: *Contains .nomedia* switch (same action as §3.4), *Favorite* star toggle.

---

## 3. Personalized Power Features

### 3.1 Show / Hide Hidden Files
- Preference `show_hidden` (default off), persisted immediately.
- Quick toggles: overflow menu checkbox in Browser, chip in Sort sheet, switch in Tools.
- When off: entries starting with `.` are excluded from listings, search results and item counts, *except* the badge logic which still probes `.nomedia` existence.
- When on: hidden entries render with the name at 70% alpha.

### 3.2 Built-in Text Viewer & Editor

**Opens for:** `.txt .md .json .xml .yml .yaml .log .csv .ini .conf .cfg .properties .env .py .sh .bat .js .ts .kt .java .c .cpp .h .html .css .gradle .toml .nomedia .gitignore` (and any file with `text/*` MIME or size ≤ 1 MB whose first 8 KB contains no NUL bytes). Any file can be force-opened via "Open as text".

**Screen layout**
```
┌──────────────────────────────────────────────┐
│ ←  notes.md ●            🔍  ↶ ↷  💾  ⋮      │  ● = unsaved
│──────────────────────────────────────────────│
│  1 │ # Title                                 │
│  2 │ Some text…                              │
│  3 │                                         │
│ ...│                                         │
│──────────────────────────────────────────────│
│ UTF-8 · LF · Ln 2, Col 5 · 4.2 KB            │  ← status bar (tap encoding to change)
└──────────────────────────────────────────────┘
```
- **Line numbers** gutter (toggleable), monospace font, horizontal scroll (wrap toggle), font size +/- in overflow (persisted).
- **Find / Replace** bar: case-sensitive toggle, next/prev, match count, replace / replace all.
- **Encoding**: auto-detect BOM (UTF-8/16LE/16BE); default UTF-8; chooser: UTF-8, UTF-16LE, UTF-16BE, ISO-8859-1, Windows-1252, GBK, Shift_JIS. Re-opening with a different encoding re-decodes from disk. Line endings detected (LF/CRLF) and preserved on save; selectable.
- **Save**: writes to a temp file in the same directory then atomically renames (File backend) or streams to the `DocumentFile` (SAF). Save As… offers filename + location (current folder or a Favorite). Read-only files prompt Save As.
- **Large files**: > 2 MB opens in read-only *viewer* mode (chunked loading) with a "Edit anyway (may be slow)" option; > 20 MB viewer only.
- Unsaved-changes guard on back: *Save · Discard · Cancel*. Undo/redo stack (≥ 100 steps).
- Syntax highlighting is **out of scope** for v1 (keeps the editor tiny); a simple JSON pretty-print action is included for `.json`.

### 3.3 Archive Compress & Decompress (ZIP)

**Compress**
- From selection (1..N files/folders) → *Compress to ZIP* → dialog: archive name (default: single item's name or parent folder name + `.zip`, auto-unique), destination (*current folder* or pick a Favorite), compression level (Store / Fast / Normal [default] / Best).
- Preserves relative directory structure, modification times, and UTF-8 names (`ZipOutputStream` with UTF-8 flag). Empty folders are preserved as directory entries. Symlinks are stored as their targets (never followed outside the selection root).
- Runs in `FileOperationService` with progress; result snackbar "backup.zip created · Open".

**Extract**
- Tap a `.zip` → **Archive preview** screen: tree listing (name, size, compressed size, modified) with *Extract all* and *Extract here* buttons; long-press entries → *Extract selected*. Also supports `.jar`/`.apk` preview (read-only).
- Extract dialog: destination (*current folder*, *new folder named after archive* [default], or a Favorite), *Overwrite existing* switch.
- Password-protected zips are detected and reported as unsupported (v1).
- **Security (mandatory):** zip-slip protection — every entry's canonical destination path must start with the canonical destination directory path + separator; entries with absolute paths, `..` segments, drive letters, or NUL bytes are rejected and the operation reports them as skipped (**"3 unsafe entries skipped"**). Zip-bomb guard: abort if total uncompressed size > 20× archive size *and* > 2 GB, or entry count > 200 000 (user can override once with a warning).

### 3.4 `.nomedia` Manager
- Every **folder** row menu, the Browser overflow (current folder), the Properties sheet, and the selection "More" menu expose **"Hide from gallery (.nomedia)"** as a toggle.
- On: creates an empty `.nomedia` file in that folder. Off: deletes it. Both trigger `MediaScannerConnection.scanFile` on the folder (File backend) so the gallery updates promptly.
- **Badge:** folders containing `.nomedia` render a small `🚫` (`VisibilityOff`) badge next to the name in list and grid, regardless of the hidden-files preference. Detection is cheap (`File(dir, ".nomedia").exists()`; for SAF, a `findFile(".nomedia")` on the listed children — done during listing, no extra round trip).
- Works for SAF trees (e.g. USB, `Android/data`) via `DocumentFile.createFile("application/octet-stream", ".nomedia")`.
- Tools → **.nomedia Overview**: lists all folders with `.nomedia` under a chosen root (scan), each with a toggle — bulk hygiene.

---

## 4. Wireless Transfer — Built-in FTP Server

### 4.1 Wireless Tab UI

```
┌──────────────────────────────────────────────┐
│ Wireless Transfer                            │
│                                              │
│   ┌──────────────────────────────────────┐   │
│   │  ● Running                           │   │
│   │  ftp://192.168.1.42:2121             │   │
│   │  [Copy URL]   [Share]   [Stop]       │   │
│   └──────────────────────────────────────┘   │
│                                              │
│  Settings                                    │
│   Port                           2121      › │
│   Root folder                 Internal     › │
│   Authentication           Anonymous       › │
│   Read-only                     ◯ off        │
│   Keep screen on while running  ● on         │
│   Passive ports          50000–50100       › │
│                                              │
│  Activity                        [Clear]     │
│   20:14:03  192.168.1.10 connected           │
│   20:14:05  LIST /Download                   │
│   20:14:09  RETR /Download/notes.md (4.2 KB) │
│   20:14:12  STOR /Download/photo.jpg (2.1 MB)│
└──────────────────────────────────────────────┘
```

- **Status card**: Stopped (primary `Start` button) / Starting / Running (green dot, URL in large monospace text; also shows all IPv4 LAN addresses when multiple interfaces, e.g. Wi-Fi + hotspot; "No Wi-Fi — server reachable only via hotspot/USB tethering" banner if no address).
- Start requests `POST_NOTIFICATIONS` (API 33+) once; the server runs as a **foreground service** with a persistent notification "MiniFile FTP · ftp://192.168.1.42:2121 · 1 client" and a **Stop** action; stopping from the notification or the tab tears down all sessions.
- **Settings** (persisted):
  - Port (1024–65535, default **2121**; validation + "port in use" error on start).
  - Root folder: picker over volumes/Favorites (File path or SAF tree) — the FTP virtual root `/` maps to it; clients can never escape it.
  - Authentication: *Anonymous* (any user/pass accepted) or *Username + Password* (password stored in app-private `SharedPreferences` (`MODE_PRIVATE`) — `security-crypto` is deprecated and not available offline; the trade-off is acceptable because the password only protects a LAN FTP session and app-private storage is inaccessible to other apps on non-rooted devices; shown masked with reveal).
  - Read-only toggle: write commands (`STOR, DELE, MKD, RMD, RNFR/RNTO, APPE`) return `550 Read-only server`.
  - Passive port range (default 50000–50100), UTF-8 (always on), idle timeout (default 300 s), keep-screen-on. *Auto-start on Wi-Fi* is **dropped from v1** (starting a foreground service from a background `NetworkCallback` is blocked on API 31+); the row in the mock-up above is replaced by **"Stopped by system"** handling: if Android stops the service (`Service.onTimeout`, low memory), the app posts a "MiniFile FTP was stopped — tap to restart" notification.
- **Live log**: last 500 entries (ring buffer) with timestamp, client IP, command / transfer summary with byte count and duration; connection count chip; export log to a text file.
- QR code of the URL is a *nice-to-have* (only if implementable without a dependency — otherwise omit).

### 4.2 Server Behavior (summary; protocol detail in ARCHITECTURE §5)
- Pure Kotlin RFC 959 + RFC 3659 (`SIZE, MDTM, MLST, MLSD, REST`) + RFC 2389 (`FEAT, OPTS`) + RFC 2428 (`EPSV`, `EPRT`) implementation; UTF-8 paths; `PASV`/`EPSV` passive mode (default for all modern clients) and active `PORT` mode.
- Binds `0.0.0.0:<port>`; `PASV` advertises the IP the control connection arrived on (so hotspot and Wi-Fi both work).
- Multiple concurrent clients (default max 8), each on its own coroutine/thread; transfers stream with 64 KB buffers.
- Works on both backends: File root (fast path with `RandomAccessFile` for `REST`) and SAF root (`ContentResolver` streams; `REST` for downloads via skip).

### 4.3 PC Companion — CLI

Install-free: `cd companion && python3 -m minifile_companion --help` (also a `minifile.py` shim). Zero third-party dependencies (`ftplib`, `argparse`, `http.server`, `json`, `zipfile`, `concurrent.futures`).

Global options: `--host/-H` (or env `MINIFILE_HOST`), `--port/-P` (default 2121), `--user/-u`, `--password/-p`, `--timeout`, `--json` (machine-readable output), `--verbose`. A config file `~/.config/minifile/config.json` stores the last used host/port/user and favorites.

| Command | Synopsis | Behavior |
|---------|----------|----------|
| `scan` | `scan [--subnet 192.168.1.0/24] [--port 2121] [--timeout 0.3]` | Concurrent TCP connect scan of the local /24 (auto-detected from the default route) for FTP banners containing `MiniFile`; prints host table; `--save` sets it as default host. |
| `ls` | `ls [remote_path] [-l] [-a] [-R]` | `MLSD` (fallback `LIST`); columns: type, size, modified, name; `-a` includes dotfiles; `-R` recursive tree. |
| `tree` | `tree [remote_path] [-L depth]` | ASCII tree. |
| `get` | `get <remote> [local] [-r] [--resume] [--overwrite|--skip]` | Download file or directory (`-r`), progress bar (bytes, speed, ETA), `REST` resume when supported. |
| `put` | `put <local> [remote] [-r] [--overwrite|--skip]` | Upload file or directory, creating remote dirs; preserves structure. |
| `sync` | `sync <local_dir> <remote_dir> --to-phone|--to-pc|--both [--delete] [--dry-run] [--exclude GLOB]…` | Compares by size + mtime (`MDTM`; 2 s tolerance for FAT); copies newer; `--delete` mirrors deletions (asks for confirmation unless `--yes`); `--both` = newest wins, never deletes. Prints a plan first with `--dry-run`. |
| `cat` | `cat <remote_file> [--encoding utf-8] [-n]` | Streams a text file to stdout (`-n` line numbers). |
| `edit` | `edit <remote_file> [--editor $EDITOR]` | Downloads to a temp file, opens `$EDITOR` (fallback `nano`/`notepad`), re-uploads if changed (with backup `name.bak` optional via `--backup`). |
| `mkdir` | `mkdir <remote_dir> [-p]` | Creates directories (`-p` recursive). |
| `rm` | `rm <remote_path>… [-r] [-f]` | Deletes files/dirs (`-r` recursive); confirmation prompt unless `-f`. |
| `mv` | `mv <src> <dst>` | `RNFR`/`RNTO`. |
| `nomedia` | `nomedia <remote_dir> [--on|--off|--status]` | Creates/removes `.nomedia`, or prints status. |
| `shell` | `shell` | Interactive REPL (`cd, pwd, ls, lcd, lls, get, put, …`, tab completion via `readline` when available). |
| `gui` / `web` | `gui [--port 8765] [--no-browser]` | Starts the Web Desktop Companion (§4.4) and opens the browser. `gui` is an alias for `web` (a native Tkinter window is **optional**: only when `tkinter` is importable; otherwise the command transparently launches the web UI — this machine has no `tkinter`). |
| `update` | `update [--check] [--pre] [--apply] [--dest DIR]` | Checks GitHub Releases for a newer companion; downloads, verifies and stages it (§11.2). `--check` only reports (exit `10` when an update exists). |

Exit codes: `0` ok, `1` generic error, `2` usage, `3` connection/auth failure, `4` partial failure (some items failed; listed on stderr). All commands accept `--json` for scripting.

### 4.4 PC Companion — Web Desktop Companion (GUI)

`python3 -m minifile_companion gui` → serves `http://127.0.0.1:8765` (localhost only, random token in the URL to prevent cross-site access from other local pages) and opens the default browser. Single-page app in vanilla HTML/CSS/JS (no build step, no CDN — works offline).

```
┌───────────────────────────────────────────────────────────────────────┐
│ MiniFile Companion      Phone: 192.168.1.42:2121 ● Connected  [Scan ▾]│
│───────────────────────────────┬───────────────────────────────────────│
│ 💻 This PC   ~/Downloads      │ 📱 Phone   /Download                  │
│ [↑] [⌂] [New folder] [👁 .*]  │ [↑] [⌂] [New folder] [👁 .*] [★ Fav▾]│
│  📁 invoices                  │  📁 Telegram            🚫            │
│  📄 report.pdf      1.2 MB    │  📄 notes.md            4.2 KB        │
│  📄 photo.jpg       2.1 MB    │  🗜 backup.zip          18 MB         │
│                               │                                       │
│      ⟶  Upload selected       │      ⟵  Download selected             │
│───────────────────────────────┴───────────────────────────────────────│
│ Transfers: photo.jpg → /Download  ▓▓▓▓▓▓▓░░░ 68%  3.4 MB/s           │
└───────────────────────────────────────────────────────────────────────┘
```

Features:
- **Two panes** (local PC filesystem ⇄ phone over FTP), each with breadcrumbs, sort by column headers, instant filter, hidden-files toggle, multi-select (click/shift/ctrl), context menu (Open/Download, Upload, Rename, Delete, New folder, Properties, Toggle .nomedia [phone folders], Edit text).
- **Drag-and-drop**: drag files from the OS file manager onto the phone pane to upload; drag between panes to copy in that direction. Directory drops are supported where the browser exposes `webkitGetAsEntry`.
- **One-click download** (file streams through the local server to the browser as a download; folders are zipped on the fly), **upload** via drop zone or file picker.
- **Inline text editor modal** (textarea with line numbers, find, encoding select, Save) for phone or PC text files.
- **Favorites quick-jump** dropdown (phone paths; stored in the local config file) + "Add current folder".
- **Transfer queue** panel with progress, speed, cancel, retry; errors surfaced inline.
- **Scan** button runs the LAN discovery and lets the user pick the phone; connection settings dialog (host, port, user, password, remember).
- Theme follows OS `prefers-color-scheme`; keyboard shortcuts (`F2` rename, `Del` delete, `Ctrl+A`, `Backspace` up, `Enter` open).

Local HTTP API (JSON, consumed by the page; also handy for testing) is specified in ARCHITECTURE §6.3.

---

## 5. Favorites (Bookmarks)

### 5.1 Model & Management
- A favorite = `{ id, name, target (file path or SAF tree URI + relative document id), backend, icon, createdAt, lastUsedAt, order }`.
- **Add**: star icon in Browser overflow/app bar (current folder), folder row menu, Properties, selection "More". Dialog proposes the folder name as the display name (editable) and an optional icon color.
- **Favorites tab**: reorderable list (drag handle), swipe-to-remove with undo snackbar, long-press → Rename / Change icon / Remove / Properties. Row shows name, subtitle path, badges (`🚫` if `.nomedia`, `🔌` for removable/SAF locations, `⚠` if currently unavailable, e.g. USB unplugged — tap explains and offers *Remove*).
- Tapping a favorite opens it in the Browse tab (switching tabs). Default favorites seeded on first launch: `Download`, `DCIM/Camera`, `Documents` (removable by user).
- Favorites back up/restore as JSON via Tools (export/import), and are exposed to the PC Companion as quick-jump entries when the FTP root contains them (companion keeps its own list; see §4.4).

### 5.2 Copy / Move Directly to Favorite — One-Tap Destination Picker

Available **whenever items are selected or on the clipboard**:

1. **From the selection bottom bar** → *More* → **Copy to Favorite…** / **Move to Favorite…**
2. **From the Clipboard Bar** → **To Favorite ▾**
3. From a file row menu → *Copy to…* / *Move to…* (same sheet, with the single item).

**Bottom sheet** (`ModalBottomSheet`):
```
┌──────────────────────────────────────────┐
│ Move 3 items to…                         │
│  ★ Download              /storage/…/Download   │
│  ★ Telegram Backup       /storage/…/Telegram   │
│  ★ USB Docs  🔌          USB Drive/Docs        │
│  ★ Obsidian Vault        /storage/…/Obsidian   │
│  ─────────────────────────────────────── │
│  📁 Choose another folder…               │  ← opens a mini folder picker (browse mode)
│  ＋ Add current folder to Favorites      │
└──────────────────────────────────────────┘
```
- One tap on a favorite → operation starts immediately into that folder (no further confirmation; conflicts and errors are handled via the standard conflict dialog/progress sheet). Snackbar: "Moved 3 items to Telegram Backup · **Open**".
- Favorites that equal the source directory or are inside a selected folder are disabled with a reason ("Same folder" / "Can't move a folder into itself").
- Unavailable favorites (unmounted USB) are greyed with `⚠`.
- The sheet also supports **Segmented toggle `Copy | Move`** at the top so the user can change intent at the last moment when launched from the selection bar.
- After a successful operation, `lastUsedAt` is updated so the most used favorites bubble to the top of the sheet (favorites order = user order, sheet order = recency, configurable in Tools).

---

## 6. App Data & External / USB OTG Storage

### 6.1 Permission Model (Android 8 – 15)

| API | Primary storage | `Android/data` & `Android/obb` | SD card / USB OTG |
|-----|-----------------|-------------------------------|-------------------|
| 26–28 | `READ/WRITE_EXTERNAL_STORAGE` runtime permission; `java.io.File`. | Plain `File` access works. | Secondary volumes: read via `File` where allowed; write via SAF tree (`ACTION_OPEN_DOCUMENT_TREE`). |
| 29 | Opt out of scoped storage (`requestLegacyExternalStorage=true`) + runtime permission. | `File` access works (legacy). | SAF tree. |
| 30–32 | `MANAGE_EXTERNAL_STORAGE` ("All files access") via `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` settings intent; `java.io.File` everywhere else. | **Not** accessible via `File`. Use `ACTION_OPEN_DOCUMENT_TREE` with `EXTRA_INITIAL_URI` = `content://com.android.externalstorage.documents/document/primary%3AAndroid%2Fdata` (and `…%2Fobb`). On API 30–32 the picker allows selecting these dirs directly. | `File` with All-files access on many devices; SAF tree always works. |
| 33–35 | `MANAGE_EXTERNAL_STORAGE`. | Picker blocks `Android/data` root on 33+; **per-package subfolders** can still be granted on many OEM builds via `EXTRA_INITIAL_URI` = `primary:Android/data/<pkg>` (MiniFile's Apps Inspector constructs this URI per app). If the system refuses, show a clear explanation and offer the ADB/"Shizuku-free" alternatives list (just an info sheet; no root). `Android/obb` same rules. | SAF tree (`ACTION_OPEN_DOCUMENT_TREE` with `StorageVolume.createOpenDocumentTreeIntent()`); `File` works for reads when All-files access granted. |

UX:
- First launch → **Storage access onboarding** card (not a blocking wall): explains why All-files access is needed, `Grant` button opens the settings screen, and a secondary **"Mount a folder instead…"** button runs the generic SAF flow; the app remains fully usable for SAF-mounted locations (Storage Home → *Mounted folders*, §1.2) without All-files access.
- Each SAF location shows **Mount** (grant) on first use; the app calls `takePersistableUriPermission` and remembers the grant (`PersistedTree` table). Revoked grants are detected (`SecurityException`) → row shows "Access lost · Mount again".
- Tools → *Storage access* page lists all granted trees with *Revoke* buttons.

### 6.2 Volumes & USB OTG
- `StorageManager.storageVolumes` is the source of truth for the volume list: description (`getDescription`), state, `isPrimary`, `isRemovable`, `uuid`, directory (`getDirectory()` on API 30+, reflection-free fallback `Environment.getExternalStorageDirectory()` for primary and `/storage/<UUID>` for others).
- For each non-primary volume: if `File(dir).canRead()` with All-files access → File backend (fast); writing is attempted via File and falls back to the persisted SAF tree if `EACCES`. If no read access → SAF backend via `createOpenDocumentTreeIntent()` (API 29+) or `ACTION_OPEN_DOCUMENT_TREE` (API 26–28).
- Hot-plug: `BroadcastReceiver` for `ACTION_MEDIA_MOUNTED`, `ACTION_MEDIA_UNMOUNTED`, `ACTION_MEDIA_EJECT`, `ACTION_MEDIA_BAD_REMOVAL` (data scheme `file`) + `UsbManager.ACTION_USB_DEVICE_ATTACHED/DETACHED` refreshes the Storage Home; browsing an unplugged volume shows an "Unavailable" empty state and navigates back to Home.
- Volume rows show free/total space via `StatFs` (File) or `—` (SAF only).

### 6.3 App Data Access & Installed Apps Inspector
- **Storage Home → App Data**: `Android/data` and `Android/obb` entries open the SAF mount flow described above (or File browsing on API ≤ 29 / rooted-free legacy). Once mounted, they browse like any directory (copy out, delete caches, toggle `.nomedia`, etc.).
- **Installed Apps** (Tools tab and Storage Home): list of installed packages (`PackageManager.getInstalledApplications`, requires `QUERY_ALL_PACKAGES` with Play-policy note, or `<queries>`; we declare `QUERY_ALL_PACKAGES` since we are a file manager) with icon, label, package, version, *User/System* chip, APK size; filter/search; sort by name/size/updated.
  - App detail sheet: package, version, min/target SDK, install/update time, source dir, data dir path (shown, not browsable), external data dir `Android/data/<pkg>` size (computed when accessible) with **Open external data** (uses `EXTRA_INITIAL_URI` per-package trick), **Open obb**, **Export APK** (copies base APK + split APKs `.apks` zip to a chosen folder/Favorite via `FileOperationService`), **App info** (system settings), **Open shared folder** if the app exposes one in `Android/media/<pkg>`.
- Nothing here requires root; features that the OS denies are explained rather than hidden.

---

## 7. Tools Tab

Sections (M3 settings list):
- **Appearance**: Theme (System/Light/Dark), Dynamic color (API 31+), Default view (List/Grid), Default sort, Show hidden files, Show thumbnails, Folders first.
- **Behavior**: Confirm before delete (always on; option only for *also confirm overwrite*), Open text files in built-in editor, Editor font size, Remember last folder.
- **Storage access**: All-files access status + button; granted SAF trees list with revoke; Installed Apps Inspector; Volumes diagnostics (paths, states).
- **Utilities**: `.nomedia` Overview, Checksum calculator (pick any file), Archive tasks (history of last 20 operations with retry), Favorites export/import (JSON), FTP log export.
- **Updates** (检查更新): current/latest version, release notes, *Check now*, *Update & Install* with progress, *Check automatically (every 6 h)* switch, *Include pre-releases* switch — see §11.1.
- **About**: version, licenses (none third-party except AndroidX/Kotlin/kotlinx-serialization/optional Coil), privacy statement ("No network access except the FTP server you start and the optional GitHub update check"), link to `github.com/codescv/minifile`.

---

## 8. Visual Design System

- **Material 3** with dynamic color on API 31+ and a fallback palette (seed `#3366CC`; neutral surfaces). Light & dark themes.
- **Typography**: M3 defaults; file names `bodyLarge`, metadata `bodySmall` onSurfaceVariant; editor and hashes use `FontFamily.Monospace`.
- **Shape**: `large` rounded cards (16 dp) on Home; `extraLarge` bottom sheets; FAB `medium`.
- **Icons**: `material-icons-extended`: `Folder, InsertDriveFile, Image, Movie, MusicNote, Description, Archive, Android, Code, Star/StarBorder, VisibilityOff, Usb, SdCard, Smartphone, Wifi, Build, ContentCopy, ContentCut, ContentPaste, DriveFileRenameOutline, Delete, CreateNewFolder, NoteAdd, Info, Search, Sort, GridView, ViewList, Check, Close`.
- **Motion**: `AnimatedContent` directory transitions (slide/fade ≤ 200 ms), selection checkbox morph, shared-axis between tabs. Respect "Remove animations" accessibility setting.
- **Density**: list rows 56 dp; touch targets ≥ 48 dp; breadcrumbs 32 dp chips.
- **Edge-to-edge** (mandatory with `targetSdk 35`): `enableEdgeToEdge()` in `MainActivity`; every screen uses `Scaffold` with `contentWindowInsets` and the `NavigationBar`/bottom bars/FAB/clipboard bar apply `WindowInsets.navigationBars` + `ime` padding so nothing draws under system bars; `LazyColumn` content padding includes the bottom inset.
- **Accessibility**: content descriptions for all icons/badges ("Hidden from gallery", "Favorite"), TalkBack announces selection counts, large-text tested at 200%, color contrast AA, keyboard navigation in the Web Companion.
- **Localization**: all strings in `strings.xml` (English base); RTL-safe layouts; file sizes via `Formatter.formatFileSize`; dates via `DateUtils` relative + full.

---

## 9. Key User Flows (acceptance scenarios)

1. **Move downloads to USB in one tap**: Browse → Download → long-press 3 files → Cut → *To Favorite ▾* → "USB Docs" → progress → snackbar "Moved 3 items · Open" → files on USB; source entries gone.
2. **Hide a folder from the gallery**: Browse → `Pictures/Screenshots` → row `⋮` → *Hide from gallery* → `🚫` badge appears; Google Photos stops showing those images after rescan; toggle again removes it.
3. **Edit a config over Wi-Fi**: Wireless → Start → PC: `python3 -m minifile_companion scan` → `edit /Documents/app.ini` → saved back; phone editor shows the new content.
4. **Zip & extract safely**: select folder → Compress → `project.zip` → tap zip → Extract to new folder; a crafted zip containing `../../evil.sh` results in "1 unsafe entry skipped" and no file outside the destination.
5. **Inspect app data**: Tools → Installed Apps → "Some Game" → *Open external data* → SAF picker lands in `Android/data/com.some.game` → *Use this folder* → browse and copy saves out; *Export APK* to Favorite "Backups".
6. **Checksum verify**: Properties of `firmware.bin` → SHA-256 *Compute* → paste expected → ✓.
7. **Text file from scratch**: FAB → New file `todo.md` → editor opens → type → Save → listed with correct size.
8. **Search**: type "inv" → instant filter; enable "Search subfolders" → results stream with relative paths; tap → navigates and highlights.
9. **Update**: Tools → Updates → *Check now* → "v1.1.0 available" with notes → *Update & Install* → progress → (first time) "Allow installs from MiniFile" settings hop → system installer → new version runs; a tampered/foreign-signed APK is refused with a clear message. PC: `python3 -m minifile_companion update --check` exits 10 and prints the version line.

Each scenario maps to automated or manual checks in ARCHITECTURE §8.

---

## 10. Open Questions / Decisions Log

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | Single-Activity, Compose, no Navigation library | Older `navigation-compose` only in cache; back-stack is simple (tabs + dir stack + a few full-screen routes). |
| D2 | Persist favorites/settings in `SharedPreferences` + JSON (no Room/DataStore) | Zero extra deps; tiny data; trivially unit-testable through an interface. |
| D3 | Web GUI is the primary PC GUI; Tkinter optional | Host machine lacks `tkinter`; browser UI is cross-platform and supports drag-and-drop natively. |
| D4 | FTP (not SFTP/HTTP) as the phone server protocol | Requirement; universal client support; the companion abstracts it. Documented as LAN-only/plaintext with an in-app warning. |
| D5 | No syntax highlighting in v1 editor | Keeps the editor small and fast; can be added later as a pure-Kotlin tokenizer. |

---

## 11. In-App Auto-Update & Releases (added v1.0 — requirement 6)

MiniFile is distributed from GitHub Releases at **`https://github.com/codescv/minifile`**; both the Android app and the PC Companion can check for and apply updates themselves. No update check happens without the user's knowledge: the first launch shows a one-line notice in the Tools → *Updates* card ("MiniFile checks GitHub for new versions at most every 6 hours. You can turn this off."), and the check is the **only** outbound connection the app makes besides the user-started FTP server.

### 11.1 Android — Update UX

**Silent startup check** (`MainActivity`, after first frame, on `Dispatchers.IO`):
- Runs only if *Automatic update check* (Tools) is on (default **on**), network is available, and the last check is older than **6 h** (`update_last_check_ms`).
- Outcome *update available* → a non-intrusive **snackbar** on the current screen: "MiniFile v1.1.0 is available · **View**" (dismissable; same version is not re-announced until the next 6 h window). Never a blocking dialog on launch.
- Outcome *up to date* / *error* → silent (error is logged and shown only in the Updates card as "Last check failed: …").

**Tools → Updates card** ("Check for Updates" / 检查更新):
```
┌──────────────────────────────────────────────┐
│ Updates                                      │
│ Current version  1.0.0 (1)                   │
│ Latest release   v1.1.0 · Oct 30, 2026       │
│ ──────────────────────────────────────────── │
│ What's new                                   │
│ • Favorites picker remembers last target     │
│ • Fix: EPSV on IPv6-only hotspots            │
│ ──────────────────────────────────────────── │
│ ▓▓▓▓▓▓▓▓▓░░░░░░  58%  7.2 / 12.4 MB   [✕]    │
│                                              │
│ [Check now]              [Update & Install]  │
│ ☑ Check automatically (every 6 h)            │
│ Release page ↗                               │
└──────────────────────────────────────────────┘
```
States: *Idle* (Check now) → *Checking* (spinner) → *Up to date* ("You're on the latest version", timestamp) | *Update available* (notes, size, **Update & Install**) → *Downloading* (progress bar, speed, cancel ✕) → *Verifying* → *Ready to install* (**Install** — re-launches the installer if the user backed out; the verified APK is kept in `cacheDir/updates/` until the app is updated or 7 days pass) | *Error* (human message + *Retry* + *Open release page*).

**Install flow**
- Tap **Update & Install** → if `packageManager.canRequestPackageInstalls()` is false → explanatory dialog "Allow MiniFile to install updates" → `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` for our package → on return, continue automatically.
- Then the system package installer opens (`ACTION_VIEW` on a `FileProvider` `content://` URI with MIME `application/vnd.android.package-archive`, `FLAG_GRANT_READ_URI_PERMISSION | FLAG_ACTIVITY_NEW_TASK`). The user confirms in the system UI; the app does not auto-restart.
- Release notes are rendered as plain text (Markdown bullets kept as-is, links stripped) — no WebView.

**Debug builds** (`versionName` suffix `-DEBUG`/`-debug`) compare using the stripped base version so developers see the same behaviour as users.

### 11.2 PC Companion — Update UX

- **CLI**: `python3 -m minifile_companion update --check` prints `Current 1.0.0 · Latest v1.1.0 (2026-10-30) · Update available` (exit code `10` when an update is available, `0` when current, `3` on network error — scripting-friendly, also with `--json`). `update` (no flag) downloads `minifile-companion-v1.1.0.zip` to `~/.cache/minifile/updates/`, verifies it (size, zip integrity, **zip-slip-safe listing**, contains `minifile_companion/__init__.py` with the expected `__version__`), and then:
  - if the companion runs from a plain directory checkout (`companion/`), extracts into a sibling `companion-v1.1.0/` directory **next to** the current one and prints the `cd`/run instructions — it never overwrites the running code in place;
  - `--apply` additionally swaps directories (`companion` → `companion-v1.0.0.bak`, new → `companion`) when the current directory is writable and not a git checkout (`.git` absent); otherwise prints what to do.
  - `--check-only-notify`: used by the Web UI poller.
- **Web GUI**: top-right **"⬆ v1.1.0 available"** badge (checked at startup and every 6 h via `/api/update`, cached in the config file); clicking opens a small panel with notes, *Download*, and *Open release page* — the download lands in the user's Downloads folder (through the normal browser download), because applying code updates from inside a browser tab is deliberately not done.
- Every command prints a one-line hint `(new version v1.1.0 available — run `update`)` on stderr at most once per 24 h when a cached check says so; disable with `--no-update-hint` or config `update_hint: false`.

### 11.3 Release Policy
- Tags `vX.Y.Z` on `main` publish a GitHub Release (CI, ARCH §11.4) with assets:
  - `minifile-vX.Y.Z-release.apk` — signed release APK (debug keystore fallback when no release keystore is configured, so the APK is still installable and self-consistent for updates signed by the same key),
  - `minifile-companion-vX.Y.Z.zip` — the `companion/` tree (no tests/caches),
  - `SHA256SUMS.txt`.
- `versionName` in `app/build.gradle.kts` and `__version__` in `companion/minifile_companion/__init__.py` **must equal the tag without `v`**; CI fails the release otherwise.
- Release notes = the tag's annotated message / `CHANGELOG.md` section; the app displays `body`.
- Pre-releases (`prerelease: true`) are ignored by the app and companion unless the user enables *Include pre-releases* (Tools / `--pre`).
