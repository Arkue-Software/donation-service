package co.gov.redvital.donacion.infrastructure.repository;

import co.gov.redvital.donacion.domain.model.Unidad;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UnidadRepository extends JpaRepository<Unidad, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from Unidad u where u.id = :id")
    Optional<Unidad> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
            select u.id
            from Unidad u, EstadoUnidad e
            where e.id = u.estadoId
              and u.fechaVencimiento <= :fecha
              and e.codigo in ('disponible', 'reservada', 'fraccionada', 'en_tamizaje')
            """)
    List<UUID> findIdsUnidadesExpiradasParaVencimiento(@Param("fecha") LocalDate fecha);
}
