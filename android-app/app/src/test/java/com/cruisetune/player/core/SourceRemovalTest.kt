package com.cruisetune.player.core
import org.junit.Assert.*
import org.junit.Test
class SourceRemovalTest {
    private val web=Track("web-song","web","file","Song")
    private val token=web.copy(id="token-song",sourceId="token")
    @Test fun sourceRemovalKeepsRemainingOrderCurrentPositionAndModes() {
        val last=web.copy(id="last")
        val before=PlaybackSnapshot(1,listOf(QueueEntry(token,0),QueueEntry(web,1),QueueEntry(last,2)),1,42000,true,2,true)
        val after=SourceRemoval.queue(before,setOf("token"),2)
        assertEquals(listOf(web,last),after.entries.map { it.track });assertEquals(0,after.index)
        assertEquals(42000L,after.positionMs);assertTrue(after.playIntent);assertTrue(after.shuffled);assertEquals(2,after.repeatMode)
        val currentRemoved=SourceRemoval.queue(before.copy(index=0),setOf("token"),2)
        assertEquals(0L,currentRemoved.positionMs);assertFalse(currentRemoved.playIntent)
        assertTrue(SourceRemoval.queue(before,setOf("web","token"),2).entries.isEmpty())
    }
}
