package com.communicatorone.operators;

import com.communicatorone.organizations.OrganizationMember;
import java.util.UUID;

record AuthenticatedOperator(UUID operatorId, UUID organizationId) implements OrganizationMember {
}
