package com.ogul.plakakayit

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.slider.Slider
import com.ogul.plakakayit.data.PlateDatabase
import com.ogul.plakakayit.data.PlateRecord
import com.ogul.plakakayit.data.VehicleObservation
import com.ogul.plakakayit.databinding.ActivityMainBinding
import com.ogul.plakakayit.databinding.DialogVehicleInfoBinding
import com.ogul.plakakayit.ml.PlateAnalyzer
import com.ogul.plakakayit.settings.AppPreferences
import com.ogul.plakakayit.ui.PlateRecordAdapter
import com.ogul.plakakayit.update.UpdateChecker
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var database: PlateDatabase
    private lateinit var preferences: AppPreferences
    private val adapter = PlateRecordAdapter(
        onEdit = ::showVehicleInfoDialog,
        onDelete = ::confirmDeleteRecord
    )
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val databaseExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val updateExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var analyzer: PlateAnalyzer? = null
    private var allRecords: List<PlateRecord> = emptyList()
    private var pendingCsv: String? = null
    private var latestReleaseUrl: String = UpdateChecker.RELEASES_PAGE
    private var settingUpSettings = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            binding.statusText.text = getString(R.string.camera_permission_required)
        }
    }

    private val csvDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        val csv = pendingCsv
        pendingCsv = null
        if (uri == null || csv == null) return@registerForActivityResult

        runCatching {
            contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(csv.toByteArray(StandardCharsets.UTF_8))
            } ?: error("Dosya açılamadı")
        }.onSuccess {
            Toast.makeText(this, "CSV dosyası kaydedildi", Toast.LENGTH_SHORT).show()
        }.onFailure { error ->
            Toast.makeText(
                this,
                "CSV kaydedilemedi: ${error.localizedMessage}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        preferences = AppPreferences(this)
        applyThemePreference()
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = PlateDatabase(applicationContext)
        setupNavigation()
        setupRecordsScreen()
        setupUpdateScreen()
        setupSettingsScreen()

        showPanel(binding.cameraPanel)
        loadRecords()
        requestCameraOrStart()
    }

    private fun applyThemePreference() {
        when (preferences.themeMode) {
            AppPreferences.ThemeMode.SYSTEM -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                setTheme(R.style.Theme_PlakaKayit)
            }
            AppPreferences.ThemeMode.LIGHT -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                setTheme(R.style.Theme_PlakaKayit)
            }
            AppPreferences.ThemeMode.DARK -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                setTheme(R.style.Theme_PlakaKayit)
            }
            AppPreferences.ThemeMode.AMOLED -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                setTheme(R.style.Theme_PlakaKayit_Amoled)
            }
        }
    }

    private fun setupNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_camera -> showPanel(binding.cameraPanel)
                R.id.navigation_records -> {
                    showPanel(binding.recordsPanel)
                    loadRecords()
                }
                R.id.navigation_updates -> showPanel(binding.updatesPanel)
                R.id.navigation_settings -> showPanel(binding.settingsPanel)
                else -> return@setOnItemSelectedListener false
            }
            true
        }
        binding.bottomNavigation.selectedItemId = R.id.navigation_camera
    }

    private fun showPanel(panel: View) {
        binding.cameraPanel.visibility = if (panel === binding.cameraPanel) View.VISIBLE else View.GONE
        binding.recordsPanel.visibility = if (panel === binding.recordsPanel) View.VISIBLE else View.GONE
        binding.updatesPanel.visibility = if (panel === binding.updatesPanel) View.VISIBLE else View.GONE
        binding.settingsPanel.visibility = if (panel === binding.settingsPanel) View.VISIBLE else View.GONE
    }

    private fun setupRecordsScreen() {
        binding.recordsList.layoutManager = LinearLayoutManager(this)
        binding.recordsList.adapter = adapter
        binding.clearButton.setOnClickListener { confirmClearRecords() }
        binding.exportButton.setOnClickListener { exportCsv() }
        binding.searchEditText.doAfterTextChanged {
            applyFilter(it?.toString().orEmpty())
        }
    }

    private fun setupUpdateScreen() {
        binding.currentVersionText.text = getString(R.string.current_version, currentVersionName())
        binding.checkUpdateButton.setOnClickListener { checkForUpdates() }
        binding.openReleaseButton.setOnClickListener {
            openWebPage(latestReleaseUrl)
        }
    }

    private fun setupSettingsScreen() {
        settingUpSettings = true
        binding.confidenceSlider.valueFrom = 0.30f
        binding.confidenceSlider.valueTo = 0.90f
        binding.confidenceSlider.stepSize = 0.05f
        binding.aiEnabledSwitch.isChecked = preferences.aiEnabled
        binding.confidenceSlider.value = preferences.aiThreshold
        binding.confidenceValueText.text = formatThreshold(preferences.aiThreshold)
        binding.themeGroup.check(
            when (preferences.themeMode) {
                AppPreferences.ThemeMode.SYSTEM -> R.id.themeSystem
                AppPreferences.ThemeMode.LIGHT -> R.id.themeLight
                AppPreferences.ThemeMode.DARK -> R.id.themeDark
                AppPreferences.ThemeMode.AMOLED -> R.id.themeAmoled
            }
        )
        settingUpSettings = false

        binding.themeGroup.setOnCheckedChangeListener { _, checkedId ->
            if (settingUpSettings) return@setOnCheckedChangeListener
            val mode = when (checkedId) {
                R.id.themeLight -> AppPreferences.ThemeMode.LIGHT
                R.id.themeDark -> AppPreferences.ThemeMode.DARK
                R.id.themeAmoled -> AppPreferences.ThemeMode.AMOLED
                else -> AppPreferences.ThemeMode.SYSTEM
            }
            if (mode != preferences.themeMode) {
                preferences.themeMode = mode
                recreate()
            }
        }

        binding.aiEnabledSwitch.setOnCheckedChangeListener { _, checked ->
            if (settingUpSettings) return@setOnCheckedChangeListener
            preferences.aiEnabled = checked
            binding.aiStatusText.text = if (checked) {
                getString(R.string.ai_waiting)
            } else {
                getString(R.string.ai_disabled)
            }
            startCamera()
        }

        binding.confidenceSlider.addOnChangeListener { _, value, _ ->
            binding.confidenceValueText.text = formatThreshold(value)
        }
        binding.confidenceSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit

            override fun onStopTrackingTouch(slider: Slider) {
                preferences.aiThreshold = slider.value
                if (preferences.aiEnabled) startCamera()
            }
        })
    }

    private fun requestCameraOrStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        if (!::binding.isInitialized) return
        binding.statusText.text = getString(R.string.camera_starting)
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val cameraProvider = runCatching { providerFuture.get() }
                .getOrElse {
                    binding.statusText.text = "Kamera başlatılamadı: ${it.localizedMessage}"
                    return@addListener
                }

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = binding.previewView.surfaceProvider
            }

            analyzer?.close()
            analyzer = PlateAnalyzer(
                context = applicationContext,
                aiEnabled = preferences.aiEnabled,
                aiThreshold = preferences.aiThreshold,
                onPlateDetected = ::saveDetectedPlate,
                onVehicleObserved = ::showVehicleObservation,
                onAnalyzerError = { error ->
                    runOnUiThread {
                        binding.statusText.text =
                            "Okuma hatası: ${error.localizedMessage ?: "bilinmiyor"}"
                    }
                }
            )

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor, analyzer!!) }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
                binding.statusText.text = getString(R.string.scanning)
                binding.aiStatusText.text = if (preferences.aiEnabled) {
                    getString(R.string.ai_waiting)
                } else {
                    getString(R.string.ai_disabled)
                }
            } catch (error: Exception) {
                binding.statusText.text = "Kamera bağlantı hatası: ${error.localizedMessage}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun showVehicleObservation(observation: VehicleObservation?) {
        runOnUiThread {
            binding.aiStatusText.text = when {
                !preferences.aiEnabled -> getString(R.string.ai_disabled)
                observation == null -> getString(R.string.ai_no_vehicle)
                else -> {
                    val confidence = (observation.confidence * 100).toInt()
                    listOf(
                        observation.type,
                        observation.color,
                        "AI %$confidence"
                    ).filter { it.isNotBlank() }.joinToString(" • ")
                }
            }
        }
    }

    private fun saveDetectedPlate(plate: String, observation: VehicleObservation?) {
        databaseExecutor.execute {
            runCatching { database.upsertPlate(plate, observation) }
                .onSuccess {
                    runOnUiThread { binding.statusText.text = "Kaydedildi: $plate" }
                    loadRecords()
                }
                .onFailure { error ->
                    runOnUiThread {
                        binding.statusText.text = "Kayıt hatası: ${error.localizedMessage}"
                    }
                }
        }
    }

    private fun loadRecords() {
        databaseExecutor.execute {
            val records = runCatching { database.getRecent() }.getOrDefault(emptyList())
            runOnUiThread {
                allRecords = records
                applyFilter(binding.searchEditText.text?.toString().orEmpty())
            }
        }
    }

    private fun applyFilter(query: String) {
        val normalized = query.trim().lowercase(Locale.getDefault())
        val filtered = if (normalized.isBlank()) {
            allRecords
        } else {
            allRecords.filter { record ->
                listOf(
                    record.plate,
                    record.brand,
                    record.model,
                    record.color,
                    record.vehicleType
                ).any { it.lowercase(Locale.getDefault()).contains(normalized) }
            }
        }

        adapter.submitList(filtered)
        binding.emptyText.text = if (allRecords.isEmpty()) {
            getString(R.string.no_records)
        } else {
            getString(R.string.no_search_results)
        }
        binding.emptyText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        binding.recordsTitle.text = getString(R.string.records_count, filtered.size)
    }

    private fun showVehicleInfoDialog(record: PlateRecord) {
        val dialogBinding = DialogVehicleInfoBinding.inflate(layoutInflater)
        dialogBinding.brandEditText.setText(record.brand)
        dialogBinding.modelEditText.setText(record.model)
        dialogBinding.colorEditText.setText(record.color)

        AlertDialog.Builder(this)
            .setTitle("${record.plate} araç bilgileri")
            .setView(dialogBinding.root)
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Kaydet") { _, _ ->
                val brand = dialogBinding.brandEditText.text?.toString().orEmpty()
                val model = dialogBinding.modelEditText.text?.toString().orEmpty()
                val color = dialogBinding.colorEditText.text?.toString().orEmpty()
                databaseExecutor.execute {
                    runCatching {
                        database.updateVehicleInfo(record.id, brand, model, color)
                    }.onSuccess {
                        runOnUiThread {
                            Toast.makeText(this, "Araç bilgileri kaydedildi", Toast.LENGTH_SHORT)
                                .show()
                        }
                        loadRecords()
                    }.onFailure { error ->
                        runOnUiThread {
                            Toast.makeText(
                                this,
                                "Bilgiler kaydedilemedi: ${error.localizedMessage}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
            .show()
    }

    private fun confirmDeleteRecord(record: PlateRecord) {
        AlertDialog.Builder(this)
            .setTitle("${record.plate} silinsin mi?")
            .setMessage("Yalnızca bu kayıt silinecek.")
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Sil") { _, _ ->
                databaseExecutor.execute {
                    database.deleteRecord(record.id)
                    runOnUiThread {
                        Toast.makeText(this, "Kayıt silindi", Toast.LENGTH_SHORT).show()
                    }
                    loadRecords()
                }
            }
            .show()
    }

    private fun exportCsv() {
        databaseExecutor.execute {
            val records = runCatching { database.getRecent(1000) }.getOrDefault(emptyList())
            if (records.isEmpty()) {
                runOnUiThread {
                    Toast.makeText(this, "Dışa aktarılacak kayıt yok", Toast.LENGTH_SHORT).show()
                }
                return@execute
            }

            val csv = buildCsv(records)
            val fileName = "plaka-kayit-${
                SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
            }.csv"
            runOnUiThread {
                pendingCsv = csv
                csvDocumentLauncher.launch(fileName)
            }
        }
    }

    private fun buildCsv(records: List<PlateRecord>): String {
        val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
        return buildString {
            append('\uFEFF')
            appendLine(
                "Plaka;Araç Türü;Marka;Model;Renk;AI Güveni;İlk Görülme;Son Görülme;Görülme Sayısı"
            )
            records.forEach { record ->
                append(csvCell(record.plate)).append(';')
                append(csvCell(record.vehicleType)).append(';')
                append(csvCell(record.brand)).append(';')
                append(csvCell(record.model)).append(';')
                append(csvCell(record.color)).append(';')
                append(csvCell(if (record.aiConfidence > 0f) "%.0f%%".format(record.aiConfidence * 100) else ""))
                    .append(';')
                append(csvCell(dateFormat.format(Date(record.firstSeenAt)))).append(';')
                append(csvCell(dateFormat.format(Date(record.lastSeenAt)))).append(';')
                append(record.seenCount)
                appendLine()
            }
        }
    }

    private fun csvCell(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    private fun confirmClearRecords() {
        AlertDialog.Builder(this)
            .setTitle("Tüm kayıtlar silinsin mi?")
            .setMessage("Bu işlem geri alınamaz.")
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Sil") { _, _ ->
                databaseExecutor.execute {
                    database.clearAll()
                    runOnUiThread {
                        allRecords = emptyList()
                        applyFilter("")
                        Toast.makeText(this, "Kayıtlar silindi", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun checkForUpdates() {
        binding.checkUpdateButton.isEnabled = false
        binding.latestVersionText.text = getString(R.string.update_checking)
        binding.releaseNotesText.text = ""

        updateExecutor.execute {
            runCatching { UpdateChecker().checkLatestRelease() }
                .onSuccess { release ->
                    latestReleaseUrl = release.pageUrl
                    runOnUiThread {
                        binding.latestVersionText.text = if (release.tag == null) {
                            release.title
                        } else {
                            getString(R.string.latest_version, release.tag)
                        }
                        binding.releaseTitleText.text = release.title
                        binding.releaseNotesText.text = release.notes
                        binding.checkUpdateButton.isEnabled = true
                    }
                }
                .onFailure { error ->
                    runOnUiThread {
                        binding.latestVersionText.text =
                            "Güncelleme kontrol edilemedi: ${error.localizedMessage}"
                        binding.checkUpdateButton.isEnabled = true
                    }
                }
        }
    }

    private fun openWebPage(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(this, "Bağlantı açılamadı", Toast.LENGTH_SHORT).show()
        }
    }

    private fun currentVersionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "Bilinmiyor"
    }.getOrDefault("Bilinmiyor")

    private fun formatThreshold(value: Float): String = "Minimum güven: %${(value * 100).toInt()}"

    override fun onDestroy() {
        analyzer?.close()
        cameraExecutor.shutdown()
        databaseExecutor.shutdown()
        updateExecutor.shutdown()
        database.close()
        super.onDestroy()
    }
}
