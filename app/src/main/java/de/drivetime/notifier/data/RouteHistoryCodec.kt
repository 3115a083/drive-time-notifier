package de.drivetime.notifier.data

import de.drivetime.notifier.model.RouteEstimate
import de.drivetime.notifier.model.RouteRequest
import org.json.JSONArray
import org.json.JSONObject

internal object RouteHistoryCodec {
    private const val MAX_BYTES=8*1024*1024
    fun encode(entries:List<RouteHistoryEntry>):String {
        val array=JSONArray()
        var size=128
        for(entry in entries) {
            val item=JSONObject().apply {
                put("id",entry.id);put("created",entry.createdAt);put("calculated",entry.calculatedAt)
                put("trigger",entry.trigger.name);put("save",entry.saveState.name)
                put("origin",entry.request.origin);put("destination",entry.request.destination)
                put("appointment",entry.request.arrivalMillis);put("departure",entry.departureMillis)
                put("arrival",entry.arrivalMillis);put("buffer",entry.bufferMinutes)
                put("duration",entry.route.durationSeconds);put("staticDuration",entry.route.staticDurationSeconds)
                put("distance",entry.route.distanceMeters);put("geometry",entry.route.encodedPolyline)
                put("warning",entry.route.warning);put("provider",entry.route.providerId)
                put("originLat",entry.route.originLatitude);put("originLon",entry.route.originLongitude)
                put("destinationLat",entry.route.destinationLatitude);put("destinationLon",entry.route.destinationLongitude)
            }
            val bytes=item.toString().toByteArray().size+1
            if(size+bytes>MAX_BYTES)break
            array.put(item);size+=bytes
        }
        return JSONObject().put("version",1).put("entries",array).toString()
    }
    fun decode(raw:String?):List<RouteHistoryEntry> {
        if(raw==null)return emptyList()
        require(raw.toByteArray().size<=MAX_BYTES) { "Historie ist zu groß." }
        val root=JSONObject(raw)
        require(root.getInt("version")==1) { "Unbekanntes Historienformat." }
        val array=root.getJSONArray("entries")
        require(array.length()<=RouteHistoryPolicy.MAX_ENTRIES)
        return (0 until array.length()).map { index ->
            val item=array.getJSONObject(index)
            val origin=item.getString("origin");val destination=item.getString("destination")
            val geometry=item.getString("geometry")
            require(origin.length<=4096&&destination.length<=4096&&geometry.length<=32000)
            val duration=item.getLong("duration");val distance=item.getLong("distance")
            require(duration>=0&&distance>=0)
            val request=RouteRequest(origin,destination,item.getLong("appointment"))
            val route=RouteEstimate(duration,item.getLong("staticDuration"),distance,geometry,
                if(item.isNull("warning"))null else item.getString("warning"),
                coordinate(item,"originLat",90.0),coordinate(item,"originLon",180.0),
                coordinate(item,"destinationLat",90.0),coordinate(item,"destinationLon",180.0),
                if(item.isNull("provider"))null else item.getString("provider"))
            RouteHistoryEntry(item.getString("id"),item.getLong("created"),item.getLong("calculated"),
                HistoryTrigger.valueOf(item.getString("trigger")),request,route,
                item.getLong("departure"),item.getLong("arrival"),item.getInt("buffer"),
                HistorySaveState.valueOf(item.getString("save")))
        }
    }
    private fun coordinate(item:JSONObject,key:String,limit:Double):Double? {
        if(item.isNull(key))return null
        val value=item.getDouble(key)
        require(value.isFinite()&&value in -limit..limit)
        return value
    }
}
