package ru.skillbox.socialnetwork.auth.listener;

import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.skillbox.socialnetwork.auth.dto.kafka.BlockDeleteEvent;
import ru.skillbox.socialnetwork.auth.dto.kafka.RegistrationEvent;
import ru.skillbox.socialnetwork.auth.persistense.UserRepository;

@Component
@RequiredArgsConstructor
public class KafkaResponseConsumer {
    private final UserRepository userRepository;

    @KafkaListener(topics = "${spring.kafka.topic.registrationResponse}", groupId = "${spring.kafka.consumer.group-id}" )
    public void listenRegistered(RegistrationEvent event) {
        userRepository.findByEmail(event.getEmail()).ifPresent(user -> {
            user.setAccountId(event.getAccountId());
            userRepository.save(user);
        });
    }

    @KafkaListener(topics = "${spring.kafka.topic.blockResponse}", groupId = "${spring.kafka.consumer.group-id}")
    public void listenBlock(BlockDeleteEvent event) {
        userRepository.findByAccountId(event.getAccountId()).ifPresent(user -> {
            user.setBlocked(event.getEnabled());
            userRepository.save(user);
        });
    }

    @KafkaListener(topics = "${spring.kafka.topic.deleteResponse}", groupId = "${spring.kafka.consumer.group-id}")
    public void listenDelete(BlockDeleteEvent event) {
        userRepository.findByAccountId(event.getAccountId()).ifPresent(userRepository::delete);
    }
}
