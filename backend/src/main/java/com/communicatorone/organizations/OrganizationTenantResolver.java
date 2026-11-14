package com.communicatorone.organizations;

import java.util.Map;
import java.util.UUID;
import org.hibernate.cfg.MultiTenancySettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.stereotype.Component;

@Component
class OrganizationTenantResolver implements CurrentTenantIdentifierResolver<UUID>, HibernatePropertiesCustomizer {

    private static final UUID NO_ORGANIZATION = new UUID(0, 0);

    private final CurrentOrganization currentOrganization;

    OrganizationTenantResolver(CurrentOrganization currentOrganization) {
        this.currentOrganization = currentOrganization;
    }

    @Override
    public UUID resolveCurrentTenantIdentifier() {
        return currentOrganization.resolve().orElse(NO_ORGANIZATION);
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return true;
    }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put(MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER, this);
    }
}
