package com.music.bitchord.data.recommendation

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index
import androidx.room.TypeConverter
import java.util.Date

// ─────────────────────────────────────────────────────────────────────────────
// Data Models (Entities)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A single listening event: play, skip, like, repeat, etc.
 * 
 * Stored as append-only log for later aggregation and model training.
 * Automatically pruned after 6 months to control database size.
 */
@Entity(
    tableName = "listening_event",
    indices = [
        Index(value = ["videoId"]),
        Index(value = ["timestamp"]),
        Index(value = ["eventType"]),
        Index(value = ["videoId", "timestamp"]) // Composite index for time-series queries
    ]
)
data class ListeningEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    /** YouTube video ID or local file path hash */
    val videoId: String,
    
    /** Type of interaction */
    val eventType: EventType,
    
    /** When the event occurred (epoch milliseconds) */
    val timestamp: Long = System.currentTimeMillis(),
    
    /** Position in track when event occurred (milliseconds) */
    val positionMs: Long = 0L,
    
    /** Total track duration (for calculating completion percentage) */
    val durationMs: Long = 0L,
    
    /** Session ID for grouping related events */
    val sessionId: String? = null,
    
    /** Additional context (time of day, day of week, etc.) */
    val contextJson: String? = null
)

/** Types of user interactions with tracks */
enum class EventType {
    PLAY_START,     // Started playing (from EventLogger)
    PLAY_COMPLETE,  // Played to end (>90%)
    SKIP,           // Skipped before 30 seconds
    LIKE,           // Thumbs up / favorite
    UNLIKE,         // Removed like
    SEARCH_RESULT,  // Clicked from search
    REC_IMPRESSION, // Recommendation shown
    REC_CLICK,      // Recommendation clicked
    SESSION_START,  // Session began
    SESSION_END,    // Session ended
    SHUFFLE_TOGGLE, // Shuffle toggled
    REPEAT_TOGGLE,  // Repeat toggled
    QUEUE_ADD,      // Added to queue
    QUEUE_REMOVE,   // Removed from queue
    VOLUME_CHANGE,  // Volume changed
    // Legacy event types for backwards compatibility
    @Deprecated("Use PLAY_START")
    PLAY,           
    @Deprecated("Use PLAY_COMPLETE")
    COMPLETE,       
    SEEK,           
    @Deprecated("Use UNLIKE")
    DISLIKE,        
    REPEAT,         
    SHARE,          
    ADD_TO_PLAYLIST
}

/**
 * Audio features extracted from songs.
 * 
 * Cached locally to avoid re-extraction and enable fast similarity searches.
 * Features are normalized to 0.0-1.0 range where applicable.
 */
@Entity(
    tableName = "song_features",
    indices = [
        Index(value = ["videoId"], unique = true),
        Index(value = ["tempo"]),
        Index(value = ["energy"]),
        Index(value = ["valence"]),
        Index(value = ["genre"]),
        Index(value = ["mood"])
    ]
)
data class SongFeatures(
    @PrimaryKey
    val videoId: String,
    
    val title: String = "",
    val artist: String = "",
    
    /** Audio tempo in BPM (beats per minute), 0 if not analyzed */
    val tempo: Float = 0f,
    
    /** Energy level: 0.0 (calm) to 1.0 (intense) */
    val energy: Float = 0f,
    
    /** Valence: 0.0 (sad/negative) to 1.0 (happy/positive) */
    val valence: Float = 0f,
    
    /** Danceability: 0.0 (not danceable) to 1.0 (very danceable) */
    val danceability: Float = 0f,
    
    /** Acousticness: 0.0 (electronic) to 1.0 (acoustic) */
    val acousticness: Float = 0f,
    
    /** Instrumentalness: 0.0 (vocal) to 1.0 (instrumental) */
    val instrumentalness: Float = 0f,
    
    /** Liveness: 0.0 (studio) to 1.0 (live performance) */
    val liveness: Float = 0f,
    
    /** Speechiness: 0.0 (music) to 1.0 (spoken word) */
    val speechiness: Float = 0f,
    
    /** Loudness in dB, typically -60 to 0 */
    val loudness: Float = 0f,
    
    /** Audio fingerprint for similarity matching (compressed) */
    val audioFingerprint: ByteArray? = null,
    
    /** Genre tag (single primary genre) */
    val genre: String? = null,
    
    /** Mood tag (single primary mood) */
    val mood: String? = null,
    
    /** Pre-computed feature vector (comma-separated floats) for fast similarity search */
    val featureVector: String? = null,
    
    /** When features were last updated */
    val lastUpdated: Long = System.currentTimeMillis(),
    
    /** Analysis version (for re-analysis on schema changes) */
    val analysisVersion: Int = 1
)

/**
 * User preference profile built from listening history.
 * 
 * Updated incrementally as user listens to music.
 * Used for personalization and cold-start recommendations.
 */
