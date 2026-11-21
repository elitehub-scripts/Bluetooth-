package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.crypto.CryptoEngine
import com.example.core.database.AppDatabase
import com.example.core.database.entities.ApplicationSettingsEntity
import com.example.core.models.FileMetaPayload
import com.example.core.models.FileTransferStatus
import com.example.core.models.TransportType
import com.example.core.protocol.PacketType
import com.example.core.transfer.FilePacketSender
import com.example.core.transfer.FileTransferEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FileTransferRobolectricTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var engine: FileTransferEngine
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        engine = FileTransferEngine(
            context = context,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            cryptoEngine = CryptoEngine(context),
            fileTransferDao = db.fileTransferDao(),
            profileDao = db.deviceProfileDao(),
            settingsDao = db.applicationSettingsDao(),
            packetSender = object : FilePacketSender {
                override suspend fun sendPacket(
                    peerDeviceId: String,
                    type: PacketType,
                    streamId: String,
                    payload: ByteArray
                ): Boolean = true

                override fun getPeerTransportType(peerDeviceId: String): TransportType = TransportType.CLASSIC_RFCOMM
            }
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `chunked incoming file completes atomically only after sha256 verification`() = runTest {
        db.applicationSettingsDao().upsert(ApplicationSettingsEntity(maxFileSizeMb = 50))
        val content = ByteArray(1024) { (it % 89).toByte() }
        val sha256 = CryptoEngine.sha256Hex(content)
        val meta = FileMetaPayload(
            transferId = "tx-file-1",
            senderDeviceId = "PEER_SENDER",
            senderDisplayName = "Sender Phone",
            recipientDeviceId = "LOCAL",
            fileName = "offline_photo.jpg",
            mimeType = "image/jpeg",
            fileSizeBytes = content.size.toLong(),
            sha256Checksum = sha256,
            chunkSizeBytes = 512,
            totalChunks = 2
        )
        engine.handleIncomingPacket("PEER_SENDER", "Sender Phone", PacketType.FILE_META, "tx-file-1", json.encodeToString(meta).toByteArray())

        for (idx in 0..1) {
            val slice = content.copyOfRange(idx * 512, (idx + 1) * 512)
            val payload = ByteBuffer.allocate(4 + slice.size).putInt(idx).put(slice).array()
            engine.handleIncomingPacket("PEER_SENDER", "Sender Phone", PacketType.FILE_CHUNK, "tx-file-1", payload)
        }

        val saved = db.fileTransferDao().getById("tx-file-1")!!
        assertEquals(FileTransferStatus.COMPLETED, saved.status)
        assertTrue(File(saved.localUriOrPath).exists())
    }

    @Test
    fun `corrupted incoming file fails sha256 check and discards file`() = runTest {
        db.applicationSettingsDao().upsert(ApplicationSettingsEntity(maxFileSizeMb = 50))
        val content = ByteArray(512) { 0x33 }
        val meta = FileMetaPayload(
            transferId = "tx-corrupt-1",
            senderDeviceId = "PEER_SENDER",
            senderDisplayName = "Sender Phone",
            recipientDeviceId = "LOCAL",
            fileName = "corrupt.zip",
            mimeType = "application/zip",
            fileSizeBytes = content.size.toLong(),
            sha256Checksum = "0000000000000000000000000000000000000000000000000000000000000000",
            chunkSizeBytes = 512,
            totalChunks = 1
        )
        engine.handleIncomingPacket("PEER_SENDER", "Sender Phone", PacketType.FILE_META, "tx-corrupt-1", json.encodeToString(meta).toByteArray())
        val payload = ByteBuffer.allocate(4 + content.size).putInt(0).put(content).array()
        engine.handleIncomingPacket("PEER_SENDER", "Sender Phone", PacketType.FILE_CHUNK, "tx-corrupt-1", payload)

        val saved = db.fileTransferDao().getById("tx-corrupt-1")!!
        assertEquals(FileTransferStatus.INTEGRITY_FAILED, saved.status)
    }
}
