package com.communicatorone.operators;

import java.time.Instant;

record IssuedToken(String value, Instant expiresAt) {
}
