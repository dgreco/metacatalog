-- Complete metacatalog schema, created in one shot.
--
-- This project does not support migrating an existing database: the schema is always created from
-- scratch, so there is a single baseline migration rather than an incremental chain. Change this
-- file in place when the model changes and recreate the database; do not add V2, V3, ... files
-- unless the project starts needing real migrations again.
--
-- Ordering below is dependency-driven (a table is created before anything that references it).
-- Foreign keys are still added via ALTER TABLE, matching the constraint names Hibernate expects to
-- find, and index names match the @Index declarations on the JPA entities.

-- ---------------------------------------------------------------------------------------------
-- Types: entity_type and trait
--
-- Both carry `version` (the live row's version number) and `version_group_id` (groups the live row
-- with every historical snapshot of the same logical type in the *_version tables below).
-- ---------------------------------------------------------------------------------------------

CREATE TABLE entity_type
(
    id               VARCHAR(255) NOT NULL,
    name             VARCHAR(255) NOT NULL,
    base_schema      JSONB        NOT NULL,
    derived_schema   JSONB,
    father_id        VARCHAR(255),
    version          INT          NOT NULL,
    version_group_id VARCHAR(255) NOT NULL,
    CONSTRAINT pk_entity_type PRIMARY KEY (id)
);

CREATE UNIQUE INDEX idx_entity_type_name_unq ON entity_type (name);

CREATE INDEX idx_entity_type_version_group_id ON entity_type (version_group_id);

ALTER TABLE entity_type
    ADD CONSTRAINT FK_ENTITY_TYPE_ON_FATHER FOREIGN KEY (father_id) REFERENCES entity_type (id);

CREATE TABLE trait
(
    id               VARCHAR(255) NOT NULL,
    name             VARCHAR(255) NOT NULL,
    base_schema      JSONB        NOT NULL,
    derived_schema   JSONB,
    father_id        VARCHAR(255),
    version          INT          NOT NULL,
    version_group_id VARCHAR(255) NOT NULL,
    CONSTRAINT pk_trait PRIMARY KEY (id)
);

CREATE UNIQUE INDEX idx_trait_name_unq ON trait (name);

CREATE INDEX idx_trait_version_group_id ON trait (version_group_id);

ALTER TABLE trait
    ADD CONSTRAINT FK_TRAIT_ON_FATHER FOREIGN KEY (father_id) REFERENCES trait (id);

CREATE TABLE type_traits
(
    entity_type_id VARCHAR(255) NOT NULL,
    trait_id       VARCHAR(255) NOT NULL
);

ALTER TABLE type_traits
    ADD CONSTRAINT fk_typtra_on_entity_type FOREIGN KEY (entity_type_id) REFERENCES entity_type (id);

ALTER TABLE type_traits
    ADD CONSTRAINT fk_typtra_on_trait FOREIGN KEY (trait_id) REFERENCES trait (id);

-- ---------------------------------------------------------------------------------------------
-- Type version history: append-only snapshots
--
-- "Live row + append-only history": the type tables above keep exactly one row per name (the live
-- version). On every update a snapshot of the current live state is appended here, then the live
-- row is mutated in place and its version bumped. previous_version_id chains the snapshots, so the
-- full version history can be reconstructed; the UI synthesises "successor-of" graph edges from it.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE entity_type_version
(
    id                  VARCHAR(255)                NOT NULL,
    version_group_id    VARCHAR(255)                NOT NULL,
    version             INT                         NOT NULL,
    name                VARCHAR(255)                NOT NULL,
    base_schema         JSONB                       NOT NULL,
    derived_schema      JSONB,
    father_name         VARCHAR(255),
    traits              JSONB                       NOT NULL,
    previous_version_id VARCHAR(255),
    created_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_entity_type_version PRIMARY KEY (id)
);

CREATE UNIQUE INDEX idx_entity_type_version_group_version_unq ON entity_type_version (version_group_id, version);

CREATE INDEX idx_entity_type_version_previous ON entity_type_version (previous_version_id);

ALTER TABLE entity_type_version
    ADD CONSTRAINT FK_ENTITY_TYPE_VERSION_ON_PREVIOUS FOREIGN KEY (previous_version_id) REFERENCES entity_type_version (id);

CREATE TABLE trait_version
(
    id                  VARCHAR(255)                NOT NULL,
    version_group_id    VARCHAR(255)                NOT NULL,
    version             INT                         NOT NULL,
    name                VARCHAR(255)                NOT NULL,
    base_schema         JSONB                       NOT NULL,
    derived_schema      JSONB,
    father_name         VARCHAR(255),
    previous_version_id VARCHAR(255),
    created_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_trait_version PRIMARY KEY (id)
);

CREATE UNIQUE INDEX idx_trait_version_group_version_unq ON trait_version (version_group_id, version);

CREATE INDEX idx_trait_version_previous ON trait_version (previous_version_id);

