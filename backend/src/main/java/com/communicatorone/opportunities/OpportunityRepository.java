package com.communicatorone.opportunities;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface OpportunityRepository extends JpaRepository<Opportunity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Opportunity> findFirstByPersonIdOrderByOpenedAtDesc(UUID personId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select o from Opportunity o
            where o.status = com.communicatorone.opportunities.OpportunityStatus.OPEN
              and o.lastActivityAt <= :cutoff
            order by o.id""")
    List<Opportunity> findOpenInactiveSince(Instant cutoff);

    @Query(value = "select set_config('lock_timeout', :timeout, true)", nativeQuery = true)
    String limitLockWaitingInCurrentTransaction(String timeout);
}
