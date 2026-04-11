package com.blue.learnjp.domain;

/**
 * 단어 등록 출처 (대분류).
 * Word 노드의 source 필드에 "대분류:소분류" 형태로 저장된다.
 * 소분류는 자유 텍스트 (optional).
 * 예: "JLPT:N5", "NEWS:NHK_EASY", "SUB_ANIME:進撃の巨人"
 * 복수 출처는 쉼표 구분 누적: "JLPT:N5,NEWS:NHK_EASY"
 */
public enum Source {
    JLPT,
    NEWS,
    LYRICS,
    SUB_MOVIE,
    SUB_ANIME,
    SUB_SERIES,
    MANUAL,
    EXAMPLE;

    /**
     * "대분류:소분류" 또는 "대분류" 문자열을 검증하고 정규화한다.
     * 대분류가 유효한 enum이면 원본 문자열 반환, 아니면 null.
     */
    public static String validate(String source) {
        if (source == null || source.isBlank()) return "";
        String category = source.contains(":") ? source.substring(0, source.indexOf(':')) : source;
        try {
            Source.valueOf(category.toUpperCase());
            // 대분류를 대문자로 정규화, 소분류는 원본 유지
            if (source.contains(":")) {
                return category.toUpperCase() + source.substring(source.indexOf(':'));
            }
            return category.toUpperCase();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
