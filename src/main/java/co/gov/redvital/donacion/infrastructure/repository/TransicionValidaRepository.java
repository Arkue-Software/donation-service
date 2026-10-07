package co.gov.redvital.donacion.infrastructure.repository;

import co.gov.redvital.donacion.domain.model.ActorTipo;
import co.gov.redvital.donacion.domain.model.TransicionValida;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TransicionValidaRepository extends JpaRepository<TransicionValida, UUID> {
    boolean existsByEstadoOrigenIdAndEstadoDestinoIdAndActorTipo(
            UUID estadoOrigenId,
            UUID estadoDestinoId,
            ActorTipo actorTipo);
}
