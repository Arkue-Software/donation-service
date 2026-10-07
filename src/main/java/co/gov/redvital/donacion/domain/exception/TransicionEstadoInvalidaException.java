package co.gov.redvital.donacion.domain.exception;

public class TransicionEstadoInvalidaException extends RuntimeException {
    private final String estadoOrigen;
    private final String estadoDestino;
    private final String correlacionId;

    public TransicionEstadoInvalidaException(String message, String estadoOrigen, String estadoDestino, String correlacionId) {
        super(message);
        this.estadoOrigen = estadoOrigen;
        this.estadoDestino = estadoDestino;
        this.correlacionId = correlacionId;
    }

    public String getEstadoOrigen() { return estadoOrigen; }
    public String getEstadoDestino() { return estadoDestino; }
    public String getCorrelacionId() { return correlacionId; }
}
