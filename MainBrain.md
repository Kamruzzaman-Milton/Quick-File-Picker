# MainBrain.md - Quick File Picker Project Specification & Architecture Truth

## 1. Project Overview & Role
- **Application Name**: Quick File Picker
- **Package Name / Namespace**: `com.quickfilepicker`
- **Application ID**: `com.quickfilepicker`
- **Architecture Role**: Production-grade, zero-network Android system integration engine that extends Android's native **Storage Access Framework (SAF)** / `DocumentsUI`.
- **Core Mission**: Solves Android's notorious file picker friction. Whenever apps (ChatGPT, Gemini, Google Drive, Chrome, Firefox, WhatsApp, Telegram, Slack, Gmail, etc.) summon the system file picker via `ACTION_OPEN_DOCUMENT`, `ACTION_GET_CONTENT`, or `ACTION_OPEN_DOCUMENT_TREE`, users are forced to tap through the navigation drawer, scroll through internal storage, and drill into deeply nested directories. Quick File Picker acts as a registered `DocumentsProvider` (`com.quickfilepicker.documents`), injecting customizable virtual directory roots and 3rd-party file manager shortcuts directly into the system file picker sidebar ("Open from" drawer).
- **Key Capabilities**:
  1. **Direct SAF Virtual Roots**: Exposes user-selected storage folders as top-level independent roots in `queryRoots()`.
  2. **Direct 3rd-Party File Manager Launch**: Instantly launches advanced file managers (MiXplorer, Solid Explorer, Cx File Explorer, Files by Google, ZArchiver, Total Commander, X-plore, etc.) directly from the picker sidebar via overlay proxy or heads-up notifications.
  3. **"Force Newest First" Smart Sorting Engine**: Bypasses system file picker alphabetical locking by prefixing filenames dynamically (`01, 02...` or `[YYYY-MM-DD]`) and honoring cursor sort query arguments.
  4. **Hierarchical Virtual Sub-Shortcuts**: Groups secondary folders inside primary roots without cluttering the main SAF drawer.
  5. **Native Thumbnail Generation Pipeline**: Embedded `PdfRenderer` generates crisp page-1 thumbnails for PDF documents; `MediaMetadataRetriever` extracts video frames; `BitmapFactory` caches image thumbs; package manager extracts APK launcher icons.
  6. **Smart Collections & Auto-Roots**: Automated detection of high-volume directories (Downloads, Documents, WhatsApp, Telegram, DCIM/Camera, Screenshots) and smart virtual groupings (PDFs, APKs, Pictures, Videos, Recent Files, Recent Folders, Favorites).
  7. **Drag-and-Drop Order Synchronization**: Reorder roots within the app via `ItemTouchHelper`, immediately firing `contentResolver.notifyChange(rootsUri)` so DocumentsUI updates on the fly.
  8. **Offline JSON Backup & Restore**: Robust schema v2 backup engine with automatic persistable URI permission re-taking.
  9. **Pure Black AMOLED & Dynamic M3 Design**: High-contrast, battery-friendly aesthetics with MIUI-inspired squircles, pills, and card elevations.
  10. **100% Offline & Zero Root**: Does NOT request `android.permission.INTERNET`. Zero cloud sync, zero telemetry, absolute local privacy.

---

## 2. Core Architectural Guardrails & Coding Constraints
- **Zero Heavy Third-Party Runtime Overhead**:
  - Exclusively built with standard AndroidX, Google Material Components (M3), Android Room Database, Kotlin Coroutines & Flow, and Gson.
  - Zero network dependencies: Retrofit, OkHttp, Ktor, and Firebase are strictly prohibited. The app has zero network capability (`android.permission.INTERNET` is omitted from the manifest).
- **MVVM Architecture & Reactive Data Flow**:
  - `MainActivity` (View) observes `MainViewModel`.
  - `MainViewModel` exposes `shortcutsWithChildrenLiveData` derived from Room's reactive `Flow<List<ShortcutWithChildren>>`.
  - Single Source of Truth: `ShortcutRepository` handles all reads and writes across Room database (`AppDatabase`), SharedPreferences (`QuickFilePickerPrefs`), and system notification of root URI changes (`notifyRootsChanged`).
- **Storage Access Framework (SAF) Compliance**:
  - Fully implements `android.provider.DocumentsProvider`.
  - Must comply with Android's strict SAF contract across API 24 through API 36 (Android 7.0 to Android 16).
  - Handles both SAF Content URIs (`content://.../tree/...`) and direct filesystem paths (`/storage/emulated/0/...` or `file:...`).
  - Supports persistable URI permissions via `contentResolver.takePersistableUriPermission()` with `FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION`.
- **Thread Safety & Asynchronous IO**:
  - All database mutations and disk operations in `MainViewModel` run on `Dispatchers.IO`.
  - Room database allows main thread queries as a safety fallback (`allowMainThreadQueries()`) because the system's `DocumentsProvider` methods (`queryRoots`, `queryDocument`, `queryChildDocuments`, `openDocument`) are invoked synchronously by system IPC Binder threads from the DocumentsUI process.

---

## 3. Storage Access Framework (SAF) Deep Integration Engine (`QuickFilePickerDocumentsProvider.kt`)

### 3.1 Contract & Authority
- **Authority**: `com.quickfilepicker.documents`
- **Permission**: `android.permission.MANAGE_DOCUMENTS` (mandatory system contract).
- **Exported**: `true` with `grantUriPermissions="true"`.
- **Intent Filter**: `android.content.action.DOCUMENTS_PROVIDER`.

### 3.2 The Core IPC Lifecycle Methods
The system file picker (`DocumentsUI`) communicates with `QuickFilePickerDocumentsProvider` across Binder IPC using four primary lifecycle methods:

