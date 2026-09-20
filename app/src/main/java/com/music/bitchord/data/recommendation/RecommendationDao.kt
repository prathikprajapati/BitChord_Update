package com.music.bitchord.data.recommendation

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ─────────────────────────────────────────────────────────────────────────────
// Data Access Objects (DAOs)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * DAO for listening events.
 * Optimized for high-frequency writes and time-range queries.
 */
@Dao
interface ListeningEventDao {
    
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: ListeningEvent): Long
    
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(events: List<ListeningEvent>): List<Long>
    
    @Query("SELECT * FROM listening_event WHERE videoId = :videoId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastEventForVideo(videoId: String): ListeningEvent?
    
    @Query("SELECT * FROM listening_event WHERE timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp ASC")
    suspend fun getEventsInRange(startTime: Long, endTime: Long): List<ListeningEvent>
    
    @Query("SELECT * FROM listening_event WHERE timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp ASC")
    fun getEventsInRangeFlow(startTime: Long, endTime: Long): Flow<List<ListeningEvent>>
    
    @Query("SELECT COUNT(*) FROM listening_event WHERE videoId = :videoId AND eventType = 'PLAY_START'")
    suspend fun getPlayCountForVideo(videoId: String): Int
    
    @Query("SELECT COUNT(*) FROM listening_event WHERE videoId = :videoId AND eventType = 'SKIP'")
    suspend fun getSkipCountForVideo(videoId: String): Int
    
    @Query("SELECT AVG(positionMs * 1.0 / durationMs) FROM listening_event WHERE videoId = :videoId AND durationMs > 0")
    suspend fun getAverageCompletionRate(videoId: String): Float?
    
    @Query("DELETE FROM listening_event WHERE timestamp < :cutoffTime")
    suspend fun deleteEventsOlderThan(cutoffTime: Long): Int
    
    @Query("SELECT DISTINCT videoId FROM listening_event ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentVideos(limit: Int = 100): List<String>
    
    @Query("""
        SELECT videoId, COUNT(*) as playCount 
        FROM listening_event 
        WHERE eventType = 'PLAY_START' AND timestamp >= :startTime 
        GROUP BY videoId 
        ORDER BY playCount DESC 
        LIMIT :limit
    """)
    suspend fun getMostPlayedVideos(startTime: Long, limit: Int = 50): List<VideoPlayCount>
    
    @Query("""
        SELECT videoId, SUM(CASE WHEN positionMs * 1.0 / durationMs >= 0.9 THEN 1 ELSE 0 END) as completes,
               SUM(CASE WHEN positionMs * 1.0 / durationMs < 0.3 THEN 1 ELSE 0 END) as skips
        FROM listening_event 
        WHERE timestamp >= :startTime 
        GROUP BY videoId 
        HAVING completes > 0
        ORDER BY (completes * 1.0 / (completes + skips + 1)) DESC
        LIMIT :limit
    """)
    suspend fun getBestEngagementVideos(startTime: Long, limit: Int = 50): List<VideoEngagement>
    
    // New methods for EventLogger compatibility
    @Query("SELECT * FROM listening_event WHERE eventType = 'PLAY_START' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentPlayEvents(limit: Int = 50): List<ListeningEvent>
    
    @Query("SELECT * FROM listening_event ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastPlayedSong(): ListeningEvent?
    
    @Query("SELECT videoId, COUNT(*) as playCount FROM listening_event WHERE eventType = 'PLAY_START' GROUP BY videoId ORDER BY playCount DESC LIMIT :limit")
    suspend fun getMostPlayedSongs(limit: Int = 50): List<VideoPlayCount>
    
    @Query("SELECT * FROM listening_event ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentEvents(limit: Int = 50): List<ListeningEvent>
    
    @Query("DELETE FROM listening_event")
    suspend fun deleteAll()
}

/**
 * Helper data class for video play counts
 */
data class VideoPlayCount(
    val videoId: String,
    val playCount: Int
)

/**
 * Helper data class for video engagement metrics
 */
data class VideoEngagement(
    val videoId: String,
    val completes: Int,
    val skips: Int
)

/**
 * DAO for song features.
 * Supports fast lookups and similarity searches.
 */
@Dao
interface SongFeaturesDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(features: SongFeatures)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(features: SongFeatures)
    
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(features: List<SongFeatures>)
    
    @Query("SELECT * FROM song_features WHERE videoId = :videoId")
    suspend fun getFeatures(videoId: String): SongFeatures?
    
    @Query("SELECT * FROM song_features WHERE videoId IN (:videoIds)")
    suspend fun getFeaturesForVideos(videoIds: List<String>): List<SongFeatures>
    
    @Query("SELECT * FROM song_features ORDER BY lastUpdated DESC")
    suspend fun getAllFeatures(): List<SongFeatures>
    
    @Query("SELECT * FROM song_features WHERE energy BETWEEN :minEnergy AND :maxEnergy AND valence BETWEEN :minValence AND :maxValence LIMIT :limit")
    suspend fun getSongsByMood(minEnergy: Float, maxEnergy: Float, minValence: Float, maxValence: Float, limit: Int = 50): List<SongFeatures>
    
    @Query("SELECT * FROM song_features WHERE tempo BETWEEN :minTempo AND :maxTempo LIMIT :limit")
    suspend fun getSongsByTempo(minTempo: Float, maxTempo: Float, limit: Int = 50): List<SongFeatures>
    
    @Query("SELECT * FROM song_features WHERE genre = :genre LIMIT :limit")
    suspend fun getSongsByGenre(genre: String, limit: Int = 50): List<SongFeatures>
    
    @Query("SELECT * FROM song_features WHERE mood = :mood LIMIT :limit")
    suspend fun getSongsByMoodTag(mood: String, limit: Int = 50): List<SongFeatures>
    
    @Query("SELECT COUNT(*) FROM song_features")
    suspend fun getTotalCount(): Int
    
    @Query("DELETE FROM song_features WHERE lastUpdated < :cutoffTime")
    suspend fun deleteOldFeatures(cutoffTime: Long): Int
}

/**
 * DAO for user profile.
 * Single-user design with easy multi-user extension.
 */
@Dao
interface UserProfileDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(profile: UserProfile)
    
    @Query("SELECT * FROM user_profile WHERE userId = :userId")
    suspend fun getProfile(userId: String = "default"): UserProfile?
    
    @Query("SELECT * FROM user_profile WHERE userId = :userId")
    fun getProfileFlow(userId: String = "default"): Flow<UserProfile?>
    
    @Query("UPDATE user_profile SET explorationFactor = :factor, lastUpdated = :timestamp WHERE userId = :userId")
    suspend fun updateExplorationFactor(factor: Float, timestamp: Long, userId: String = "default")
    
    @Query("UPDATE user_profile SET diversityFactor = :factor, lastUpdated = :timestamp WHERE userId = :userId")
    suspend fun updateDiversityFactor(factor: Float, timestamp: Long, userId: String = "default")
}

/**
 * DAO for artist similarity.
 * Pre-computed similarities for fast recommendations.
 */
@Dao
interface ArtistSimilarityDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(similarity: ArtistSimilarity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(similarities: List<ArtistSimilarity>)
    
    @Query("""
        SELECT artistId2, similarity 
        FROM artist_similarity 
        WHERE artistId1 = :artistId 
        ORDER BY similarity DESC 
        LIMIT :limit
    """)
    suspend fun getSimilarArtists(artistId: String, limit: Int = 20): List<ArtistScore>
    
    @Query("""
        SELECT artistId1, similarity 
        FROM artist_similarity 
        WHERE artistId2 = :artistId 
        ORDER BY similarity DESC 
        LIMIT :limit
    """)
    suspend fun getSimilarArtistsReverse(artistId: String, limit: Int = 20): List<ArtistScore>
    
    @Query("SELECT similarity FROM artist_similarity WHERE artistId1 = :artistId1 AND artistId2 = :artistId2")
    suspend fun getSimilarity(artistId1: String, artistId2: String): Float?
    
    @Query("DELETE FROM artist_similarity WHERE lastUpdated < :cutoffTime")
    suspend fun deleteOldSimilarities(cutoffTime: Long): Int
}

/**
 * Helper data class for artist scores
 */
data class ArtistScore(
    val artistId1: String,
    val artistId2: String,
    val similarity: Float
)

/**
 * DAO for song similarity.
 * Content-based similarity for radio and related songs.
 */
@Dao
interface SongSimilarityDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(similarity: SongSimilarity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(similarities: List<SongSimilarity>)
    
    @Query("""
        SELECT similarVideoId, similarityScore, title, artist
        FROM song_similarity 
        WHERE videoId = :videoId AND similarityScore >= :minScore
        ORDER BY similarityScore DESC 
        LIMIT :limit
    """)
    suspend fun getSimilarSongs(videoId: String, minScore: Float = 0.3f, limit: Int = 50): List<SongSimilarity>
    
    @Query("SELECT similarityScore FROM song_similarity WHERE videoId = :videoId1 AND similarVideoId = :videoId2")
    suspend fun getSimilarity(videoId1: String, videoId2: String): Float?
    
    @Query("DELETE FROM song_similarity WHERE lastComputed < :cutoffTime")
    suspend fun deleteOldSimilarities(cutoffTime: Long): Int
    
    @Query("SELECT COUNT(*) FROM song_similarity")
    suspend fun getTotalCount(): Int
}

/**
 * DAO for recommendation cache.
 * Fast retrieval of pre-computed recommendations.
 */
@Dao
interface RecommendationCacheDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(cache: RecommendationCache)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(caches: List<RecommendationCache>)
    
    @Query("SELECT * FROM recommendation_cache WHERE cacheKey = :cacheKey AND expiresAt > :currentTime")
    suspend fun getValidCache(cacheKey: String, currentTime: Long = System.currentTimeMillis()): RecommendationCache?
    
    @Query("SELECT recommendedIds FROM recommendation_cache WHERE cacheKey = :cacheKey AND expiresAt > :currentTime")
    suspend fun getValidRecommendations(cacheKey: String, currentTime: Long = System.currentTimeMillis()): String?
    
    @Query("DELETE FROM recommendation_cache WHERE expiresAt <= :currentTime")
    suspend fun deleteExpiredCaches(currentTime: Long = System.currentTimeMillis()): Int
    
    @Query("DELETE FROM recommendation_cache WHERE cacheKey = :cacheKey")
    suspend fun invalidateCache(cacheKey: String)
    
    @Query("SELECT COUNT(*) FROM recommendation_cache WHERE expiresAt > :currentTime")
    suspend fun getValidCacheCount(currentTime: Long = System.currentTimeMillis()): Int
}
