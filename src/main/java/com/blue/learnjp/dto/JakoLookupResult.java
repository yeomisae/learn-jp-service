package com.blue.learnjp.dto;

import java.util.List;

/**
 * 네이버 jako API 파싱 결과.
 * 하나의 단어 검색에 대한 exact match 결과를 담는다.
 */
public record JakoLookupResult(
    boolean found,
    String resolvedLemma,  // 실제 jako에서 찾은 lemma (변형 시도 시 정제된 값)
    String reading,        // expEntry (히라가나)
    String meaning,        // meansCollector 전체 합산
    String pos,            // partOfSpeech (한국어 원본)
    String posDetail,      // partOfSpeech2 (日本語 원본)
    String posDesc,        // pos 매핑값 (학습자 친화적 설명)
    String antonyms,       // means[].value 내 ↔ 파싱
    int starGrade,         // priority
    String conjugations,   // JSON 배열 문자열
    String dictEntryId,    // entryId
    List<Example> examples // 예문 목록
) {
    public record Example(
        String textJa,     // 일본어 예문 (furigana 제거)
        String textKo      // 한국어 번역
    ) {}

    /** jako API에서 결과를 찾지 못했을 때 반환하는 빈 결과 */
    public static JakoLookupResult notFound() {
        return new JakoLookupResult(false, "", "", "", "", "", "", "", 0, "[]", "", List.of());
    }
}
