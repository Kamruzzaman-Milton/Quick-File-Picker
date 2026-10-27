package com.quickfilepicker

import com.quickfilepicker.data.model.Shortcut
import org.junit.Assert.assertEquals
import org.junit.Test

class QuickFilePickerUnitTest {

    @Test
    fun shortcutCreation_isCorrect() {
        val shortcut = Shortcut(
            id = 1,
            name = "FCPS",
            treeUri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FFCPS",
            icon = "folder",
            sortOrder = 0
        )
        assertEquals("FCPS", shortcut.name)
        assertEquals(1L, shortcut.id)
        assertEquals("folder", shortcut.icon)
    }
}
