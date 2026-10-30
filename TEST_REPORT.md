# MiniFile v1.0.0 — Verification, Security Audit & Release Report

**Version:** `1.0.0` (`versionCode = 10000`, `minifile_companion.__version__ = "1.0.0"`)  
**Repository:** `https://github.com/codescv/minifile`  
**Verified Branches Merged:** `feat/android-app` (`e3137b7`) + `feat/pc-companion` (`e5c200a`) → `test/verification` → `main`

---

## 1. Build Verification

All Android builds, unit tests, and Android Lint checks succeeded **100% offline** (`--offline`) with zero errors.

| Check | Details / Result | Status |
|---|---|---|
| **Toolchain** | JDK `21` (`JAVA_HOME`), Android `compileSdk = 35`, `targetSdk = 35`, `minSdk = 26`, Build-Tools `35.0.0`, Gradle `8.10.2`, AGP `8.4.0`, Kotlin `2.0.21`, Python `3.12+` (zero external pip dependencies) | **PASS** |
| **Offline Build Command** | `./gradlew --offline assembleDebug assembleRelease testDebugUnitTest lintDebug` | **PASS** |
| **Android Lint (`lintDebug`)** | `0 errors, 21 warnings` (zero `MissingPermission`, `ExportedService`, or `InlinedApi` errors; remaining warnings are benign version/icon suggestions) | **PASS** |
| **Pure-JVM `core/` Boundary** | Zero `android.*` or `dalvik.*` imports across `app/src/main/java/com/minifile/explorer/core/**` | **PASS** |
| **Debug APK** | `app/build/outputs/apk/debug/app-debug.apk` (`17,289,521` bytes / ~16.5 MiB, `versionName = "1.0.0-DEBUG"`, `versionCode = 10000`) | **PASS** |
| **Release APK** | `app/build/outputs/apk/release/app-release.apk` (`11,475,792` bytes / ~10.9 MiB, `versionName = "1.0.0"`, `versionCode = 10000`) | **PASS** |
| **APK Signature Verification** | `apksigner verify --verbose` passed on both Debug and Release APKs; APK Signature Scheme v2 verified (`CN=Android Debug`, cert SHA-256: `a2be41f8d6d8ade3f4c4cc618259114bc1c50a98f94032ea5ac94c310628dcf5`) | **PASS** |

---

## 2. Unit Test Suites

### 2.1 Android Pure-JVM Unit Tests (`./gradlew --offline testDebugUnitTest`)

**Summary:** `18` test classes, `35` tests total — **`34` passed, `1` skipped (interop server harness), `0` failures, `0` errors**.

| Test Class | Tests | Passed | Skipped | Coverage Scope |
|---|---:|---:|---:|---|
| `core.vfs.PathUtilsTest` | 6 | 6 | 0 | Normalization, `isAncestor`, `(1)` collision naming, illegal name validation, natural sort, hidden file detection |
| `core.vfs.LocalFileSystemTest` | 2 | 2 | 0 | List/stat/mkdir/rename/delete, hidden filtering, `.nomedia` flag, permission bits |
| `core.ops.FileOperationsTest` | 2 | 2 | 0 | Same & cross-backend copy/move, `SKIP`/`OVERWRITE`/`KEEP_BOTH`, move-into-self guard, cancellation safety, recursive delete |
| `core.ops.ZipArchiverTest` | 3 | 3 | 0 | Multi-entry & UTF-8 ZIP roundtrip, selective extract, Zip-Slip rejection (`../evil`, `/abs`, `C:\x`, `..\\x`, NUL), Zip-Bomb limits |
| `core.ops.ChecksumCalculatorTest` | 2 | 2 | 0 | MD5 & SHA-256 RFC test vectors, coroutine cancellation |
| `core.ops.TextFileCodecTest` | 1 | 1 | 0 | UTF-8 / UTF-16 BOM detection, CRLF preservation, NUL binary detection |
| `core.ops.NomediaManagerTest` | 1 | 1 | 0 | `.nomedia` create/remove/check & MediaScanner callback trigger |
| `core.ops.TransferTargetValidatorTest` | 1 | 1 | 0 | `SameDirectory`, `InsideSource`, `ReadOnly`, and `Unavailable` target rejection |
| `core.ftp.FtpPathResolverTest` | 2 | 2 | 0 | Adversarial traversal (`..`, backslashes, NUL) & `Files.createSymbolicLink` escape rejection |
| `core.ftp.FtpServerEngineTest` | 3 | 3 | 0 | Auth (anon & credentials, `530`), `FEAT`, `UTF8`, `PASV`/`EPSV`, `MLSD`/`MLST`/`LIST`, `RETR`/`STOR`/`APPE`/`REST`, UTF-8 names, read-only `550`, idle timeout, clean shutdown |
| `core.ftp.FtpEngineServeTest` | 1 | 0 | 1 | Cross-component interop harness (skipped unless `MINIFILE_FTP_SERVE=1` per `ARCHITECTURE.md` §8.4) |
| `core.update.SemanticVersionTest` | 2 | 2 | 0 | SemVer grammar, pre-release ordering, `-DEBUG` suffix stripping, invalid input handling |
| `core.update.ReleaseParserTest` | 2 | 2 | 0 | `/releases/latest` & `/releases` fallback parsing, APK/ZIP asset priority, GitHub URL allow-list |
| `core.update.AppUpdaterTest` | 2 | 2 | 0 | Loopback `HttpServer` tests: 200 happy path, 404→list fallback, 403 rate limit, ≤5 redirect hops, HTTPS downgrade rejection, size/ZIP/validator checks, `.tmp` cleanup |
| `core.model.MiniJsonTest` | 2 | 2 | 0 | Minimal JSON serializer/parser roundtrip & escaping |
| `data.FavoritesRepositoryTest` | 1 | 1 | 0 | Seeded defaults, add/remove/reorder/touch, JSON export/import |
| `data.SettingsRepositoryTest` | 1 | 1 | 0 | Preferences persistence & defaults |
| `ui.UiModelsTest` | 2 | 2 | 0 | UI state models & formatting helpers |
| **Total** | **35** | **34** | **1** | **100% pass** |

