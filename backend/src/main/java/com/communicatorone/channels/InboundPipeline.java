package com.communicatorone.channels;

import com.communicatorone.messaging.ChannelType;
import com.communicatorone.messaging.ReceivedMessage;
import com.communicatorone.messaging.Timeline;
import com.communicatorone.opportunities.OpportunityTracker;
import com.communicatorone.organizations.CurrentOrganization;
import com.communicatorone.people.PersonDirectory;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
class InboundPipeline {

    private static final Logger log = LoggerFactory.getLogger(InboundPipeline.class);
    private static final int MAX_LOGGED_ID_LENGTH = 64;

    private final ChannelAdapterRegistry adapters;
    private final ChannelAccountDirectory accounts;
    private final CurrentOrganization currentOrganization;
    private final PersonDirectory people;
    private final OpportunityTracker opportunities;
    private final Timeline timeline;
    private final ApplicationEventPublisher events;

    InboundPipeline(ChannelAdapterRegistry adapters, ChannelAccountDirectory accounts,
            CurrentOrganization currentOrganization, PersonDirectory people, OpportunityTracker opportunities,
            Timeline timeline, ApplicationEventPublisher events) {
        this.adapters = adapters;
        this.accounts = accounts;
        this.currentOrganization = currentOrganization;
        this.people = people;
        this.opportunities = opportunities;
        this.timeline = timeline;
        this.events = events;
    }

    void receive(ChannelType channelType, WebhookPayload payload) {
        var adapter = adapters.adapterFor(channelType);
        var inboundMessages = adapter.parseInbound(payload);
        var statusUpdates = adapter.parseStatusUpdates(payload);
        for (var inbound : inboundMessages) {
            accountReceiving(channelType, inbound.externalAccountId()).ifPresent(account ->
                    currentOrganization.runAs(account.organizationId(), () -> accept(account, inbound)));
        }
        for (var update : statusUpdates) {
            accountReceiving(channelType, update.externalAccountId()).ifPresent(account ->
                    currentOrganization.runAs(account.organizationId(),
                            () -> timeline.applyStatus(account.id(), update.externalId(), update.status())));
        }
    }

    private Optional<ChannelAccount> accountReceiving(ChannelType channelType, String externalAccountId) {
        var account = accounts.resolve(channelType, externalAccountId);
        if (account.isEmpty()) {
            log.warn("Inbound {} notification for unknown channel account {} was discarded", channelType,
                    loggable(externalAccountId));
        }
        return account;
    }

    private static String loggable(String externalAccountId) {
        var printable = externalAccountId.replaceAll("\\p{Cntrl}", "_");
        return printable.substring(0, Math.min(printable.length(), MAX_LOGGED_ID_LENGTH));
    }

    private void accept(ChannelAccount account, InboundMessage inbound) {
        if (timeline.isRecorded(account.id(), inbound.externalId())) {
            return;
        }
        var personId = people.resolveOrCreate(inbound.sender());
        var opportunityId = opportunities.recordActivity(personId, account.channelType().name());
        var recorded = timeline.recordReceived(new ReceivedMessage(personId, opportunityId, account.id(),
                account.channelType(), inbound.externalId(), inbound.content(), inbound.occurredAt()));
        if (!recorded.duplicate()) {
            events.publishEvent(new MessageReceived(account.organizationId(), recorded.id(), personId, opportunityId,
                    account.id(), account.channelType()));
        }
    }
}
