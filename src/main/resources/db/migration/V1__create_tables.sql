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

CREATE TABLE entity_relationship
(
    id            VARCHAR(255) NOT NULL,
    source_id     VARCHAR(255) NOT NULL,
    target_id     VARCHAR(255) NOT NUll,
    relation_type VARCHAR(255) NOT NULL,
    CONSTRAINT pk_entity_relationship PRIMARY KEY (id)
);

ALTER TABLE entity_relationship
    ADD CONSTRAINT FK_ENTITY_RELATIONSHIP_ON_SOURCE FOREIGN KEY (source_id) REFERENCES entity (id);

CREATE UNIQUE INDEX idx_entity_relationship_source_id_relation_type_target_id_unq ON trait_relationship (source_id, relation_type, target_id);

CREATE INDEX idx_entity_relationship_source_id_relation_type_unq ON entity_relationship (source_id, relation_type);

CREATE INDEX idx_entity_relationship_target_id_relation_type ON entity_relationship (target_id, relation_type);

ALTER TABLE entity_relationship
    ADD CONSTRAINT FK_ENTITY_RELATIONSHIP_ON_TARGET FOREIGN KEY (target_id) REFERENCES entity (id);

CREATE TABLE mapping_type_relationship
(
    id                     VARCHAR(255) NOT NULL,
    source_id              VARCHAR(255) NOT NULL,
    target_id              VARCHAR(255) NOT NULL,
    relation_type          VARCHAR(255) NOT NULL,
    mapping_values         JSONB        NOT NULL,
    entity_path_references JSONB        NOT NULL,
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

--- ADDING STANDARD TRAITS
INSERT INTO trait (id, name, base_schema, father_id) VALUES ('99f4fbb6-b7e1-49fd-b617-296b05cd4863', 'Aggregate', '{"type": "object", "properties": {}}', NULL);

INSERT INTO trait (id, name, base_schema, father_id) VALUES ('a8eb4327-4682-4d2d-a297-562d7116e9fc', 'Provisionable', '{"type": "object", "properties": {}}', '99f4fbb6-b7e1-49fd-b617-296b05cd4863');
