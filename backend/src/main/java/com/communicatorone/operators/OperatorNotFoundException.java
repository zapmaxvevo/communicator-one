package com.communicatorone.operators;

public class OperatorNotFoundException extends RuntimeException {

    OperatorNotFoundException() {
        super("The authenticated operator does not exist in their organization");
    }
}
