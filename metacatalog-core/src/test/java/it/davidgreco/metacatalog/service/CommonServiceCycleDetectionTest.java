package it.davidgreco.metacatalog.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.EntityRelationship;
import it.davidgreco.metacatalog.entity.RelationType;
import it.davidgreco.metacatalog.repository.EntityRelationshipRepository;
import it.davidgreco.metacatalog.repository.EntityRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CommonService#checkLoops} — the entity-relationship cycle detection. These
 * guard the regression where the depth-first search returned inside the first loop iteration and
 * therefore explored only the first outgoing edge of each node.
 */
class CommonServiceCycleDetectionTest {

  private final RelationType rel = RelationType.DEPENDS_ON;
  private final EntityRepository entityRepository = mock(EntityRepository.class);
  private final EntityRelationshipRepository relRepository =
      mock(EntityRelationshipRepository.class);
  private final Map<String, Entity> entities = new HashMap<>();

  private Entity entity(String id) {
    return entities.computeIfAbsent(
        id,
        key -> {
          Entity e = new Entity();
          e.setId(key);
          lenient().when(entityRepository.findById(key)).thenReturn(Optional.of(e));
          return e;
        });
  }

  /** Wires {@code source --rel--> target...} into the mocked relationship repository. */
  private void edges(String source, String... targets) {
    Entity src = entity(source);
    var rels = new java.util.ArrayList<EntityRelationship>();
    for (String t : targets) {
      // Resolve (and stub findById for) the target BEFORE stubbing r.getTarget(), otherwise the
      // nested stubbing trips Mockito's UnfinishedStubbingException.
      Entity target = entity(t);
      EntityRelationship r = mock(EntityRelationship.class);
      when(r.getTarget()).thenReturn(target);
      rels.add(r);
    }
    when(relRepository.findBySourceAndRelationType(src, rel)).thenReturn(rels);
  }

  private boolean checkLoops(String from, String goal) throws ServiceError {
    return CommonService.checkLoops(
        entityRepository, relRepository, from, new HashSet<>(), goal, rel);
  }

  @Test
  void findsTargetReachableOnlyViaSecondEdge() throws ServiceError {
    // A -> B (dead end) and A -> C -> GOAL. The buggy DFS explored only the first edge (B) and
    // missed the path through C.
    edges("A", "B", "C");
    edges("B");
    edges("C", "GOAL");
    edges("GOAL");
    assertTrue(checkLoops("A", "GOAL"));
  }

  @Test
  void findsDirectSingleEdgeTarget() throws ServiceError {
    edges("A", "GOAL");
    edges("GOAL");
    assertTrue(checkLoops("A", "GOAL"));
  }

  @Test
  void returnsFalseWhenTargetUnreachable() throws ServiceError {
    edges("A", "B", "C");
    edges("B");
    edges("C");
    assertFalse(checkLoops("A", "GOAL"));
  }

  @Test
  void terminatesOnCyclicGraphWithoutReachingTarget() throws ServiceError {
    // A -> B -> A cycle; the visited set must prevent infinite traversal and still return false.
    edges("A", "B");
    edges("B", "A");
    assertFalse(checkLoops("A", "GOAL"));
  }

  @Test
  void detectsDeepMultiBranchLoop() throws ServiceError {
    // A -> B, A -> C -> D -> GOAL: target sits at the end of a deep second branch.
    edges("A", "B", "C");
    edges("B");
    edges("C", "D");
    edges("D", "GOAL");
    edges("GOAL");
    assertTrue(checkLoops("A", "GOAL"));
  }
}
