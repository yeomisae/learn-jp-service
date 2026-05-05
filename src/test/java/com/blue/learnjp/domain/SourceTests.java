package com.blue.learnjp.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SourceTests {

    @Test
    void validateNormalizesTopLevelCategoryAndPreservesSubcategory() {
        assertThat(Source.validate("news:Nhk_Easy")).isEqualTo("NEWS:Nhk_Easy");
        assertThat(Source.validate("jlpt")).isEqualTo("JLPT");
    }

    @Test
    void validateReturnsEmptyStringForBlankInputAndNullForInvalidCategory() {
        assertThat(Source.validate(null)).isEqualTo("");
        assertThat(Source.validate("   ")).isEqualTo("");
        assertThat(Source.validate("invalid:foo")).isNull();
    }
}
