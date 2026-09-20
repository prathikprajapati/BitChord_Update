# BitChord Recommendation System Improvement Plan
## High-Speed, High-Quality, Lightweight Hybrid Model

---

## Executive Summary

**Goal:** Build a custom hybrid recommendation engine that operates locally within BitChord, prioritizing:
1. **Speed** (<50ms response time)
2. **Quality** (Personalized, diverse, serendipitous recommendations)
3. **Size** (<50MB storage footprint, <100MB RAM usage)

**Approach:** Develop **"BitChord Neural-Content Hybrid (BNCH)"** - A lightweight 2-stage ranking system combining content-based filtering with implicit collaborative signals, optimized for edge deployment.

---

## 1. Architecture Overview

### 1.1 System Design Philosophy

```
┌─────────────────────────────────────────────────────────────┐
│                    BitChord Client                          │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │   Stage 1    │  │   Stage 2    │  │   Caching    │      │
│  │  Candidate   │→ │  Ranking &   │→ │   Layer      │      │
│  │  Generation  │  │  Re-ranking  │  │  (LRU+TF)    │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
│         ↑                  ↑                  ↑              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │   Local      │  │   Audio      │  │   User       │      │
│  │   Profile    │  │   Features   │  │   Context    │      │
│  │   (SQLite)   │  │   (ONNX)     │  │   (Session)  │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
└─────────────────────────────────────────────────────────────┘
```

### 1.2 Key Constraints & Targets

| Metric | Target | Maximum Acceptable |
|--------|--------|-------------------|
| **Latency (P95)** | <30ms | <50ms |
| **Storage** | <30MB | <50MB |
| **RAM Usage** | <80MB | <100MB |
| **Cold Start Time** | <500ms | <1s |
| **Recommendation Quality (NDCG@10)** | >0.65 | >0.55 |
| **Diversity Score** | >0.45 | >0.35 |

---

## 2. The BNCH Hybrid Model

### 2.1 Model Components

#### Component A: Lightweight Content-Based Filter (CBF)
- **Purpose:** Fast candidate generation based on audio features and metadata
- **Model Size:** ~15MB
- **Latency:** <10ms
- **Technology:** TF-IDF + Cosine Similarity with locality-sensitive hashing (LSH)

**Features Used:**
- Audio features (tempo, energy, danceability, valence) - extracted via pre-trained ONNX model
- Metadata (genre, artist, release year, language)
- Lyrical themes (via keyword extraction from available metadata)

#### Component B: Implicit Collaborative Filtering (ICF)
- **Purpose:** Capture user behavior patterns without explicit ratings
- **Model Size:** ~20MB (user embeddings + item embeddings)
- **Latency:** <15ms
- **Technology:** Matrix Factorization (ALS - Alternating Least Squares) with dimensionality reduction

**Signals Tracked:**
- Play count (weighted by completion rate)
- Skip rate (negative signal)
- Repeat plays (strong positive signal)
- Time of day listening patterns
- Playlist additions

#### Component C: Contextual Re-ranker
- **Purpose:** Adjust recommendations based on real-time context
- **Model Size:** ~5MB
- **Latency:** <5ms
- **Technology:** Gradient Boosted Decision Trees (LightGBM) or simple rule-based scorer

**Context Features:**
- Time of day
- Day of week
- Session length so far
- Recent skip streak
- Current mood (inferred from recent listening)
- Device type (mobile/desktop)

### 2.2 Two-Stage Ranking Pipeline

```
Stage 1: Candidate Generation (20ms)
├── Retrieve 500 candidates from:
│   ├── Content-Based: 200 songs (similar to recently played)
│   ├── Collaborative: 200 songs (users like you listened to)
│   └── Trending/Exploration: 100 songs (new releases, diverse genres)
│
└── Apply fast filtering:
    ├── Remove already played in last 7 days (optional)
    ├── Remove explicitly disliked
    └── Apply content restrictions (if any)

Stage 2: Ranking & Re-ranking (10ms)
├── Score each candidate using ensemble:
│   Score = w1*CBF_score + w2*ICF_score + w3*Context_score + w4*Recency_bonus
│
├── Apply diversity constraints:
│   ├── Max 3 songs per artist in top 20
│   ├── Max 5 songs per genre in top 20
│   └── Ensure at least 20% exploration content
│
└── Return top N recommendations
```

### 2.3 Mathematical Formulation

**Final Score Calculation:**

```
Score(song, user, context) = 
    α × Sim_content(song, user_profile) +
    β × Pref_collaborative(user, song) +
    γ × Context_fit(song, context) +
    δ × Recency_decay(song, last_played) +
    ε × Diversity_penalty(song, current_list)

Where:
    α = 0.35 (Content weight)
    β = 0.35 (Collaborative weight)
    γ = 0.15 (Context weight)
    δ = 0.10 (Recency weight)
    ε = 0.05 (Diversity adjustment)
    
    All weights are tunable per user via online learning
```

---

## 3. Implementation Roadmap

### Phase 1: Foundation (Weeks 1-4)
**Goal:** Basic content-based filtering with local profile storage

#### Tasks:
1. **Database Schema Design** (Week 1)
   - Create SQLite tables for user profiles, listening history, song features
   - Implement efficient indexing strategies