```
┌────────────────────────────────────────────────────────┐
│                   System Process                       │
│        (com.google.android.documentsui)                │
└──────────────────────────┬─────────────────────────────┘
                           │ 1. queryRoots()
                           ▼
┌────────────────────────────────────────────────────────┐
│        QuickFilePickerDocumentsProvider                │
│  - Returns MatrixCursor of roots                       │
│    * Primary User Shortcuts                            │
│    * 3rd-Party App Launchers                           │
│    * Smart Collections (PDF, APK, Pictures, Videos)    │
│    * Automatic System Roots (Downloads, WhatsApp, etc) │
└──────────────────────────┬─────────────────────────────┘
                           │ 2. User selects root -> queryDocument("root:1")
                           ▼
┌────────────────────────────────────────────────────────┐
│  - Returns Document metadata (FLAGS, MIME, TITLE)      │
└──────────────────────────┬─────────────────────────────┘
                           │ 3. queryChildDocuments("1:...")
                           ▼
┌────────────────────────────────────────────────────────┐
│  - Reads physical directory / SAF Tree URI             │
│  - Injects Secondary Sub-shortcuts at top of folder    │
│  - Applies sort mode (01, 02... or [Date] prefix)     │
│  - Returns children MatrixCursor                       │
└──────────────────────────┬─────────────────────────────┘
                           │ 4. User taps file -> openDocument() or openDocumentThumbnail()
                           ▼
┌────────────────────────────────────────────────────────┐
│  - Returns ParcelFileDescriptor (MODE_READ_ONLY)       │
│  - Or renders real-time Bitmap via PdfRenderer / Media │
└────────────────────────────────────────────────────────┘
```

### 3.3 Root Projections & Cursor Building (`queryRoots`)
When `queryRoots(projection)` is invoked:
1. Projection Defaults:
   - `DocumentsContract.Root.COLUMN_ROOT_ID`
   - `DocumentsContract.Root.COLUMN_FLAGS`
   - `DocumentsContract.Root.COLUMN_ICON`
   - `DocumentsContract.Root.COLUMN_TITLE`
   - `DocumentsContract.Root.COLUMN_SUMMARY`
   - `DocumentsContract.Root.COLUMN_DOCUMENT_ID`
   - `DocumentsContract.Root.COLUMN_MIME_TYPES`
2. **Order of Execution in `queryRoots()`**:
   - **Step 1: User Bookmarks FIRST**:
     - Queries `repository.getVisiblePrimaryShortcutsSync()`.
     - Non-app shortcuts:
       - Root ID: `${shortcut.id}`
       - Flags: `FLAG_SUPPORTS_CREATE or FLAG_SUPPORTS_SEARCH or FLAG_LOCAL_ONLY`
       - Icon: Mapped from `shortcut.icon` via `getIconResourceId()`
       - Title: `"${if (shortcut.isPinned) "📌 " else ""}${shortcut.name}"`
       - Summary: If sub-shortcuts exist, shows `"X secondary shortcut(s)"`; otherwise shows formatted storage path `/storage/emulated/0/...`.
       - Document ID: `"root:${shortcut.id}"`
       - MIME Types: `*/*`
     - 3rd-Party App shortcuts (`treeUri.startsWith("app:")`):
       - Root ID: `${shortcut.id}`
       - Flags: `FLAG_LOCAL_ONLY or FLAG_SUPPORTS_SEARCH`
       - Icon: `R.drawable.ic_app`
       - Title: `"${if (shortcut.isPinned) "📌 " else ""}${shortcut.name}"`
       - Summary: `"Open ${shortcut.name} to browse files"`
       - Document ID: `"approot:${shortcut.id}"`
       - MIME Types: `*/*`
   - **Step 2: Smart Collections (if `enableSmartCollections` is true in prefs)**:
     - `smart:recent_files` (🕒 Recent Files)
     - `smart:recent_folders` (📁 Recent Folders)
     - `smart:favorites` (⭐ Favorites)
     - `smart:pdfs` (📚 PDFs, MIME: `application/pdf`)
     - `smart:apks` (📦 APK Files, MIME: `application/vnd.android.package-archive`)
     - `smart:pictures` (🖼 Pictures, MIME: `image/*`)
     - `smart:videos` (🎥 Movies & Videos, MIME: `video/*`)
   - **Step 3: Automatic System Roots (if `enableAutoFolders` is true in prefs)**:
     - Scans `Environment.getExternalStorageDirectory()` for standard directories:
       - `auto_download`: `⬇ Downloads` (`/Download`)
       - `auto_documents`: `📄 Documents` (`/Documents`)
       - `auto_pictures`: `🖼 Pictures` (`/Pictures`)
       - `auto_camera`: `📷 Camera` (`/DCIM/Camera`)
       - `auto_screenshots`: `📱 Screenshots` (`/DCIM/Screenshots`)
       - `auto_movies`: `🎥 Movies` (`/Movies`)
       - `auto_music`: `🎵 Music` (`/Music`)
       - `auto_books`: `📖 Books` (`/Books`)
       - `auto_telegram`: `💬 Telegram` (`/Telegram`)
       - `auto_whatsapp`: `💬 WhatsApp` (`/Android/media/com.whatsapp/WhatsApp/Media`)
       - `auto_fcps`: `📖 FCPS` (`/Documents/FCPS`)
       - `auto_storage`: `📂 Internal Storage` (`storageBase`)
     - Each def only added if `def.dir.exists()` returns `true`. Document ID: `"file:${def.dir.absolutePath}"`.

---

## 4. Virtual Document ID Protocol & URI Routing Map

The provider relies on strict prefix-encoded Document IDs to multiplex heterogeneous data sources into standard SAF cursors:

