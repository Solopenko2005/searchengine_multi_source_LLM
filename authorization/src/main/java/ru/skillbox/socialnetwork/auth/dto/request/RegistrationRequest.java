package ru.skillbox.socialnetwork.auth.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;

@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public record RegistrationRequest(@NotBlank String firstName,
                                  @NotBlank String lastName,
                                  @NotBlank @Email String email,
                                  @NotBlank @Size(min = 8, max = 128) String password1,
                                  @NotBlank @Size(min = 8, max = 128) String password2,
                                  String captchaCode,
                                  String role) {
}
