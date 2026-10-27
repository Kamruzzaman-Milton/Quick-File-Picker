package com.quickfilepicker.data.pref

import android.content.Context
import android.content.SharedPreferences

class QuickFilePickerPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("quickfilepicker_settings", Context.MODE_PRIVATE).also { newPrefs ->
            if (!newPrefs.contains(KEY_ENABLE_AUTO_FOLDERS)) {
                val oldPrefs = context.getSharedPreferences("quickfilepicker_settings", Context.MODE_PRIVATE)
                if (oldPrefs.contains(KEY_ENABLE_AUTO_FOLDERS)) {
                    val editor = newPrefs.edit()
                    for ((k, v) in oldPrefs.all) {
                        when (v) {
                            is Boolean -> editor.putBoolean(k, v)
                            is Int -> editor.putInt(k, v)
                            is String -> editor.putString(k, v)
                            is Long -> editor.putLong(k, v)
                            is Float -> editor.putFloat(k, v)
                        }
                    }
                    editor.apply()
                }
            }
        }

    companion object {
        const val KEY_ENABLE_AUTO_FOLDERS = "enable_auto_folders"
        const val KEY_ENABLE_SMART_COLLECTIONS = "enable_smart_collections"
        const val KEY_ENABLE_SEARCH = "enable_search"
        const val KEY_ENABLE_RECENT_FILES = "enable_recent_files"
        const val KEY_ENABLE_RECENT_FOLDERS = "enable_recent_folders"
        const val KEY_MAX_RECENT_FILES = "max_recent_files"
        const val KEY_SORT_MODE = "sort_mode"
        const val KEY_DARK_THEME_MODE = "dark_theme_mode"

        const val SORT_NAME_ASC = "NAME_ASC"
        const val SORT_NAME_DESC = "NAME_DESC"
        const val SORT_DATE_DESC = "DATE_DESC"
        const val SORT_DATE_ASC = "DATE_ASC"
        const val SORT_SIZE_DESC = "SIZE_DESC"
        const val SORT_SIZE_ASC = "SIZE_ASC"
        const val SORT_EXTENSION = "EXTENSION"
        const val SORT_PREFIX_NUMBER = "PREFIX_NUMBER"
        const val SORT_PREFIX_DATE = "PREFIX_DATE"

        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        const val THEME_AMOLED = "amoled"
    }

    var enableAutoFolders: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_AUTO_FOLDERS, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_AUTO_FOLDERS, value).apply()

    var enableSmartCollections: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_SMART_COLLECTIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_SMART_COLLECTIONS, value).apply()

    var enableSearch: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_SEARCH, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_SEARCH, value).apply()

    var enableRecentFiles: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_RECENT_FILES, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_RECENT_FILES, value).apply()

    var enableRecentFolders: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_RECENT_FOLDERS, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_RECENT_FOLDERS, value).apply()

    var maxRecentFiles: Int
        get() = prefs.getInt(KEY_MAX_RECENT_FILES, 200)
        set(value) = prefs.edit().putInt(KEY_MAX_RECENT_FILES, value).apply()

    var sortMode: String
        get() = prefs.getString(KEY_SORT_MODE, SORT_DATE_DESC) ?: SORT_DATE_DESC
        set(value) = prefs.edit().putString(KEY_SORT_MODE, value).apply()

    var darkThemeMode: String
        get() = prefs.getString(KEY_DARK_THEME_MODE, THEME_SYSTEM) ?: THEME_SYSTEM
        set(value) = prefs.edit().putString(KEY_DARK_THEME_MODE, value).apply()
}
