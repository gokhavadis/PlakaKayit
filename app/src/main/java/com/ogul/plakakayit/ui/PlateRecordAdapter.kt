package com.ogul.plakakayit.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ogul.plakakayit.data.PlateRecord
import com.ogul.plakakayit.databinding.ItemPlateRecordBinding
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class PlateRecordAdapter(
    private val onEdit: (PlateRecord) -> Unit,
    private val onDelete: (PlateRecord) -> Unit
) : RecyclerView.Adapter<PlateRecordAdapter.RecordViewHolder>() {

    private val items = mutableListOf<PlateRecord>()
    private val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)

    fun submitList(newItems: List<PlateRecord>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecordViewHolder {
        val binding = ItemPlateRecordBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return RecordViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RecordViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class RecordViewHolder(
        private val binding: ItemPlateRecordBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: PlateRecord) {
            binding.plateText.text = item.plate
            binding.vehicleText.text = vehicleSummary(item)
            binding.timeText.text = "Son görülme: ${dateFormat.format(Date(item.lastSeenAt))}"
            binding.countText.text = "Görülme sayısı: ${item.seenCount}"
            binding.editButton.setOnClickListener { onEdit(item) }
            binding.deleteButton.setOnClickListener { onDelete(item) }
            binding.root.setOnClickListener { onEdit(item) }
        }

        private fun vehicleSummary(item: PlateRecord): String {
            val brandModel = listOf(item.brand, item.model)
                .filter { it.isNotBlank() }
                .joinToString(" ")
            val confidence = if (item.aiConfidence > 0f) {
                "AI %${String.format(Locale.US, "%.0f", item.aiConfidence * 100)}"
            } else {
                ""
            }
            val details = listOf(item.vehicleType, brandModel, item.color, confidence)
                .filter { it.isNotBlank() }
            return if (details.isEmpty()) {
                "Araç bilgisi eklenmedi"
            } else {
                details.joinToString(" • ")
            }
        }
    }
}
