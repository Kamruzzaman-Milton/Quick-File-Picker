package com.quickfilepicker.ui.main

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.quickfilepicker.R
import com.quickfilepicker.data.model.Shortcut
import com.quickfilepicker.data.model.ShortcutWithChildren
import com.quickfilepicker.databinding.ItemSecondaryShortcutBinding
import com.quickfilepicker.databinding.ItemShortcutBinding
import java.util.Collections

class ShortcutAdapter(
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit,
    private val onRename: (Shortcut) -> Unit,
    private val onChangeIcon: (Shortcut) -> Unit,
    private val onTogglePin: (Shortcut) -> Unit,
    private val onToggleFavorite: (Shortcut) -> Unit,
    private val onToggleHide: (Shortcut) -> Unit,
    private val onDelete: (Shortcut) -> Unit,
    private val onDetails: (Shortcut) -> Unit,
    private val onSortOrder: (Shortcut) -> Unit,
    private val onAddSecondary: (Shortcut) -> Unit,
    private val onMoveToPrimary: (Shortcut) -> Unit,
    private val onPromoteToPrimary: (Shortcut) -> Unit,
    private val onItemMoved: (List<ShortcutWithChildren>) -> Unit
) : ListAdapter<ShortcutWithChildren, ShortcutAdapter.ShortcutViewHolder>(ShortcutDiffCallback()) {

    val itemsList = mutableListOf<ShortcutWithChildren>()
    private val expandedItemIds = mutableSetOf<Long>()

    override fun submitList(list: List<ShortcutWithChildren>?) {
        itemsList.clear()
        if (list != null) {
            itemsList.addAll(list)
        }
        super.submitList(list)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ShortcutViewHolder {
        val binding = ItemShortcutBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ShortcutViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ShortcutViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    fun onItemMove(fromPosition: Int, toPosition: Int) {
        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                Collections.swap(itemsList, i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                Collections.swap(itemsList, i, i - 1)
            }
        }
        notifyItemMoved(fromPosition, toPosition)
    }

    inner class ShortcutViewHolder(private val binding: ItemShortcutBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ShortcutWithChildren) {
            val shortcut = item.primary
            val secondaryList = item.secondaryShortcuts
            val isExpanded = expandedItemIds.contains(shortcut.id)

            val pinPrefix = if (shortcut.isPinned) "📌 " else ""
            val favPrefix = if (shortcut.isFavorite) "⭐ " else ""

            val isApp = shortcut.treeUri.startsWith("app:")
            val typePrefix = if (isApp) "📱 " else "📁 "

            binding.tvShortcutName.text = "$pinPrefix$favPrefix$typePrefix${shortcut.name}"
            binding.tvShortcutPath.text = if (isApp) "App: ${shortcut.treeUri.substringAfter("app:")}" else formatPath(shortcut.treeUri)
            binding.ivShortcutIcon.setImageResource(getIconResourceId(shortcut.icon))

            binding.ivDragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    onStartDrag(this)
                }
                false
            }

            // Sub-shortcuts toggle button text and chevron
            if (isApp) {
                binding.btnToggleSecondary.visibility = View.GONE
                binding.layoutSecondaryContainer.visibility = View.GONE
            } else {
                binding.btnToggleSecondary.visibility = View.VISIBLE
                val secCount = secondaryList.size
                binding.btnToggleSecondary.text = if (secCount == 0) {
                    "📁 0 Sub-shortcuts"
                } else {
                    "📁 $secCount Sub-shortcut${if (secCount > 1) "s" else ""}"
                }
                binding.btnToggleSecondary.setIconResource(
                    if (isExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
                )

                binding.layoutSecondaryContainer.visibility = if (isExpanded) View.VISIBLE else View.GONE
            }

            binding.btnToggleSecondary.setOnClickListener {
                if (expandedItemIds.contains(shortcut.id)) {
                    expandedItemIds.remove(shortcut.id)
                } else {
                    expandedItemIds.add(shortcut.id)
                }
                notifyItemChanged(bindingAdapterPosition)
            }

            binding.btnAddSecondary.setOnClickListener {
                expandedItemIds.add(shortcut.id)
                onAddSecondary(shortcut)
            }

            // Bind Secondary items list
            binding.layoutSecondaryItems.removeAllViews()
            if (secondaryList.isEmpty()) {
                binding.tvNoSecondaryHint.visibility = View.VISIBLE
            } else {
                binding.tvNoSecondaryHint.visibility = View.GONE
                val inflater = LayoutInflater.from(binding.root.context)
                for (sec in secondaryList) {
                    val secBinding = ItemSecondaryShortcutBinding.inflate(
                        inflater,
                        binding.layoutSecondaryItems,
                        false
                    )
                    secBinding.tvSecondaryName.text = "📁 ${sec.name}"
                    secBinding.tvSecondaryPath.text = formatPath(sec.treeUri)
                    secBinding.ivSecondaryIcon.setImageResource(getIconResourceId(sec.icon))

                    secBinding.layoutSecondaryItem.setOnClickListener {
                        onDetails(sec)
                    }

                    secBinding.btnSecondaryMenu.setOnClickListener { v ->
                        val popup = PopupMenu(v.context, v)
                        popup.menu.add(0, 1, 0, R.string.rename)
                        popup.menu.add(0, 2, 1, R.string.change_icon)
                        popup.menu.add(0, 9, 2, R.string.sort_order)
                        popup.menu.add(0, 3, 3, R.string.promote_to_primary)
                        popup.menu.add(0, 4, 4, R.string.move_to_primary)
                        popup.menu.add(0, 5, 5, R.string.details)
                        popup.menu.add(0, 6, 6, R.string.delete)

                        popup.setOnMenuItemClickListener { menuItem ->
                            when (menuItem.itemId) {
                                1 -> onRename(sec)
                                2 -> onChangeIcon(sec)
                                9 -> onSortOrder(sec)
                                3 -> onPromoteToPrimary(sec)
                                4 -> onMoveToPrimary(sec)
                                5 -> onDetails(sec)
                                6 -> onDelete(sec)
                            }
                            true
                        }
                        popup.show()
                    }

                    binding.layoutSecondaryItems.addView(secBinding.root)
                }
            }

            // Primary 3-dot menu
            binding.btnMenu.setOnClickListener { view ->
                val popup = PopupMenu(view.context, view)
                val pinTitle = if (shortcut.isPinned) R.string.unpin else R.string.pin
                val favTitle = if (shortcut.isFavorite) R.string.unfavorite else R.string.favorite

                popup.menu.add(0, 10, 0, R.string.add_secondary_shortcut)
                popup.menu.add(0, 1, 1, pinTitle)
                popup.menu.add(0, 2, 2, favTitle)
                popup.menu.add(0, 9, 3, R.string.sort_order)
                popup.menu.add(0, 8, 4, R.string.move_to_primary)
                popup.menu.add(0, 3, 5, R.string.rename)
                popup.menu.add(0, 4, 6, R.string.change_icon)
                popup.menu.add(0, 5, 7, R.string.hide)
                popup.menu.add(0, 6, 8, R.string.details)
                popup.menu.add(0, 7, 9, R.string.delete)

                popup.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        10 -> {
                            expandedItemIds.add(shortcut.id)
                            onAddSecondary(shortcut)
                        }
                        1 -> onTogglePin(shortcut)
                        2 -> onToggleFavorite(shortcut)
                        9 -> onSortOrder(shortcut)
                        8 -> onMoveToPrimary(shortcut)
                        3 -> onRename(shortcut)
                        4 -> onChangeIcon(shortcut)
                        5 -> onToggleHide(shortcut)
                        6 -> onDetails(shortcut)
                        7 -> onDelete(shortcut)
                    }
                    true
                }
                popup.show()
            }

            binding.cardShortcut.setOnClickListener {
                if (shortcut.treeUri.startsWith("app:")) {
                    val pkg = shortcut.treeUri.substringAfter("app:")
                    try {
                        val ctx = binding.root.context
                        val pm = ctx.packageManager
                        val intent = pm.getLaunchIntentForPackage(pkg) ?: Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_LAUNCHER)
                            setPackage(pkg)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        ctx.startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(binding.root.context, "Could not launch app: ${shortcut.name}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    onDetails(shortcut)
                }
            }
        }

        private fun formatPath(treeUriString: String): String {
            if (treeUriString.startsWith("app:")) {
                return "3rd-Party App: ${treeUriString.substringAfter("app:")}"
            }
            return try {
                val uri = Uri.parse(treeUriString)
                val docId = DocumentsContract.getTreeDocumentId(uri) ?: return treeUriString
                val parts = docId.split(":", limit = 2)
                if (parts.size == 2) {
                    val path = parts[1]
                    "/storage/emulated/0/$path"
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
                "favorite" -> R.drawable.ic_favorite
                "star" -> R.drawable.ic_star
                "documents" -> R.drawable.ic_documents
                "storage" -> R.drawable.ic_storage
                else -> R.drawable.ic_folder
            }
        }
    }

    class ShortcutDiffCallback : DiffUtil.ItemCallback<ShortcutWithChildren>() {
        override fun areItemsTheSame(oldItem: ShortcutWithChildren, newItem: ShortcutWithChildren): Boolean {
            return oldItem.primary.id == newItem.primary.id
        }

        override fun areContentsTheSame(oldItem: ShortcutWithChildren, newItem: ShortcutWithChildren): Boolean {
            return oldItem == newItem
        }
    }
}
