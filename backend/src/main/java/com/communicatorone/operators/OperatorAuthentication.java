package com.communicatorone.operators;

import com.communicatorone.organizations.CurrentOrganization;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class OperatorAuthentication {

    private static final Logger log = LoggerFactory.getLogger(OperatorAuthentication.class);

    private final OperatorEmailDirectory emailDirectory;
    private final CurrentOrganization currentOrganization;
    private final OperatorRepository operators;
    private final PasswordEncoder passwordEncoder;
    private final OperatorTokens tokens;
    private final String unknownOperatorPasswordHash;

    OperatorAuthentication(OperatorEmailDirectory emailDirectory, CurrentOrganization currentOrganization,
            OperatorRepository operators, PasswordEncoder passwordEncoder, OperatorTokens tokens) {
        this.emailDirectory = emailDirectory;
        this.currentOrganization = currentOrganization;
        this.operators = operators;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.unknownOperatorPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    IssuedToken signIn(String email, String rawPassword) {
        var operator = operatorWithEmail(Operator.normalizedEmail(email));
        if (operator.isEmpty()) {
            passwordEncoder.matches(rawPassword, unknownOperatorPasswordHash);
            throw new InvalidCredentialsException();
        }
        if (!accepts(operator.get(), rawPassword)) {
            throw new InvalidCredentialsException();
        }
        return tokens.issueFor(operator.get());
    }

    @Transactional(readOnly = true)
    Operator currentOperator(AuthenticatedOperator principal) {
        return operators.findById(principal.operatorId()).orElseThrow(OperatorNotFoundException::new);
    }

    private boolean accepts(Operator operator, String rawPassword) {
        try {
            return operator.acceptsPassword(rawPassword, passwordEncoder);
        } catch (IllegalArgumentException unreadablePasswordHash) {
            log.warn("Operator {} has a password hash without a known encoding", operator.id());
            return false;
        }
    }

    private Optional<Operator> operatorWithEmail(String email) {
        return emailDirectory.organizationOf(email)
                .flatMap(organizationId -> currentOrganization.callAs(organizationId,
                        () -> operators.findByEmail(email)));
    }
}
