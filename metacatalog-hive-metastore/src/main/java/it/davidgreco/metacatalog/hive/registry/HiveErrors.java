package it.davidgreco.metacatalog.hive.registry;

import it.davidgreco.metacatalog.service.ServiceError;

/**
 * What the registry throws, and why they are not Thrift's own exception types.
 *
 * <p>{@code MetaException}, {@code NoSuchObjectException} and friends are Thrift-generated and
 * therefore <b>checked</b>. Spring's declarative transactions roll back on unchecked exceptions
 * only, so a registry method that threw one of them partway through its work would have its
 * transaction <em>committed</em> — leaving exactly the half-written state the transaction exists to
 * prevent. Marking every method {@code rollbackFor = Exception.class} would work and would be one
 * forgotten annotation away from silent corruption.
 *
 * <p>So the registry throws these instead, all unchecked, and {@code MetacatalogHmsHandler}
 * translates them into the protocol's vocabulary at the boundary — the same division the Iceberg
 * module has between its registry and its REST exception mapper.
 */
public final class HiveErrors {

  private HiveErrors() {}

  /** The addressed database, table or partition does not exist. Maps to {@code NoSuchObject}. */
  public static class NotFound extends ServiceError {
    public NotFound(String message) {
      super(message);
    }
  }

  /** The name is already taken. Maps to {@code AlreadyExistsException}. */
  public static class AlreadyExists extends ServiceError {
    public AlreadyExists(String message) {
      super(message);
    }
  }

  /** The operation is not legal against the current state. Maps to {@code InvalidOperation}. */
  public static class InvalidOperation extends ServiceError {
    public InvalidOperation(String message) {
      super(message);
    }
  }

  /** A lock could not be taken in time; the caller should retry. Maps to {@code MetaException}. */
  public static class Contended extends ServiceError {
    public Contended(String message) {
      super(message);
    }
  }
}
