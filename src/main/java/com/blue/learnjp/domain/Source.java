package com.blue.learnjp.domain;

/**
 * 단어 등록 출처.
 * Word 노드의 source 필드에 쉼표 구분으로 누적 저장된다.
 */
public enum Source {
    JLPT,
    NEWS,
    LYRICS,
    SUB_MOVIE,
    SUB_ANIME,
    SUB_SERIES,
    MANUAL;
}
