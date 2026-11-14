package com.communicatorone.opportunities;

import com.communicatorone.organizations.CurrentOrganization;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class OpportunityClosingJob {

    private static final Logger log = LoggerFactory.getLogger(OpportunityClosingJob.class);

    private final JdbcTemplate jdbcTemplate;
    private final CurrentOrganization currentOrganization;
    private final InactiveOpportunityClosing closing;

    OpportunityClosingJob(JdbcTemplate jdbcTemplate, CurrentOrganization currentOrganization,
            InactiveOpportunityClosing closing) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentOrganization = currentOrganization;
        this.closing = closing;
    }

    @Scheduled(cron = "${communicator-one.opportunities.closing-cron}")
    void closeInactiveOpportunitiesOfEveryOrganization() {
        organizationsWithOpenOpportunities().forEach(this::closeInactiveOpportunitiesOf);
    }

    private List<UUID> organizationsWithOpenOpportunities() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT organization_id FROM opportunities WHERE status = 'OPEN' ORDER BY organization_id",
                UUID.class);
    }

    private void closeInactiveOpportunitiesOf(UUID organizationId) {
        try {
            currentOrganization.runAs(organizationId, closing::closeInactiveOpportunities);
        } catch (RuntimeException failure) {
            log.warn("Inactive opportunities of organization {} could not be closed", organizationId, failure);
        }
    }
}
