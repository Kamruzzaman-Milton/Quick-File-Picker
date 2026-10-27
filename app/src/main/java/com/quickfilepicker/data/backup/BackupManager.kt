package com.quickfilepicker.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.quickfilepicker.data.model.Shortcut
import com.quickfilepicker.data.pref.QuickFilePickerPrefs
import java.io.InputStreamReader
import java.io.OutputStreamWriter

data class BackupSettings(
    val enableAutoFolders: Boolean = true,
    val enableSmartCollections: Boolean = true,
    val enableSearch: Boolean = true,
    val enableRecentFiles: Boolean = true,
    val enableRecentFolders: Boolean = true,
    val maxRecentFiles: Int = 200,
    val sortMode: String = QuickFilePickerPrefs.SORT_DATE_DESC,
    val darkThemeMode: String = QuickFilePickerPrefs.THEME_SYSTEM
)

data class BackupShortcut(
    val id: Long? = null,
    val name: String,
    val treeUri: String,
    val icon: String = "folder",
    val sortOrder: Int = 0,
    val parentId: Long? = null,
    val parentIndex: Int? = null,
    val isPinned: Boolean = false,
    val isFavorite: Boolean = false,
    val isHidden: Boolean = false,
    val sortMode: String? = null
)

data class BackupContainer(
    val version: Int = 2,
    val settings: BackupSettings? = null,
    val shortcuts: List<BackupShortcut> = emptyList()
)

