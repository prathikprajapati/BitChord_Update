package com.music.bitchord.data.recommendation

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * SQLite database for BitChord's recommendation system.
 * 
 * Stores user listening history, song features, and similarity matrices
 * for fast, privacy-first recommendations that work offline.
 * 
 * Design principles:
 * - Privacy-first: No data leaves device
 * - Offline-first: All features work without internet
 * - Performance: Optimized indexes for <30ms query times
 * - Size: Aggressive pruning to keep storage <50MB
 */
@Database(
    entities = [
        ListeningEvent::class,
        SongFeatures::class,
        UserProfile::class,
        ArtistSimilarity::class,
        SongSimilarity::class,
        RecommendationCache::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class RecommendationDatabase : RoomDatabase() {
    
    abstract fun listeningEventDao(): ListeningEventDao
    abstract fun songFeaturesDao(): SongFeaturesDao
    abstract fun userProfileDao(): UserProfileDao
    abstract fun artistSimilarityDao(): ArtistSimilarityDao
    abstract fun songSimilarityDao(): SongSimilarityDao
    abstract fun recommendationCacheDao(): RecommendationCacheDao
    
    companion object {
        private const val DATABASE_NAME = "bitchord_recommendations.db"
        
        @Volatile
        private var INSTANCE: RecommendationDatabase? = null
        
        /**
         * Get singleton database instance.
         * Creates database on first call, reuses thereafter.
         */
        fun getDatabase(context: Context): RecommendationDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    RecommendationDatabase::class.java,
                    DATABASE_NAME
                )
                .addMigrations(MIGRATION_1_2)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        // Pre-create indexes for performance
                        db.execSQL("CREATE INDEX IF NOT EXISTS idx_listening_event_videoId ON listening_event(videoId)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS idx_listening_event_timestamp ON listening_event(timestamp)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS idx_listening_event_type ON listening_event(eventType)")
                    }
                })
                // Optimize for write-heavy workload (listening events)
                .setJournalMode(JournalMode.TRUNCATE)
                .build()
                .also { INSTANCE = it }
            }
        }
        
        /**
         * Migration from version 1 to 2 (if needed in future)
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Future migrations go here
            }
        }
        
        /**
         * Close database and clear instance.
         * Call only when app is shutting down.
         */
        fun close() {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}