### 2.2 Python PC Companion Unit Tests (`python3 -m unittest discover -s companion/tests -v`)

**Summary:** `6` test modules (`8` `TestCase` classes), **`43/43` passed (`0` failures, `0` skipped)**.

| Test Class | Tests | Passed | Coverage Scope |
|---|---:|---:|---|
| `test_client.TestMiniFileClient` | 13 | 13 | Connect/auth, UTF-8 paths & quotes, `MLSD`/`LIST` fallback, recursive transfer + progress, `REST` resume, overwrite modes, `mkdir`/`rm`/`mv`, `.nomedia`, `sync` (`to_phone`/`to_pc`/`both`/`--delete`/`--dry-run`/`--exclude`), symlink jail & read-only enforcement |
| `test_client.TestPathAndParsers` | 5 | 5 | POSIX path clamping (`..` never sent raw), `MLSD`/`MLST`/`LIST`/`PASV`/`EPSV` parsers |
| `test_cli.TestCli` | 6 | 6 | All CLI subcommands (`ls`, `tree`, `get`, `put`, `sync`, `cat`, `edit`, `mkdir`, `rm`, `mv`, `nomedia`, `scan`, `fav`), `--json` schemas, exit codes `0/1/2/3/4` |
| `test_webui.TestWebUi` | 6 | 6 | `127.0.0.1` loopback bind, `X-MF-Token` (`401`), `Host` allow-list (`403`), `Origin` CSRF guard (`403`), cookie scoped solely to `GET /api/download`, static traversal block (`404`), streaming multipart upload (>5 MiB, UTF-8, zero `cgi`), directory ZIP download, local `--jail` confinement |
| `test_updater.TestUpdater` | 7 | 7 | SemVer comparison, `/releases/latest` & list fallback, redirect cap & HTTP downgrade block, `SHA256SUMS.txt` verification, Zip-Slip & Zip-Bomb abort, `companion/` staging, `.git` checkout swap refusal, `/api/update` 60s throttle |
| `test_scanner.TestScanner` | 3 | 3 | `220 MiniFile` banner matching, unreachable subnet timeouts, concurrent worker pool |
| `test_gui_and_text.TestGuiFallback` | 1 | 1 | Headless Tkinter fallback error handling |
| `test_gui_and_text.TestTextUtilAndConfig` | 2 | 2 | BOM/encoding detection, binary sniff, `0600` config permissions |
| **Total** | **43** | **43** | **100% pass** |

---

## 3. Cross-Component JVM Interop (`tools/run_interop_tests.py`)

Executed `python3 tools/run_interop_tests.py`, which boots the real Android Kotlin `FtpServerEngine` on JVM via Gradle (`FtpEngineServeTest` with `MINIFILE_FTP_SERVE=1`) and drives the Python PC Companion suite and CLI/Web UI against it over loopback TCP.

