package it.davidgreco.metacatalog.ui;

/**
 * Read model for one row of an instance's version history, rendered oldest first with the current
 * version last.
 *
 * @param version the version number
 * @param current whether this is the latest version, which equals the live instance
 * @param typeVersion the version of the entity type these values were validated against, or null if
 *     it could not be resolved
 * @param values the values at this version, pretty-printed JSON
 * @param createdAt when the version was recorded, as an ISO-8601 instant
 */
public record EntityVersionView(
    int version, boolean current, Integer typeVersion, String values, String createdAt) {}