2. **Audio Feature Extraction** (Week 2-3)
   - Integrate lightweight pre-trained model (e.g., Essentia.js or TensorFlow Lite)
   - Extract features for top 10,000 most-played songs first (lazy loading for others)
   - Cache features in local database

3. **Basic CBF Implementation** (Week 4)
   - TF-IDF vectorization on metadata
   - Cosine similarity computation with LSH for fast retrieval
   - Simple API endpoint: `GET /recommendations/content-based?limit=20`

#### Deliverables:
- ✅ Local user profile database
- ✅ Audio feature extraction pipeline
- ✅ Content-based recommendation engine
- ✅ Basic caching layer

#### Metrics:
- Latency: <20ms for content-based recs
- Storage: <20MB for 10k songs
- Quality: Baseline NDCG@10 measurement

---

### Phase 2: Collaborative Signals (Weeks 5-8)
**Goal:** Add implicit collaborative filtering with matrix factorization

#### Tasks:
1. **Implicit Feedback Collection** (Week 5)
   - Track play counts, skip rates, completion rates, repeat plays
   - Build user-item interaction matrix (sparse format)

2. **Matrix Factorization Model** (Week 6-7)
   - Implement ALS (Alternating Least Squares) for implicit feedback
   - Use dimensionality reduction (embeddings size = 64)
   - Train incrementally (online learning approach)

3. **Hybrid Scoring Integration** (Week 8)
   - Combine CBF + ICF scores with weighted ensemble
   - Implement dynamic weight adjustment based on confidence
   - A/B testing framework for weight tuning

#### Deliverables:
- ✅ Implicit feedback tracking system
- ✅ Matrix factorization model (ALS)
- ✅ Hybrid scoring engine
- ✅ Online learning pipeline

#### Metrics:
- Latency: <30ms for hybrid recs
- Storage: <35MB total
- Quality: NDCG@10 improvement >15% over Phase 1

---

### Phase 3: Contextual Awareness (Weeks 9-12)
**Goal:** Add context-aware re-ranking and diversity optimization

#### Tasks:
1. **Context Feature Engineering** (Week 9)
   - Time-of-day patterns
   - Session-based features
   - Mood inference from recent listening
   - Device and location context (if available)

2. **Re-ranking Model** (Week 10-11)
   - Implement LightGBM or XGBoost for ranking
   - Train on historical session data (what users actually played next)
   - Optimize for both relevance and diversity

3. **Diversity & Serendipity Controls** (Week 12)
   - Implement MMR (Maximal Marginal Relevance) for diversity
   - Add exploration/exploitation balance (ε-greedy or Thompson Sampling)
   - User controls for "discovery mode" vs "familiar mode"

#### Deliverables:
- ✅ Context feature extraction
- ✅ Learning-to-rank model
- ✅ Diversity optimization algorithms
- ✅ User preference controls

#### Metrics:
- Latency: <40ms end-to-end
- Storage: <45MB total
- Quality: NDCG@10 >0.60, Diversity Score >0.40

---

### Phase 4: Optimization & Production (Weeks 13-16)
**Goal:** Performance optimization, model compression, production hardening

#### Tasks:
1. **Model Compression** (Week 13)
   - Quantize neural network weights (int8 instead of float32)
   - Prune low-importance features
   - Knowledge distillation if using complex models

2. **Caching Strategy Enhancement** (Week 14)
   - Multi-level caching (L1: in-memory LRU, L2: SQLite, L3: file system)
   - Predictive pre-fetching based on user patterns
   - Cache invalidation strategies

3. **Performance Optimization** (Week 15)
   - Profile and optimize hot paths
   - Parallel processing for candidate generation
   - SIMD optimizations for similarity computations

4. **Monitoring & Continuous Learning** (Week 16)
   - Implement online metrics collection
   - Set up automated model retraining triggers
   - Create feedback loop for model improvement

#### Deliverables:
- ✅ Compressed models (<50MB total)
- ✅ Advanced caching system
- ✅ Performance monitoring dashboard
- ✅ Automated retraining pipeline

#### Metrics:
- Latency: <30ms P95
- Storage: <50MB total
- RAM: <100MB peak usage
- Quality: NDCG@10 >0.65

---

## 4. Technical Specifications

### 4.1 Database Schema (SQLite)

