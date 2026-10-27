package com.quickfilepicker.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorite_items")
data class FavoriteItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val uriString: String,
    val isDirectory: Boolean = false,
    val mimeType: String = "*/*",
    val addedTime: Long = System.currentTimeMillis()
)
