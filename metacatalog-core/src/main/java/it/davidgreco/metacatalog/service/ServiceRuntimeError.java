package it.davidgreco.metacatalog.service;

public class ServiceRuntimeError extends RuntimeException {
  public ServiceRuntimeError(String message) {
    super(message);
  }
}