@Entity(
    tableName = "user_profile",
    indices = [
        Index(value = ["userId"], unique = true)
    ]
)
data class UserProfile(
    @PrimaryKey
    val userId: String = "default", // Single user for now
    
    /** Preferred tempo range (min, max) */
    val preferredTempoMin: Float = 0f,
    val preferredTempoMax: Float = 200f,
    
    /** Preferred energy level (0-1) */
    val preferredEnergy: Float = 0.5f,
    
    /** Preferred valence (0-1) */
    val preferredValence: Float = 0.5f,
    
    /** Top artist IDs by listening time (JSON array) */
    val topArtists: String? = null,
    
    /** Top genre preferences (JSON object: genre -> weight) */
    val genrePreferences: String? = null,
    
    /** Top mood preferences (JSON object: mood -> weight) */
    val moodPreferences: String? = null,
    
    /** Typical listening hours (JSON array of hours 0-23) */
    val listeningHours: String? = null,
    
    /** Exploration vs exploitation preference (0=explore, 1=exploit) */
    val explorationFactor: Float = 0.3f,
    
    /** Diversity preference (0=similar, 1=varied) */
    val diversityFactor: Float = 0.5f,
    
    /** Profile last updated timestamp */
    val lastUpdated: Long = System.currentTimeMillis(),
    
    /** Total listening time in milliseconds */
    val totalListeningTimeMs: Long = 0L,
    
    /** Number of unique tracks played */
    val uniqueTracksPlayed: Int = 0
)

/**
 * Artist-to-artist similarity scores.
 * 
 * Pre-computed based on co-listening patterns and audio features.
 * Enables fast "artists you might like" recommendations.
 */
@Entity(
    tableName = "artist_similarity",
    indices = [
        Index(value = ["artistId1"]),
        Index(value = ["artistId2"]),
        Index(value = ["artistId1", "similarity"], order = ["DESC"])
    ]
)
data class ArtistSimilarity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    /** First artist ID */
    val artistId1: String,
    
    /** Second artist ID */
    val artistId2: String,
    
    /** Similarity score (0.0 to 1.0) */
    val similarity: Float,
    
    /** Number of co-occurrences in listening sessions */
    val coOccurrenceCount: Int = 0,
    
    /** When this similarity was computed */
    val lastUpdated: Long = System.currentTimeMillis()
)

/**
 * Song-to-song content similarity scores.
 * 
 * Based on audio feature similarity using cosine distance.
 * Used for "similar songs" and radio generation.
 */
@Entity(
    tableName = "song_similarity",
    indices = [
        Index(value = ["videoId"]),
        Index(value = ["similarVideoId"]),
        Index(value = ["videoId", "similarityScore"], order = ["DESC"])
    ]
)
data class SongSimilarity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    /** Source song ID */
    val videoId: String,
    
    /** Similar song ID */
    val similarVideoId: String,
    
    /** Overall similarity score (0.0 to 1.0) */
    val similarityScore: Float,
    
    /** Title of similar song (cached for display) */
    val title: String? = null,
    
    /** Artist of similar song (cached for display) */
    val artist: String? = null,
    
    /** When this similarity was computed */
    val lastComputed: Long = System.currentTimeMillis()
)

/**
 * Cached recommendation results.
 * 
 * Stores pre-computed recommendations for fast retrieval.
 * Automatically expired after TTL (time-to-live).
 */
@Entity(
    tableName = "recommendation_cache",
    indices = [
        Index(value = ["cacheKey"], unique = true),
        Index(value = ["expiresAt"]),
        Index(value = ["recommendationType"])
    ]
)
data class RecommendationCache(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    /** Unique cache key (e.g., "quick_picks:user1") */
    val cacheKey: String,
    
    /** Type of recommendation */
    val recommendationType: String,
    
    /** Serialized list of recommended video IDs (JSON array) */
    val recommendedIds: String,
    
    /** When this cache entry was created */
    val createdAt: Long = System.currentTimeMillis(),
    
    /** When this cache entry expires */
    val expiresAt: Long
)

// ─────────────────────────────────────────────────────────────────────────────
// Type Converters for Room
// ─────────────────────────────────────────────────────────────────────────────

class Converters {
    
    @TypeConverter
    fun fromEventType(eventType: EventType): String {
        return eventType.name
    }
    
    @TypeConverter
    fun toEventType(value: String): EventType {
        return EventType.valueOf(value)
    }
    
    @TypeConverter
    fun fromByteArray(bytes: ByteArray?): String? {
        return bytes?.let { android.util.Base64.encodeToString(it, android.util.Base64.DEFAULT) }
    }
    
    @TypeConverter
    fun toByteArray(value: String?): ByteArray? {
        return value?.let { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }
    }
    
    @TypeConverter
    fun fromDate(date: Date): Long {
        return date.time
    }
    
    @TypeConverter
    fun toDate(timestamp: Long): Date {
        return Date(timestamp)
    }
}
