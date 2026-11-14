package com.communicatorone.people;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;

@Service
public class PersonDirectory {

    private final PersonRepository people;
    private final TransactionTemplate writing;
    private final Clock clock;

    PersonDirectory(PersonRepository people, TransactionTemplate writing, Clock clock) {
        this.people = people;
        this.writing = writing;
        this.clock = clock;
    }

    public UUID resolveOrCreate(ContactIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        Assert.state(!TransactionSynchronizationManager.isSynchronizationActive(),
                "A person is resolved in its own transaction, not inside an open one");
        return findIdOf(identity).orElseGet(() -> create(identity));
    }

    private UUID create(ContactIdentity identity) {
        try {
            return writing.execute(transaction -> findIdOf(identity)
                    .orElseGet(() -> people.saveAndFlush(Person.identifiedBy(identity, clock)).id()));
        } catch (DataIntegrityViolationException concurrentCreation) {
            return findIdOf(identity).orElseThrow(() -> concurrentCreation);
        }
    }

    private Optional<UUID> findIdOf(ContactIdentity identity) {
        return people.findIdByIdentity(identity.kind(), identity.value());
    }
}
