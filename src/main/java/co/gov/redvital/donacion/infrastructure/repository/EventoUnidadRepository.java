package co.gov.redvital.donacion.infrastructure.repository;

import co.gov.redvital.donacion.domain.model.EventoUnidad;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface EventoUnidadRepository extends JpaRepository<EventoUnidad, UUID> {}
