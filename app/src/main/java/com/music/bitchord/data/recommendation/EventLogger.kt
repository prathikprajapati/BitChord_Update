package com.music.bitchord.data.recommendation

import android.content.Context
import android.util.Log
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.min

/**
 * Unified event logging for the recommendation system.
 *
 * Captures user interactions (play, skip, like, search, etc.) and persists them
 * to the recommendation database for training the hybrid model.
 *
 * ## Design Principles
 *
 * - **Batched writes**: Events are accumulated and written in batches to reduce I/O
 * - **Non-blocking**: Logging never blocks playback or UI threads
 * - **Loss-tolerant**: In-memory queue is flushed periodically; minor loss acceptable
 * - **Privacy-first**: All data stays on-device, no external transmission
 */
object EventLogger {

    private const val TAG = "EventLogger"
    private const val BATCH_SIZE = 10
    private const val FLUSH_INTERVAL_MS = 30_000L // 30 seconds
    private const val MAX_QUEUE_SIZE = 1000

    private lateinit var database: RecommendationDatabase
    private val dao get() = database.listeningEventDao()
    
    private val eventQueue = ConcurrentLinkedQueue<ListeningEvent>()
    private val flushMutex = Mutex()
    private var lastFlushAt = 0L
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    @Volatile
    private var initialized = false

    /**
     * Initialize the event logger with application context.
     */
    fun init(context: Context) {
        if (initialized) return
        database = RecommendationDatabase.getInstance(context)
        initialized = true
        Log.d(TAG, "EventLogger initialized")
        
        // Schedule periodic flush
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(FLUSH_INTERVAL_MS)
                flush()
            }
        }
    }

    /**
     * Log a playback start event.
     */
    fun onPlayStarted(song: Song, source: PlaybackSource = PlaybackSource.UNKNOWN) {
        logEvent(
            videoId = song.videoId,
            eventType = EventType.PLAY_START,
            metadata = mapOf(
                "title" to song.title,
                "artist" to song.artist,
                "album" to (song.albumName ?: ""),
                "source" to source.name,
                "duration_ms" to song.durationMillis().toString()
            )
        )
    }

    /**
     * Log a playback completion event.
     */
    fun onPlayCompleted(song: Song, playDurationMs: Long) {
        logEvent(
            videoId = song.videoId,
            eventType = EventType.PLAY_COMPLETE,
            metadata = mapOf(
                "play_duration_ms" to playDurationMs.toString(),
                "completion_ratio" to computeCompletionRatio(playDurationMs, song.durationMillis())
            )
        )
    }

    /**
     * Log a skip event.
     */
    fun onSkip(song: Song, playedDurationMs: Long, reason: SkipReason = SkipReason.USER_ACTION) {
        logEvent(
            videoId = song.videoId,
            eventType = EventType.SKIP,
            metadata = mapOf(
                "played_duration_ms" to playedDurationMs.toString(),
                "skip_reason" to reason.name,
                "skip_ratio" to computeCompletionRatio(playedDurationMs, song.durationMillis())
            )
        )
    }

    /**
     * Log a like/favorite event.
     */
    fun onLike(song: Song, liked: Boolean) {
        logEvent(
            videoId = song.videoId,
            eventType = if (liked) EventType.LIKE else EventType.UNLIKE,
            metadata = emptyMap()
        )
    }

    /**
     * Log a search interaction.
     */
    fun onSearch(query: String, resultSong: Song?, position: Int) {
        logEvent(
            videoId = resultSong?.videoId ?: "",
            eventType = EventType.SEARCH_RESULT,
            metadata = mapOf(
                "query" to query,
                "position" to position.toString(),
                "clicked" to (resultSong != null).toString()
            )
        )
    }

    /**
     * Log a recommendation impression (when a song is shown to user).
     */
    fun onRecommendationShown(song: Song, recommendationType: RecommendationType, position: Int) {
        logEvent(
            videoId = song.videoId,
            eventType = EventType.REC_IMPRESSION,
            metadata = mapOf(
                "rec_type" to recommendationType.name,
                "position" to position.toString()
            )
        )
    }

    /**
     * Log a recommendation click (when user selects a recommended song).
     */
    fun onRecommendationClicked(song: Song, recommendationType: RecommendationType, position: Int) {
        logEvent(
            videoId = song.videoId,
            eventType = EventType.REC_CLICK,
            metadata = mapOf(
                "rec_type" to recommendationType.name,
                "position" to position.toString()
            )
        )
    }

    /**
     * Log session start/end events.
     */
    fun onSessionStart(sessionId: String) {
        logEvent(
            videoId = "",
            eventType = EventType.SESSION_START,
            metadata = mapOf("session_id" to sessionId)
        )
    }

    fun onSessionEnd(sessionId: String, totalPlays: Int, totalDurationMs: Long) {
        logEvent(
            videoId = "",
            eventType = EventType.SESSION_END,
            metadata = mapOf(
                "session_id" to sessionId,
                "total_plays" to totalPlays.toString(),
                "total_duration_ms" to totalDurationMs.toString()
            )
        )
    }

    /**
     * Core logging mechanism - adds event to queue and triggers batch flush if needed.
     */
    private fun logEvent(videoId: String, eventType: EventType, metadata: Map<String, String>) {
        if (!initialized) {
            Log.w(TAG, "EventLogger not initialized, dropping event: $eventType")
            return
        }

        val event = ListeningEvent(
            id = 0, // Auto-generated by Room
            videoId = videoId,
            timestamp = System.currentTimeMillis(),
            eventType = eventType,
            metadataJson = serializeMetadata(metadata)
        )

        eventQueue.offer(event)

        // Prune queue if it grows too large (drop oldest events)
        if (eventQueue.size > MAX_QUEUE_SIZE) {
            eventQueue.poll() // Remove oldest
            Log.w(TAG, "Event queue full, dropped oldest event")
        }

        // Flush if batch size reached
        if (eventQueue.size >= BATCH_SIZE) {
            flushAsync()
        }
    }

    /**
     * Flush pending events to database asynchronously.
     */
    private fun flushAsync() {
        scope.launch {
            flush()
        }
    }

    /**
     * Flush pending events to database (suspend function).
     */
    suspend fun flush() = flushMutex.withLock {
        if (eventQueue.isEmpty()) return@withLock

        val eventsToWrite = mutableListOf<ListeningEvent>()
        var event = eventQueue.poll()
        while (event != null) {
            eventsToWrite.add(event)
            event = eventQueue.poll()
        }

        try {
            dao.insertAll(eventsToWrite)
            lastFlushAt = System.currentTimeMillis()
            Log.d(TAG, "Flushed ${eventsToWrite.size} events to database")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to flush events", e)
            // Re-queue events on failure (add back to front of queue)
            eventsToWrite.reversed().forEach { eventQueue.offer(it) }
        }
    }

    /**
     * Get recent events for debugging/analysis.
     */
    suspend fun getRecentEvents(limit: Int = 50): List<ListeningEvent> {
        return dao.getRecentEvents(limit)
    }

    /**
     * Clear all events (for testing/debugging).
     */
    suspend fun clearAllEvents() {
        dao.deleteAll()
        eventQueue.clear()
        Log.d(TAG, "All events cleared")
    }

    /**
     * Compute completion ratio as string for metadata.
     */
    private fun computeCompletionRatio(playedMs: Long, totalMs: Long): String {
        if (totalMs <= 0) return "1.0"
        return String.format("%.2f", min(playedMs.toFloat() / totalMs, 1.0f))
    }

    /**
     * Simple JSON serialization for metadata map.
     * Using a basic format since we don't need full JSON library overhead.
     */
    private fun serializeMetadata(metadata: Map<String, String>): String {
        if (metadata.isEmpty()) return "{}"
        return metadata.entries.joinToString(
            prefix = "{",
            postfix = "}",
            separator = ","
        ) { "\"${it.key}\":\"${it.value.replace("\"", "\\\"")}\"" }
    }
}

