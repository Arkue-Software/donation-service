package co.gov.redvital.donacion.infrastructure.repository;

import co.gov.redvital.donacion.domain.model.ObservacionOperativa;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ObservacionOperativaRepository extends JpaRepository<ObservacionOperativa, UUID> {
    Optional<ObservacionOperativa> findByCodigoAndVigenteTrue(String codigo);
}
