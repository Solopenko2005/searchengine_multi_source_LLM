package ru.skillbox.socialnetwork.auth.dto.kafka;

import lombok.*;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
public abstract class KafkaEvent {
    private UUID accountId;
    private String key;
}
