package co.gov.redvital.donacion.infrastructure.repository;

import co.gov.redvital.donacion.domain.model.EstadoUnidad;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EstadoUnidadRepository extends JpaRepository<EstadoUnidad, UUID> {
    Optional<EstadoUnidad> findByCodigo(String codigo);
}
