package com.communicatorone.operators;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AuthenticationController {

    private final OperatorAuthentication authentication;

    AuthenticationController(OperatorAuthentication authentication) {
        this.authentication = authentication;
    }

    @PostMapping("/api/auth/login")
    LoginResponse signIn(@Valid @RequestBody LoginRequest request) {
        var token = authentication.signIn(request.email(), request.password());
        return new LoginResponse(token.value(), token.expiresAt());
    }

    @GetMapping("/api/me")
    CurrentOperatorResponse currentOperator(@AuthenticationPrincipal AuthenticatedOperator principal) {
        return CurrentOperatorResponse.of(authentication.currentOperator(principal));
    }

    record LoginRequest(@NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 128) String password) {
    }

    record LoginResponse(String accessToken, Instant expiresAt) {
    }

    record CurrentOperatorResponse(UUID id, UUID organizationId, String email, String name, OperatorRole role,
            Availability availability) {

        static CurrentOperatorResponse of(Operator operator) {
            return new CurrentOperatorResponse(operator.id(), operator.organizationId(), operator.email(),
                    operator.name(), operator.role(), operator.availability());
        }
    }
}
