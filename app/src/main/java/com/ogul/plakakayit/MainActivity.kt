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
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.slider.Slider
import com.ogul.plakakayit.data.DetectionOutcome
import com.ogul.plakakayit.data.MovementType
import com.ogul.plakakayit.data.PlateDatabase
import com.ogul.plakakayit.data.PlateRecord
import com.ogul.plakakayit.data.VehicleObservation
import com.ogul.plakakayit.databinding.ActivityMainBinding
import com.ogul.plakakayit.databinding.DialogSetPinBinding
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
        onOpenProfile = ::openVehicleProfile,
        onEdit = ::showVehicleInfoDialog,
        onDelete = ::confirmDeleteRecord
    )
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val databaseExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val updateExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var analyzer: PlateAnalyzer? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var allRecords: List<PlateRecord> = emptyList()
    private var pendingCsv: String? = null
    private var latestReleaseUrl: String = UpdateChecker.RELEASES_PAGE
    private var settingUpSettings = false
    private var unlockedUiInitialized = false
    private var isUnlocked = false
    private var backgroundedAt = 0L

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && isUnlocked) {
            startCamera()
        } else if (!granted) {
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
            Toast.makeText(this, R.string.csv_saved, Toast.LENGTH_SHORT).show()
        }.onFailure { error ->
            Toast.makeText(
                this,
                getString(R.string.csv_save_failed, error.localizedMessage ?: "bilinmiyor"),
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
        setupLockScreen()

        if (preferences.securityEnabled && preferences.hasPin) {
            showLockScreen(tryBiometric = true)
        } else {
            if (preferences.securityEnabled && !preferences.hasPin) {
                preferences.securityEnabled = false
                preferences.biometricEnabled = false
            }
            unlockApp()
        }
    }

    override fun onStart() {
        super.onStart()
        if (
            ::binding.isInitialized && unlockedUiInitialized && isUnlocked &&
            preferences.securityEnabled && backgroundedAt > 0L &&
            System.currentTimeMillis() - backgroundedAt >= LOCK_TIMEOUT_MS &&
            !isChangingConfigurations
        ) {
            showLockScreen(tryBiometric = preferences.biometricEnabled)
        }
    }

    override fun onResume() {
        super.onResume()
        if (isUnlocked && unlockedUiInitialized) {
            loadRecords()
            loadInsideCount()
        }
    }

    override fun onStop() {
        if (isUnlocked && !isChangingConfigurations) {
            backgroundedAt = System.currentTimeMillis()
        }
        super.onStop()
    }

    private fun setupLockScreen() {
        binding.unlockButton.setOnClickListener { unlockWithPin() }
        binding.lockPinEditText.setOnEditorActionListener { _, _, _ ->
            unlockWithPin()
            true
        }
        binding.biometricUnlockButton.setOnClickListener { showBiometricPrompt() }
    }

    private fun showLockScreen(tryBiometric: Boolean) {
        isUnlocked = false
        analyzer?.close()
        analyzer = null
        cameraProvider?.unbindAll()
        binding.contentContainer.visibility = View.INVISIBLE
        binding.bottomNavigation.visibility = View.GONE
        binding.lockOverlay.visibility = View.VISIBLE
        binding.lockPinEditText.text?.clear()
        binding.lockErrorText.text = ""
        binding.biometricUnlockButton.isVisible =
            preferences.biometricEnabled && canUseBiometrics()
        binding.lockPinEditText.requestFocus()
        if (tryBiometric && binding.biometricUnlockButton.isVisible) {
            binding.lockOverlay.post { showBiometricPrompt() }
        }
    }

    private fun unlockWithPin() {
        val pin = binding.lockPinEditText.text?.toString().orEmpty()
        if (preferences.verifyPin(pin)) {
            unlockApp()
        } else {
            binding.lockErrorText.text = getString(R.string.wrong_pin)
            binding.lockPinEditText.text?.clear()
        }
    }

    private fun unlockApp() {
        isUnlocked = true
        backgroundedAt = 0L
        binding.lockOverlay.visibility = View.GONE
        binding.contentContainer.visibility = View.VISIBLE
        binding.bottomNavigation.visibility = View.VISIBLE

        if (!unlockedUiInitialized) {
            setupNavigation()
            setupCameraMode()
            setupRecordsScreen()
            setupUpdateScreen()
            setupSettingsScreen()
            showPanel(binding.cameraPanel)
            unlockedUiInitialized = true
            loadRecords()
            loadInsideCount()
            requestCameraOrStart()
            if (preferences.automaticUpdateCheck) checkForUpdates(silent = true)
        } else {
            requestCameraOrStart()
            loadRecords()
            loadInsideCount()
        }
    }

    private fun canUseBiometrics(): Boolean {
        return BiometricManager.from(this).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun showBiometricPrompt() {
        if (!preferences.biometricEnabled || !canUseBiometrics()) return
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) {
                    super.onAuthenticationSucceeded(result)
                    unlockApp()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    if (errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                        errorCode != BiometricPrompt.ERROR_USER_CANCELED
                    ) {
                        binding.lockErrorText.text = errString
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    binding.lockErrorText.text = getString(R.string.biometric_failed)
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_title))
            .setSubtitle(getString(R.string.biometric_subtitle))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText(getString(R.string.use_pin))
            .build()
        prompt.authenticate(info)
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

    private fun setupCameraMode() {
        binding.cameraModeGroup.check(
            when (preferences.accessMode) {
                MovementType.ENTRY -> R.id.modeEntryButton
                MovementType.EXIT -> R.id.modeExitButton
                MovementType.OBSERVATION -> R.id.modeObserveButton
            }
        )
        updateModeText()
        binding.cameraModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            preferences.accessMode = when (checkedId) {
                R.id.modeEntryButton -> MovementType.ENTRY
                R.id.modeExitButton -> MovementType.EXIT
                else -> MovementType.OBSERVATION
            }
            updateModeText()
        }
    }

    private fun updateModeText() {
        binding.modeInfoText.text = when (preferences.accessMode) {
            MovementType.ENTRY -> getString(R.string.mode_entry_explanation)
            MovementType.EXIT -> getString(R.string.mode_exit_explanation)
            MovementType.OBSERVATION -> getString(R.string.mode_observe_explanation)
        }
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
        binding.checkUpdateButton.setOnClickListener { checkForUpdates(silent = false) }
        binding.openReleaseButton.setOnClickListener { openWebPage(latestReleaseUrl) }
    }

    private fun setupSettingsScreen() {
        settingUpSettings = true
        binding.confidenceSlider.valueFrom = 0.30f
        binding.confidenceSlider.valueTo = 0.90f
        binding.confidenceSlider.stepSize = 0.05f
        binding.aiEnabledSwitch.isChecked = preferences.aiEnabled
        binding.confidenceSlider.value = preferences.aiThreshold
        binding.confidenceValueText.text = formatThreshold(preferences.aiThreshold)
        binding.autoUpdateSwitch.isChecked = preferences.automaticUpdateCheck
        binding.appLockSwitch.isChecked = preferences.securityEnabled
        binding.biometricSwitch.isChecked = preferences.biometricEnabled
        binding.biometricSwitch.isEnabled = preferences.securityEnabled && canUseBiometrics()
        binding.themeGroup.check(
            when (preferences.themeMode) {
                AppPreferences.ThemeMode.SYSTEM -> R.id.themeSystem
                AppPreferences.ThemeMode.LIGHT -> R.id.themeLight
                AppPreferences.ThemeMode.DARK -> R.id.themeDark
                AppPreferences.ThemeMode.AMOLED -> R.id.themeAmoled
            }
        )
        updateSecurityStatus()
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

        binding.autoUpdateSwitch.setOnCheckedChangeListener { _, checked ->
            if (!settingUpSettings) preferences.automaticUpdateCheck = checked
        }

        binding.appLockSwitch.setOnCheckedChangeListener { _, checked ->
            if (settingUpSettings) return@setOnCheckedChangeListener
            if (checked) {
                if (preferences.hasPin) {
                    preferences.securityEnabled = true
                    binding.biometricSwitch.isEnabled = canUseBiometrics()
                    updateSecurityStatus()
                } else {
                    showSetPinDialog(
                        onSaved = {
                            preferences.securityEnabled = true
                            binding.biometricSwitch.isEnabled = canUseBiometrics()
                            updateSecurityStatus()
                        },
                        onCancelled = {
                            settingUpSettings = true
                            binding.appLockSwitch.isChecked = false
                            settingUpSettings = false
                        }
                    )
                }
            } else {
                preferences.securityEnabled = false
                preferences.biometricEnabled = false
                settingUpSettings = true
                binding.biometricSwitch.isChecked = false
                binding.biometricSwitch.isEnabled = false
                settingUpSettings = false
                updateSecurityStatus()
            }
        }

        binding.setPinButton.setOnClickListener {
            showSetPinDialog(onSaved = {
                preferences.securityEnabled = true
                settingUpSettings = true
                binding.appLockSwitch.isChecked = true
                binding.biometricSwitch.isEnabled = canUseBiometrics()
                settingUpSettings = false
                updateSecurityStatus()
                Toast.makeText(this, R.string.pin_saved, Toast.LENGTH_SHORT).show()
            })
        }

        binding.biometricSwitch.setOnCheckedChangeListener { _, checked ->
            if (settingUpSettings) return@setOnCheckedChangeListener
            if (checked && (!preferences.securityEnabled || !preferences.hasPin)) {
                Toast.makeText(this, R.string.set_pin_first, Toast.LENGTH_SHORT).show()
                settingUpSettings = true
                binding.biometricSwitch.isChecked = false
                settingUpSettings = false
                return@setOnCheckedChangeListener
            }
            if (checked && !canUseBiometrics()) {
                Toast.makeText(this, R.string.biometric_unavailable, Toast.LENGTH_SHORT).show()
                settingUpSettings = true
                binding.biometricSwitch.isChecked = false
                settingUpSettings = false
                return@setOnCheckedChangeListener
            }
            preferences.biometricEnabled = checked
            updateSecurityStatus()
        }
    }

    private fun updateSecurityStatus() {
        binding.securityStatusText.text = when {
            !preferences.securityEnabled -> getString(R.string.security_disabled)
            preferences.biometricEnabled -> getString(R.string.security_pin_biometric)
            else -> getString(R.string.security_pin_only)
        }
    }

    private fun showSetPinDialog(
        onSaved: () -> Unit,
        onCancelled: () -> Unit = {}
    ) {
        val dialogBinding = DialogSetPinBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.set_pin_title)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.cancel) { _, _ -> onCancelled() }
            .setPositiveButton(R.string.save, null)
            .create()

        dialog.setOnCancelListener { onCancelled() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val first = dialogBinding.pinEditText.text?.toString().orEmpty()
                val second = dialogBinding.pinAgainEditText.text?.toString().orEmpty()
                when {
                    !first.matches(Regex("\\d{4,6}")) -> {
                        dialogBinding.pinLayout.error = getString(R.string.pin_length_error)
                    }
                    first != second -> {
                        dialogBinding.pinLayout.error = null
                        dialogBinding.pinAgainLayout.error = getString(R.string.pin_mismatch)
                    }
                    else -> {
                        preferences.setPin(first)
                        dialog.dismiss()
                        onSaved()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun requestCameraOrStart() {
        if (!isUnlocked) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        if (!::binding.isInitialized || !isUnlocked) return
        binding.statusText.text = getString(R.string.camera_starting)
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = runCatching { providerFuture.get() }
                .getOrElse {
                    binding.statusText.text = "Kamera başlatılamadı: ${it.localizedMessage}"
                    return@addListener
                }
            cameraProvider = provider

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
                provider.unbindAll()
                provider.bindToLifecycle(
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
                    listOf(observation.type, observation.color, "AI %$confidence")
                        .filter { it.isNotBlank() }
                        .joinToString(" • ")
                }
            }
        }
    }

    private fun saveDetectedPlate(plate: String, observation: VehicleObservation?) {
        val mode = preferences.accessMode
        databaseExecutor.execute {
            runCatching { database.upsertPlate(plate, observation, mode) }
                .onSuccess { outcome ->
                    runOnUiThread { binding.statusText.text = outcomeMessage(plate, outcome) }
                    loadRecords()
                    loadInsideCount()
                }
                .onFailure { error ->
                    runOnUiThread {
                        binding.statusText.text = "Kayıt hatası: ${error.localizedMessage}"
                    }
                }
        }
    }

    private fun outcomeMessage(plate: String, outcome: DetectionOutcome): String {
        return when (outcome.movementType) {
            MovementType.OBSERVATION -> getString(R.string.saved_plate, plate)
            MovementType.ENTRY -> if (outcome.movementChanged) {
                getString(R.string.entry_saved, plate)
            } else {
                getString(R.string.already_inside, plate)
            }
            MovementType.EXIT -> if (outcome.movementChanged) {
                getString(R.string.exit_saved, plate)
            } else {
                getString(R.string.already_outside, plate)
            }
        }
    }

    private fun loadInsideCount() {
        databaseExecutor.execute {
            val count = runCatching { database.countInside() }.getOrDefault(0)
            runOnUiThread { binding.insideCountText.text = getString(R.string.inside_count, count) }
        }
    }

    private fun loadRecords() {
        if (!unlockedUiInitialized) return
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
                    record.vehicleType,
                    record.category,
                    record.note,
                    if (record.isInside) "içeride" else "dışarıda"
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

    private fun openVehicleProfile(record: PlateRecord) {
        startActivity(
            Intent(this, VehicleProfileActivity::class.java)
                .putExtra(VehicleProfileActivity.EXTRA_RECORD_ID, record.id)
        )
    }

    private fun showVehicleInfoDialog(record: PlateRecord) {
        val dialogBinding = DialogVehicleInfoBinding.inflate(layoutInflater)
        dialogBinding.brandEditText.setText(record.brand)
        dialogBinding.modelEditText.setText(record.model)
        dialogBinding.colorEditText.setText(record.color)
        dialogBinding.categoryEditText.setText(record.category)
        dialogBinding.noteEditText.setText(record.note)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.vehicle_info_title, record.plate))
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val brand = dialogBinding.brandEditText.text?.toString().orEmpty()
                val model = dialogBinding.modelEditText.text?.toString().orEmpty()
                val color = dialogBinding.colorEditText.text?.toString().orEmpty()
                val category = dialogBinding.categoryEditText.text?.toString().orEmpty()
                val note = dialogBinding.noteEditText.text?.toString().orEmpty()
                databaseExecutor.execute {
                    runCatching {
                        database.updateVehicleProfile(
                            record.id,
                            brand,
                            model,
                            color,
                            category,
                            note
                        )
                    }.onSuccess {
                        runOnUiThread {
                            Toast.makeText(this, R.string.vehicle_info_saved, Toast.LENGTH_SHORT)
                                .show()
                        }
                        loadRecords()
                    }.onFailure { error ->
                        runOnUiThread {
                            Toast.makeText(
                                this,
                                getString(
                                    R.string.vehicle_info_save_failed,
                                    error.localizedMessage ?: "bilinmiyor"
                                ),
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
            .setTitle(getString(R.string.delete_plate_title, record.plate))
            .setMessage(R.string.delete_plate_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                databaseExecutor.execute {
                    database.deleteRecord(record.id)
                    runOnUiThread {
                        Toast.makeText(this, R.string.record_deleted, Toast.LENGTH_SHORT).show()
                    }
                    loadRecords()
                    loadInsideCount()
                }
            }
            .show()
    }

    private fun exportCsv() {
        databaseExecutor.execute {
            val records = runCatching { database.getRecent(1000) }.getOrDefault(emptyList())
            if (records.isEmpty()) {
                runOnUiThread {
                    Toast.makeText(this, R.string.no_records_to_export, Toast.LENGTH_SHORT).show()
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
        fun dateOrBlank(value: Long): String = if (value > 0L) dateFormat.format(Date(value)) else ""
        return buildString {
            append('\uFEFF')
            appendLine(
                "Plaka;Durum;Kategori;Araç Türü;Marka;Model;Renk;Not;AI Güveni;Son Giriş;Son Çıkış;Toplam Giriş;İlk Görülme;Son Görülme;Görülme Sayısı"
            )
            records.forEach { record ->
                append(csvCell(record.plate)).append(';')
                append(csvCell(if (record.isInside) "İçeride" else "Dışarıda")).append(';')
                append(csvCell(record.category)).append(';')
                append(csvCell(record.vehicleType)).append(';')
                append(csvCell(record.brand)).append(';')
                append(csvCell(record.model)).append(';')
                append(csvCell(record.color)).append(';')
                append(csvCell(record.note)).append(';')
                append(csvCell(if (record.aiConfidence > 0f) "%.0f%%".format(record.aiConfidence * 100) else ""))
                    .append(';')
                append(csvCell(dateOrBlank(record.lastEntryAt))).append(';')
                append(csvCell(dateOrBlank(record.lastExitAt))).append(';')
                append(record.totalEntries).append(';')
                append(csvCell(dateOrBlank(record.firstSeenAt))).append(';')
                append(csvCell(dateOrBlank(record.lastSeenAt))).append(';')
                append(record.seenCount)
                appendLine()
            }
        }
    }

    private fun csvCell(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    private fun confirmClearRecords() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_all_title)
            .setMessage(R.string.clear_all_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                databaseExecutor.execute {
                    database.clearAll()
                    runOnUiThread {
                        allRecords = emptyList()
                        applyFilter("")
                        binding.insideCountText.text = getString(R.string.inside_count, 0)
                        Toast.makeText(this, R.string.records_deleted, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun checkForUpdates(silent: Boolean) {
        if (!silent) {
            binding.checkUpdateButton.isEnabled = false
            binding.latestVersionText.text = getString(R.string.update_checking)
            binding.releaseNotesText.text = ""
        }

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
                        if (!silent) {
                            binding.latestVersionText.text = getString(
                                R.string.update_check_failed,
                                error.localizedMessage ?: "bilinmiyor"
                            )
                        }
                        binding.checkUpdateButton.isEnabled = true
                    }
                }
        }
    }

    private fun openWebPage(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(this, R.string.cannot_open_link, Toast.LENGTH_SHORT).show()
        }
    }

    private fun currentVersionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "Bilinmiyor"
    }.getOrDefault("Bilinmiyor")

    private fun formatThreshold(value: Float): String =
        getString(R.string.minimum_confidence, (value * 100).toInt())

    override fun onDestroy() {
        analyzer?.close()
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        databaseExecutor.shutdown()
        updateExecutor.shutdown()
        database.close()
        super.onDestroy()
    }

    companion object {
        private const val LOCK_TIMEOUT_MS = 30_000L
    }
}
