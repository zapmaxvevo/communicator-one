package com.communicatorone.people;

public class InvalidContactIdentityException extends RuntimeException {

    InvalidContactIdentityException(IdentityKind kind) {
        super("Invalid " + kind + " identity");
    }
}
