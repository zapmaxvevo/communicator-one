package com.communicatorone.people;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PersonRepository extends JpaRepository<Person, UUID> {

    @Query("select p.id from Person p join p.identities i where i.kind = :kind and i.value = :value")
    Optional<UUID> findIdByIdentity(@Param("kind") IdentityKind kind, @Param("value") String value);
}
