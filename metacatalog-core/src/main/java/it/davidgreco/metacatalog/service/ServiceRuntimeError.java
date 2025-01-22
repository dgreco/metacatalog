package it.davidgreco.metacatalog.service;

public class ServiceRuntimeError extends RuntimeException {
    public ServiceRuntimeError(String message) {
        super(message);
    }

    public ServiceRuntimeError(Throwable cause) {
        super(cause);
    }
}
