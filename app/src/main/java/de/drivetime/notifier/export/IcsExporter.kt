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
import de.drivetime.notifier.calendar.DriveEntryIdentity

class IcsExporter(private val context: Context) {
    fun create(
        origin: String,
        destination: String,
        start: Long,
        end: Long,
        title: String,
        description: String,
        appointmentMillis: Long? = null,
        calculatedAtMillis: Long = System.currentTimeMillis()
    ): Uri {
        val dir = File(context.cacheDir, "ics").apply { mkdirs() }
        val file = File(dir, "drive-${System.currentTimeMillis()}.ics")
        file.writeText(content(origin, destination, start, end, title, description, appointmentMillis, calculatedAtMillis))
        return FileProvider.getUriForFile(context, context.packageName + ".files", file)
    }

    fun saveToDownloads(
        origin: String,
        destination: String,
        start: Long,
        end: Long,
        title: String,
        description: String,
        appointmentMillis: Long? = null,
        calculatedAtMillis: Long = System.currentTimeMillis()
    ): Uri {
        val name = "drive-${System.currentTimeMillis()}.ics"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            val dir = File(root, "DriveTimeNotifier").apply { mkdirs() }
            val file = File(dir, name)
            file.writeText(content(origin, destination, start, end, title, description, appointmentMillis, calculatedAtMillis))
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
        writeToUri(uri, origin, destination, start, end, title, description, appointmentMillis, calculatedAtMillis)
        return uri
    }

    fun writeToUri(
        uri: Uri,
        origin: String,
        destination: String,
        start: Long,
        end: Long,
        title: String,
        description: String,
        appointmentMillis: Long? = null,
        calculatedAtMillis: Long = System.currentTimeMillis()
    ) {
        context.contentResolver.openOutputStream(uri, "w")?.use {
            it.write(content(origin, destination, start, end, title, description, appointmentMillis, calculatedAtMillis).toByteArray(Charsets.UTF_8))
        } ?: error("Could not write ICS file.")
    }

    private fun content(
        origin: String,
        destination: String,
        start: Long,
        end: Long,
        title: String,
        description: String,
        appointmentMillis: Long? = null,
        calculatedAtMillis: Long = System.currentTimeMillis()
    ): String {
        val formatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        val identity = DriveEntryIdentity.key(destination, appointmentMillis ?: end)
        val uid = identity + "-" + DriveEntryIdentity.originKey(origin)
        val body = DriveEntryIdentity.attachPlanningMetadata(DriveEntryIdentity.attach(description.ifBlank { "From: $origin" }, identity), origin, calculatedAtMillis)
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Drive Time Notifier//EN\r\n" +
            "BEGIN:VEVENT\r\nUID:$uid@drive-time-notifier\r\n" +
            "SEQUENCE:${calculatedAtMillis / 1_000}\r\nLAST-MODIFIED:${formatter.format(Instant.ofEpochMilli(calculatedAtMillis))}\r\n" +
            "DTSTAMP:${formatter.format(Instant.now())}\r\nDTSTART:${formatter.format(Instant.ofEpochMilli(start))}\r\n" +
            "DTEND:${formatter.format(Instant.ofEpochMilli(end))}\r\nSUMMARY:${escapeIcsText(title)}\r\n" +
            "LOCATION:${escapeIcsText(destination)}\r\nDESCRIPTION:${escapeIcsText(body)}\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
    }

    companion object {
        internal fun escapeIcsText(value: String): String = value
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .filter { it == '\n' || it == '\t' || !it.isISOControl() }
            .replace("\\", "\\\\")
            .replace(",", "\\,")
            .replace(";", "\\;")
            .replace("\n", "\\n")
    }
}
