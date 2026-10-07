package co.gov.redvital.donacion.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "unidad")
public class Unidad {
    @Id
    private UUID id;

    @Column(name = "donacion_id", nullable = false)
    private UUID donacionId;

    @Column(name = "componente_id", nullable = false)
    private UUID componenteId;

    @Column(name = "grupo_sanguineo_id", nullable = false)
    private UUID grupoSanguineoId;

    @Column(name = "institucion_custodia_id", nullable = false)
    private UUID institucionCustodiaId;

    @Column(name = "estado_id", nullable = false)
    private UUID estadoId;

    private Boolean apta;

    @Column(name = "fecha_vencimiento", nullable = false)
    private LocalDate fechaVencimiento;

    @Column(name = "volumen_ml")
    private Integer volumenMl;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn;

    @Column(name = "actualizado_en", nullable = false)
    private Instant actualizadoEn;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getDonacionId() { return donacionId; }
    public void setDonacionId(UUID donacionId) { this.donacionId = donacionId; }
    public UUID getComponenteId() { return componenteId; }
    public void setComponenteId(UUID componenteId) { this.componenteId = componenteId; }
    public UUID getGrupoSanguineoId() { return grupoSanguineoId; }
    public void setGrupoSanguineoId(UUID grupoSanguineoId) { this.grupoSanguineoId = grupoSanguineoId; }
    public UUID getInstitucionCustodiaId() { return institucionCustodiaId; }
    public void setInstitucionCustodiaId(UUID institucionCustodiaId) { this.institucionCustodiaId = institucionCustodiaId; }
    public UUID getEstadoId() { return estadoId; }
    public void setEstadoId(UUID estadoId) { this.estadoId = estadoId; }
    public Boolean getApta() { return apta; }
    public void setApta(Boolean apta) { this.apta = apta; }
    public LocalDate getFechaVencimiento() { return fechaVencimiento; }
    public void setFechaVencimiento(LocalDate fechaVencimiento) { this.fechaVencimiento = fechaVencimiento; }
    public Integer getVolumenMl() { return volumenMl; }
    public void setVolumenMl(Integer volumenMl) { this.volumenMl = volumenMl; }
    public Instant getCreadoEn() { return creadoEn; }
    public void setCreadoEn(Instant creadoEn) { this.creadoEn = creadoEn; }
    public Instant getActualizadoEn() { return actualizadoEn; }
    public void setActualizadoEn(Instant actualizadoEn) { this.actualizadoEn = actualizadoEn; }
}
