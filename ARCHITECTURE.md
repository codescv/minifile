# MiniFile — System Architecture

> **Status:** v1.0 (authoritative for implementation by `coder-android`, `coder-companion`, verified by `tester`).
> **Companion doc:** [DESIGN.md](./DESIGN.md) — product/UX specification. Section references like *DESIGN §5.2* point there.

---

## 1. Repository Layout & Build Environment

```
minifile/
├── DESIGN.md, ARCHITECTURE.md, README.md
├── settings.gradle.kts, build.gradle.kts, gradle.properties, gradlew, gradle/
│   └── libs.versions.toml                 # single source of dependency versions
├── app/                                   # Android application (Kotlin + Compose)
│   ├── build.gradle.kts
│   └── src/
│       ├── main/AndroidManifest.xml
│       ├── main/java/com/minifile/explorer/…   # see §3
│       ├── main/res/…                     # strings.xml, xml/file_paths.xml, icons
│       └── test/java/com/minifile/explorer/…   # pure-JVM unit tests (JUnit 4)
├── companion/                             # PC Companion (pure Python 3, stdlib only)
│   ├── minifile.py                        # shim → minifile_companion.__main__
│   ├── minifile_companion/
│   │   ├── __init__.py, __main__.py
│   │   ├── client.py      # FTP client wrapper, recursive ops, sync, discovery
│   │   ├── cli.py         # argparse commands (DESIGN §4.3)
│   │   ├── webui.py       # localhost HTTP server + JSON API (§6.3)
│   │   ├── gui.py         # `gui` entry: Tkinter if importable, else delegates to webui
│   │   ├── config.py      # ~/.config/minifile/config.json
│   │   ├── scanner.py     # LAN discovery
│   │   ├── textutil.py    # encoding detection helpers
│   │   ├── updater.py     # GitHub release check / download / safe staging (§11.3)
│   │   └── static/        # index.html, app.css, app.js (no build step)
│   ├── tests/             # unittest suite with embedded FTP server fixture (§8.3)
│   └── README.md
├── .github/workflows/release.yml          # tag-triggered GitHub Release (§11.4)
├── tools/                                 # tester-owned scripts: interop runner, e2e/ ACID scripts (§8.4, §12)
├── TEST_REPORT.md                         # written by tester (§12.4)
└── .worktrees/            # (git-ignored) per-worker worktrees
```

### 1.1 Toolchain (all cached locally; builds must pass `--offline`)

| Component | Version | Source |
|-----------|---------|--------|
| JDK | 21 (Temurin) | `JAVA_HOME=/usr/local/google/home/chii/.jdks/jdk21` |
| Android SDK | platforms 35 (36 available), build-tools 35.0.0 | `ANDROID_HOME=/usr/local/google/home/chii/Android/Sdk` (`local.properties` present, git-ignored) |
| Gradle | 8.10.2 (`gradle-8.10.2-bin`, wrapper committed) | `~/.gradle/wrapper/dists` |
| AGP | **8.7.1** (fallback 8.4.0; both cached; 8.7.1 requires Gradle ≥ 8.9 ✓) | `~/.gradle/caches` |
| Kotlin | **2.0.21** + `org.jetbrains.kotlin.plugin.compose` 2.0.21 | cached |
| Compose | BOM `2024.10.01` (ui/foundation 1.7.5), `material3` **1.3.1** pinned explicitly if the BOM's 1.3.0 is not cached, `material-icons-extended` 1.7.6 | cached |
| AndroidX | `core-ktx` 1.15.0, `activity-compose` 1.9.3, `lifecycle-runtime-compose`/`viewmodel-compose` 2.8.7, `lifecycle-service` 2.8.7, `documentfile` 1.0.1, `appcompat` 1.7.1 (only for `FileProvider`/compat if needed — otherwise `core`) | cached |
| Coroutines | `kotlinx-coroutines-android` 1.9.0 | cached |
| JSON | **`kotlinx-serialization-json` 1.7.3** + Gradle plugin `org.jetbrains.kotlin.plugin.serialization` 2.0.21 (both cached). **`org.json` is forbidden** in `core.*`/`data` code: the android.jar stubs throw "not mocked" in JVM unit tests, so `FavoritesRepository`, settings and `ReleaseJson` all use `@Serializable` data classes + `Json { ignoreUnknownKeys = true; isLenient = true }`. | cached |
| Thumbnails (optional) | `io.coil-kt:coil-compose` 2.6.0 — **must be removable without breaking the build** (feature-flag `BuildConfig.THUMBNAILS`) | cached per researcher |
| Tests | JUnit 4.13.2 (pure JVM). **No Robolectric/Mockito/MockK/`kotlinx-coroutines-test`** (not cached) → all testable logic must be Android-free (§3.2 layering); suspend code is tested with `runBlocking`, HTTP code with a loopback `com.sun.net.httpserver.HttpServer` (JDK built-in) test seam. | cached |
| Python | 3.13 (`/usr/bin/python3`); `tkinter` **absent** on this host; no Node.js. | system |

Build commands (from a worktree):
```bash
export JAVA_HOME=/usr/local/google/home/chii/.jdks/jdk21 ANDROID_HOME=/usr/local/google/home/chii/Android/Sdk
./gradlew --offline assembleDebug testDebugUnitTest      # try offline first; drop --offline only if a dep is truly missing
python3 -m unittest discover -s companion/tests -v
```
Debug APK path: `app/build/outputs/apk/debug/app-debug.apk`. `gradle.properties` must set `org.gradle.jvmargs=-Xmx3g`, `android.useAndroidX=true`, `kotlin.code.style=official`.

Android app config: `applicationId "com.minifile.explorer"`, `namespace "com.minifile.explorer"`, `minSdk 26`, `targetSdk 35`, `compileSdk 35`, `versionCode 1`, `versionName "1.0.0"`, `buildFeatures { compose = true; buildConfig = true }`.

**Reproducibility rules (binding):**
- **Never** use `kotlin { jvmToolchain(17) }` or `java { toolchain { … } }` — Gradle would try to auto-provision a JDK 17 and fail offline. Instead set directly:
  ```kotlin
  android { compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 } }
  kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
  ```
  JDK 21 (`JAVA_HOME`) runs Gradle and compiles with `--release`-style targeting of 17.
- Git worktrees do **not** contain `local.properties` (git-ignored) → `ANDROID_HOME` must be exported in every build shell (or copy `local.properties` into the worktree; it stays ignored).
- **Milestone M0 (first commit of `feat/android-app`)**: an empty Compose skeleton app (`MainActivity` showing "MiniFile") that passes `./gradlew --offline assembleDebug testDebugUnitTest` with one trivial JUnit test, committed *before* any feature code. Every dependency added later must also resolve with `--offline`; if one does not, pick a cached alternative rather than dropping `--offline`.
- Lock the versions in `gradle/libs.versions.toml`; no dynamic (`+`) versions; `repositories { google(); mavenCentral() }` only.

---

## 2. System Context

```mermaid
flowchart LR
    subgraph Phone["Android Phone — MiniFile app"]
        UI["Compose UI\n(Browse/Favorites/Wireless/Tools)"]
        VM["ViewModels"]
        FS["FileSystemRepository\n(VfsNode abstraction)"]
        LOCAL["LocalFileSystem\n(java.io.File)"]
        SAF["SafFileSystem\n(DocumentFile / ContentResolver)"]
        OPS["FileOperationService\n(copy/move/delete/zip/checksum)"]
        FTP["FtpServerService\n+ FtpServerEngine (RFC 959/3659)"]
        PREFS["PreferencesStore\n(SharedPreferences+JSON)"]
        UI --> VM --> FS
        VM --> OPS --> FS
        VM --> FTP --> FS
        FS --> LOCAL
        FS --> SAF
        VM --> PREFS
        FTP --> PREFS
    end
    subgraph PC["PC — Companion (Python 3)"]
        CLI["cli.py"]
        WEB["webui.py\n(localhost HTTP + static SPA)"]
        CLIENT["client.py\n(ftplib wrapper, sync, discovery)"]
        CLI --> CLIENT
        WEB --> CLIENT
        BROWSER["Browser SPA"] --> WEB
    end
    CLIENT -- "FTP over LAN (TCP 2121 + PASV range)" --> FTP
    ANDROID["Android OS\nStorageManager · SAF · MediaScanner · PackageManager"] --> LOCAL
    ANDROID --> SAF
```

---

## 3. Android App Architecture

### 3.1 Pattern
**MVVM + unidirectional data flow** with Kotlin coroutines/`StateFlow`. Manual dependency wiring via a single `AppContainer` (no Hilt/Dagger — not needed, keeps the build minimal and offline-safe). Single `MainActivity` hosting `MiniFileApp()` composable with a state-driven router.

### 3.2 Layering & Package Map (`com.minifile.explorer`)

