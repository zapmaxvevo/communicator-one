package com.communicatorone.opportunities;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface OpportunitySettingsRepository extends JpaRepository<OpportunitySettings, UUID> {

    @Query("select s from OpportunitySettings s")
    Optional<OpportunitySettings> findCurrent();

    default OpportunitySettings currentOrDefaults() {
        return findCurrent().orElseGet(OpportunitySettings::defaults);
    }
}
