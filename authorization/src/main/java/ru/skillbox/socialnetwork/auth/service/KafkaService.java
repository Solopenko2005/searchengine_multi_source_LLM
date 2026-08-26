package ru.skillbox.socialnetwork.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import ru.skillbox.socialnetwork.auth.dto.kafka.DetailedRegistrationEvent;

@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaService {
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${spring.kafka.topic.registrationRequest}")
    private String registrationRequestTopic;

    public void getAccountId(DetailedRegistrationEvent request) throws Exception {
        kafkaTemplate.send(registrationRequestTopic, request);
        log.info("Request for: {}", request.getEmail());
    }

}