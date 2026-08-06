package com.ogul.plakakayit.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ogul.plakakayit.data.SecurityEvent
import com.ogul.plakakayit.databinding.ItemSecurityEventBinding
import java.text.DateFormat
import java.util.Date

class SecurityEventAdapter : RecyclerView.Adapter<SecurityEventAdapter.EventViewHolder>() {
    private val items = mutableListOf<SecurityEvent>()
    private val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)

    fun submitList(newItems: List<SecurityEvent>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EventViewHolder {
        val binding = ItemSecurityEventBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return EventViewHolder(binding)
    }

    override fun onBindViewHolder(holder: EventViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class EventViewHolder(
        private val binding: ItemSecurityEventBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SecurityEvent) {
            binding.eventTypeText.text = "${item.type.displayName} • Kişi-${item.trackId}"
            binding.eventSummaryText.text = item.summary
            binding.eventTimeText.text = dateFormat.format(Date(item.occurredAt))
            binding.eventDetailsText.text = buildList {
                if (item.linkedPlate.isNotBlank()) add("Plaka: ${item.linkedPlate}")
                if (item.faceVisibility.isNotBlank()) {
                    add("Yüz: ${item.faceVisibility} (%${item.faceQuality})")
                }
                add("AI güveni: %${(item.confidence * 100).toInt()}")
            }.joinToString(" • ")
        }
    }
}
