package it.witboost.dataplatformshaper.service;

import java.util.List;

public class SchemaValidationError extends Exception {
    public final List<String> errors;

    public SchemaValidationError(List<String> errors) {
        super("Schema validation failed");
        this.errors = errors;
    }
}
