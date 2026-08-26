package ru.skillbox.socialnetwork.auth.dto.kafka;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(callSuper = true)
public class RegistrationEvent extends KafkaEvent {

    private String email;
}
