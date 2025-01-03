package it.davidgreco.metacatalog.service;

public class ServiceError extends Exception {
    public ServiceError(String message) {
        super(message);
    }
}
