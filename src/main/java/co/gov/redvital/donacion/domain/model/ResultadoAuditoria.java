package co.gov.redvital.donacion.domain.model;

public enum ResultadoAuditoria {
    PERMITIDO("permitido"),
    DENEGADO("denegado");

    private final String codigo;

    ResultadoAuditoria(String codigo) {
        this.codigo = codigo;
    }

    public String codigo() {
        return codigo;
    }
}
