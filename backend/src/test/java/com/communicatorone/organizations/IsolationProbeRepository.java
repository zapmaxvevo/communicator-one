package com.communicatorone.organizations;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface IsolationProbeRepository extends JpaRepository<IsolationProbe, UUID> {
}
