package com.blue.learnjp.dto;

public record UserJoinResponse(
    long userId,
    String name,
    String discordSenderId,
    boolean created,
    int initializedWordStates,
    String status
) {}
