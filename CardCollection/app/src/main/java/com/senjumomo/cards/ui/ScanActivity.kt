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
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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
    /** Codes read in the last few frames: one seen twice is a confident read (misreads rarely repeat). */
    private val recent = ArrayDeque<String?>()
    private var camera: Camera? = null
    /** What the live hint says now, so it's only updated when it changes. */
    private var hint = ""

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
            val previewView = findViewById<PreviewView>(R.id.preview)
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            // Set codes are tiny (about 1.5 mm): the default 640×480 analysis frames leave the letters
            // too few pixels to read, so ask for a full-HD feed.
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(android.util.Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                    ).build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analysisExecutor, ::analyze) }
            provider.unbindAll()
            runCatching {
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }.onFailure { showNoCamera() }
            // Tap to focus there (close-ups of the code need it on some phones).
            previewView.setOnTouchListener { v, e ->
                if (e.action == android.view.MotionEvent.ACTION_UP) {
                    val point = previewView.meteringPointFactory.createPoint(e.x, e.y)
                    camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                    v.performClick()
                }
                true
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (!scanning || media == null) { proxy.close(); return }
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        recognizer.process(image)
            .addOnSuccessListener { text ->
                if (!scanning) return@addOnSuccessListener
                // Look line by line too, so a code isn't glued to neighbouring text.
                val code = SetCode.find(text.text)
                    ?: text.textBlocks.flatMap { it.lines }.firstNotNullOfOrNull { SetCode.find(it.text) }
                recent.addLast(code)
                while (recent.size > 6) recent.removeFirst()
                if (code != null && recent.count { it == code } >= 2) {
                    scanning = false
                    lookup(code)
                    return@addOnSuccessListener
                }
                showHint(
                    when {
                        code != null -> "Reading $code… hold still"
                        text.text.isBlank() -> "Looking for a set code…"
                        else -> "I can see text but no set code yet: move closer to the code under the artwork (tap to focus)"
                    }
                )
            }
            .addOnFailureListener { e ->
                // Never fail silently: say why scanning isn't working.
                showHint("Text recognition isn't working on this phone: ${e.message ?: e.javaClass.simpleName}. You can still type the code.", busy = false)
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
            com.senjumomo.cards.data.PriceRefresher.repriceSoon(dao, card)
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

    private fun showHint(text: String, busy: Boolean = true) {
        if (text == hint || !scanning) return
        hint = text
        showStatus(text, busy)
    }

    private fun resume() {
        recent.clear()
        hint = ""
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
