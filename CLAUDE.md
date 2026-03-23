# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build and run
./gradlew bootRun

# Run tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.blue.learnjp.LearnJpServiceApplicationTests"

# Build JAR
./gradlew build
```

**Required environment variables** (see `.env`):
- `NEO4J_PASSWORD` — Neo4j database password
- `OPENCLAW_API_KEY` — OpenClaw gateway API key
- `OPENCLAW_BASE_URL` (optional, default: `http://127.0.0.1:18789/v1`)
- `OPENCLAW_MODEL` (optional, default: `openclaw:jp-analyzer`)

**Prerequisites:** Neo4j running on `bolt://localhost:7687`, OpenClaw gateway running locally.

## Architecture

**learn-jp-service** is a Japanese language learning microservice. It accepts Japanese sentences, extracts content words via morphological analysis, and stores word co-occurrence relationships as a graph in Neo4j. The graph accumulates over time so learners can discover associative paths between words they already know.

### Pipeline

```
POST /api/sentences {"sentence": "寿司を食べに行く"}
  → OpenClawService (OpenAI-compatible HTTP API)
      → extracts content words + co-occurrence patterns
  → GraphRepository (Neo4j Cypher)
      → MERGE word nodes (keyed on lemma)
      → CREATE CO_OCCURS relationships with sentence context
  → returns AnalysisResult
```

### Key Design Decisions

- **Word identity**: words are deduplicated by `lemma` (dictionary form), not surface form. `GraphRepository.mergeWord()` uses `MERGE ... ON CREATE SET` to avoid duplicates.
- **Relationships**: `CO_OCCURS` edges are directional and store the originating `sentence` and `pattern` as properties. Multiple co-occurrences between the same word pair create multiple edges (not merged).
- **OpenClaw integration**: `OpenClawService` calls an OpenAI-compatible chat completion endpoint. The model returns structured JSON with `words` (surface/lemma/reading/pos/meaning) and `edges` (from/to/pattern). `AnalysisResult` is the DTO for this response.
- **Config**: `OpenClawConfig` is a `@ConfigurationProperties` record, scanned via `@ConfigurationPropertiesScan` on the main class.

### Package Layout

```
com.blue.learnjp/
  controller/    SentenceController — POST /api/sentences
  service/       SentenceService (orchestration), OpenClawService (external API)
  repository/    WordRepository (Spring Data), GraphRepository (custom Cypher via Neo4jClient)
  domain/        Word (node), CoOccurs (relationship)
  dto/           AnalysisResult, WordInfo, EdgeInfo
  config/        OpenClawConfig (@ConfigurationProperties record)
```