/**
 * Types of user interaction events.
 */
enum class EventType {
    PLAY_START,
    PLAY_COMPLETE,
    SKIP,
    LIKE,
    UNLIKE,
    SEARCH_RESULT,
    REC_IMPRESSION,      // Recommendation shown
    REC_CLICK,          // Recommendation clicked
    SESSION_START,
    SESSION_END,
    SHUFFLE_TOGGLE,
    REPEAT_TOGGLE,
    QUEUE_ADD,
    QUEUE_REMOVE,
    VOLUME_CHANGE
}

/**
 * Sources where playback can originate.
 */
enum class PlaybackSource {
    HOME_FEED,
    QUICK_PICKS,
    SEARCH,
    LIBRARY,
    PLAYLIST,
    ALBUM,
    ARTIST,
    RADIO,
    AUTOPLAY,
    RECOMMENDATION,
    EXTERNAL,
    UNKNOWN
}

/**
 * Reasons for skipping a track.
 */
enum class SkipReason {
    USER_ACTION,
    AUTO_SKIP,
    ERROR,
    END_OF_QUEUE,
    LOW_COMPLETION
}

/**
 * Types of recommendations for tracking effectiveness.
 */
enum class RecommendationType {
    HOME_FEED,
    QUICK_PICKS,
    RELATED_SONGS,
    RADIO,
    AUTOPLAY,
    DISCOVER_WEEKLY,
    DAILY_MIX,
    MOOD_PLAYLIST
}
