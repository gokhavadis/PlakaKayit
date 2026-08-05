package com.ogul.plakakayit

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.ogul.plakakayit.data.PlateDatabase
import com.ogul.plakakayit.data.PlateRecord
import com.ogul.plakakayit.databinding.ActivityMainBinding
import com.ogul.plakakayit.databinding.DialogVehicleInfoBinding
import com.ogul.plakakayit.ml.PlateAnalyzer
import com.ogul.plakakayit.ui.PlateRecordAdapter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var database: PlateDatabase
    private val adapter = PlateRecordAdapter(
        onEdit = ::showVehicleInfoDialog,
        onDelete = ::confirmDeleteRecord
    )
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val databaseExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var analyzer: PlateAnalyzer? = null
    private var allRecords: List<PlateRecord> = emptyList()
    private var pendingCsv: String? = null

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
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = PlateDatabase(applicationContext)
        binding.recordsList.layoutManager = LinearLayoutManager(this)
        binding.recordsList.adapter = adapter
        binding.clearButton.setOnClickListener { confirmClearRecords() }
        binding.exportButton.setOnClickListener { exportCsv() }
        binding.searchEditText.doAfterTextChanged {
            applyFilter(it?.toString().orEmpty())
        }

        loadRecords()
        requestCameraOrStart()
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
                onPlateDetected = ::saveDetectedPlate,
                onAnalyzerError = {
                    runOnUiThread {
                        binding.statusText.text =
                            "Okuma hatası: ${it.localizedMessage ?: "bilinmiyor"}"
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
            } catch (error: Exception) {
                binding.statusText.text = "Kamera bağlantı hatası: ${error.localizedMessage}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun saveDetectedPlate(plate: String) {
        databaseExecutor.execute {
            runCatching { database.upsertPlate(plate) }
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
                listOf(record.plate, record.brand, record.model, record.color)
                    .any { it.lowercase(Locale.getDefault()).contains(normalized) }
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
            val fileName = "plaka-kayit-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.csv"
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
            appendLine("Plaka;Marka;Model;Renk;İlk Görülme;Son Görülme;Görülme Sayısı")
            records.forEach { record ->
                append(csvCell(record.plate)).append(';')
                append(csvCell(record.brand)).append(';')
                append(csvCell(record.model)).append(';')
                append(csvCell(record.color)).append(';')
                append(csvCell(dateFormat.format(Date(record.firstSeenAt)))).append(';')
                append(csvCell(dateFormat.format(Date(record.lastSeenAt)))).append(';')
                append(record.seenCount)
                appendLine()
            }
        }
    }

    private fun csvCell(value: String): String =
        "\"${value.replace("\"", "\"\"")}\""

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

    override fun onDestroy() {
        analyzer?.close()
        cameraExecutor.shutdown()
        databaseExecutor.shutdown()
        database.close()
        super.onDestroy()
    }
}
