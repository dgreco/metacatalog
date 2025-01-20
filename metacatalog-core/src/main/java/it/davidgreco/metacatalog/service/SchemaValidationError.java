package it.davidgreco.metacatalog.service;

import java.util.List;

public class SchemaValidationError extends ServiceError {
    public final List<String> errors;

    public SchemaValidationError(List<String> errors) {
        super("Schema validation failed");
        this.errors = errors;
    }
}