### 3.1 Python Unittest Suite vs. Live Kotlin `FtpServerEngine`
- **Result:** **`39` passed, `4` skipped, `0` failures** (`elapsed: 6.32s` total including E2E scenario).
- **4 Skipped Fixture-Only Fault-Injection Tests** (skipped via `require_fixture()` when `MINIFILE_TEST_SERVER` is set because they instantiate custom fault-injected `EmbeddedFtpServer` instances):
  1. `test_credentials_auth_success_and_rejection` — spawns custom-credential `EmbeddedFtpServer(credentials=("alice", "s3cr3t"))` (Kotlin credential auth verified in `FtpServerEngineTest`).
  2. `test_list_fallback_when_mlsd_disabled_and_pasv_522_epsv_fallback` — simulates legacy server with `MLSD` disabled and `PASV` returning `522`.
  3. `test_upload_resume_falls_back_cleanly_on_saf_504` — simulates SAF `504 Command not implemented for that parameter` on `REST`+`STOR`.
  4. `test_sync_handles_missing_mlsd_modify_fact` — simulates SAF entries omitting `modify=` in `MLSD`.

### 3.2 11-Step CLI & Web UI Interop Scenario (`11/11 PASS`)
All steps asserted server-side filesystem state and SHA-256 digests directly on the Kotlin `FtpServerEngine` root directory:
1. `scan`: `minifile scan --subnet 127.0.0.1/32 --json` discovered `220 MiniFile FTP Server ready`.
2. `mkdir`: `minifile mkdir -p /it/sub` created nested directories.
3. `put_utf8_tree`: `minifile put -r` uploaded nested folders with UTF-8 filenames (`测试 файл ✓ (1).txt`) and 96 KiB random binary payload (SHA-256 verified).
4. `ls_and_tree`: `minifile ls -R /it --json` and `minifile tree /it` matched all entries.
5. `get_recursive`: `minifile get -r /it/tree` restored full hierarchy with identical SHA-256.
6. `cat_and_edit`: `minifile cat` & `minifile edit --backup` round-tripped UTF-8 edits and created `.bak` on the Kotlin server.
7. `sync_both`: `minifile sync --both --json` merged PC-only and phone-only files without data loss.
8. `nomedia_toggle`: `minifile nomedia /it/sub --on / --status / --off` created, queried, and removed `.nomedia`.
9. `mv`: `minifile mv` renamed remote files via `RNFR`/`RNTO`.
10. `webui_api_interop`: `CompanionWebServer` `/api/phone/list` and `/api/download` streamed UTF-8 listings and binary files from the Kotlin `FtpServerEngine` with matching SHA-256.
11. `rm_recursive`: `minifile rm -r -f /it` cleanly removed the entire directory tree.

---

## 4. ACID Android 15 Emulator End-to-End Suite (`E1`–`E15`)

- **Runner:** `tools/e2e/run_acid_e2e.sh` / `tools/e2e/run_acid_e2e.py`
- **Device:** Google Pixel 9 Pro Emulator (`Android 15`, `ro.build.version.sdk = 35`, screen `1008x2244`)
- **Build Fingerprint:** `google/sdk_g3komodo_x86_64/komodo:Baklava/ZP1A.241117.001/12667737:userdebug/dev-keys`
- **Crash & ANR Audit:** `adb logcat -d -b crash` empty for `com.minifile.explorer`; `dumpsys activity processes` reported zero ANRs (`tools/e2e/out/v1.0.0-e2e/logcat_excerpt.txt`).
- **Result:** **`15/15 PASS`**

