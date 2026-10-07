package co.gov.redvital.donacion.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "estado_unidad")
public class EstadoUnidad {
    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 40)
    private String codigo;

    @Column(nullable = false, length = 80)
    private String nombre;

    @Column(name = "es_terminal", nullable = false)
    private boolean esTerminal;

    @Column(name = "cuenta_disponible", nullable = false)
    private boolean cuentaDisponible;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getCodigo() { return codigo; }
    public void setCodigo(String codigo) { this.codigo = codigo; }
    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }
    public boolean isEsTerminal() { return esTerminal; }
    public void setEsTerminal(boolean esTerminal) { this.esTerminal = esTerminal; }
    public boolean isCuentaDisponible() { return cuentaDisponible; }
    public void setCuentaDisponible(boolean cuentaDisponible) { this.cuentaDisponible = cuentaDisponible; }
}
