package ru.skillbox.socialnetwork.auth.dto.kafka;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class BlockDeleteEvent extends KafkaEvent{
    private Boolean enabled;

}
