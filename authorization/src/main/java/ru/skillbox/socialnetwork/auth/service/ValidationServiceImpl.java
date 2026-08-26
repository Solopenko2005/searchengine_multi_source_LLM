package ru.skillbox.socialnetwork.auth.service;

import org.springframework.stereotype.Service;
import ru.skillbox.socialnetwork.auth.dto.request.RegistrationRequest;
import ru.skillbox.socialnetwork.auth.exception.PasswordNotMatchesException;

@Service
public class ValidationServiceImpl implements ValidationService {

    @Override
    public void validateConfirmPassword(RegistrationRequest registrationRequest) {
        if (!registrationRequest.password1().equals(registrationRequest.password2())){
            throw new PasswordNotMatchesException("Confirm password does not match");
        }
    }

}
