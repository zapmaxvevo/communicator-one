package com.communicatorone.organizations;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class CurrentOrganization {

    private static final ScopedValue<UUID> SCOPED_ORGANIZATION = ScopedValue.newInstance();

    public void runAs(UUID organizationId, Runnable action) {
        callAs(organizationId, () -> {
            action.run();
            return null;
        });
    }

    public <T> T callAs(UUID organizationId, Supplier<T> action) {
        Objects.requireNonNull(organizationId, "organizationId");
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("The organization cannot be switched inside a transaction");
        }
        return ScopedValue.where(SCOPED_ORGANIZATION, organizationId).call(action::get);
    }

    Optional<UUID> resolve() {
        if (SCOPED_ORGANIZATION.isBound()) {
            return Optional.of(SCOPED_ORGANIZATION.get());
        }
        return authenticatedMemberOrganization();
    }

    private static Optional<UUID> authenticatedMemberOrganization() {
        return Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
                .filter(Authentication::isAuthenticated)
                .map(Authentication::getPrincipal)
                .filter(OrganizationMember.class::isInstance)
                .map(OrganizationMember.class::cast)
                .map(OrganizationMember::organizationId);
    }
}
