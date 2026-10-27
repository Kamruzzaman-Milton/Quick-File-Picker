package com.quickfilepicker.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recent_folders")
data class RecentFolder(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val path: String,
    val treeUri: String,
    val lastAccessed: Long = System.currentTimeMillis()
)
