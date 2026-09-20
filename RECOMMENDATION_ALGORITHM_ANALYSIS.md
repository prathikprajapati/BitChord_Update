# BitChord Recommendation Algorithm Analysis & Improvement Plan

## Executive Summary

This document analyzes BitChord's current recommendation system, compares it with Spotify's sophisticated recommendation engine, and provides a prioritized improvement roadmap.

---

## 1. Current BitChord Recommendation Algorithm

### 1.1 Architecture Overview

BitChord operates as a **YouTube Music client**, meaning it does not maintain its own independent recommendation engine. Instead, it:

1. **Relies entirely on YouTube Music's API** (Innertube) for all recommendations
2. **Acts as a passive consumer** of YouTube's recommendation data
3. **Implements client-side filtering and presentation logic** only

### 1.2 Current Recommendation Sources

#### A. Home Feed Recommendations (`YtMusicRepository.kt`)
```kotlin
suspend fun home(): Result<HomeFeed> = call("home") {
    val home = Innertube.browse("FEmusic_home")
    HomeFeed(InnertubeParser.parseHome(home), InnertubeParser.continuationToken(home))
}
```

**Characteristics:**
- Fetches personalized shelves from YouTube Music's `FEmusic_home` endpoint
- Parses carousel shelves and plain shelves via `InnertubeParser.parseHome()`
- Includes "Quick Picks", "Mix", and "Recommendations" shelves
- Excludes video-only content and history-based shelves when appropriate

#### B. Quick Picks (`quickPicks()` function)
**Implementation Strategy:**
1. First attempts to find explicit "Quick picks"/"Mix"/"Recommendations" shelf
2. Falls back to all home shelves (excluding recent/history)
3. Further falls back to Explore/New Releases
4. Deduplicates by videoId

**Key Limitation:** Purely relies on YouTube's curation; no client-side intelligence

#### C. Radio/AutoPlay Recommendations
```kotlin
suspend fun radio(videoId: String): Result<List<Song>> = call("radio:$videoId") {
    InnertubeParser.parseWatchQueue(Innertube.next(videoId))
}
```

**How it works:**
- Uses YouTube's `next` endpoint to get radio queue based on seed track
- Parsed via `parseWatchQueue()` which extracts `playlistPanelVideoRenderer` items
- AutoPlay feature in `PlaybackService.kt` continuously queues related tracks
- Maintains `MAX_QUEUED_AUTOPLAY = 10` tracks ahead

#### D. Listening History Tracking
```kotlin
// PlaybackTracker.kt - Registers plays to YouTube Music history
object PlaybackTracker {
    fun onPlaying(videoId: String) { /* Sends playback start ping */ }
    fun onProgress(videoId: String, positionSeconds: Long) { /* Reports watch time */ }
    fun onPlaybackFinished(positionSeconds: Long) { /* Final report */ }
}
```

**Purpose:** Ensures YouTube's recommendation engine receives listening signals

#### E. Local Listening Statistics (`ListeningRecorder.kt`)
- Tracks play counts and listening minutes locally
- Uses wall-clock sampling (not position-based) for accuracy
- Enriches tracks with album/artist data via `trackLinks()` lookup
- **NOT used for generating recommendations** - only for stats display

### 1.3 Key Characteristics of Current System

| Aspect | Implementation |
|--------|---------------|
| **Source** | 100% YouTube Music API |
| **Personalization** | Delegated to YouTube (via cookie-authenticated requests) |
| **Client Intelligence** | Minimal (filtering, deduplication, fallback logic) |
| **Feedback Loop** | Sends play data to YouTube, but doesn't use it locally |
| **Offline Capability** | None (requires API calls) |
| **Cross-Source** | No integration between local library and recommendations |

---

## 2. Spotify's Recommendation Algorithm (Reference Architecture)

### 2.1 Multi-Model Ensemble Approach

Spotify employs **three distinct recommendation models** working in concert:

#### A. Collaborative Filtering Model
- **Technique:** Matrix factorization + Neural collaborative filtering
- **Data Source:** User-item interaction matrix (plays, skips, saves, playlist adds)
- **Mechanism:** 
  - Maps users and tracks into latent space
  - Finds users with similar taste profiles
  - Recommends tracks liked by similar users
