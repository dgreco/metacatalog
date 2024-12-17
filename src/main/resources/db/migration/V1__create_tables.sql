CREATE TABLE entity_type
(
    id     VARCHAR(255) NOT NULL,
    name   VARCHAR(255) NOT NULL,
    schema JSONB NOT NULL,
    father_id VARCHAR(255) NULL,
    CONSTRAINT pk_entity_type PRIMARY KEY (id)
);

CREATE UNIQUE INDEX idx_entity_type_id_unq ON entity_type(id);
CREATE UNIQUE INDEX idx_entity_type_name_unq ON entity_type(name);

ALTER TABLE entity_type
    ADD CONSTRAINT FK_ENTITY_TYPE_ON_ENTITY_TYPE_ID FOREIGN KEY (father_id) REFERENCES entity_type (id);

CREATE TABLE entity
(
    id             VARCHAR(255) NOT NULL,
    values         JSONB NOT NULL,
    entity_type_id VARCHAR(255) NOT NULL,
    CONSTRAINT pk_entity PRIMARY KEY (id)
);

CREATE UNIQUE INDEX idx_entity_id_unq ON entity_type(id);

ALTER TABLE entity
    ADD CONSTRAINT FK_ENTITY_ON_ENTITY_TYPE_ID FOREIGN KEY (entity_type_id) REFERENCES entity_type (id);