class BackupManager(private val context: Context) {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun exportBackup(shortcuts: List<Shortcut>, destinationUri: Uri): Boolean {
        return try {
            val prefs = QuickFilePickerPrefs(context)
            val settings = BackupSettings(
                enableAutoFolders = prefs.enableAutoFolders,
                enableSmartCollections = prefs.enableSmartCollections,
                enableSearch = prefs.enableSearch,
                enableRecentFiles = prefs.enableRecentFiles,
                enableRecentFolders = prefs.enableRecentFolders,
                maxRecentFiles = prefs.maxRecentFiles,
                sortMode = prefs.sortMode,
                darkThemeMode = prefs.darkThemeMode
            )

            val backupShortcuts = shortcuts.map { item ->
                val parentIdx = if (item.parentId != null) {
                    shortcuts.indexOfFirst { it.id == item.parentId }.takeIf { it >= 0 }
                } else null

                BackupShortcut(
                    id = item.id,
                    name = item.name,
                    treeUri = item.treeUri,
                    icon = item.icon,
                    sortOrder = item.sortOrder,
                    parentId = item.parentId,
                    parentIndex = parentIdx,
                    isPinned = item.isPinned,
                    isFavorite = item.isFavorite,
                    isHidden = item.isHidden,
                    sortMode = item.sortMode
                )
            }

            val container = BackupContainer(
                version = 2,
                settings = settings,
                shortcuts = backupShortcuts
            )
            val jsonString = gson.toJson(container)

            context.contentResolver.openOutputStream(destinationUri)?.use { outputStream ->
                OutputStreamWriter(outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(jsonString)
                    writer.flush()
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun importBackup(sourceUri: Uri): List<Shortcut> {
        val shortcuts = mutableListOf<Shortcut>()
        try {
            val jsonText = context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                InputStreamReader(inputStream, Charsets.UTF_8).use { reader ->
                    reader.readText()
                }
            } ?: return emptyList()

            if (jsonText.isBlank()) return emptyList()

            val jsonElement: JsonElement = JsonParser.parseString(jsonText)

            // Restore settings if present
            if (jsonElement.isJsonObject) {
                val rootObj = jsonElement.asJsonObject
                if (rootObj.has("settings") && rootObj.get("settings").isJsonObject) {
                    val sObj = rootObj.getAsJsonObject("settings")
                    val prefs = QuickFilePickerPrefs(context)
                    if (sObj.has("enableAutoFolders")) prefs.enableAutoFolders = sObj.get("enableAutoFolders").asBoolean
                    if (sObj.has("enableSmartCollections")) prefs.enableSmartCollections = sObj.get("enableSmartCollections").asBoolean
                    if (sObj.has("enableSearch")) prefs.enableSearch = sObj.get("enableSearch").asBoolean
                    if (sObj.has("enableRecentFiles")) prefs.enableRecentFiles = sObj.get("enableRecentFiles").asBoolean
                    if (sObj.has("enableRecentFolders")) prefs.enableRecentFolders = sObj.get("enableRecentFolders").asBoolean
                    if (sObj.has("maxRecentFiles")) prefs.maxRecentFiles = sObj.get("maxRecentFiles").asInt
                    if (sObj.has("sortMode")) prefs.sortMode = sObj.get("sortMode").asString
                    if (sObj.has("darkThemeMode")) prefs.darkThemeMode = sObj.get("darkThemeMode").asString
                }
            }

            val jsonArray: JsonArray = when {
                jsonElement.isJsonArray -> jsonElement.asJsonArray
                jsonElement.isJsonObject -> {
                    val obj = jsonElement.asJsonObject
                    when {
                        obj.has("shortcuts") && obj.get("shortcuts").isJsonArray -> obj.getAsJsonArray("shortcuts")
                        obj.has("items") && obj.get("items").isJsonArray -> obj.getAsJsonArray("items")
                        else -> JsonArray()
                    }
                }
                else -> JsonArray()
            }

            data class RawParsedItem(
                val originalId: Long?,
                val name: String,
                val treeUri: String,
                val icon: String,
                val sortOrder: Int,
                val parentIndex: Int?,
                val originalParentId: Long?,
                val isPinned: Boolean,
                val isFavorite: Boolean,
                val isHidden: Boolean,
                val sortMode: String?
            )

            val rawItems = mutableListOf<RawParsedItem>()

            for (i in 0 until jsonArray.size()) {
                val itemEl = jsonArray.get(i)
                if (!itemEl.isJsonObject) continue
                val obj = itemEl.asJsonObject

                val name = obj.get("name")?.asString ?: "Shortcut"
                val treeUri = obj.get("treeUri")?.asString
                    ?: obj.get("uri")?.asString
                    ?: obj.get("path")?.asString
                    ?: ""
                if (treeUri.isEmpty()) continue

                val icon = obj.get("icon")?.asString ?: "folder"
                val sortOrder = obj.get("sortOrder")?.asInt ?: i
                val parentIndex = if (obj.has("parentIndex") && !obj.get("parentIndex").isJsonNull) {
                    obj.get("parentIndex").asInt
                } else null
                val originalId = if (obj.has("id") && !obj.get("id").isJsonNull) {
                    obj.get("id").asLong
                } else null
                val originalParentId = if (obj.has("parentId") && !obj.get("parentId").isJsonNull) {
                    obj.get("parentId").asLong
                } else null
                val isPinned = obj.get("isPinned")?.asBoolean ?: false
                val isFavorite = obj.get("isFavorite")?.asBoolean ?: false
                val isHidden = obj.get("isHidden")?.asBoolean ?: false
                val sortMode = if (obj.has("sortMode") && !obj.get("sortMode").isJsonNull) {
                    obj.get("sortMode").asString
                } else null

                // Try to take persistable URI permission if it's a content URI
                if (treeUri.startsWith("content://")) {
                    try {
                        val uri = Uri.parse(treeUri)
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    } catch (e: Exception) {
                        // Persistable permission might need direct file fallback
                    }
                }

                rawItems.add(
                    RawParsedItem(
                        originalId = originalId,
                        name = name,
                        treeUri = treeUri,
                        icon = icon,
                        sortOrder = sortOrder,
                        parentIndex = parentIndex,
                        originalParentId = originalParentId,
                        isPinned = isPinned,
                        isFavorite = isFavorite,
                        isHidden = isHidden,
                        sortMode = sortMode
                    )
                )
            }

            // Distinguish primary shortcuts from secondary shortcuts
            val primaryIndices = rawItems.indices.filter { idx ->
                val it = rawItems[idx]
                it.parentIndex == null && it.originalParentId == null
            }
            val secondaryIndices = rawItems.indices.filter { idx ->
                idx !in primaryIndices
            }

            var nextId = 1L
            val indexToNewId = mutableMapOf<Int, Long>()
            val originalIdToNewId = mutableMapOf<Long, Long>()

            // Assign IDs to primaries first
            for (idx in primaryIndices) {
                val assignedId = nextId++
                indexToNewId[idx] = assignedId
                rawItems[idx].originalId?.let { originalIdToNewId[it] = assignedId }
            }

            // Assign IDs to secondaries
            for (idx in secondaryIndices) {
                val assignedId = nextId++
                indexToNewId[idx] = assignedId
                rawItems[idx].originalId?.let { originalIdToNewId[it] = assignedId }
            }

            // Build final list of Shortcuts with resolved parentId
            for (idx in rawItems.indices) {
                val item = rawItems[idx]
                val assignedId = indexToNewId[idx] ?: nextId++

                val resolvedParentId = when {
                    item.originalParentId != null && originalIdToNewId.containsKey(item.originalParentId) -> {
                        originalIdToNewId[item.originalParentId]
                    }
                    item.parentIndex != null && indexToNewId.containsKey(item.parentIndex) -> {
                        indexToNewId[item.parentIndex]
                    }
                    else -> null
                }

                shortcuts.add(
                    Shortcut(
                        id = assignedId,
                        name = item.name,
                        treeUri = item.treeUri,
                        icon = item.icon,
                        sortOrder = item.sortOrder,
                        parentId = resolvedParentId,
                        isPinned = item.isPinned,
                        isFavorite = item.isFavorite,
                        isHidden = item.isHidden,
                        sortMode = item.sortMode
                    )
                )
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
        return shortcuts
    }
}
