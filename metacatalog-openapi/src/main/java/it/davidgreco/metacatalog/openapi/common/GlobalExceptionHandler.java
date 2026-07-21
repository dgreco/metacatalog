package it.davidgreco.metacatalog.openapi.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import it.davidgreco.metacatalog.openapi.model.SystemError;
import it.davidgreco.metacatalog.openapi.model.ValidationError;
import it.davidgreco.metacatalog.service.NotFoundException;
import it.davidgreco.metacatalog.service.SchemaValidationError;
import it.davidgreco.metacatalog.service.ServiceError;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Translates exceptions thrown during request handling into the OpenAPI contract's {@link
 * ValidationError} (for 4xx) and {@link SystemError} (for 5xx) response shapes.
 *
 * <p>This advice is the single owner of the exception→HTTP-status→response-body mapping for the
 * REST API. It handles both:
 *
 * <ul>
 *   <li><b>Framework exceptions</b> — bean-validation failures ({@link
 *       MethodArgumentNotValidException}), malformed JSON bodies ({@link
 *       HttpMessageNotReadableException}), bad path/query params ({@link
 *       MethodArgumentTypeMismatchException}), missing required params ({@link
 *       MissingServletRequestParameterException}), unknown URLs ({@link NoHandlerFoundException},
 *       {@link NoResourceFoundException}), wrong HTTP methods ({@link
 *       HttpRequestMethodNotSupportedException}), and unsupported content types ({@link
 *       HttpMediaTypeNotSupportedException}).
 *   <li><b>Business exceptions</b> — {@link SchemaValidationError} (400 with the validation error
 *       list), {@link NotFoundException} (404), {@link ServiceError} (400), {@link
 *       DataIntegrityViolationException} (400 with a generic non-leaking message), {@link
 *       JsonProcessingException} (400), and {@link IllegalArgumentException} (400, with
 *       enum-constant messages replaced by a generic string).
 * </ul>
 *
 * <p>A catch-all {@link Exception} handler returns 500 with a generic {@link SystemError} body so
 * that no unexpected failure ever leaks Spring's default {@code ProblemDetail} (RFC 7807), which is
 * not in the OpenAPI contract.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  // ──────────────────────────── Framework exceptions ────────────────────────────

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ValidationError> handleValidation(MethodArgumentNotValidException e) {
    List<String> errors = new ArrayList<>();
    e.getBindingResult()
        .getFieldErrors()
        .forEach(fe -> errors.add(fe.getField() + ": " + fe.getDefaultMessage()));
    e.getBindingResult()
        .getGlobalErrors()
        .forEach(ge -> errors.add(ge.getObjectName() + ": " + ge.getDefaultMessage()));
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ValidationError(errors));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ValidationError> handleUnreadable(HttpMessageNotReadableException e) {
    log.debug("Malformed request body received", e);
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new ValidationError(List.of("Malformed request body")));
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ValidationError> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(
            new ValidationError(
                List.of(
                    "Parameter '"
                        + e.getName()
                        + "' must be of type "
                        + (e.getRequiredType() != null
                            ? e.getRequiredType().getSimpleName()
                            : "unknown"))));
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<ValidationError> handleMissingParam(
      MissingServletRequestParameterException e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new ValidationError(List.of("Missing required parameter: " + e.getParameterName())));
  }

  @ExceptionHandler(NoHandlerFoundException.class)
  public ResponseEntity<ValidationError> handleNoHandler(NoHandlerFoundException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new ValidationError(List.of("Resource not found: " + e.getRequestURL())));
  }

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ValidationError> handleNoResource(NoResourceFoundException e) {
    // Thrown by the static-resource handler when no controller maps the URL (e.g. API paths
    // like /metacatalog/v1/traits that fall through, or stale service-worker registrations).
    // Without this handler the exception hits the catch-all, logging a full stack trace at
    // ERROR for what is a routine client 404.
    log.debug("No static resource for {} {}", e.getHttpMethod(), e.getResourcePath());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new ValidationError(List.of("Resource not found: " + e.getResourcePath())));
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ValidationError> handleMethodNotSupported(
      HttpRequestMethodNotSupportedException e) {
    var supported = e.getSupportedHttpMethods();
    String msg =
        "HTTP method '"
            + e.getMethod()
            + "' is not supported for this endpoint"
            + (supported != null ? "; supported: " + supported : "");
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
        .body(new ValidationError(List.of(msg)));
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<ValidationError> handleMediaTypeNotSupported(
      HttpMediaTypeNotSupportedException e) {
    String msg =
        "Content-Type '"
            + e.getContentType()
            + "' is not supported; supported: "
            + e.getSupportedMediaTypes();
    return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
        .body(new ValidationError(List.of(msg)));
  }

  // ──────────────────────────── Business exceptions ─────────────────────────────

  @ExceptionHandler(SchemaValidationError.class)
  public ResponseEntity<ValidationError> handleSchemaValidation(SchemaValidationError e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ValidationError(e.getErrors()));
  }

  @ExceptionHandler(NotFoundException.class)
  public ResponseEntity<ValidationError> handleNotFound(NotFoundException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new ValidationError(List.of(e.getMessage())));
  }

  @ExceptionHandler(ServiceError.class)
  public ResponseEntity<ValidationError> handleServiceError(ServiceError e) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new ValidationError(List.of(e.getMessage())));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ValidationError> handleDataIntegrity(DataIntegrityViolationException e) {
    // A DB constraint violation (e.g. duplicate name) is a client input error, not a server fault.
    // Report it as 400 with a generic message that does not leak DB internals. Services may also
    // wrap DIV into ServiceError.forDataIntegrity(e) before reaching here; that path is handled by
    // the ServiceError handler above using the same constant.
    log.debug("Data integrity violation on API request", e);
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new ValidationError(List.of(ServiceError.DATA_INTEGRITY_VIOLATION)));
  }

  @ExceptionHandler(JsonProcessingException.class)
  public ResponseEntity<ValidationError> handleJsonProcessing(JsonProcessingException e) {
    log.debug("Malformed JSON in API request", e);
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new ValidationError(List.of("Malformed JSON input")));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ValidationError> handleIllegalArgument(IllegalArgumentException e) {
    log.debug("Illegal argument in API request", e);
    // IAE from RelationType.valueOf() leaks the enum class name and package ("No enum constant
    // it.davidgreco..."). Replace those with a generic message; keep user-facing IAE messages
    // from WrappedJsonNode (e.g. "Path '...' did not resolve to a value").
    String message =
        e.getMessage() != null && e.getMessage().startsWith("No enum constant")
            ? "Unsupported parameter value"
            : e.getMessage();
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new ValidationError(List.of(message)));
  }

  // ──────────────────────────── Catch-all ────────────────────────────

  @ExceptionHandler(Exception.class)
  public ResponseEntity<SystemError> handleUnexpected(Exception e) {
    log.error("Unexpected error handling API request", e);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(new SystemError("Internal server error"));
  }
}
