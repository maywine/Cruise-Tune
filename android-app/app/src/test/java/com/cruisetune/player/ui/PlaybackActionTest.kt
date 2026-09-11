package com.cruisetune.player.ui

import com.cruisetune.player.core.UserError
import com.cruisetune.player.core.readableError
import com.cruisetune.player.core.userError
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PlaybackActionTest {
    @Test fun fatalErrorOverridesStalePlayIntentWhileBufferingCanStillPause() {
        assertEquals(PlaybackAction.RECONNECT, PlaybackAction.choose(true, true, true, false, false))
        assertEquals(PlaybackAction.RETRY, PlaybackAction.choose(true, false, true, false, true))
        assertEquals(PlaybackAction.PAUSE, PlaybackAction.choose(false, false, true, false, false))
        assertEquals(PlaybackAction.RESUME, PlaybackAction.choose(false, false, false, false, true))
    }
    @Test fun nestedMediaFailureRetainsLoginActionAndSanitizedMessage() {
        val error = IOException("private transport details", IOException(UserError("夸克账号需重新连接", true)))
        assertEquals("夸克账号需重新连接", readableError(error))
        assertTrue(userError(error)!!.needsLogin)
    }
}
