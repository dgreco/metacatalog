package it.davidgreco.metacatalog.service;

import com.fasterxml.jackson.databind.JsonNode;
import it.davidgreco.metacatalog.entity.BuiltInTraits;
import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.EntityTypeVersion;
import it.davidgreco.metacatalog.entity.EntityVersion;
import it.davidgreco.metacatalog.repository.EntityRepository;
import it.davidgreco.metacatalog.repository.EntityVersionRepository;
import java.time.Instant;
import java.util.Comparator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records the version history of entities whose type carries the built-in {@link
 * BuiltInTraits#VERSIONABLE} trait, directly or through inheritance.
 *
 * <p>Every path that writes an entity's values calls this after the write: {@link
 * EntityServiceImpl} for user and internal writes, {@link MappedEntityService} for derived
 * entities. Keeping the rules here means no write path can version differently from another.
 *
 * <p>The rules:
 *
 * <ul>
 *   <li>The live type decides, not the type version the entity is pinned to. A type that gains the
 *       trait starts versioning its existing instances, and one that loses it stops.
 *   <li>Every version has a snapshot, the current one included, so the latest snapshot always
 *       equals the live entity.
 *   <li>A write that leaves the values and the pin unchanged records nothing.
 *   <li>The first versioned write to an entity that has no history yet, because its type gained the
 *       trait after the entity was created, first records the state before the write.
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class EntityVersionRecorder {

  /**
   * Compares JSON leaves by value, so that {@code 1.0} (a floating-point node) equals {@code 1} (an
   * integer node), as it does in {@code jsonb}. Without it, re-writing a number in a different
   * notation would record a version although the stored value is unchanged.
   */
  private static final Comparator<JsonNode> BY_VALUE =
      (a, b) -> {
        if (a.equals(b)) return 0;
        if (a.isNumber() && b.isNumber()) return a.decimalValue().compareTo(b.decimalValue());
        return 1;
      };

  private final EntityRepository entityRepository;

  private final EntityVersionRepository entityVersionRepository;

  /**
   * Records version 1 of a newly saved entity, if its type is versionable.
   *
   * @param entity the entity, already passed to {@code save}
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void recordCreated(Entity entity) {
    if (!isVersionable(entity)) return;
    append(entity, null, 0, entity.getValues(), entity.getEntityTypeVersion());
    log.info("Recorded version 1 of entity with id {}", entity.getId());
  }

  /**
   * Records the state an entity has just been written to, if its type is versionable.
   *
   * <p>Takes a row lock on the entity first, so concurrent writers to the same entity number their
   * snapshots one after the other. The latest snapshot is then read from the database rather than
   * from the managed entity, which may have been loaded before another writer committed. The
   * previous values are used only to backfill an entity that has no history yet.
   *
   * @param entity the entity, holding its new values and pin
   * @param previousValues the values before the write
   * @param previousPin the type version the entity was pinned to before the write
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void recordUpdated(Entity entity, JsonNode previousValues, EntityTypeVersion previousPin) {
    if (!isVersionable(entity)) return;
    entityRepository.findByIdForUpdate(entity.getId());
    var latest = entityVersionRepository.findFirstByEntityIdOrderByVersionDesc(entity.getId());
    if (latest.isPresent()) {
      var last = latest.get();
      if (sameState(last.getValues(), last.getEntityTypeVersion(), entity)) return;
      append(
          entity,
          last.getId(),
          last.getVersion(),
          entity.getValues(),
          entity.getEntityTypeVersion());
    } else {
      if (sameState(previousValues, previousPin, entity)) return;
      var before = append(entity, null, 0, previousValues, previousPin);
      append(entity, before.getId(), 1, entity.getValues(), entity.getEntityTypeVersion());
    }
    log.info("Recorded version {} of entity with id {}", entity.getVersion(), entity.getId());
  }

  private boolean isVersionable(Entity entity) {
    return ServiceUtils.implementsTrait(entity.getEntityType(), BuiltInTraits.VERSIONABLE);
  }

  private static boolean sameState(JsonNode values, EntityTypeVersion pin, Entity entity) {
    return pin.getId().equals(entity.getEntityTypeVersion().getId())
        && values.equals(BY_VALUE, entity.getValues());
  }

  private EntityVersion append(
      Entity entity,
      String previousId,
      int previousNumber,
      JsonNode values,
      EntityTypeVersion pin) {
    var snapshot = new EntityVersion();
    snapshot.setEntity(entity);
    snapshot.setVersion(previousNumber + 1);
    // A copy, because JsonNode is mutable and the snapshot must not move with the live entity.
    snapshot.setValues(values.deepCopy());
    snapshot.setEntityTypeVersion(pin);
    snapshot.setPreviousVersionId(previousId);
    snapshot.setCreatedAt(Instant.now());
    entityVersionRepository.save(snapshot);
    entity.setVersion(snapshot.getVersion());
    return snapshot;
  }
}
