package it.davidgreco.metacatalog.hive;

import it.davidgreco.metacatalog.bootstrap.ImmutableModelContributor;
import it.davidgreco.metacatalog.bootstrap.ImmutableModelRegistry;
import it.davidgreco.metacatalog.entity.BuiltInTraits;
import it.davidgreco.metacatalog.entity.RelationType;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Declares the immutable model the Hive metastore stores its objects in: a database trait, a table
 * trait, a partition trait, the two {@code HAS_PART} compositions that sanction the entity links
 * between them, and the three entity types instances are created from.
 *
 * <p><b>A table is a real aggregate of its partitions.</b> {@code HiveTableTrait} inherits {@code
 * Aggregate} and {@code HivePartitionTrait} inherits {@code AggregateElement}, so the
 * table→partition containment gets aggregate semantics for free: {@code EntityService.unlink}
 * refuses to detach a partition, because doing so would strand it where nothing could reach it
 * again, and dropping a table goes through {@code AggregateService.delete}, which removes the table
 * and every partition hanging off it in one transaction. A stock metastore has to remember to
 * cascade; here it is the model that will not let you forget.
 *
 * <p><b>The database trait deliberately stays outside the aggregate model.</b> This is the same
 * conclusion the Iceberg module reached about namespaces, for the same reason: were the database an
 * {@code Aggregate}, the database→table link could never be unlinked, and neither dropping a table
 * nor renaming one into another database would be possible. A carrier of neither built-in trait is
 * unconstrained in what it may contain, which is exactly the freedom a database needs.
 */
@Component
public class HiveModelContributor implements ImmutableModelContributor {

  @Override
  public void contribute(ImmutableModelRegistry registry) {
    registry.trait(HiveModel.DATABASE_TRAIT, HiveModel.DATABASE_SCHEMA);
    registry.trait(HiveModel.TABLE_TRAIT, HiveModel.TABLE_SCHEMA, BuiltInTraits.AGGREGATE);
    registry.trait(
        HiveModel.PARTITION_TRAIT, HiveModel.PARTITION_SCHEMA, BuiltInTraits.AGGREGATE_ELEMENT);
    registry.link(HiveModel.DATABASE_TRAIT, RelationType.HAS_PART, HiveModel.TABLE_TRAIT);
    registry.link(HiveModel.TABLE_TRAIT, RelationType.HAS_PART, HiveModel.PARTITION_TRAIT);
    registry.entityType(
        HiveModel.DATABASE_TYPE,
        ImmutableModelRegistry.EMPTY_SCHEMA,
        List.of(HiveModel.DATABASE_TRAIT));
    registry.entityType(
        HiveModel.TABLE_TYPE, ImmutableModelRegistry.EMPTY_SCHEMA, List.of(HiveModel.TABLE_TRAIT));
    registry.entityType(
        HiveModel.PARTITION_TYPE,
        ImmutableModelRegistry.EMPTY_SCHEMA,
        List.of(HiveModel.PARTITION_TRAIT));
  }
}
