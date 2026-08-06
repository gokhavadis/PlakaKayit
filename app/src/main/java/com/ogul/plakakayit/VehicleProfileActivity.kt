package com.ogul.plakakayit

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ogul.plakakayit.data.MovementEvent
import com.ogul.plakakayit.data.MovementType
import com.ogul.plakakayit.data.PlateDatabase
import com.ogul.plakakayit.data.PlateRecord
import com.ogul.plakakayit.databinding.ActivityVehicleProfileBinding
import com.ogul.plakakayit.settings.AppPreferences
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class VehicleProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVehicleProfileBinding
    private lateinit var database: PlateDatabase
    private lateinit var preferences: AppPreferences
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
    private var recordId: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        preferences = AppPreferences(this)
        applyThemePreference()
        super.onCreate(savedInstanceState)
        binding = ActivityVehicleProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        recordId = intent.getLongExtra(EXTRA_RECORD_ID, -1L)
        if (recordId <= 0L) {
            finish()
            return
        }

        database = PlateDatabase(applicationContext)
        binding.backButton.setOnClickListener { finish() }
        binding.saveProfileButton.setOnClickListener { saveProfile() }
        binding.manualEntryButton.setOnClickListener { addMovement(MovementType.ENTRY) }
        binding.manualExitButton.setOnClickListener { addMovement(MovementType.EXIT) }
        loadProfile()
    }

    override fun onResume() {
        super.onResume()
        if (::database.isInitialized) loadProfile()
    }

    private fun applyThemePreference() {
        when (preferences.themeMode) {
            AppPreferences.ThemeMode.SYSTEM -> setTheme(R.style.Theme_PlakaKayit)
            AppPreferences.ThemeMode.LIGHT -> setTheme(R.style.Theme_PlakaKayit)
            AppPreferences.ThemeMode.DARK -> setTheme(R.style.Theme_PlakaKayit)
            AppPreferences.ThemeMode.AMOLED -> setTheme(R.style.Theme_PlakaKayit_Amoled)
        }
    }

    private fun loadProfile() {
        executor.execute {
            val record = runCatching { database.getRecord(recordId) }.getOrNull()
            val history = runCatching { database.getMovementHistory(recordId) }.getOrDefault(emptyList())
            runOnUiThread {
                if (record == null) {
                    Toast.makeText(this, R.string.record_not_found, Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    bindRecord(record, history)
                }
            }
        }
    }

    private fun bindRecord(record: PlateRecord, history: List<MovementEvent>) {
        binding.profilePlateText.text = record.plate
        binding.profileStatusText.text = if (record.isInside) {
            getString(R.string.status_inside)
        } else {
            getString(R.string.status_outside)
        }
        binding.profileSummaryText.text = listOf(
            record.vehicleType,
            listOf(record.brand, record.model).filter { it.isNotBlank() }.joinToString(" "),
            record.color
        ).filter { it.isNotBlank() }.joinToString(" • ").ifBlank {
            getString(R.string.vehicle_info_missing)
        }
        binding.firstSeenText.text = getString(
            R.string.first_seen_value,
            dateFormat.format(Date(record.firstSeenAt))
        )
        binding.lastSeenText.text = getString(
            R.string.last_seen_value,
            dateFormat.format(Date(record.lastSeenAt))
        )
        binding.totalEntriesText.text = getString(R.string.total_entries_value, record.totalEntries)
        binding.lastEntryText.text = getString(
            R.string.last_entry_value,
            formatOptionalDate(record.lastEntryAt)
        )
        binding.lastExitText.text = getString(
            R.string.last_exit_value,
            formatOptionalDate(record.lastExitAt)
        )

        if (!binding.brandEditText.hasFocus()) binding.brandEditText.setText(record.brand)
        if (!binding.modelEditText.hasFocus()) binding.modelEditText.setText(record.model)
        if (!binding.colorEditText.hasFocus()) binding.colorEditText.setText(record.color)
        if (!binding.categoryEditText.hasFocus()) binding.categoryEditText.setText(record.category)
        if (!binding.noteEditText.hasFocus()) binding.noteEditText.setText(record.note)

        binding.movementHistoryText.text = if (history.isEmpty()) {
            getString(R.string.no_movement_history)
        } else {
            history.joinToString("\n") { event ->
                val label = when (event.type) {
                    MovementType.ENTRY -> getString(R.string.movement_entry)
                    MovementType.EXIT -> getString(R.string.movement_exit)
                    MovementType.OBSERVATION -> getString(R.string.movement_observation)
                }
                "$label — ${dateFormat.format(Date(event.time))}"
            }
        }
    }

    private fun saveProfile() {
        val brand = binding.brandEditText.text?.toString().orEmpty()
        val model = binding.modelEditText.text?.toString().orEmpty()
        val color = binding.colorEditText.text?.toString().orEmpty()
        val category = binding.categoryEditText.text?.toString().orEmpty()
        val note = binding.noteEditText.text?.toString().orEmpty()
        binding.saveProfileButton.isEnabled = false
        executor.execute {
            runCatching {
                database.updateVehicleProfile(
                    recordId,
                    brand,
                    model,
                    color,
                    category,
                    note
                )
            }.onSuccess {
                runOnUiThread {
                    binding.saveProfileButton.isEnabled = true
                    Toast.makeText(this, R.string.profile_saved, Toast.LENGTH_SHORT).show()
                }
                loadProfile()
            }.onFailure { error ->
                runOnUiThread {
                    binding.saveProfileButton.isEnabled = true
                    Toast.makeText(
                        this,
                        getString(R.string.profile_save_failed, error.localizedMessage ?: "bilinmiyor"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun addMovement(type: MovementType) {
        binding.manualEntryButton.isEnabled = false
        binding.manualExitButton.isEnabled = false
        executor.execute {
            runCatching { database.recordManualMovement(recordId, type) }
                .onSuccess { outcome ->
                    runOnUiThread {
                        binding.manualEntryButton.isEnabled = true
                        binding.manualExitButton.isEnabled = true
                        val message = when {
                            !outcome.movementChanged && type == MovementType.ENTRY ->
                                getString(R.string.vehicle_already_inside)
                            !outcome.movementChanged && type == MovementType.EXIT ->
                                getString(R.string.vehicle_already_outside)
                            type == MovementType.ENTRY -> getString(R.string.manual_entry_saved)
                            else -> getString(R.string.manual_exit_saved)
                        }
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    }
                    loadProfile()
                }
                .onFailure { error ->
                    runOnUiThread {
                        binding.manualEntryButton.isEnabled = true
                        binding.manualExitButton.isEnabled = true
                        Toast.makeText(
                            this,
                            error.localizedMessage ?: getString(R.string.operation_failed),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
        }
    }

    private fun formatOptionalDate(value: Long): String =
        if (value > 0L) dateFormat.format(Date(value)) else getString(R.string.never)

    override fun onDestroy() {
        executor.shutdown()
        if (::database.isInitialized) database.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RECORD_ID = "record_id"
    }
}
