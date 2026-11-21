package com.example

import com.example.core.protocol.DecodeFrameResult
import com.example.core.protocol.Fragmenter
import com.example.core.protocol.PacketType
import com.example.core.protocol.ReassemblyBuffer
import com.example.core.protocol.TransportCodec
import com.example.core.protocol.TransportFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportProtocolTest {

    @Test
    fun `encode and decode preserves frame fields and verifies crc32`() {
        val frame = TransportFrame(
            packetId = 42L,
            type = PacketType.MSG_ENVELOPE,
            streamId = "msg-1234",
            seqNum = 0,
            totalSeq = 1,
            payload = "EncryptedPayloadBytes".toByteArray()
        )
        val encoded = TransportCodec.encode(frame)
        val decoded = TransportCodec.decode(encoded)
        assertTrue(decoded is DecodeFrameResult.Success)
        val success = decoded as DecodeFrameResult.Success
        assertEquals(frame, success.frame)
        assertEquals(encoded.size, success.bytesConsumed)
    }

    @Test
    fun `corrupted payload fails crc32 checksum`() {
        val frame = TransportFrame(
            packetId = 99L,
            type = PacketType.RELAY_ENVELOPE,
            streamId = "relay-1",
            seqNum = 0,
            totalSeq = 1,
            payload = "IntegrityCriticalData".toByteArray()
        )
        val encoded = TransportCodec.encode(frame)
        encoded[encoded.size - 8] = (encoded[encoded.size - 8].toInt() xor 0xFF).toByte()
        val decoded = TransportCodec.decode(encoded)
        assertTrue(decoded is DecodeFrameResult.Malformed)
    }

    @Test
    fun `malformed magic header is rejected`() {
        val bad = ByteArray(40) { 0x11 }
        val decoded = TransportCodec.decode(bad)
        assertTrue(decoded is DecodeFrameResult.Malformed)
    }

    @Test
    fun `fragmentation and out-of-order reassembly handles duplicates`() {
        val largePayload = ByteArray(1000) { (it % 127).toByte() }
        var id = 1L
        val frames = Fragmenter.fragment(
            packetIdGenerator = { id++ },
            type = PacketType.RELAY_ENVELOPE,
            streamId = "stream-xyz",
            fullPayload = largePayload,
            maxChunkPayloadBytes = 180
        )
        assertEquals(6, frames.size)

        val buffer = ReassemblyBuffer()
        // Offer out of order + duplicate
        assertTrue(buffer.offer(frames[2]) is ReassemblyBuffer.OfferResult.Partial)
        assertTrue(buffer.offer(frames[2]) is ReassemblyBuffer.OfferResult.Duplicate)
        assertTrue(buffer.offer(frames[0]) is ReassemblyBuffer.OfferResult.Partial)
        assertTrue(buffer.offer(frames[4]) is ReassemblyBuffer.OfferResult.Partial)
        assertTrue(buffer.offer(frames[1]) is ReassemblyBuffer.OfferResult.Partial)
        assertTrue(buffer.offer(frames[3]) is ReassemblyBuffer.OfferResult.Partial)

        val finalRes = buffer.offer(frames[5])
        assertTrue(finalRes is ReassemblyBuffer.OfferResult.Complete)
        val complete = finalRes as ReassemblyBuffer.OfferResult.Complete
        assertArrayEquals(largePayload, complete.data)
        assertEquals(0, buffer.activeStreamCount())
    }
}
