package com.quickfilepicker.ui.main

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.quickfilepicker.R
import com.quickfilepicker.databinding.ItemIconOptionBinding

data class IconOption(val key: String, val drawableRes: Int)

class IconAdapter(
    private val selectedIconKey: String,
    private val onIconSelected: (String) -> Unit
) : RecyclerView.Adapter<IconAdapter.IconViewHolder>() {

    val icons = listOf(
        IconOption("folder", R.drawable.ic_folder),
        IconOption("book", R.drawable.ic_book),
        IconOption("medical", R.drawable.ic_medical),
        IconOption("telegram", R.drawable.ic_telegram),
        IconOption("whatsapp", R.drawable.ic_whatsapp),
        IconOption("image", R.drawable.ic_image),
        IconOption("pdf", R.drawable.ic_pdf),
        IconOption("download", R.drawable.ic_download),
        IconOption("favorite", R.drawable.ic_favorite),
        IconOption("star", R.drawable.ic_star),
        IconOption("documents", R.drawable.ic_documents),
        IconOption("storage", R.drawable.ic_storage)
    )

    private var currentSelectedKey = selectedIconKey

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): IconViewHolder {
        val binding = ItemIconOptionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return IconViewHolder(binding)
    }

    override fun onBindViewHolder(holder: IconViewHolder, position: Int) {
        holder.bind(icons[position])
    }

    override fun getItemCount(): Int = icons.size

    inner class IconViewHolder(private val binding: ItemIconOptionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(option: IconOption) {
            binding.ivIcon.setImageResource(option.drawableRes)
            val isSelected = option.key == currentSelectedKey

            val density = binding.root.resources.displayMetrics.density
            if (isSelected) {
                val primaryColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimary)
                val primaryContainerColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimaryContainer)
                binding.cardIcon.strokeColor = primaryColor
                binding.cardIcon.strokeWidth = (2.5f * density).toInt()
                binding.cardIcon.setCardBackgroundColor(primaryContainerColor)
                binding.ivIcon.setColorFilter(primaryColor)
            } else {
                val outlineColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOutlineVariant)
                val surfaceColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurface)
                val onSurfaceColor = MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnSurface)
                binding.cardIcon.strokeColor = outlineColor
                binding.cardIcon.strokeWidth = (1f * density).toInt()
                binding.cardIcon.setCardBackgroundColor(surfaceColor)
                binding.ivIcon.setColorFilter(onSurfaceColor)
            }

            binding.cardIcon.setOnClickListener {
                val oldKey = currentSelectedKey
                currentSelectedKey = option.key
                val oldIndex = icons.indexOfFirst { it.key == oldKey }
                val newIndex = icons.indexOfFirst { it.key == currentSelectedKey }
                if (oldIndex != -1) notifyItemChanged(oldIndex)
                if (newIndex != -1) notifyItemChanged(newIndex)
                onIconSelected(option.key)
            }
        }
    }
}
