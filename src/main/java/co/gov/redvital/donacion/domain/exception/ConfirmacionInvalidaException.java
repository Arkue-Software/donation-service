package co.gov.redvital.donacion.domain.exception;

public class ConfirmacionInvalidaException extends RuntimeException {
    private final String recursoIdEsperado;
    private final String confirmacionRecibida;
    private final String correlacionId;

    public ConfirmacionInvalidaException(
            String message,
            String recursoIdEsperado,
            String confirmacionRecibida,
            String correlacionId) {
        super(message);
        this.recursoIdEsperado = recursoIdEsperado;
        this.confirmacionRecibida = confirmacionRecibida;
        this.correlacionId = correlacionId;
    }

    public String getRecursoIdEsperado() { return recursoIdEsperado; }
    public String getConfirmacionRecibida() { return confirmacionRecibida; }
    public String getCorrelacionId() { return correlacionId; }
}
