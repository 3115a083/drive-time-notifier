package de.drivetime.notifier.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

class IcsExporter(private val context: Context) {
    fun create(
        origin: String,
        destination: String,
        start: Long,
        end: Long,
        title: String,
        description: String
    ): Uri {
        val dir = File(context.cacheDir, "ics").apply { mkdirs() }
        val file = File(dir, "drive-${System.currentTimeMillis()}.ics")
        file.writeText(content(origin, destination, start, end, title, description))
        return FileProvider.getUriForFile(context, context.packageName + ".files", file)
    }

    fun saveToDownloads(
        origin: String,
        destination: String,
        start: Long,
        end: Long,
        title: String,
        description: String
    ): Uri {
        val name = "drive-${System.currentTimeMillis()}.ics"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            val dir = File(root, "DriveTimeNotifier").apply { mkdirs() }
            val file = File(dir, name)
            file.writeText(content(origin, destination, start, end, title, description))
            return FileProvider.getUriForFile(context, context.packageName + ".files", file)
        }

        val uri = context.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/calendar")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/DriveTimeNotifier")
            }
        ) ?: error("Could not create ICS file.")
        writeToUri(uri, origin, destination, start, end, title, description)
        return uri
    }

    fun writeToUri(
        uri: Uri,
        origin: String,
        destination: String,
        start: Long,
        end: Long,
        title: String,
        description: String
    ) {
        context.contentResolver.openOutputStream(uri, "w")?.use {
            it.write(content(origin, destination, start, end, title, description).toByteArray(Charsets.UTF_8))
        } ?: error("Could not write ICS file.")
    }

    private fun content(
        origin: String,
        destination: String,
        start: Long,
        end: Long,
        title: String,
        description: String
    ): String {
        val formatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        fun esc(s: String) = s
            .replace("\\", "\\\\")
            .replace(",", "\\,")
            .replace(";", "\\;")
            .replace("\r\n", "\\n")
            .replace("\n", "\\n")
        val body = description.ifBlank { "Start: $origin" }
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Drive Time Notifier//EN\r\n" +
            "BEGIN:VEVENT\r\nUID:${UUID.randomUUID()}@drive-time-notifier\r\n" +
            "DTSTAMP:${formatter.format(Instant.now())}\r\nDTSTART:${formatter.format(Instant.ofEpochMilli(start))}\r\n" +
            "DTEND:${formatter.format(Instant.ofEpochMilli(end))}\r\nSUMMARY:${esc(title)}\r\n" +
            "LOCATION:${esc(destination)}\r\nDESCRIPTION:${esc(body)}\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
    }
}
