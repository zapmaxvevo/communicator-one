package com.communicatorone.channels;

import org.springframework.util.Assert;

record WebhookPayload(String body) {

    WebhookPayload {
        Assert.notNull(body, "A webhook payload needs its body");
    }

    @Override
    public String toString() {
        return "WebhookPayload[body]";
    }
}
