package com.ogul.plakakayit

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.slider.Slider
import com.ogul.plakakayit.data.PlateDatabase
import com.ogul.plakakayit.data.SecurityEvent
import com.ogul.plakakayit.data.SecurityFrameResult
import com.ogul.plakakayit.databinding.ActivitySecurityBinding
import com.ogul.plakakayit.security.SecurityFrameAnalyzer
import com.ogul.plakakayit.settings.AppPreferences
import com.ogul.plakakayit.ui.SecurityEventAdapter
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class SecurityActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySecurityBinding
    private lateinit var preferences: AppPreferences
    private lateinit var database: PlateDatabase
    private val adapter = SecurityEventAdapter()
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val databaseExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var analyzer: SecurityFrameAnalyzer? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var pendingPackage: String? = null
    private var settingUp = false
    @Volatile private var lastObservedPlate: String = ""
    @Volatile private var lastObservedPlateAt: Long = 0L

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else binding.liveSecurityText.text =
            getString(R.string.camera_permission_required)
    }

    private val packageDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val content = pendingPackage
        pendingPackage = null
        if (uri == null || content == null) return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(content.toByteArray(StandardCharsets.UTF_8))
            } ?: error("Dosya açılamadı")
        }.onSuccess {
            Toast.makeText(this, R.string.event_package_saved, Toast.LENGTH_SHORT).show()
        }.onFailure { error ->
            Toast.makeText(
                this,
                getString(R.string.event_package_failed, error.localizedMessage ?: "bilinmiyor"),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        preferences = AppPreferences(this)
        applyThemePreference()
        super.onCreate(savedInstanceState)
        binding = ActivitySecurityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        database = PlateDatabase(applicationContext)

        setupUi()
        loadEvents()
        requestCameraOrStart()
    }

    override fun onResume() {
        super.onResume()
        loadEvents()
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

    private fun setupUi() {
        binding.backButton.setOnClickListener { finish() }
        binding.securityEventsList.layoutManager = LinearLayoutManager(this)
        binding.securityEventsList.adapter = adapter
        binding.exportSecurityButton.setOnClickListener { exportEventPackage() }
        binding.clearSecurityButton.setOnClickListener { confirmClearEvents() }

        settingUp = true
        binding.securityAnalysisSwitch.isChecked = preferences.securityAnalysisEnabled
        binding.restrictedZoneSwitch.isChecked = preferences.restrictedZoneEnabled
        binding.securityOverlay.restrictedZoneVisible = preferences.restrictedZoneEnabled
        binding.dwellSlider.valueFrom = 5f
        binding.dwellSlider.valueTo = 60f
        binding.dwellSlider.stepSize = 5f
        binding.dwellSlider.value = preferences.securityDwellSeconds.coerceIn(5, 60).toFloat()
        updateDwellText(binding.dwellSlider.value.toInt())
        settingUp = false

        binding.securityAnalysisSwitch.setOnCheckedChangeListener { _, checked ->
            if (settingUp) return@setOnCheckedChangeListener
            preferences.securityAnalysisEnabled = checked
            binding.securityOverlay.setObservations(emptyList())
            startCamera()
        }
        binding.restrictedZoneSwitch.setOnCheckedChangeListener { _, checked ->
            if (settingUp) return@setOnCheckedChangeListener
            preferences.restrictedZoneEnabled = checked
            binding.securityOverlay.restrictedZoneVisible = checked
        }
        binding.dwellSlider.addOnChangeListener { _, value, _ ->
            updateDwellText(value.toInt())
        }
        binding.dwellSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) = Unit
            override fun onStopTrackingTouch(slider: Slider) {
                preferences.securityDwellSeconds = slider.value.toInt()
            }
        })
    }

    private fun updateDwellText(seconds: Int) {
        binding.dwellValueText.text = getString(R.string.dwell_seconds, seconds)
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
        binding.liveSecurityText.text = getString(R.string.camera_starting)
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = runCatching { providerFuture.get() }
                .getOrElse {
                    binding.liveSecurityText.text =
                        getString(R.string.security_camera_failed, it.localizedMessage ?: "bilinmiyor")
                    return@addListener
                }
            cameraProvider = provider
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = binding.securityPreviewView.surfaceProvider
            }

            analyzer?.close()
            analyzer = null
            provider.unbindAll()

            try {
                if (preferences.securityAnalysisEnabled) {
                    val securityAnalyzer = SecurityFrameAnalyzer(
                        context = applicationContext,
                        threshold = preferences.aiThreshold,
                        restrictedZoneEnabled = { preferences.restrictedZoneEnabled },
                        dwellThresholdSeconds = { preferences.securityDwellSeconds },
                        onResult = ::handleSecurityResult,
                        onError = { error ->
                            runOnUiThread {
                                binding.liveSecurityText.text = getString(
                                    R.string.security_analysis_error,
                                    error.localizedMessage ?: "bilinmiyor"
                                )
                            }
                        }
                    )
                    analyzer = securityAnalyzer
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(cameraExecutor, securityAnalyzer) }
                    provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                    binding.liveSecurityText.text = getString(R.string.security_waiting)
                } else {
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                    binding.liveSecurityText.text = getString(R.string.security_analysis_disabled)
                }
            } catch (error: Exception) {
                binding.liveSecurityText.text = getString(
                    R.string.security_camera_failed,
                    error.localizedMessage ?: "bilinmiyor"
                )
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleSecurityResult(result: SecurityFrameResult) {
        if (result.plates.isNotEmpty()) {
            lastObservedPlate = result.plates.first()
            lastObservedPlateAt = System.currentTimeMillis()
        }
        runOnUiThread {
            binding.securityOverlay.setObservations(result.persons)
            val personText = when {
                result.persons.isEmpty() -> getString(R.string.security_no_person)
                else -> result.persons.take(2).joinToString("\n") { person ->
                    buildList {
                        add("Kişi-${person.trackId}")
                        add(person.movement)
                        if (person.upperColor.isNotBlank()) add("üst ${person.upperColor}")
                        if (person.lowerColor.isNotBlank()) add("alt ${person.lowerColor}")
                        if (person.accessory.isNotBlank()) add(person.accessory)
                        add("yüz ${person.faceVisibility.lowercase()}")
                    }.joinToString(" • ")
                }
            }
            val plateText = result.plates.firstOrNull()?.let { "\nPlaka: $it" }.orEmpty()
            binding.liveSecurityText.text = personText + plateText
        }

        if (result.events.isNotEmpty()) {
            databaseExecutor.execute {
                val now = System.currentTimeMillis()
                val linkedPlate = if (lastObservedPlate.isNotBlank() && now - lastObservedPlateAt <= 20_000L) {
                    lastObservedPlate
                } else {
                    database.getLatestPlateWithin(now - 20_000L)
                }
                result.events.forEach { draft ->
                    runCatching { database.insertSecurityEvent(draft, linkedPlate) }
                }
                loadEvents()
            }
        }
    }

    private fun loadEvents() {
        if (!::database.isInitialized) return
        databaseExecutor.execute {
            val events = runCatching { database.getSecurityEvents() }.getOrDefault(emptyList())
            runOnUiThread {
                adapter.submitList(events)
                binding.securityEventsEmptyText.visibility =
                    if (events.isEmpty()) View.VISIBLE else View.GONE
                binding.securityEventsTitle.text = getString(R.string.security_events_count, events.size)
            }
        }
    }

    private fun exportEventPackage() {
        databaseExecutor.execute {
            val events = runCatching { database.getSecurityEvents(1000) }.getOrDefault(emptyList())
            if (events.isEmpty()) {
                runOnUiThread {
                    Toast.makeText(this, R.string.no_events_to_export, Toast.LENGTH_SHORT).show()
                }
                return@execute
            }
            val packageText = buildEventPackage(events)
            val fileName = "guvenlik-olay-paketi-${
                SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
            }.json"
            runOnUiThread {
                pendingPackage = packageText
                packageDocumentLauncher.launch(fileName)
            }
        }
    }

    private fun buildEventPackage(events: List<SecurityEvent>): String {
        val eventArray = JSONArray()
        events.sortedBy { it.occurredAt }.forEach { event ->
            eventArray.put(JSONObject().apply {
                put("id", event.id)
                put("occurredAt", event.occurredAt)
                put("trackId", event.trackId)
                put("type", event.type.value)
                put("typeName", event.type.displayName)
                put("summary", event.summary)
                put("confidence", event.confidence.toDouble())
                put("upperColor", event.upperColor)
                put("lowerColor", event.lowerColor)
                put("accessory", event.accessory)
                put("movement", event.movement)
                put("direction", event.direction)
                put("dwellSeconds", event.dwellSeconds)
                put("faceVisibility", event.faceVisibility)
                put("faceQuality", event.faceQuality)
                put("linkedPlate", event.linkedPlate)
            })
        }
        val payload = JSONObject().apply {
            put("format", "PlakaKayit-SecurityEventPackage")
            put("formatVersion", 1)
            put("appVersion", currentVersionName())
            put("createdAt", System.currentTimeMillis())
            put("mediaIncluded", false)
            put("notice", "AI sonuçları tahmindir; insan doğrulaması gerekir.")
            put("events", eventArray)
        }
        val payloadText = payload.toString()
        return JSONObject().apply {
            put("payload", payload)
            put("payloadSha256", sha256(payloadText))
        }.toString(2)
    }

    private fun currentVersionName(): String {
        return runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "bilinmiyor"
        }.getOrDefault("bilinmiyor")
    }

    private fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun confirmClearEvents() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_security_events_title)
            .setMessage(R.string.clear_security_events_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.clear) { _, _ ->
                databaseExecutor.execute {
                    database.clearSecurityEvents()
                    loadEvents()
                }
            }
            .show()
    }

    override fun onStop() {
        if (preferences.securityEnabled && !isChangingConfigurations) {
            finish()
        }
        super.onStop()
    }

    override fun onDestroy() {
        analyzer?.close()
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        databaseExecutor.shutdown()
        database.close()
        super.onDestroy()
    }
}
