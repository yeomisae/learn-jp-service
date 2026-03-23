package com.blue.learnjp.dto;

public record ExportResult(
    int wordCount,
    String spreadsheetId,
    String status
) {}