```sql
-- User Profile Table
CREATE TABLE user_profile (
    user_id TEXT PRIMARY KEY,
    created_at INTEGER,
    updated_at INTEGER,
    preferred_genres TEXT, -- JSON array
    preferred_languages TEXT, -- JSON array
    avg_session_length REAL,
    discovery_preference REAL -- 0.0 (familiar) to 1.0 (exploratory)
);

-- Listening History Table
CREATE TABLE listening_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id TEXT,
    song_id TEXT,
    timestamp INTEGER,
    completion_rate REAL, -- 0.0 to 1.0
    skipped BOOLEAN,
    repeated_count INTEGER,
    context_time_of_day INTEGER, -- 0-23
    context_day_of_week INTEGER, -- 0-6
    device_type TEXT,
    FOREIGN KEY (user_id) REFERENCES user_profile(user_id)
);

-- Song Features Table
CREATE TABLE song_features (
    song_id TEXT PRIMARY KEY,
    tempo REAL,
    energy REAL,
    danceability REAL,
    valence REAL,
    acousticness REAL,
    instrumentalness REAL,
    liveness REAL,
    speechiness REAL,
    loudness REAL,
    duration_ms INTEGER,
    genre TEXT,
    release_year INTEGER,
    language TEXT,
    feature_vector BLOB, -- Compressed TF-IDF vector
    last_updated INTEGER
);

-- User-Item Interaction Matrix (Sparse)
CREATE TABLE user_item_interactions (
    user_id TEXT,
    song_id TEXT,
    interaction_score REAL, -- Weighted score from plays, skips, etc.
    last_interaction INTEGER,
    interaction_count INTEGER,
    PRIMARY KEY (user_id, song_id)
);

-- Indexes for Performance
CREATE INDEX idx_history_user_timestamp ON listening_history(user_id, timestamp DESC);
CREATE INDEX idx_history_song ON listening_history(song_id);
CREATE INDEX idx_interactions_user ON user_item_interactions(user_id);
CREATE INDEX idx_interactions_song ON user_item_interactions(song_id);
CREATE INDEX idx_features_genre ON song_features(genre);
```

### 4.2 Data Structures

```typescript
// User Profile Structure
interface UserProfile {
  userId: string;
  preferences: {
    genres: Map<string, number>; // genre -> weight
    artists: Map<string, number>; // artist -> weight
    languages: Set<string>;
    eras: Map<string, number>; // decade -> weight
  };
  behavioralPatterns: {
    timeOfDayPreferences: number[]; // 24-hour distribution
    dayOfWeekPreferences: number[]; // 7-day distribution
    avgSessionLength: number;
    skipRate: number;
    discoveryScore: number; // 0-1
  };
  embeddings: Float32Array; // 64-dimensional user embedding
}

// Song Feature Structure
interface SongFeatures {
  songId: string;
  audioFeatures: {
    tempo: number;
    energy: number;
    danceability: number;
    valence: number;
    acousticness: number;
    instrumentalness: number;
    liveness: number;
    speechiness: number;
    loudness: number;
  };
  metadata: {
    genres: string[];
    releaseYear: number;
    language: string;
    artists: string[];
  };
  contentVector: SparseVector; // TF-IDF compressed
  embedding?: Float32Array; // 64-dimensional item embedding (if available)
}

// Recommendation Request/Response
interface RecommendationRequest {
  userId: string;
  limit: number;
  context: {
    timeOfDay: number;
    dayOfWeek: number;
    recentSongs: string[];
    sessionLength: number;
    deviceType: 'mobile' | 'desktop';
    mood?: 'chill' | 'energetic' | 'focus' | 'party';
  };
  filters?: {
    excludeGenres?: string[];
    excludeArtists?: string[];
    minReleaseYear?: number;
    discoveryMode?: boolean;
  };
}

interface RecommendationResponse {
  songs: Array<{
    songId: string;
    score: number;
    reasons: string[]; // Explainable AI: why this was recommended
  }>;
  metadata: {
    latencyMs: number;
    modelVersion: string;
    cacheHit: boolean;
  };
}
```

### 4.3 Algorithm Pseudocode

```python
def generate_recommendations(user_id, context, limit=20):
    # Stage 1: Candidate Generation
    candidates = []
    
    # Content-based candidates (200 songs)
    user_profile = load_user_profile(user_id)
    content_candidates = content_based_filter(
        user_profile=user_profile,
        recent_songs=context.recent_songs,
        limit=200
    )
    candidates.extend(content_candidates)
    
    # Collaborative candidates (200 songs)
    collaborative_candidates = collaborative_filter(
        user_id=user_id,
        user_embedding=user_profile.embeddings,
        limit=200
    )
    candidates.extend(collaborative_candidates)
    
    # Exploration candidates (100 songs)
    if context.discovery_mode:
        exploration_candidates = get_exploration_candidates(
            user_profile=user_profile,
            limit=100
        )
        candidates.extend(exploration_candidates)
    
    # Deduplicate and filter
    candidates = deduplicate(candidates)
    candidates = apply_filters(candidates, context.filters)
    
    # Stage 2: Ranking
    scored_candidates = []
    for song in candidates:
        # Calculate component scores
        cbf_score = calculate_content_similarity(song, user_profile)
        icf_score = calculate_collaborative_score(user_id, song.id)
        ctx_score = calculate_context_fit(song, context)
        recency_score = calculate_recency_bonus(song, context.recent_songs)
        
        # Ensemble scoring
        final_score = (
            0.35 * cbf_score +
            0.35 * icf_score +
            0.15 * ctx_score +
            0.10 * recency_score
        )
        
        scored_candidates.append({
            'song': song,
            'score': final_score,
            'breakdown': {
                'content': cbf_score,
                'collaborative': icf_score,
                'context': ctx_score,
                'recency': recency_score
            }
        })
    
    # Apply diversity constraints (MMR)
    ranked_list = maximal_marginal_relevance(
        candidates=scored_candidates,
        lambda_param=0.7,  # Balance relevance vs diversity
        limit=limit
    )
    
    # Add explainability
    for item in ranked_list:
        item['reasons'] = generate_explanation(item['breakdown'])
    
    return ranked_list[:limit]
```

---

## 5. Performance Optimization Strategies

### 5.1 Speed Optimizations

