package com.communicatorone.operators;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface OperatorRepository extends JpaRepository<Operator, UUID> {

    Optional<Operator> findByEmail(String email);
}
