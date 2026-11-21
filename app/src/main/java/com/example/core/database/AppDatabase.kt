package com.example.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.core.database.daos.ApplicationSettingsDao
import com.example.core.database.daos.BlockedDeviceDao
import com.example.core.database.daos.ConnectionHistoryDao
import com.example.core.database.daos.ContactDao
import com.example.core.database.daos.ConversationDao
import com.example.core.database.daos.DeviceProfileDao
import com.example.core.database.daos.FileTransferDao
import com.example.core.database.daos.MeshTransactionDao
import com.example.core.database.daos.MessageDao
import com.example.core.database.daos.NetworkNodeDao
import com.example.core.database.daos.RelayHistoryDao
import com.example.core.database.daos.RelayMessageDao
import com.example.core.database.daos.TrustedDeviceDao
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
import com.example.core.models.AppThemeMode
import com.example.core.models.BleScanModeSetting
import com.example.core.models.FileTransferDirection
import com.example.core.models.FileTransferStatus
import com.example.core.models.MessageDeliveryStatus
import com.example.core.models.MessageDirection
import com.example.core.models.RelayAction
import com.example.core.models.RelayState
import com.example.core.models.TransportType
import com.example.core.models.TrustStatus

class MeshTypeConverters {
    @TypeConverter fun fromTrustStatus(value: TrustStatus): String = value.name
    @TypeConverter fun toTrustStatus(value: String): TrustStatus = enumValueOf(value)

    @TypeConverter fun fromTransportType(value: TransportType?): String? = value?.name
    @TypeConverter fun toTransportType(value: String?): TransportType? = value?.let { enumValueOf<TransportType>(it) }

    @TypeConverter fun fromMessageStatus(value: MessageDeliveryStatus): String = value.name
    @TypeConverter fun toMessageStatus(value: String): MessageDeliveryStatus = enumValueOf(value)

    @TypeConverter fun fromMessageDirection(value: MessageDirection): String = value.name
    @TypeConverter fun toMessageDirection(value: String): MessageDirection = enumValueOf(value)

    @TypeConverter fun fromRelayState(value: RelayState): String = value.name
    @TypeConverter fun toRelayState(value: String): RelayState = enumValueOf(value)

    @TypeConverter fun fromRelayAction(value: RelayAction): String = value.name
    @TypeConverter fun toRelayAction(value: String): RelayAction = enumValueOf(value)

    @TypeConverter fun fromFileDirection(value: FileTransferDirection): String = value.name
    @TypeConverter fun toFileDirection(value: String): FileTransferDirection = enumValueOf(value)

    @TypeConverter fun fromFileStatus(value: FileTransferStatus): String = value.name
    @TypeConverter fun toFileStatus(value: String): FileTransferStatus = enumValueOf(value)

    @TypeConverter fun fromThemeMode(value: AppThemeMode): String = value.name
    @TypeConverter fun toThemeMode(value: String): AppThemeMode = enumValueOf(value)

    @TypeConverter fun fromScanMode(value: BleScanModeSetting): String = value.name
    @TypeConverter fun toScanMode(value: String): BleScanModeSetting = enumValueOf(value)
}

@Database(
    entities = [
        DeviceProfileEntity::class,
        ContactEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        RelayMessageEntity::class,
        RelayHistoryEntity::class,
        FileTransferEntity::class,
        TrustedDeviceEntity::class,
        BlockedDeviceEntity::class,
        ConnectionHistoryEntity::class,
        NetworkNodeEntity::class,
        ApplicationSettingsEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(MeshTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deviceProfileDao(): DeviceProfileDao
    abstract fun contactDao(): ContactDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun relayMessageDao(): RelayMessageDao
    abstract fun relayHistoryDao(): RelayHistoryDao
    abstract fun fileTransferDao(): FileTransferDao
    abstract fun trustedDeviceDao(): TrustedDeviceDao
    abstract fun blockedDeviceDao(): BlockedDeviceDao
    abstract fun connectionHistoryDao(): ConnectionHistoryDao
    abstract fun networkNodeDao(): NetworkNodeDao
    abstract fun applicationSettingsDao(): ApplicationSettingsDao
    abstract fun meshTransactionDao(): MeshTransactionDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Schema upgrade hook ready for v2 enhancements without data loss
            }
        }

        fun build(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "bluetooth_global_share.db"
            )
                .addMigrations(MIGRATION_1_2)
                .build()
        }
    }
}
