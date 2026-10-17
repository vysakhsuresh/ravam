package com.layerbit.ravam.capture.shell

import java.io.InputStream

/**
 * A live privileged-capture session that streams the far-end PCM, however it was opened —
 * through the Shizuku app or through Ravam's own embedded ADB pairing. The recorder reads
 * [input] (16-bit little-endian PCM mono at [sampleRateHz]) on a background thread and
 * calls [close] to stop.
 */
interface PcmStreamSession {
    val input: InputStream
    val sampleRateHz: Int
    fun close()
}
