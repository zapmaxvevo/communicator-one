package com.communicatorone.organizations;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class CurrentOrganization {

    private static final ScopedValue<UUID> SCOPED_ORGANIZATION = ScopedValue.newInstance();

    public void runAs(UUID organizationId, Runnable action) {
        scopeOf(organizationId).run(action);
    }

    public <T> T callAs(UUID organizationId, Supplier<T> action) {
        return scopeOf(organizationId).call(action::get);
    }

    Optional<UUID> resolve() {
        if (SCOPED_ORGANIZATION.isBound()) {
            return Optional.of(SCOPED_ORGANIZATION.get());
        }
        return authenticatedMemberOrganization();
    }

    private static ScopedValue.Carrier scopeOf(UUID organizationId) {
        Objects.requireNonNull(organizationId, "organizationId");
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("The organization cannot be switched inside a transaction");
        }
        return ScopedValue.where(SCOPED_ORGANIZATION, organizationId);
    }

    private static Optional<UUID> authenticatedMemberOrganization() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof OrganizationMember member) {
            return Optional.of(member.organizationId());
        }
        return Optional.empty();
    }
}
