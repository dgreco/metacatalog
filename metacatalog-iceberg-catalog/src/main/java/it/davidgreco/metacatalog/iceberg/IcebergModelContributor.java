package it.davidgreco.metacatalog.iceberg;

import it.davidgreco.metacatalog.bootstrap.ImmutableModelContributor;
import it.davidgreco.metacatalog.bootstrap.ImmutableModelRegistry;
import it.davidgreco.metacatalog.entity.BuiltInTraits;
import it.davidgreco.metacatalog.entity.RelationType;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Declares the immutable model the Iceberg REST catalog stores its registry in: a namespace trait,
 * a table trait, the {@code HAS_PART} composition that sanctions namespace→table entity links, and
 * the two entity types instances are created from.
 *
 * <p>A table is a real aggregate of its schema entities: {@code IcebergTableTrait} inherits {@code
 * Aggregate} and {@code IcebergTableSchemaTrait} inherits {@code AggregateElement}, so the
 * table→schema containment gets aggregate semantics — {@code EntityService.unlink} refuses to
 * detach a schema (it would strand it), and dropping a table goes through {@code
 * AggregateService.delete}, which removes the table and everything hanging off it in one
 * transaction. The <b>namespace</b> trait deliberately stays outside the aggregate model: were it
 * an {@code Aggregate} too, the namespace→table link could never be unlinked and neither dropping
 * nor renaming a table across namespaces would be possible.
 */
@Component
public class IcebergModelContributor implements ImmutableModelContributor {

  @Override
  public void contribute(ImmutableModelRegistry registry) {
    registry.trait(IcebergModel.NAMESPACE_TRAIT, IcebergModel.NAMESPACE_SCHEMA);
    registry.trait(IcebergModel.TABLE_TRAIT, IcebergModel.TABLE_SCHEMA, BuiltInTraits.AGGREGATE);
    registry.trait(
        IcebergModel.TABLE_SCHEMA_TRAIT,
        IcebergModel.TABLE_SCHEMA_SCHEMA,
        BuiltInTraits.AGGREGATE_ELEMENT);
    registry.link(IcebergModel.NAMESPACE_TRAIT, RelationType.HAS_PART, IcebergModel.TABLE_TRAIT);
    registry.link(IcebergModel.TABLE_TRAIT, RelationType.HAS_PART, IcebergModel.TABLE_SCHEMA_TRAIT);
    registry.entityType(
        IcebergModel.NAMESPACE_TYPE,
        ImmutableModelRegistry.EMPTY_SCHEMA,
        List.of(IcebergModel.NAMESPACE_TRAIT));
    registry.entityType(
        IcebergModel.TABLE_TYPE,
        ImmutableModelRegistry.EMPTY_SCHEMA,
        List.of(IcebergModel.TABLE_TRAIT));
    registry.entityType(
        IcebergModel.TABLE_SCHEMA_TYPE,
        ImmutableModelRegistry.EMPTY_SCHEMA,
        List.of(IcebergModel.TABLE_SCHEMA_TRAIT));
  }
}
