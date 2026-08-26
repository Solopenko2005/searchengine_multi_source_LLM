package ru.skillbox.socialnetwork.auth.dto.kafka;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DetailedRegistrationEvent {
    private UUID userId;
    private String firstName;
    private String lastName;
    private String email;
    private UUID accountId;
}