| Package | Responsibility | Android dependency? | Unit-testable on JVM? |
|---------|----------------|---------------------|-----------------------|
| `core.vfs` | `VfsNode`, `VfsPath`, `FileSystem` interface, `LocalFileSystem` (java.io), `FileSystemRepository` (routes paths → backend), `PathUtils` (normalization, name validation, unique naming, natural sort) | **No** (pure Kotlin/JVM) | ✅ |
| `core.ops` | `FileOperations` (copy/move/delete/rename/mkdir/create, conflict policy, progress), `ZipArchiver` (compress/extract + zip-slip guard), `ChecksumCalculator`, `NomediaManager`, `TextFileCodec` (encoding detect/decode/encode, EOL) | **No** | ✅ |
| `core.ftp` | `FtpServerEngine`, `FtpSession`, `FtpCommandHandler`, `FtpPathResolver` (virtual root jail), `FtpListingFormatter` (LIST/MLSD), `FtpConfig`, `FtpEvent` | **No** (uses `java.net`, `FileSystem` interface) | ✅ (real sockets on loopback) |
| `core.model` | `Favorite`, `SortSpec`, `ViewMode`, `AppSettings`, `ClipboardState`, `OperationResult` | No | ✅ |
| `core.update` | `SemanticVersion`, `ReleaseJson`/`ReleaseInfo` (GitHub release parsing + asset selection), `AppUpdater` (HTTP check + download with redirect/size/zip validation, `HttpURLConnection` seam), `UpdateException`, `CancelToken` — see §11 | **No** (validator for package/signature is injected from `data`) | ✅ (loopback `HttpServer`) |
| `data` | `SafFileSystem` (DocumentFile), `AndroidVolumeProvider` (`StorageManager`), `PersistedTreeStore`, `FavoritesRepository`, `SettingsRepository` (SharedPreferences impl of `KeyValueStore`), `InstalledAppsRepository` (PackageManager), `MediaScanNotifier` | Yes | Interfaces tested with fakes; impls via manual/instrumented checks |
| `service` | `FileOperationService` (foreground, progress notifications, cancel), `FtpServerService` (foreground, wake/wifi locks), `VolumeChangeReceiver` | Yes | — |
| `ui` | Compose screens, `MiniFileApp` router, theme, components (`FileListItem`, `BreadcrumbBar`, `ClipboardBar`, `SelectionBottomBar`, `FavoritePickerSheet`, `ConflictDialog`, `ProgressSheet`), screens: `home`, `browser`, `favorites`, `wireless`, `tools`, `editor`, `archive`, `apps` | Yes | — |
| `di` | `AppContainer` (wires everything; `MiniFileApplication.container`) | Yes | — |

**Rule:** nothing in `core.*` may import `android.*`. Android-specific needs are injected as small interfaces (`Clock`, `KeyValueStore`, `MediaScanNotifier`, `Logger`).

### 3.3 Threading
- `Dispatchers.IO` for all filesystem work; listing runs in `viewModelScope` with cancellation on navigation.
- `FileOperationService` runs each `OperationJob` in a `SupervisorJob` + `Dispatchers.IO`, exposes `StateFlow<List<OperationProgress>>` via a bound `Binder`/singleton `OperationRegistry`.
- `FtpServerEngine` uses a dedicated `Executors.newCachedThreadPool` (blocking socket I/O is clearer and testable without coroutines); emits `FtpEvent`s via a `MutableSharedFlow` (buffer 512, DROP_OLDEST) consumed by the Wireless screen and the notification updater.

### 3.4 Navigation & UI State
```kotlin
sealed interface Route {
    data object Home : Route                        // Storage Home (Browse tab root)
    data class Browser(val path: VfsPath) : Route
    data object Favorites : Route
    data object Wireless : Route
    data object Tools : Route
    data class Editor(val path: VfsPath, val forceText: Boolean = false) : Route
    data class ArchivePreview(val path: VfsPath) : Route
    data object InstalledApps : Route
    data class AppDetail(val packageName: String) : Route
    data class ToolsPage(val page: ToolsPageId) : Route
}
enum class Tab { BROWSE, FAVORITES, WIRELESS, TOOLS }
```
`NavState` = `currentTab` + per-tab back stacks (`ArrayDeque<Route>`), kept in `MainViewModel`, saved via `SavedStateHandle` (paths serialized as strings). `BackHandler` follows DESIGN §1.1.

**`BrowserUiState`**
```kotlin
data class BrowserUiState(
    val path: VfsPath,
    val breadcrumbs: List<Crumb>,
    val entries: List<VfsNode>,            // already sorted + filtered
    val isLoading: Boolean,
    val error: BrowserError?,              // NoPermission, SafNotMounted, Unavailable, Io(msg)
    val sort: SortSpec, val viewMode: ViewMode, val showHidden: Boolean,
    val query: String, val recursiveSearch: Boolean, val searchResults: List<SearchHit>?,
    val selection: Set<VfsPath>, val selectionMode: Boolean,
    val clipboard: ClipboardState?,        // shared app-wide via ClipboardHolder
    val isFavorite: Boolean, val hasNomedia: Boolean, val isReadOnly: Boolean,
)
```

### 3.5 Clipboard & Favorite Picker contract
```kotlin
data class ClipboardState(val items: List<VfsPath>, val mode: TransferMode /* COPY, MOVE */)
object ClipboardHolder { val state: StateFlow<ClipboardState?> ; fun set(..); fun clear() }

// Launched from: SelectionBottomBar.More, ClipboardBar.ToFavorite, row menu "Copy to…/Move to…"
@Composable fun FavoritePickerSheet(
    request: TransferRequest,                 // items + initial mode
    favorites: List<FavoriteWithStatus>,      // status: Available | Unavailable(reason) | Disabled(reason)
    onPick: (Favorite, TransferMode) -> Unit, // starts FileOperationService job immediately
    onBrowse: () -> Unit, onAddCurrent: () -> Unit, onDismiss: () -> Unit,
)
```
Disabled reasons computed by `TransferTargetValidator.validate(items, mode, target)`: `SameDirectory`, `InsideSource`, `ReadOnlyTarget`, `TargetUnavailable`. **Unit-tested.**

---

## 4. Virtual File System (VFS)

### 4.1 Data Model
```kotlin
/** Opaque, serializable location. Exactly one of the two forms. */
sealed interface VfsPath {
    val displayName: String
    data class Local(val absolutePath: String) : VfsPath                      // "/storage/emulated/0/Download"
    data class Saf(val treeUri: String, val documentId: String) : VfsPath     // tree root + child document id
    fun serialize(): String  // "file:///…" or "saf:<treeUri>#<documentId>"  — used in prefs & SavedState
    companion object { fun parse(s: String): VfsPath }
}

data class VfsNode(
    val path: VfsPath,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,                 // bytes; -1 unknown (SAF dir)
    val lastModified: Long,         // epoch ms; 0 unknown
    val mimeType: String?,          // null → derive from extension
    val isHidden: Boolean,          // name.startsWith('.')
    val canRead: Boolean, val canWrite: Boolean,
    val isSymlink: Boolean = false,
    val childCount: Int = -1,       // -1 unknown/not computed
    val hasNomedia: Boolean = false,// directories only
    val posixPermissions: String? = null, // "rwxr-xr-x" (Local only)
)

data class Volume(
    val id: String,            // uuid or "primary"
    val name: String,          // "Internal Storage", "SD Card", "USB Drive (SanDisk)"
    val kind: VolumeKind,      // PRIMARY, SD_CARD, USB_OTG, APP_DATA, APP_OBB, MOUNTED_TREE (generic "Mount a folder…" SAF grant, DESIGN §1.2)
    val root: VfsPath?,        // null until a SAF grant exists
    val totalBytes: Long, val freeBytes: Long,   // -1 when unknown
    val isMounted: Boolean, val needsSafGrant: Boolean,
)
```

### 4.2 `FileSystem` interface (implemented by `LocalFileSystem`, `SafFileSystem`, test `FakeFileSystem`)
```kotlin
interface FileSystem {
    fun supports(path: VfsPath): Boolean
    suspend fun stat(path: VfsPath): VfsNode?
    suspend fun list(dir: VfsPath, includeHidden: Boolean): List<VfsNode>   // also sets hasNomedia on dir children
    suspend fun child(dir: VfsPath, name: String): VfsPath
    suspend fun parent(path: VfsPath): VfsPath?
    suspend fun exists(path: VfsPath): Boolean
    suspend fun createDirectory(dir: VfsPath, name: String): VfsPath
    suspend fun createFile(dir: VfsPath, name: String, mime: String = "application/octet-stream"): VfsPath
    suspend fun delete(path: VfsPath): Boolean              // non-recursive; recursion in FileOperations
    suspend fun rename(path: VfsPath, newName: String): VfsPath
    suspend fun moveWithinSameBackend(src: VfsPath, dstDir: VfsPath): VfsPath?  // fast path (File.renameTo / DocumentsContract.moveDocument); null → caller falls back to copy+delete
    fun openInput(path: VfsPath, offset: Long = 0): InputStream
    fun openOutput(path: VfsPath, append: Boolean = false): OutputStream
    suspend fun setLastModified(path: VfsPath, epochMs: Long): Boolean  // best effort (no-op on SAF)
    suspend fun space(path: VfsPath): Pair<Long, Long>?     // total, free
}

class FileSystemRepository(private val backends: List<FileSystem>) : FileSystem {
    // dispatches by `supports(path)`; cross-backend ops handled by FileOperations via streams
}
```
Path safety: `LocalFileSystem` canonicalizes and refuses names containing `/` or NUL; `SafFileSystem` builds children with `DocumentsContract.buildDocumentUriUsingTree` and never concatenates strings into URIs.

**Jail support for `Local` roots (used by the FTP server and by `ZipArchiver.extract`):** `LocalFileSystem` exposes
```kotlin
fun isInside(candidate: File, root: File): Boolean {
    val r = root.canonicalPath; val c = candidate.canonicalPath
    return c == r || c.startsWith(r + File.separator)
}
```
which resolves **symlinks** via `canonicalPath`. `FtpPathResolver` and the extractor call it on the fully resolved target before every list/stat/open/create/rename/delete; a `false` result is a hard failure (`550` for FTP, "unsafe entry skipped" for zip). This is what makes a symlink inside the root that points outside (`root/evil -> /`) non-traversable. Covered by `FtpPathResolverTest` (symlink case) and `ZipArchiverTest`.

