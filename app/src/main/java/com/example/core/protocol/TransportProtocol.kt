package com.example.core.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

enum class PacketType(val code: Byte) {
    HELLO(0x01),
    HELLO_ACK(0x02),
    AUTH_CHALLENGE(0x03),
    AUTH_RESPONSE(0x04),
    MSG_ENVELOPE(0x10),
    RELAY_SYNC_OFFER(0x11),
    RELAY_SYNC_REQUEST(0x12),
    RELAY_ENVELOPE(0x13),
    DELIVERY_ACK(0x14),
    FILE_META(0x20),
    FILE_CHUNK(0x21),
    FILE_ACK(0x22),
    FILE_PAUSE(0x23),
    FILE_CANCEL(0x24),
    PING(0x30),
    PONG(0x31),
    FRAME_ACK(0x32);

    companion object {
        fun fromCode(code: Byte): PacketType? = entries.firstOrNull { it.code == code }
    }
}

data class TransportFrame(
    val packetId: Long,
    val type: PacketType,
    val streamId: String,
    val seqNum: Int,
    val totalSeq: Int,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TransportFrame) return false
        return packetId == other.packetId &&
            type == other.type &&
            streamId == other.streamId &&
            seqNum == other.seqNum &&
            totalSeq == other.totalSeq &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = packetId.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + streamId.hashCode()
        result = 31 * result + seqNum
        result = 31 * result + totalSeq
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

sealed class DecodeFrameResult {
    data class Success(val frame: TransportFrame, val bytesConsumed: Int) : DecodeFrameResult()
    data object NeedMoreBytes : DecodeFrameResult()
    data class Malformed(val reason: String) : DecodeFrameResult()
}

object TransportCodec {
    const val MAGIC_0: Byte = 0x42 // 'B'
    const val MAGIC_1: Byte = 0x47 // 'G'
    const val PROTOCOL_VERSION: Byte = 0x01
    const val MAX_STREAM_ID_LEN = 64
    const val MAX_FRAME_PAYLOAD_BYTES = 64 * 1024 // 64 KB hard upper bound
    const val HEADER_FIXED_SIZE = 28 // magic(2) + ver(1) + type(1) + packetId(8) + seq(4) + totalSeq(4) + streamLen(2) + payloadLen(4) + headerPad/reserved? Wait: let's compute exact!
    // Exact binary layout:
    // [0..1]   MAGIC ("BG")          2 bytes
    // [2]      VERSION (1)           1 byte
    // [3]      PACKET_TYPE           1 byte
    // [4..11]  PACKET_ID (Long)      8 bytes
    // [12..15] SEQ_NUM (Int)         4 bytes
    // [16..19] TOTAL_SEQ (Int)       4 bytes
    // [20..21] STREAM_ID_LEN (Short) 2 bytes
    // [22..25] PAYLOAD_LEN (Int)     4 bytes
    // Total fixed header = 26 bytes
    // [26 .. 26+streamLen-1]         STREAM_ID UTF-8
    // [.. +payloadLen]               PAYLOAD
    // [last 4 bytes]                 CRC32 over entire preceding frame bytes
    private const val FIXED_HEADER_BYTES = 26
    private const val CRC_BYTES = 4

    fun encode(frame: TransportFrame): ByteArray {
        val streamBytes = frame.streamId.toByteArray(Charsets.UTF_8)
        require(streamBytes.size <= MAX_STREAM_ID_LEN) { "streamId exceeds $MAX_STREAM_ID_LEN bytes" }
        require(frame.payload.size <= MAX_FRAME_PAYLOAD_BYTES) { "payload exceeds $MAX_FRAME_PAYLOAD_BYTES bytes" }
        require(frame.seqNum >= 0 && frame.totalSeq >= 1 && frame.seqNum < frame.totalSeq) {
            "Invalid sequence numbers: ${frame.seqNum}/${frame.totalSeq}"
        }

        val bodySize = FIXED_HEADER_BYTES + streamBytes.size + frame.payload.size
        val buffer = ByteBuffer.allocate(bodySize + CRC_BYTES).order(ByteOrder.BIG_ENDIAN)
        buffer.put(MAGIC_0)
        buffer.put(MAGIC_1)
        buffer.put(PROTOCOL_VERSION)
        buffer.put(frame.type.code)
        buffer.putLong(frame.packetId)
        buffer.putInt(frame.seqNum)
        buffer.putInt(frame.totalSeq)
        buffer.putShort(streamBytes.size.toShort())
        buffer.putInt(frame.payload.size)
        buffer.put(streamBytes)
        buffer.put(frame.payload)

        val crc = CRC32()
        crc.update(buffer.array(), 0, bodySize)
        buffer.putInt(crc.value.toInt())
        return buffer.array()
    }

