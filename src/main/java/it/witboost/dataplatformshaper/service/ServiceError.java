package it.witboost.dataplatformshaper.service;

public class ServiceError extends Exception {
    public ServiceError(String message) {
        super(message);
    }
}
