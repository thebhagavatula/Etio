package com.etio.ot.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.etio.ot.data.local.dao.CaseDao
import com.etio.ot.data.local.dao.ChecklistRunDao
import com.etio.ot.data.local.dao.DelayRecordDao
import com.etio.ot.data.local.dao.EventDao
import com.etio.ot.data.local.dao.GeneratedMessageDao
import com.etio.ot.data.local.entity.CaseEntity
import com.etio.ot.data.local.entity.ChecklistRunEntity
import com.etio.ot.data.local.entity.DelayRecordEntity
import com.etio.ot.data.local.entity.EventEntity
import com.etio.ot.data.local.entity.GeneratedMessageEntity

@Database(
    entities = [
        CaseEntity::class,
        EventEntity::class,
        DelayRecordEntity::class,
        ChecklistRunEntity::class,
        GeneratedMessageEntity::class,
    ],
    // 2: grounding flags on delay_records (T3).
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class EtioDatabase : RoomDatabase() {
    abstract fun caseDao(): CaseDao
    abstract fun eventDao(): EventDao
    abstract fun delayRecordDao(): DelayRecordDao
    abstract fun checklistRunDao(): ChecklistRunDao
    abstract fun generatedMessageDao(): GeneratedMessageDao

    companion object {
        private const val NAME = "etio.db"

        fun build(context: Context): EtioDatabase =
            Room.databaseBuilder(context.applicationContext, EtioDatabase::class.java, NAME)
                // Hackathon posture: a schema change wipes local data rather than
                // failing to launch 20 minutes before the pitch.
                .fallbackToDestructiveMigration()
                .build()
    }
}
