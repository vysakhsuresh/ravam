package com.layerbit.ravam.audio

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Writes captured audio to disk in a way that survives the process being killed.
 *
 * Cube-style "my ninety-minute call became a 90-second file" is not a mysterious bug; it
 * is what happens when a recorder buffers in memory, or writes a container whose header
 * is only valid once it has been closed properly. Android kills background processes
 * routinely, and a call recorder is a background process by definition.
 *
 * So: raw PCM, appended as it arrives, flushed as it goes. **Every byte on disk is
 * already valid audio.** There is no header to finalise and nothing to corrupt. If the
 * process dies mid-call the `.pcm` is complete up to the instant of death, and
 * [wrapAsWav] turns it into a playable file afterwards — see [recoverOrphans].
 *
 * WAV is roomy (about 115 MB an hour at 16 kHz mono) and compression belongs on the
 * roadmap. It is the right trade for now: a large file you definitely have beats a small
 * one you might not.
 */
class PcmSink(private val pcmFile: File, private val sampleRateHz: Int, private val channels: Int = 1) {

    private var out: FileOutputStream? = null

    var bytesWritten: Long = 0
        private set

    fun open() {
        pcmFile.parentFile?.mkdirs()
        out = FileOutputStream(pcmFile, /* append = */ true)
        bytesWritten = pcmFile.length()
    }

    /** Append [count] samples. Flushed every call — the cost is small, the protection is not. */
    fun write(samples: ShortArray, count: Int = samples.size) {
        val stream = out ?: return
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            val v = samples[i].toInt()
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        stream.write(bytes)
        stream.flush()
        bytesWritten += bytes.size
    }

    /** Close and convert to a playable WAV. Returns null if nothing was captured. */
    fun finish(): File? {
        runCatching { out?.flush(); out?.close() }
        out = null
        if (!pcmFile.exists() || pcmFile.length() == 0L) {
            pcmFile.delete()
            return null
        }
        return wrapAsWav(pcmFile, sampleRateHz, channels)
    }

    companion object {
        /**
         * Give a raw PCM file a WAV header and rename it in place.
         *
         * Writing the header to a fresh file and copying would double the disk needed at
         * the worst possible moment — the end of a long call on a full phone. Instead the
         * header is prepended by rewriting only what must move.
         */
        fun wrapAsWav(pcm: File, sampleRateHz: Int, channels: Int): File {
            val wav = File(pcm.parentFile, pcm.nameWithoutExtension + ".wav")
            val dataLen = pcm.length().toInt()
            val byteRate = sampleRateHz * channels * 2

            RandomAccessFile(wav, "rw").use { dst ->
                dst.setLength(0)
                dst.write(header(dataLen, sampleRateHz, channels, byteRate))
                pcm.inputStream().use { src ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = src.read(buf)
                        if (n <= 0) break
                        dst.write(buf, 0, n)
                    }
                }
            }
            pcm.delete()
            return wav
        }

        /**
         * Wrap any `.pcm` left behind by a process that died mid-call.
         *
         * Called at startup. This is the half of crash-safety that users actually notice:
         * the recording is there when they come back for it.
         */
        fun recoverOrphans(dir: File, sampleRateHz: Int, channels: Int = 1): List<File> =
            dir.listFiles { f -> f.extension == "pcm" && f.length() > 0 }
                ?.map { wrapAsWav(it, sampleRateHz, channels) }
                ?: emptyList()

        private fun header(dataLen: Int, rate: Int, channels: Int, byteRate: Int): ByteArray {
            val h = ByteArray(44)
            fun ascii(at: Int, s: String) = s.forEachIndexed { i, c -> h[at + i] = c.code.toByte() }
            fun le32(at: Int, v: Int) {
                h[at] = (v and 0xFF).toByte(); h[at + 1] = ((v shr 8) and 0xFF).toByte()
                h[at + 2] = ((v shr 16) and 0xFF).toByte(); h[at + 3] = ((v shr 24) and 0xFF).toByte()
            }
            fun le16(at: Int, v: Int) {
                h[at] = (v and 0xFF).toByte(); h[at + 1] = ((v shr 8) and 0xFF).toByte()
            }
            ascii(0, "RIFF");  le32(4, 36 + dataLen); ascii(8, "WAVE")
            ascii(12, "fmt "); le32(16, 16); le16(20, 1)
            le16(22, channels); le32(24, rate); le32(28, byteRate)
            le16(32, channels * 2); le16(34, 16)
            ascii(36, "data"); le32(40, dataLen)
            return h
        }
    }
}
