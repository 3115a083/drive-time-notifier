package de.drivetime.notifier.export

import android.content.Context
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
        val formatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        fun esc(s: String) = s.replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;").replace("\n", "\\n")
        file.writeText(
            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Drive Time Notifier//DE\r\n" +
                "BEGIN:VEVENT\r\nUID:${UUID.randomUUID()}@drive-time-notifier\r\n" +
                "DTSTAMP:${formatter.format(Instant.now())}\r\nDTSTART:${formatter.format(Instant.ofEpochMilli(start))}\r\n" +
                "DTEND:${formatter.format(Instant.ofEpochMilli(end))}\r\nSUMMARY:Fahrt zum Termin\r\n" +
                "LOCATION:${esc(destination)}\r\nDESCRIPTION:${esc("Start: $origin")}\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        )
        return FileProvider.getUriForFile(context, context.packageName + ".files", file)
    }
}