| ID | Area | Result | Time | Evidence (`tools/e2e/out/v1.0.0-e2e/` — local git-ignored artefact dir) | Verification Notes |
|---|---|---|---:|---|---|
| **E1** | Launch & bottom navigation tabs | **PASS** | 49.23s | `e1_browse.{png,xml}`, `e1_favorites.{png,xml}`, `e1_wireless.{png,xml}`, `e1_tools.{png,xml}` | All 4 tabs (`Browse`, `Favorites`, `Wireless`, `Tools`) rendered cleanly |
| **E2** | Browse & breadcrumbs | **PASS** | 24.56s | `e2_breadcrumbs_src.{png,xml}` | Breadcrumb `Internal › MiniFileE2E › src`; rows `sub`, `notes.txt`, `blob.bin` verified |
| **E3** | Sort, grid/list view, search & hidden files | **PASS** | 93.66s | `e3_grid_view.png`, `e3_sorted_size_desc.xml`, `e3_search_blob.{png,xml}`, `e3_hidden_visible.png`, `e3_hidden_persisted.xml` | Grid/list toggle, `Size` descending order, `blob` live search, `.hidden` visibility & persistence across `am force-stop` |
| **E4** | Create folder & empty file | **PASS** | 48.39s | `e4_made_txt_editor.png`, `e4_created_items.{png,xml}` | Created `E2E Folder` and 0-byte `made.txt` (auto-opened in built-in editor) |
| **E5** | Built-in text editor (`UTF-8 · LF`) | **PASS** | 34.86s | `e5_text_editor.{png,xml}` | Appended line to `notes.txt`, verified `UTF-8 · LF` status bar, saved & verified via `adb shell cat` |
| **E6** | Multi-select Copy, Cut/Move & Paste | **PASS** | 57.49s | `e6_selection.png`, `e6_dst_after_paste.{png,xml}` | Copied `notes.txt` & 512 KiB `blob.bin` (identical `md5sum`) and moved `made.txt` into `dst` |
| **E7** | Favorites & 1-tap Copy to Favorite | **PASS** | 57.53s | `e7_favorite_picker_sheet.{png,xml}`, `e7_after_copy_to_favorite.png`, `e7_favorites_tab.{png,xml}` | Starred `dst` as `Target`, copied `src/sub` in 1 tap via *Copy to Favorite…*, verified `Favorites` tab |
| **E8** | Rename & Delete with confirmation | **PASS** | 43.75s | `e8_delete_confirm.{png,xml}` | Renamed `blob.bin` → `blob2.bin`, verified Delete confirmation dialog, confirmed removal on disk |
| **E9** | Properties & SHA-256 checksum | **PASS** | 27.52s | `e9_properties_sha256.{png,xml}` | Computed SHA-256 `97bb02fe9032dbc3f1530ab4f778eb36c1ba74016d52e9f8db13d82a21431155` matched `adb shell sha256sum` |
| **E10** | `.nomedia` toggle & gallery badge | **PASS** | 33.46s | `e10_nomedia_on.{png,xml}`, `e10_nomedia_off.xml` | Toggled *Hide from gallery* on `dst`; verified `.nomedia` file and `content-desc="Hidden from gallery"` badge on/off |
| **E11** | ZIP compress, extract & Zip-Slip guard | **PASS** | 56.99s | `e11_zip_preview.png`, `e11_evil_zip_preview.{png,xml}`, `e11_evil_extracted.{png,xml}` | Compressed & extracted `e2e.zip` (MD5 match); crafted `evil.zip` (`../evil.txt` + `safe.txt`) showed `"1 unsafe path entry detected"`, extracted `safe.txt`, skipped `../evil.txt` with `"1 unsafe entry skipped"`, nothing written outside destination |
| **E12** | Storage Home, SAF `Android/data` & APK export | **PASS** | 65.20s | `e12_storage_home.{png,xml}`, `e12_saf_picker.png`, `e12_installed_apps.{png,xml}` | Storage Home capacities shown; `Android/data` launched `com.android.documentsui` SAF picker cleanly; exported `com.minifile.explorer-1.0.0-DEBUG.apk` verified valid ZIP/APK |
| **E13** | Wireless FTP server + PC Companion (`adb forward`) | **PASS** | 61.88s | `e13_ftp_running.{png,xml}`, `e13_ftp_activity_log.{png,xml}` | Started FTP with passive window `50000–50004`, forwarded `12121→2121` + `50000–50004`; PC Companion ran `ls`, `mkdir`, `put`, `get`, `sync --to-pc`, `cat`, `nomedia --on`, `rm`; verified live command log, foreground notification, and clean service stop |
| **E14** | Updates card *Check now* | **PASS** | 19.43s | `e14_updates_check.{png,xml}` | Verified both pre-release error resilience and post-publication **`Up to date`** check against live `codescv/minifile` `v1.0.0` (see §7) |
| **E15** | Process death (`am kill`) & state restoration | **PASS** | 39.77s | `e15_after_process_death.{png,xml}`, `logcat_excerpt.txt` | Killed backgrounded process (`am kill`), relaunched cleanly into `src` via `SavedStateHandle` with transient clipboard cleared and zero crashes |

