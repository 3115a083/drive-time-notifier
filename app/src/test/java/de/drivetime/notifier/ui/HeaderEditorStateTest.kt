package de.drivetime.notifier.ui

import de.drivetime.notifier.network.*
import org.junit.Assert.*
import org.junit.Test

class HeaderEditorStateTest {
    @Test fun incompleteNewRowsDoNotDisableSavedCredentialsAndRemovalIsExplicit() {
        val original=EndpointHeader("P-Access-Token-Id","fixture-id")
        val state=HeaderEditorState(listOf(HeaderDraft(id="old",name=original.name,value=original.value,saved=original)))
        state.add()
        val added=state.rows.last().id
        state.edit(added,name="P-Access-Token")
        assertEquals(listOf(original),state.persistable())
        state.edit(added,value="fixture-token")
        assertEquals(2,state.persistable()!!.size)
        state.committed()
        state.edit("old",value="")
        assertEquals(original,state.persistable()!!.first())
        assertEquals("",state.rows.first().value)
        state.remove("old")
        assertEquals(listOf(EndpointHeader("P-Access-Token","fixture-token")),state.persistable())
        state.remove(added)
        assertEquals(emptyList<EndpointHeader>(),state.persistable())
    }
    @Test fun duplicatesAndInjectedValuesCannotReplaceTheLastValidList() {
        val original=EndpointHeader("X-Key","fixture")
        val state=HeaderEditorState(listOf(HeaderDraft(id="old",name=original.name,value=original.value,saved=original)))
        state.edit("old",value="bad\r\nInjected: header")
        assertEquals(listOf(original),state.persistable())
        state.add()
        val added=state.rows.last().id
        state.edit(added,name="x-key",value="duplicate")
        assertNull(state.persistable())
        state.edit(added,name="P-Access-Token",value="token")
        assertEquals(2,state.persistable()!!.size)
    }
    @Test fun headerCountHasNoArbitraryEditorLimit() {
        val state=HeaderEditorState(emptyList())
        repeat(40) { index -> state.add(); state.edit(state.rows.last().id,name="X-Fixture-$index",value="value-$index") }
        assertEquals(40,state.persistable()!!.size)
    }
}
