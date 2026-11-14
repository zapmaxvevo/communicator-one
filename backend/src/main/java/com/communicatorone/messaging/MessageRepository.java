package com.communicatorone.messaging;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface MessageRepository extends JpaRepository<Message, UUID> {

    @Query("select m.id from Message m where m.channelAccountId = :channelAccountId and m.externalId = :externalId")
    Optional<UUID> findIdByChannelAccountIdAndExternalId(@Param("channelAccountId") UUID channelAccountId,
            @Param("externalId") String externalId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Message> findByChannelAccountIdAndExternalId(UUID channelAccountId, String externalId);
}
