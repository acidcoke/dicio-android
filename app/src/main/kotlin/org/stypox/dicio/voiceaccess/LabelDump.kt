package org.stypox.dicio.voiceaccess

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Collects the label dumps of a voice access session and writes them to a file when the session
 * ends, so a screen that shows unexpected chips can be reported without needing `adb logcat`.
 *
 * Diagnostics only: [ClickableNodeScanner] feeds this in debug builds, and the file lands in the
 * device's Downloads folder where a file manager or share sheet can pick it up.
 */
object LabelDump {

    private const val TAG = "DicioLabels"
    private const val FILE_NAME = "dicio-labels.txt"
    // a session dumps every time the label set changes, several times a second on a busy screen, so
    // keep the buffer bounded and drop the oldest entries rather than growing without limit
    private const val MAX_CHARS = 400_000

    private val buffer = StringBuilder()

    /** Adds one dump, tagged with the time so several screens can be told apart in the file. */
    fun append(dump: String) {
        val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date())
        synchronized(buffer) {
            buffer.append(stamp).append(' ').append(dump)
            if (buffer.length > MAX_CHARS) buffer.delete(0, buffer.length - MAX_CHARS)
        }
    }

    /**
     * Writes everything collected so far to Downloads and clears the buffer, telling the user where
     * it went. Does nothing when no dump was collected, so ending a session without labels showing
     * does not litter the folder.
     */
    fun writeToDownloads(context: Context) {
        val text = synchronized(buffer) {
            if (buffer.isEmpty()) return
            buffer.toString().also { buffer.setLength(0) }
        }
        val target = try {
            write(context, text)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to write label dump", t)
            null
        }
        val message = if (target == null) "Label dump failed, see logcat" else "Label dump: $target"
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    /** Returns a human-readable location of the file that was written. */
    private fun write(context: Context, text: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // MediaStore needs no permission here, and a repeated display name is turned into
            // "dicio-labels (1).txt" and so on, so each session keeps its own file
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore did not accept the file")
            resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                ?: throw IllegalStateException("Could not open $uri")
            return "Downloads/$FILE_NAME"
        }
        // before Android 10 writing to public Downloads needs a runtime permission the accessibility
        // service does not hold, so fall back to the app's own external directory
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, FILE_NAME)
        file.writeText(text)
        return file.absolutePath
    }
}
