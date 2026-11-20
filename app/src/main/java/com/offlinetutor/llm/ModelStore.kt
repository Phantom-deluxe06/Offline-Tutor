package com.offlinetutor.llm

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class ModelSelection(val pte: File, val tokenizer: File) {
    val key get() = pte.absolutePath + "|" + tokenizer.absolutePath
    val name: String get() = pte.nameWithoutExtension
}

/**
 * Model files live in filesDir/models (imported with the system file picker). For dev,
 * files adb-pushed to /sdcard/Android/data/<pkg>/files/models are also picked up.
 */
class ModelStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("models", Context.MODE_PRIVATE)
    val internalDir: File = File(context.filesDir, "models").apply { mkdirs() }
    val externalDir: File? = context.getExternalFilesDir("models")?.apply { mkdirs() }

    private fun dirs() = listOfNotNull(internalDir, externalDir)

    fun listPte(): List<File> = dirs().flatMap { d ->
        d.listFiles { f -> f.isFile && f.name.endsWith(".pte", ignoreCase = true) }?.toList().orEmpty()
    }.sortedBy { it.name }

    fun tokenizer(): File? = dirs().flatMap { d ->
        listOf("tokenizer.model", "tokenizer.bin", "tokenizer.json").map { File(d, it) }
    }.firstOrNull { it.isFile && it.length() > 0 }

    fun setActive(file: File) = prefs.edit().putString(KEY_ACTIVE, file.absolutePath).apply()

    fun activePath(): String? = prefs.getString(KEY_ACTIVE, null)

    fun activeModel(): ModelSelection? {
        val tok = tokenizer() ?: return null
        val chosen = activePath()?.let(::File)?.takeIf { it.isFile }
        // Default to the SpinQuant export (better accuracy) when nothing was picked explicitly.
        val all = listPte()
        val pte = chosen ?: all.firstOrNull { it.name.contains("spinquant", ignoreCase = true) } ?: all.firstOrNull() ?: return null
        return ModelSelection(pte, tok)
    }

    fun delete(file: File): Boolean {
        val ok = file.delete()
        if (activePath() == file.absolutePath) prefs.edit().remove(KEY_ACTIVE).apply()
        return ok
    }

    /** Copies a picked document into filesDir/models. A .pte keeps its name; anything else becomes the tokenizer. */
    suspend fun import(uri: Uri, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        var name = "model.pte"
        var size = -1L
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0) ?: name
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        val target = when {
            name.endsWith(".pte", true) -> File(internalDir, sanitize(name))
            name.endsWith(".json", true) -> File(internalDir, "tokenizer.json")
            else -> File(internalDir, "tokenizer.model")
        }
        if (size > 0 && internalDir.usableSpace < size + HEADROOM_BYTES) {
            throw IOException("Not enough free storage: need ${size / 1_048_576} MB + 200 MB headroom")
        }
        val tmp = File(internalDir, target.name + ".part")
        val input = cr.openInputStream(uri) ?: throw IOException("Cannot open the selected file")
        input.use { ins ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 20)
                var copied = 0L
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    copied += n
                    if (size > 0) onProgress(copied.toFloat() / size)
                }
                out.fd.sync()
            }
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) throw IOException("Could not move file into place")
        if (target.name.endsWith(".pte")) setActive(target)
        target
    }

    private fun sanitize(n: String) = n.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private companion object {
        const val KEY_ACTIVE = "active_pte"
        const val HEADROOM_BYTES = 200L * 1024 * 1024
    }
}
