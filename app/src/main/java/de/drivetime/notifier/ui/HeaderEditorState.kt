package de.drivetime.notifier.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import de.drivetime.notifier.network.*
import org.json.JSONArray
import org.json.JSONObject

internal data class HeaderDraft(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val value: String = "",
    val saved: EndpointHeader? = null
)

/** Invalid intermediate edits keep their previous credential until explicit removal. */
internal class HeaderEditorState(initial: List<HeaderDraft>) {
    var rows by mutableStateOf(initial)
        private set
    fun add() { rows = rows + HeaderDraft() }
    fun remove(id:String) { rows = rows.filterNot { it.id == id } }
    fun edit(id:String, name:String?=null, value:String?=null) {
        rows = rows.map { if(it.id==id) it.copy(name=name?:it.name,value=value?:it.value) else it }
    }
    fun persistable(): List<EndpointHeader>? {
        val headers=rows.mapNotNull {
            if(EndpointHeaderPolicy.valid(it.name,it.value)) EndpointHeader(it.name,it.value) else it.saved
        }
        return headers.takeIf(EndpointHeaderPolicy::valid)
    }
    fun committed() {
        rows=rows.map { if(EndpointHeaderPolicy.valid(it.name,it.value)) it.copy(saved=EndpointHeader(it.name,it.value)) else it }
    }
    companion object {
        val Saver = Saver<HeaderEditorState,String>(
            save={ state ->
                val array=JSONArray()
                state.rows.forEach { row -> array.put(JSONObject().put("id",row.id).put("name",row.name).put("value",row.value)
                    .put("savedName",row.saved?.name).put("savedValue",row.saved?.value)) }
                EndpointSecurity.encryptDraft(array.toString())
            },
            restore={ raw -> runCatching {
                val array=JSONArray(EndpointSecurity.decryptDraft(raw))
                HeaderEditorState((0 until array.length()).map { index ->
                    val item=array.getJSONObject(index)
                    HeaderDraft(item.getString("id"),item.getString("name"),item.getString("value"),
                        if(item.has("savedName")) EndpointHeader(item.getString("savedName"),item.getString("savedValue")) else null)
                })
            }.getOrNull() }
        )
    }
}
