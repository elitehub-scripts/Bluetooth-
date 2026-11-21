package com.example.core.database.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.core.database.entities.ApplicationSettingsEntity
import com.example.core.database.entities.BlockedDeviceEntity
import com.example.core.database.entities.ConnectionHistoryEntity
import com.example.core.database.entities.ContactEntity
import com.example.core.database.entities.ConversationEntity
import com.example.core.database.entities.DeviceProfileEntity
import com.example.core.database.entities.FileTransferEntity
import com.example.core.database.entities.MessageEntity
import com.example.core.database.entities.NetworkNodeEntity
import com.example.core.database.entities.RelayHistoryEntity
import com.example.core.database.entities.RelayMessageEntity
import com.example.core.database.entities.TrustedDeviceEntity
import com.example.core.models.FileTransferStatus
import com.example.core.models.MessageDeliveryStatus
import com.example.core.models.RelayState
import com.example.core.models.TrustStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceProfileDao {
    @Query("SELECT * FROM device_profile LIMIT 1")
    fun observeProfile(): Flow<DeviceProfileEntity?>

    @Query("SELECT * FROM device_profile LIMIT 1")
    suspend fun getProfile(): DeviceProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: DeviceProfileEntity)

    @Query("UPDATE device_profile SET displayName = :name, updatedAt = :updatedAt WHERE deviceId = :deviceId")
    suspend fun updateDisplayName(deviceId: String, name: String, updatedAt: Long)
}

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY trustStatus DESC, lastSeenAt DESC")
    fun observeAllContacts(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE deviceId = :deviceId LIMIT 1")
    suspend fun getById(deviceId: String): ContactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: ContactEntity)

    @Query("UPDATE contacts SET trustStatus = :status WHERE deviceId = :deviceId")
    suspend fun updateTrustStatus(deviceId: String, status: TrustStatus)

    @Query("DELETE FROM contacts WHERE deviceId = :deviceId")
    suspend fun deleteById(deviceId: String)
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY isPinned DESC, lastMessageAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE peerDeviceId = :peerDeviceId LIMIT 1")
    suspend fun getByPeerId(peerDeviceId: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE conversationId = :conversationId LIMIT 1")
    suspend fun getById(conversationId: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity)

    @Query("UPDATE conversations SET unreadCount = 0 WHERE conversationId = :conversationId")
    suspend fun clearUnread(conversationId: String)

    @Query("UPDATE conversations SET lastMessageStatus = :status WHERE conversationId = :conversationId")
    suspend fun updateLastStatus(conversationId: String, status: MessageDeliveryStatus)

    @Query("DELETE FROM conversations WHERE conversationId = :conversationId")
    suspend fun deleteById(conversationId: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    fun observeForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM messages WHERE status IN ('CREATED', 'WAITING_FOR_RELAY', 'RELAYED')")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT * FROM messages WHERE messageId = :messageId LIMIT 1")
    suspend fun getById(messageId: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Query("UPDATE messages SET status = :status, deliveredAt = :deliveredAt WHERE messageId = :messageId")
    suspend fun updateDeliveryStatus(messageId: String, status: MessageDeliveryStatus, deliveredAt: Long?)

    @Query("UPDATE messages SET status = 'EXPIRED' WHERE status IN ('CREATED', 'WAITING_FOR_RELAY', 'RELAYED') AND expiresAt < :now")
    suspend fun markExpiredMessages(now: Long): Int

    @Query("DELETE FROM messages WHERE messageId = :messageId")
    suspend fun deleteById(messageId: String)
}

@Dao
interface RelayMessageDao {
    @Query("SELECT * FROM relay_messages ORDER BY isPriority DESC, createdAt DESC")
    fun observeAll(): Flow<List<RelayMessageEntity>>

    @Query("SELECT COUNT(*) FROM relay_messages WHERE relayState IN ('QUEUED', 'FORWARDED')")
    fun observeActiveCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(payloadSizeBytes), 0) FROM relay_messages")
    fun observeTotalBytes(): Flow<Long>

    @Query("SELECT * FROM relay_messages WHERE messageId = :messageId LIMIT 1")
    suspend fun getById(messageId: String): RelayMessageEntity?

    @Query("SELECT * FROM relay_messages WHERE relayState IN ('QUEUED', 'FORWARDED') AND expiresAt > :now ORDER BY isPriority DESC, createdAt ASC")
    suspend fun getEligibleForForwarding(now: Long): List<RelayMessageEntity>

    @Query("SELECT messageId FROM relay_messages WHERE relayState = 'ACKED_DELIVERED'")
    suspend fun getKnownAckedIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(relayMessage: RelayMessageEntity): Long

    @Update
    suspend fun update(relayMessage: RelayMessageEntity)

    @Query("UPDATE relay_messages SET relayState = :state WHERE messageId = :messageId")
    suspend fun updateState(messageId: String, state: RelayState)

    @Query("DELETE FROM relay_messages WHERE expiresAt < :now OR relayState = 'EXPIRED'")
    suspend fun deleteExpired(now: Long): Int

    @Query("SELECT * FROM relay_messages ORDER BY isPriority ASC, receivedAt ASC LIMIT :limit")
    suspend fun getOldestLowestPriority(limit: Int): List<RelayMessageEntity>

    @Query("DELETE FROM relay_messages WHERE messageId = :messageId")
    suspend fun deleteById(messageId: String)

    @Query("SELECT COALESCE(SUM(payloadSizeBytes), 0) FROM relay_messages")
    suspend fun getTotalBytesSync(): Long
}

