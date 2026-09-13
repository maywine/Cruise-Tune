package com.cruisetune.player.playback

import android.app.Application
import androidx.media3.common.ParserException
import com.cruisetune.player.core.UserError
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@androidx.media3.common.util.UnstableApi
class PlaybackRecoveryPolicyTest {
    @Test fun automaticRecoveryIsOncePerFileUntilAnExplicitReset() {
        val policy = PlaybackRecoveryPolicy()
        assertTrue(policy.tryRecovery("a")); assertFalse(policy.tryRecovery("a"))
        policy.failed("a")
        assertTrue(policy.tryRecovery("b")); assertFalse(policy.tryRecovery("a"))
        policy.reset(); assertTrue(policy.tryRecovery("a"))
    }
    @Test fun exhaustedRepeatAllStopsInsteadOfLoopingThroughBrokenTracks() {
        val policy = PlaybackRecoveryPolicy()
        val keys = listOf("a", "b", "c")
        policy.failed("a")
        assertEquals(1, policy.nextIndex(keys, 0) { (it + 1) % keys.size })
        policy.failed("b")
        assertEquals(2, policy.nextIndex(keys, 1) { (it + 1) % keys.size })
        policy.failed("c")
        assertNull(policy.nextIndex(keys, 2) { (it + 1) % keys.size })
    }
    @Test fun skippingFollowsActualOrderAndSkipsRepeatedReferencesToTheSameBadFile() {
        val policy = PlaybackRecoveryPolicy()
        policy.failed("a")
        val keys = listOf("a", "c", "a", "b")
        assertEquals(3, policy.nextIndex(keys, 0) { mapOf(0 to 2, 2 to 3, 3 to 1)[it] ?: -1 })
        assertNull(policy.nextIndex(keys, 3) { -1 })
    }
    @Test fun emptyAndSingleItemQueuesHaveATerminalBoundary() {
        val policy = PlaybackRecoveryPolicy(); policy.failed("a")
        assertNull(policy.nextIndex(emptyList(), 0) { error("No next item") })
        assertNull(policy.nextIndex(listOf("a"), 0) { 0 })
    }
    @Test fun networkAuthenticationAndRendererFailuresAreNotProofOfBadFiles() {
        for (error in listOf(SocketTimeoutException(), UserError("Login", needsLogin = true),
            SecurityException(), IOException("Unclassified read error"), IllegalStateException("Audio output stalled"))) {
            assertFalse(PlaybackRecoveryPolicy.isFileFailure(error))
        }
        assertTrue(PlaybackRecoveryPolicy.isFileFailure(IOException(EOFException())))
        assertTrue(PlaybackRecoveryPolicy.isFileFailure(ParserException.createForMalformedContainer("Broken header", null)))
    }
}
