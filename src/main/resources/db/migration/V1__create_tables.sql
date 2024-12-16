CREATE TABLE address
(
    id            VARCHAR(255) NOT NULL,
    address_line1 VARCHAR(255) NOT NULL,
    address_line2 VARCHAR(255),
    city          VARCHAR(255) NOT NULL,
    state         VARCHAR(255) NOT NULL,
    country       VARCHAR(255) NOT NULL,
    zipcode       VARCHAR(255) NOT NULL,
    customer_id   VARCHAR(255),
    CONSTRAINT pk_address PRIMARY KEY (id)
);

CREATE UNIQUE INDEX idx_address_id_unq ON address(id);

CREATE TABLE customer
(
    id          VARCHAR(255) NOT NULL,
    first_name  VARCHAR(255),
    middle_name VARCHAR(255),
    family_name VARCHAR(255),
    age         INTEGER,
    CONSTRAINT pk_customer PRIMARY KEY (id)
);

CREATE UNIQUE INDEX idx_customer_id_unq ON customer(id);

ALTER TABLE address
    ADD CONSTRAINT FK_ADDRESS_ON_CUSTOMERID FOREIGN KEY (customer_id) REFERENCES customer (id);

CREATE TABLE entity_type
(
    id     VARCHAR(255) NOT NULL,
    name   VARCHAR(255) NOT NULL,
    schema JSONB
);

CREATE UNIQUE INDEX idx_entity_type_id_unq ON entity_type(id);
CREATE UNIQUE INDEX idx_entity_type_name_unq ON entity_type(name);
