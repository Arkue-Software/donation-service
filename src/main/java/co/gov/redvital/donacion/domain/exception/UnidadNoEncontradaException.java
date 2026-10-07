package co.gov.redvital.donacion.domain.exception;

import java.util.UUID;

public class UnidadNoEncontradaException extends RuntimeException {
    private final UUID unidadId;
    private final String correlacionId;

    public UnidadNoEncontradaException(UUID unidadId, String correlacionId) {
        super("No se encontró la unidad solicitada: " + unidadId);
        this.unidadId = unidadId;
        this.correlacionId = correlacionId;
    }

    public UUID getUnidadId() { return unidadId; }
    public String getCorrelacionId() { return correlacionId; }
}
