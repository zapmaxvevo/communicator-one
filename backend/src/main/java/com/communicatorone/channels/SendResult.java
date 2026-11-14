package com.communicatorone.channels;

import org.springframework.util.Assert;

record SendResult(String externalId) {

    SendResult {
        Assert.hasText(externalId, "A sent message needs the id its provider gave it");
    }
}