| Document ID Prefix / Pattern | Source Representation | Target Action / Data Returned |
|---|---|---|
| `root:<shortcutId>` | Primary Folder Root Document | Represents the top-level directory of a user shortcut. Document ID for child queries. |
| `<shortcutId>:<innerDocId>` | Primary Shortcut Child File/Folder | Physical child inside a primary shortcut. Handled via `DocumentsContract.buildDocumentUriUsingTree` or file fallback. |
| `sec:<secId>:root` | Secondary Shortcut Root | Top-level entry point for a nested sub-folder shortcut. |
| `sec:<secId>:<innerDocId>` | Secondary Shortcut Child File/Folder | Descendant file/folder within a nested sub-shortcut. |
| `approot:<shortcutId>` | 3rd-Party App Shortcut Root | Virtual directory. Querying child triggers `tryLaunchApp()` to fire the external app. |
| `appaction:<shortcutId>` | 3rd-Party App Launcher Item | Special document row: `"🚀 Open App (Tap here)"` with MIME `application/x-quickfilepicker-launch`. |
| `smart:<collectionName>` | Smart Aggregated Collection | Virtual directory for PDFs, APKs, Images, Videos, Recents, Favorites. |
| `file:<absolutePath>` | Direct On-Disk File / Directory | Bypasses SAF content URI layer; reads/writes directly via `java.io.File`. |

### 4.1 Document Querying Logic (`queryDocument`)
- When `queryDocument(documentId, projection)` is called:
  - If `sec:`: Resolves secondary shortcut from Room by `secId`. Queries underlying SAF tree URI using `targetDocId`. If SAF query fails, resolves local `File` via `resolveFileForShortcut()`.
  - If `smart:`: Returns a virtual directory row with title (e.g. `"📚 PDFs"`), MIME `MIME_TYPE_DIR`, size `0L`.
  - If `approot:`: Returns virtual directory row with app name, MIME `MIME_TYPE_DIR`.
  - If `appaction:`: Returns action item with MIME `application/x-quickfilepicker-launch`, size `1L`.
  - If `file:`: Inspects `File(filePath)`. If directory, sets `MIME_TYPE_DIR`; else detects MIME via extension. If `file.canWrite()`, sets `FLAG_SUPPORTS_WRITE or FLAG_SUPPORTS_DELETE`.
  - If Primary (`root:<id>` or `<id>:<inner>`):
    - When `isAtRoot` and secondary sub-shortcuts exist, returns root directory metadata.
    - Otherwise queries SAF via `targetDocumentUri`. If SAF fails, falls back to direct `File` resolution.

### 4.2 Child Document Querying (`queryChildDocuments`)
- When `queryChildDocuments(parentDocumentId, projection, sortOrder)` is called:
  - **For App Shortcuts (`approot:<id>`)**:
    - Calls `tryLaunchApp(packageName, appName)` immediately!
    - Adds `appaction:<id>` row so the user can tap to re-trigger launch.
  - **For Secondary Shortcuts (`sec:<secId>:<innerDocId>`)**:
    - Queries child items from SAF tree URI using `buildChildDocumentsUriUsingTree`.
    - If SAF yields no children or fails, falls back to `resolveFileForShortcut()?.listFiles()`.
    - Applies custom sort mode via `sortItems()`.
    - Returns `MatrixCursor` with `setHonoredSortArgs(result)`.
  - **For Smart Collections (`smart:<collection>`)**:
    - Delegates to `querySmartCollection()`.
  - **For File Directories (`file:<path>`)**:
    - Lists files via `File(dirPath).listFiles()`.
    - Sorts items according to `prefs.sortMode`.
  - **For Primary Shortcuts (`<shortcutId>:<innerDocId>` or `root:<shortcutId>`)**:
    - **Step 1: If at root (`isAtRoot == true`)**:
      - Injects all Secondary Shortcuts configured under this parent at the very top:
        - `docId`: `"sec:${sec.id}:root"`
        - `displayName`: `sec.name`
        - `mimeType`: `DocumentsContract.Document.MIME_TYPE_DIR`
        - `flags`: `FLAG_DIR_SUPPORTS_CREATE`
    - **Step 2: Queries physical children**:
      - Queries SAF Tree URI.
      - If SAF fails, queries file system fallback.
    - **Step 3: Sorts items**:
      - Applies folder-specific `shortcut.sortMode` (or falls back to global `prefs.sortMode`).
    - **Step 4: Returns cursor with honored sort arguments**.

---

## 5. Direct 3rd-Party File Manager Launch Engine (`LaunchProxyActivity.kt` & System Overlays)

### 5.1 The Problem It Solves
Power users often prefer managing or selecting files inside feature-packed standalone file explorers (such as MiXplorer, Solid Explorer, or Cx File Explorer) instead of Android's stripped-down `DocumentsUI`. However, the system file picker cannot natively switch tasks.

### 5.2 Multi-Tier Launch Mechanism
Quick File Picker implements a 4-tier launching architecture in `QuickFilePickerDocumentsProvider.tryLaunchApp()` and `LaunchProxyActivity`:

```
User taps App Root in Picker Drawer
              │
              ├──► Tier 1: Direct Context startActivity()
              │    (FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP)
              │    • Works if SYSTEM_ALERT_WINDOW is granted
              │    • Works on Android < 10
              │
              ├──► Tier 2: ACTION_GET_CONTENT Intent Matching
              │    (Intent(ACTION_GET_CONTENT).setPackage(pkg))
              │    • Directly requests external manager file selection mode
              │
              ├──► Tier 3: Full-Screen Priority Notification
              │    (NotificationCompat.Builder + setFullScreenIntent)
              │    • High priority alert (PRIORITY_MAX, CATEGORY_ALARM)
              │    • Pops heads-up banner on Android 10+ background restriction
              │
              └──► Tier 4: LaunchProxyActivity
                   (Theme.Translucent.NoTitleBar)
                   • Dispatched if user taps the "🚀 Open App" action row
                   • Resolves package and finishes immediately without flicker
```

### 5.3 Xiaomi / MIUI / HyperOS Special Handling
MIUI aggressively blocks background activity launches even when `SYSTEM_ALERT_WINDOW` is enabled.
- `MainActivity.openOverlayOrMiuiPermissionSettings()` directs the user to MIUI's specialized permission center:
  ```kotlin
  val miuiIntent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
      setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
      putExtra("extra_pkgname", packageName)
  }
  ```
- If MIUI settings are absent, falls back to `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` (`package:$packageName`), and finally to `ACTION_APPLICATION_DETAILS_SETTINGS`.