1. **Locality-Sensitive Hashing (LSH)**
   - Reduce O(n) similarity search to O(log n)
   - Use MinHash for Jaccard similarity on metadata
   - Use Random Projection for cosine similarity on audio features

2. **Approximate Nearest Neighbors (ANN)**
   - Implement FAISS-like index for embedded vectors
   - Trade off minimal accuracy (<2% loss) for 10x speedup

3. **Multi-Level Caching**
   ```
   L1 Cache: In-memory LRU (most recent 50 recommendations per user)
   L2 Cache: SQLite with TTL (last 500 recommendations)
   L3 Cache: Filesystem cache for song features (lazy loading)
   
   Cache Hit Rates Target:
   - L1: 40%
   - L2: 35%
   - L3: 15%
   - Miss: 10%
   ```

4. **Parallel Processing**
   - Generate candidates from multiple sources in parallel
   - Use Web Workers (Electron) or Worker Threads (Node.js)
   - SIMD instructions for vector operations

5. **Incremental Updates**
   - Update user embeddings incrementally (no full retrain)
   - Batch process interaction logs every 5 minutes
   - Lazy feature extraction for new songs

### 5.2 Size Optimizations

1. **Model Quantization**
   - Convert float32 weights to int8 (75% size reduction)
   - Use quantization-aware training if needed

2. **Feature Selection**
   - Identify and remove low-importance features
   - Use recursive feature elimination (RFE)
   - Target: Reduce from 50 features to 20 most impactful

3. **Embedding Compression**
   - Use product quantization for embeddings
   - Reduce embedding dimension from 64 to 32 if quality loss <5%
   - Store embeddings in sparse format where applicable

4. **Database Optimization**
   - Use WAL (Write-Ahead Logging) mode for SQLite
   - Compress BLOB data with LZ4 or Zstandard
   - Implement automatic VACUUM on startup

5. **Lazy Loading**
   - Only extract features for songs in user's library + frequently recommended
   - Download audio features on-demand with aggressive caching
   - Use progressive loading for large catalogs

### 5.3 Quality Optimizations

1. **Online Learning**
   - Adjust ensemble weights based on user feedback
   - Use multi-armed bandit for exploration/exploitation
   - Implement Thompson Sampling for dynamic weight tuning

2. **Negative Feedback Integration**
   - Strong penalty for skipped songs (especially early skips)
   - Temporary blacklist for repeatedly skipped songs
   - Down-weight similar songs after negative feedback

3. **Temporal Dynamics**
   - Decay old interactions exponentially (half-life = 30 days)
   - Boost recently discovered artists/genres
   - Account for seasonal trends (holiday music, summer vibes)

4. **Diversity Metrics**
   - Monitor intra-list diversity (genre, artist, era distribution)
   - Track catalog coverage (% of library exposed to user)
   - Measure serendipity (unexpected but relevant recommendations)

5. **Explainability**
   - Provide "Why this song?" explanations
   - Show similarity factors (audio features, collaborative signals)
   - Allow users to adjust recommendation factors

---

## 6. Comparison with Spotify's Model

| Aspect | Spotify | BitChord BNCH | Advantage |
|--------|---------|---------------|-----------|
| **Architecture** | 3-model ensemble (CF, NLP, Audio) + RL | 2-stage hybrid (CBF+ICF) + Context | BitChord: Simpler, faster |
| **Model Size** | ~500MB+ (cloud-based) | <50MB (local) | BitChord: 10x smaller |
| **Latency** | 100-200ms (network + compute) | <30ms (local) | BitChord: 3-6x faster |
| **Privacy** | Cloud-based, data collection | Fully local, no data leaving device | BitChord: Privacy-first |
| **Personalization** | Deep (millions of users) | Moderate (single-user focused) | Spotify: More data |
| **Cold Start** | Uses global trends | Uses content features + YouTube fallback | Tie (different approaches) |
| **Serendipity** | High (explore/exploit tuning) | Medium-High (configurable) | Spotify: More refined |
| **Offline Support** | Limited (downloaded playlists only) | Full (all recommendations work offline) | BitChord: Better offline |
| **Customization** | Fixed algorithm | User-tunable weights | BitChord: More control |
| **Resource Usage** | High (server-side) | Low (client-side) | BitChord: Efficient |

**Key Differentiators for BitChord:**
1. **Privacy-First:** No data leaves the device
2. **Offline-First:** Works without internet connection
3. **Transparent:** Users can see and adjust recommendation factors
4. **Lightweight:** Runs on low-end devices
5. **Open Source:** Community can audit and improve

---

## 7. Risk Analysis & Mitigation

### Risk 1: Cold Start Problem
**Description:** New users have no listening history
**Mitigation:**
- Use content-based filtering as default (works without history)
- Ask users to select favorite genres/artists on onboarding
- Leverage YouTube Music's trending/new release APIs temporarily
- Implement "taste builder" quiz during first session

### Risk 2: Filter Bubble
**Description:** Recommendations become too narrow over time
**Mitigation:**
- Enforce minimum diversity thresholds (20% exploration content)
- Implement "discovery mode" toggle for users
- Use contextual bandits to balance exploration/exploitation
- Periodically inject serendipitous recommendations

