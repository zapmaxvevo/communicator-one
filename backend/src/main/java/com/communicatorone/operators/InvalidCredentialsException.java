package com.communicatorone.operators;

public class InvalidCredentialsException extends RuntimeException {

    InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
