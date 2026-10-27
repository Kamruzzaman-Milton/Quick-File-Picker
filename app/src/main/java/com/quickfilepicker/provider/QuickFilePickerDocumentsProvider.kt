package com.quickfilepicker.provider

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.util.Log
import com.quickfilepicker.R
import com.quickfilepicker.QuickFilePickerApplication
import com.quickfilepicker.data.model.Shortcut
import com.quickfilepicker.data.pref.QuickFilePickerPrefs
import com.quickfilepicker.data.repository.ShortcutRepository
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.PriorityQueue

class QuickFilePickerDocumentsProvider : DocumentsProvider() {

    companion object {
        private const val TAG = "QuickFilePickerProvider"
        const val AUTHORITY = "com.quickfilepicker.documents"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_MIME_TYPES
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE
        )

        private const val PREFIX_USER_SHORTCUT = "sc_"
        private const val PREFIX_AUTO_ROOT = "auto_"
        private const val PREFIX_SMART_COLLECTION = "smart_"
    }

    @Volatile
    private var repositoryInstance: ShortcutRepository? = null

    private val repository: ShortcutRepository?
        get() {
            repositoryInstance?.let { return it }
            return try {
                QuickFilePickerApplication.getRepository().also { repositoryInstance = it }
            } catch (e: Exception) {
                context?.let { ctx ->
                    val appCtx = ctx.applicationContext ?: ctx
                    val db = com.quickfilepicker.data.db.AppDatabase.getInstance(appCtx)
                    ShortcutRepository(
                        appCtx,
                        db.shortcutDao(),
                        db.recentFolderDao(),
                        db.favoriteItemDao()
                    ).also { repositoryInstance = it }
                }
            }
        }

    private val prefs: QuickFilePickerPrefs?
        get() = context?.let { QuickFilePickerPrefs(it) }

    override fun onCreate(): Boolean {
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val resultProjection = projection ?: DEFAULT_ROOT_PROJECTION
        val result = MatrixCursor(resultProjection)

        val settings = prefs

        // 1. User Bookmarks FIRST (Primary Shortcuts in user order)
        val primaryShortcuts = repository?.getVisiblePrimaryShortcutsSync() ?: emptyList()
        for (shortcut in primaryShortcuts) {
            try {
                val row = result.newRow()
                val rootId = shortcut.id.toString()
                val isApp = shortcut.treeUri.startsWith("app:")
                val iconRes = if (shortcut.icon == "app" || shortcut.icon == "rocket" || isApp) {
                    R.drawable.ic_app
                } else {
                    getIconResourceId(shortcut.icon)
                }
                val isPinned = if (shortcut.isPinned) "📌 " else ""

                if (isApp) {
                    val appName = shortcut.name
                    row.add(DocumentsContract.Root.COLUMN_ROOT_ID, rootId)
                    row.add(
                        DocumentsContract.Root.COLUMN_FLAGS,
                        DocumentsContract.Root.FLAG_LOCAL_ONLY or
                                DocumentsContract.Root.FLAG_SUPPORTS_SEARCH
                    )
                    row.add(DocumentsContract.Root.COLUMN_ICON, iconRes)
                    row.add(DocumentsContract.Root.COLUMN_TITLE, "$isPinned$appName")
                    row.add(DocumentsContract.Root.COLUMN_SUMMARY, "Open $appName to browse files")
                    row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, "approot:${shortcut.id}")
                    row.add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
                } else {
                    val secShortcuts = repository?.getSecondaryShortcutsSync(shortcut.id) ?: emptyList()
                    val secCount = secShortcuts.size
                    val summary = if (secCount > 0) {
                        "$secCount secondary shortcut${if (secCount > 1) "s" else ""}"
                    } else {
                        getDisplayPath(shortcut.treeUri)
                    }

                    row.add(DocumentsContract.Root.COLUMN_ROOT_ID, rootId)
                    row.add(
                        DocumentsContract.Root.COLUMN_FLAGS,
                        DocumentsContract.Root.FLAG_SUPPORTS_CREATE or
                                DocumentsContract.Root.FLAG_SUPPORTS_SEARCH or
                                DocumentsContract.Root.FLAG_LOCAL_ONLY
                    )
                    row.add(DocumentsContract.Root.COLUMN_ICON, iconRes)
                    row.add(DocumentsContract.Root.COLUMN_TITLE, "$isPinned${shortcut.name}")
                    row.add(DocumentsContract.Root.COLUMN_SUMMARY, summary)
                    row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, "root:${shortcut.id}")
                    row.add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error adding root for shortcut ${shortcut.name}", e)
            }
        }

        // 2. Smart Collections (AFTER User Shortcuts)
        if (settings?.enableSmartCollections == true) {
            addSmartCollectionRoots(result)
        }

        // 3. Automatic System Roots (AFTER User Shortcuts)
        if (settings?.enableAutoFolders == true) {
            addAutomaticSystemRoots(result)
        }

        return result
    }

    override fun queryRecentDocuments(rootId: String, projection: Array<out String>?): Cursor {
        val resultProjection = projection ?: DEFAULT_DOCUMENT_PROJECTION
        return MatrixCursor(resultProjection)
    }

    private fun addSmartCollectionRoots(result: MatrixCursor) {
        val flags = DocumentsContract.Root.FLAG_LOCAL_ONLY

        if (prefs?.enableRecentFiles == true) {
            val row = result.newRow()
            row.add(DocumentsContract.Root.COLUMN_ROOT_ID, "${PREFIX_SMART_COLLECTION}recent_files")
            row.add(DocumentsContract.Root.COLUMN_FLAGS, flags)
            row.add(DocumentsContract.Root.COLUMN_ICON, R.drawable.ic_history)
            row.add(DocumentsContract.Root.COLUMN_TITLE, "🕒 Recent Files")
            row.add(DocumentsContract.Root.COLUMN_SUMMARY, "All recently modified files")
            row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, "smart:recent_files")
            row.add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
        }

        if (prefs?.enableRecentFolders == true) {
            val row = result.newRow()
            row.add(DocumentsContract.Root.COLUMN_ROOT_ID, "${PREFIX_SMART_COLLECTION}recent_folders")
            row.add(DocumentsContract.Root.COLUMN_FLAGS, flags)
            row.add(DocumentsContract.Root.COLUMN_ICON, R.drawable.ic_folder)
            row.add(DocumentsContract.Root.COLUMN_TITLE, "📁 Recent Folders")
            row.add(DocumentsContract.Root.COLUMN_SUMMARY, "History of opened folders")
            row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, "smart:recent_folders")
            row.add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
        }

        val favRow = result.newRow()
        favRow.add(DocumentsContract.Root.COLUMN_ROOT_ID, "${PREFIX_SMART_COLLECTION}favorites")
        favRow.add(DocumentsContract.Root.COLUMN_FLAGS, flags)
        favRow.add(DocumentsContract.Root.COLUMN_ICON, R.drawable.ic_star)
        favRow.add(DocumentsContract.Root.COLUMN_TITLE, "⭐ Favorites")
        favRow.add(DocumentsContract.Root.COLUMN_SUMMARY, "Starred files and shortcuts")
        favRow.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, "smart:favorites")
        favRow.add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")

        val pdfRow = result.newRow()
        pdfRow.add(DocumentsContract.Root.COLUMN_ROOT_ID, "${PREFIX_SMART_COLLECTION}pdfs")
        pdfRow.add(DocumentsContract.Root.COLUMN_FLAGS, flags)
        pdfRow.add(DocumentsContract.Root.COLUMN_ICON, R.drawable.ic_pdf)
        pdfRow.add(DocumentsContract.Root.COLUMN_TITLE, "📚 PDFs")
        pdfRow.add(DocumentsContract.Root.COLUMN_SUMMARY, "All PDF documents")
        pdfRow.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, "smart:pdfs")
        pdfRow.add(DocumentsContract.Root.COLUMN_MIME_TYPES, "application/pdf")

        val apkRow = result.newRow()
        apkRow.add(DocumentsContract.Root.COLUMN_ROOT_ID, "${PREFIX_SMART_COLLECTION}apks")
        apkRow.add(DocumentsContract.Root.COLUMN_FLAGS, flags)
        apkRow.add(DocumentsContract.Root.COLUMN_ICON, R.drawable.ic_apk)
        apkRow.add(DocumentsContract.Root.COLUMN_TITLE, "📦 APK Files")
        apkRow.add(DocumentsContract.Root.COLUMN_SUMMARY, "Android Application Packages")
        apkRow.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, "smart:apks")
        apkRow.add(DocumentsContract.Root.COLUMN_MIME_TYPES, "application/vnd.android.package-archive")

        val picRow = result.newRow()
        picRow.add(DocumentsContract.Root.COLUMN_ROOT_ID, "${PREFIX_SMART_COLLECTION}pictures")
        picRow.add(DocumentsContract.Root.COLUMN_FLAGS, flags)
        picRow.add(DocumentsContract.Root.COLUMN_ICON, R.drawable.ic_image)
        picRow.add(DocumentsContract.Root.COLUMN_TITLE, "🖼 Pictures")
        picRow.add(DocumentsContract.Root.COLUMN_SUMMARY, "All photos & images")
        picRow.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, "smart:pictures")
        picRow.add(DocumentsContract.Root.COLUMN_MIME_TYPES, "image/*")

        val vidRow = result.newRow()
        vidRow.add(DocumentsContract.Root.COLUMN_ROOT_ID, "${PREFIX_SMART_COLLECTION}videos")
        vidRow.add(DocumentsContract.Root.COLUMN_FLAGS, flags)
        vidRow.add(DocumentsContract.Root.COLUMN_ICON, R.drawable.ic_video)
        vidRow.add(DocumentsContract.Root.COLUMN_TITLE, "🎥 Movies & Videos")
        vidRow.add(DocumentsContract.Root.COLUMN_SUMMARY, "All video files")
        vidRow.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, "smart:videos")
        vidRow.add(DocumentsContract.Root.COLUMN_MIME_TYPES, "video/*")
    }

    private fun addAutomaticSystemRoots(result: MatrixCursor) {
        val flags = DocumentsContract.Root.FLAG_SUPPORTS_CREATE or
                DocumentsContract.Root.FLAG_SUPPORTS_SEARCH or
                DocumentsContract.Root.FLAG_LOCAL_ONLY

        val storageBase = Environment.getExternalStorageDirectory()

        val autoDirs = listOf(
            AutoRootDef("auto_download", "⬇ Downloads", File(storageBase, "Download"), R.drawable.ic_download),
            AutoRootDef("auto_documents", "📄 Documents", File(storageBase, "Documents"), R.drawable.ic_documents),
            AutoRootDef("auto_pictures", "🖼 Pictures", File(storageBase, "Pictures"), R.drawable.ic_image),
            AutoRootDef("auto_camera", "📷 Camera", File(storageBase, "DCIM/Camera"), R.drawable.ic_camera),
            AutoRootDef("auto_screenshots", "📱 Screenshots", File(storageBase, "DCIM/Screenshots"), R.drawable.ic_image),
            AutoRootDef("auto_movies", "🎥 Movies", File(storageBase, "Movies"), R.drawable.ic_video),
            AutoRootDef("auto_music", "🎵 Music", File(storageBase, "Music"), R.drawable.ic_music),
            AutoRootDef("auto_books", "📖 Books", File(storageBase, "Books"), R.drawable.ic_book),
            AutoRootDef("auto_telegram", "💬 Telegram", File(storageBase, "Telegram"), R.drawable.ic_telegram),
            AutoRootDef("auto_whatsapp", "💬 WhatsApp", File(storageBase, "Android/media/com.whatsapp/WhatsApp/Media"), R.drawable.ic_whatsapp),
            AutoRootDef("auto_fcps", "📖 FCPS", File(storageBase, "Documents/FCPS"), R.drawable.ic_book),
            AutoRootDef("auto_storage", "📂 Internal Storage", storageBase, R.drawable.ic_storage)
        )

        for (def in autoDirs) {
            if (def.dir.exists()) {
                val row = result.newRow()
                row.add(DocumentsContract.Root.COLUMN_ROOT_ID, def.id)
                row.add(DocumentsContract.Root.COLUMN_FLAGS, flags)
                row.add(DocumentsContract.Root.COLUMN_ICON, def.iconRes)
                row.add(DocumentsContract.Root.COLUMN_TITLE, def.title)
                row.add(DocumentsContract.Root.COLUMN_SUMMARY, def.dir.absolutePath)
                row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, "file:${def.dir.absolutePath}")
                row.add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
            }
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val resultProjection = projection ?: DEFAULT_DOCUMENT_PROJECTION
        val result = MatrixCursor(resultProjection)

        try {
            when {
                documentId.startsWith("sec:") -> {
                    val withoutPrefix = documentId.substringAfter("sec:")
                    val parts = withoutPrefix.split(":", limit = 2)
                    val secId = parts[0].toLongOrNull()
                    val innerDocId = if (parts.size > 1) parts[1] else "root"
                    val secShortcut = secId?.let { repository?.getShortcutByIdSync(it) }

                    if (secShortcut == null) {
                        val row = result.newRow()
                        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
                        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, "Folder")
                        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
                        row.add(DocumentsContract.Document.COLUMN_FLAGS, 0)
                        row.add(DocumentsContract.Document.COLUMN_SIZE, 0L)
                        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, System.currentTimeMillis())
                        return result
                    }

                    val treeUri = Uri.parse(secShortcut.treeUri)
                    val treeDocId = safeGetTreeDocumentId(treeUri) ?: ""
                    val isAtRoot = innerDocId == "root" || innerDocId.isEmpty() || (treeDocId.isNotEmpty() && innerDocId == treeDocId)
                    val targetDocId = if (isAtRoot) treeDocId else innerDocId

                    var rowAdded = false
                    if (targetDocId.isNotEmpty() && DocumentsContract.isTreeUri(treeUri)) {
                        try {
                            val targetDocumentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, targetDocId)
                            val cursor = context?.contentResolver?.query(
                                targetDocumentUri,
                                projection,
                                null,
                                null,
                                null
                            )
                            if (cursor != null && cursor.moveToFirst()) {
                                copyAndTransformSecRow(cursor, result, resultProjection, secId, secShortcut.name, isAtRoot)
                                cursor.close()
                                rowAdded = true
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "SAF queryDocument failed for sec:$secId: ${e.message}")
                        }
                    }

                    if (!rowAdded) {
                        val resolvedFile = resolveFileForShortcut(secShortcut.treeUri, if (isAtRoot) null else innerDocId)
                        val row = result.newRow()
                        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
                        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, if (isAtRoot) secShortcut.name else (resolvedFile?.name ?: "Folder"))
                        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, if (resolvedFile?.isFile == true) getMimeType(resolvedFile) else DocumentsContract.Document.MIME_TYPE_DIR)
                        row.add(
                            DocumentsContract.Document.COLUMN_FLAGS,
                            DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE or DocumentsContract.Document.FLAG_SUPPORTS_WRITE
                        )
                        row.add(DocumentsContract.Document.COLUMN_SIZE, resolvedFile?.length() ?: 0L)
                        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, resolvedFile?.lastModified() ?: secShortcut.createdTime)
                    }
                    return result
                }

                documentId.startsWith("smart:") -> {
                    val collection = documentId.substringAfter("smart:")
                    val title = when (collection) {
                        "recent_files" -> "🕒 Recent Files"
                        "recent_folders" -> "📁 Recent Folders"
                        "favorites" -> "⭐ Favorites"
                        "pdfs" -> "📚 PDFs"
                        "apks" -> "📦 APK Files"
                        "pictures" -> "🖼 Pictures"
                        "videos" -> "🎥 Movies & Videos"
                        else -> "Collection"
                    }
                    val row = result.newRow()
                    row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
                    row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, title)
                    row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
                    row.add(DocumentsContract.Document.COLUMN_FLAGS, 0)
                    row.add(DocumentsContract.Document.COLUMN_SIZE, 0L)
                    row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, System.currentTimeMillis())
                    return result
                }

                documentId.startsWith("approot:") -> {
                    val shortcutId = documentId.substringAfter("approot:").toLongOrNull() ?: 0L
                    val shortcut = repository?.getShortcutByIdSync(shortcutId)
                    val row = result.newRow()
                    row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
                    row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, shortcut?.name ?: "App Shortcut")
                    row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
                    row.add(DocumentsContract.Document.COLUMN_FLAGS, 0)
                    row.add(DocumentsContract.Document.COLUMN_SIZE, 0L)
                    row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, shortcut?.createdTime ?: System.currentTimeMillis())
                    return result
                }

                documentId.startsWith("appaction:") -> {
                    val shortcutId = documentId.substringAfter("appaction:").toLongOrNull() ?: 0L
                    val shortcut = repository?.getShortcutByIdSync(shortcutId)
                    val row = result.newRow()
                    row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
                    row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, "🚀 Open ${shortcut?.name ?: "App"} (Tap here)")
                    row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, "application/x-quickfilepicker-launch")
                    row.add(DocumentsContract.Document.COLUMN_FLAGS, 0)
                    row.add(DocumentsContract.Document.COLUMN_SIZE, 1L)
                    row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, System.currentTimeMillis())
                    return result
                }

                documentId.startsWith("file:") -> {
                    val filePath = documentId.substringAfter("file:")
                    val file = File(filePath)
                    val row = result.newRow()
                    row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
                    row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, file.name.ifEmpty { "Storage" })
                    row.add(
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else getMimeType(file)
                    )
                    var flags = 0
                    if (file.canWrite()) {
                        flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_WRITE or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
                    }
                    row.add(DocumentsContract.Document.COLUMN_FLAGS, flags)
                    row.add(DocumentsContract.Document.COLUMN_SIZE, if (file.isDirectory) 0L else file.length())
                    row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified())
                    return result
                }

                else -> {
                    val parsed = parseDocumentId(documentId)
                    val shortcutId = parsed?.first ?: 0L
                    val innerDocId = parsed?.second ?: "root"
                    val shortcut = repository?.getShortcutByIdSync(shortcutId)

                    if (shortcut == null) {
                        val row = result.newRow()
                        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
                        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, "Folder")
                        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
                        row.add(DocumentsContract.Document.COLUMN_FLAGS, 0)
                        row.add(DocumentsContract.Document.COLUMN_SIZE, 0L)
                        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, System.currentTimeMillis())
                        return result
                    }

                    val treeUri = Uri.parse(shortcut.treeUri)
                    val treeRootDocId = safeGetTreeDocumentId(treeUri) ?: ""
                    val isAtRoot = innerDocId == "root" || innerDocId.isEmpty() || (treeRootDocId.isNotEmpty() && innerDocId == treeRootDocId)
                    val secShortcuts = repository?.getSecondaryShortcutsSync(shortcutId) ?: emptyList()

                    if (isAtRoot && secShortcuts.isNotEmpty()) {
                        val row = result.newRow()
                        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, "root:$shortcutId")
                        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, shortcut.name)
                        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
                        row.add(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE)
                        row.add(DocumentsContract.Document.COLUMN_SIZE, 0L)
                        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, shortcut.createdTime)
                        return result
                    }

                    var rowAdded = false
                    val targetDocId = if (isAtRoot) treeRootDocId else innerDocId
                    if (targetDocId.isNotEmpty() && DocumentsContract.isTreeUri(treeUri)) {
                        try {
                            val targetDocumentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, targetDocId)
                            val cursor = context?.contentResolver?.query(
                                targetDocumentUri,
                                projection,
                                null,
                                null,
                                null
                            )
                            if (cursor != null && cursor.moveToFirst()) {
                                copyAndTransformRow(cursor, result, resultProjection, shortcutId, shortcut.name, isAtRoot)
                                cursor.close()
                                rowAdded = true
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "SAF queryDocument failed for primary $shortcutId: ${e.message}")
                        }
                    }

                    if (!rowAdded) {
                        val resolvedFile = resolveFileForShortcut(shortcut.treeUri, if (isAtRoot) null else innerDocId)
                        val row = result.newRow()
                        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, if (isAtRoot) "root:$shortcutId" else documentId)
                        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, if (isAtRoot) shortcut.name else (resolvedFile?.name ?: "Folder"))
                        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, if (resolvedFile?.isFile == true) getMimeType(resolvedFile) else DocumentsContract.Document.MIME_TYPE_DIR)
                        row.add(
                            DocumentsContract.Document.COLUMN_FLAGS,
                            DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE or DocumentsContract.Document.FLAG_SUPPORTS_WRITE
                        )
                        row.add(DocumentsContract.Document.COLUMN_SIZE, resolvedFile?.length() ?: 0L)
                        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, resolvedFile?.lastModified() ?: shortcut.createdTime)
                    }
                    return result
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in queryDocument for $documentId", e)
            val row = result.newRow()
            row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
            row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, "Folder")
            row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
            row.add(DocumentsContract.Document.COLUMN_FLAGS, 0)
            row.add(DocumentsContract.Document.COLUMN_SIZE, 0L)
            row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, System.currentTimeMillis())
            return result
        }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val resultProjection = projection ?: DEFAULT_DOCUMENT_PROJECTION
        val result = MatrixCursor(resultProjection)

        try {
            when {
                parentDocumentId.startsWith("approot:") -> {
                    val shortcutId = parentDocumentId.substringAfter("approot:").toLongOrNull() ?: 0L
                    val shortcut = repository?.getShortcutByIdSync(shortcutId)
                    val packageName = shortcut?.treeUri?.substringAfter("app:") ?: ""
                    tryLaunchApp(packageName, shortcut?.name ?: "App")
                    setHonoredSortArgs(result)
                    return result
                }

                parentDocumentId.startsWith("sec:") -> {
                    val withoutPrefix = parentDocumentId.substringAfter("sec:")
                    val parts = withoutPrefix.split(":", limit = 2)
                    val secId = parts[0].toLongOrNull() ?: return result
                    val innerDocId = if (parts.size > 1) parts[1] else "root"

                    val secShortcut = repository?.getShortcutByIdSync(secId) ?: return result

                    val treeUri = Uri.parse(secShortcut.treeUri)
                    val treeDocId = safeGetTreeDocumentId(treeUri) ?: ""
                    val isAtRoot = innerDocId == "root" || innerDocId.isEmpty() || (treeDocId.isNotEmpty() && innerDocId == treeDocId)
                    val targetParentDocId = if (isAtRoot) treeDocId else innerDocId

                    val items = mutableListOf<DocumentRow>()
                    var safSuccess = false

                    if (targetParentDocId.isNotEmpty() && DocumentsContract.isTreeUri(treeUri)) {
                        try {
                            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, targetParentDocId)
                            val cursor = context?.contentResolver?.query(
                                childrenUri,
                                projection,
                                null,
                                null,
                                null
                            )

                            if (cursor != null) {
                                val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                                val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                                val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                                val flagsIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
                                val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)

                                while (cursor.moveToNext()) {
                                    val rawDocId = if (idIndex != -1) cursor.getString(idIndex) else ""
                                    val displayName = if (nameIndex != -1) cursor.getString(nameIndex) ?: "" else ""
                                    val mimeType = if (mimeIndex != -1) cursor.getString(mimeIndex) ?: "*/*" else "*/*"
                                    val lastModified = if (modifiedIndex != -1) cursor.getLong(modifiedIndex) else 0L
                                    val flags = if (flagsIndex != -1) cursor.getInt(flagsIndex) else 0
                                    val size = if (sizeIndex != -1) cursor.getLong(sizeIndex) else 0L

                                    val isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR

                                    items.add(
                                        DocumentRow(
                                            docId = "sec:$secId:$rawDocId",
                                            displayName = displayName,
                                            mimeType = mimeType,
                                            lastModified = lastModified,
                                            flags = flags,
                                            size = size,
                                            isDirectory = isDirectory
                                        )
                                    )
                                }
                                cursor.close()
                                safSuccess = true
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "SAF child query failed for secondary shortcut $secId: ${e.message}")
                        }
                    }

                    // Fallback to local filesystem directory if SAF failed or produced no children
                    if (!safSuccess || items.isEmpty()) {
                        val resolvedDir = resolveFileForShortcut(secShortcut.treeUri, if (isAtRoot) null else innerDocId)
                        if (resolvedDir != null && resolvedDir.isDirectory) {
                            val files = resolvedDir.listFiles() ?: emptyArray()
                            for (file in files) {
                                val isDir = file.isDirectory
                                items.add(
                                    DocumentRow(
                                        docId = "file:${file.absolutePath}",
                                        displayName = file.name,
                                        mimeType = if (isDir) DocumentsContract.Document.MIME_TYPE_DIR else getMimeType(file),
                                        lastModified = file.lastModified(),
                                        flags = if (file.canWrite()) DocumentsContract.Document.FLAG_SUPPORTS_DELETE or DocumentsContract.Document.FLAG_SUPPORTS_WRITE else 0,
                                        size = if (isDir) 0L else file.length(),
                                        isDirectory = isDir
                                    )
                                )
                            }
                        }
                    }

                    val parentShortcut = secShortcut.parentId?.let { repository?.getShortcutByIdSync(it) }
                    val activeSortMode = secShortcut.sortMode ?: parentShortcut?.sortMode ?: prefs?.sortMode ?: QuickFilePickerPrefs.SORT_DATE_DESC
                    sortItems(items, activeSortMode)

                    for (item in items) {
                        addRowToCursor(result, resultProjection, item)
                    }

                    setHonoredSortArgs(result)
                    return result
                }

                parentDocumentId.startsWith("smart:") -> {
                    val collectionType = parentDocumentId.substringAfter("smart:")
                    val smartCursor = querySmartCollection(collectionType, resultProjection)
                    if (smartCursor is MatrixCursor) {
                        setHonoredSortArgs(smartCursor)
                    }
                    return smartCursor
                }

                parentDocumentId.startsWith("file:") -> {
                    val dirPath = parentDocumentId.substringAfter("file:")
                    val dir = File(dirPath)
                    if (dir.exists() && dir.isDirectory) {
                        val files = dir.listFiles() ?: emptyArray()
                        val items = files.map { file ->
                            DocumentRow(
                                docId = "file:${file.absolutePath}",
                                displayName = file.name,
                                mimeType = if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else getMimeType(file),
                                lastModified = file.lastModified(),
                                flags = if (file.canWrite()) DocumentsContract.Document.FLAG_SUPPORTS_DELETE or DocumentsContract.Document.FLAG_SUPPORTS_WRITE else 0,
                                size = if (file.isDirectory) 0L else file.length(),
                                isDirectory = file.isDirectory
                            )
                        }.toMutableList()

                        sortItems(items, prefs?.sortMode ?: QuickFilePickerPrefs.SORT_DATE_DESC)

                        for (item in items) {
                            addRowToCursor(result, resultProjection, item)
                        }
                        setHonoredSortArgs(result)
                    }
                    return result
                }

                else -> {
                    val parsed = parseDocumentId(parentDocumentId) ?: return result
                    val (shortcutId, innerDocId) = parsed

                    val shortcut = repository?.getShortcutByIdSync(shortcutId) ?: return result

                    val treeUri = Uri.parse(shortcut.treeUri)
                    val treeRootDocId = safeGetTreeDocumentId(treeUri) ?: ""
                    val isAtRoot = innerDocId == "root" || innerDocId.isEmpty() || (treeRootDocId.isNotEmpty() && innerDocId == treeRootDocId)

                    val activeSortMode = shortcut.sortMode ?: prefs?.sortMode ?: QuickFilePickerPrefs.SORT_DATE_DESC
                    val secondaryShortcuts = repository?.getSecondaryShortcutsSync(shortcutId) ?: emptyList()

                    val items = mutableListOf<DocumentRow>()

                    // 1. If at root, display any secondary shortcuts (sub-folders)
                    if (isAtRoot && secondaryShortcuts.isNotEmpty()) {
                        for (sec in secondaryShortcuts) {
                            items.add(
                                DocumentRow(
                                    docId = "sec:${sec.id}:root",
                                    displayName = sec.name,
                                    mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                                    lastModified = sec.createdTime,
                                    flags = DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE,
                                    size = 0L,
                                    isDirectory = true
                                )
                            )
                        }
                    }

                    // 2. Also query physical children of this folder
                    var safSuccess = false
                    val targetParentDocId = if (isAtRoot) treeRootDocId else innerDocId

                    if (targetParentDocId.isNotEmpty() && DocumentsContract.isTreeUri(treeUri)) {
                        try {
                            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, targetParentDocId)
                            val cursor = context?.contentResolver?.query(
                                childrenUri,
                                projection,
                                null,
                                null,
                                null
                            )

                            if (cursor != null) {
                                val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                                val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                                val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                                val flagsIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
                                val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)

                                while (cursor.moveToNext()) {
                                    val rawDocId = if (idIndex != -1) cursor.getString(idIndex) else ""
                                    val displayName = if (nameIndex != -1) cursor.getString(nameIndex) ?: "" else ""
                                    val mimeType = if (mimeIndex != -1) cursor.getString(mimeIndex) ?: "*/*" else "*/*"
                                    val lastModified = if (modifiedIndex != -1) cursor.getLong(modifiedIndex) else 0L
                                    val flags = if (flagsIndex != -1) cursor.getInt(flagsIndex) else 0
                                    val size = if (sizeIndex != -1) cursor.getLong(sizeIndex) else 0L

                                    val isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR

                                    items.add(
                                        DocumentRow(
                                            docId = "$shortcutId:$rawDocId",
                                            displayName = displayName,
                                            mimeType = mimeType,
                                            lastModified = lastModified,
                                            flags = flags,
                                            size = size,
                                            isDirectory = isDirectory
                                        )
                                    )
                                }
                                cursor.close()
                                safSuccess = true
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "SAF child query failed for primary $shortcutId: ${e.message}")
                        }
                    }

                    // Fallback to local filesystem if SAF query was denied, failed, or returned no files
                    if (!safSuccess || items.none { !it.docId.startsWith("sec:") }) {
                        val resolvedDir = resolveFileForShortcut(shortcut.treeUri, if (isAtRoot) null else innerDocId)
                        if (resolvedDir != null && resolvedDir.isDirectory) {
                            val files = resolvedDir.listFiles() ?: emptyArray()
                            for (file in files) {
                                val isDir = file.isDirectory
                                items.add(
                                    DocumentRow(
                                        docId = "file:${file.absolutePath}",
                                        displayName = file.name,
                                        mimeType = if (isDir) DocumentsContract.Document.MIME_TYPE_DIR else getMimeType(file),
                                        lastModified = file.lastModified(),
                                        flags = if (file.canWrite()) DocumentsContract.Document.FLAG_SUPPORTS_DELETE or DocumentsContract.Document.FLAG_SUPPORTS_WRITE else 0,
                                        size = if (isDir) 0L else file.length(),
                                        isDirectory = isDir
                                    )
                                )
                            }
                        }
                    }

                    sortItems(items, activeSortMode)

                    for (item in items) {
                        addRowToCursor(result, resultProjection, item)
                    }

                    setHonoredSortArgs(result)
                    return result
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in queryChildDocuments for $parentDocumentId", e)
            setHonoredSortArgs(result)
            return result
        }
    }

    private fun querySmartCollection(collectionType: String, projection: Array<out String>): Cursor {
        val result = MatrixCursor(projection)
        val items = mutableListOf<DocumentRow>()
        val maxLimit = prefs?.maxRecentFiles ?: 200

        when (collectionType) {
            "recent_files" -> {
                scanAccessibleDirectories { file ->
                    if (!file.isDirectory) {
                        items.add(fileToDocumentRow(file))
                    }
                }
                items.sortByDescending { it.lastModified }
            }

            "recent_folders" -> {
                val recentFolders = repository?.getRecentFoldersSync() ?: emptyList()
                for (folder in recentFolders) {
                    val file = File(folder.path)
                    if (file.exists()) {
                        items.add(fileToDocumentRow(file))
                    }
                }
            }

            "favorites" -> {
                val favs = repository?.getFavoriteItemsSync() ?: emptyList()
                for (fav in favs) {
                    val uri = Uri.parse(fav.uriString)
                    if (uri.scheme == "file") {
                        val file = File(uri.path ?: "")
                        if (file.exists()) {
                            items.add(fileToDocumentRow(file))
                        }
                    }
                }
            }

            "pdfs" -> {
                scanAccessibleDirectories { file ->
                    if (!file.isDirectory && (file.extension.equals("pdf", ignoreCase = true) || file.name.endsWith(".pdf", ignoreCase = true))) {
                        items.add(fileToDocumentRow(file))
                    }
                }
                items.sortByDescending { it.lastModified }
            }

            "apks" -> {
                scanAccessibleDirectories { file ->
                    if (!file.isDirectory && file.extension.equals("apk", ignoreCase = true)) {
                        items.add(fileToDocumentRow(file))
                    }
                }
                items.sortByDescending { it.lastModified }
            }

            "pictures" -> {
                scanAccessibleDirectories { file ->
                    if (!file.isDirectory && isImageFile(file.name)) {
                        items.add(fileToDocumentRow(file))
                    }
                }
                items.sortByDescending { it.lastModified }
            }

            "videos" -> {
                scanAccessibleDirectories { file ->
                    if (!file.isDirectory && isVideoFile(file.name)) {
                        items.add(fileToDocumentRow(file))
                    }
                }
                items.sortByDescending { it.lastModified }
            }
        }

        val limitedItems = if (items.size > maxLimit) items.take(maxLimit) else items
        for (item in limitedItems) {
            addRowToCursor(result, projection, item)
        }

        return result
    }

    private fun scanAccessibleDirectories(onFileFound: (File) -> Unit) {
        val rootDirs = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        )

        val queue = PriorityQueue<File>()
        for (dir in rootDirs) {
            if (dir != null && dir.exists() && dir.isDirectory) {
                queue.add(dir)
            }
        }

        var scannedCount = 0
        while (queue.isNotEmpty() && scannedCount < 800) {
            val current = queue.poll() ?: break
            val children = current.listFiles() ?: continue
            for (child in children) {
                scannedCount++
                if (child.isDirectory && !child.name.startsWith(".")) {
                    queue.add(child)
                } else if (child.isFile && !child.name.startsWith(".")) {
                    onFileFound(child)
                }
            }
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        if (documentId.startsWith("appaction:")) {
            val shortcutId = documentId.substringAfter("appaction:").toLongOrNull() ?: 0L
            val shortcut = repository?.getShortcutByIdSync(shortcutId)
            val packageName = shortcut?.treeUri?.substringAfter("app:") ?: ""
            tryLaunchApp(packageName, shortcut?.name ?: "App")

            val cacheFile = File(context?.cacheDir, "app_launcher.txt")
            if (!cacheFile.exists()) {
                cacheFile.writeText("Opening ${shortcut?.name ?: "App"}...")
            }
            return ParcelFileDescriptor.open(cacheFile, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        if (documentId.startsWith("file:")) {
            val file = File(documentId.substringAfter("file:"))
            val parcelMode = ParcelFileDescriptor.parseMode(mode)
            return ParcelFileDescriptor.open(file, parcelMode)
        }

        if (documentId.startsWith("sec:")) {
            val withoutPrefix = documentId.substringAfter("sec:")
            val parts = withoutPrefix.split(":", limit = 2)
            val secId = parts[0].toLongOrNull() ?: throw IllegalArgumentException("Invalid secId: $documentId")
            val innerDocId = if (parts.size > 1) parts[1] else "root"

            val secShortcut = repository?.getShortcutByIdSync(secId)
                ?: throw IllegalArgumentException("Secondary shortcut not found for ID: $secId")

            val treeUri = Uri.parse(secShortcut.treeUri)
            if (DocumentsContract.isTreeUri(treeUri)) {
                try {
                    val targetDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, innerDocId)
                    val pfd = context?.contentResolver?.openFileDescriptor(targetDocUri, mode, signal)
                    if (pfd != null) return pfd
                } catch (e: Exception) {
                    Log.w(TAG, "SAF openFileDescriptor failed for sec:$documentId, falling back to file", e)
                }
            }

            val file = resolveFileForShortcut(secShortcut.treeUri, innerDocId)
            if (file != null && file.exists()) {
                val parcelMode = ParcelFileDescriptor.parseMode(mode)
                return ParcelFileDescriptor.open(file, parcelMode)
            }
            throw IOException("Unable to open file descriptor for $documentId")
        }

        val parsed = parseDocumentId(documentId)
            ?: throw IllegalArgumentException("Invalid documentId: $documentId")
        val (shortcutId, innerDocId) = parsed

        val shortcut = repository?.getShortcutByIdSync(shortcutId)
            ?: throw IllegalArgumentException("Shortcut not found for ID: $shortcutId")

        val treeUri = Uri.parse(shortcut.treeUri)
        if (DocumentsContract.isTreeUri(treeUri)) {
            try {
                val targetDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, innerDocId)
                val pfd = context?.contentResolver?.openFileDescriptor(targetDocUri, mode, signal)
                if (pfd != null) return pfd
            } catch (e: Exception) {
                Log.w(TAG, "SAF openFileDescriptor failed for $documentId, falling back to file", e)
            }
        }

        val file = resolveFileForShortcut(shortcut.treeUri, innerDocId)
        if (file != null && file.exists()) {
            val parcelMode = ParcelFileDescriptor.parseMode(mode)
            return ParcelFileDescriptor.open(file, parcelMode)
        }
        throw IOException("Unable to open file descriptor for $documentId")
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String {
        return when {
            parentDocumentId.startsWith("sec:") -> {
                val withoutPrefix = parentDocumentId.substringAfter("sec:")
                val parts = withoutPrefix.split(":", limit = 2)
                val secId = parts[0].toLongOrNull() ?: throw IllegalArgumentException("Invalid parent: $parentDocumentId")
                val innerDocId = if (parts.size > 1) parts[1] else "root"
                val sec = repository?.getShortcutByIdSync(secId) ?: throw IllegalArgumentException("Secondary shortcut not found")
                val treeUri = Uri.parse(sec.treeUri)
                val treeDocId = safeGetTreeDocumentId(treeUri) ?: ""
                val targetDocId = if (innerDocId == "root") treeDocId else innerDocId

                if (targetDocId.isNotEmpty() && DocumentsContract.isTreeUri(treeUri)) {
                    try {
                        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, targetDocId)
                        val newDocUri = DocumentsContract.createDocument(context!!.contentResolver, parentUri, mimeType, displayName)
                        if (newDocUri != null) {
                            val newDocId = DocumentsContract.getDocumentId(newDocUri)
                            return "sec:$secId:$newDocId"
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "SAF createDocument failed, falling back to File", e)
                    }
                }

                val resolvedDir = resolveFileForShortcut(sec.treeUri, if (innerDocId == "root") null else innerDocId)
                if (resolvedDir != null && resolvedDir.isDirectory) {
                    val newFile = File(resolvedDir, displayName)
                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        newFile.mkdirs()
                    } else {
                        newFile.createNewFile()
                    }
                    return "file:${newFile.absolutePath}"
                }
                throw IOException("Failed to create document")
            }
            parentDocumentId.startsWith("file:") -> {
                val dir = File(parentDocumentId.substringAfter("file:"))
                val newFile = File(dir, displayName)
                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    newFile.mkdirs()
                } else {
                    newFile.createNewFile()
                }
                "file:${newFile.absolutePath}"
            }
            else -> {
                val (shortcutId, innerDocId) = parseDocumentId(parentDocumentId)
                    ?: throw IllegalArgumentException("Invalid parent: $parentDocumentId")
                val shortcut = repository?.getShortcutByIdSync(shortcutId)
                    ?: throw IllegalArgumentException("Shortcut not found")
                val treeUri = Uri.parse(shortcut.treeUri)
                val treeDocId = safeGetTreeDocumentId(treeUri) ?: ""
                val targetDocId = if (innerDocId == "root") treeDocId else innerDocId

                if (targetDocId.isNotEmpty() && DocumentsContract.isTreeUri(treeUri)) {
                    try {
                        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, targetDocId)
                        val newDocUri = DocumentsContract.createDocument(context!!.contentResolver, parentUri, mimeType, displayName)
                        if (newDocUri != null) {
                            val newDocId = DocumentsContract.getDocumentId(newDocUri)
                            return "$shortcutId:$newDocId"
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "SAF createDocument failed for primary, falling back to File", e)
                    }
                }

                val resolvedDir = resolveFileForShortcut(shortcut.treeUri, if (innerDocId == "root") null else innerDocId)
                if (resolvedDir != null && resolvedDir.isDirectory) {
                    val newFile = File(resolvedDir, displayName)
                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        newFile.mkdirs()
                    } else {
                        newFile.createNewFile()
                    }
                    return "file:${newFile.absolutePath}"
                }
                throw IOException("Failed to create document")
            }
        }
    }

    override fun deleteDocument(documentId: String) {
        when {
            documentId.startsWith("sec:") -> {
                val withoutPrefix = documentId.substringAfter("sec:")
                val parts = withoutPrefix.split(":", limit = 2)
                val secId = parts[0].toLongOrNull() ?: return
                val innerDocId = if (parts.size > 1) parts[1] else "root"
                val sec = repository?.getShortcutByIdSync(secId) ?: return
                val treeUri = Uri.parse(sec.treeUri)
                if (DocumentsContract.isTreeUri(treeUri)) {
                    try {
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, innerDocId)
                        DocumentsContract.deleteDocument(context!!.contentResolver, docUri)
                        return
                    } catch (e: Exception) {
                        Log.w(TAG, "SAF deleteDocument failed, falling back to File", e)
                    }
                }
                val file = resolveFileForShortcut(sec.treeUri, innerDocId)
                file?.deleteRecursively()
            }
            documentId.startsWith("file:") -> {
                val file = File(documentId.substringAfter("file:"))
                file.deleteRecursively()
            }
            else -> {
                val (shortcutId, innerDocId) = parseDocumentId(documentId) ?: return
                val shortcut = repository?.getShortcutByIdSync(shortcutId) ?: return
                val treeUri = Uri.parse(shortcut.treeUri)
                if (DocumentsContract.isTreeUri(treeUri)) {
                    try {
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, innerDocId)
                        DocumentsContract.deleteDocument(context!!.contentResolver, docUri)
                        return
                    } catch (e: Exception) {
                        Log.w(TAG, "SAF deleteDocument failed, falling back to File", e)
                    }
                }
                val file = resolveFileForShortcut(shortcut.treeUri, innerDocId)
                file?.deleteRecursively()
            }
        }
    }

    override fun querySearchDocuments(
        rootId: String,
        query: String,
        projection: Array<out String>?
    ): Cursor {
        val resultProjection = projection ?: DEFAULT_DOCUMENT_PROJECTION
        val result = MatrixCursor(resultProjection)

        scanAccessibleDirectories { file ->
            if (file.name.contains(query, ignoreCase = true)) {
                addRowToCursor(result, resultProjection, fileToDocumentRow(file))
            }
        }

        return result
    }

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point,
        signal: CancellationSignal?
    ): AssetFileDescriptor {
        var pfd: ParcelFileDescriptor? = null
        var mimeType = "*/*"

        if (documentId.startsWith("sec:")) {
            val withoutPrefix = documentId.substringAfter("sec:")
            val parts = withoutPrefix.split(":", limit = 2)
            val secId = parts[0].toLongOrNull()
            val innerDocId = if (parts.size > 1) parts[1] else "root"
            if (secId != null) {
                val secShortcut = repository?.getShortcutByIdSync(secId)
                if (secShortcut != null) {
                    val treeUri = Uri.parse(secShortcut.treeUri)
                    if (DocumentsContract.isTreeUri(treeUri)) {
                        try {
                            val targetDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, innerDocId)
                            pfd = context?.contentResolver?.openFileDescriptor(targetDocUri, "r", signal)
                            mimeType = context?.contentResolver?.getType(targetDocUri) ?: "*/*"
                        } catch (e: Exception) {
                            // Ignore
                        }
                    }
                    if (pfd == null) {
                        val file = resolveFileForShortcut(secShortcut.treeUri, innerDocId)
                        if (file != null && file.exists()) {
                            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                            mimeType = getMimeType(file)
                        }
                    }
                }
            }
        } else if (documentId.startsWith("file:")) {
            val file = File(documentId.substringAfter("file:"))
            if (file.exists()) {
                pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                mimeType = getMimeType(file)
            }
        } else {
            val parsed = parseDocumentId(documentId)
            if (parsed != null) {
                val shortcut = repository?.getShortcutByIdSync(parsed.first)
                if (shortcut != null) {
                    val treeUri = Uri.parse(shortcut.treeUri)
                    if (DocumentsContract.isTreeUri(treeUri)) {
                        try {
                            val targetDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parsed.second)
                            pfd = context?.contentResolver?.openFileDescriptor(targetDocUri, "r", signal)
                            mimeType = context?.contentResolver?.getType(targetDocUri) ?: "*/*"
                        } catch (e: Exception) {
                            // Ignore
                        }
                    }
                    if (pfd == null) {
                        val file = resolveFileForShortcut(shortcut.treeUri, parsed.second)
                        if (file != null && file.exists()) {
                            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                            mimeType = getMimeType(file)
                        }
                    }
                }
            }
        }

        if (pfd == null) {
            throw IOException("Unable to open file descriptor for thumbnail: $documentId")
        }

        try {
            val bitmap: Bitmap? = when {
                mimeType.startsWith("image/") -> {
                    BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor)
                }
                mimeType.startsWith("video/") -> {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(pfd.fileDescriptor)
                    val frame = retriever.frameAtTime
                    retriever.release()
                    frame
                }
                mimeType == "application/pdf" || documentId.endsWith(".pdf", ignoreCase = true) -> {
                    renderPdfThumbnail(pfd, sizeHint)
                }
                mimeType == "application/vnd.android.package-archive" || documentId.endsWith(".apk", ignoreCase = true) -> {
                    renderApkThumbnail(documentId)
                }
                else -> null
            }

            if (bitmap != null) {
                val tempFile = File.createTempFile("thumb_", ".png", context?.cacheDir)
                FileOutputStream(tempFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                }
                pfd.close()

                val thumbPfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
                return AssetFileDescriptor(thumbPfd, 0, tempFile.length())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Thumbnail generation failed for $documentId", e)
        }

        return AssetFileDescriptor(pfd, 0, pfd.statSize)
    }

    private fun renderApkThumbnail(documentId: String): Bitmap? {
        if (!documentId.startsWith("file:")) return null
        return try {
            val filePath = documentId.substringAfter("file:")
            val pm = context?.packageManager
            val info = pm?.getPackageArchiveInfo(filePath, 0)
            val appInfo = info?.applicationInfo
            if (appInfo != null && pm != null) {
                appInfo.sourceDir = filePath
                appInfo.publicSourceDir = filePath
                val drawable = appInfo.loadIcon(pm)
                drawableToBitmap(drawable)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        val bitmap = Bitmap.createBitmap(
            drawable.intrinsicWidth.coerceAtLeast(128),
            drawable.intrinsicHeight.coerceAtLeast(128),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun renderPdfThumbnail(pfd: ParcelFileDescriptor, sizeHint: Point): Bitmap? {
        return try {
            val renderer = PdfRenderer(pfd)
            if (renderer.pageCount > 0) {
                val page = renderer.openPage(0)
                val width = sizeHint.x.coerceAtLeast(128)
                val height = sizeHint.y.coerceAtLeast(128)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                renderer.close()
                bitmap
            } else {
                renderer.close()
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun sortItems(items: MutableList<DocumentRow>, mode: String?) {
        val effectiveMode = mode ?: QuickFilePickerPrefs.SORT_DATE_DESC
        when (effectiveMode) {
            QuickFilePickerPrefs.SORT_PREFIX_NUMBER -> {
                items.sortWith(compareByDescending<DocumentRow> { it.isDirectory }.thenByDescending { it.lastModified }.thenBy { it.displayName.lowercase() })
                var counter = 1
                for (item in items) {
                    if (!item.isDirectory) {
                        val prefix = String.format("%02d. ", counter++)
                        item.displayName = prefix + item.displayName
                    }
                }
            }
            QuickFilePickerPrefs.SORT_PREFIX_DATE -> {
                items.sortWith(compareByDescending<DocumentRow> { it.isDirectory }.thenByDescending { it.lastModified }.thenBy { it.displayName.lowercase() })
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                for (item in items) {
                    if (!item.isDirectory && item.lastModified > 0) {
                        val dateStr = sdf.format(java.util.Date(item.lastModified))
                        item.displayName = "[$dateStr] ${item.displayName}"
                    }
                }
            }
            QuickFilePickerPrefs.SORT_DATE_DESC -> items.sortWith(compareByDescending<DocumentRow> { it.isDirectory }.thenByDescending { it.lastModified }.thenBy { it.displayName.lowercase() })
            QuickFilePickerPrefs.SORT_DATE_ASC -> items.sortWith(compareByDescending<DocumentRow> { it.isDirectory }.thenBy { it.lastModified }.thenBy { it.displayName.lowercase() })
            QuickFilePickerPrefs.SORT_SIZE_DESC -> items.sortWith(compareByDescending<DocumentRow> { it.isDirectory }.thenByDescending { it.size }.thenBy { it.displayName.lowercase() })
            QuickFilePickerPrefs.SORT_SIZE_ASC -> items.sortWith(compareByDescending<DocumentRow> { it.isDirectory }.thenBy { it.size }.thenBy { it.displayName.lowercase() })
            QuickFilePickerPrefs.SORT_NAME_DESC -> items.sortWith(compareByDescending<DocumentRow> { it.isDirectory }.thenByDescending { it.displayName.lowercase() })
            QuickFilePickerPrefs.SORT_EXTENSION -> items.sortWith(compareByDescending<DocumentRow> { it.isDirectory }.thenBy { File(it.displayName).extension.lowercase() }.thenBy { it.displayName.lowercase() })
            else -> items.sortWith(compareByDescending<DocumentRow> { it.isDirectory }.thenBy { it.displayName.lowercase() })
        }
    }

    private fun setHonoredSortArgs(cursor: MatrixCursor) {
        try {
            val existing: Bundle? = cursor.extras
            val extraBundle = if (existing != null) Bundle(existing) else Bundle()
            extraBundle.putStringArray(
                android.content.ContentResolver.EXTRA_HONORED_ARGS,
                arrayOf(
                    android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS,
                    android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION
                )
            )
            cursor.extras = extraBundle
        } catch (e: Exception) {
            Log.e(TAG, "Error setting honored sort args", e)
        }
    }

    private fun fileToDocumentRow(file: File): DocumentRow {
        return DocumentRow(
            docId = "file:${file.absolutePath}",
            displayName = file.name,
            mimeType = if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else getMimeType(file),
            lastModified = file.lastModified(),
            flags = if (file.canWrite()) DocumentsContract.Document.FLAG_SUPPORTS_DELETE or DocumentsContract.Document.FLAG_SUPPORTS_WRITE else 0,
            size = if (file.isDirectory) 0L else file.length(),
            isDirectory = file.isDirectory
        )
    }

    private fun addRowToCursor(target: MatrixCursor, projection: Array<out String>, item: DocumentRow) {
        val row = target.newRow()
        for (col in projection) {
            when (col) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> row.add(item.docId)
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> row.add(item.displayName)
                DocumentsContract.Document.COLUMN_MIME_TYPE -> row.add(item.mimeType)
                DocumentsContract.Document.COLUMN_LAST_MODIFIED -> row.add(item.lastModified)
                DocumentsContract.Document.COLUMN_FLAGS -> row.add(item.flags)
                DocumentsContract.Document.COLUMN_SIZE -> row.add(item.size)
                else -> row.add(null)
            }
        }
    }

    private fun isImageFile(name: String): Boolean {
        val ext = File(name).extension.lowercase()
        return ext in listOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "svg")
    }

    private fun isVideoFile(name: String): Boolean {
        val ext = File(name).extension.lowercase()
        return ext in listOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp")
    }

    private fun getMimeType(file: File): String {
        val ext = file.extension.lowercase()
        return when (ext) {
            "pdf" -> "application/pdf"
            "apk" -> "application/vnd.android.package-archive"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "txt" -> "text/plain"
            "json" -> "application/json"
            "zip" -> "application/zip"
            else -> "*/*"
        }
    }

    private fun parseDocumentId(documentId: String): Pair<Long, String>? {
        if (documentId.startsWith("root:")) {
            val id = documentId.substringAfter("root:").toLongOrNull() ?: return null
            return Pair(id, "root")
        }
        val parts = documentId.split(":", limit = 2)
        if (parts.size == 2) {
            val id = parts[0].toLongOrNull() ?: return null
            return Pair(id, parts[1])
        }
        val singleId = documentId.toLongOrNull()
        if (singleId != null) {
            return Pair(singleId, "root")
        }
        return null
    }

    private fun safeGetTreeDocumentId(uri: Uri): String? {
        return try {
            if (DocumentsContract.isTreeUri(uri)) {
                DocumentsContract.getTreeDocumentId(uri)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun resolveFileForShortcut(treeUriString: String, innerDocId: String? = null): File? {
        try {
            if (treeUriString.startsWith("/") || treeUriString.startsWith("file://")) {
                val basePath = treeUriString.removePrefix("file://")
                val baseFile = File(basePath)
                if (innerDocId == null || innerDocId == "root" || innerDocId.isEmpty()) {
                    return if (baseFile.exists()) baseFile else null
                }
                val target = File(baseFile, innerDocId)
                return if (target.exists()) target else if (baseFile.exists()) baseFile else null
            }

            val uri = Uri.parse(treeUriString)
            val treeDocId = safeGetTreeDocumentId(uri) ?: ""
            val targetDocId = if (innerDocId != null && innerDocId != "root" && innerDocId.isNotEmpty()) {
                innerDocId
            } else {
                treeDocId
            }

            if (targetDocId.isNotEmpty()) {
                val parts = targetDocId.split(":", limit = 2)
                if (parts.size == 2) {
                    val volume = parts[0]
                    val subPath = parts[1]
                    val baseDir = if (volume.equals("primary", ignoreCase = true)) {
                        Environment.getExternalStorageDirectory()
                    } else {
                        File("/storage/$volume")
                    }
                    val candidate = File(baseDir, subPath)
                    if (candidate.exists()) return candidate

                    val fallbackDirect = File(Environment.getExternalStorageDirectory(), subPath)
                    if (fallbackDirect.exists()) return fallbackDirect
                } else {
                    val candidate = File(Environment.getExternalStorageDirectory(), targetDocId)
                    if (candidate.exists()) return candidate
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving file for $treeUriString", e)
        }
        return null
    }

    private fun copyAndTransformRow(
        source: Cursor,
        target: MatrixCursor,
        projection: Array<out String>,
        shortcutId: Long,
        overrideName: String?,
        isRootDoc: Boolean
    ) {
        val row = target.newRow()
        for (col in projection) {
            when (col) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> {
                    val rawId = getStringFromCursor(source, col)
                    if (isRootDoc) {
                        row.add("root:$shortcutId")
                    } else {
                        row.add("$shortcutId:$rawId")
                    }
                }
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> {
                    if (overrideName != null && isRootDoc) {
                        row.add(overrideName)
                    } else {
                        row.add(getStringFromCursor(source, col))
                    }
                }
                DocumentsContract.Document.COLUMN_MIME_TYPE -> row.add(getStringFromCursor(source, col))
                DocumentsContract.Document.COLUMN_LAST_MODIFIED -> row.add(getLongFromCursor(source, col))
                DocumentsContract.Document.COLUMN_FLAGS -> row.add(getIntFromCursor(source, col))
                DocumentsContract.Document.COLUMN_SIZE -> row.add(getLongFromCursor(source, col))
                else -> row.add(null)
            }
        }
    }

    private fun copyAndTransformSecRow(
        source: Cursor,
        target: MatrixCursor,
        projection: Array<out String>,
        secId: Long,
        overrideName: String?,
        isRootDoc: Boolean
    ) {
        val row = target.newRow()
        for (col in projection) {
            when (col) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> {
                    val rawId = getStringFromCursor(source, col)
                    if (isRootDoc) {
                        row.add("sec:$secId:root")
                    } else {
                        row.add("sec:$secId:$rawId")
                    }
                }
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> {
                    if (overrideName != null && isRootDoc) {
                        row.add(overrideName)
                    } else {
                        row.add(getStringFromCursor(source, col))
                    }
                }
                DocumentsContract.Document.COLUMN_MIME_TYPE -> row.add(getStringFromCursor(source, col))
                DocumentsContract.Document.COLUMN_LAST_MODIFIED -> row.add(getLongFromCursor(source, col))
                DocumentsContract.Document.COLUMN_FLAGS -> row.add(getIntFromCursor(source, col))
                DocumentsContract.Document.COLUMN_SIZE -> row.add(getLongFromCursor(source, col))
                else -> row.add(null)
            }
        }
    }

    private fun getStringFromCursor(cursor: Cursor, col: String): String {
        val idx = cursor.getColumnIndex(col)
        return if (idx != -1) cursor.getString(idx) ?: "" else ""
    }

    private fun getLongFromCursor(cursor: Cursor, col: String): Long {
        val idx = cursor.getColumnIndex(col)
        return if (idx != -1) cursor.getLong(idx) else 0L
    }

    private fun getIntFromCursor(cursor: Cursor, col: String): Int {
        val idx = cursor.getColumnIndex(col)
        return if (idx != -1) cursor.getInt(idx) else 0
    }

    private fun getDisplayPath(treeUriString: String): String {
        return try {
            val uri = Uri.parse(treeUriString)
            val docId = safeGetTreeDocumentId(uri) ?: return treeUriString
            val parts = docId.split(":", limit = 2)
            if (parts.size == 2) {
                val volume = if (parts[0] == "primary") "Internal Storage" else parts[0]
                "/storage/emulated/0/${parts[1]}"
            } else {
                docId
            }
        } catch (e: Exception) {
            treeUriString
        }
    }

    private fun getIconResourceId(iconName: String): Int {
        return when (iconName.lowercase()) {
            "app" -> R.drawable.ic_app
            "rocket" -> R.drawable.ic_rocket
            "book" -> R.drawable.ic_book
            "medical" -> R.drawable.ic_medical
            "telegram" -> R.drawable.ic_telegram
            "whatsapp" -> R.drawable.ic_whatsapp
            "image" -> R.drawable.ic_image
            "pdf" -> R.drawable.ic_pdf
            "download" -> R.drawable.ic_download
            "favorite", "star" -> R.drawable.ic_star
            "documents" -> R.drawable.ic_documents
            "storage" -> R.drawable.ic_storage
            else -> R.drawable.ic_folder
        }
    }

    private fun tryLaunchApp(packageName: String, appName: String) {
        if (packageName.isEmpty()) return
        try {
            val ctx = context ?: return
            val pm = ctx.packageManager ?: return

            var launchIntent = pm.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            if (launchIntent == null) {
                val getContentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    setPackage(packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                if (pm.queryIntentActivities(getContentIntent, 0).isNotEmpty()) {
                    launchIntent = getContentIntent
                }
            }

            if (launchIntent == null) {
                launchIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setPackage(packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            }

            if (launchIntent != null) {
                try {
                    ctx.startActivity(launchIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Direct launch failed: ${e.message}")
                }
            }

            showLaunchNotification(packageName, appName, launchIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error in tryLaunchApp", e)
        }
    }

    private fun showLaunchNotification(packageName: String, appName: String, intent: Intent?) {
        try {
            val ctx = context ?: return
            val pendingIntent = if (intent != null) {
                val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                PendingIntent.getActivity(ctx, (packageName.hashCode() and 0x7FFFFFFF), intent, flags)
            } else null

            val builder = androidx.core.app.NotificationCompat.Builder(ctx, "app_launcher_channel")
                .setSmallIcon(R.drawable.ic_rocket)
                .setContentTitle("🚀 Open $appName")
                .setContentText("Tap here to open $appName to browse files")
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
                .setCategory(androidx.core.app.NotificationCompat.CATEGORY_ALARM)
                .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
                .setTicker("Open $appName")
                .setDefaults(androidx.core.app.NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)

            if (pendingIntent != null) {
                builder.setContentIntent(pendingIntent)
                builder.setFullScreenIntent(pendingIntent, true)
            }

            val notificationManager = androidx.core.app.NotificationManagerCompat.from(ctx)
            notificationManager.notify((packageName.hashCode() and 0x7FFFFFFF), builder.build())
        } catch (e: Exception) {
            Log.e(TAG, "Error displaying launch notification", e)
        }
    }

    private data class AutoRootDef(
        val id: String,
        val title: String,
        val dir: File,
        val iconRes: Int
    )

    private data class DocumentRow(
        val docId: String,
        var displayName: String,
        val mimeType: String,
        val lastModified: Long,
        val flags: Int,
        val size: Long,
        val isDirectory: Boolean
    )
}
