package com.blue.learnjp.dto;

public record UserJoinRequest(
    String name,
    String discordSenderId
) {}
