package it.davidgreco.metacatalog.ui;

import java.util.List;

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
 * @param aggregate for a version of an aggregate root, the whole aggregate it recorded as
 *     pretty-printed JSON; null otherwise
 * @param frozenRootId for a past version of an aggregate root, the root of its frozen copy, which
 *     functions can still run on; null otherwise
 * @param copyStatuses the run statuses the frozen copy's root holds, as {@code field: status}
 * @param actions the procedures the frozen copy offers, by the capabilities of its root's type
 */
public record EntityVersionView(
    int version,
    boolean current,
    Integer typeVersion,
    String values,
    String createdAt,
    String aggregate,
    String frozenRootId,
    List<String> copyStatuses,
    List<AggregateAction> actions) {}
