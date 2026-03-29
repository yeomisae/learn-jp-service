#!/bin/bash
# Word enrichment 배치 실행 스크립트
# 사용법: ./enrich.sh [batches_per_request] [sleep_seconds]
#   batches_per_request: 1회 요청당 배치 수 (기본: 3, 1배치=10단어)
#   sleep_seconds: 요청 간 대기 시간 (기본: 2초)

BASE_URL="http://localhost:8080/api/words"
BATCHES=${1:-3}
SLEEP=${2:-2}
TOTAL_UPDATED=0

echo "=== Word Enrichment 시작 ==="
echo "1회 요청: ${BATCHES}배치 ($(( BATCHES * 10 ))단어), 요청 간격: ${SLEEP}초"

remaining=$(curl -s "$BASE_URL/enrich/status" | python3 -c "import sys,json; print(json.load(sys.stdin)['remaining'])")
echo "남은 단어: ${remaining}개"
echo ""

while true; do
    result=$(curl -s -X POST "$BASE_URL/enrich?batches=$BATCHES")
    updated=$(echo "$result" | python3 -c "import sys,json; print(json.load(sys.stdin)['updated'])" 2>/dev/null)
    status=$(echo "$result" | python3 -c "import sys,json; print(json.load(sys.stdin)['status'])" 2>/dev/null)
    error=$(echo "$result" | python3 -c "import sys,json; print(json.load(sys.stdin).get('error',''))" 2>/dev/null)

    if [ -z "$updated" ]; then
        echo "[$(date +%H:%M:%S)] 서버 응답 오류. 30초 후 재시도..."
        sleep 30
        continue
    fi

    TOTAL_UPDATED=$((TOTAL_UPDATED + updated))
    remaining=$(curl -s "$BASE_URL/enrich/status" | python3 -c "import sys,json; print(json.load(sys.stdin)['remaining'])")
    echo "[$(date +%H:%M:%S)] +${updated} 완료 | 누적: ${TOTAL_UPDATED} | 남음: ${remaining} | 상태: ${status}"

    if [ "$status" = "completed" ]; then
        echo ""
        echo "=== 전체 완료! 총 ${TOTAL_UPDATED}개 enriched ==="
        break
    fi

    if [ "$status" = "rate_limited" ]; then
        echo "  Rate limit 감지. 60초 대기..."
        sleep 60
        continue
    fi

    if [ "$status" = "error" ]; then
        echo "  오류: ${error}. 30초 후 재시도..."
        sleep 30
        continue
    fi

    sleep "$SLEEP"
done
