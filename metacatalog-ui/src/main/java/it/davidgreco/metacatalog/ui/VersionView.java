package it.davidgreco.metacatalog.ui;

import java.util.List;

/**
 * Read-model for a single row in the version-history list of a trait or entity type.
 *
 * <p>The list page renders one of these per version, oldest first, with the live (current) version
 * last. {@code live} is {@code true} for the current row so the template can tag it. {@code traits}
 * is only populated for entity types; it is empty for trait versions. {@code createdAt} is {@code
 * null} for the live row, which has no capture timestamp.
 */
public record VersionView(
    int version,
    boolean live,
    String fatherName,
    List<String> traits,
    String schema,
    String createdAt) {}
