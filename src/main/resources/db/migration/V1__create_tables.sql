CREATE TABLE entity_type
(
    id             VARCHAR(255) NOT NULL,
    name           VARCHAR(255) NOT NULL,
    base_schema    JSONB        NOT NULL,
    derived_schema JSONB,
    father_id      VARCHAR(255),
    CONSTRAINT pk_entity_type PRIMARY KEY (id)
);

CREATE UNIQUE INDEX idx_entity_type_name_unq ON entity_type (name);

ALTER TABLE entity_type
    ADD CONSTRAINT FK_ENTITY_TYPE_ON_FATHER FOREIGN KEY (father_id) REFERENCES entity_type (id);

CREATE TABLE entity
(
    id             VARCHAR(255) NOT NULL,
    values         JSONB        NOT NULL,
    entity_type_id VARCHAR(255) NOT NULL,
    CONSTRAINT pk_entity PRIMARY KEY (id)
);

ALTER TABLE entity
    ADD CONSTRAINT FK_ENTITY_ON_ENTITY_TYPE FOREIGN KEY (entity_type_id) REFERENCES entity_type (id);

CREATE INDEX idx_entity_entity_type_id_unq ON entity (entity_type_id);

CREATE TABLE trait
(
    id             VARCHAR(255) NOT NULL,
    name           VARCHAR(255) NOT NULL,
    base_schema    JSONB        NOT NULL,
    derived_schema JSONB,
    father_id      VARCHAR(255),
    CONSTRAINT pk_trait PRIMARY KEY (id)
);

CREATE UNIQUE INDEX idx_trait_name_unq ON trait (name);

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

CREATE TABLE trait_relationship
(
    id            VARCHAR(255) NOT NULL,
    source_id     VARCHAR(255),
    target_id     VARCHAR(255),
    relation_type VARCHAR(255) NOT NULL,
    CONSTRAINT pk_trait_relationship PRIMARY KEY (id)
);

ALTER TABLE trait_relationship
    ADD CONSTRAINT FK_TRAIT_RELATIONSHIP_ON_SOURCE FOREIGN KEY (source_id) REFERENCES trait (id);

CREATE UNIQUE INDEX idx_trait_relationship_source_id_relation_type_target_id_unq ON trait_relationship (source_id, relation_type, target_id);

CREATE INDEX idx_trait_relationship_source_id_relation_type_unq ON trait_relationship (source_id, relation_type);

ALTER TABLE trait_relationship
    ADD CONSTRAINT FK_TRAIT_RELATIONSHIP_ON_TARGET FOREIGN KEY (target_id) REFERENCES trait (id);