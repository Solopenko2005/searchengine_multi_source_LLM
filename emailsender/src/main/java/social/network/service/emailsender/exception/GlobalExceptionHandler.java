package social.network.service.emailsender.exception;

import org.springframework.http.HttpStatus;
import org.springframework.validation.ObjectError;

import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import social.network.service.emailsender.dto.ErrorResponse;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ErrorResponse catchValidationException(MethodArgumentNotValidException ve){
        return new ErrorResponse(HttpStatus.BAD_REQUEST.value(),String.format("Ваш запрос не валиден, проверьте данные - %s", ve.getBindingResult()
                .getAllErrors()
                .stream()
                .findFirst()
                .map(ObjectError::getDefaultMessage)
                .orElse("Ошибка валидации")));
    }
}
