package com.offlinetutor.data

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.offlinetutor.domain.Textbook
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 6b Textbook-grounded mode: OCR a photographed page on the phone (ML Kit bundled models: Latin and Devanagari),
 * store its passages in the FTS4 table, and give the quiz prompt the 1–2 passages that best match a topic.
 * Tamil / Bengali OCR (Tesseract in the spec) is not included yet.
 */
class TextbookService(private val context: Context, private val dao: TextbookDao, private val flags: FeatureFlags) {
    enum class Script { LATIN, DEVANAGARI }

    suspend fun ocr(uri: Uri, script: Script): String {
        val image = InputImage.fromFilePath(context, uri)
        val client = when (script) {
            Script.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            Script.DEVANAGARI -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        }
        return try {
            suspendCancellableCoroutine { cont ->
                client.process(image)
                    // ML Kit gives blocks; a blank line between blocks keeps paragraph boundaries for the splitter.
                    .addOnSuccessListener { t -> cont.resume(t.textBlocks.joinToString("\n\n") { it.text }) }
                    .addOnFailureListener { e -> cont.resumeWithException(e) }
            }
        } finally {
            client.close()
        }
    }

    /** Splits [text] into passages and stores them as one page. Returns the number of passages saved. */
    suspend fun addPage(profile: Profile, course: String, text: String): Int {
        val label = "Page · " + SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date())
        val parts = Textbook.passages(text)
        parts.forEach { dao.insert(TextbookPassage(profileId = profile.id.toString(), course = course, page = label, text = it)) }
        return parts.size
    }

    fun observe(profileId: Long) = dao.observe(profileId.toString())

    suspend fun deletePage(profileId: Long, page: String) = dao.deletePage(profileId.toString(), page)

    /** The 1–2 best-matching passages for [topic], trimmed for the prompt budget, or null (toggle off / no match). */
    suspend fun grounding(profileId: Long, topic: String): String? {
        if (!flags.isOn(Feature.TEXTBOOK_MODE)) return null
        val q = Textbook.matchQuery(topic) ?: return null
        return runCatching { dao.search(profileId.toString(), q, 2) }.getOrDefault(emptyList())
            .takeIf { it.isNotEmpty() }?.joinToString("\n\n") { it.text.take(450) }
    }
}
