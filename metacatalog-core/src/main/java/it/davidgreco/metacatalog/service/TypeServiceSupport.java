package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.common.JsonUtils.stringToJsonSchema;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Shared helpers for the versioned type services ({@link TraitService} and {@link
 * EntityTypeService}) covering the logic that is genuinely identical between them: parsing a JSON
 * schema string and resolving a requested version to either the live row or a historical snapshot.
 *
 * <p>The divergent parts (derived-schema computation, snapshot deletion and entity-reference
 * constraints) intentionally remain in the individual services because they follow different domain
 * rules.
 */
final class TypeServiceSupport {

  private TypeServiceSupport() {}

  /**
   * Parses a JSON schema string into its schema node, raising a {@link SchemaValidationError} if
   * the string is not a valid JSON schema.
   *
   * @param schema the JSON schema document
   * @return the parsed schema node
   * @throws SchemaValidationError if the schema is invalid
   */
  static JsonNode parseSchema(String schema) throws SchemaValidationError {
    var eitherSchema = stringToJsonSchema(schema);
    if (eitherSchema.isLeft()) {
      throw new SchemaValidationError(eitherSchema.getLeft());
    }
    return eitherSchema.get().getSchemaNode();
  }

  /**
   * Resolves a requested version number to either the live row (when it is the current version) or
   * the matching historical snapshot, validating that the version is within bounds.
   *
   * @param live the live row
   * @param liveVersion the live row's current version number
   * @param versionGroupId the version-group id shared by the live row and all its snapshots
   * @param requested the requested version number
   * @param notFoundMessage the error message used when the requested version does not exist
   * @param snapshotLookup looks up a snapshot by (version-group id, version)
   * @param <L> the live type
   * @param <S> the snapshot type
   * @return the live row (if {@code requested} is current) or the snapshot
   * @throws ServiceError if the requested version is out of range or missing
   */
  static <L, S> Object resolveVersion(
      L live,
      int liveVersion,
      String versionGroupId,
      int requested,
      String notFoundMessage,
      BiFunction<String, Integer, Optional<S>> snapshotLookup)
      throws ServiceError {
    if (requested == liveVersion) {
      return live;
    }
    if (requested > liveVersion || requested < 1) {
      throw new ServiceError(notFoundMessage);
    }
    return snapshotLookup
        .apply(versionGroupId, requested)
        .orElseThrow(() -> new ServiceError(notFoundMessage));
  }
}
