package com.communicatorone.channels;

import com.communicatorone.messaging.ChannelType;
import com.communicatorone.messaging.ReceivedMessage;
import com.communicatorone.messaging.Timeline;
import com.communicatorone.opportunities.OpportunityTracker;
import com.communicatorone.organizations.CurrentOrganization;
import com.communicatorone.people.PersonDirectory;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
class InboundPipeline {

    private static final Logger log = LoggerFactory.getLogger(InboundPipeline.class);
    private static final int MAX_LOGGED_ID_LENGTH = 64;
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]");

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
            inOrganizationOf(channelType, inbound.externalAccountId(), account -> accept(account, inbound));
        }
        for (var update : statusUpdates) {
            inOrganizationOf(channelType, update.externalAccountId(),
                    account -> timeline.applyStatus(account.id(), update.externalId(), update.status()));
        }
    }

    private void inOrganizationOf(ChannelType channelType, String externalAccountId,
            Consumer<ChannelAccount> action) {
        accounts.resolve(channelType, externalAccountId).ifPresentOrElse(
                account -> currentOrganization.runAs(account.organizationId(), () -> action.accept(account)),
                () -> log.warn("Inbound {} notification for unknown channel account {} was discarded", channelType,
                        loggable(externalAccountId)));
    }

    private static String loggable(String externalAccountId) {
        var truncated = externalAccountId.substring(0, Math.min(externalAccountId.length(), MAX_LOGGED_ID_LENGTH));
        return CONTROL_CHARACTERS.matcher(truncated).replaceAll("_");
    }

    private void accept(ChannelAccount account, InboundMessage inbound) {
        if (timeline.isRecorded(account.id(), inbound.externalId())) {
            return;
        }
        var personId = people.resolveOrCreate(inbound.sender(), inbound.senderDisplayName());
        var opportunityId = opportunities.recordActivity(personId, account.channelType().name());
        var recorded = timeline.recordReceived(new ReceivedMessage(personId, opportunityId, account.id(),
                account.channelType(), inbound.externalId(), inbound.content(), inbound.occurredAt()));
        if (!recorded.duplicate()) {
            events.publishEvent(new MessageReceived(account.organizationId(), recorded.id(), personId, opportunityId,
                    account.id(), account.channelType()));
        }
    }
}