- **Strength:** Discovers serendipitous connections across genres

#### B. Natural Language Processing (NLP) Model
- **Technique:** Scrapes web for text about songs/artists
- **Data Sources:** 
  - News articles, blogs, reviews, forums
  - Playlist descriptions and titles
  - Social media mentions
- **Mechanism:**
  - Builds "cultural vectors" from term frequency analysis
  - Creates "top terms" descriptors for each track
  - Matches tracks with similar linguistic profiles
- **Strength:** Captures cultural context and emerging trends

#### C. Audio Analysis Model (Raw Audio Features)
- **Technique:** CNNs and acoustic feature extraction
- **Features Analyzed:**
  - **Temporal:** Tempo, rhythm stability, beat strength
  - **Timbral:** Spectral centroid, roughness, brightness
  - **Tonal:** Key, mode, harmonicity
  - **Structural:** Segments, sections, tatums
- **Mechanism:**
  - Raw audio → CNN → 500K+ dimensional feature vector
  - Clustering in audio space finds sonically similar tracks
- **Strength:** Works for new tracks with no play history

### 2.2 Hybrid Ensemble: "BaRTS" (Bandit Recommendations through Time Series)

**Architecture:**
```
User Context → [Collaborative Filter] ─┐
              [NLP Model] ──────────────┼→ Ensemble → Personalized Playlists
              [Audio Model] ────────────┘
                        ↓
              Reinforcement Learning Layer
                        ↓
              Real-time Feedback Adjustment
```

**Key Components:**

1. **Contextual Bandits:** Dynamically weights models based on:
   - Time of day
   - Day of week
   - User's current activity (inferred from behavior)
   - Device type

2. **Reinforcement Learning:**
   - Continuous feedback loop from skip rate, save rate, replay rate
   - Adjusts model weights per user in real-time
   - Explores vs. exploits trade-off managed algorithmically

3. **Sequence Modeling:**
   - Uses RNNs/LSTMs to model listening sessions as sequences
   - Predicts next track based on session context
   - Accounts for mood progression within sessions

### 2.3 Feature Engineering Pipeline

**User Features:**
- Listening history (weighted by recency)
- Playlist creation patterns
- Skip behavior (early skip vs. late skip)
- Save/favorite actions
- Share behavior
- Search queries
- Time-series patterns (morning vs. evening preferences)

**Item Features:**
- Audio features (danceability, energy, valence, tempo, etc.)
- Cultural vectors (from NLP)
- Collaborative embeddings
- Release date, popularity trajectory
- Genre tags (multi-label)

**Context Features:**
- Session length so far
- Previous tracks in session
- Platform (mobile, desktop, smart speaker)
- Network quality (affects streaming quality preference)

### 2.4 Key Differentiators

| Feature | Spotify | BitChord |
|---------|---------|----------|
| **Models** | 3 ensemble models | 0 (delegated) |
| **Audio Analysis** | Yes (CNN on raw audio) | No |
| **NLP/Cultural** | Yes (web scraping) | No |
| **Collaborative Filtering** | Yes (matrix factorization) | No |
| **Real-time Adaptation** | Yes (RL + bandits) | No |
| **Session Modeling** | Yes (RNN sequences) | No |
| **A/B Testing** | Extensive infrastructure | None |
| **Cold Start Handling** | Audio + NLP models | None |
| **Explainability** | "Because you listened to X" | None |

---

## 3. Gap Analysis

### 3.1 Critical Gaps

#### 🔴 **No Independent Recommendation Engine**
- **Problem:** BitChord cannot recommend without YouTube API
- **Impact:** No offline recommendations, no cross-source recommendations
- **Severity:** High

#### 🔴 **No User Preference Learning**
- **Problem:** Listening data sent to YouTube but not used locally
- **Impact:** Cannot improve recommendations based on BitChord-specific behavior
- **Severity:** High

#### 🔴 **No Content-Based Filtering**
- **Problem:** No audio analysis, no metadata-based similarity
- **Impact:** Cannot recommend similar-sounding tracks independently
- **Severity:** High

