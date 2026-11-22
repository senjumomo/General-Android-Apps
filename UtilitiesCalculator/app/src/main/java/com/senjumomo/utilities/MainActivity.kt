package com.senjumomo.utilities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {

    private lateinit var screen: BillScreen
    private lateinit var root: View

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)

        // Keep content clear of the navigation bar and the keyboard.
        val content = findViewById<View>(R.id.content)
        val basePadding = content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val bottom = maxOf(
                insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom,
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom,
            )
            v.updatePadding(bottom = basePadding + bottom)
            insets
        }

        screen = BillScreen(root, getSharedPreferences("utilities", Context.MODE_PRIVATE))

        findViewById<MaterialToolbar>(R.id.toolbar).setOnMenuItemClickListener {
            if (it.itemId == R.id.action_new_month) confirmNewMonth()
            true
        }
        findViewById<MaterialButton>(R.id.copy).setOnClickListener { copyMessage() }
        findViewById<MaterialButton>(R.id.share).setOnClickListener { shareMessage() }
    }

    override fun onPause() {
        super.onPause()
        screen.save()
    }

    private fun copyMessage() {
        val text = screen.message ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Utilities", text))
        Snackbar.make(root, "Copied, ready to paste", Snackbar.LENGTH_SHORT).show()
    }

    private fun shareMessage() {
        val text = screen.message ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "Send to landlord"))
    }

    private fun confirmNewMonth() {
        MaterialAlertDialogBuilder(this)
            .setIcon(R.drawable.ic_new_month)
            .setTitle("Start a new month?")
            .setMessage(
                "This month's water reading becomes last month's. " +
                    "Debt and tokens are cleared; prices are kept."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Start") { _, _ ->
                val msg = if (screen.startNewMonth()) "Ready for next month"
                else "Enter this month's water reading first"
                Snackbar.make(root, msg, Snackbar.LENGTH_SHORT).show()
            }
            .show()
    }
}
