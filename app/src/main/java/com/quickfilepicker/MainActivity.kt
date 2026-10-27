package com.quickfilepicker

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.quickfilepicker.data.model.Shortcut
import com.quickfilepicker.data.model.ShortcutWithChildren
import com.quickfilepicker.databinding.ActivityMainBinding
import com.quickfilepicker.databinding.DialogAddShortcutBinding
import com.quickfilepicker.ui.main.IconAdapter
import com.quickfilepicker.ui.main.MainViewModel
import com.quickfilepicker.ui.main.ShortcutAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel

    private lateinit var adapter: ShortcutAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper

    private var pendingParentShortcutId: Long? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val treeUri = result.data?.data
            if (treeUri != null) {
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                try {
                    contentResolver.takePersistableUriPermission(treeUri, takeFlags)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                val defaultName = parseFolderNameFromUri(treeUri)
                showAddShortcutDialog(treeUri.toString(), defaultName, pendingParentShortcutId)
            } else {
                Toast.makeText(this, R.string.invalid_folder_uri, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val exportBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                val success = viewModel.exportBackup(uri)
                if (success) {
                    Toast.makeText(this@MainActivity, R.string.backup_success, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, R.string.backup_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val importBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                val count = viewModel.importBackup(uri)
                if (count > 0) {
                    Toast.makeText(this@MainActivity, getString(R.string.restore_success, count), Toast.LENGTH_SHORT).show()
                    viewModel.notifyRootsChanged()
                    recreate()
                } else {
                    Toast.makeText(this@MainActivity, R.string.restore_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        val prefs = viewModel.prefs
        when (prefs.darkThemeMode) {
            com.quickfilepicker.data.pref.QuickFilePickerPrefs.THEME_LIGHT -> androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)
            com.quickfilepicker.data.pref.QuickFilePickerPrefs.THEME_DARK -> androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
            com.quickfilepicker.data.pref.QuickFilePickerPrefs.THEME_AMOLED -> androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
            else -> androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
        if (prefs.darkThemeMode == com.quickfilepicker.data.pref.QuickFilePickerPrefs.THEME_AMOLED) {
            setTheme(R.style.Theme_QuickFilePicker_Amoled)
        } else {
            setTheme(R.style.Theme_QuickFilePicker)
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        val isNightMode = when (prefs.darkThemeMode) {
            com.quickfilepicker.data.pref.QuickFilePickerPrefs.THEME_LIGHT -> false
            com.quickfilepicker.data.pref.QuickFilePickerPrefs.THEME_DARK, com.quickfilepicker.data.pref.QuickFilePickerPrefs.THEME_AMOLED -> true
            else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        insetsController.isAppearanceLightStatusBars = !isNightMode
        insetsController.isAppearanceLightNavigationBars = !isNightMode

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        ViewCompat.setOnApplyWindowInsetsListener(binding.appBarLayout) { v, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.setPadding(0, statusBarInsets.top, 0, 0)
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            val fabParams = binding.fabAddShortcut.layoutParams as android.view.ViewGroup.MarginLayoutParams
            val baseMargin = (20 * resources.displayMetrics.density).toInt()
            fabParams.bottomMargin = baseMargin + navBarInsets.bottom
            binding.fabAddShortcut.layoutParams = fabParams

            binding.rvShortcuts.setPadding(
                binding.rvShortcuts.paddingLeft,
                binding.rvShortcuts.paddingTop,
                binding.rvShortcuts.paddingRight,
                (96 * resources.displayMetrics.density).toInt() + navBarInsets.bottom
            )

            insets
        }

        setupRecyclerView()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        binding.fabAddShortcut.setOnClickListener {
            pendingParentShortcutId = null
            showAddShortcutChoiceDialog()
        }

        binding.btnEmptyAdd.setOnClickListener {
            pendingParentShortcutId = null
            showAddShortcutChoiceDialog()
        }

        binding.btnGrantOverlay.setOnClickListener {
            openOverlayOrMiuiPermissionSettings()
        }

        viewModel.shortcutsWithChildrenLiveData.observe(this) { shortcutsWithChildren ->
            adapter.submitList(shortcutsWithChildren) {
                updatePermissionCardVisibility()
            }
            if (shortcutsWithChildren.isNullOrEmpty()) {
                binding.layoutEmpty.visibility = View.VISIBLE
                binding.rvShortcuts.visibility = View.GONE
                binding.tvSubtitle.visibility = View.GONE
            } else {
                binding.layoutEmpty.visibility = View.GONE
                binding.rvShortcuts.visibility = View.VISIBLE
                binding.tvSubtitle.visibility = View.VISIBLE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionCardVisibility()
    }

    private fun updatePermissionCardVisibility() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val hasOverlay = android.provider.Settings.canDrawOverlays(this)
            val hasAppShortcuts = adapter.itemsList.any { it.primary.treeUri.startsWith("app:") }
            binding.cardPermissionNotice.visibility = if (!hasOverlay && hasAppShortcuts) View.VISIBLE else View.GONE
        } else {
            binding.cardPermissionNotice.visibility = View.GONE
        }
    }

    private fun openOverlayOrMiuiPermissionSettings() {
        var intentOpened = false
        try {
            val miuiIntent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                putExtra("extra_pkgname", packageName)
            }
            startActivity(miuiIntent)
            intentOpened = true
        } catch (e: Exception) {
            // Ignore
        }

        if (!intentOpened) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    val intent = Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                    intentOpened = true
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        if (!intentOpened) {
            try {
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = ShortcutAdapter(
            onStartDrag = { viewHolder ->
                itemTouchHelper.startDrag(viewHolder)
            },
            onRename = { shortcut ->
                showEditShortcutNameDialog(shortcut)
            },
            onChangeIcon = { shortcut ->
                showChangeIconDialog(shortcut)
            },
            onTogglePin = { shortcut ->
                viewModel.togglePin(shortcut)
            },
            onToggleFavorite = { shortcut ->
                viewModel.toggleFavorite(shortcut)
            },
            onToggleHide = { shortcut ->
                viewModel.toggleHide(shortcut)
            },
            onDelete = { shortcut ->
                showDeleteConfirmDialog(shortcut)
            },
            onDetails = { shortcut ->
                showDetailsDialog(shortcut)
            },
            onSortOrder = { shortcut ->
                showSortOrderDialog(shortcut)
            },
            onAddSecondary = { primaryShortcut ->
                pendingParentShortcutId = primaryShortcut.id
                launchFolderPicker()
            },
            onMoveToPrimary = { shortcut ->
                showMoveToPrimaryDialog(shortcut)
            },
            onPromoteToPrimary = { secondaryShortcut ->
                viewModel.moveToParent(secondaryShortcut, null)
                Toast.makeText(this, R.string.promote_to_primary, Toast.LENGTH_SHORT).show()
            },
            onItemMoved = { updatedList ->
                viewModel.updatePrimaryOrder(updatedList)
            }
        )

        binding.rvShortcuts.layoutManager = LinearLayoutManager(this)
        binding.rvShortcuts.adapter = adapter

        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                adapter.onItemMove(fromPos, toPos)
                return true
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ) {
                super.clearView(recyclerView, viewHolder)
                val currentItems = adapter.itemsList.toList()
                viewModel.updatePrimaryOrder(currentItems)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        }

        itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper.attachToRecyclerView(binding.rvShortcuts)
    }

    private fun launchFolderPicker() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            }
            folderPickerLauncher.launch(intent)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to launch folder picker", e)
            Toast.makeText(this, "Could not open folder picker: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showAddShortcutDialog(
        treeUriString: String,
        defaultName: String,
        targetParentId: Long?
    ) {
        try {
            val dialogBinding = DialogAddShortcutBinding.inflate(LayoutInflater.from(this))
            dialogBinding.etShortcutName.setText(defaultName)

            var selectedIcon = "folder"
            val iconAdapter = IconAdapter(selectedIcon) { iconKey ->
                selectedIcon = iconKey
            }
            dialogBinding.rvIcons.adapter = iconAdapter

            // Populate parent placement options
            lifecycleScope.launch {
                val primaryShortcuts = withContext(Dispatchers.IO) {
                    try {
                        viewModel.getAllPrimaryShortcutsSync()
                    } catch (e: Exception) {
                        emptyList()
                    }
                }

                val parentOptions = mutableListOf<Pair<String, Long?>>()
                parentOptions.add(getString(R.string.shortcut_type_primary_option) to null)

                for (p in primaryShortcuts) {
                    parentOptions.add("↳ Inside: ${p.name}" to p.id)
                }

                val spinnerAdapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_item,
                    parentOptions.map { it.first }
                )
                spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                dialogBinding.spinnerParentShortcut.adapter = spinnerAdapter

                // If user clicked "+ Add Sub-shortcut" on a specific primary folder, select it
                if (targetParentId != null) {
                    val matchIndex = parentOptions.indexOfFirst { it.second == targetParentId }
                    if (matchIndex >= 0) {
                        dialogBinding.spinnerParentShortcut.setSelection(matchIndex)
                    }
                }

                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(if (targetParentId != null) R.string.add_secondary_shortcut else R.string.add_shortcut_dialog_title)
                    .setView(dialogBinding.root)
                    .setPositiveButton(R.string.save) { _, _ ->
                        val nameInput = dialogBinding.etShortcutName.text.toString().trim()
                        val finalName = if (nameInput.isNotEmpty()) nameInput else defaultName
                        val selectedPos = dialogBinding.spinnerParentShortcut.selectedItemPosition
                        val chosenParentId = if (selectedPos in parentOptions.indices) {
                            parentOptions[selectedPos].second
                        } else {
                            targetParentId
                        }

                        viewModel.addShortcut(finalName, treeUriString, selectedIcon, chosenParentId)
                        Toast.makeText(this@MainActivity, R.string.permission_granted, Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error creating add shortcut dialog", e)
            Toast.makeText(this, "Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showMoveToPrimaryDialog(shortcut: Shortcut) {
        lifecycleScope.launch {
            val primaries = withContext(Dispatchers.IO) {
                viewModel.getAllPrimaryShortcutsSync().filter { it.id != shortcut.id }
            }

            if (primaries.isEmpty()) {
                Toast.makeText(this@MainActivity, "No other primary folders available.", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val names = primaries.map { "📁 ${it.name}" }.toTypedArray()
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.select_primary_parent)
                .setItems(names) { _, which ->
                    val chosen = primaries[which]
                    viewModel.moveToParent(shortcut, chosen.id)
                    Toast.makeText(this@MainActivity, "Moved under ${chosen.name}.", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun showEditShortcutNameDialog(shortcut: Shortcut) {
        val dialogBinding = DialogAddShortcutBinding.inflate(LayoutInflater.from(this))
        dialogBinding.etShortcutName.setText(shortcut.name)
        dialogBinding.rvIcons.visibility = View.GONE
        dialogBinding.layoutParentSelection.visibility = View.GONE

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.edit_shortcut_dialog_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val newName = dialogBinding.etShortcutName.text.toString().trim()
                if (newName.isNotEmpty()) {
                    viewModel.updateShortcutName(shortcut, newName)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showChangeIconDialog(shortcut: Shortcut) {
        val dialogBinding = DialogAddShortcutBinding.inflate(LayoutInflater.from(this))
        dialogBinding.tilShortcutName.visibility = View.GONE
        dialogBinding.layoutParentSelection.visibility = View.GONE

        var selectedIcon = shortcut.icon
        val iconAdapter = IconAdapter(selectedIcon) { iconKey ->
            selectedIcon = iconKey
        }
        dialogBinding.rvIcons.adapter = iconAdapter

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.change_icon)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                viewModel.updateShortcutIcon(shortcut, selectedIcon)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDeleteConfirmDialog(shortcut: Shortcut) {
        val isSub = shortcut.parentId != null
        val titleRes = if (isSub) R.string.delete_sub_confirm_title else R.string.delete_confirm_title
        val msg = if (isSub) {
            getString(R.string.delete_sub_confirm_msg, shortcut.name)
        } else {
            getString(R.string.delete_confirm_msg, shortcut.name)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setMessage(msg)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteShortcut(shortcut)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDetailsDialog(shortcut: Shortcut) {
        val formattedPath = parseDisplayPath(shortcut.treeUri)
        val type = if (shortcut.parentId != null) "Secondary Shortcut (Sub-folder)" else "Primary Shortcut (Drawer Root)"
        val sortInfo = when (shortcut.sortMode) {
            com.quickfilepicker.data.pref.QuickFilePickerPrefs.SORT_DATE_DESC -> "Date Modified (Newest First)"
            com.quickfilepicker.data.pref.QuickFilePickerPrefs.SORT_DATE_ASC -> "Date Modified (Oldest First)"
            com.quickfilepicker.data.pref.QuickFilePickerPrefs.SORT_NAME_ASC -> "Name (A to Z)"
            com.quickfilepicker.data.pref.QuickFilePickerPrefs.SORT_NAME_DESC -> "Name (Z to A)"
            com.quickfilepicker.data.pref.QuickFilePickerPrefs.SORT_SIZE_DESC -> "Size (Largest First)"
            com.quickfilepicker.data.pref.QuickFilePickerPrefs.SORT_SIZE_ASC -> "Size (Smallest First)"
            com.quickfilepicker.data.pref.QuickFilePickerPrefs.SORT_EXTENSION -> "File Extension / Type"
            else -> "Default (Global Setting)"
        }
        val msg = "Shortcut Name: ${shortcut.name}\n\nType: $type\n\nSort Mode: $sortInfo\n\nPath: $formattedPath\n\nURI:\n${shortcut.treeUri}"

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.details)
            .setMessage(msg)
            .setPositiveButton(R.string.cancel, null)
            .show()
    }

    private fun showSortOrderDialog(shortcut: Shortcut) {
        val sortOptions = listOf(
            getString(R.string.sort_inherit_global) to null,
            "⚡ Force Newest First (01, 02... prefix)" to com.quickfilepicker.data.pref.QuickFilePickerPrefs.SORT_PREFIX_NUMBER,
            "⚡ Force Newest First ([Date] prefix)" to com.quickfilepicker.data.pref.QuickFilePickerPrefs.SORT_PREFIX_DATE,
            "Date Modified (Newest First)" to com.quickfilepicker.data.pref.QuickFilePickerPrefs.SORT_DATE_DESC,
            "Date Modified (Oldest First)" to com.quickfilepicker.data.pref.QuickFilePickerPrefs.SORT_DATE_ASC,
            "Name (A to Z)" to com.quickfilepicker.data.pref.QuickFilePickerPrefs.SORT_NAME_ASC,
            "Name (Z to A)" to com.quickfilepicker.data.pref.QuickFilePickerPrefs.SORT_NAME_DESC,
            "Size (Largest First)" to com.quickfilepicker.data.pref.QuickFilePickerPrefs.SORT_SIZE_DESC,
            "Size (Smallest First)" to com.quickfilepicker.data.pref.QuickFilePickerPrefs.SORT_SIZE_ASC,
            "File Extension / Type" to com.quickfilepicker.data.pref.QuickFilePickerPrefs.SORT_EXTENSION
        )

        val currentSelectionIndex = sortOptions.indexOfFirst { it.second == shortcut.sortMode }.coerceAtLeast(0)
        var selectedIndex = currentSelectionIndex

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.shortcut_sort_order_title, shortcut.name))
            .setSingleChoiceItems(sortOptions.map { it.first }.toTypedArray(), currentSelectionIndex) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton(R.string.save) { _, _ ->
                val chosenMode = sortOptions[selectedIndex].second
                viewModel.updateShortcutSortMode(shortcut, chosenMode)
                Toast.makeText(this, "Sort order saved for ${shortcut.name}.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAboutDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about)
            .setMessage(R.string.about_message)
            .setIcon(R.drawable.ic_quickfilepicker_logo)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 101, 0, R.string.backup_shortcuts).setIcon(R.drawable.ic_backup)
        menu.add(0, 102, 1, R.string.restore_shortcuts).setIcon(R.drawable.ic_restore)
        menu.add(0, 104, 2, R.string.settings).setIcon(R.drawable.ic_settings)
        menu.add(0, 103, 3, R.string.about)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            101 -> {
                exportBackupLauncher.launch("quickfilepicker_backup.json")
                true
            }
            102 -> {
                importBackupLauncher.launch(arrayOf("application/json", "*/*"))
                true
            }
            104 -> {
                showSettingsDialog()
                true
            }
            103 -> {
                showAboutDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showSettingsDialog() {
        val settingsBinding = com.quickfilepicker.databinding.DialogSettingsBinding.inflate(LayoutInflater.from(this))
        val prefs = viewModel.prefs

        settingsBinding.switchAutoFolders.isChecked = prefs.enableAutoFolders
        settingsBinding.switchSmartCollections.isChecked = prefs.enableSmartCollections
        settingsBinding.switchRecentFiles.isChecked = prefs.enableRecentFiles
        settingsBinding.switchRecentFolders.isChecked = prefs.enableRecentFolders
        settingsBinding.etMaxRecents.setText(prefs.maxRecentFiles.toString())

        val themeOptions = listOf(
            "System Default" to com.quickfilepicker.data.pref.QuickFilePickerPrefs.THEME_SYSTEM,
            "Light" to com.quickfilepicker.data.pref.QuickFilePickerPrefs.THEME_LIGHT,
            "Dark" to com.quickfilepicker.data.pref.QuickFilePickerPrefs.THEME_DARK,
            "AMOLED Dark (Pure Black)" to com.quickfilepicker.data.pref.QuickFilePickerPrefs.THEME_AMOLED
        )

        val themeAdapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            themeOptions.map { it.first }
        )
        themeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        settingsBinding.spinnerThemeMode.adapter = themeAdapter

        val currentThemeIndex = themeOptions.indexOfFirst { it.second == prefs.darkThemeMode }.coerceAtLeast(0)
        settingsBinding.spinnerThemeMode.setSelection(currentThemeIndex)

        val sortOptions = listOf(
            "⚡ Force Newest First (01, 02... prefix)" to com.quickfilepicker.data.pref.QuickFilePickerPrefs.SORT_PREFIX_NUMBER,
            "⚡ Force Newest First ([Date] prefix)" to com.quickfilepicker.data.pref.QuickFilePickerPrefs.SORT_PREFIX_DATE,
            "Date Modified (Newest First)" to com.quickfilepicker.data.pref.QuickFilePickerPrefs.SORT_DATE_DESC,
            "Date Modified (Oldest First)" to com.quickfilepicker.data.pref.QuickFilePickerPrefs.SORT_DATE_ASC,
            "Name (A to Z)" to com.quickfilepicker.data.pref.QuickFilePickerPrefs.SORT_NAME_ASC,
            "Name (Z to A)" to com.quickfilepicker.data.pref.QuickFilePickerPrefs.SORT_NAME_DESC,
            "Size (Largest First)" to com.quickfilepicker.data.pref.QuickFilePickerPrefs.SORT_SIZE_DESC,
            "Size (Smallest First)" to com.quickfilepicker.data.pref.QuickFilePickerPrefs.SORT_SIZE_ASC,
            "File Extension / Type" to com.quickfilepicker.data.pref.QuickFilePickerPrefs.SORT_EXTENSION
        )

        val adapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            sortOptions.map { it.first }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        settingsBinding.spinnerSortMode.adapter = adapter

        val currentSortIndex = sortOptions.indexOfFirst { it.second == prefs.sortMode }.coerceAtLeast(0)
        settingsBinding.spinnerSortMode.setSelection(currentSortIndex)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings)
            .setView(settingsBinding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                prefs.enableAutoFolders = settingsBinding.switchAutoFolders.isChecked
                prefs.enableSmartCollections = settingsBinding.switchSmartCollections.isChecked
                prefs.enableRecentFiles = settingsBinding.switchRecentFiles.isChecked
                prefs.enableRecentFolders = settingsBinding.switchRecentFolders.isChecked

                val limit = settingsBinding.etMaxRecents.text.toString().toIntOrNull() ?: 200
                prefs.maxRecentFiles = limit

                val selectedThemePos = settingsBinding.spinnerThemeMode.selectedItemPosition
                if (selectedThemePos in themeOptions.indices) {
                    prefs.darkThemeMode = themeOptions[selectedThemePos].second
                }

                when (prefs.darkThemeMode) {
                    com.quickfilepicker.data.pref.QuickFilePickerPrefs.THEME_LIGHT -> androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)
                    com.quickfilepicker.data.pref.QuickFilePickerPrefs.THEME_DARK -> androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
                    com.quickfilepicker.data.pref.QuickFilePickerPrefs.THEME_AMOLED -> androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
                    else -> androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                }

                val selectedPosition = settingsBinding.spinnerSortMode.selectedItemPosition
                if (selectedPosition in sortOptions.indices) {
                    prefs.sortMode = sortOptions[selectedPosition].second
                }

                viewModel.notifyRootsChanged()
                Toast.makeText(this, "Settings saved.", Toast.LENGTH_SHORT).show()
                recreate()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun parseFolderNameFromUri(uri: Uri): String {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri) ?: return "Folder"
            val parts = docId.split(":")
            if (parts.size >= 2 && parts[1].isNotEmpty()) {
                val subParts = parts[1].split("/")
                subParts.lastOrNull { it.isNotEmpty() } ?: "Folder"
            } else {
                parts.getOrNull(0) ?: "Folder"
            }
        } catch (e: Exception) {
            "Folder"
        }
    }

    private fun showAddShortcutChoiceDialog() {
        val options = arrayOf("📁 Folder Shortcut (Storage Access)", "📱 3rd-Party App Shortcut (MiXplorer, Solid Explorer, etc.)")
        MaterialAlertDialogBuilder(this)
            .setTitle("Add Shortcut")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        pendingParentShortcutId = null
                        launchFolderPicker()
                    }
                    1 -> showAddAppShortcutDialog()
                }
            }
            .show()
    }

    private fun showAddAppShortcutDialog() {
        val pm = packageManager
        val installedApps = try {
            pm.getInstalledApplications(PackageManager.MATCH_ALL)
        } catch (e: Exception) {
            emptyList()
        }

        val popularFileManagers = listOf(
            Pair("MiXplorer", "com.mixplorer"),
            Pair("MiXplorer Silver", "com.mixplorer.silver"),
            Pair("Solid Explorer", "pl.solidexplorer2"),
            Pair("Cx File Explorer", "com.cxinventor.file.explorer"),
            Pair("Files by Google", "com.google.android.apps.nbu.files"),
            Pair("ZArchiver", "ru.zdevs.zarchiver"),
            Pair("Total Commander", "com.ghisler.android.TotalCommander"),
            Pair("X-plore File Manager", "com.lonelycatgames.Xplore"),
            Pair("FX File Explorer", "nextapp.fx"),
            Pair("Material Files", "me.zhanghai.android.files")
        )

        val appChoices = mutableListOf<Triple<String, String, Drawable?>>()
        for (fm in popularFileManagers) {
            try {
                val appInfo = pm.getApplicationInfo(fm.second, 0)
                val label = pm.getApplicationLabel(appInfo).toString()
                val icon = pm.getApplicationIcon(fm.second)
                appChoices.add(Triple(label, fm.second, icon))
            } catch (e: Exception) {
                appChoices.add(Triple(fm.first, fm.second, null))
            }
        }

        for (app in installedApps) {
            if ((app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 || popularFileManagers.any { it.second == app.packageName }) {
                val label = pm.getApplicationLabel(app).toString()
                val pkg = app.packageName
                if (appChoices.none { it.second == pkg }) {
                    try {
                        val icon = pm.getApplicationIcon(pkg)
                        appChoices.add(Triple(label, pkg, icon))
                    } catch (e: Exception) {
                        appChoices.add(Triple(label, pkg, null))
                    }
                }
            }
        }

        // Add custom package option at the end
        appChoices.add(Triple("✏️ Enter Package Name Manually...", "CUSTOM_MANUAL", null))

        val displayNames = appChoices.map { 
            if (it.second == "CUSTOM_MANUAL") it.first else "${it.first} (${it.second})" 
        }.toTypedArray()
        var selectedIndex = 0

        MaterialAlertDialogBuilder(this)
            .setTitle("📱 Add 3rd-Party App Shortcut")
            .setSingleChoiceItems(displayNames, 0) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton("Next / Add") { _, _ ->
                if (appChoices.isNotEmpty()) {
                    val chosen = appChoices[selectedIndex]
                    if (chosen.second == "CUSTOM_MANUAL") {
                        showManualPackageInputDialog()
                    } else {
                        viewModel.addShortcut(
                            name = chosen.first,
                            treeUri = "app:${chosen.second}",
                            icon = "app",
                            parentId = null
                        )
                        Toast.makeText(this, "Added app shortcut: ${chosen.first}", Toast.LENGTH_SHORT).show()
                        checkOverlayPermissionForAppShortcuts()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showManualPackageInputDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "e.g. com.mixplorer or pl.solidexplorer2"
            setPadding(50, 40, 50, 40)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Enter App Package Name")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val pkg = input.text.toString().trim()
                if (pkg.isNotEmpty()) {
                    val appName = try {
                        val appInfo = packageManager.getApplicationInfo(pkg, 0)
                        packageManager.getApplicationLabel(appInfo).toString()
                    } catch (e: Exception) {
                        pkg.substringAfterLast('.')
                    }
                    viewModel.addShortcut(
                        name = appName,
                        treeUri = "app:$pkg",
                        icon = "app",
                        parentId = null
                    )
                    Toast.makeText(this, "Added app shortcut: $appName", Toast.LENGTH_SHORT).show()
                    checkOverlayPermissionForAppShortcuts()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkOverlayPermissionForAppShortcuts() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("⚡ Instant App Launch Permission")
                    .setMessage("To allow Quick File Picker to open third-party file managers (MiXplorer, Solid Explorer, Cx File Explorer, etc.) directly when you tap their shortcut in the system file picker, please grant the 'Display over other apps' permission.")
                    .setPositiveButton("Grant Permission") { _, _ ->
                        openOverlayOrMiuiPermissionSettings()
                    }
                    .setNegativeButton("Later", null)
                    .show()
            }
        }
    }

    private fun parseDisplayPath(treeUriString: String): String {
        return try {
            val uri = Uri.parse(treeUriString)
            val docId = DocumentsContract.getTreeDocumentId(uri) ?: return treeUriString
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
}
