package com.pincatcher.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.pincatcher.core.data.db.entity.BodyEntity
import com.pincatcher.core.data.db.entity.FlowEntity
import com.pincatcher.core.data.db.entity.FlowSearchEntity
import com.pincatcher.core.data.db.entity.SessionEntity

@Database(
    entities = [
        SessionEntity::class,
        FlowEntity::class,
        BodyEntity::class,
        FlowSearchEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(RoomConverters::class)
abstract class PinCatcherDatabase : RoomDatabase() {
    abstract fun flowDao(): FlowDao
    abstract fun sessionDao(): SessionDao
    abstract fun bodyDao(): BodyDao

    companion object {
        const val NAME = "pincatcher.db"
    }
}