#### 🟡 **No Collaborative Filtering**
- **Problem:** No community-driven recommendations
- **Impact:** Missing serendipitous discoveries from similar users
- **Severity:** Medium (requires user base)

#### 🟡 **No Context Awareness**
- **Problem:** Recommendations don't consider time, location, activity
- **Impact:** Less relevant suggestions for specific contexts
- **Severity:** Medium

#### 🟡 **No Feedback Loop Integration**
- **Problem:** Skip/save/replay behavior not fed back into ranking
- **Impact:** Static recommendations that don't adapt
- **Severity:** Medium

### 3.2 Architectural Constraints

1. **Privacy-First Design:** BitChord emphasizes privacy (optional sign-in, local playback)
   - Must respect this when designing any tracking
   
2. **Third-Party Client Status:** 
   - Cannot modify YouTube's algorithm
   - Must work within API limitations
   
3. **Open Source Nature:**
   - Algorithms must be transparent
   - Computational efficiency matters (runs on devices)

---

## 4. Improvement Plan

### Phase 1: Foundation (Months 1-3)
**Goal:** Establish basic client-side recommendation capability

#### 4.1.1 Implement Local User Profile
```kotlin
data class UserProfile(
    val userId: String,
    val genrePreferences: Map<String, Float>,      // Genre → affinity score
    val artistAffinity: Map<String, Float>,         // Artist → play count weighted
    val audioFeaturePreferences: AudioProfile?,     // If audio analysis enabled
    val listeningPatterns: TimePatterns,            // Time-of-day preferences
    val skipRate: Map<String, Float>,               // Track/artist skip rates
    val saveRate: Map<String, Float>                // Track/artist save rates
)
```

**Implementation:**
- Extend `ListeningRecorder.kt` to build profile incrementally
- Store in Room database with periodic flushes
- Compute weighted scores (recency decay: `score *= 0.95^days_since_play`)

#### 4.1.2 Basic Content-Based Filtering
```kotlin
object ContentBasedRecommender {
    fun recommend(seedTrack: Song, userProfile: UserProfile, limit: Int): List<Song> {
        // 1. Extract features from seed track
        // 2. Find tracks with similar metadata (genre, artist, era)
        // 3. Score by user's historical preferences
        // 4. Return top N
    }
}
```

**Features to Use:**
- Genre matching (from YouTube metadata)
- Artist similarity (same artist, featured artists)
- Era matching (release decade)
- Explicit content preference (learned from skip behavior)

#### 4.1.3 Enhanced Quick Picks Logic
**Current:** Simple fallback chain
**Improved:**
```kotlin
suspend fun quickPicks(excludeSongIds: Set<String>): Result<List<Song>> {
    val youtubeRecs = fetchYouTubeRecommendations()
    val localRecs = ContentBasedRecommender.recommend(...)
    
    // Hybrid approach: blend YouTube + local
    return rankAndMerge(youtubeRecs, localRecs, userProfile)
}
```

**Ranking Formula:**
```
finalScore = 0.6 * youtubeScore + 0.4 * localScore
youtubeScore = position_in_shelf / total_shelf_size
localScore = genreAffinity * artistAffinity * (1 - skipRate)
```

---

### Phase 2: Audio Intelligence (Months 4-6)
**Goal:** Add audio-based similarity independent of metadata

#### 4.2.1 Lightweight Audio Feature Extraction
**Approach:** Use pre-trained model (no training required)

**Option A: Essentia.js (WebAssembly)**
- Extract features client-side
- Features: tempo, key, energy, danceability, valence
- Pros: Runs in app, no server needed
- Cons: Adds ~5MB to APK

**Option B: Pre-computed Features from Open Sources**
- Use MusicBrainz + AcousticBrainz dataset
- Match tracks by MBID
- Pros: No runtime cost
- Cons: Limited coverage

