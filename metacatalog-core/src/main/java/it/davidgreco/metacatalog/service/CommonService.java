package it.davidgreco.metacatalog.service;

/**
 * Common service interface for all services.
 *
 * <p>Static utility methods that were previously on this interface have been extracted to {@link
 * ServiceUtils} so that implementors only inherit the real contract.
 *
 * @param <T> the entity type managed by the service
 * @param <K> the key type used to identify entities
 */
public interface CommonService<T, K> {

  /**
   * Reads an entity by its key.
   *
   * @param key the key identifying the entity
   * @return the entity
   */
  T read(K key);

  /**
   * Deletes an entity by its key.
   *
   * @param key the key identifying the entity to delete
   */
  void delete(K key);

  /**
   * Checks if an entity with the given key exists.
   *
   * @param key the key to check
   * @return true if an entity with the key exists, false otherwise
   */
  boolean exists(K key);
}
