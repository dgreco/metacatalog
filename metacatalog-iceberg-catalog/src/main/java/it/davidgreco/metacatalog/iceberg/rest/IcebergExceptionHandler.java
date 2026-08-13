package it.davidgreco.metacatalog.iceberg.rest;

import it.davidgreco.metacatalog.service.ServiceError;
import lombok.extern.slf4j.Slf4j;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.CommitStateUnknownException;
import org.apache.iceberg.exceptions.ForbiddenException;
import org.apache.iceberg.exceptions.NamespaceNotEmptyException;
import org.apache.iceberg.exceptions.NoSuchIcebergTableException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.exceptions.NotAuthorizedException;
import org.apache.iceberg.exceptions.NotFoundException;
import org.apache.iceberg.exceptions.UnprocessableEntityException;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.rest.responses.ErrorResponse;
import org.apache.iceberg.rest.responses.ErrorResponseParser;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps exceptions to the Iceberg REST error contract — the same status table iceberg-core's own
 * reference adapter uses — with the body rendered by {@link ErrorResponseParser} so the wire shape
 * ({@code {"error": {"message", "type", "code"}}}) is exactly what REST clients expect.
 */
@Slf4j
@RestControllerAdvice(assignableTypes = IcebergRestController.class)
public class IcebergExceptionHandler {

  @ExceptionHandler(RuntimeException.class)
  public ResponseEntity<String> handle(RuntimeException e) {
    int code = statusOf(e);
    if (code >= 500) {
      log.error("Iceberg REST request failed", e);
    }
    var response =
        ErrorResponse.builder()
            .responseCode(code)
            .withType(e.getClass().getSimpleName())
            .withMessage(e.getMessage())
            .build();
    return ResponseEntity.status(code)
        .contentType(MediaType.APPLICATION_JSON)
        .body(ErrorResponseParser.toJson(response));
  }

  private static int statusOf(RuntimeException e) {
    return switch (e) {
      case IllegalArgumentException iae -> 400;
      case ValidationException ve -> 400;
      case NotAuthorizedException nae -> 401;
      case ForbiddenException fe -> 403;
      case NoSuchNamespaceException nsne -> 404;
      case NoSuchIcebergTableException nsite -> 404;
      case NoSuchTableException nste -> 404;
      case NotFoundException nfe -> 404;
      case it.davidgreco.metacatalog.service.NotFoundException mnfe -> 404;
      case UnsupportedOperationException uoe -> 406;
      case AlreadyExistsException aee -> 409;
      case CommitFailedException cfe -> 409;
      case NamespaceNotEmptyException nnee -> 409;
      case UnprocessableEntityException uee -> 422;
      case CommitStateUnknownException csue -> 500;
      case ServiceError se -> 400;
      default -> 500;
    };
  }
}