> **Integration Bug Caught & Resolved During E11:** On Android 14+ (`targetSdk = 35`), ART's default `dalvik.system.ZipPathValidator` threw `ZipException` inside `ZipFile.entries()` / `ZipInputStream.nextEntry` before `ZipArchiver.sanitizeEntrySegments()` could inspect, warn, and skip unsafe entries while still extracting safe entries. Reported to `@coder-android` and resolved in `e3137b7` by clearing the global ART callback in `MiniFileApplication.onCreate()` (API 34+) so `ZipArchiver`'s explicit segment sanitizer handles traversal entries consistently across API 26–35, alongside `android:launchMode="singleTop"` on `.MainActivity`.

---

## 5. Security & Requirements Audit (`ARCHITECTURE.md` §8.5 & §11)

| Requirement / Security Rule | Verification Method & Evidence | Status |
|---|---|---|
| **Zip-Slip & Zip-Bomb guards (§5.2)** | `ZipArchiver.sanitizeEntrySegments()` rejects `..`, leading `/`, Windows drive letters, `\`, and NUL; enforces ≤100,000 entries, ≤4 GiB total uncompressed, and ratio ≤100× (>1 MiB). Verified in `ZipArchiverTest`, `test_updater.py`, and live Android 15 `E11` (`evil.zip`). | **PASS** |
| **FTP path & symlink jail (§4.2, §6.1)** | `FtpPathResolver` normalizes segments and verifies `toRealPath()` stays inside `rootReal`; `LocalFileSystem.resolveInside()` re-checks canonical ancestry before every file operation. Verified in `FtpPathResolverTest`, `FtpServerEngineTest`, and cross-JVM symlink escape test in `test_client.py`. | **PASS** |
| **IPv6 `PASV` returns `522`; `EPSV` works (§6.1)** | `FtpSession` returns `522 Network protocol not supported, use EPSV` when control local address is `Inet6Address`; `LanAddressResolver` enumerates all usable non-loopback IPv4 interface addresses. Verified in `FtpServerEngineTest` and code inspection. | **PASS** |
| **`FtpServerService` `specialUse` FGS & `onTimeout` (§6.2)** | Declared `android:exported="false"` and `android:foregroundServiceType="specialUse"` with property subtype; handles `onTimeout()` on API 34+ by stopping engine & posting timeout notification; never auto-starts in background. Verified in `AndroidManifest.xml`, `FtpServerService.kt`, and live `E13`. | **PASS** |
| **PASV peer-IP check, bounded range & socket cleanup (§6.1)** | Passive `ServerSocket` binds inside configured range (`50000–50100` default) and rejects data peers whose `InetAddress` != control peer IP (`425`); closed on session teardown. Verified in `FtpSession.kt` and `FtpServerEngineTest`. | **PASS** |
| **Credential redaction (`PASS ****`) (§6.1, §8.5)** | `FtpSession` logs `PASS ****` instead of plaintext passwords; FTP credentials stored separately from general settings export. Verified in `FtpSession.kt` and `SettingsRepository.kt`. | **PASS** |
| **`FileProvider` scoping & zero `file://` leaks (§6.2, §11.1)** | `res/xml/file_paths.xml` scopes `<external-path>`, `<root-path>` (for external/USB volumes), and `<cache-path name="updates" path="updates/"/>`; external open/share/install intents exclusively use `FileProvider` `content://` URIs with `FLAG_GRANT_READ_URI_PERMISSION`. | **PASS** |
| **Companion Web UI loopback, CSRF, DNS-rebinding & jail (§7.4)** | Binds `127.0.0.1` by default (`0.0.0.0` requires `--allow-lan` + warning); enforces `Host` allow-list (`403`), `X-MF-Token` header on all `/api/*` (`mf_token` `HttpOnly; SameSite=Strict` cookie accepted **only** on `GET /api/download`), `Origin` validation on mutating methods (`403`), `os.path.realpath` `--jail` confinement, `static/` confinement (`404` on `/static/../webui.py`), and stdlib streaming multipart parser (zero `cgi` imports). Verified in `test_webui.py` (`6/6`). | **PASS** |
| **Edge-to-edge UI, non-exported service & immutable `PendingIntent`s** | `MainActivity` invokes `enableEdgeToEdge()` with inset-aware Material 3 chrome; `FtpServerService` has `android:exported="false"`; all notifications use `PendingIntent.FLAG_IMMUTABLE`. | **PASS** |
| **Minimal network surface (`usesCleartextTraffic="false"`)** | `AndroidManifest.xml` explicitly sets `android:usesCleartextTraffic="false"`; network usage is restricted strictly to user-started LAN FTP server, loopback PC Companion, and HTTPS-only GitHub Releases check/download. | **PASS** |
| **Android & Companion Auto-Updater security (§11.1–§11.3)** | Both updaters enforce `https://` initial URLs on `github.com/codescv/minifile/releases/download/`, manual redirects ≤5 hops with zero `https→http` downgrade, 2 MiB JSON cap, exact `Content-Length`/size match, `.tmp` cleanup on failure. Android `ApkValidator` verifies `PK\x03\x04`, `AndroidManifest.xml`, `packageName == context.packageName`, `longVersionCode > installed`, and exact SHA-256 signing-certificate set equality (API 28+ `GET_SIGNING_CERTIFICATES` with API 28–29 archive fallback). Companion verifies `SHA256SUMS.txt`, aborts on Zip-Slip/Zip-Bomb, stages to sibling `companion-v<ver>/`, and refuses `--apply` inside a `.git` checkout. Verified in `AppUpdaterTest`, `ReleaseParserTest`, `SemanticVersionTest`, and `test_updater.py`. | **PASS** |

