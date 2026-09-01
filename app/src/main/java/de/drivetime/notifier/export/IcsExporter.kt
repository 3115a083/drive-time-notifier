package de.drivetime.notifier.export

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

class IcsExporter(private val context: Context) {
    fun create(origin: String, destination: String, start: Long, end: Long): android.net.Uri {
        val dir = File(context.cacheDir, "ics").apply { mkdirs() }
        val file = File(dir, "drive-${System.currentTimeMillis()}.ics")
        file.writeText(content(origin, destination, start, end))
        return FileProvider.getUriForFile(context, context.packageName + ".files", file)
    }

    fun saveToDownloads(origin: String, destination: String, start: Long, end: Long): android.net.Uri {
        val name = "drive-${System.currentTimeMillis()}.ics"
        val uri = context.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/calendar")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/DriveTimeNotifier")
            }
        ) ?: error("ICS-Datei konnte nicht angelegt werden.")
        context.contentResolver.openOutputStream(uri, "w")!!.use {
            it.write(content(origin, destination, start, end).toByteArray(Charsets.UTF_8))
        }
        return uri
    }

    private fun content(origin: String, destination: String, start: Long, end: Long): String {
        val formatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        fun esc(s: String) = s.replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;").replace("\n", "\\n")
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Drive Time Notifier//DE\r\n" +
            "BEGIN:VEVENT\r\nUID:${UUID.randomUUID()}@drive-time-notifier\r\n" +
            "DTSTAMP:${formatter.format(Instant.now())}\r\nDTSTART:${formatter.format(Instant.ofEpochMilli(start))}\r\n" +
            "DTEND:${formatter.format(Instant.ofEpochMilli(end))}\r\nSUMMARY:Fahrt zum Termin\r\n" +
            "LOCATION:${esc(destination)}\r\nDESCRIPTION:${esc("Start: $origin")}\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
    }
}
