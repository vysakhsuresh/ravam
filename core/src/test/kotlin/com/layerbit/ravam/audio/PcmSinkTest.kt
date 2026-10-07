package com.layerbit.ravam.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PcmSinkTest {

    @get:Rule val tmp = TemporaryFolder()

    private val rate = 16_000

    @Test
    fun `a finished recording is a playable wav`() {
        val sink = PcmSink(File(tmp.root, "call.pcm"), rate)
        sink.open()
        sink.write(Signals.oneSpeaker())
        val wav = sink.finish()

        assertNotNull(wav)
        assertTrue(wav!!.name.endsWith(".wav"))
        assertTrue("header missing", wav.length() > 44)
        assertEquals("RIFF", wav.readBytes().copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals("WAVE", wav.readBytes().copyOfRange(8, 12).toString(Charsets.US_ASCII))
    }

    @Test
    fun `the wav header describes the audio that follows it`() {
        val samples = Signals.oneSpeaker()
        val sink = PcmSink(File(tmp.root, "call.pcm"), rate)
        sink.open(); sink.write(samples)
        val bytes = sink.finish()!!.readBytes()

        fun le32(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)

        assertEquals("sample rate", rate, le32(24))
        assertEquals("data length", samples.size * 2, le32(40))
        assertEquals("riff size", 36 + samples.size * 2, le32(4))
        assertEquals("payload actually present", 44 + samples.size * 2, bytes.size)
    }

    @Test
    fun `audio written before a crash is recovered, not lost`() {
        // The whole point of the design: no finish(), as if the process died mid-call.
        val pcm = File(tmp.root, "interrupted.pcm")
        val sink = PcmSink(pcm, rate)
        sink.open()
        sink.write(Signals.oneSpeaker())
        val writtenBytes = pcm.length()
        assertTrue("nothing reached disk during the call", writtenBytes > 0)

        val recovered = PcmSink.recoverOrphans(tmp.root, rate)

        assertEquals(1, recovered.size)
        assertTrue(recovered[0].name.endsWith(".wav"))
        assertEquals("audio lost in recovery", 44 + writtenBytes, recovered[0].length())
        assertFalse("orphan left behind", pcm.exists())
    }

    @Test
    fun `every byte reaches disk as it is written`() {
        // If this regresses, a crash costs the whole call instead of a few milliseconds.
        val pcm = File(tmp.root, "streaming.pcm")
        val sink = PcmSink(pcm, rate)
        sink.open()
        val chunk = ShortArray(1_600)
        repeat(5) { i ->
            sink.write(chunk)
            assertEquals("not flushed after chunk ${i + 1}", (i + 1) * 3_200L, pcm.length())
        }
        sink.finish()
    }

    @Test
    fun `an empty recording leaves no file behind`() {
        val pcm = File(tmp.root, "empty.pcm")
        val sink = PcmSink(pcm, rate)
        sink.open()
        assertEquals(null, sink.finish())
        assertFalse(pcm.exists())
        assertFalse(File(tmp.root, "empty.wav").exists())
    }

    @Test
    fun `recovery ignores files that are not orphans`() {
        File(tmp.root, "notes.txt").writeText("hello")
        File(tmp.root, "done.wav").writeBytes(ByteArray(100))
        File(tmp.root, "zero.pcm").writeBytes(ByteArray(0))

        assertTrue(PcmSink.recoverOrphans(tmp.root, rate).isEmpty())
        assertTrue(File(tmp.root, "notes.txt").exists())
        assertTrue(File(tmp.root, "done.wav").exists())
    }

    @Test
    fun `bytesWritten tracks what is on disk`() {
        val pcm = File(tmp.root, "count.pcm")
        val sink = PcmSink(pcm, rate)
        sink.open()
        sink.write(ShortArray(500))
        assertEquals(1_000L, sink.bytesWritten)
        assertEquals(pcm.length(), sink.bytesWritten)
        sink.finish()
    }
}