### 5.4 Pre-Configured File Managers
The app includes explicit `<queries>` declarations in `AndroidManifest.xml` for top Android file managers:
- `com.mixplorer` (MiXplorer standard)
- `com.mixplorer.silver` (MiXplorer Silver)
- `com.mixplorer.beta` (MiXplorer Beta)
- `pl.solidexplorer2` (Solid Explorer)
- `com.cxinventor.file.explorer` (Cx File Explorer)
- `com.google.android.apps.nbu.files` (Files by Google)
- `ru.zdevs.zarchiver` (ZArchiver)
- `com.ghisler.android.TotalCommander` (Total Commander)
- `com.lonelycatgames.Xplore` (X-plore File Manager)
- `nextapp.fx` (FX File Explorer)
- `me.zhanghai.android.files` (Material Files)
- Custom Package Input: Users can manually type any package name (e.g. `bin.mt.plus` for MT Manager).

---

## 6. Breakthrough "Force Newest First" Smart Sorting Engine & Prefix Injection

### 6.1 The System Picker Alpha-Sort Flaw
The Android system file picker (`DocumentsUI`) frequently ignores provider sort orders and re-sorts all returned items alphabetically by `COLUMN_DISPLAY_NAME` in certain third-party apps or system OEM builds. This makes finding a recently downloaded document or invoice frustrating.

### 6.2 The Virtual Prefix Inversion Engine
Quick File Picker solves this at the data provider layer inside `sortItems()`:

1. **`SORT_PREFIX_NUMBER` ("⚡ Force Newest First (01, 02... prefix)")**:
   - First sorts all items: Directories first, then by `lastModified` descending (newest timestamp first).
   - Dynamically mutates `item.displayName` before writing to `MatrixCursor`:
     ```kotlin
     var counter = 1
     for (item in items) {
         if (!item.isDirectory) {
             val prefix = String.format("%02d. ", counter++)
             item.displayName = prefix + item.displayName
         }
     }
     ```
   - When `DocumentsUI` applies its forced alphabetical sort, `"01. invoice.pdf"`, `"02. report.docx"` remain strictly at the top in true chronological order!

2. **`SORT_PREFIX_DATE` ("⚡ Force Newest First ([Date] prefix)")**:
   - Sorts items by `lastModified` descending.
   - Prepends ISO-style date format: `"[2026-11-12] resume.pdf"`.
   - Lexicographical sort on `[YYYY-MM-DD]` mirrors chronological sorting.

3. **Standard Sort Modes**:
   - `SORT_DATE_DESC`: Date Modified (Newest First)
   - `SORT_DATE_ASC`: Date Modified (Oldest First)
   - `SORT_NAME_ASC`: Name (A to Z)
   - `SORT_NAME_DESC`: Name (Z to A)
   - `SORT_SIZE_DESC`: Size (Largest First)
   - `SORT_SIZE_ASC`: Size (Smallest First)
   - `SORT_EXTENSION`: Grouped by file extension / type

4. **Honored Sort Arguments**:
   To prevent `DocumentsUI` from performing redundant secondary sorting, the provider attaches `EXTRA_HONORED_ARGS` into cursor extras:
   ```kotlin
   extraBundle.putStringArray(
       ContentResolver.EXTRA_HONORED_ARGS,
       arrayOf(
           ContentResolver.QUERY_ARG_SORT_COLUMNS,
           ContentResolver.QUERY_ARG_SORT_DIRECTION
       )
   )
   cursor.extras = extraBundle
   ```

5. **Per-Folder vs Global Inheritance**:
   - Each `Shortcut` record in Room has a nullable `sortMode: String?` field.
   - If `shortcut.sortMode != null`, the folder enforces its specific sort mode.
   - If `shortcut.sortMode == null`, it inherits the global `prefs.sortMode` from Settings.

---

## 7. Nested Virtual Sub-Shortcuts (Hierarchical Directory Tree)

### 7.1 UX Rationale
Exposing 30 different folders directly in the Android file picker's "Open from" sidebar creates clutter. Quick File Picker allows nesting secondary shortcuts inside any primary folder.

### 7.2 Data Representation & Room Entity
In `Shortcut.kt`:
```kotlin
@Entity(tableName = "shortcuts")
data class Shortcut(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val treeUri: String,
    val icon: String = "folder",
    val sortOrder: Int = 0,
    val createdTime: Long = System.currentTimeMillis(),
    val isPinned: Boolean = false,
    val isHidden: Boolean = false,
    val isFavorite: Boolean = false,
    val colorTag: String = "#0061A4",
    val parentId: Long? = null,        // NULL = Primary Drawer Root; Non-NULL = Secondary Sub-shortcut
    val sortMode: String? = null
)

data class ShortcutWithChildren(
    @Embedded val primary: Shortcut,
    @Relation(
        parentColumn = "id",
        entityColumn = "parentId"
    )
    val secondaryShortcuts: List<Shortcut> = emptyList()
)
```

### 7.3 Management Workflows in `MainActivity`
- **Add Sub-shortcut**: Tapping "+ Add Sub-shortcut" on a primary card sets `pendingParentShortcutId = primary.id` and launches the system folder picker.
- **Move to Primary**: Users can move any sub-shortcut to a different primary folder or promote it to a top-level drawer root via `onPromoteToPrimary()`.
- **Promotion**: `viewModel.moveToParent(secondaryShortcut, null)` clears `parentId`, promoting it to a primary drawer root.
- **Expansion State**: `ShortcutAdapter` maintains a `mutableSetOf<Long>()` of expanded item IDs so expanding a card reveals its sub-shortcuts inline with smooth animation.

---

## 8. Smart Collections & Automated System Roots Engine

