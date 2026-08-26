package ru.skillbox.socialnetwork.auth.listener;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.skillbox.socialnetwork.auth.dto.kafka.BlockDeleteEvent;
import ru.skillbox.socialnetwork.auth.dto.kafka.RegistrationEvent;
import ru.skillbox.socialnetwork.auth.persistense.UserEntity;
import ru.skillbox.socialnetwork.auth.persistense.UserRepository;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;


class KafkaResponseConsumerTest {
    private KafkaResponseConsumer consumer;
    private UserRepository userRepository;

    @BeforeEach
    void setup() {
        userRepository = mock(UserRepository.class);
        consumer = new KafkaResponseConsumer(userRepository);
    }

    @Test
    void shouldUpdateAccountId_whenUserFoundInRegistration() {
        String email = "no@user.com";
        UUID accountId = UUID.randomUUID();

        UserEntity user = new UserEntity();
        user.setEmail(email);
        user.setAccountId(null);

        RegistrationEvent event = new RegistrationEvent();
        event.setEmail(email);
        event.setAccountId(accountId);

        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));

        consumer.listenRegistered(event);

        assertEquals(accountId, user.getAccountId());
        verify(userRepository).save(user);
    }

    @Test
    void shouldUpdateBlocked_whenUserFoundInBlockEvent() {
        UUID accountId = UUID.randomUUID();

        UserEntity user = new UserEntity();

        BlockDeleteEvent blockEvent = new BlockDeleteEvent();
        blockEvent.setEnabled(true);
        blockEvent.setAccountId(accountId);

        when(userRepository.findByAccountId(accountId)).thenReturn(Optional.of(user));

        consumer.listenBlock(blockEvent);

        assertTrue(user.isBlocked());
        verify(userRepository).save(user);
    }

    @Test
    void shouldDeleteUser_whenUserFoundInDeleteEvent() {
        UUID accountId = UUID.randomUUID();
        UserEntity user = new UserEntity();

        BlockDeleteEvent blockEvent = new BlockDeleteEvent();
        blockEvent.setEnabled(false);
        blockEvent.setAccountId(accountId);

        when(userRepository.findByAccountId(accountId)).thenReturn(Optional.of(user));

        consumer.listenDelete(blockEvent);

        verify(userRepository).delete(user);
    }

    @Test
    void shouldNotFail_whenUserNotFound() {
        RegistrationEvent registrationEvent = new RegistrationEvent();
        registrationEvent.setEmail("no@user.com");
        registrationEvent.setAccountId(UUID.randomUUID());

        BlockDeleteEvent blockEvent = new BlockDeleteEvent();
        blockEvent.setEnabled(true);
        blockEvent.setAccountId(UUID.randomUUID());

        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(userRepository.findByAccountId(any())).thenReturn(Optional.empty());

        consumer.listenRegistered(registrationEvent);
        consumer.listenBlock(blockEvent);
        consumer.listenDelete(blockEvent);

        verify(userRepository, never()).save(any());
        verify(userRepository, never()).delete(any());
    }
}
