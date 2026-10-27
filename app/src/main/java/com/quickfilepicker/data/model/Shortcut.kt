package com.quickfilepicker.data.model

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "shortcuts")
data class Shortcut(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val treeUri: String,
    val icon: String = "folder",
    val sortOrder: Int = 0,
    val createdTime: Long = System.currentTimeMillis(),
    val isPinned: Boolean = false,
    val isHidden: Boolean = false,
    val isFavorite: Boolean = false,
    val colorTag: String = "#0061A4",
    val parentId: Long? = null,
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