### 8.1 Automated Directory Scanning (`scanAccessibleDirectories`)
To aggregate files for Smart Collections (PDFs, APKs, Images, Videos, Recent Files) without hanging the UI or Binder thread, the provider employs a bounded priority queue scanner:
- Scans external storage roots: `Download`, `Documents`, `Pictures`, `DCIM`, `Movies`, `Music`.
- Breadth-first traversal up to 800 items max.
- Skips hidden files and directories (`name.startsWith(".")`).
- Feeds matching files to the caller via callback.

### 8.2 Collections Implemented
1. **🕒 Recent Files (`smart:recent_files`)**:
   - Collects all accessible files, sorts descending by `lastModified`, bounded by `prefs.maxRecentFiles` (default 200).
2. **📁 Recent Folders (`smart:recent_folders`)**:
   - Populated from Room's `RecentFolderDao`.
3. **⭐ Favorites (`smart:favorites`)**:
   - Populated from Room's `FavoriteItemDao`.
4. **📚 PDFs (`smart:pdfs`)**:
   - Filters `extension.equals("pdf", ignoreCase = true)`.
5. **📦 APK Files (`smart:apks`)**:
   - Filters `extension.equals("apk", ignoreCase = true)`.
6. **🖼 Pictures (`smart:pictures`)**:
   - Filters: `jpg`, `jpeg`, `png`, `webp`, `gif`, `bmp`, `heic`, `svg`.
7. **🎥 Movies & Videos (`smart:videos`)**:
   - Filters: `mp4`, `mkv`, `avi`, `mov`, `wmv`, `flv`, `webm`, `3gp`.

---

## 9. High-Fidelity Thumbnail Generation Pipeline (PDF, Media, APK)

The system invokes `openDocumentThumbnail(documentId, sizeHint, signal)`. Quick File Picker implements dedicated decoders per file category:

### 9.1 PDF Thumbnail Engine (`renderPdfThumbnail`)
```kotlin
val renderer = PdfRenderer(pfd)
if (renderer.pageCount > 0) {
    val page = renderer.openPage(0) // Page 1 preview
    val width = sizeHint.x.coerceAtLeast(128)
    val height = sizeHint.y.coerceAtLeast(128)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
    page.close()
    renderer.close()
    // Compresses to temporary PNG and returns AssetFileDescriptor
}
```

### 9.2 Video Frame Extraction
Uses Android's `MediaMetadataRetriever`:
```kotlin
val retriever = MediaMetadataRetriever()
retriever.setDataSource(pfd.fileDescriptor)
val frame = retriever.frameAtTime
retriever.release()
```

### 9.3 APK Launcher Icon Extraction (`renderApkThumbnail`)
Uses Android's `PackageManager.getPackageArchiveInfo`:
```kotlin
val info = pm?.getPackageArchiveInfo(filePath, 0)
val appInfo = info?.applicationInfo
if (appInfo != null) {
    appInfo.sourceDir = filePath
    appInfo.publicSourceDir = filePath
    val drawable = appInfo.loadIcon(pm)
    drawableToBitmap(drawable)
}
```

### 9.4 Caching & Pipe Descriptors
Generated thumbnails are compressed into temporary cache files (`thumb_*.png` in `context.cacheDir`) and returned as `AssetFileDescriptor(thumbPfd, 0, tempFile.length())`.

---

## 10. Room Database Persistence Layer & Migrations (`AppDatabase.kt`)

### 10.1 Schema Definition
- **Database Name**: `quickfilepicker_database`
- **Current Version**: `4`
- **Entities**:
  - `Shortcut`: id, name, treeUri, icon, sortOrder, createdTime, isPinned, isHidden, isFavorite, colorTag, parentId, sortMode.
  - `RecentFolder`: id, name, path, treeUri, lastAccessed.
  - `FavoriteItem`: id, name, uriString, isDirectory, mimeType, addedTime.

### 10.2 Database Migrations
- **Migration 1 -> 2**: Added `isPinned`, `isHidden`, `isFavorite`, `colorTag` to `shortcuts`; created `recent_folders` and `favorite_items` tables.
- **Migration 2 -> 3**: Added `parentId INTEGER DEFAULT NULL` to `shortcuts` for hierarchical sub-shortcuts.
- **Migration 3 -> 4**: Added `sortMode TEXT DEFAULT NULL` to `shortcuts` for per-folder sort customization.
- **Destructive Fallback**: `fallbackToDestructiveMigrationOnDowngrade()`.

### 10.3 Room DAOs
- **`ShortcutDao`**:
  - `getPrimaryShortcutsWithChildrenFlow()`: Emits `List<ShortcutWithChildren>` where `parentId IS NULL ORDER BY isPinned DESC, sortOrder ASC`.
  - `getAllPrimaryShortcutsSync()`: Returns all primary shortcuts synchronously.
  - `getVisiblePrimaryShortcutsSync()`: Excludes `isHidden == 1`.
  - `getSecondaryShortcutsSync(parentId)`: Returns sub-shortcuts for a parent.
  - `deleteSecondaryShortcutsForParent(parentId)`: Cascade delete on parent removal.
- **`RecentFolderDao`**:
  - `getRecentFoldersSync()`: Ordered by `lastAccessed DESC`.
- **`FavoriteItemDao`**:
  - `getAllFavoritesSync()`: Ordered by `addedTime DESC`.

---

## 11. Offline JSON Backup & Restore Engine (`BackupManager.kt`)

### 11.1 Schema v2 Container Structure
The backup file is UTF-8 formatted JSON created via Android's Storage Access Framework (`CreateDocument("application/json")`):
```json
{
  "version": 2,
  "settings": {
    "enableAutoFolders": true,
    "enableSmartCollections": true,
    "enableSearch": true,
    "enableRecentFiles": true,
    "enableRecentFolders": true,
    "maxRecentFiles": 200,
    "sortMode": "DATE_DESC",
    "darkThemeMode": "system"
  },
  "shortcuts": [
    {
      "id": 1,
      "name": "Work Documents",
      "treeUri": "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FWork",
      "icon": "documents",
      "sortOrder": 0,
      "parentId": null,
      "parentIndex": null,
      "isPinned": true,
      "isFavorite": false,
      "isHidden": false,
      "sortMode": "PREFIX_NUMBER"
    },
    {
      "id": 2,
      "name": "Invoices 2026",
      "treeUri": "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FWork%2FInvoices",
      "icon": "folder",
      "sortOrder": 0,
      "parentId": 1,
      "parentIndex": 0,
      "isPinned": false,
      "isFavorite": false,
      "isHidden": false,
      "sortMode": null
    }
  ]
}
```

