package co.gov.redvital.donacion.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "registro_auditoria")
public class RegistroAuditoria {
    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_tipo", nullable = false, length = 20)
    private ActorTipo actorTipo;

    @Column(name = "actor_id", length = 64)
    private String actorId;

    @Column(length = 40)
    private String rol;

    @Column(name = "jurisdiccion_solicitada", length = 80)
    private String jurisdiccionSolicitada;

    @Column(nullable = false, length = 80)
    private String operacion;

    @Column(name = "recurso_tipo", nullable = false, length = 40)
    private String recursoTipo;

    @Column(name = "recurso_id")
    private UUID recursoId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ResultadoAuditoria resultado;

    @Column(name = "correlacion_id", nullable = false, length = 36)
    private String correlacionId;

    @Column(name = "ocurrido_en", nullable = false)
    private Instant ocurridoEn;

    @Transient
    private Map<String, Object> detalles;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public ActorTipo getActorTipo() { return actorTipo; }
    public void setActorTipo(ActorTipo actorTipo) { this.actorTipo = actorTipo; }
    public String getActorId() { return actorId; }
    public void setActorId(String actorId) { this.actorId = actorId; }
    public String getRol() { return rol; }
    public void setRol(String rol) { this.rol = rol; }
    public String getJurisdiccionSolicitada() { return jurisdiccionSolicitada; }
    public void setJurisdiccionSolicitada(String jurisdiccionSolicitada) { this.jurisdiccionSolicitada = jurisdiccionSolicitada; }
    public String getOperacion() { return operacion; }
    public void setOperacion(String operacion) { this.operacion = operacion; }
    public String getRecursoTipo() { return recursoTipo; }
    public void setRecursoTipo(String recursoTipo) { this.recursoTipo = recursoTipo; }
    public UUID getRecursoId() { return recursoId; }
    public void setRecursoId(UUID recursoId) { this.recursoId = recursoId; }
    public ResultadoAuditoria getResultado() { return resultado; }
    public void setResultado(ResultadoAuditoria resultado) { this.resultado = resultado; }
    public String getCorrelacionId() { return correlacionId; }
    public void setCorrelacionId(String correlacionId) { this.correlacionId = correlacionId; }
    public Instant getOcurridoEn() { return ocurridoEn; }
    public void setOcurridoEn(Instant ocurridoEn) { this.ocurridoEn = ocurridoEn; }
    public Map<String, Object> getDetalles() { return detalles; }
    public void setDetalles(Map<String, Object> detalles) { this.detalles = detalles; }
}
