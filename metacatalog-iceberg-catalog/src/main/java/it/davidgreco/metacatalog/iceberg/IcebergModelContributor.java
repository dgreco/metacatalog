package it.davidgreco.metacatalog.iceberg;

import it.davidgreco.metacatalog.bootstrap.ImmutableModelContributor;
import it.davidgreco.metacatalog.bootstrap.ImmutableModelRegistry;
import it.davidgreco.metacatalog.entity.RelationType;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Declares the immutable model the Iceberg REST catalog stores its registry in: a namespace trait,
 * a table trait, the {@code HAS_PART} composition that sanctions namespace→table entity links, and
 * the two entity types instances are created from.
 *
 * <p>The traits deliberately do <b>not</b> inherit from {@code Aggregate} / {@code
 * AggregateElement}: {@code EntityService.unlink} refuses to remove a {@code HAS_PART} link whose
 * containing side carries the {@code Aggregate} trait, which would make dropping a table
 * impossible. With a dedicated trait pair, dropping is the normal unlink-then-delete path.
 */
@Component
public class IcebergModelContributor implements ImmutableModelContributor {

  @Override
  public void contribute(ImmutableModelRegistry registry) {
    registry.trait(IcebergModel.NAMESPACE_TRAIT, IcebergModel.NAMESPACE_SCHEMA);
    registry.trait(IcebergModel.TABLE_TRAIT, IcebergModel.TABLE_SCHEMA);
    registry.trait(IcebergModel.TABLE_SCHEMA_TRAIT, IcebergModel.TABLE_SCHEMA_SCHEMA);
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
