package com.folio.reader

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var bookList: LinearLayout
    private lateinit var emptyView: View
    private lateinit var progress: View
    private lateinit var toolbar: MaterialToolbar

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importUris(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bookList = findViewById(R.id.bookList)
        emptyView = findViewById(R.id.emptyView)
        progress = findViewById(R.id.progress)
        toolbar = findViewById(R.id.toolbar)
        findViewById<View>(R.id.fabAdd).setOnClickListener { openPicker() }
        findViewById<View>(R.id.emptyAdd).setOnClickListener { openPicker() }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_VIEW) {
            i.data?.let { importUris(listOf(it)) }
            setIntent(Intent(this, MainActivity::class.java))
        }
    }

    private fun openPicker() {
        picker.launch(arrayOf("application/epub+zip", "application/octet-stream"))
    }

    private fun importUris(uris: List<Uri>) {
        progress.isVisible = true
        Thread {
            val ok = ArrayList<BookRecord>()
            val errors = ArrayList<String>()
            for (u in uris) {
                try {
                    ok.add(BookImporter.import(this, u))
                } catch (e: Exception) {
                    errors.add((Util.displayName(this, u) ?: "File") + ": " + (e.message ?: "unknown error"))
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                progress.isVisible = false
                refresh()
                if (errors.isNotEmpty()) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(if (errors.size == 1) "Couldn't add book" else "Some books couldn't be added")
                        .setMessage(errors.joinToString("\n\n"))
                        .setPositiveButton("OK", null)
                        .show()
                } else if (ok.size == 1) {
                    openBook(ok[0])
                }
            }
        }.start()
    }

    private fun openBook(rec: BookRecord) {
        startActivity(Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_ID, rec.id))
    }

    private fun refresh() {
        val books = Library.all(this).sortedByDescending { maxOf(it.lastOpened, it.addedAt) }
        bookList.removeAllViews()
        emptyView.isVisible = books.isEmpty()
        toolbar.subtitle = if (books.isEmpty()) null else "${books.size} book${if (books.size == 1) "" else "s"}"
        for (b in books) {
            val row = layoutInflater.inflate(R.layout.item_book, bookList, false)
            row.findViewById<TextView>(R.id.title).text = b.title
            row.findViewById<TextView>(R.id.author).apply {
                text = b.author
                isVisible = b.author.isNotBlank()
            }
            val pct = (b.progress * 100).roundToInt()
            row.findViewById<TextView>(R.id.progressText).text =
                if (b.lastOpened == 0L) "New" else if (pct >= 100) "Finished" else "$pct% read"
            row.findViewById<LinearProgressIndicator>(R.id.progressBar).apply {
                max = 1000
                progress = (b.progress * 1000).roundToInt()
                isVisible = b.lastOpened != 0L
            }
            val cover = row.findViewById<ImageView>(R.id.cover)
            val letter = row.findViewById<TextView>(R.id.coverLetter)
            val coverFile = Library.coverFile(this, b.id)
            val bmp = if (coverFile.exists()) BitmapFactory.decodeFile(coverFile.absolutePath) else null
            if (bmp != null) {
                cover.setImageBitmap(bmp)
                letter.text = ""
            } else {
                cover.setImageDrawable(null)
                letter.text = b.title.trim().take(1).uppercase()
            }
            row.setOnClickListener { openBook(b) }
            row.setOnLongClickListener {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Remove book?")
                    .setMessage("Remove “${b.title}” from your library? Its bookmarks and reading progress will be deleted.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Remove") { _, _ ->
                        Library.remove(this, b.id)
                        refresh()
                    }
                    .show()
                true
            }
            bookList.addView(row)
        }
    }
}
