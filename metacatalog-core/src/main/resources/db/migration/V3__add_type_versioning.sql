-- Versioning support for entity_type and trait.
--
-- Design: "live row + append-only history table". The entity_type / trait tables keep exactly one
-- row per name (the current / live version). On every update a snapshot of the current live state
-- is appended to the *_version history table, then the live row is mutated in place and its
-- version number is bumped. The version_group_id column groups every snapshot of the same logical
-- type together with its live row, so the full version chain can be reconstructed.
--
-- The version chain is carried by the previous_version_id self-referencing FK on the history
-- tables: each snapshot points to the snapshot that preceded it. The UI synthesises "successor-of"
-- graph edges from this chain. No new RelationType is introduced, so the existing relationship
-- model is untouched.

ALTER TABLE entity_type ADD COLUMN version INT NOT NULL DEFAULT 1;
ALTER TABLE entity_type ADD COLUMN version_group_id VARCHAR(255);
UPDATE entity_type SET version_group_id = gen_random_uuid()::text WHERE version_group_id IS NULL;
ALTER TABLE entity_type ALTER COLUMN version_group_id SET NOT NULL;
ALTER TABLE entity_type ALTER COLUMN version DROP DEFAULT;
CREATE INDEX idx_entity_type_version_group_id ON entity_type (version_group_id);

ALTER TABLE trait ADD COLUMN version INT NOT NULL DEFAULT 1;
ALTER TABLE trait ADD COLUMN version_group_id VARCHAR(255);
UPDATE trait SET version_group_id = gen_random_uuid()::text WHERE version_group_id IS NULL;
ALTER TABLE trait ALTER COLUMN version_group_id SET NOT NULL;
ALTER TABLE trait ALTER COLUMN version DROP DEFAULT;
CREATE INDEX idx_trait_version_group_id ON trait (version_group_id);

CREATE TABLE entity_type_version
(
    id                 VARCHAR(255) NOT NULL,
    version_group_id   VARCHAR(255) NOT NULL,
    version            INT          NOT NULL,
    name               VARCHAR(255) NOT NULL,
    base_schema        JSONB        NOT NULL,
    derived_schema     JSONB,
    father_name        VARCHAR(255),
    traits             JSONB        NOT NULL,
    previous_version_id VARCHAR(255),
    created_at         TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_entity_type_version PRIMARY KEY (id)
);

CREATE UNIQUE INDEX idx_entity_type_version_group_version_unq ON entity_type_version (version_group_id, version);
CREATE INDEX idx_entity_type_version_previous ON entity_type_version (previous_version_id);

ALTER TABLE entity_type_version
    ADD CONSTRAINT FK_ENTITY_TYPE_VERSION_ON_PREVIOUS FOREIGN KEY (previous_version_id) REFERENCES entity_type_version (id);

CREATE TABLE trait_version
(
    id                 VARCHAR(255) NOT NULL,
    version_group_id   VARCHAR(255) NOT NULL,
    version            INT          NOT NULL,
    name               VARCHAR(255) NOT NULL,
    base_schema        JSONB        NOT NULL,
    derived_schema     JSONB,
    father_name        VARCHAR(255),
    previous_version_id VARCHAR(255),
    created_at         TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_trait_version PRIMARY KEY (id)
);

CREATE UNIQUE INDEX idx_trait_version_group_version_unq ON trait_version (version_group_id, version);
CREATE INDEX idx_trait_version_previous ON trait_version (previous_version_id);

ALTER TABLE trait_version
    ADD CONSTRAINT FK_TRAIT_VERSION_ON_PREVIOUS FOREIGN KEY (previous_version_id) REFERENCES trait_version (id);
