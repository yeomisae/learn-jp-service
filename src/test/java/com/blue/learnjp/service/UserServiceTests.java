package com.blue.learnjp.service;

import com.blue.learnjp.dto.UserJoinRequest;
import com.blue.learnjp.dto.UserJoinResponse;
import com.blue.learnjp.repository.GraphRepository;
import com.blue.learnjp.repository.UserRepository;
import com.blue.learnjp.repository.UserWordStateRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserServiceTests {

    @Test
    void joinCreatesUserAndInitializesWordStates() {
        UserRepository userRepository = mock(UserRepository.class);
        UserWordStateRepository userWordStateRepository = mock(UserWordStateRepository.class);
        GraphRepository graphRepository = mock(GraphRepository.class);
        when(userRepository.findByDiscordSenderId("sender-1")).thenReturn(java.util.Optional.empty());
        when(userRepository.upsertByDiscordSenderId("blue", "sender-1"))
            .thenReturn(new UserRepository.UserRecord(7, "blue", "sender-1"));
        when(graphRepository.findAllWordIds()).thenReturn(List.of("word-1", "word-2"));
        when(userWordStateRepository.initializeWordStates(7, List.of("word-1", "word-2"))).thenReturn(2);

        UserService service = new UserService(userRepository, userWordStateRepository, graphRepository);

        UserJoinResponse response = service.join(new UserJoinRequest("blue", "sender-1"));

        assertThat(response.userId()).isEqualTo(7);
        assertThat(response.created()).isTrue();
        assertThat(response.initializedWordStates()).isEqualTo(2);
    }
}
