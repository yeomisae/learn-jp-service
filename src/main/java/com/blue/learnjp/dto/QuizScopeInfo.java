package com.blue.learnjp.dto;

public record QuizScopeInfo(
    String accountId,
    String provider,
    String chatId,
    String chatType,
    String guildId,
    String channelId,
    String label
) {}