    fun decode(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): DecodeFrameResult {
        if (length < FIXED_HEADER_BYTES + CRC_BYTES) {
            return DecodeFrameResult.NeedMoreBytes
        }
        val buf = ByteBuffer.wrap(bytes, offset, length).order(ByteOrder.BIG_ENDIAN)
        val m0 = buf.get()
        val m1 = buf.get()
        if (m0 != MAGIC_0 || m1 != MAGIC_1) {
            return DecodeFrameResult.Malformed("Invalid protocol magic header")
        }
        val version = buf.get()
        if (version != PROTOCOL_VERSION) {
            return DecodeFrameResult.Malformed("Unsupported protocol version: $version")
        }
        val typeByte = buf.get()
        val type = PacketType.fromCode(typeByte)
            ?: return DecodeFrameResult.Malformed("Unknown packet type: $typeByte")

        val packetId = buf.getLong()
        val seqNum = buf.getInt()
        val totalSeq = buf.getInt()
        val streamLen = buf.getShort().toInt() and 0xFFFF
        val payloadLen = buf.getInt()

        if (streamLen > MAX_STREAM_ID_LEN) {
            return DecodeFrameResult.Malformed("Stream ID length out of bounds: $streamLen")
        }
        if (payloadLen < 0 || payloadLen > MAX_FRAME_PAYLOAD_BYTES) {
            return DecodeFrameResult.Malformed("Payload length out of bounds: $payloadLen")
        }
        if (totalSeq <= 0 || seqNum < 0 || seqNum >= totalSeq) {
            return DecodeFrameResult.Malformed("Invalid sequence bounds: $seqNum/$totalSeq")
        }

        val totalFrameBytes = FIXED_HEADER_BYTES + streamLen + payloadLen + CRC_BYTES
        if (length < totalFrameBytes) {
            return DecodeFrameResult.NeedMoreBytes
        }

        val streamBytes = ByteArray(streamLen)
        buf.get(streamBytes)
        val payload = ByteArray(payloadLen)
        buf.get(payload)
        val expectedCrc = buf.getInt()

        val crc = CRC32()
        crc.update(bytes, offset, totalFrameBytes - CRC_BYTES)
        val actualCrc = crc.value.toInt()
        if (expectedCrc != actualCrc) {
            return DecodeFrameResult.Malformed("CRC32 checksum mismatch")
        }

        return DecodeFrameResult.Success(
            frame = TransportFrame(
                packetId = packetId,
                type = type,
                streamId = String(streamBytes, Charsets.UTF_8),
                seqNum = seqNum,
                totalSeq = totalSeq,
                payload = payload
            ),
            bytesConsumed = totalFrameBytes
        )
    }
}

