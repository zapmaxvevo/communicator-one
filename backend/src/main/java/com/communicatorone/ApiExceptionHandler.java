package com.communicatorone;

import com.communicatorone.operators.InvalidCredentialsException;
import com.communicatorone.operators.OperatorNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(InvalidCredentialsException.class)
    ProblemDetail rejectInvalidCredentials(InvalidCredentialsException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, exception.getMessage());
    }

    @ExceptionHandler(OperatorNotFoundException.class)
    ProblemDetail reportOperatorNotFound() {
        return ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    }
}
