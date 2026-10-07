package co.gov.redvital.donacion.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "evento_unidad")
public class EventoUnidad {
    @Id
    private UUID id;

    @Column(name = "unidad_id", nullable = false)
    private UUID unidadId;

    @Column(name = "estado_anterior_id")
    private UUID estadoAnteriorId;

    @Column(name = "estado_nuevo_id", nullable = false)
    private UUID estadoNuevoId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_tipo", nullable = false, length = 20)
    private ActorTipo actorTipo;

    @Column(name = "actor_id", nullable = false)
    private String actorId;

    @Column(name = "institucion_id", nullable = false)
    private UUID institucionId;

    @Column(name = "observacion_id")
    private UUID observacionId;

    @Column(name = "correlacion_id", nullable = false, length = 36)
    private String correlacionId;

    @Column(name = "ocurrido_en", nullable = false)
    private Instant ocurridoEn;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getUnidadId() { return unidadId; }
    public void setUnidadId(UUID unidadId) { this.unidadId = unidadId; }
    public UUID getEstadoAnteriorId() { return estadoAnteriorId; }
    public void setEstadoAnteriorId(UUID estadoAnteriorId) { this.estadoAnteriorId = estadoAnteriorId; }
    public UUID getEstadoNuevoId() { return estadoNuevoId; }
    public void setEstadoNuevoId(UUID estadoNuevoId) { this.estadoNuevoId = estadoNuevoId; }
    public ActorTipo getActorTipo() { return actorTipo; }
    public void setActorTipo(ActorTipo actorTipo) { this.actorTipo = actorTipo; }
    public String getActorId() { return actorId; }
    public void setActorId(String actorId) { this.actorId = actorId; }
    public UUID getInstitucionId() { return institucionId; }
    public void setInstitucionId(UUID institucionId) { this.institucionId = institucionId; }
    public UUID getObservacionId() { return observacionId; }
    public void setObservacionId(UUID observacionId) { this.observacionId = observacionId; }
    public String getCorrelacionId() { return correlacionId; }
    public void setCorrelacionId(String correlacionId) { this.correlacionId = correlacionId; }
    public Instant getOcurridoEn() { return ocurridoEn; }
    public void setOcurridoEn(Instant ocurridoEn) { this.ocurridoEn = ocurridoEn; }
}
