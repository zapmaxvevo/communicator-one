package com.communicatorone.operators;

import com.communicatorone.organizations.OrganizationMember;
import java.util.UUID;
import org.springframework.security.core.AuthenticatedPrincipal;

record AuthenticatedOperator(UUID operatorId, UUID organizationId)
        implements OrganizationMember, AuthenticatedPrincipal {

    @Override
    public String getName() {
        return operatorId.toString();
    }
}