**Recommended:** Hybrid approach
```kotlin
object AudioFeatureExtractor {
    suspend fun getFeatures(track: Song): AudioFeatures {
        // Try cached/pre-computed first
        return MusicBrainzCache.get(track.isrc) 
            ?: Essentia.extract(localFile(track))
    }
}

data class AudioFeatures(
    val tempo: Float,           // BPM
    val key: Int,               // 0-11 (C, C#, ..., B)
    val mode: Int,              // 0=minor, 1=major
    val energy: Float,          // 0-1
    val danceability: Float,    // 0-1
    val valence: Float,         // 0-1 (mood: sad→happy)
    val acousticness: Float,    // 0-1
    val instrumentalness: Float // 0-1
)
```

#### 4.2.2 Audio-Based Similarity
```kotlin
object AudioSimilarity {
    fun cosineSimilarity(a: AudioFeatures, b: AudioFeatures): Float {
        // Compute cosine similarity in 8D feature space
    }
    
    fun findSimilarTracks(seed: Song, audioDb: AudioDatabase, limit: Int): List<Song> {
        val seedFeatures = AudioFeatureExtractor.getFeatures(seed)
        return audioDb.allTracks
            .map { it to cosineSimilarity(seedFeatures, it.features) }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }
}
```

#### 4.2.3 Mood-Based Playlists
**Feature:** Auto-generate playlists based on audio characteristics

```kotlin
enum class Mood(val targetValence: Float, val targetEnergy: Float) {
    CHILL(0.3f, 0.3f),      // Low energy, neutral mood
    WORKOUT(0.7f, 0.8f),    // High energy, positive
    FOCUS(0.5f, 0.4f),      // Low energy, instrumental
    PARTY(0.8f, 0.9f),      // High energy, high valence
    MELANCHOLY(0.2f, 0.4f)  // Low valence, moderate energy
}

object MoodPlaylistGenerator {
    fun generate(mood: Mood, durationMinutes: Int): List<Song> {
        return audioDb.allTracks
            .filter { 
                abs(it.features.valence - mood.targetValence) < 0.2f &&
                abs(it.features.energy - mood.targetEnergy) < 0.2f
            }
            .take(durationMinutes * 3) // ~3 min per song
    }
}
```

---

### Phase 3: Advanced Personalization (Months 7-9)
**Goal:** Implement collaborative and contextual features

#### 4.3.1 Opt-In Anonymous Analytics
**Privacy-Preserving Approach:**
```kotlin
object AnonymousAnalytics {
    // Users can opt-in to share anonymized listening data
    data class AnonymizedPlay(
        val hashedUserId: String,  // SHA-256(random_salt + user_id)
        val trackHash: String,     // SHA-256(video_id)
        val timestamp: Long,
        val completionRate: Float, // How much was played
        val context: ContextType   // HOME, RADIO, SEARCH, PLAYLIST
    )
    
    // Aggregate statistics only, no individual tracking
    fun getGlobalTrends(): Map<String, Int> {
        return aggregatedDB.trendingTracks()
    }
}
```

#### 4.3.2 Simple Collaborative Filtering
**Matrix Factorization Lite:**
```kotlin
object CollaborativeFilter {
    // User-Item matrix (sparse)
    private val userItemMatrix: MutableMap<String, MutableMap<String, Float>> = mutableMapOf()
    
    fun findSimilarUsers(userId: String, k: Int = 10): List<String> {
        val userVector = userItemMatrix[userId] ?: return emptyList()
        return userItemMatrix.entries
            .filter { it.key != userId }
            .map { it.key to cosineSimilarity(userVector, it.value) }
            .sortedByDescending { it.second }
            .take(k)
            .map { it.first }
    }
    
    fun recommendForUser(userId: String, limit: Int): List<String> {
        val similarUsers = findSimilarUsers(userId)
        val userPlays = userItemMatrix[userId]?.keys ?: emptySet()
        
        // Aggregate tracks from similar users, weighted by similarity
        return similarUsers.flatMap { otherUser ->
            userItemMatrix[otherUser]?.entries?.filter { it.key !in userPlays }
                ?.map { it.key to it.value } ?: emptyList()
        }.groupingBy { it.first }
            .fold(0f) { acc, (_, score) -> acc + score }
            .toList()
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }
}
```

