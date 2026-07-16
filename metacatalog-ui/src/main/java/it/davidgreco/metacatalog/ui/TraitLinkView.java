package it.davidgreco.metacatalog.ui;

import it.davidgreco.metacatalog.entity.RelationType;

/**
 * Read model for one directed trait relationship rendered on the dashboard.
 *
 * <p>Because relationships are stored bidirectionally, the dashboard lists only the canonical
 * (primary) direction; the {@code relationType} therefore is always one of the primary relation
 * types ({@code DEPENDS_ON}, {@code HAS_PART}, {@code MAPPED_TO}).
 *
 * @param source the name of the source trait
 * @param relationType the primary relation type from source to target
 * @param target the name of the target trait
 */
public record TraitLinkView(String source, RelationType relationType, String target) {}