ALTER TABLE trait_version
    ADD CONSTRAINT FK_TRAIT_VERSION_ON_PREVIOUS FOREIGN KEY (previous_version_id) REFERENCES trait_version (id);

-- ---------------------------------------------------------------------------------------------
-- Entities
--
-- entity_type_id points at the live type row. entity_type_version_id additionally pins the entity
-- to the exact snapshot it was created and validated against. Both are NOT NULL: EntityTypeService
-- creates a snapshot for every live version, so there is always a row to pin to, and an unpinned
-- entity would have no record of which schema validated it.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE entity
(
    id                     VARCHAR(255) NOT NULL,
    values                 JSONB        NOT NULL,
    entity_type_id         VARCHAR(255) NOT NULL,
    entity_type_version_id VARCHAR(255) NOT NULL,
    CONSTRAINT pk_entity PRIMARY KEY (id)
);

CREATE INDEX idx_entity_entity_type_id_unq ON entity (entity_type_id);

CREATE INDEX idx_entity_entity_type_version_id ON entity (entity_type_version_id);

ALTER TABLE entity
    ADD CONSTRAINT FK_ENTITY_ON_ENTITY_TYPE FOREIGN KEY (entity_type_id) REFERENCES entity_type (id);

ALTER TABLE entity
    ADD CONSTRAINT FK_ENTITY_ON_ENTITY_TYPE_VERSION FOREIGN KEY (entity_type_version_id) REFERENCES entity_type_version (id);

-- ---------------------------------------------------------------------------------------------
-- Relationships
--
-- source_id / target_id are NOT NULL on every relationship table, matching the
-- @JoinColumn(nullable = false) declarations on CommonRelationship.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE trait_relationship
(
    id            VARCHAR(255) NOT NULL,
    source_id     VARCHAR(255) NOT NULL,
    target_id     VARCHAR(255) NOT NULL,
    relation_type VARCHAR(255) NOT NULL,
    CONSTRAINT pk_trait_relationship PRIMARY KEY (id)
);

CREATE UNIQUE INDEX idx_trait_relationship_source_id_relation_type_target_id ON trait_relationship (source_id, relation_type, target_id);

CREATE INDEX idx_trait_relationship_source_id_relation_type ON trait_relationship (source_id, relation_type);

ALTER TABLE trait_relationship
    ADD CONSTRAINT FK_TRAIT_RELATIONSHIP_ON_SOURCE FOREIGN KEY (source_id) REFERENCES trait (id);

ALTER TABLE trait_relationship
    ADD CONSTRAINT FK_TRAIT_RELATIONSHIP_ON_TARGET FOREIGN KEY (target_id) REFERENCES trait (id);

CREATE TABLE entity_relationship
(
    id            VARCHAR(255) NOT NULL,
    source_id     VARCHAR(255) NOT NULL,
    target_id     VARCHAR(255) NOT NULL,
    relation_type VARCHAR(255) NOT NULL,
    CONSTRAINT pk_entity_relationship PRIMARY KEY (id)
);

CREATE UNIQUE INDEX idx_entity_relationship_source_id_relation_type_target_id ON entity_relationship (source_id, relation_type, target_id);

CREATE INDEX idx_entity_relationship_source_id_relation_type ON entity_relationship (source_id, relation_type);

CREATE INDEX idx_entity_relationship_target_id_relation_type ON entity_relationship (target_id, relation_type);

ALTER TABLE entity_relationship
    ADD CONSTRAINT FK_ENTITY_RELATIONSHIP_ON_SOURCE FOREIGN KEY (source_id) REFERENCES entity (id);

ALTER TABLE entity_relationship
    ADD CONSTRAINT FK_ENTITY_RELATIONSHIP_ON_TARGET FOREIGN KEY (target_id) REFERENCES entity (id);

-- ---------------------------------------------------------------------------------------------
-- Mapping relationships
-- ---------------------------------------------------------------------------------------------

CREATE TABLE mapping_type_relationship
(
    id                         VARCHAR(255) NOT NULL,
    source_id                  VARCHAR(255) NOT NULL,
    target_id                  VARCHAR(255) NOT NULL,
    relation_type              VARCHAR(255) NOT NULL,
    mapping_values             JSONB        NOT NULL,
    entity_path_references     JSONB        NOT NULL,
    source_entity_type_version INTEGER      NOT NULL,
    target_entity_type_version INTEGER      NOT NULL,
    CONSTRAINT pk_mapping_type_relationship PRIMARY KEY (id)
);

CREATE INDEX idx_mapping_type_relationship_source_id_relation_type ON mapping_type_relationship (source_id, relation_type);

CREATE INDEX idx_mapping_type_relationship_source_id_relation_type_target_id ON mapping_type_relationship (source_id, relation_type, target_id);

ALTER TABLE mapping_type_relationship
    ADD CONSTRAINT FK_MAPPING_TYPE_RELATIONSHIP_ON_SOURCE FOREIGN KEY (source_id) REFERENCES entity_type (id);