### Risk 3: Performance Degradation
**Description:** Model slows down as data grows
**Mitigation:**
- Implement automatic pruning of old interactions (>1 year)
- Use incremental updates instead of full retraining
- Monitor performance metrics and trigger optimization when needed
- Set hard limits on database size with automatic cleanup

### Risk 4: Quality Gap vs Spotify
**Description:** Recommendations not as good as industry leader
**Mitigation:**
- Focus on niche strengths (privacy, offline, customization)
- Allow hybrid mode: BNCH + YouTube Music recommendations
- Implement user feedback loops for continuous improvement
- Be transparent about limitations and roadmap

### Risk 5: Resource Constraints
**Description:** Exceeds size/speed targets on low-end devices
**Mitigation:**
- Implement adaptive quality based on device capabilities
- Provide "lite mode" with reduced features
- Allow users to configure storage limits
- Profile and optimize for lowest common denominator

---

## 8. Success Metrics & KPIs

### Primary Metrics
1. **Latency P95:** <30ms (target), <50ms (acceptable)
2. **Storage Footprint:** <50MB total
3. **RAM Usage:** <100MB peak
4. **NDCG@10:** >0.65 (measured via implicit feedback)
5. **User Engagement:** 
   - Skip rate reduction >20%
   - Session length increase >15%
   - Repeat listen rate increase >25%

### Secondary Metrics
1. **Diversity Score:** >0.45 (Shannon entropy on genre distribution)
2. **Serendipity Score:** >0.30 (unexpected but positively received)
3. **Catalog Coverage:** >40% of user's library exposed within 30 days
4. **Cold Start Satisfaction:** >70% positive feedback in first session
5. **Cache Hit Rate:** >80% combined (L1+L2+L3)

### Monitoring Dashboard
```
┌─────────────────────────────────────────────────────────────┐
│              BitChord Recommendation Dashboard              │
├─────────────────────────────────────────────────────────────┤
│  Real-Time Metrics:                                         │
│  • Avg Latency: 24ms ▲                                     │
│  • Cache Hit Rate: 83% ▼                                   │
│  • Active Users: 1,247                                     │
│                                                             │
│  Quality Metrics (Last 7 Days):                            │
│  • NDCG@10: 0.67 ✓                                         │
│  • Skip Rate: 18% ▼ (target: <20%)                         │
│  • Avg Session Length: 42min ▲                             │
│  • Diversity Score: 0.48 ✓                                 │
│                                                             │
│  System Health:                                             │
│  • Storage Used: 38MB / 50MB                               │
│  • RAM Peak: 76MB / 100MB                                  │
│  • Model Version: v1.2.3                                   │
│  • Last Retraining: 2 hours ago                            │
└─────────────────────────────────────────────────────────────┘
```

---

## 9. Technology Stack Recommendations

### Core Technologies
- **Language:** TypeScript (existing codebase) + Python (for model training scripts)
- **Database:** SQLite with better-sqlite3 (synchronous, high-performance)
- **ML Framework:** 
  - TensorFlow Lite (for audio feature extraction)
  - Surprise or Implicit (Python libraries for matrix factorization)
  - LightGBM (for ranking model)
- **Vector Search:** Custom LSH implementation or use `usearch` library
- **Caching:** Node-cache (L1) + SQLite (L2) + filesystem (L3)

### Libraries & Dependencies
```json
{
  "dependencies": {
    "better-sqlite3": "^9.0.0",
    "@tensorflow/tfjs-node": "^4.0.0",
    "node-cache": "^5.1.0",
    "lodash": "^4.17.0",
    "ml-matrix": "^6.0.0",
    "usearch": "^2.0.0"
  },
  "devDependencies": {
    "surprise": "^0.1.0",
    "implicit": "^0.7.0",
    "lightgbm": "^4.0.0",
    "jest": "^29.0.0"
  }
}
```

### Infrastructure
- **Build System:** Webpack/Vite with tree-shaking for minimal bundle size
- **CI/CD:** GitHub Actions with automated performance testing
- **Monitoring:** Custom metrics collector + Grafana (optional, for advanced users)
- **Testing:** Jest for unit tests, custom integration tests for recommendation quality

---

## 10. Sample Code Implementation

### 10.1 Content-Based Filter (Simplified)