### 11.2 Import & Parent Index Resolution Algorithm
When restoring on a new device, database primary keys (`id`) may change. To preserve sub-shortcut relationships safely:
1. Distinguishes primary items (`parentIndex == null && originalParentId == null`) from secondary items.
2. Allocates new unique IDs (`nextId++`) and maps both array indices (`indexToNewId`) and original IDs (`originalIdToNewId`).
3. Reconstructs `parentId` using either `originalIdToNewId[originalParentId]` or `indexToNewId[parentIndex]`.
4. Re-takes persistable URI permissions on content URIs via `context.contentResolver.takePersistableUriPermission()`.
5. Atomic database replacement via `repository.restoreAll(shortcuts)`.
6. Dispatches `notifyRootsChanged()` to update the system file picker immediately.

---

## 12. User Interface, Themes & MIUI-Inspired Aesthetics

### 12.1 Visual Philosophy
The UI follows Material Design 3 guidelines blended with MIUI/HyperOS clean card aesthetics:
- 24dp card corner radii on empty state and dialog containers.
- 18dp corner radii on shortcut cards and permission banners.
- Squircle icon backgrounds with subtle elevation and borders.
- Capsule Extended Floating Action Button (`fabAddShortcut`).
- Edge-to-edge layout via `WindowCompat.setDecorFitsSystemWindows(window, false)` with status bar and navigation bar insets handled dynamically.

### 12.2 Theme Modes (`QuickFilePickerPrefs`)
1. **`THEME_SYSTEM`**: Follows Android system dark/light configuration.
2. **`THEME_LIGHT`**: Forces light palette (`AppCompatDelegate.MODE_NIGHT_NO`).
3. **`THEME_DARK`**: Standard Material 3 dark palette (`AppCompatDelegate.MODE_NIGHT_YES`).
4. **`THEME_AMOLED`**: Pure black `#000000` surface and background (`Theme.QuickFilePicker.Amoled`), maximizing battery savings on OLED displays.

### 12.3 Icon Options
Users can customize each shortcut icon from a curated palette of 12 vector drawables:
- 📁 `folder` (`R.drawable.ic_folder`)
- 📱 `app` (`R.drawable.ic_app`)
- 🚀 `rocket` (`R.drawable.ic_rocket`)
- 📖 `book` (`R.drawable.ic_book`)
- 🏥 `medical` (`R.drawable.ic_medical`)
- 💬 `telegram` (`R.drawable.ic_telegram`)
- 💬 `whatsapp` (`R.drawable.ic_whatsapp`)
- 🖼 `image` (`R.drawable.ic_image`)
- 📑 `pdf` (`R.drawable.ic_pdf`)
- ⬇ `download` (`R.drawable.ic_download`)
- ⭐ `star` / `favorite` (`R.drawable.ic_star`)
- 📄 `documents` (`R.drawable.ic_documents`)
- 📂 `storage` (`R.drawable.ic_storage`)

---

## 13. Drag-and-Drop Reordering & Live SAF Roots Notification

### 13.1 `ItemTouchHelper` Integration
- Attached to `binding.rvShortcuts`.
- Drag flags: `ItemTouchHelper.UP or ItemTouchHelper.DOWN`.
- Swipe flags: `0` (swiping disabled to prevent accidental deletions).
- Touch handle: `binding.ivDragHandle` triggers `itemTouchHelper.startDrag(viewHolder)` on `MotionEvent.ACTION_DOWN`.
- Real-time swap: `adapter.onItemMove(fromPos, toPos)` updates the in-memory list and notifies the adapter.
- Persistence on release: `clearView()` invokes `viewModel.updatePrimaryOrder(currentItems)`.

### 13.2 Live System Drawer Update
Whenever shortcuts are added, renamed, reordered, or deleted:
```kotlin
fun notifyRootsChanged() {
    val rootsUri = DocumentsContract.buildRootsUri(QuickFilePickerDocumentsProvider.AUTHORITY)
    context.contentResolver.notifyChange(rootsUri, null)
    val docsUri = Uri.parse("content://${QuickFilePickerDocumentsProvider.AUTHORITY}/document")
    context.contentResolver.notifyChange(docsUri, null)
}
```
This forces the Android DocumentsUI process to invalidate its root cache and re-query `queryRoots()`, reflecting reordered folders instantly inside ChatGPT, Chrome, or any active file picker without requiring an app restart.

---

## 14. Preferences & Settings Management (`QuickFilePickerPrefs.kt`)

Stores key-value flags in SharedPreferences (`quickfilepicker_settings`):
- `enable_auto_folders`: Toggles automatic system folders (Downloads, WhatsApp, etc.). Default: `true`.
- `enable_smart_collections`: Toggles smart collections (PDFs, APKs, etc.). Default: `true`.
- `enable_search`: Enables SAF search support. Default: `true`.
- `enable_recent_files`: Toggles recent files collection. Default: `true`.
- `enable_recent_folders`: Toggles recent folders collection. Default: `true`.
- `max_recent_files`: Maximum items scanned in recents (default 200).
- `sort_mode`: Global fallback sort mode. Default: `SORT_DATE_DESC`.
- `dark_theme_mode`: `system`, `light`, `dark`, or `amoled`.

---

## 15. Permissions, Security & Zero-Network Privacy Architecture

