package com.senjumomo.cards.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.senjumomo.cards.R
import com.senjumomo.cards.data.Card
import com.senjumomo.cards.data.CardDatabase
import com.senjumomo.cards.data.CardImages
import com.senjumomo.cards.data.CardLookup
import com.senjumomo.cards.data.Printing
import com.senjumomo.cards.data.SetCode
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Reads the set code printed on a card with the camera (on-device text recognition), looks it up,
 * and adds the chosen rarity to the collection or wishlist. Stays open for scanning more cards.
 */
class ScanActivity : AppCompatActivity() {

    private val dao by lazy { CardDatabase.get(this).cards() }
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private lateinit var analysisExecutor: ExecutorService

    private lateinit var root: View
    private lateinit var statusView: View
    private lateinit var resultView: View
    private lateinit var status: TextView
    private lateinit var spinner: CircularProgressIndicator
    private lateinit var rarities: ChipGroup
    private lateinit var firstEdition: MaterialSwitch
    private lateinit var owned: TextView

    /** Only look for codes while true; paused while a lookup or result is showing. */
    @Volatile private var scanning = true
    private var candidate: String? = null
    private var candidateHits = 0

    private var printings: List<Printing> = emptyList()
    private var cards: List<Card> = emptyList()
    private var usdRate: Double? = null
    private var currency = "ZAR"

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else showNoCamera()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)
        analysisExecutor = Executors.newSingleThreadExecutor()

        root = findViewById(R.id.root)
        statusView = findViewById(R.id.statusView)
        resultView = findViewById(R.id.resultView)
        status = findViewById(R.id.status)
        spinner = findViewById(R.id.spinner)
        rarities = findViewById(R.id.rarities)
        firstEdition = findViewById(R.id.firstEdition)
        owned = findViewById(R.id.resultOwned)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        val panel = findViewById<View>(R.id.panel)
        val margin = (panel.layoutParams as android.view.ViewGroup.MarginLayoutParams).bottomMargin
        ViewCompat.setOnApplyWindowInsetsListener(panel) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> { bottomMargin = margin + bottom }
            insets
        }

        findViewById<MaterialButton>(R.id.typeCode).setOnClickListener { askForCode() }
        findViewById<MaterialButton>(R.id.scanAgain).setOnClickListener { resume() }
        findViewById<MaterialButton>(R.id.addCollection).setOnClickListener { add(wishlist = false) }
        findViewById<MaterialButton>(R.id.addWishlist).setOnClickListener { add(wishlist = true) }
        rarities.setOnCheckedStateChangeListener { _, _ -> updateOwned() }
        firstEdition.setOnCheckedChangeListener { _, _ -> updateOwned() }

        lifecycleScope.launch {
            cards = dao.all()
            currency = cards.firstOrNull()?.currency ?: "ZAR"
            usdRate = CardLookup.usdRate(currency)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
        recognizer.close()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = findViewById<PreviewView>(R.id.preview).surfaceProvider
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analysisExecutor, ::analyze) }
            provider.unbindAll()
            runCatching {
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }.onFailure { showNoCamera() }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (!scanning || media == null) { proxy.close(); return }
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        recognizer.process(image)
            .addOnSuccessListener { text ->
                // Same code in two frames in a row = a confident read.
                val code = SetCode.find(text.text)
                if (code != null && scanning) {
                    if (code == candidate) candidateHits++ else { candidate = code; candidateHits = 1 }
                    if (candidateHits >= 2) {
                        scanning = false
                        lookup(code)
                    }
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun lookup(code: String) {
        scanning = false
        showStatus("Found $code, looking it up…", busy = true)
        lifecycleScope.launch {
            val result = runCatching { CardLookup.byCode(code) }
            val found = result.getOrNull().orEmpty()
            when {
                result.isFailure -> retryLater("No connection. Check your internet and try again.")
                found.isEmpty() -> retryLater("Couldn't find $code. Try again, or type the code.")
                else -> showResult(found)
            }
        }
    }

    private fun showResult(found: List<Printing>) {
        printings = found
        val first = found.first()
        statusView.visibility = View.GONE
        resultView.visibility = View.VISIBLE
        findViewById<TextView>(R.id.resultName).text = first.name
        findViewById<TextView>(R.id.resultMeta).text = "${first.code} · ${first.setName}"
        CardImages.load(findViewById<ImageView>(R.id.resultImage), first.imageUrl)

        rarities.removeAllViews()
        found.forEachIndexed { i, p ->
            val price = p.priceUsd?.let { usd -> usdRate?.let { Money.format((usd * it * 100).toLong(), currency) } }
            rarities.addView(Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
                id = View.generateViewId()
                text = if (price != null) "${p.rarity} · $price" else p.rarity
                isCheckable = true
                isChecked = i == 0
                tag = i
            })
        }
        updateOwned()
    }

    private fun selected(): Printing? {
        val chip = rarities.findViewById<Chip>(rarities.checkedChipId) ?: return printings.firstOrNull()
        return printings.getOrNull(chip.tag as Int)
    }

    private fun updateOwned() {
        val p = selected() ?: return
        val rarity = CardLookup.rarityLabel(p.rarity, firstEdition.isChecked)
        val have = CardLookup.findOwned(cards, p.code, rarity, wishlist = false)
        val wanted = CardLookup.findOwned(cards, p.code, rarity, wishlist = true)
        owned.text = when {
            have != null -> "You own ×${have.quantity}"
            wanted != null -> "On your wishlist"
            else -> "Not in your collection yet"
        }
    }

    private fun add(wishlist: Boolean) {
        val p = selected() ?: return
        lifecycleScope.launch {
            val card = CardLookup.toCard(p, firstEdition.isChecked, wishlist, cards, usdRate, currency)
            dao.upsert(card)
            cards = dao.all()
            val where = if (wishlist) "wishlist" else "collection"
            val msg = if (card.quantity > 1) "${card.name}: now ×${card.quantity}" else "Added ${card.name} to your $where"
            Snackbar.make(root, msg, Snackbar.LENGTH_SHORT).setAnchorView(R.id.panel).show()
            resume()
        }
    }

    private fun askForCode() {
        scanning = false
        val layout = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = "Set code, e.g. MAMA-EN081"
            setPadding(48, 16, 48, 0)
        }
        val input = TextInputEditText(layout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            maxLines = 1
        }
        layout.addView(input)
        MaterialAlertDialogBuilder(this)
            .setTitle("Type the set code")
            .setMessage("It's printed just below the artwork, on the right.")
            .setView(layout)
            .setNegativeButton("Cancel") { _, _ -> resume() }
            .setPositiveButton("Look up") { _, _ ->
                val code = SetCode.find(input.text.toString()) ?: input.text.toString().trim().uppercase()
                if (code.isNotEmpty()) lookup(code) else resume()
            }
            .setOnCancelListener { resume() }
            .show()
    }

    private fun retryLater(message: String) {
        showStatus(message, busy = false)
        root.postDelayed({ if (resultView.visibility != View.VISIBLE) resume() }, 2500)
    }

    private fun resume() {
        candidate = null
        candidateHits = 0
        resultView.visibility = View.GONE
        showStatus("Looking for a set code…", busy = true)
        scanning = true
    }

    private fun showStatus(text: String, busy: Boolean) {
        statusView.visibility = View.VISIBLE
        status.text = text
        spinner.visibility = if (busy) View.VISIBLE else View.INVISIBLE
    }

    private fun showNoCamera() {
        scanning = false
        showStatus("Camera not available. You can still type the code.", busy = false)
    }
}
