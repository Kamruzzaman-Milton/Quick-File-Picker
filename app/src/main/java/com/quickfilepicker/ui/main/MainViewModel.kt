package com.quickfilepicker.ui.main

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.quickfilepicker.QuickFilePickerApplication
import com.quickfilepicker.data.backup.BackupManager
import com.quickfilepicker.data.model.Shortcut
import com.quickfilepicker.data.model.ShortcutWithChildren
import com.quickfilepicker.data.pref.QuickFilePickerPrefs
import com.quickfilepicker.data.repository.ShortcutRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: ShortcutRepository = QuickFilePickerApplication.getRepository()
    private val backupManager = BackupManager(application)
    val prefs = QuickFilePickerPrefs(application)

    val shortcutsWithChildrenLiveData: LiveData<List<ShortcutWithChildren>> =
        repository.shortcutsWithChildrenFlow.asLiveData()

    fun addShortcut(name: String, treeUri: String, icon: String, parentId: Long? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (parentId != null) {
                    repository.addSecondaryShortcut(parentId, name, treeUri, icon)
                } else {
                    val currentCount = repository.getVisiblePrimaryShortcutsSync().size
                    val shortcut = Shortcut(
                        name = name,
                        treeUri = treeUri,
                        icon = icon,
                        sortOrder = currentCount,
                        parentId = null
                    )
                    repository.insert(shortcut)
                }
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Error adding shortcut", e)
            }
        }
    }

    fun addSecondaryShortcut(parentId: Long, name: String, treeUri: String, icon: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.addSecondaryShortcut(parentId, name, treeUri, icon)
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Error adding secondary shortcut", e)
            }
        }
    }

    fun moveToParent(shortcut: Shortcut, targetParentId: Long?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.moveToParent(shortcut, targetParentId)
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Error moving shortcut", e)
            }
        }
    }

    fun updateShortcutName(shortcut: Shortcut, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val updated = shortcut.copy(name = newName)
                repository.update(updated)
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Error updating shortcut name", e)
            }
        }
    }

    fun updateShortcutIcon(shortcut: Shortcut, newIcon: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val updated = shortcut.copy(icon = newIcon)
                repository.update(updated)
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Error updating shortcut icon", e)
            }
        }
    }

    fun togglePin(shortcut: Shortcut) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.togglePin(shortcut)
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Error toggling pin", e)
            }
        }
    }

    fun toggleHide(shortcut: Shortcut) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.toggleHide(shortcut)
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Error toggling hide", e)
            }
        }
    }

    fun toggleFavorite(shortcut: Shortcut) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.toggleFavorite(shortcut)
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Error toggling favorite", e)
            }
        }
    }

    fun deleteShortcut(shortcut: Shortcut) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.delete(shortcut)
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Error deleting shortcut", e)
            }
        }
    }

    fun updateShortcutSortMode(shortcut: Shortcut, sortMode: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.update(shortcut.copy(sortMode = sortMode))
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Error updating shortcut sort mode", e)
            }
        }
    }

    fun updatePrimaryOrder(updatedList: List<ShortcutWithChildren>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val shortcuts = updatedList.mapIndexed { index, item ->
                    item.primary.copy(sortOrder = index)
                }
                repository.updateOrder(shortcuts)
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Error updating order", e)
            }
        }
    }

    fun getAllPrimaryShortcutsSync(): List<Shortcut> {
        return repository.getAllPrimaryShortcutsSync()
    }

    fun notifyRootsChanged() {
        repository.notifyRootsChanged()
    }

    suspend fun exportBackup(destinationUri: Uri): Boolean = withContext(Dispatchers.IO) {
        val shortcuts = repository.getAllShortcuts()
        backupManager.exportBackup(shortcuts, destinationUri)
    }

    suspend fun importBackup(sourceUri: Uri): Int = withContext(Dispatchers.IO) {
        val shortcuts = backupManager.importBackup(sourceUri)
        if (shortcuts.isNotEmpty()) {
            repository.restoreAll(shortcuts)
        }
        shortcuts.size
    }
}
