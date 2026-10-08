package com.pincatcher.capture

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * The two-way pipe to the tun device.
 *
 * One reader, many writers. The reader is the tunnel's only consumer and owns a
 * dedicated thread; writers are one per upstream socket pump, and the kernel
 * serialises a tun write against a read without us needing a lock. A mutex here
 * would only add a way to interleave two half-written packets.
 *
 * A tun read returns one IP packet, never a partial one, so [read] looping until
 * the buffer fills would be wrong - it would block forever waiting for bytes that
 * are not coming.
 */
class TunIo(private val descriptor: ParcelFileDescriptor) {

    private val input = FileInputStream(descriptor.fileDescriptor)
    private val output = FileOutputStream(descriptor.fileDescriptor)

    @Volatile
    private var closed = false

    /**
     * Reads the next packet into [into] and returns its length, or -1 when the
     * tun is gone.
     *
     * The call blocks, which is what the reader thread wants. Closing the
     * descriptor is what unblocks it: the pending read fails with an IOException
     * rather than sitting there until the next packet the device may never send.
     */
    fun read(into: ByteArray): Int = try {
        input.read(into, 0, into.size)
    } catch (_: IOException) {
        -1
    }

    /** Writes one packet. Silently ignored after close, because racing teardown is normal. */
    fun write(packet: ByteArray, length: Int = packet.size) {
        if (closed) return
        try {
            output.write(packet, 0, length)
            output.flush()
        } catch (_: IOException) {
            // The tun was torn down underneath us. Nothing useful to do, and the
            // reader thread will observe the same thing and shut the service down.
        }
    }

    fun close() {
        closed = true
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { descriptor.close() }
    }

    companion object {
        /** One MTU plus room for the tun's own framing. */
        const val BUFFER_SIZE = 16384
    }
}