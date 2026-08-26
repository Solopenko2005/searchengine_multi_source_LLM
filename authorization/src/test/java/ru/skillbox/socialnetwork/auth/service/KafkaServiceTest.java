package ru.skillbox.socialnetwork.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import ru.skillbox.socialnetwork.auth.dto.kafka.DetailedRegistrationEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class KafkaServiceTest  {
    private KafkaTemplate<String, Object> kafkaTemplate;
    private KafkaService kafkaService;

    @BeforeEach
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        kafkaService = new KafkaService(kafkaTemplate);
        try {
            var field = KafkaService.class.getDeclaredField("registrationRequestTopic");
            field.setAccessible(true);
            field.set(kafkaService, "test-topic");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void getAccountId_shouldSendEventToKafka() throws Exception {
        DetailedRegistrationEvent event = DetailedRegistrationEvent.builder()
                .email("user@example.com")
                .firstName("Test")
                .lastName("User")
                .userId(java.util.UUID.randomUUID())
                .build();

        kafkaService.getAccountId(event);

        ArgumentCaptor<DetailedRegistrationEvent> eventCaptor = ArgumentCaptor.forClass(DetailedRegistrationEvent.class);
        verify(kafkaTemplate, times(1)).send(eq("test-topic"), eventCaptor.capture());

        assertEquals("user@example.com", eventCaptor.getValue().getEmail());
    }
}