### 15.1 Manifest Declarations
- `android.permission.MANAGE_DOCUMENTS`: Signature/system-level permission required by Android to host a `DocumentsProvider`.
- `android.permission.QUERY_ALL_PACKAGES`: Required to list installed file managers (MiXplorer, Solid Explorer, Cx File Explorer) in the 3rd-party app picker dialog.
- `android.permission.POST_NOTIFICATIONS`: Runtime permission (Android 13+ / API 33+) to dispatch high-priority launch notifications.
- `android.permission.SYSTEM_ALERT_WINDOW`: Allows launching external file explorers from the background when a user taps an app shortcut in the system file picker.

### 15.2 Zero-Internet Privacy Guarantee
- The app does **NOT** declare `android.permission.INTERNET`.
- Android enforces this at the OS kernel level: the app cannot make sockets, HTTP requests, or DNS lookups.
- User files, folder paths, and documents never leave the physical device.

---

## 16. Complete File Tree, Class Map & Coding Reference for Future Developers

### 16.1 Source Code Directory Structure
```
/app/src/main/
├── AndroidManifest.xml
├── java/com/quickfilepicker/
│   ├── MainActivity.kt                             # Primary UI: RecyclerView, drag-and-drop, dialogs
│   ├── QuickFilePickerApplication.kt               # Application subclass: initializes DB & Repository
│   ├── data/
│   │   ├── backup/
│   │   │   └── BackupManager.kt                    # JSON export/import & parent index resolution
│   │   ├── db/
│   │   │   ├── AppDatabase.kt                      # Room database definition & migrations 1->4
│   │   │   ├── ShortcutDao.kt                      # SQL queries for primary & secondary shortcuts
│   │   │   ├── RecentFolderDao.kt                  # History of opened folders
│   │   │   └── FavoriteItemDao.kt                  # Starred items DAO
│   │   ├── model/
│   │   │   ├── Shortcut.kt                         # Shortcut & ShortcutWithChildren Room entities
│   │   │   ├── RecentFolder.kt                     # RecentFolder Room entity
│   │   │   └── FavoriteItem.kt                     # FavoriteItem Room entity
│   │   ├── pref/
│   │   │   └── QuickFilePickerPrefs.kt             # SharedPreferences manager (themes, sorts, toggles)
│   │   └── repository/
│   │       └── ShortcutRepository.kt               # Central data mediator & roots notifier
│   ├── provider/
│   │   └── QuickFilePickerDocumentsProvider.kt     # Storage Access Framework DocumentsProvider engine
│   └── ui/
│       ├── launcher/
│       │   └── LaunchProxyActivity.kt              # Transparent proxy activity for app launch intents
│       └── main/
│           ├── IconAdapter.kt                      # RecyclerView adapter for 12-icon picker dialog
│           ├── MainViewModel.kt                    # ViewModel managing state flows & coroutines
│           └── ShortcutAdapter.kt                  # RecyclerView adapter for hierarchical shortcuts
└── res/
    ├── layout/
    │   ├── activity_main.xml                       # CoordinatorLayout + Toolbar + RecyclerView + FAB
    │   ├── dialog_add_shortcut.xml                 # Dialog layout for adding/editing shortcut
    │   ├── dialog_settings.xml                     # Dialog layout for settings switches & spinners
    │   ├── item_icon_option.xml                    # Single icon grid item
    │   ├── item_shortcut.xml                       # Primary shortcut card layout (with sub-shortcut container)
    │   └── item_secondary_shortcut.xml             # Sub-shortcut child item row
    ├── values/
    │   ├── colors.xml                              # Material 3 light/dark color tokens
    │   ├── strings.xml                             # Localized user-facing strings
    │   └── themes.xml                              # Base and AMOLED themes
    └── xml/
        ├── backup_rules.xml
        └── data_extraction_rules.xml
```

### 16.2 Developer Cheatsheet: How to Modify & Extend
- **To add a new Smart Collection**:
  1. Add an ID in `QuickFilePickerDocumentsProvider.addSmartCollectionRoots()` with a new prefix (`smart:<id>`).
  2. Implement the file filtering or query in `querySmartCollection()`.
  3. Update `queryDocument()` to return the collection title and icon.
- **To add a new Sort Mode**:
  1. Define a constant in `QuickFilePickerPrefs.kt`.
  2. Implement the comparator in `QuickFilePickerDocumentsProvider.sortItems()`.
  3. Add the label and option into `showSortOrderDialog()` in `MainActivity.kt` and `dialog_settings.xml`.
- **To support an additional 3rd-Party File Manager**:
  1. Add `<package android:name="<new.package>" />` to `<queries>` in `AndroidManifest.xml`.
  2. Add `Pair("App Name", "new.package")` to `popularFileManagers` in `MainActivity.showAddAppShortcutDialog()`.
- **To add a new Icon**:
  1. Place the vector drawable in `res/drawable/ic_<name>.xml`.
  2. Register the key in `IconAdapter.kt`.
  3. Map the resource in `getIconResourceId()` in both `ShortcutAdapter.kt` and `QuickFilePickerDocumentsProvider.kt`.

---

## 17. Deep Dive on Android Scoped Storage & DocumentsUI Interoperability

### 17.1 How Android DocumentsUI Consumes QuickFilePicker
When a client application (e.g. WhatsApp, ChatGPT, or Google Chrome) triggers file selection, it builds an Intent:
```kotlin
val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
    addCategory(Intent.CATEGORY_OPENABLE)
    type = "*/*"
    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
}
startActivityForResult(intent, REQUEST_CODE)
```
1. Android OS intercepts this Intent and routes it to `com.google.android.documentsui` (the system Files / DocumentsUI APK).
2. DocumentsUI queries the `PackageManager` for all ContentProviders registered with the `android.content.action.DOCUMENTS_PROVIDER` intent-filter.
3. It finds `QuickFilePickerDocumentsProvider` under authority `com.quickfilepicker.documents`.
4. It calls `queryRoots()` via Binder IPC.
5. `QuickFilePickerDocumentsProvider` queries Room for user shortcuts and constructs a `MatrixCursor`.
6. DocumentsUI draws each root as an item in the navigation drawer with its icon and title.
7. When the user taps a root, DocumentsUI calls `queryDocument(documentId)` followed by `queryChildDocuments(parentDocumentId)`.
8. The returned files are displayed in grid or list view.
9. When the user taps a file, DocumentsUI calls `openDocument(documentId, "r", signal)` or `openDocumentThumbnail(documentId, sizeHint, signal)`.
10. The client app receives a `content://com.quickfilepicker.documents/document/...` URI with temporary read grants (`FLAG_GRANT_READ_URI_PERMISSION`).