### 4.3 Storage Volumes & SAF (data layer)
- `AndroidVolumeProvider.volumes(): Flow<List<Volume>>` — merges `StorageManager.storageVolumes`, `PersistedTreeStore` grants, and `VolumeChangeReceiver` events.
- `PersistedTreeStore`: map `volumeKey → treeUri` in prefs; on load it validates against `contentResolver.persistedUriPermissions` and drops stale grants.
- SAF mount flow (`SafMountLauncher` in UI, `ActivityResultContracts.OpenDocumentTree`):
  - Generic **"Mount a folder…"** (Storage Home, onboarding card): plain `ACTION_OPEN_DOCUMENT_TREE` with no initial URI; the result becomes a `Volume(kind = MOUNTED_TREE)` keyed by its tree URI. This is the guaranteed path when All-files access is denied.
  - Removable volume: `volume.createOpenDocumentTreeIntent()` (API 29+) else `ACTION_OPEN_DOCUMENT_TREE`.
  - `Android/data`/`obb`: `EXTRA_INITIAL_URI = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Android/data")` (`…/obb`); per-package: `"primary:Android/data/<pkg>"`.
  - On result: `takePersistableUriPermission(uri, READ|WRITE)`; verify the returned tree really is the requested one (`DocumentsContract.getTreeDocumentId(uri)` starts with the expected id) else show "You picked a different folder" and keep it as a generic mounted tree.
- `SafFileSystem.list` uses a single `ContentResolver.query` over `buildChildDocumentsUriUsingTree` with projection `[DOCUMENT_ID, DISPLAY_NAME, MIME_TYPE, SIZE, LAST_MODIFIED, FLAGS]` (much faster than `DocumentFile.listFiles()`), setting `hasNomedia` for directories by a second children query *only for directories currently rendered* (lazy, cached per listing).

---

## 5. File Operations, Archives, Checksums, Text, `.nomedia`

### 5.1 `FileOperations` (core.ops)
```kotlin
enum class ConflictResolution { SKIP, OVERWRITE, KEEP_BOTH }
interface ConflictResolver { suspend fun resolve(src: VfsNode, existing: VfsNode): ConflictResolution }  // UI dialog; "apply to all" wraps it
interface ProgressSink { fun onProgress(p: OperationProgress); fun isCancelled(): Boolean }

class FileOperations(private val fs: FileSystem, private val clock: Clock) {
    suspend fun copy(items: List<VfsPath>, dstDir: VfsPath, resolver: ConflictResolver, sink: ProgressSink): OperationResult
    suspend fun move(items: List<VfsPath>, dstDir: VfsPath, resolver: ConflictResolver, sink: ProgressSink): OperationResult
    suspend fun delete(items: List<VfsPath>, sink: ProgressSink): OperationResult
    suspend fun rename(item: VfsPath, newName: String): VfsPath
    suspend fun createFolder(dir: VfsPath, name: String): VfsPath
    suspend fun createFile(dir: VfsPath, name: String): VfsPath
    suspend fun sizeOf(items: List<VfsPath>, sink: ProgressSink): FolderStats   // bytes, files, dirs
}
data class OperationResult(val succeeded: Int, val skipped: Int, val failed: List<Pair<VfsPath, String>>, val cancelled: Boolean)
```
Rules: refuse moving/copying a directory into itself or a descendant (`PathUtils.isAncestor`); same-dir copy auto-renames `name (1).ext`; move = `moveWithinSameBackend` fast path else copy → verify size → delete source (per item, so cancellation never loses data); 64 KB buffers; preserve `lastModified` when supported.