---

## 6. Known Issues & Deviations

- **None blocking.** All 6 user product requirements (Minimalist UI & file ops, Wireless FTP + PC Companion CLI/Web GUI/Tkinter fallback, hidden files + built-in text editor + ZIP/Unzip + `.nomedia`, Storage Home + SAF `Android/data` + USB/external volumes + Installed Apps APK inspector, Favorites + 1-tap Copy/Move to Favorite, and GitHub Releases auto-update) are implemented and verified end-to-end.
- **Informational Notes:**
  - `lintDebug` reports `0` errors and `21` informational warnings (dependency/SDK metadata hints; offline local pin `agp = 8.4.0`, `kotlin = 2.0.21` is preserved intentionally for reproducible `--offline` builds).
  - As designed on Android 13+ (`API 33+`), system `DocumentsUI` restricts root-level `Android/data` SAF tree grants unless selecting an app-specific subfolder where permitted by the OS; MiniFile surfaces clear guidance and handles picker return gracefully (`E12`).

---

## 7. Release & GitHub Publication (`codescv/minifile` `v1.0.0`)

- **Repository URL:** `https://github.com/codescv/minifile`
- **Release Tag & Version:** `v1.0.0` (`versionCode = 10000`)
- **Release URL:** `https://github.com/codescv/minifile/releases/tag/v1.0.0`
- **CI Signing Secret:** `MINIFILE_DEBUG_KEYSTORE_B64` configured on `codescv/minifile` matching release signing certificate SHA-256 `a2be41f8d6d8ade3f4c4cc618259114bc1c50a98f94032ea5ac94c310628dcf5`.

### 7.1 Published Release Assets & SHA-256 Checksums

| Asset | SHA-256 |
|---|---|
| `minifile-v1.0.0-release.apk` | `ef038e720f25bdd40855d4dc8786ce41f602b5aa7d224d4d6ffe1add44017ae5` |
| `minifile-companion-v1.0.0.zip` | `0f89330a3617ce6f9305c0cb5bb373f371bbc002bfa8b9aeb25ad1493f2f03e9` |
| `SHA256SUMS.txt` | Basename SHA-256 manifest attached to `v1.0.0` release (`190` bytes) |

### 7.2 Live Post-Publication Smoke Verification

- **GitHub Releases API (`/repos/codescv/minifile/releases/latest`):** **PASS** — returned `tag_name = "v1.0.0"` (`https://github.com/codescv/minifile/releases/tag/v1.0.0`) with all 3 expected assets (`minifile-v1.0.0-release.apk`, `minifile-companion-v1.0.0.zip`, `SHA256SUMS.txt`).
- **PC Companion Live Update Check (`python3 -m minifile_companion update --check`):** **PASS** — exited `0` (`Current 1.0.0 · Latest v1.0.0 (2026-10-30) · Up to date`, `"available": false`, verified `asset_url` and `checksums_url`).
- **Android Emulator Live Update Check (`Tools -> Updates -> Check now` on API 35 Pixel 9 Pro with `app-release.apk`):** **PASS** (`19.5s`) — UI displayed `Current version: 1.0.0 (10000)` and **`Up to date — you're on the latest version (0 min. ago).`** (`tools/e2e/out/v1.0.0-live-update/e14_updates_check.{png,xml}`).
