-- Trait mix-ins: a trait may now inherit from several traits, not only from its single father.
--
-- Until now a trait had one parent (trait.father_id), so no trait could carry two independent
-- built-ins at once — `Provisionable` and `Authorizable` both descend from `Aggregate`, and nothing
-- could inherit both. The built-in `Asset` trait is exactly that, so traits gain what entity types
-- already had: a father plus an ordered list of mixed-in traits.
--
-- Existing rows need no data migration: every trait created before this script simply has no
-- mix-ins, which is what an empty join table and a NULL snapshot column already say.

-- ---------------------------------------------------------------------------------------------
-- The traits mixed into a trait, in declaration order.
--
-- `position` is part of the key because the order is semantic: the linearization that decides which
-- declaration of a property wins gives the last mix-in the highest precedence. `type_traits` has no
-- such column and relies on insertion order; that is not repeated here.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE trait_traits
(
    trait_id VARCHAR(255) NOT NULL,
    mixin_id VARCHAR(255) NOT NULL,
    position INT          NOT NULL,
    CONSTRAINT pk_trait_traits PRIMARY KEY (trait_id, position)
);

ALTER TABLE trait_traits
    ADD CONSTRAINT fk_tratra_on_trait FOREIGN KEY (trait_id) REFERENCES trait (id);

ALTER TABLE trait_traits
    ADD CONSTRAINT fk_tratra_on_mixin FOREIGN KEY (mixin_id) REFERENCES trait (id);

-- Deleting a trait checks whether anything still mixes it in, which reads this side of the pair.
CREATE INDEX idx_trait_traits_mixin ON trait_traits (mixin_id);

-- ---------------------------------------------------------------------------------------------
-- The mix-in names frozen in each trait snapshot, as entity_type_version.traits does for types.
--
-- Nullable, unlike that column: snapshots taken before this migration predate mix-ins and are left
-- untouched rather than rewritten, and NULL reads as "none".
-- ---------------------------------------------------------------------------------------------
ALTER TABLE trait_version
    ADD COLUMN traits JSONB;