#### 4.3.3 Context-Aware Recommendations
```kotlin
data class ListeningContext(
    val hourOfDay: Int,
    val dayOfWeek: Int,
    val isWeekend: Boolean,
    val networkType: NetworkType,
    val deviceType: DeviceType,
    val sessionLength: Long,
    val previousTracks: List<String>
)

object ContextualRecommender {
    private val contextPatterns: MutableMap<String, Map<Int, Float>> = mutableMapOf()
    
    fun learn(context: ListeningContext, trackId: String, completionRate: Float) {
        val patternKey = "${context.hourOfDay}_${context.dayOfWeek}"
        val current = contextPatterns[patternKey] ?: emptyMap()
        contextPatterns[patternKey] = current + (trackId to 
            ((current[trackId] ?: 0f) + completionRate) / 2f)
    }
    
    fun adjustScores(candidates: List<Candidate>, context: ListeningContext): List<Candidate> {
        val patternKey = "${context.hourOfDay}_${context.dayOfWeek}"
        val pattern = contextPatterns[patternKey] ?: return candidates
        
        return candidates.map { candidate ->
            val contextBonus = pattern[candidate.trackId] ?: 0.5f
            candidate.copy(score = candidate.score * (1 + contextBonus))
        }
    }
}
```

---

### Phase 4: Machine Learning Integration (Months 10-12)
**Goal:** Deploy lightweight ML models for ranking

#### 4.4.1 On-Device Ranking Model
**Approach:** TensorFlow Lite model for final ranking

**Features for Model:**
```kotlin
data class RankingFeatures(
    // User features
    val userGenreAffinity: Float,
    val userArtistAffinity: Float,
    val userSkipRateForGenre: Float,
    
    // Item features
    val trackPopularity: Float,
    val trackReleaseRecency: Float,
    val audioFeatureSimilarity: Float,
    
    // Context features
    val timeOfDayMatch: Float,
    val sessionDiversity: Float,
    
    // Interaction features
    val userItemInteractionCount: Int,
    val similarUsersLikeRate: Float
)
```

**Model Architecture:**
```
Input (10 features) → Dense(32, ReLU) → Dense(16, ReLU) → Dense(1, Sigmoid)
Output: Probability of positive interaction (play > 30s)
```

**Training:**
- Collect training data from opt-in users
- Train offline, deploy model updates via app updates
- Or use federated learning (advanced)

#### 4.4.2 Exploration vs. Exploitation
**Multi-Armed Bandit for Diversity:**
```kotlin
object ExplorationManager {
    private val epsilon = 0.1f // 10% exploration
    
    fun selectTrack(candidates: List<RankedCandidate>): RankedCandidate {
        return if (Random.nextFloat() < epsilon) {
            // Explore: random selection from lower-ranked
            candidates.drop(candidates.size / 2).random()
        } else {
            // Exploit: best ranked
            candidates.first()
        }
    }
    
    fun updateBeliefs(candidate: RankedCandidate, outcome: Boolean) {
        // Update expected reward for similar candidates
        // Thompson Sampling or UCB algorithm
    }
}
```

---

## 5. Implementation Roadmap

### Timeline Overview

```
Month 1-3:  ██████████░░░░░░░░░░░░  Phase 1: Foundation
            ├─ Local user profiles
            ├─ Content-based filtering  
            └─ Enhanced Quick Picks
            
Month 4-6:  ░░░░██████████░░░░░░░░  Phase 2: Audio Intelligence
            ├─ Audio feature extraction
            ├─ Audio similarity matching
            └─ Mood-based playlists
            
Month 7-9:  ░░░░░░░░██████████░░░░  Phase 3: Advanced Personalization
            ├─ Anonymous analytics (opt-in)
            ├─ Collaborative filtering
            └─ Context-aware recommendations
            
Month 10-12:░░░░░░░░░░░░██████████  Phase 4: ML Integration
             ├─ TF Lite ranking model
             ├─ Exploration/exploitation
             └─ A/B testing framework
```

### Priority Matrix

