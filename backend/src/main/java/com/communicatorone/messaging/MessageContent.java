package com.communicatorone.messaging;

import org.springframework.util.Assert;

public record MessageContent(String text) {

    public MessageContent {
        Assert.hasLength(text, "A message needs its text");
    }

    @Override
    public String toString() {
        return "MessageContent[text]";
    }
}
