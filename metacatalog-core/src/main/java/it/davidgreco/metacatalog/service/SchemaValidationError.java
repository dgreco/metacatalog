package it.davidgreco.metacatalog.service;

import java.util.List;
import lombok.Getter;

@Getter
public class SchemaValidationError extends ServiceError {
    private final List<String> errors;

    public SchemaValidationError(List<String> errors) {
        super("Schema validation failed");
        this.errors = errors;
    }
}