| Feature | Impact | Effort | Priority |
|---------|--------|--------|----------|
| Local User Profiles | High | Low | **P0** |
| Content-Based Filtering | High | Medium | **P0** |
| Audio Feature Extraction | High | High | P1 |
| Enhanced Quick Picks | Medium | Low | **P0** |
| Mood Playlists | Medium | Medium | P1 |
| Anonymous Analytics | Medium | Medium | P2 |
| Collaborative Filtering | Low* | High | P3 |
| Context Awareness | Medium | Medium | P2 |
| ML Ranking Model | High | Very High | P3 |

*Low priority initially due to small user base

---

## 6. Technical Considerations

### 6.1 Privacy Preservation

**Design Principles:**
1. All processing on-device by default
2. Opt-in for any data sharing
3. No personally identifiable information stored
4. Regular data purging (e.g., 90-day rolling window)
5. Transparent about what data is collected

### 6.2 Performance Constraints

**Target Specifications:**
- Recommendation generation: < 500ms
- Memory footprint: < 50MB additional
- Storage: < 100MB for local databases
- Battery impact: < 2% daily drain

**Optimization Strategies:**
- Lazy loading of audio features
- Incremental profile updates (not batch recalculations)
- Cached recommendations with TTL (time-to-live)
- Background computation during charging

### 6.3 Offline Support

**Requirements:**
- Recommendations must work without network
- Sync when connectivity restored
- Conflict resolution for multi-device users

**Implementation:**
```kotlin
class OfflineRecommendationCache {
    private val cache: MutableMap<String, List<Song>> = mutableMapOf()
    private val timestamps: MutableMap<String, Long> = mutableMapOf()
    
    fun getCached(context: RecommendationContext): List<Song>? {
        val key = context.hash()
        val age = System.currentTimeMillis() - (timestamps[key] ?: 0)
        return if (age < CACHE_TTL_MS) cache[key] else null
    }
    
    companion object {
        const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L // 24 hours
    }
}
```

---

## 7. Success Metrics

### 7.1 Engagement Metrics

| Metric | Current | Target (6mo) | Target (12mo) |
|--------|---------|--------------|---------------|
| Skip Rate (first 30s) | Unknown | < 25% | < 20% |
| Session Length | Unknown | +20% | +40% |
| Return Rate (DAU/MAU) | Unknown | 35% | 45% |
| Saves per Session | Unknown | 1.5 | 2.5 |
| Playlist Creates/Month | Unknown | 2 | 5 |

### 7.2 Technical Metrics

| Metric | Target |
|--------|--------|
| Recommendation Latency | < 500ms p95 |
| Cache Hit Rate | > 80% |
| Model Size | < 10MB |
| Battery Impact | < 2%/day |

### 7.3 User Satisfaction

**Measurement Approaches:**
1. In-app thumbs up/down on recommendations
2. "Why this recommendation?" explainer clicks
3. Voluntary feedback surveys
4. App store review sentiment analysis

---

## 8. Risks and Mitigations

### 8.1 Technical Risks

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Audio features too slow | Medium | High | Pre-compute, cache aggressively |
| Model too large for mobile | Low | High | Quantization, pruning |
| Battery drain complaints | Medium | High | Aggressive background limits |
| Cold start problem | High | Medium | Fallback to YouTube recs |

### 8.2 Product Risks

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Users dislike new recs | Medium | High | A/B test, easy rollback |
| Privacy concerns | Low | High | Opt-in only, clear documentation |
| YouTube API changes | Medium | Medium | Abstraction layer, fallbacks |
| Feature bloat | High | Medium | Phased rollout, usage tracking |

---

## 9. Conclusion

### Current State Assessment

BitChord's recommendation system is **entirely dependent** on YouTube Music's algorithm, providing:
- ✅ Zero computational overhead
- ✅ Leverages YouTube's massive data advantage
- ❌ No offline capability
- ❌ No customization for BitChord-specific features
- ❌ No learning from BitChord user behavior
- ❌ No differentiation from official YouTube Music app

### Strategic Recommendation

**Adopt a hybrid approach:**
1. **Continue using YouTube recommendations** as primary source (leverage their R&D)
2. **Add client-side re-ranking** based on local signals
3. **Supplement with content-based filtering** for offline scenarios
4. **Gradually introduce audio analysis** for unique value-add

