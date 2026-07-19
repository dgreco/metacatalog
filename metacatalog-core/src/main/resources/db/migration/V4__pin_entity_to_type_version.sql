-- Pin each entity to the exact EntityTypeVersion it was created against.
--
-- Design: previously, entity.entity_type_id referenced the live entity_type row, and
-- EntityTypeService.createVersion mutated that row in place (same id, version bumped). As a
-- result every existing entity silently followed the live row when a new version was created,
-- and there was no record of which schema version an entity was validated against at creation
-- time.
--
-- This migration adds an optional FK from entity to entity_type_version. The service layer
-- always sets it for new entities and uses the snapshot's schema for validation, so an entity
-- stays pinned to the version it was created with. Existing rows (if any) get NULL and continue
-- to follow the live row (legacy behaviour) — no backfill is performed.
--
-- EntityTypeService.create / createVersion now also create an EntityTypeVersion snapshot for
-- the CURRENT live version (not just history), so that entities created "now" have a row to
-- pin to. listVersions continues to return [history snapshots..., live] by filtering out the
-- snapshot that matches the current live version.

ALTER TABLE entity ADD COLUMN entity_type_version_id VARCHAR(255);

CREATE INDEX idx_entity_entity_type_version_id ON entity (entity_type_version_id);

ALTER TABLE entity
    ADD CONSTRAINT FK_ENTITY_ON_ENTITY_TYPE_VERSION
        FOREIGN KEY (entity_type_version_id) REFERENCES entity_type_version (id);
