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
import androidx.recyclerview.widget.LinearLayoutManager
import com.ogul.plakakayit.data.PlateDatabase
import com.ogul.plakakayit.databinding.ActivityMainBinding
import com.ogul.plakakayit.ml.PlateAnalyzer
import com.ogul.plakakayit.ui.PlateRecordAdapter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var database: PlateDatabase
    private val adapter = PlateRecordAdapter()
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val databaseExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var analyzer: PlateAnalyzer? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            binding.statusText.text = getString(R.string.camera_permission_required)
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
                        binding.statusText.text = "Okuma hatası: ${it.localizedMessage ?: "bilinmiyor"}"
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
                adapter.submitList(records)
                binding.emptyText.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun confirmClearRecords() {
        AlertDialog.Builder(this)
            .setTitle("Tüm kayıtlar silinsin mi?")
            .setMessage("Bu işlem geri alınamaz.")
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Sil") { _, _ ->
                databaseExecutor.execute {
                    database.clearAll()
                    runOnUiThread {
                        adapter.submitList(emptyList())
                        binding.emptyText.visibility = View.VISIBLE
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
