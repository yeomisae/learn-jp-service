package com.blue.learnjp.service;

import com.blue.learnjp.dto.UserJoinRequest;
import com.blue.learnjp.dto.UserJoinResponse;
import com.blue.learnjp.repository.GraphRepository;
import com.blue.learnjp.repository.UserRepository;
import com.blue.learnjp.repository.UserWordStateRepository;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final UserWordStateRepository userWordStateRepository;
    private final GraphRepository graphRepository;

    public UserService(UserRepository userRepository,
                       UserWordStateRepository userWordStateRepository,
                       GraphRepository graphRepository) {
        this.userRepository = userRepository;
        this.userWordStateRepository = userWordStateRepository;
        this.graphRepository = graphRepository;
    }

    public UserJoinResponse join(UserJoinRequest request) {
        String senderId = request != null ? request.discordSenderId() : "";
        String name = request != null ? request.name() : "";
        boolean existed = userRepository.findByDiscordSenderId(senderId).isPresent();
        UserRepository.UserRecord user = userRepository.upsertByDiscordSenderId(name, senderId);
        int initialized = userWordStateRepository.initializeWordStates(user.id(), graphRepository.findAllWordIds());
        return new UserJoinResponse(
            user.id(),
            user.name(),
            user.discordSenderId(),
            !existed,
            initialized,
            "ok"
        );
    }
}