```typescript
// src/recommender/content-based-filter.ts

import { Database } from 'better-sqlite3';
import { cosineSimilarity } from '../utils/math';
import { LSHIndex } from '../utils/lsh';

export class ContentBasedFilter {
  private db: Database;
  private lshIndex: LSHIndex;
  
  constructor(db: Database) {
    this.db = db;
    this.lshIndex = new LSHIndex(numHashTables: 10, bitsPerHash: 12);
  }
  
  async getUserProfile(userId: string): Promise<UserProfile> {
    const profile = this.db.prepare(`
      SELECT * FROM user_profile WHERE user_id = ?
    `).get(userId);
    
    if (!profile) {
      return this.createDefaultProfile(userId);
    }
    
    return this.parseProfile(profile);
  }
  
  async getSimilarSongs(
    seedSongs: string[],
    userProfile: UserProfile,
    limit: number = 200
  ): Promise<SongCandidate[]> {
    // Aggregate features from seed songs
    const seedFeatures = this.aggregateSeedFeatures(seedSongs);
    
    // Query LSH index for approximate nearest neighbors
    const candidateIds = this.lshIndex.query(seedFeatures.featureVector, limit * 2);
    
    // Fetch full features for candidates
    const candidates = this.db.prepare(`
      SELECT * FROM song_features WHERE song_id IN (${candidateIds.map(() => '?').join(',')})
    `).all(...candidateIds);
    
    // Score each candidate
    const scored = candidates.map(song => ({
      songId: song.song_id,
      score: this.calculateContentScore(song, userProfile, seedFeatures),
      song
    }));
    
    // Sort and return top N
    return scored
      .sort((a, b) => b.score - a.score)
      .slice(0, limit);
  }
  
  private calculateContentScore(
    song: SongFeatures,
    userProfile: UserProfile,
    seedFeatures: AggregatedFeatures
  ): number {
    // Audio feature similarity (40%)
    const audioSim = cosineSimilarity(
      song.audioFeatureVector,
      seedFeatures.audioFeatureVector
    );
    
    // Genre match (30%)
    const genreMatch = this.calculateGenreOverlap(
      song.metadata.genres,
      userProfile.preferences.genres
    );
    
    // Artist preference (20%)
    const artistPref = song.metadata.artists.reduce((sum, artist) => {
      return sum + (userProfile.preferences.artists.get(artist) || 0);
    }, 0) / song.metadata.artists.length;
    
    // Era preference (10%)
    const decade = Math.floor(song.metadata.releaseYear / 10) * 10;
    const eraPref = userProfile.preferences.eras.get(decade.toString()) || 0;
    
    return (
      0.40 * audioSim +
      0.30 * genreMatch +
      0.20 * artistPref +
      0.10 * eraPref
    );
  }
  
  async updateLSHIndex(songId: string): Promise<void> {
    const song = this.db.prepare(`
      SELECT * FROM song_features WHERE song_id = ?
    `).get(songId);
    
    if (song) {
      this.lshIndex.insert(song.song_id, song.feature_vector);
    }
  }
}
```

### 10.2 Collaborative Filter (ALS Implementation)

```typescript
// src/recommender/collaborative-filter.ts

import { Database } from 'better-sqlite3';
import { Matrix } from 'ml-matrix';

export class CollaborativeFilter {
  private db: Database;
  private userEmbeddings: Map<string, Float32Array>;
  private itemEmbeddings: Map<string, Float32Array>;
  private embeddingDim: number = 64;
  
  constructor(db: Database, embeddingDim: number = 64) {
    this.db = db;
    this.embeddingDim = embeddingDim;
    this.userEmbeddings = new Map();
    this.itemEmbeddings = new Map();
  }
  
  async buildInteractionMatrix(): Promise<SparseMatrix> {
    const interactions = this.db.prepare(`
      SELECT user_id, song_id, interaction_score
      FROM user_item_interactions
      WHERE interaction_score > 0
    `).all();
    
    // Build sparse matrix in COO format
    const rows: string[] = [];
    const cols: string[] = [];
    const values: number[] = [];
    
    for (const interaction of interactions) {
      rows.push(interaction.user_id);
      cols.push(interaction.song_id);
      values.push(interaction.interaction_score);
    }
    
    return { rows, cols, values };
  }
  
  async trainALS(iterations: number = 15, regularization: number = 0.1): Promise<void> {
    const matrix = await this.buildInteractionMatrix();
    
    // Initialize embeddings randomly
    this.initializeEmbeddings(matrix);
    
    // Alternating Least Squares
    for (let iter = 0; iter < iterations; iter++) {
      // Fix items, solve for users
      this.updateUsers(matrix, regularization);
      
      // Fix users, solve for items
      this.updateItems(matrix, regularization);
      
      console.log(`ALS iteration ${iter + 1}/${iterations} complete`);
    }
    
    // Save embeddings to database
    this.saveEmbeddings();
  }
  
  private updateUsers(matrix: SparseMatrix, reg: number): void {
    // Solve least squares problem for each user
    // Using conjugate gradient method for efficiency
    // Implementation omitted for brevity
  }
  
  private updateItems(matrix: SparseMatrix, reg: number): void {
    // Solve least squares problem for each item
    // Using conjugate gradient method for efficiency
    // Implementation omitted for brevity
  }
  
  getRecommendationsForUser(
    userId: string,
    excludeSongs: string[] = [],
    limit: number = 200
  ): SongCandidate[] {
    const userEmbedding = this.userEmbeddings.get(userId);
    
    if (!userEmbedding) {
      return []; // Cold start: fall back to content-based
    }
    
    const candidates: SongCandidate[] = [];
    
    for (const [songId, itemEmbedding] of this.itemEmbeddings.entries()) {
      if (excludeSongs.includes(songId)) continue;
      
      const score = this.dotProduct(userEmbedding, itemEmbedding);
      candidates.push({
        songId,
        score,
        source: 'collaborative'
      });
    }
    
    return candidates
      .sort((a, b) => b.score - a.score)
      .slice(0, limit);
  }
  
  private dotProduct(a: Float32Array, b: Float32Array): number {
    let sum = 0;
    for (let i = 0; i < this.embeddingDim; i++) {
      sum += a[i] * b[i];
    }
    return sum;
  }
  
  async updateUserEmbeddingIncremental(
    userId: string,
    newInteractions: Interaction[]
  ): Promise<void> {
    // Incremental update without full retrain
    // Use SGD (Stochastic Gradient Descent) for small updates
    // Implementation omitted for brevity
  }
}
```