ALTER TABLE mapping_type_relationship
    ADD CONSTRAINT FK_MAPPING_TYPE_RELATIONSHIP_ON_TARGET FOREIGN KEY (target_id) REFERENCES entity_type (id);

CREATE TABLE mapping_entity_relationship
(
    id                                  VARCHAR(255) NOT NULL,
    source_id                           VARCHAR(255) NOT NULL,
    target_id                           VARCHAR(255) NOT NULL,
    relation_type                       VARCHAR(255) NOT NULL,
    mapping_entity_type_relationship_id VARCHAR(255),
    CONSTRAINT pk_mapping_entity_relationship PRIMARY KEY (id)
);

CREATE UNIQUE INDEX idx_mapping_entity_relationship_source_id_mapping_entity_type_relationship_id ON mapping_entity_relationship (source_id, mapping_entity_type_relationship_id);

CREATE INDEX idx_mapping_entity_relationship_source_id_relation_type ON mapping_entity_relationship (source_id, relation_type);

ALTER TABLE mapping_entity_relationship
    ADD CONSTRAINT FK_MAPPING_ENTITY_RELATIONSHIP_ON_MAPPINGENTITYTYPERELATIONSHIP FOREIGN KEY (mapping_entity_type_relationship_id) REFERENCES mapping_type_relationship (id);

ALTER TABLE mapping_entity_relationship
    ADD CONSTRAINT FK_MAPPING_ENTITY_RELATIONSHIP_ON_SOURCE FOREIGN KEY (source_id) REFERENCES entity (id);

ALTER TABLE mapping_entity_relationship
    ADD CONSTRAINT FK_MAPPING_ENTITY_RELATIONSHIP_ON_TARGET FOREIGN KEY (target_id) REFERENCES entity (id);

-- ---------------------------------------------------------------------------------------------
-- Entity lifecycle events (drive the asynchronous mapping updater)
-- ---------------------------------------------------------------------------------------------

CREATE SEQUENCE IF NOT EXISTS entity_lifecycle_event_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE entity_lifecycle_event
(
    id               BIGINT                      NOT NULL,
    entity_id        VARCHAR(255)                NOT NULL,
    entity_type_name VARCHAR(255)                NOT NULL,
    event_time       TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    process_time     TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    event_type       VARCHAR(255)                NOT NULL,
    event_status     VARCHAR(255)                NOT NULL,
    CONSTRAINT pk_entity_lifecycle_event PRIMARY KEY (id)
);

CREATE INDEX idx_entitylifecycleevent_event_type_event_status ON entity_lifecycle_event (event_type, event_status);

-- ---------------------------------------------------------------------------------------------
-- Built-in traits (see it.davidgreco.metacatalog.entity.BuiltInTraits)
--
-- Each seeded trait starts at version 1 and is its own version group; the group id reuses the
-- trait id, which is already unique. No trait_version snapshot is seeded — history starts at the
-- first update made through TraitService.
--
-- provisioningStatus / provisioningResult are readOnly: the provisioning procedure sets them, not
-- the user, so the UI renders them disabled and validators treat them as server-managed.
-- ---------------------------------------------------------------------------------------------

INSERT INTO trait (id, name, base_schema, father_id, version, version_group_id)
VALUES ('00000000-0000-0000-0000-000000000100', 'Aggregate', '{"type": "object", "properties": {}}', NULL, 1,
        '00000000-0000-0000-0000-000000000100');

INSERT INTO trait (id, name, base_schema, father_id, version, version_group_id)
VALUES ('00000000-0000-0000-0000-000000000200', 'AggregateElement', '{"type": "object", "properties": {}}', NULL, 1,
        '00000000-0000-0000-0000-000000000200');

INSERT INTO trait (id, name, base_schema, father_id, version, version_group_id)
VALUES ('00000000-0000-0000-0000-000000000300', 'Provisionable', '{"type": "object", "properties": {}}',
        '00000000-0000-0000-0000-000000000100', 1, '00000000-0000-0000-0000-000000000300');

INSERT INTO trait (id, name, base_schema, father_id, version, version_group_id)
VALUES ('00000000-0000-0000-0000-000000000400', 'ProvisionableResource', '{
  "type": "object",
  "properties": {
    "provisioningStatus": {
      "type": "string",
      "enum": ["PROVISIONED", "UNPROVISIONED", "FAILED"],
      "readOnly": true
    },
    "provisioningResult": {
      "type": "string",
      "readOnly": true
    }
  }
}', '00000000-0000-0000-0000-000000000200', 1, '00000000-0000-0000-0000-000000000400');

-- Composition between the built-in traits: an Aggregate HAS_PART an AggregateElement.
INSERT INTO trait_relationship (id, source_id, target_id, relation_type)
VALUES ('00000000-0000-0000-0000-000000000100', '00000000-0000-0000-0000-000000000100',
        '00000000-0000-0000-0000-000000000200', 'HAS_PART');
