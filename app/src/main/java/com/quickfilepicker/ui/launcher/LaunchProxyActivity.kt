package com.quickfilepicker.ui.launcher

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast
import com.quickfilepicker.QuickFilePickerApplication

class LaunchProxyActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        var shortcutId = intent.getLongExtra("shortcut_id", -1L)
        val uri = intent.data

        if (shortcutId == -1L && uri != null) {
            val docId = try {
                DocumentsContract.getDocumentId(uri)
            } catch (e: Exception) {
                uri.lastPathSegment
            } ?: ""

            if (docId.startsWith("appaction:")) {
                shortcutId = docId.substringAfter("appaction:").toLongOrNull() ?: -1L
            } else if (docId.startsWith("approot:")) {
                shortcutId = docId.substringAfter("approot:").toLongOrNull() ?: -1L
            }
        }

        if (shortcutId != -1L) {
            val repo = QuickFilePickerApplication.getRepository()
            val shortcut = repo.getShortcutByIdSync(shortcutId)
            val packageName = shortcut?.treeUri?.substringAfter("app:") ?: ""
            if (packageName.isNotEmpty()) {
                val launched = launchApp(packageName)
                if (!launched) {
                    Toast.makeText(this, "Could not open ${shortcut?.name ?: packageName}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        finish()
    }

    private fun launchApp(packageName: String): Boolean {
        return try {
            val pm = packageManager
            val getContentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val hasGetContent = pm.queryIntentActivities(getContentIntent, 0).isNotEmpty()

            val launchIntent = if (hasGetContent) {
                getContentIntent
            } else {
                pm.getLaunchIntentForPackage(packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            } ?: Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            startActivity(launchIntent)
            true
        } catch (e: Exception) {
            Log.e("LaunchProxyActivity", "Error launching app: $packageName", e)
            false
        }
    }
}