### 10.3 Hybrid Ranker

```typescript
// src/recommender/hybrid-ranker.ts

import { ContentBasedFilter } from './content-based-filter';
import { CollaborativeFilter } from './collaborative-filter';
import { ContextualScorer } from './contextual-scorer';

export class HybridRanker {
  private cbf: ContentBasedFilter;
  private icf: CollaborativeFilter;
  private ctx: ContextualScorer;
  
  // Ensemble weights (can be tuned per user)
  private weights = {
    content: 0.35,
    collaborative: 0.35,
    context: 0.15,
    recency: 0.10,
    diversity: 0.05
  };
  
  constructor(
    cbf: ContentBasedFilter,
    icf: CollaborativeFilter,
    ctx: ContextualScorer
  ) {
    this.cbf = cbf;
    this.icf = icf;
    this.ctx = ctx;
  }
  
  async generateRecommendations(
    request: RecommendationRequest
  ): Promise<RecommendationResponse> {
    const startTime = Date.now();
    
    // Stage 1: Candidate Generation (parallel)
    const [contentCandidates, collabCandidates, explorationCandidates] = await Promise.all([
      this.generateContentCandidates(request),
      this.generateCollaborativeCandidates(request),
      request.context.discoveryMode 
        ? this.generateExplorationCandidates(request) 
        : Promise.resolve([])
    ]);
    
    // Merge and deduplicate candidates
    const allCandidates = this.mergeAndDeduplicate([
      ...contentCandidates,
      ...collabCandidates,
      ...explorationCandidates
    ]);
    
    // Apply hard filters
    const filteredCandidates = this.applyFilters(allCandidates, request.filters);
    
    // Stage 2: Scoring and Ranking
    const scoredCandidates = filteredCandidates.map(candidate => {
      const cbfScore = this.normalizeScore(candidate.cbfScore || 0);
      const icfScore = this.normalizeScore(candidate.icfScore || 0);
      const ctxScore = this.ctx.calculateContextScore(candidate, request.context);
      const recencyScore = this.calculateRecencyScore(candidate, request.context.recentSongs);
      
      const finalScore = 
        this.weights.content * cbfScore +
        this.weights.collaborative * icfScore +
        this.weights.context * ctxScore +
        this.weights.recency * recencyScore;
      
      return {
        ...candidate,
        finalScore,
        breakdown: {
          content: cbfScore,
          collaborative: icfScore,
          context: ctxScore,
          recency: recencyScore
        }
      };
    });
    
    // Apply diversity constraints (MMR)
    const diverseRanking = this.maximalMarginalRelevance(
      scoredCandidates,
      request.limit,
      lambdaParam: 0.7
    );
    
    // Generate explanations
    const withExplanations = diverseRanking.map(item => ({
      ...item,
      reasons: this.generateExplanation(item.breakdown)
    }));
    
    const latency = Date.now() - startTime;
    
    return {
      songs: withExplanations.slice(0, request.limit),
      metadata: {
        latencyMs: latency,
        modelVersion: '1.0.0',
        cacheHit: false,
        candidatesGenerated: allCandidates.length,
        candidatesAfterFilter: filteredCandidates.length
      }
    };
  }
  
  private maximalMarginalRelevance(
    candidates: ScoredCandidate[],
    limit: number,
    lambdaParam: number
  ): ScoredCandidate[] {
    const selected: ScoredCandidate[] = [];
    const remaining = [...candidates];
    
    while (selected.length < limit && remaining.length > 0) {
      // Find candidate with highest MMR score
      let bestIdx = 0;
      let bestMMR = -Infinity;
      
      for (let i = 0; i < remaining.length; i++) {
        const relevance = remaining[i].finalScore;
        const diversity = this.minSimilarityToSelected(remaining[i], selected);
        
        const mmrScore = lambdaParam * relevance - (1 - lambdaParam) * diversity;
        
        if (mmrScore > bestMMR) {
          bestMMR = mmrScore;
          bestIdx = i;
        }
      }
      
      selected.push(remaining[bestIdx]);
      remaining.splice(bestIdx, 1);
    }
    
    return selected;
  }
  
  private generateExplanation(breakdown: ScoreBreakdown): string[] {
    const reasons: string[] = [];
    
    if (breakdown.content > 0.7) {
      reasons.push('Similar to songs you\'ve enjoyed');
    }
    
    if (breakdown.collaborative > 0.7) {
      reasons.push('Popular among listeners with your taste');
    }
    
    if (breakdown.context > 0.7) {
      reasons.push('Great for this time of day');
    }
    
    if (breakdown.recency > 0.7) {
      reasons.push('Complements your recent listening');
    }
    
    return reasons.length > 0 ? reasons : ['Recommended for you'];
  }
  
  private normalizeScore(score: number): number {
    // Sigmoid normalization to [0, 1] range
    return 1 / (1 + Math.exp(-score));
  }
  
  private minSimilarityToSelected(
    candidate: ScoredCandidate,
    selected: ScoredCandidate[]
  ): number {
    if (selected.length === 0) return 0;
    
    const similarities = selected.map(s => 
      this.calculateSimilarity(candidate, s)
    );
    
    return Math.max(...similarities);
  }
  
  private calculateSimilarity(a: ScoredCandidate, b: ScoredCandidate): number {
    // Simple similarity based on genre and artist overlap
    const genreOverlap = a.genres.filter(g => b.genres.includes(g)).length;
    const artistOverlap = a.artists.filter(a => b.artists.includes(a)).length;
    
    return (genreOverlap / Math.max(a.genres.length, b.genres.length)) * 0.6 +
           (artistOverlap / Math.max(a.artists.length, b.artists.length)) * 0.4;
  }
}
```