### 17.2 Persistable URI Permissions Across Device Reboots
Standard content URIs granted via `startActivityForResult` are temporary and expire when the activity finishes or the device restarts.
To ensure user-selected folders remain accessible indefinitely:
```kotlin
val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
contentResolver.takePersistableUriPermission(treeUri, takeFlags)
```
- Android stores these grants in `/data/system/users/0/uri_grants.xml`.
- When Quick File Picker reboots, `contentResolver.persistedUriPermissions` confirms that access is intact.
- Even if the user moves or flashes a custom ROM, the JSON backup contains the original tree URI strings, allowing the import engine to attempt re-taking permissions seamlessly.

---

## 18. Complete Database SQL Queries & DAO Implementation Details

### 18.1 `ShortcutDao.kt` Query Architecture
The DAO defines reactive and synchronous queries optimized for both Compose/View lifecycles and IPC Binder calls:
```kotlin
@Dao
interface ShortcutDao {
    @Transaction
    @Query("SELECT * FROM shortcuts WHERE parentId IS NULL ORDER BY isPinned DESC, sortOrder ASC")
    fun getPrimaryShortcutsWithChildrenFlow(): Flow<List<ShortcutWithChildren>>

    @Query("SELECT * FROM shortcuts WHERE parentId IS NULL ORDER BY isPinned DESC, sortOrder ASC")
    fun getAllPrimaryShortcutsFlow(): Flow<List<Shortcut>>

    @Query("SELECT * FROM shortcuts WHERE parentId IS NULL ORDER BY isPinned DESC, sortOrder ASC")
    fun getAllPrimaryShortcutsSync(): List<Shortcut>

    @Query("SELECT * FROM shortcuts WHERE parentId IS NULL AND isHidden = 0 ORDER BY isPinned DESC, sortOrder ASC")
    fun getVisiblePrimaryShortcutsSync(): List<Shortcut>

    @Query("SELECT * FROM shortcuts WHERE parentId = :parentId ORDER BY sortOrder ASC")
    fun getSecondaryShortcutsSync(parentId: Long): List<Shortcut>

    @Query("SELECT * FROM shortcuts WHERE id = :id LIMIT 1")
    fun getShortcutByIdSync(id: Long): Shortcut?

    @Query("SELECT * FROM shortcuts")
    fun getAllShortcutsSync(): List<Shortcut>

    @Query("DELETE FROM shortcuts WHERE parentId = :parentId")
    fun deleteSecondaryShortcutsForParent(parentId: Long)
}
```

### 18.2 Relation Mapping via `@Relation`
Room automatically executes two internal queries inside `@Transaction`:
1. `SELECT * FROM shortcuts WHERE parentId IS NULL ORDER BY isPinned DESC, sortOrder ASC` (fetches primary items).
2. `SELECT * FROM shortcuts WHERE parentId IN (...)` (fetches children mapped by `parentColumn = "id"` and `entityColumn = "parentId"`).
This yields a clean, strongly-typed `ShortcutWithChildren` tree without requiring manual relational join parsing.

---

## 19. Comprehensive Troubleshooting, Debugging & Test Scenarios

### 19.1 Common Symptoms & Root Cause Fixes
| Symptom / Issue | Underlying Root Cause | Verification & Fix Strategy |
|---|---|---|
| Shortcut does not appear in System File Picker | System DocumentsUI cached previous roots cursor | Call `ShortcutRepository.notifyRootsChanged()`. Verify authority matches `com.quickfilepicker.documents`. |
| 3rd-Party App (MiXplorer, Solid) fails to launch from picker | Background activity launch blocked on Android 10+ | Grant `SYSTEM_ALERT_WINDOW` permission or enable Xiaomi "Show on Lock screen" / "Display pop-up windows while running in the background". |
| PDF thumbnails show generic document icon | `PdfRenderer` encountered encrypted or zero-byte PDF | `renderPdfThumbnail` catches `Exception` and falls back to standard file descriptor icon. Verify file has read permissions. |
| Files in sub-folders not showing | Tree URI was taken with single-document grant instead of tree grant | Ensure `ACTION_OPEN_DOCUMENT_TREE` was used (not `ACTION_OPEN_DOCUMENT`). |
| System picker forces alphabetical sort over custom sort | App or ROM ignores provider cursor order | Enable `SORT_PREFIX_NUMBER` ("01, 02... prefix") in Shortcut or Global Settings. This forces chronological sorting through the prefix. |

### 19.2 Local Robolectric Unit Testing
The project includes unit testing capabilities configured with Robolectric:
- Test runner: `androidx.test.runner.AndroidJUnitRunner`.
- `isIncludeAndroidResources = true` enabled in `build.gradle.kts`.
- Test command: `gradle :app:testDebugUnitTest`.

---

## 20. Performance Profiling & IPC Optimization

- **Zero In-Memory Buffering**: `openDocument()` delegates directly to `ParcelFileDescriptor.open(file, parcelMode)`. Data flows kernel-to-kernel without user-space buffer duplication.
- **Bounded Directory Scanning**: Priority queue scanning in `scanAccessibleDirectories()` terminates strictly at 800 items, preventing ANR (Application Not Responding) timeouts on multi-terabyte SD cards or OTG drives.
- **MatrixCursor Pre-Allocation**: MatrixCursor columns are indexed with constant integer lookups, eliminating redundant reflection overhead during IPC serialization.
- **Thumbnail Compression**: Bitmaps rendered via `PdfRenderer` and `MediaMetadataRetriever` are compressed at 90% PNG quality into lightweight cache files and immediately freed, preventing native graphic memory leaks.