object Fragmenter {
    fun fragment(
        packetIdGenerator: () -> Long,
        type: PacketType,
        streamId: String,
        fullPayload: ByteArray,
        maxChunkPayloadBytes: Int
    ): List<TransportFrame> {
        require(maxChunkPayloadBytes > 0)
        if (fullPayload.isEmpty()) {
            return listOf(
                TransportFrame(
                    packetId = packetIdGenerator(),
                    type = type,
                    streamId = streamId,
                    seqNum = 0,
                    totalSeq = 1,
                    payload = ByteArray(0)
                )
            )
        }
        val totalSeq = (fullPayload.size + maxChunkPayloadBytes - 1) / maxChunkPayloadBytes
        val frames = ArrayList<TransportFrame>(totalSeq)
        for (seq in 0 until totalSeq) {
            val start = seq * maxChunkPayloadBytes
            val end = minOf(fullPayload.size, start + maxChunkPayloadBytes)
            frames.add(
                TransportFrame(
                    packetId = packetIdGenerator(),
                    type = type,
                    streamId = streamId,
                    seqNum = seq,
                    totalSeq = totalSeq,
                    payload = fullPayload.copyOfRange(start, end)
                )
            )
        }
        return frames
    }
}

class ReassemblyBuffer(
    private val maxConcurrentStreams: Int = 24,
    private val maxStreamBytes: Int = 4 * 1024 * 1024,
    private val streamTimeoutMs: Long = 90_000L
) {
    private data class StreamAccumulator(
        val type: PacketType,
        val totalSeq: Int,
        val chunks: MutableMap<Int, ByteArray> = LinkedHashMap(),
        var accumulatedBytes: Int = 0,
        var updatedAt: Long = System.currentTimeMillis()
    )

    private val activeStreams = ConcurrentHashMap<String, StreamAccumulator>()

    sealed class OfferResult {
        data class Complete(val streamId: String, val type: PacketType, val data: ByteArray) : OfferResult()
        data class Partial(val receivedChunks: Int, val totalChunks: Int) : OfferResult()
        data class Duplicate(val seqNum: Int) : OfferResult()
        data class Rejected(val reason: String) : OfferResult()
    }

    @Synchronized
    fun offer(frame: TransportFrame): OfferResult {
        evictExpired()
        val key = "${frame.type.code}:${frame.streamId}"
        val existing = activeStreams[key]

        if (existing == null) {
            if (activeStreams.size >= maxConcurrentStreams) {
                return OfferResult.Rejected("Too many concurrent reassembly streams")
            }
            if (frame.totalSeq == 1 && frame.seqNum == 0) {
                return OfferResult.Complete(frame.streamId, frame.type, frame.payload)
            }
            if (frame.payload.size > maxStreamBytes) {
                return OfferResult.Rejected("Stream exceeds max memory cap")
            }
            val acc = StreamAccumulator(type = frame.type, totalSeq = frame.totalSeq)
            acc.chunks[frame.seqNum] = frame.payload
            acc.accumulatedBytes = frame.payload.size
            activeStreams[key] = acc
            return OfferResult.Partial(1, frame.totalSeq)
        }

        if (existing.totalSeq != frame.totalSeq) {
            activeStreams.remove(key)
            return OfferResult.Rejected("Inconsistent totalSeq for stream ${frame.streamId}")
        }
        if (existing.chunks.containsKey(frame.seqNum)) {
            return OfferResult.Duplicate(frame.seqNum)
        }
        if (existing.accumulatedBytes + frame.payload.size > maxStreamBytes) {
            activeStreams.remove(key)
            return OfferResult.Rejected("Stream exceeded max memory cap")
        }

        existing.chunks[frame.seqNum] = frame.payload
        existing.accumulatedBytes += frame.payload.size
        existing.updatedAt = System.currentTimeMillis()

        if (existing.chunks.size == existing.totalSeq) {
            activeStreams.remove(key)
            val out = ByteArrayOutputStream(existing.accumulatedBytes)
            for (i in 0 until existing.totalSeq) {
                val chunk = existing.chunks[i]
                    ?: return OfferResult.Rejected("Missing chunk $i during reassembly")
                out.write(chunk)
            }
            return OfferResult.Complete(frame.streamId, frame.type, out.toByteArray())
        }

        return OfferResult.Partial(existing.chunks.size, existing.totalSeq)
    }

    private fun evictExpired() {
        val now = System.currentTimeMillis()
        val iterator = activeStreams.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.updatedAt > streamTimeoutMs) {
                iterator.remove()
            }
        }
    }

    fun activeStreamCount(): Int = activeStreams.size
}