@Dao
interface RelayHistoryDao {
    @Query("SELECT * FROM relay_history ORDER BY timestamp DESC LIMIT 150")
    fun observeRecent(): Flow<List<RelayHistoryEntity>>

    @Insert
    suspend fun insert(entry: RelayHistoryEntity)

    @Query("DELETE FROM relay_history WHERE timestamp < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long)
}

@Dao
interface FileTransferDao {
    @Query("SELECT * FROM file_transfers ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<FileTransferEntity>>

    @Query("SELECT COUNT(*) FROM file_transfers WHERE status IN ('QUEUED', 'TRANSFERRING', 'PAUSED')")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT * FROM file_transfers WHERE transferId = :transferId LIMIT 1")
    suspend fun getById(transferId: String): FileTransferEntity?

    @Query("SELECT * FROM file_transfers WHERE peerDeviceId = :peerDeviceId AND status = 'QUEUED' ORDER BY createdAt ASC")
    suspend fun getQueuedForPeer(peerDeviceId: String): List<FileTransferEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(transfer: FileTransferEntity)

    @Query("UPDATE file_transfers SET status = :status, completedChunks = :chunks, bytesTransferred = :bytes, updatedAt = :updatedAt, errorMessage = :error WHERE transferId = :transferId")
    suspend fun updateProgress(
        transferId: String,
        status: FileTransferStatus,
        chunks: Int,
        bytes: Long,
        updatedAt: Long,
        error: String? = null
    )

    @Query("DELETE FROM file_transfers WHERE transferId = :transferId")
    suspend fun deleteById(transferId: String)
}

@Dao
interface TrustedDeviceDao {
    @Query("SELECT * FROM trusted_devices ORDER BY trustedAt DESC")
    fun observeAll(): Flow<List<TrustedDeviceEntity>>

    @Query("SELECT * FROM trusted_devices WHERE deviceId = :deviceId LIMIT 1")
    suspend fun getById(deviceId: String): TrustedDeviceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: TrustedDeviceEntity)

    @Query("DELETE FROM trusted_devices WHERE deviceId = :deviceId")
    suspend fun deleteById(deviceId: String)
}

@Dao
interface BlockedDeviceDao {
    @Query("SELECT * FROM blocked_devices ORDER BY blockedAt DESC")
    fun observeAll(): Flow<List<BlockedDeviceEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM blocked_devices WHERE deviceId = :deviceId OR (:address IS NOT NULL AND bluetoothAddress = :address))")
    suspend fun isBlocked(deviceId: String, address: String?): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(blocked: BlockedDeviceEntity)

    @Query("DELETE FROM blocked_devices WHERE deviceId = :deviceId")
    suspend fun unblock(deviceId: String)
}

@Dao
interface ConnectionHistoryDao {
    @Query("SELECT * FROM connection_history ORDER BY connectedAt DESC LIMIT 100")
    fun observeRecent(): Flow<List<ConnectionHistoryEntity>>

    @Insert
    suspend fun insert(entry: ConnectionHistoryEntity): Long

    @Query("UPDATE connection_history SET disconnectedAt = :disconnectedAt, bytesSent = :bytesSent, bytesReceived = :bytesReceived, disconnectReason = :reason WHERE id = :id")
    suspend fun closeSession(id: Long, disconnectedAt: Long, bytesSent: Long, bytesReceived: Long, reason: String)
}

@Dao
interface NetworkNodeDao {
    @Query("SELECT * FROM network_nodes ORDER BY isCurrentlyConnected DESC, lastSeenAt DESC")
    fun observeAll(): Flow<List<NetworkNodeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(node: NetworkNodeEntity)

    @Query("UPDATE network_nodes SET isCurrentlyConnected = 0")
    suspend fun markAllDisconnected()

    @Query("UPDATE network_nodes SET successfulRelays = successfulRelays + 1, lastSeenAt = :timestamp WHERE nodeId = :nodeId")
    suspend fun incrementRelayCount(nodeId: String, timestamp: Long)
}

@Dao
interface ApplicationSettingsDao {
    @Query("SELECT * FROM application_settings WHERE id = 1")
    fun observeSettings(): Flow<ApplicationSettingsEntity?>

    @Query("SELECT * FROM application_settings WHERE id = 1")
    suspend fun getSettings(): ApplicationSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: ApplicationSettingsEntity)
}

@Dao
interface MeshTransactionDao {
    @Transaction
    suspend fun saveOutgoingMessageAndRelay(
        messageDao: MessageDao,
        conversationDao: ConversationDao,
        relayDao: RelayMessageDao,
        message: MessageEntity,
        conversation: ConversationEntity,
        relayMessage: RelayMessageEntity?
    ) {
        messageDao.upsert(message)
        conversationDao.upsert(conversation)
        if (relayMessage != null) {
            relayDao.insertIgnore(relayMessage)
        }
    }
}
