# learn-jp-service

일본어 학습 시스템 — Graph DB 기반 연상 경로 학습

## 개요

일본어 문장을 입력하면 내용어(명사, 동사, 형용사)를 추출하고, 단어 간 관계를 그래프로 저장한다.
축적된 그래프에서 n-hop 경로를 탐색하여, 학습자가 아는 단어만으로 구성된 예문을 조립하는 것이 핵심 기능이다.

## 기술 스택

| 레이어 | 선택 |
|---|---|
| 애플리케이션 | Java 21 + Spring Boot 3.5 |
| Graph DB | Neo4j Community Edition |
| 형태소 분석 | OpenClaw (OpenAI) |

## 데이터 흐름

```
문장 입력 → POST /api/sentences
  → OpenClaw(OpenAI)에서 내용어 추출 + 패턴 분석
  → Neo4j에 단어(노드) + 관계(엣지) 저장
```

## 실행 방법

### 사전 요구사항
- Java 21
- Neo4j (Homebrew: `brew install neo4j && brew services start neo4j`)
- OpenClaw 게이트웨이 실행 중

### 환경변수 설정

```bash
export NEO4J_PASSWORD=<neo4j 비밀번호>
export OPENCLAW_API_KEY=<openclaw 게이트웨이 토큰>
```

### 빌드 및 실행

```bash
./gradlew bootRun
```

### 테스트

```bash
curl -X POST http://localhost:8080/api/sentences \
  -H "Content-Type: application/json" \
  -d '{"sentence": "寿司を食べに行く"}'
```
