package de.drivetime.notifier.data

import de.drivetime.notifier.model.DrivePlan
import de.drivetime.notifier.model.RouteEstimate
import de.drivetime.notifier.model.RouteRequest
import de.drivetime.notifier.routing.PolylineDecoder
import de.drivetime.notifier.routing.PolylineEncoder
import java.security.MessageDigest

enum class HistoryTrigger { MANUAL, APP_ACTION }
enum class HistorySaveState { CALCULATED, CALENDAR, ICS, FAILED }

data class RouteHistoryEntry(
    val id:String,
    val createdAt:Long,
    val calculatedAt:Long,
    val trigger:HistoryTrigger,
    val request:RouteRequest,
    val route:RouteEstimate,
    val departureMillis:Long,
    val arrivalMillis:Long,
    val bufferMinutes:Int,
    val saveState:HistorySaveState=HistorySaveState.CALCULATED
) {
    fun snapshotKey():String {
        val value=listOf(request.origin,request.destination,request.arrivalMillis.toString(),
            route.durationSeconds.toString(),route.distanceMeters.toString(),route.encodedPolyline,
            departureMillis.toString(),arrivalMillis.toString(),bufferMinutes.toString(),route.providerId.orEmpty())
            .joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    companion object {
        fun create(id:String,createdAt:Long,trigger:HistoryTrigger,request:RouteRequest,route:RouteEstimate,
            plan:DrivePlan,walkingSeconds:Long=0):RouteHistoryEntry {
            require(request.origin.length<=4096&&request.destination.length<=4096)
            val geometry=compactGeometry(route.encodedPolyline)
            return RouteHistoryEntry(id,createdAt,System.currentTimeMillis(),trigger,request,
                route.copy(encodedPolyline=geometry),plan.departureMillis,
                plan.arrivalMillis+walkingSeconds*1000,plan.appliedBufferMinutes)
        }
        internal fun compactGeometry(encoded:String):String {
            if(encoded.length<=16000)return encoded
            return runCatching {
                val points=PolylineDecoder.decode(encoded)
                val step=((points.size+999)/1000).coerceAtLeast(1)
                PolylineEncoder.encode(points.filterIndexed { index,_ ->index%step==0||index==points.lastIndex })
            }.getOrDefault("")
        }
    }
}

data class RouteHistoryState(
    val enabled:Boolean=false,
    val limit:Int=50,
    val entries:List<RouteHistoryEntry> = emptyList(),
    val error:String?=null,
    val ready:Boolean=false
)

object RouteHistoryPolicy {
    const val MAX_ENTRIES=500
    const val MAX_AGE_MILLIS=30L*24*60*60*1000
    fun trim(entries:List<RouteHistoryEntry>,limit:Int,now:Long):List<RouteHistoryEntry> =
        entries.filter {it.createdAt>=now-MAX_AGE_MILLIS}
            .sortedByDescending {it.createdAt}.distinctBy {it.id}.take(limit.coerceIn(1,MAX_ENTRIES))
}
