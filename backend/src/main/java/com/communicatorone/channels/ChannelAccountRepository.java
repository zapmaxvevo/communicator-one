package com.communicatorone.channels;

import com.communicatorone.messaging.ChannelType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ChannelAccountRepository extends JpaRepository<ChannelAccount, UUID> {

    Optional<ChannelAccount> findByChannelTypeAndExternalAccountId(ChannelType channelType, String externalAccountId);
}
