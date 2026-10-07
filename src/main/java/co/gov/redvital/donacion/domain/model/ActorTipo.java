package co.gov.redvital.donacion.domain.model;

public enum ActorTipo {
    USUARIO("usuario"),
    SISTEMA("sistema"),
    ANONIMO("anonimo");

    private final String codigo;

    ActorTipo(String codigo) {
        this.codigo = codigo;
    }

    public String codigo() {
        return codigo;
    }
}
