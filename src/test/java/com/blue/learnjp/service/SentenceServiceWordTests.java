package com.blue.learnjp.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SentenceServiceWordTests {

    @Test
    void toNWordsHandlesNumericWordsAndExistingLLMNormalizedWords() {
        assertThat(SentenceService.toNWords("365日")).containsExactly("n-日");
        assertThat(SentenceService.toNWords("8時40分")).containsExactly("n-時", "n-分");
        assertThat(SentenceService.toNWords("n時")).containsExactly("n-時");
        assertThat(SentenceService.toNWords("食べる")).containsExactly("食べる");
    }
}
