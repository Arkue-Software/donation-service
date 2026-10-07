package co.gov.redvital.donacion.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "transicion_valida")
public class TransicionValida {
    @Id
    private UUID id;

    @Column(name = "estado_origen_id", nullable = false)
    private UUID estadoOrigenId;

    @Column(name = "estado_destino_id", nullable = false)
    private UUID estadoDestinoId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_tipo", nullable = false, length = 20)
    private ActorTipo actorTipo;

    @Column(nullable = false, length = 120)
    private String operacion;

    @Column(length = 280)
    private String condicion;
}