### 5.2 `ZipArchiver`
```kotlin
class ZipArchiver(private val fs: FileSystem) {
    suspend fun compress(items: List<VfsPath>, dstDir: VfsPath, archiveName: String, level: Int, sink: ProgressSink): VfsPath
    suspend fun listEntries(zip: VfsPath): List<ZipEntryInfo>
    suspend fun extract(zip: VfsPath, dstDir: VfsPath, entries: Set<String>? = null, overwrite: Boolean, sink: ProgressSink): ExtractResult  // ExtractResult includes unsafeEntriesSkipped
}
```
**Zip-slip guard (mandatory, tested):** for every entry compute `relative = entry.name` → reject if it is empty, absolute (`/`, `\`, or `X:`), contains a NUL, or any segment equals `..` after splitting on both `/` and `\`. For `Local` destinations additionally verify `File(dstDir, relative).canonicalPath.startsWith(dstCanonical + File.separator)`. For SAF destinations, create children segment by segment through `FileSystem.child/createDirectory` (no path strings), which structurally prevents escape. Zip-bomb guard per DESIGN §3.3. Uses `java.util.zip` with `Charsets.UTF_8`; falls back to `CP437` decoding when the UTF-8 flag is absent and names fail to decode.

**Archive source backend:** `java.util.zip.ZipFile` requires a `java.io.File`, so `Local` archives use `ZipFile` (random access → fast `listEntries`, selective extract by entry). `Saf` archives (USB/`Android/data`) use `ZipInputStream` over `fs.openInput(zip)` — sequential; `listEntries` scans once (sizes come from the local header / stream, "compressed size" may be unknown → `-1`), and selective extract is implemented as a name filter while streaming. Both paths share the same entry-safety and conflict logic (`ZipEntrySink`). Compression always writes through `ZipOutputStream` over `fs.openOutput`, so it works for both backends.

### 5.3 `ChecksumCalculator`
`suspend fun digest(path: VfsPath, algorithm: "MD5"|"SHA-256", sink: ProgressSink): String` — `MessageDigest` over `openInput`, 256 KB buffer, cancellable, lower-case hex.

### 5.4 `TextFileCodec`
`detect(bytes: ByteArray): Detected(charset, bom, eol)` (BOM sniff → UTF-8 validity check → fallback ISO-8859-1), `decode`, `encode(text, charset, eol, bom)`, `isProbablyText(head: ByteArray)` (no NUL in first 8 KB). Editor save path: `Local` → write `name.tmp-<rand>` in same dir then `rename` over target (fallback copy on failure); `Saf` → `openOutput(path)` with `"wt"` mode semantics (truncate).

### 5.5 `NomediaManager`
```kotlin
class NomediaManager(private val fs: FileSystem, private val scanner: MediaScanNotifier) {
    suspend fun has(dir: VfsPath): Boolean
    suspend fun set(dir: VfsPath, hidden: Boolean): Boolean   // create/delete ".nomedia", then scanner.rescan(dir)
    suspend fun scan(root: VfsPath, maxDepth: Int = 8): List<VfsPath> // for Tools overview
}
```

### 5.6 `FileOperationService`
Foreground service (`FOREGROUND_SERVICE_DATA_SYNC` type on API 34+). `OperationRegistry` (process singleton) holds `StateFlow<List<OperationProgress>>`; UI binds via the registry, not the service. Notification channel `ops` with progress, cancel action; completion notification auto-cancels after 5 s. Jobs: `Copy, Move, Delete, Compress, Extract, ExportApk, ComputeSize`.

---

## 6. Wireless Transfer

### 6.1 `FtpServerEngine` (core.ftp — pure Kotlin, JVM-testable)
```kotlin
data class FtpConfig(
    val port: Int = 2121, val bindAddress: String = "0.0.0.0",
    val root: VfsPath, val readOnly: Boolean = false,
    val auth: FtpAuth = FtpAuth.Anonymous,           // or FtpAuth.Credentials(user, password)
    val passivePorts: IntRange = 50000..50100,
    val idleTimeoutMs: Long = 300_000, val maxClients: Int = 8,
    val serverName: String = "MiniFile FTP",         // appears in 220 banner → used by companion `scan`
)
sealed interface FtpEvent { ClientConnected, ClientDisconnected, Command(ip, verb, arg), Transfer(ip, kind, path, bytes, durationMs), Error(msg), Started(port), Stopped }

class FtpServerEngine(config: FtpConfig, fs: FileSystem, externalIpProvider: () -> List<String>) {
    fun start()            // binds ServerSocket; throws BindException (UI shows "port in use")
    fun stop()             // closes listener + all sessions + data sockets
    val events: SharedFlow<FtpEvent>; val activeSessions: StateFlow<Int>; val boundPort: Int
}
```
**Command set** (replies per RFC 959/3659/2389/2428):
`USER, PASS, QUIT, NOOP, SYST ("215 UNIX Type: L8"), FEAT (lists `UTF8, MLST type*;size*;modify*;perm*;, MLSD, SIZE, MDTM, REST STREAM, EPSV, PASV, TVFS`), OPTS UTF8 ON, PWD/XPWD, CWD, CDUP, TYPE (A/I; data always sent raw), MODE S, STRU F, PASV, EPSV (also `EPSV ALL`), PORT, EPRT, LIST, NLST, MLSD, MLST, SIZE, MDTM, REST, RETR, STOR, APPE, DELE, MKD/XMKD, RMD/XRMD, RNFR/RNTO, ABOR, STAT (basic), HELP`. Unknown → `502`. Not logged in → `530`. Write command while read-only → `550 Read-only server`.

**Path jail (`FtpPathResolver`)** — virtual absolute paths (`/a/b`) are normalized by splitting on `/`, dropping empty and `.` segments, resolving `..` by popping (never past root), rejecting NUL and backslash-containing segments; the result maps to `root` by descending segment by segment via `FileSystem.child`. Therefore `CWD ../../..`, `RETR /../../etc/passwd`, and encoded variants can never leave `root`. For `Local` roots the resolver additionally runs `LocalFileSystem.isInside(resolvedFile, rootFile)` (canonical-path/symlink check, §4.2) **before every** list/stat/open/create/rename/delete; failure → `550 Access denied`. **Unit-tested with an adversarial table incl. a symlink-escape case** (JVM temp dir + `Files.createSymbolicLink`).

**Passive mode:** `PASV` binds an ephemeral listener from `passivePorts` on the wildcard address, replies `227 Entering Passive Mode (h1,h2,h3,h4,p1,p2)` where `h` = the **local address of the control socket** (`controlSocket.localAddress`) — this is the interface the client reached us on, so it is correct on Wi-Fi, hotspot and USB tethering. If the control connection is **IPv6** (incl. IPv4-mapped not representable), `PASV` replies `522 Network protocol not supported, use (1)` (RFC 2428) and the client falls back to `EPSV` (Python `ftplib` does so automatically); `EPSV` replies `229 Entering Extended Passive Mode (|||port|)` (address-less). The control listener binds `InetSocketAddress(port)` (dual-stack where the OS allows; Android phones typically reach it over IPv4). Data listener accept timeout 30 s; only one data connection per session; the accepted peer must match the control peer IP (anti-bounce) unless `allowForeignDataPeer` is set for testing. **Active mode** `PORT`/`EPRT` connects from `port-1` best-effort (falls back to ephemeral).

**Reply formats (part of the interop contract, §6.4):** `PWD`/`MKD` → `257 "<path>"` with embedded `"` doubled; `MLSD` omits the `modify` fact when the mtime is unknown (SAF returns 0); `LIST` dates use `MMM dd HH:MM` for entries newer than 6 months and `MMM dd  yyyy` otherwise (English month names, `Locale.US`); `STOR`/`MKD` whose parent does not exist → `550`; `RNTO` whose target is on a different backend → `550`; `REST` before `STOR` on a `Saf` root → `504 REST not supported for STOR on this volume` (`REST`+`RETR` works on both backends; `APPE` on SAF uses `"wa"` mode via `openOutput(append=true)`).

**Listings:** `LIST` emits UNIX `ls -l` style (`drwxr-xr-x 1 owner group size Mon dd HH:MM name`); `MLSD/MLST` emit facts `type=dir|file;size=;modify=YYYYMMDDHHMMSS;perm=…`. Hidden files are always listed (the FTP client decides). UTF-8 encoded names always.

**Transfers:** `RETR` honours `REST` offset (Local: `RandomAccessFile`/`skip`; SAF: `InputStream.skip`), `STOR` creates/truncates via `FileSystem.createFile`+`openOutput`, `APPE` appends; writes go to the final name directly (simplicity; the UI log shows partial uploads if the client aborts). Each transfer updates `FtpEvent.Transfer` with bytes & duration for the live log and notification ("↑ 2 ↓ 5 files").

### 6.2 `FtpServerService` (service)
Foreground service with `foregroundServiceType="specialUse"` + permission `FOREGROUND_SERVICE_SPECIAL_USE` + manifest `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="ftp-server"/>` (we are not Play-distributed; `dataSync` would be killed by the Android 15 ~6 h/24 h FGS timeout). Still override `Service.onTimeout(startId, fgsType)` (API 35) to stop the engine gracefully and post a "MiniFile FTP was stopped by the system — tap to restart" notification. The service holds a `WifiManager.WifiLock` (`WIFI_MODE_FULL_LOW_LATENCY` on API 29+, `WIFI_MODE_FULL_HIGH_PERF` below) + partial `WakeLock` while running; notification channel `ftp` with URL text and **Stop** action. The server is started **only from the foreground UI** (Start button) — no background auto-start (blocked on API 31+ for FGS launches from `NetworkCallback`).

`LanAddressResolver` enumerates `NetworkInterface.getNetworkInterfaces()` and accepts every interface that is up, non-loopback, non-virtual, with an IPv4 address (**not** filtered by `isSiteLocalAddress()` alone — that would miss 100.64/10 CGNAT, 169.254/16 link-local and non-RFC1918 corporate LANs). Ordering for display: site-local on `wlan*` first, then `ap*/swlan*` (hotspot), then `rndis*/usb*` (tethering), then everything else; refreshed via `ConnectivityManager.NetworkCallback` while the UI is visible. The Wireless screen observes `FtpServerState(isRunning, urls, clients, log)` from a process singleton `FtpServerHolder`.

Manifest permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` (FTP), `FOREGROUND_SERVICE_DATA_SYNC` (`FileOperationService` keeps `dataSync`), `POST_NOTIFICATIONS`, `WAKE_LOCK`, plus storage: `READ/WRITE_EXTERNAL_STORAGE` (`maxSdkVersion` 32/29), `MANAGE_EXTERNAL_STORAGE`, `QUERY_ALL_PACKAGES`, `REQUEST_INSTALL_PACKAGES` (for opening APKs). `FileProvider` (`xml/file_paths.xml`) declares `<external-path name="ext" path="."/>` **and** `<root-path name="root" path="."/>` so Share/Open works for secondary-volume File-backend paths (`/storage/XXXX-XXXX/...`); SAF items are shared by their `content://` URI directly. In-app warning: FTP is unencrypted; use on trusted Wi-Fi only.

### 6.3 PC Companion (Python 3, stdlib only)

**`client.py`**
```python
class MiniFileClient:
    def __init__(self, host, port=2121, user="anonymous", password="minifile@", timeout=10, encoding="utf-8")
    def connect(self) -> ServerInfo        # login, OPTS UTF8 ON, FEAT parse, set_pasv(True); banner check
    def close(self)
    def list(self, path="/", hidden=True) -> list[RemoteEntry]     # MLSD → fallback LIST parser
    def stat(self, path) -> RemoteEntry | None                      # MLST → SIZE/MDTM fallback
    def walk(self, path) -> Iterator[tuple[str, list[RemoteEntry], list[RemoteEntry]]]
    def download(self, remote, local, resume=False, progress=None, overwrite="ask")   # file or dir
    def upload(self, local, remote, progress=None, overwrite="ask")                   # file or dir
    def read_text(self, remote, encoding=None) -> tuple[str, str]   # (text, encoding)
    def write_text(self, remote, text, encoding="utf-8")
    def mkdir(self, path, parents=False); def remove(self, path, recursive=False); def rename(self, src, dst)
    def nomedia(self, dir_path, state: bool | None) -> bool        # None = query
    def sync(self, local_dir, remote_dir, direction: "to_phone"|"to_pc"|"both", delete=False, dry_run=False, exclude=(), progress=None) -> SyncPlan
@dataclass class RemoteEntry: name: str; path: str; is_dir: bool; size: int; mtime: float | None; perm: str
def discover(subnet: str | None = None, port=2121, timeout=0.3, workers=128) -> list[Discovered]   # scanner.py; banner must contain "MiniFile"
```
Path handling: client always sends `/`-separated POSIX paths; `posixpath.normpath` + refuse `..` escapes client-side as a courtesy (the server jail is the real guard). Mtime via `MDTM` (UTC) compared with 2 s tolerance; `MFMT` not required.

**`cli.py`** implements DESIGN §4.3 with `argparse` subparsers; `--json` prints one JSON object (or NDJSON for streaming progress). Errors → messages on stderr + exit codes 0/1/2/3/4.

**`webui.py`** — `ThreadingHTTPServer` bound to `127.0.0.1:<port>` (default 8765). Security model (all enforced in one `_authorize()` hook, unit-tested):
- **Token**: generated per run (`secrets.token_urlsafe(32)`), passed once in the launch URL `?t=<token>`; the first page load sets cookie `mf_token` (`SameSite=Strict; HttpOnly`) and the SPA also keeps it in memory. Every `/api/*` request must carry header `X-MF-Token: <token>`; **the only exception is file-returning navigations** (`GET /api/download`) which may authenticate with the `mf_token` cookie because the browser cannot add headers to a navigation/download.
- **`Host` allow-list** against DNS rebinding: only `127.0.0.1:<port>`, `localhost:<port>`, `[::1]:<port>`; anything else → `403`.
- **`Origin`/`Referer` check** on state-changing methods: if present, must be `http://127.0.0.1:<port>` or `http://localhost:<port>`.
- **Static files**: served from `static/` only; `os.path.realpath(joined).startswith(realpath(static_dir) + os.sep)` or `404`.
- **Local filesystem confinement**: `/api/local/*` paths are `realpath`-normalized; with `--jail DIR` (default: off, i.e. whole user filesystem) they must be under `DIR`.
- **Multipart on Python 3.13**: `cgi.FieldStorage` no longer exists → `/api/upload` uses a **stdlib streaming multipart parser** (own boundary scanner over `rfile` with a 1 MiB rolling buffer, or `email.parser.BytesFeedParser` for small bodies) that streams each part directly into FTP `STOR` without buffering whole files in memory. Filenames are sanitized (`os.path.basename`, no `..`, no NUL).

Serves the `static/` SPA. JSON API:

| Method & path | Body / query | Returns |
|---------------|--------------|---------|
| `GET /api/status` | — | `{connected, host, port, user, server_banner}` |
| `POST /api/connect` | `{host, port, user, password, remember}` | status |
| `POST /api/disconnect` | — | status |
| `GET /api/scan` | `?subnet=&port=` | `[{host, port, banner, rtt_ms}]` |
| `GET /api/phone/list` | `?path=&hidden=1` | `{path, parent, entries:[RemoteEntry]}` |
| `GET /api/local/list` | `?path=&hidden=1` | same shape (`os.scandir`); roots: home, drives (Windows), `/` |
| `POST /api/phone/mkdir` · `/api/local/mkdir` | `{path, name}` | `{path}` |
| `POST /api/phone/rename` · `/api/local/rename` | `{path, new_name}` | `{path}` |
| `POST /api/phone/delete` · `/api/local/delete` | `{paths:[...], recursive}` | `{deleted, failed:[...]}` |
| `POST /api/phone/nomedia` | `{path, state}` | `{path, state}` |
| `GET /api/phone/text` · `GET /api/local/text` | `?path=&encoding=` | `{text, encoding}` |
| `PUT /api/phone/text` · `PUT /api/local/text` | `{path, text, encoding}` | `{size}` |
| `POST /api/transfer` | `{direction:"up"|"down", items:[{src,dst}], overwrite}` | `{job_id}` (queued; runs in worker thread) |
| `GET /api/transfers` | — | `[{job_id, state, current, done_bytes, total_bytes, speed, error}]` |
| `POST /api/transfers/{id}/cancel` | — | `{ok}` |
| `POST /api/upload` | multipart (browser drop) `?dst=/Download` | `{stored:[...]}` — streams through to FTP `STOR` |
| `GET /api/download` | `?path=` (cookie auth allowed) | file stream (`Content-Disposition`), dirs zipped on the fly |
| `GET/PUT /api/favorites` | `[{name, path}]` | list (stored in config.json) |

**`gui.py`** — `try: import tkinter` → if available, a simple two-listbox Tk window (optional, best-effort); on `ImportError` or no display (`$DISPLAY` unset on Linux), print a one-line notice and start `webui` (opening the browser unless `--no-browser`). `python3 -m minifile_companion gui` must therefore **always work on this host** by serving the web UI.

### 6.4 Interoperability Contract (Android ⇄ Companion) — owned jointly, verified by `tester`
1. Banner: `220 MiniFile FTP Server ready` (contains `MiniFile`).
2. `FEAT` advertises `UTF8`, `MLSD`, `MLST`, `SIZE`, `MDTM`, `REST STREAM`, `EPSV`, `PASV`, `TVFS`.
3. `OPTS UTF8 ON` → `200`. All names UTF-8 both ways; test name: `测试 файл ✓ (1).txt`.
4. `MLSD` facts: `type`, `size`, `modify` (UTC `YYYYMMDDHHMMSS`), `perm`; `type=cdir`/`pdir` entries may be omitted. **`modify` is omitted when unknown** (SAF mtime 0) and the client maps a missing fact to `mtime=None` (sync treats `None` as "always copy if sizes differ").
5. `SIZE` on a directory → `550`. `MDTM` on a missing file → `550`.
6. `STOR` into a read-only server → `550 Read-only server`; client surfaces "Phone server is read-only".
7. `.nomedia` is an ordinary empty file; `nomedia --status` = `SIZE /dir/.nomedia` succeeds (`550` = absent); `nomedia --on` = `STOR /dir/.nomedia` with an empty body; `nomedia --off` = `DELE /dir/.nomedia`.
8. Paths with `..` → server resolves within root (never `550 outside root` leak; it just clamps) — client `ls /../..` returns the root listing. Symlink escapes on the server → `550 Access denied`.
9. Idle control connection closed after `idleTimeoutMs` with `421`.
10. `PWD`/`MKD` reply `257 "<path>"` with embedded `"` doubled (`""`); the client un-doubles. `LIST` lines are UNIX style with dates `MMM dd HH:MM` (< 6 months) or `MMM dd  yyyy`; the client's `LIST` parser accepts both, but prefers `MLSD`.
11. `STOR`/`MKD` with a missing parent → `550` (the client creates parents itself with `mkdir -p` semantics). `RNTO` across backends → `550`; the client falls back to copy+delete only when the user passes `mv --copy-fallback`.
12. `REST` + `RETR` works everywhere; `REST` + `STOR` returns `504` on SAF roots → the client disables upload-resume when it sees `504` and restarts the upload. `APPE` is supported on both backends.
13. The Python test fixture (`companion/tests/ftp_fixture.py`, a tiny stdlib FTP server implementing the same contract incl. items 10–12) exists so companion tests run without the phone; `tester` additionally runs the companion suite against the **Kotlin** `FtpServerEngine` launched on the JVM (§8.4). Companion tests honour `MINIFILE_TEST_SERVER=host:port` and `MINIFILE_TEST_ROOT=<server-side dir>` (for on-disk state assertions); tests that need fixture-only modes (read-only, bad credentials, idle timeout) call `self.skipTest(...)` when an external server is configured.

---

## 7. Persistence & Settings

`KeyValueStore` interface (impl: `SharedPreferences` named `minifile`; test impl: in-memory map).

| Key | Type | Default |
|-----|------|---------|
| `show_hidden` | Boolean | false |
| `view_mode` | `LIST`/`GRID` | LIST |
| `sort` | JSON `{by:"NAME",asc:true,foldersFirst:true}` | as shown |
| `theme`, `dynamic_color`, `show_thumbnails`, `editor_font_sp`, `editor_wrap`, `editor_line_numbers`, `remember_last_folder`, `last_folder` | misc | sensible |
| `favorites` | JSON array of `Favorite` | 3 seeded entries |
| `saf_trees` | JSON `{volumeKey: treeUri}` | `{}` |
| `ftp_port`, `ftp_root`, `ftp_auth_mode`, `ftp_user`, `ftp_password` (app-private `SharedPreferences`, `MODE_PRIVATE`; no `security-crypto` — see DESIGN §4.1), `ftp_read_only`, `ftp_pasv_range`, `ftp_idle_timeout`, `ftp_keep_screen_on` | — | 2121, primary root, anonymous, …, false, 50000-50100, 300, true |
| `onboarding_done` | Boolean | false |

```kotlin
data class Favorite(val id: String, val name: String, val path: VfsPath, val createdAt: Long, val lastUsedAt: Long, val order: Int, val color: Int? = null)
interface FavoritesRepository {
    val favorites: StateFlow<List<Favorite>>
    suspend fun add(name: String, path: VfsPath): Favorite; suspend fun remove(id: String); suspend fun rename(id: String, name: String)
    suspend fun reorder(ids: List<String>); suspend fun touch(id: String); fun isFavorite(path: VfsPath): Boolean
    suspend fun exportJson(): String; suspend fun importJson(json: String, merge: Boolean)
}
```

---

## 8. Verification Plan

### 8.1 Android unit tests (`app/src/test`, JUnit 4, pure JVM, run by `testDebugUnitTest`)
| Suite | Covers |
|-------|--------|
| `PathUtilsTest` | normalization, `isAncestor`, unique naming `(1)`, name validation (illegal chars, `.`/`..`, empty), natural sort order, hidden detection |
| `LocalFileSystemTest` | list/stat/create/rename/delete on `TemporaryFolder`, hidden filtering, `hasNomedia` flag, permissions string |
| `FileOperationsTest` | copy/move (same & cross `FakeFileSystem` backends), conflict SKIP/OVERWRITE/KEEP_BOTH, move-into-self rejection, cancellation leaves no partial moves, delete recursive, result counts |
| `ZipArchiverTest` | roundtrip single/multiple/folders/empty dirs/UTF-8 names, levels, selective extract, overwrite flag, **zip-slip**: crafted entries `../evil`, `/abs`, `C:\x`, `a/../../b`, `..\\x`, NUL → skipped & nothing written outside; zip-bomb thresholds |
| `ChecksumCalculatorTest` | MD5/SHA-256 known vectors (empty file, "abc"), cancellation |
| `TextFileCodecTest` | BOM detection, UTF-8/16, CRLF preserve, binary sniff |
| `NomediaManagerTest` | set/unset/has, scanner callback invoked, SAF-like fake |
| `FavoritesRepositoryTest` | add/remove/reorder/touch/export/import with in-memory `KeyValueStore`; seeded defaults |
| `TransferTargetValidatorTest` | SameDirectory / InsideSource / ReadOnly / Unavailable reasons |
| `FtpPathResolverTest` | adversarial table (`..`, `%2e%2e` literal, backslashes, NUL, deep traversal) always inside root; **symlink escape** (`root/link -> outside`, created with `Files.createSymbolicLink`) → rejected |
| `FtpEngineServeTest` | §8.4 item 1 — skipped unless `MINIFILE_FTP_SERVE=1` (interop harness for `tester`) |
| `SemanticVersionTest`, `ReleaseParserTest`, `AppUpdaterTest` | §11.1 — version grammar, GitHub JSON (latest/list/asset priority), HTTP rules via loopback `HttpServer` (redirects, downgrade refusal, caps, corrupt/cancelled downloads leave no `.tmp`) |
| `FtpServerEngineTest` | boots on port 0/loopback with `LocalFileSystem` on a temp dir; uses `sun.net.ftp`-free hand-rolled socket client or `java.net.Socket` script: login anon & credentials (wrong password → 530), `FEAT`, `OPTS UTF8 ON`, `PWD/CWD/CDUP`, `PASV` reply parses to the control-local IP, `EPSV`, `LIST/NLST/MLSD/MLST` formats, `SIZE/MDTM`, `RETR` with `REST`, `STOR`, `APPE`, `DELE/MKD/RMD/RNFR/RNTO`, UTF-8 filename roundtrip, read-only → 550, jail escape attempts, idle timeout, concurrent sessions, `stop()` closes everything |
| `BrowserViewModelTest` (optional) | pure state reducer tests for sort/filter/selection if the VM is split into a testable reducer |

Target: **100 % pass**, ≥ 90 % line coverage of `core.*` (informational; not gated).

### 8.2 Android build & manual/instrumented checklist (`tester`, device or emulator if available via `adb`)
- `./gradlew --offline assembleDebug testDebugUnitTest` green; APK installs on API 26, 30, 33/34/35 emulators if present.
- Lint: no `MissingPermission`, `ExportedService`, `InlinedApi` errors; `lintDebug` warnings reviewed.
- Manual script mirrors DESIGN §9 flows 1–8, plus: All-files access grant/deny paths; SAF mount of `Android/data` on API 30 (allowed) and API 33+ (per-package); USB OTG hot-plug; FTP start/stop, notification Stop, port-in-use error, `PASV` from a PC on Wi-Fi and from a hotspot client; process death restores tab/dir; TalkBack labels on badges.

### 8.3 Companion tests (`companion/tests`, `unittest`)
- `ftp_fixture.py`: threaded stdlib FTP server (subset: same command set as §6.1 incl. `MLSD`, `UTF8`, `PASV/EPSV`, `REST`, read-only mode, auth) serving a temp dir — the reference for the interop contract.
- `test_client.py`: connect/auth failures, `list/stat/walk`, UTF-8 names, download/upload file & dir with progress, resume, overwrite policies, `read_text/write_text` with encodings, `mkdir -p`, recursive `remove`, `rename`, `nomedia` on/off/status, `sync` to_phone/to_pc/both/delete/dry-run/exclude, path normalization (`..` never sent raw).
- `test_cli.py`: every subcommand via `subprocess`/`main(argv)`, `--json` output schema, exit codes 0/1/2/3/4, `edit` with `EDITOR=python3 -c …` mock, `scan` against the fixture on 127.0.0.1 (subnet override `127.0.0.1/32`).
- `test_webui.py`: start server on port 0, auth enforcement (401 without `X-MF-Token`, 403 on bad `Host`, 403 on foreign `Origin`, cookie accepted **only** for `GET /api/download`), static path traversal (`/static/../webui.py` → 404), every `/api/*` endpoint incl. streaming multipart upload (incl. a > 5 MB file and UTF-8 filename, no `cgi` import anywhere), download streaming, directory zip download, transfer queue lifecycle & cancel, favorites CRUD, local path confinement (cannot list outside allowed roots when `--jail` is set).
- `test_scanner.py`: banner match, timeout handling, concurrent workers.
- `test_updater.py`: §11.3 (mock GitHub API on 127.0.0.1, redirect/scheme rules, SHA-256, zip-slip-safe staging, CLI exit codes, `/api/update`).

### 8.4 Cross-component integration (`tester`)
1. **`FtpEngineServeTest`** (owned by **`coder-android`**, M3 deliverable, lives in `app/src/test/java/com/minifile/explorer/core/ftp/FtpEngineServeTest.kt`): a JUnit test that is **skipped via `Assume.assumeTrue(System.getenv("MINIFILE_FTP_SERVE") == "1")`** in normal runs. When enabled it starts `FtpServerEngine` with `LocalFileSystem` and blocks:
   - env: `MINIFILE_FTP_PORT` (default `0` = ephemeral), `MINIFILE_FTP_ROOT` (default: new temp dir), `MINIFILE_FTP_READONLY` (`1`/`0`), `MINIFILE_FTP_USER`/`MINIFILE_FTP_PASS` (unset = anonymous), `MINIFILE_FTP_READY_FILE`, `MINIFILE_FTP_STOP_FILE`.
   - prints `READY port=<n> root=<path>` to stdout **and** writes the same line to `$MINIFILE_FTP_READY_FILE`; then polls (500 ms) until `$MINIFILE_FTP_STOP_FILE` exists, stops the engine and passes.
   - run: `MINIFILE_FTP_SERVE=1 MINIFILE_FTP_READY_FILE=/tmp/mf.ready MINIFILE_FTP_STOP_FILE=/tmp/mf.stop ./gradlew --offline :app:testDebugUnitTest --tests '*FtpEngineServeTest*' -i`.
   `tester` drives this from a script under `tools/` (its own directory) without touching `app/**`.
2. Run the Python suite with `MINIFILE_TEST_SERVER=127.0.0.1:<port> MINIFILE_TEST_ROOT=<root>` so `test_client.py`/`test_cli.py` target the **Kotlin** engine instead of the Python fixture; all non-skipped tests must pass identically (this proves contract §6.4).
3. End-to-end CLI script: `scan --subnet 127.0.0.1/32` → `mkdir /it` → `put` a tree with UTF-8 names → `ls -R` → `sync --both` → `cat`/`edit` → `nomedia --on` → `rm -r`; verify filesystem state on the server side.
4. Record results in `TEST_REPORT.md` on `main`.

### 8.5 Security checklist (reviewed by `critic`, verified by `tester`)
- [ ] Zip-slip & zip-bomb guards present and tested (§5.2).
- [ ] FTP jail cannot be escaped by `..`, absolute paths, backslashes, NUL, or symlinks (`FtpPathResolver` + `LocalFileSystem.isInside` canonical-path check before every operation, §4.2/§6.1).
- [ ] `PASV` on IPv6 control connections returns `522`; `LanAddressResolver` shows all usable IPv4 addresses.
- [ ] `FtpServerService` uses `specialUse` FGS type with `onTimeout` handling; no background auto-start.
- [ ] PASV data connections only accepted from the control peer IP; passive range bounded; listeners closed on session end.
- [ ] Credentials never logged; FTP log redacts `PASS ****`.
- [ ] `FileProvider` paths limited (`xml/file_paths.xml`); no `file://` URIs leave the app.
- [ ] Web UI binds to `127.0.0.1` only; `Host` allow-list (anti DNS-rebinding), `X-MF-Token` header on all `/api/*` (cookie only for `GET /api/download`), `Origin` check on mutations, no directory traversal in `/api/local/*` (`os.path.realpath` under allowed roots when jailed) nor in static file serving (`static/` only); multipart parsed by a stdlib streaming parser (Python 3.13 has no `cgi`).
- [ ] `FileProvider` declares `<external-path>` **and** `<root-path>`; `MainActivity` calls `enableEdgeToEdge()` and all bottom chrome is inset-aware.
- [ ] Services are not exported; notifications use `PendingIntent.FLAG_IMMUTABLE`.
- [ ] No network permission use besides the FTP server, the companion loopback, and the HTTPS-only GitHub update check (§11).
- [ ] Updater: https-only, manual redirects ≤ 5 with no downgrade, JSON/size caps, `PK\x03\x04` + `AndroidManifest.xml`, `getPackageArchiveInfo` package == own, `longVersionCode >` installed, signing-cert set equality (API 28+) — mismatch is fatal; `.tmp` → atomic rename inside `cacheDir/updates/`; `FileProvider` `cache-path` limited to `updates/`; stale files purged. Companion: same redirect/scheme rules, SHA-256 vs `SHA256SUMS.txt`, zip-slip-safe staging, never overwrite the running tree.

---

## 9. Work Split & Ownership

| Owner | Owns | Must not touch |
|-------|------|----------------|
| `coder-android` | `app/**`, root Gradle files, `libs.versions.toml`, `.github/workflows/release.yml` (§11.4) | `companion/**` |
| `coder-companion` | `companion/**` | `app/**`, Gradle files |
| `tester` | `TEST_REPORT.md`, integration runner scripts under `tools/` (new dir), ACID emulator E2E (§12), merges to `main`, GitHub repo/tag/release publication (§11.5) | feature code (files bugs to owners instead) |
| `designer` | `DESIGN.md`, `ARCHITECTURE.md` (amendments on request) | — |

Branch flow: `main` ← `test/verification` ← (`feat/android-app`, `feat/pc-companion`). Commits must keep `./gradlew --offline assembleDebug testDebugUnitTest` and `python3 -m unittest discover -s companion/tests` green.

---

## 10. Implementation Order (suggested milestones)

**Android** — **M0 offline-building Compose skeleton (committed first, §1.1)** → M1 `core.vfs`+`PathUtils`+tests → M2 `core.ops` (ops, zip, checksum, text, nomedia)+tests → M3 `core.ftp` engine+tests **+ `FtpEngineServeTest` harness (§8.4)** → M4 data layer (prefs, favorites, SAF, volumes) → M5 UI shell (edge-to-edge tabs, Home incl. *Mount a folder…*, Browser, selection, clipboard bar, Favorite picker) → M6 Editor, Archive preview, Properties → M7 Wireless screen + `specialUse` service → M8 Apps Inspector, Tools, polish → **M9 `core.update` + Updates card + release signing config + `release.yml` (§11)**.

**Companion** — C1 `client.py` + fixture + tests → C2 `cli.py` + tests → C3 `webui.py` API + tests → C4 SPA (`static/`) → C5 `gui.py` fallback, `scan`, `shell`, README → **C6 `updater.py` + `update` command + `/api/update` + badge (§11.3)**.

---

## 11. Auto-Update & GitHub Releases (`codescv/minifile`)

Canonical repository: `https://github.com/codescv/minifile` (SSH `git@github.com:codescv/minifile.git`). Product behaviour is in DESIGN §11; this section fixes the code contracts and security rules. Reference implementation pattern: `~/Developer/Lemuroid/lemuroid-app/src/main/java/com/swordfish/lemuroid/app/shared/update/` (`AppUpdater`, `SemanticVersion`, `ReleaseInfo`, `CancelToken`, `UpdateException`) — port the structure, not the Lemuroid-specific UI.

### 11.1 Android `core.update` (pure JVM)

```kotlin
// SemanticVersion.kt
data class SemanticVersion(val major: Int, val minor: Int, val patch: Int, val preRelease: String? = null) : Comparable<SemanticVersion> {
    companion object {
        fun parse(s: String): SemanticVersion?            // accepts "v1.2.3", "1.2.3", "1.2.3-beta.1", "1.2" (→ patch 0); null on garbage
        fun stripDebugSuffix(v: String): String?           // "1.0.0-DEBUG"/"-debug" → "1.0.0"
        fun isNewerVersion(current: String, candidate: String): Boolean   // false when either fails to parse
    }
}
// ReleaseInfo.kt
@Serializable data class ReleaseAsset(val name: String, @SerialName("browser_download_url") val url: String, val size: Long = -1, @SerialName("content_type") val contentType: String? = null)
@Serializable data class ReleaseJson(@SerialName("tag_name") val tagName: String, val name: String? = null, val body: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null, @SerialName("published_at") val publishedAt: String? = null,
    val prerelease: Boolean = false, val draft: Boolean = false, val assets: List<ReleaseAsset> = emptyList())
object ReleaseParser {
    fun parseLatest(json: String): ReleaseJson                      // object form (/releases/latest)
    fun parseList(json: String, includePre: Boolean): ReleaseJson?  // array form (/releases?per_page=5): first non-draft (and non-pre unless includePre)
    fun pickApk(r: ReleaseJson): ReleaseAsset?                      // priority: name matches ^minifile-.*-release\.apk$ > any *.apk; https URLs only
    fun pickCompanionZip(r: ReleaseJson): ReleaseAsset?             // ^minifile-companion-.*\.zip$
}
// AppUpdater.kt
class AppUpdater(
    latestUrl: String = "https://api.github.com/repos/codescv/minifile/releases/latest",
    listUrl: String = "https://api.github.com/repos/codescv/minifile/releases?per_page=5",
    openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },   // test seam
) {
    fun checkLatestRelease(currentVersion: String, includePre: Boolean = false, cancel: CancelToken? = null, timeoutMs: Int = 15_000): UpdateCheckResult
    fun downloadApk(url: String, dest: File, expectedSize: Long, cancel: CancelToken?, validator: (File) -> Unit, onProgress: (Long, Long) -> Unit): File
}
data class UpdateCheckResult(val currentVersion: String, val latest: ReleaseJson, val apk: ReleaseAsset?, val updateAvailable: Boolean)
class UpdateException(val code: UpdateErrorCode, message: String, cause: Throwable? = null) : IOException(message, cause)
enum class UpdateErrorCode { NETWORK, TIMEOUT, NOT_FOUND, RATE_LIMITED, BAD_JSON, NO_ASSET, CORRUPT_DOWNLOAD, VERIFY_FAILED, CANCELLED, INSECURE_URL }
```

**HTTP rules (binding, tested):**
- Headers: `Accept: application/vnd.github+json`, `X-GitHub-Api-Version: 2022-11-28`, `User-Agent: MiniFile/<version> (Android)`; for assets `Accept: application/octet-stream`, `Accept-Encoding: identity`.
- `/releases/latest` → on HTTP 404 fall back to `/releases?per_page=5`; HTTP 403/429 with `X-RateLimit-Remaining: 0` → `RATE_LIMITED` (UI: "GitHub rate limit, try later").
- JSON body capped at **2 MiB**; download capped at `size` from the asset (or 512 MiB when unknown); `Content-Length` must equal `size` when both known.
- **Redirects followed manually**: `instanceFollowRedirects = false`, ≤ **5** hops on `301/302/303/307/308`, each `Location` resolved against the previous URL, scheme must be `https` (`http` is rejected outright — **no https→http downgrade**, and the initial URL must be https). The manifest keeps `usesCleartextTraffic` at its default `false`.
- Download streams to `dest.tmp` (`cacheDir/updates/minifile-<version>.apk.tmp`), progress every 256 KiB / 100 ms, cancel via `CancelToken` (closes the stream). After the stream ends: byte count == expected, first 4 bytes `PK\x03\x04`, `ZipFile(tmp).getEntry("AndroidManifest.xml") != null`, then the injected `validator` runs; any failure deletes `.tmp` and throws; success → `tmp.renameTo(dest)` (atomic within `cacheDir`).
- Stale cleanup on every check: delete `cacheDir/updates/*` older than 7 days or whose version ≤ installed.

**Android-side validator (`data/update/ApkValidator.kt`)** — runs before rename:
```kotlin
val info = pm.getPackageArchiveInfo(path, flags)   // flags: GET_SIGNING_CERTIFICATES on API 28+, else GET_SIGNATURES
require(info != null)                              → VERIFY_FAILED "Not a valid APK"
require(info.packageName == context.packageName)   → VERIFY_FAILED "Package mismatch"
require(versionCodeOf(info) > installedVersionCode)→ VERIFY_FAILED "Not newer than installed"
API 28+: require(certSet(info.signingInfo) == certSet(installed.signingInfo))  // apkContentsSigners (or signingCertificateHistory) compared as Set<ByteArray.sha256>
API 26–27: compare GET_SIGNATURES arrays as sets
```
A signature mismatch is **fatal** (no "install anyway"): the system installer would fail or, worse, the user could be tricked into uninstall/reinstall. The downloaded file is deleted and the UI shows "The downloaded update is not signed by the same key as the installed app." plus the release page link.

**Install (`UpdateInstaller`)**: `FileProvider` gains `<cache-path name="updates" path="updates/"/>` (only that subdirectory). Intent: `ACTION_VIEW`, `setDataAndType(uri, "application/vnd.android.package-archive")`, `FLAG_GRANT_READ_URI_PERMISSION | FLAG_ACTIVITY_NEW_TASK`. Gate: `pm.canRequestPackageInstalls()` else `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` with `package:<id>` data, resume after return. Manifest: `<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES"/>` (already listed in §6.2 for opening APKs).

**`UpdateManager` (data/update)** — orchestrates for the UI: `StateFlow<UpdateUiState>` (`Idle, Checking, UpToDate(at), Available(release, asset), Downloading(done, total), Verifying, Ready(file), Error(code, msg)`), `check(force)`, `downloadAndInstall()`, `cancel()`, `install()`; persists `update_last_check_ms`, `update_auto_check` (default true), `update_include_pre` (false), `update_dismissed_tag`. Startup check is invoked from `MainActivity` via `lifecycleScope` once per process, throttled to 6 h (`force=false`).

**Unit tests (`core.update`, JVM):** `SemanticVersionTest` (parse table, ordering, pre-release < release, debug suffix, garbage → null), `ReleaseParserTest` (latest object, list fallback skipping drafts/pre-releases, asset priority `minifile-*-release.apk` > `*.apk`, non-https asset ignored, unknown keys ignored), `AppUpdaterTest` with a loopback `com.sun.net.httpserver.HttpServer`: 200 happy path, 404→list fallback, 403 rate limit, redirect chain of 5 ok / 6 fails, redirect to `http://` rejected, oversize JSON rejected, download size mismatch → `.tmp` deleted, non-zip body rejected, cancellation mid-stream deletes `.tmp`, validator failure deletes `.tmp`, progress callbacks monotonic.

### 11.2 Release build configuration (`app/build.gradle.kts`)
```kotlin
signingConfigs {
    create("release") {
        val ksPath = System.getenv("MINIFILE_KEYSTORE")
        if (ksPath != null) { storeFile = file(ksPath); storePassword = System.getenv("MINIFILE_KEYSTORE_PASSWORD"); keyAlias = System.getenv("MINIFILE_KEY_ALIAS"); keyPassword = System.getenv("MINIFILE_KEY_PASSWORD") }
    }
}
buildTypes {
    release {
        isMinifyEnabled = false                       // keep stack traces readable for v1; R8 rules deferred
        signingConfig = if (System.getenv("MINIFILE_KEYSTORE") != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
    }
    debug { versionNameSuffix = "-DEBUG" }
}
```
`./gradlew --offline assembleRelease` therefore always yields an installable, consistently signed APK (`app/build/outputs/apk/release/app-release.apk`). The debug keystore is the de-facto release key for v1 unless `MINIFILE_KEYSTORE` is provided; the same keystore **must** be used for every release or the in-app updater's signature check will (correctly) refuse the update. CI uses a repository secret `MINIFILE_DEBUG_KEYSTORE_B64` (base64 of `~/.android/debug.keystore` from the publishing machine) written to `~/.android/debug.keystore` before building, so CI-built APKs match locally built ones.

### 11.3 Companion updater (`companion/minifile_companion/updater.py`)
```python
__version__ = "1.0.0"                       # in minifile_companion/__init__.py; must equal the release tag without "v"
GITHUB_LATEST = "https://api.github.com/repos/codescv/minifile/releases/latest"
GITHUB_LIST   = "https://api.github.com/repos/codescv/minifile/releases?per_page=5"

def parse_version(s) -> tuple[int,int,int,str|None]            # same grammar as Kotlin SemanticVersion
def is_newer(current, candidate) -> bool
def check_latest(current=__version__, include_pre=False, timeout=15, opener=None) -> UpdateCheck   # UpdateCheck(current, latest_tag, name, notes, html_url, asset_url, asset_size, published_at, available)
def download_asset(url, dest_dir, expected_size=-1, sha256=None, progress=None, opener=None) -> Path  # writes .tmp, verifies, renames
def verify_companion_zip(path) -> str                           # returns version from minifile_companion/__init__.py inside; raises on zip-slip-unsafe names or missing files
def stage_and_apply(zip_path, install_dir, apply=False) -> Path  # extracts to install_dir.parent/"companion-v<ver>" (safe extraction), optional swap
```
Rules mirror §11.1: `urllib.request` with a custom `HTTPRedirectHandler` that counts hops (≤ 5), rejects non-`https` `Location`s, and forwards `User-Agent: MiniFile-Companion/<version>`; JSON cap 2 MiB; `Content-Length` must match `size`; SHA-256 compared against the `SHA256SUMS.txt` asset when present (fetch it, find the line for the asset name) — mismatch is fatal; extraction uses the §5.2 segment rules (`..`, absolute, drive letters, backslashes, NUL → abort the whole update, not skip); never overwrite the running tree (stage dir + optional swap; refuse swap when `.git` exists or the dir is not writable). Update checks are cached in `config.json` (`update_last_check`, `update_latest_tag`) and never block CLI commands (hint printed from cache only).

CLI: `update [--check] [--pre] [--apply] [--json] [--dest DIR]`; exit codes `0` current/applied, `10` update available (with `--check`), `3` network, `1` verify/apply failure. Web: `GET /api/update?force=0|1` → `{current, latest, available, notes, html_url, asset_url, checked_at}`; the SPA shows the badge; downloads go through the browser to the GitHub asset URL (not proxied).

Tests (`companion/tests/test_updater.py`, against a `ThreadingHTTPServer` mock on 127.0.0.1): version grammar/order, latest + list fallback, pre-release filtering, redirect limit & http downgrade refusal, size and SHA-256 mismatch → `.tmp` removed, zip-slip zip aborts, happy-path stage into sibling dir, `--apply` swap refused in a git checkout, CLI `update --check` exit code `10`, `/api/update` schema & caching, hint throttling.

### 11.4 GitHub Actions release workflow (`.github/workflows/release.yml`, owned by `coder-android`)
- Trigger: `push: tags: ['v*']` (plus `workflow_dispatch` with a `tag` input).
- Jobs:
  1. `verify-version` — `TAG=${GITHUB_REF_NAME#v}`; assert `grep -q "versionName = \"$TAG\"" app/build.gradle.kts` and `grep -q "__version__ = \"$TAG\"" companion/minifile_companion/__init__.py`; fail otherwise.
  2. `android` — `ubuntu-latest`, `actions/setup-java@v4` (temurin 21), `android-actions/setup-android@v3` (platform 35, build-tools 35.0.0), restore debug keystore from secret if present, `./gradlew assembleRelease testDebugUnitTest --no-daemon`, rename to `minifile-v$TAG-release.apk`, upload artifact.
  3. `companion` — `actions/setup-python@v5` (3.12+), `python -m unittest discover -s companion/tests`, `zip -r minifile-companion-v$TAG.zip companion -x 'companion/tests/*' '*/__pycache__/*'`.
  4. `release` — needs both; `sha256sum *.apk *.zip > SHA256SUMS.txt`; `softprops/action-gh-release@v2` with `tag_name`, `name: MiniFile v$TAG`, `generate_release_notes: true`, `prerelease: false`, `draft: false`, files = APK, ZIP, `SHA256SUMS.txt`.
- The workflow must also succeed offline-locally in spirit: every step corresponds to a documented local command (§1.1, §11.2).

### 11.5 Initial publication procedure (`tester`, after merge)
1. `main` clean: `feat/android-app` + `feat/pc-companion` + `TEST_REPORT.md` merged, `.worktrees/` removed, `git status` empty.
2. Local artefacts: `./gradlew --offline assembleRelease` → copy to `dist/minifile-v1.0.0-release.apk` (git-ignored `dist/` or `/tmp`); `cd companion && zip -r ../dist/minifile-companion-v1.0.0.zip . -x 'tests/*' '*/__pycache__/*'`; `sha256sum` both → `SHA256SUMS.txt`.
3. `gh repo create codescv/minifile --public --source=/usr/local/google/home/chii/Developer/minifile --remote=origin --push` (gh is logged in as `codescv`).
4. `git tag -a v1.0.0 -m "MiniFile v1.0.0"` && `git push origin v1.0.0`.
5. `gh release create v1.0.0 dist/minifile-v1.0.0-release.apk dist/minifile-companion-v1.0.0.zip dist/SHA256SUMS.txt --title "MiniFile v1.0.0" --notes-file <notes> --latest` (CI may also build; whichever finishes first owns the release — CI must use `fail_on_unmatched_files` and *update* an existing release rather than fail).
6. Smoke: `curl -s https://api.github.com/repos/codescv/minifile/releases/latest | python3 -c 'import json,sys; r=json.load(sys.stdin); print(r["tag_name"], [a["name"] for a in r["assets"]])'`; on the emulator, Tools → Updates → *Check now* must report "up to date" for v1.0.0; `python3 -m minifile_companion update --check` exits `0`.

---

## 12. ACID Emulator End-to-End Test Specification (`tester`)

### 12.1 Environment & invocation rules
- Emulator: ACID (`/google/bin/releases/mobile-devx-platform/acid/acid`). **Always** `export PATH="/usr/local/google/home/chii/Android/Sdk/platform-tools:$PATH"` and run `acid …` **and** the dependent `adb -s localhost:<PORT> …` commands **inside one `run_command` shell invocation** (background `acid_daemon`/`adb` processes are reaped when the command exits). Put each scenario in a script under `tools/e2e/` (e.g. `tools/e2e/run_acid_e2e.sh`) that: starts/connects the emulator, resolves `<PORT>`, runs the steps, collects artefacts into `tools/e2e/out/<timestamp>/` (git-ignored), and tears down.
- Preferred image: API 34 or 35 (`google_apis`, x86_64). Record `adb shell getprop ro.build.version.sdk` and `ro.build.fingerprint` in the report.
- Helper conventions: `SERIAL=localhost:<PORT>`; `A="adb -s $SERIAL"`; `PKG=com.minifile.explorer`; UI assertions via `$A shell uiautomator dump /sdcard/ui.xml && $A pull /sdcard/ui.xml` + `grep`/`python3 -c` on `text=`/`content-desc=`; screenshots via `$A exec-out screencap -p > step-N.png`; crash detection via `$A logcat -d -b crash` (must be empty for `$PKG`) and `$A shell dumpsys activity processes | grep -i anr`.

### 12.2 Setup steps
```bash
$A install -r app/build/outputs/apk/debug/app-debug.apk          # or the release APK; record which
$A shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow
$A shell pm grant $PKG android.permission.POST_NOTIFICATIONS      # API 33+
$A shell appops set $PKG REQUEST_INSTALL_PACKAGES allow            # for the update-install path (best effort)
$A shell mkdir -p /sdcard/MiniFileE2E/src/sub /sdcard/MiniFileE2E/dst
$A shell 'printf "hello\nworld\n" > /sdcard/MiniFileE2E/src/notes.txt; dd if=/dev/urandom of=/sdcard/MiniFileE2E/src/blob.bin bs=1k count=512'
$A shell am start -n $PKG/.MainActivity -W
```
`$A shell` is the oracle for on-device state (`ls -la`, `md5sum`, `cat`, `stat`).

### 12.3 Scenarios (each: action → UI assertion → filesystem/logcat assertion)
| # | Area | Steps (driven by `uiautomator`/`input tap` on nodes located by `content-desc`/`text`, or by deep-link intents where provided) | Pass criteria |
|---|------|-------|---------------|
| E1 | Launch & tabs | Start; dump UI; tap each `NavigationBar` item (`Browse`, `Favorites`, `Wireless`, `Tools`) | All 4 destinations render their title; no crash/ANR; screenshots saved |
| E2 | Browse & breadcrumbs | Navigate Internal → `MiniFileE2E` → `src`; dump | Breadcrumb shows `Internal › MiniFileE2E › src`; entries `sub`, `notes.txt`, `blob.bin` with sizes |
| E3 | Sort/view/search/hidden | Toggle grid, sort by Size desc, type `blob` in search, enable hidden files after `$A shell touch …/src/.hidden` | Order changes; filter leaves one row; `.hidden` appears only after toggle; preference persists after `am force-stop` + relaunch |
| E4 | Create folder/file | FAB → New folder `E2E Folder`; New file `made.txt` (editor opens) | `ls` shows both; `made.txt` size 0 |
| E5 | Text editor | Open `notes.txt`, append a line via `input text`, Save; reopen | `$A shell cat` shows the new line; status bar shows `UTF-8 · LF` |
| E6 | Copy / Move / Paste | Select `notes.txt`+`blob.bin` → Copy → navigate to `dst` → Paste here; then Cut `made.txt` → Paste in `dst` | `dst` contains copies with identical `md5sum`; `made.txt` moved (absent in `src`) |
| E7 | Favorites + Copy/Move-to-Favorite | Star `dst` as `Target`; in `src` select `sub` → More → *Copy to Favorite…* → `Target` | `dst/sub` exists; snackbar "Copied … to Target"; Favorites tab lists `Target` |
| E8 | Rename / Delete | Rename `blob.bin` → `blob2.bin`; Delete it (confirm dialog appears) | Dialog text contains "Delete"; file absent afterwards |
| E9 | Properties & checksum | Properties of `dst/notes.txt` → Compute SHA-256 | Shown hash equals `$A shell sha256sum` |
| E10 | `.nomedia` | Toggle *Hide from gallery* on `dst`; badge visible; toggle off | `.nomedia` created then removed; `content-desc="Hidden from gallery"` present then absent |
| E11 | Zip / Unzip | Select `src` → Compress → `e2e.zip`; open it → Extract to new folder; push a crafted zip (`tools/e2e/evil.zip` with `../evil.txt`) and extract | Extracted tree matches; `evil.txt` **not** created outside the destination; UI reports "1 unsafe entry skipped" |
| E12 | App Data / Volumes | Storage Home shows Internal with free/total; tap `Android/data` → system picker appears (`com.android.documentsui` in `dumpsys activity top`); press back | Picker launched; app returns without crash; Installed Apps list shows `MiniFile` entry; *Export APK* of MiniFile to `dst` produces a valid zip/apk |
| E13 | FTP server | Wireless → Start (grant notification if prompted) → UI shows `ftp://…:2121`; `$A forward tcp:12121 tcp:2121`; on PC: `python3 -m minifile_companion -H 127.0.0.1 -P 12121 ls /MiniFileE2E`, `put`, `get`, `sync --to-pc`, `cat`, `nomedia --on /MiniFileE2E/dst`, `mkdir`, `rm`; Stop from notification | Every command exits 0; files match by hash; live log in the Wireless tab lists the commands; foreground notification present while running and gone after Stop; `$A shell dumpsys activity services $PKG` shows no service afterwards |
| E14 | Updates card | Tools → Updates → *Check now* (emulator needs network) | State becomes *Up to date* (v1.0.0 is the latest) or *Update available* with a release note body; on no network a readable error, never a crash. Optionally seed a fake newer release via `adb reverse` + a local `HttpServer` and `BuildConfig.UPDATE_API_BASE` override to exercise download + signature verification (expected: debug-signed test APK → verify OK; foreign-signed APK → "not signed by the same key") |
| E15 | Process death & back stack | While in `src`, `$A shell am kill $PKG` (background) and relaunch | Returns to Browse at the last folder (or Home) with no crash; clipboard cleared gracefully |

### 12.4 Report (`TEST_REPORT.md`, written by `tester`)
Sections: 1 Build verification (commands, versions, APK sizes, `--offline` status); 2 Unit tests (Android suite counts per class, companion suite counts); 3 JVM interop (Python suite vs Kotlin engine: passed/skipped lists); 4 ACID E2E results table E1–E15 with evidence paths (screenshots, ui dumps, logcat excerpts) and device fingerprint; 5 Security audit (§8.5 + §11 rules, each with how it was verified); 6 Known issues / deviations; 7 Release & publication (repo URL, tag, release URL, asset SHA-256s, live `releases/latest` check).

