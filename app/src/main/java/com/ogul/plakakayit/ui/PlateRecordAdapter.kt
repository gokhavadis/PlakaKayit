package com.ogul.plakakayit.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ogul.plakakayit.data.PlateRecord
import com.ogul.plakakayit.databinding.ItemPlateRecordBinding
import java.text.DateFormat
import java.util.Date

class PlateRecordAdapter : RecyclerView.Adapter<PlateRecordAdapter.RecordViewHolder>() {
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
            binding.timeText.text = "Son görülme: ${dateFormat.format(Date(item.lastSeenAt))}"
            binding.countText.text = "Görülme sayısı: ${item.seenCount}"
        }
    }
}
