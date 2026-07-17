package it.davidgreco.metacatalog.ui;

/**
 * Read model for one mapping entity type relationship rendered on the dashboard.
 *
 * <p>The {@code mappingValues} and {@code entityPathReferences} are carried as pretty-printed JSON
 * strings so the template can render them verbatim in a {@code <pre>} block, mirroring how schemas
 * are shown for traits and entity types.
 *
 * @param id the unique identifier of the mapping relationship
 * @param source the name of the source entity type
 * @param target the name of the target entity type
 * @param mappingValues the mapping expressions as a pretty-printed JSON string
 * @param entityPathReferences the entity path references as a pretty-printed JSON string
 */
public record MappingView(
    String id, String source, String target, String mappingValues, String entityPathReferences) {}