---

## 11. Testing Strategy

### 11.1 Unit Tests
- Test individual components (CBF, ICF, Contextual Scorer)
- Verify mathematical correctness of similarity calculations
- Test edge cases (empty inputs, cold start, etc.)

### 11.2 Integration Tests
- Test full recommendation pipeline
- Verify latency requirements under load
- Test caching behavior and hit rates

### 11.3 Quality Evaluation
- **Offline Metrics:**
  - Precision@K, Recall@K, NDCG@K
  - Coverage, Diversity, Serendipity scores
  - Train/test split on historical data
  
- **Online Metrics (A/B Testing):**
  - Skip rate
  - Session length
  - Repeat listen rate
  - User satisfaction surveys

### 11.4 Performance Benchmarks
```typescript
// benchmarks/recommendation-benchmark.ts

import { HybridRanker } from '../src/recommender/hybrid-ranker';

describe('Recommendation Performance', () => {
  it('should generate recommendations within 30ms (P95)', async () => {
    const ranker = new HybridRanker(cbf, icf, ctx);
    const latencies: number[] = [];
    
    for (let i = 0; i < 1000; i++) {
      const start = Date.now();
      await ranker.generateRecommendations(testRequest);
      latencies.push(Date.now() - start);
    }
    
    const p95 = percentiles(latencies)[95];
    expect(p95).toBeLessThan(30);
  });
  
  it('should use less than 100MB RAM', () => {
    const initialMem = process.memoryUsage().heapUsed;
    
    // Generate 1000 recommendations
    for (let i = 0; i < 1000; i++) {
      ranker.generateRecommendations(testRequest);
    }
    
    const finalMem = process.memoryUsage().heapUsed;
    const memIncrease = (finalMem - initialMem) / 1024 / 1024; // MB
    
    expect(memIncrease).toBeLessThan(100);
  });
});
```

---

## 12. Deployment & Rollout Plan

### Week 1-4: Alpha Release
- Internal testing only
- Collect baseline metrics
- Iterate on core algorithms

### Week 5-8: Beta Release
- Opt-in beta for power users
- A/B testing framework live
- Gather qualitative feedback

### Week 9-12: Gradual Rollout
- Enable for 10% of users
- Monitor performance and quality metrics
- Fix critical issues

### Week 13-16: Full Release
- Enable for all users
- Deprecate old YouTube-only recommendations (optional)
- Continue monitoring and optimization

### Post-Launch: Continuous Improvement
- Monthly model retraining
- Quarterly feature additions
- Annual architecture review

---

## 13. Conclusion

The **BitChord Neural-Content Hybrid (BNCH)** model provides a practical, efficient, and privacy-preserving alternative to cloud-based recommendation systems. By focusing on the three pillars of **Speed**, **Quality**, and **Size**, we can deliver a recommendation experience that:

✅ **Responds in <30ms** - Faster than Spotify's cloud-based system
✅ **Fits in <50MB** - 10x smaller than traditional models
✅ **Delivers personalized, diverse recommendations** - Competitive quality with transparency
✅ **Works offline** - Unique advantage over competitors
✅ **Respects privacy** - No data leaves the device

This plan balances ambition with pragmatism, delivering incremental value at each phase while building toward a sophisticated hybrid model. The modular architecture allows for easy experimentation and continuous improvement based on real user feedback.

**Next Steps:**
1. Review and approve this plan
2. Set up development environment and tooling
3. Begin Phase 1: Foundation (Database schema + basic CBF)
4. Establish baseline metrics for comparison

---

## Appendix A: Glossary

- **CBF (Content-Based Filtering):** Recommends items similar to what the user liked before
- **ICF (Implicit Collaborative Filtering):** Learns from user behavior patterns across all users
- **ALS (Alternating Least Squares):** Matrix factorization algorithm for collaborative filtering
- **LSH (Locality-Sensitive Hashing):** Approximate nearest neighbor search for speed
- **MMR (Maximal Marginal Relevance):** Algorithm for balancing relevance and diversity
- **NDCG (Normalized Discounted Cumulative Gain):** Metric for ranking quality
- **ONNX (Open Neural Network Exchange):** Format for machine learning models
- **LRU (Least Recently Used):** Caching eviction policy

## Appendix B: References

1. Hu, Y., Koren, Y., & Volinsky, C. (2008). "Collaborative filtering for implicit feedback datasets"
2. Sarwar, B., et al. (2001). "Item-based collaborative filtering recommendation algorithms"
3. Charikar, M. S. (2002). "Similarity estimation techniques from rounding algorithms" (LSH)
4. Carbonell, J., & Goldstein, J. (1998). "The use of MMR, diversity-based reranking..."
5. Spotify Research Blog: https://research.atspotify.com/
