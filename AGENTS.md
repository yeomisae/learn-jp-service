# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

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

### jako API 기반 파이프라인

LLM(OpenClaw)은 문장 → 단어 분리 + co-occurrence 추출만 담당. 단어 메타데이터는 네이버 jako API에서 조회한다.

```
[문장 입력] POST /api/sentences
  → LLM analyze (단어 분리 + co-occurrence)
  → 각 단어 jako API 조회 → meaning, pos, posDetail, reading, starGrade, conjugations, antonyms
  → mergeWord (jako 데이터로)
  → jako 예문 → ExampleQueue 노드 적재 (비동기 처리)

[단어 직접 등록] POST /api/word
  → jako API 직접 조회 (LLM 불필요)
  → mergeWord
  → jako 예문 → ExampleQueue 적재
```

jako에서 못 찾는 단어 (고유명사, 신조어 등)는 LLM analyze 결과로 fallback.

### ExampleQueue (비동기 예문 처리)

jako API 예문을 Neo4j `ExampleQueue` 노드로 영속화하고, `@Scheduled`(2초 간격)로 sentence 파이프라인에 재투입한다.
- **depth=1**: 예문에서 추출된 예문은 큐에 넣지 않음 (재귀 방지)
- **상태**: PENDING → DONE (성공) / FAILED (실패) / PENDING 유지 (rate limit)
- 서버 재시작해도 큐 유실 없음 (Neo4j 영속)

### Enrich (품질 보완)

`POST /api/words/enrich?batches=N` — pos 또는 meaning이 비어있는 단어를 jako API로 보강한다.

### Source (출처 추적)

Word 노드의 `source` 필드로 단어 등록 출처를 추적한다. 쉼표 구분 누적 방식.
- `POST /api/sentences/{source}` — Source enum 검증 (JLPT, NEWS, LYRICS, SUB_MOVIE, SUB_ANIME, SUB_SERIES, MANUAL, EXAMPLE)
- `POST /api/sentences` — source 빈값 (하위 호환)
- `POST /api/word/{source}` — jako 직접 조회로 단어 등록 (LLM 불필요)
- `POST /api/word` — source 빈값
- CSV import → `JLPT:{level}` (CSV 4번째 필드 기반, 예: `JLPT:N5`)

### 네이버 일본어 사전 (jako API)

비공식 API로 단어 정보를 조회할 수 있다. 공식 API는 없음. `NaverJakoDictionaryService`에서 호출.

```
GET https://ja.dict.naver.com/api3/jako/search?query={word}&m=pc&range=word
Headers: User-Agent, Referer: https://ja.dict.naver.com/
```

주요 응답 필드 (`items[]`):
- `expEntry` — 히라가나 읽기 (reading). 카타카나 단어는 `<strong>` 태그 포함 → strip 필요
- `expKanji` — 한자 표기
- `priority` — 별 갯수 (0~2+, 빈도 기반 난이도) → `starGrade`
- `frequencyAdd` — JLPT 레벨 (예: "JLPT 5"). 참고용 (별도 저장하지 않음)
- `meansCollector[].partOfSpeech` — 한국어 품사 (하1단 타동사 등) → `pos`
- `meansCollector[].partOfSpeech2` — 일본어 품사 (下一段他動詞 등) → `posDetail`
- `meansCollector[].means[].value` — 한국어 뜻 (다의어 번호별). `↔` 뒤 `related_word`에서 반의어 추출
- `meansCollector[].means[].exampleOri/Trans` — 예문 (ruby furigana HTML → 한자만 추출)
- `expAliasEntrySearchList` — 활용형 (ます형, 부정형 등) → `conjugations` JSON
- `entryId` — 사전 엔트리 ID → `dictEntryId`

**exact match 판별**: `matchType="exact:entry"` 기준 (`exactMatch` 플래그는 한자 검색 시 false인 경우 있음)

### Package Layout

```
com.blue.learnjp/
  controller/    SentenceController — POST /api/sentences, /api/sentences/{source}, /api/word, /api/word/{source}
                 EnrichmentController — POST /api/words/enrich
                 CsvImportController — POST /api/words/import/csv
  service/       SentenceService (orchestration), OpenClawService (LLM analyze)
                 NaverJakoDictionaryService (jako API), ExampleQueueService (비동기 예문 처리)
                 EnrichmentService (jako 기반 backfill), GoogleSheetsService (export)
  repository/    WordRepository (Spring Data), GraphRepository (custom Cypher via Neo4jClient)
  domain/        Word (node), CoOccurs (relationship), Source (enum)
  dto/           AnalysisResult, WordInfo, EdgeInfo, ImportRequest, JakoLookupResult
  config/        OpenClawConfig, NaverJakoConfig (@ConfigurationProperties records), GoogleSheetsConfig
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

## Neo4j 직접 조회

```bash
curl -s -X POST http://localhost:7474/db/neo4j/tx/commit \
  -H "Content-Type: application/json" \
  -u neo4j:"$NEO4J_PASSWORD" \
  -d '{"statements":[{"statement":"MATCH (w:Word) RETURN count(w)"}]}'
```

## Google Sheets 동기화

`GoogleSheetsService`는 문장 입력 시 자동으로 전체 Word 노드를 Google Sheets에 동기화한다.
- **방식**: full replace (시트 전체 clear → 전체 데이터 다시 쓰기)
- **조건**: `GOOGLE_SHEETS_CREDENTIALS_PATH` 환경변수가 설정되어 있을 때만 빈 생성 (`@ConditionalOnExpression`)
- **헤더**: W, M, POS, P, S, A, D, B, I, C (VoCat 표준)

## Codex 산출 문서

설계 문서, 분석 보고서 등 Codex가 생성하는 문서는 `claudedocs/` 디렉토리에 저장한다. 이 디렉토리는 `.gitignore`에 포함되어 있어 커밋되지 않는다.

## 빌드

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew build -x test
```
