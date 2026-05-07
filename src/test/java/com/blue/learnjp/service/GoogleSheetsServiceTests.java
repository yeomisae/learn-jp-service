package com.blue.learnjp.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleSheetsServiceTests {

    @Test
    void normalizeBookmarkForSheetClampsNegativeZeroAndPositiveValues() {
        assertThat(GoogleSheetsService.normalizeBookmarkForSheet(-3)).isEqualTo("-1");
        assertThat(GoogleSheetsService.normalizeBookmarkForSheet(0)).isEqualTo("0");
        assertThat(GoogleSheetsService.normalizeBookmarkForSheet(7)).isEqualTo("1");
    }

    @Test
    void normalizeBookmarkForSheetHandlesStringsAndInvalidValues() {
        assertThat(GoogleSheetsService.normalizeBookmarkForSheet("-2")).isEqualTo("-1");
        assertThat(GoogleSheetsService.normalizeBookmarkForSheet("0")).isEqualTo("0");
        assertThat(GoogleSheetsService.normalizeBookmarkForSheet("5")).isEqualTo("1");
        assertThat(GoogleSheetsService.normalizeBookmarkForSheet("oops")).isEqualTo("0");
        assertThat(GoogleSheetsService.normalizeBookmarkForSheet(null)).isEqualTo("0");
    }
}