This approach:
- Respects privacy-first design
- Maintains offline functionality
- Adds unique BitChord value
- Keeps development manageable for open-source team

### Next Immediate Steps

1. **Week 1-2:** Design `UserProfile` data model and storage schema
2. **Week 3-4:** Implement enhanced `ListeningRecorder` with profile building
3. **Week 5-6:** Build basic `ContentBasedRecommender` with metadata matching
4. **Week 7-8:** Integrate into `quickPicks()` with hybrid ranking
5. **Week 9-10:** Beta test with existing users (opt-in)
6. **Week 11-12:** Iterate based on feedback, prepare Phase 2

---

## Appendix A: Code Templates

### A.1 UserProfile Database Schema
```kotlin
@Entity(tableName = "user_profiles")
data class UserProfileEntity(
    @PrimaryKey val userId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val genrePreferencesJson: String,
    val artistAffinityJson: String,
    val listeningPatternsJson: String
)

@Dao
interface UserProfileDao {
    @Query("SELECT * FROM user_profiles WHERE userId = :userId")
    suspend fun getProfile(userId: String): UserProfileEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProfile(profile: UserProfileEntity)
}
```

### A.2 Content-Based Recommender Skeleton
```kotlin
class ContentBasedRecommender(
    private val userProfileRepo: UserProfileRepository,
    private val trackMetadataDb: TrackMetadataDatabase
) {
    suspend fun recommend(
        seedTrack: Song,
        userId: String,
        limit: Int,
        excludeIds: Set<String>
    ): List<Song> {
        val profile = userProfileRepo.getProfile(userId) ?: return emptyList()
        val seedMetadata = trackMetadataDb.getMetadata(seedTrack.videoId)
        
        val candidates = trackMetadataDb.getAllTracks()
            .filter { it.videoId !in excludeIds }
            .map { track ->
                val score = computeSimilarity(seedMetadata, track, profile)
                Candidate(track, score)
            }
            .sortedByDescending { it.score }
            .take(limit)
        
        return candidates.map { it.track }
    }
    
    private fun computeSimilarity(
        seed: TrackMetadata,
        candidate: TrackMetadata,
        profile: UserProfile
    ): Float {
        var score = 0.5f // Base score
        
        // Genre match bonus
        if (seed.genre == candidate.genre) {
            score += profile.genrePreferences[seed.genre] ?: 0.2f
        }
        
        // Artist match bonus
        if (seed.artistId == candidate.artistId) {
            score += profile.artistAffinity[seed.artistId] ?: 0.3f
        }
        
        // Era match
        if (abs(seed.releaseYear - candidate.releaseYear) <= 5) {
            score += 0.1f
        }
        
        // Penalize high skip rate
        val skipRate = profile.skipRates[candidate.videoId] ?: 0f
        score *= (1 - skipRate * 0.5f)
        
        return score.coerceIn(0f, 1f)
    }
}
```

---

## Appendix B: Comparison Table - Full Feature Matrix

| Feature | Spotify | YouTube Music | BitChord (Current) | BitChord (Proposed) |
|---------|---------|---------------|-------------------|---------------------|
| Collaborative Filtering | ✅ | ✅ | ❌ | ⚠️ (Phase 3) |
| Audio Analysis | ✅ | ✅ | ❌ | ⚠️ (Phase 2) |
| NLP/Cultural Vectors | ✅ | ❌ | ❌ | ❌ |
| Context Awareness | ✅ | Partial | ❌ | ⚠️ (Phase 3) |
| Session Modeling | ✅ | Partial | ❌ | ❌ |
| Offline Recommendations | ✅ | ❌ | ❌ | ✅ (Phase 1) |
| Cross-Source Recs | ❌ | ❌ | ❌ | ✅ (Future) |
| Explainability | ✅ | ❌ | ❌ | ⚠️ (Future) |
| Privacy-Focused | ❌ | ❌ | ✅ | ✅ |
| Open Source | ❌ | ❌ | ✅ | ✅ |

Legend: ✅ = Implemented, ❌ = Not Implemented, ⚠️ = Planned

---

*Document Version: 1.0*  
*Last Updated: December 2024*  
*Author: AI Code Assistant*
