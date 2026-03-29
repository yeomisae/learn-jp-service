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

## 서버 실행 환경

macOS launchd로 자동 실행 및 자동 재시작 설정되어 있음.

- **plist 경로**: `~/Library/LaunchAgents/com.blue.learnjp.plist`
- **Label**: `com.blue.learnjp`
- **RunAtLoad**: true (로그인 시 자동 시작)
- **KeepAlive**: true (프로세스 종료 시 자동 재시작)
- **실행 JAR**: `build/libs/learn-jp-service-0.0.1-SNAPSHOT.jar`
- **로그**: `~/.config/learn-jp/stdout.log`, `~/.config/learn-jp/stderr.log`

### 서버 재시작 방법

```bash
# 새 빌드 후 재시작 (KeepAlive가 자동으로 다시 띄워줌)
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew build -x test
launchctl stop com.blue.learnjp
```

`kill`로 직접 죽여도 launchd가 자동 재시작하지만, `launchctl stop` 사용을 권장.

## 빌드

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew build -x test
```
