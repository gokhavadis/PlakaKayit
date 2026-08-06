package com.ogul.plakakayit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.ogul.plakakayit.capture.CaptureEntry
import com.ogul.plakakayit.capture.CaptureMode
import com.ogul.plakakayit.capture.CaptureStorage
import com.ogul.plakakayit.capture.CaptureType
import com.ogul.plakakayit.ml.VehicleDetector
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class CaptureGalleryActivity : AppCompatActivity() {
    private lateinit var storage: CaptureStorage
    private lateinit var adapter: CaptureAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var summaryText: TextView
    private lateinit var searchEditText: EditText
    private lateinit var modeSpinner: Spinner
    private lateinit var typeSpinner: Spinner
    private lateinit var favoriteCheck: CheckBox

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var allEntries: List<CaptureEntry> = emptyList()
    private var filteredEntries: List<CaptureEntry> = emptyList()
    private var pendingExport: List<CaptureEntry> = emptyList()

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val entries = pendingExport
        pendingExport = emptyList()
        if (uri == null || entries.isEmpty()) return@registerForActivityResult
        worker.execute {
            runCatching {
                contentResolver.openOutputStream(uri)?.use { output ->
                    storage.exportZip(entries, output)
                } ?: error("Dışa aktarma dosyası açılamadı")
            }.onSuccess {
                runOnUiThread {
                    Toast.makeText(this, "Olay paketi kaydedildi", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { error ->
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Dışa aktarma başarısız: ${error.localizedMessage ?: "bilinmiyor"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        storage = CaptureStorage(applicationContext)
        adapter = CaptureAdapter(::showCaptureDetail)
        setContentView(createContentView())
        setupFilters()
        loadEntries()
    }

    override fun onResume() {
        super.onResume()
        if (::storage.isInitialized) loadEntries()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun createContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp, 10.dp, 12.dp, 8.dp)
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        toolbar.addView(MaterialButton(this).apply {
            text = "←"
            contentDescription = "Geri"
            minWidth = 48.dp
            setOnClickListener { finish() }
        })
        toolbar.addView(TextView(this).apply {
            text = "Fotoğraf ve Olay Merkezi"
            textSize = 21f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(8.dp, 0, 8.dp, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        toolbar.addView(MaterialButton(this).apply {
            text = "ZIP"
            setOnClickListener { exportCurrentList() }
        })
        root.addView(toolbar)

        searchEditText = EditText(this).apply {
            hint = "Plaka, marka, model, renk veya not ara"
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            setPadding(14.dp, 10.dp, 14.dp, 10.dp)
        }
        root.addView(
            searchEditText,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 8.dp
            }
        )

        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        modeSpinner = Spinner(this)
        typeSpinner = Spinner(this)
        favoriteCheck = CheckBox(this).apply { text = "Favori" }
        filterRow.addView(modeSpinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        filterRow.addView(typeSpinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        filterRow.addView(favoriteCheck)
        root.addView(filterRow)

        summaryText = TextView(this).apply {
            textSize = 13f
            setPadding(4.dp, 6.dp, 4.dp, 6.dp)
        }
        root.addView(summaryText)

        val content = FrameLayout(this)
        recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@CaptureGalleryActivity)
            adapter = this@CaptureGalleryActivity.adapter
            clipToPadding = false
            setPadding(0, 0, 0, 16.dp)
        }
        emptyText = TextView(this).apply {
            text = "Henüz fotoğraflı kayıt yok"
            textSize = 16f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        content.addView(
            recyclerView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        content.addView(
            emptyText,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun setupFilters() {
        modeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            listOf("Tüm modlar", "Park", "Aktif sürüş")
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        typeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            listOf("Tüm yakalamalar", "Otomatik", "Manuel")
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        searchEditText.doAfterTextChanged { applyFilters() }
        modeSpinner.onItemSelectedListener = SimpleItemSelectedListener { applyFilters() }
        typeSpinner.onItemSelectedListener = SimpleItemSelectedListener { applyFilters() }
        favoriteCheck.setOnCheckedChangeListener { _, _ -> applyFilters() }
    }

    private fun loadEntries() {
        worker.execute {
            val loaded = runCatching { storage.loadAll() }.getOrElse { emptyList() }
            runOnUiThread {
                allEntries = loaded
                applyFilters()
            }
        }
    }

    private fun applyFilters() {
        if (!::adapter.isInitialized) return
        val query = searchEditText.text?.toString().orEmpty().trim().lowercase(Locale.ROOT)
        filteredEntries = allEntries.filter { entry ->
            val modeMatches = when (modeSpinner.selectedItemPosition) {
                1 -> entry.captureMode == CaptureMode.PARK
                2 -> entry.captureMode == CaptureMode.DRIVE
                else -> true
            }
            val typeMatches = when (typeSpinner.selectedItemPosition) {
                1 -> entry.captureType == CaptureType.AUTO
                2 -> entry.captureType == CaptureType.MANUAL
                else -> true
            }
            val favoriteMatches = !favoriteCheck.isChecked || entry.favorite
            val haystack = listOf(
                entry.displayPlate,
                entry.vehicleType.orEmpty(),
                entry.vehicleColor.orEmpty(),
                entry.manualBrand,
                entry.manualModel,
                entry.note
            ).joinToString(" ").lowercase(Locale.ROOT)
            modeMatches && typeMatches && favoriteMatches &&
                (query.isBlank() || haystack.contains(query))
        }
        adapter.submitList(filteredEntries)
        recyclerView.visibility = if (filteredEntries.isEmpty()) View.GONE else View.VISIBLE
        emptyText.visibility = if (filteredEntries.isEmpty()) View.VISIBLE else View.GONE
        val bytes = storage.totalBytes(filteredEntries)
        summaryText.text = "${filteredEntries.size} kayıt • ${formatBytes(bytes)}"
    }

    private fun exportCurrentList() {
        if (filteredEntries.isEmpty()) {
            Toast.makeText(this, "Dışa aktarılacak kayıt yok", Toast.LENGTH_SHORT).show()
            return
        }
        pendingExport = filteredEntries.toList()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        exportLauncher.launch("PlakaKayit-V34-$stamp.zip")
    }

    private fun showCaptureDetail(entry: CaptureEntry) {
        val scroll = android.widget.ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 8.dp, 16.dp, 16.dp)
        }
        scroll.addView(content)

        val fullImage = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.rgb(25, 25, 25))
            setImageBitmap(decodeSampled(entry.fullImagePath, 1600, 1200))
            setOnClickListener { showZoomedImage(entry.fullImagePath) }
        }
        content.addView(fullImage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 280.dp))

        entry.plateImagePath?.let { path ->
            content.addView(TextView(this).apply {
                text = "Plaka yakın planı"
                textSize = 14f
                setPadding(0, 10.dp, 0, 4.dp)
            })
            content.addView(ImageView(this).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.rgb(35, 35, 35))
                setImageBitmap(decodeSampled(path, 1000, 400))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 120.dp))
        }

        val date = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(entry.timestamp))
        val analysis = buildString {
            append("Tarih: $date\n")
            append("Mod: ${if (entry.captureMode == CaptureMode.PARK) "Park" else "Aktif sürüş"}\n")
            append("Yakalama: ${if (entry.captureType == CaptureType.AUTO) "Otomatik" else "Manuel"}\n")
            append("Araç türü: ${entry.vehicleType ?: "Bilinmiyor"}\n")
            append("Renk: ${entry.vehicleColor ?: "Bilinmiyor"}\n")
            entry.vehicleConfidence?.let { append("Analiz güveni: %${(it * 100).roundToInt()}\n") }
            append("SHA-256: ${entry.sha256 ?: "Hesaplanmadı"}")
        }
        content.addView(TextView(this).apply {
            text = analysis
            textSize = 14f
            setPadding(0, 12.dp, 0, 8.dp)
            setTextIsSelectable(true)
        })

        val plateInput = labeledEditText(content, "Düzeltilmiş plaka", entry.correctedPlate ?: entry.plate.orEmpty()).apply {
            filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(16))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        val brandInput = labeledEditText(content, "Marka (manuel)", entry.manualBrand)
        val modelInput = labeledEditText(content, "Model (manuel)", entry.manualModel)
        val noteInput = labeledEditText(content, "Not", entry.note).apply {
            minLines = 3
            maxLines = 6
            gravity = Gravity.TOP
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(entry.displayPlate)
            .setView(scroll)
            .create()

        content.addView(MaterialButton(this).apply {
            text = "KAYDET"
            setOnClickListener {
                worker.execute {
                    storage.updateUserData(
                        id = entry.id,
                        correctedPlate = plateInput.text?.toString(),
                        note = noteInput.text?.toString().orEmpty(),
                        favorite = entry.favorite,
                        manualBrand = brandInput.text?.toString().orEmpty(),
                        manualModel = modelInput.text?.toString().orEmpty()
                    )
                    runOnUiThread {
                        dialog.dismiss()
                        loadEntries()
                    }
                }
            }
        })
        content.addView(MaterialButton(this).apply {
            text = if (entry.favorite) "FAVORİDEN ÇIKAR" else "FAVORİYE EKLE"
            setOnClickListener {
                worker.execute {
                    storage.updateUserData(
                        id = entry.id,
                        correctedPlate = plateInput.text?.toString(),
                        note = noteInput.text?.toString().orEmpty(),
                        favorite = !entry.favorite,
                        manualBrand = brandInput.text?.toString().orEmpty(),
                        manualModel = modelInput.text?.toString().orEmpty()
                    )
                    runOnUiThread {
                        dialog.dismiss()
                        loadEntries()
                    }
                }
            }
        })
        content.addView(MaterialButton(this).apply {
            text = "ARAÇ TÜRÜ VE RENGİ YENİDEN ANALİZ ET"
            setOnClickListener {
                isEnabled = false
                text = "ANALİZ EDİLİYOR…"
                reanalyze(entry, dialog)
            }
        })
        content.addView(MaterialButton(this).apply {
            text = "SHA-256 HESAPLA"
            setOnClickListener {
                isEnabled = false
                worker.execute {
                    val result = runCatching { storage.ensureSha256(entry) }
                    runOnUiThread {
                        result.onSuccess { value ->
                            Toast.makeText(this@CaptureGalleryActivity, value, Toast.LENGTH_LONG).show()
                            dialog.dismiss()
                            loadEntries()
                        }.onFailure { error ->
                            isEnabled = true
                            Toast.makeText(
                                this@CaptureGalleryActivity,
                                "Hash hesaplanamadı: ${error.localizedMessage}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        })
        content.addView(MaterialButton(this).apply {
            text = "KAYDI SİL"
            setOnClickListener {
                AlertDialog.Builder(this@CaptureGalleryActivity)
                    .setTitle("Kaydı sil")
                    .setMessage("Fotoğraf, plaka kırpımı ve kayıt bilgileri kalıcı olarak silinecek.")
                    .setNegativeButton("Vazgeç", null)
                    .setPositiveButton("Sil") { _, _ ->
                        worker.execute {
                            storage.delete(entry)
                            runOnUiThread {
                                dialog.dismiss()
                                loadEntries()
                            }
                        }
                    }
                    .show()
            }
        })
        dialog.show()
    }

    private fun reanalyze(entry: CaptureEntry, dialog: AlertDialog) {
        worker.execute {
            val result = runCatching {
                val bitmap = decodeSampled(entry.fullImagePath, 1600, 1200)
                    ?: error("Fotoğraf açılamadı")
                val detector = VehicleDetector(applicationContext, 0.35f)
                try {
                    detector.detect(bitmap) ?: error("Fotoğrafta desteklenen araç bulunamadı")
                } finally {
                    detector.close()
                    bitmap.recycle()
                }
            }
            result.onSuccess { observation -> storage.updateAnalysis(entry.id, observation) }
            runOnUiThread {
                result.onSuccess { observation ->
                    Toast.makeText(
                        this,
                        "${observation.type} • ${observation.color}",
                        Toast.LENGTH_SHORT
                    ).show()
                    dialog.dismiss()
                    loadEntries()
                }.onFailure { error ->
                    Toast.makeText(
                        this,
                        "Analiz yapılamadı: ${error.localizedMessage ?: "bilinmiyor"}",
                        Toast.LENGTH_LONG
                    ).show()
                    dialog.dismiss()
                }
            }
        }
    }

    private fun showZoomedImage(path: String) {
        val image = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            setImageBitmap(decodeSampled(path, 2400, 2400))
            setPadding(4.dp, 4.dp, 4.dp, 4.dp)
        }
        AlertDialog.Builder(this)
            .setView(image)
            .setPositiveButton("Kapat", null)
            .show()
    }

    private fun labeledEditText(parent: LinearLayout, label: String, value: String): EditText {
        parent.addView(TextView(this).apply {
            text = label
            textSize = 13f
            setPadding(0, 8.dp, 0, 2.dp)
        })
        return EditText(this).apply {
            setText(value)
            setPadding(10.dp, 8.dp, 10.dp, 8.dp)
            parent.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).roundToInt()
}

private class CaptureAdapter(
    private val onClick: (CaptureEntry) -> Unit
) : RecyclerView.Adapter<CaptureAdapter.Holder>() {
    private var items: List<CaptureEntry> = emptyList()

    fun submitList(value: List<CaptureEntry>) {
        items = value
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val card = MaterialCardView(context).apply {
            radius = context.dp(14).toFloat()
            cardElevation = context.dp(2).toFloat()
            useCompatPadding = true
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, context.dp(4), 0, context.dp(4))
            }
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(8), context.dp(8), context.dp(8), context.dp(8))
        }
        val thumbnail = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.rgb(35, 35, 35))
        }
        row.addView(thumbnail, LinearLayout.LayoutParams(context.dp(112), context.dp(84)))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(10), 0, context.dp(4), 0)
        }
        val title = TextView(context).apply {
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val subtitle = TextView(context).apply { textSize = 13f }
        val detail = TextView(context).apply { textSize = 12f }
        texts.addView(title)
        texts.addView(subtitle)
        texts.addView(detail)
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val star = TextView(context).apply {
            textSize = 24f
            gravity = Gravity.CENTER
        }
        row.addView(star, LinearLayout.LayoutParams(context.dp(40), context.dp(56)))
        card.addView(row)
        return Holder(card, thumbnail, title, subtitle, detail, star)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class Holder(
        itemView: View,
        private val thumbnail: ImageView,
        private val title: TextView,
        private val subtitle: TextView,
        private val detail: TextView,
        private val star: TextView
    ) : RecyclerView.ViewHolder(itemView) {
        fun bind(entry: CaptureEntry) {
            thumbnail.setImageBitmap(decodeSampled(entry.fullImagePath, 360, 260))
            title.text = entry.displayPlate
            val date = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(entry.timestamp))
            subtitle.text = "$date • ${if (entry.captureMode == CaptureMode.PARK) "Park" else "Aktif sürüş"}"
            detail.text = listOfNotNull(
                entry.vehicleType,
                entry.vehicleColor,
                listOf(entry.manualBrand, entry.manualModel).filter { it.isNotBlank() }.joinToString(" ").takeIf { it.isNotBlank() },
                if (entry.captureType == CaptureType.AUTO) "Otomatik" else "Manuel"
            ).joinToString(" • ")
            star.text = if (entry.favorite) "★" else "☆"
            itemView.setOnClickListener { onClick(entry) }
        }
    }
}

private class SimpleItemSelectedListener(
    private val onSelected: () -> Unit
) : android.widget.AdapterView.OnItemSelectedListener {
    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
        onSelected()
    }

    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
}

private fun android.content.Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).roundToInt()

private fun decodeSampled(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
    val file = File(path)
    if (!file.exists()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / sample > reqWidth * 2 || bounds.outHeight / sample > reqHeight * 2) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample.coerceAtLeast(1)
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return BitmapFactory.decodeFile(path, options)
}
