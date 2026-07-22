package it.davidgreco.metacatalog.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import it.davidgreco.metacatalog.entity.Entity;
import it.davidgreco.metacatalog.entity.EntityRelationship;
import it.davidgreco.metacatalog.entity.MappingEntityRelationship;
import it.davidgreco.metacatalog.entity.RelationType;
import it.davidgreco.metacatalog.repository.EntityRelationshipRepository;
import it.davidgreco.metacatalog.repository.EntityRepository;
import it.davidgreco.metacatalog.repository.MappingEntityRelationshipRepository;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves entities by traversing relationship paths from a starting entity.
 *
 * <p>A path is a string of slash-separated segments, each consisting of a relation type followed by
 * a JSON path expression in curly braces, e.g. {@code "HAS_PART{$.name=='foo'}/DEPENDS_ON{$}"}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EntityPathResolver {

  private static final String INVALID_PATH_SEGMENT = "Invalid path segment: ";

  private final EntityRepository entityRepository;

  private final MappingEntityRelationshipRepository mappingEntityRelationshipRepository;

  private final EntityRelationshipRepository entityRelationshipRepository;

  private final Configuration jsonPathConfiguration;

  /**
   * Retrieves an entity by traversing a path from a starting entity.
   *
   * @param startEntityId the ID of the starting entity
   * @param pathString the path string to traverse
   * @return an Optional containing the entity at the end of the path, or empty if not found
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public Optional<Entity> retrieveEntityByPath(String startEntityId, String pathString) {

    var pathSegments = pathString.split("/");

    var pathExpressionPattern = Pattern.compile("(?<=\\{)([^}]+)(?=})");

    var relTypePattern = Pattern.compile("^\\w*");

    Entity currentEntity =
        entityRepository
            .findById(startEntityId)
            .orElseThrow(
                () -> new NotFoundException("Entity with id " + startEntityId + " not found"));
    for (var segment : pathSegments) {
      var pathExpressions =
          pathExpressionPattern.matcher(segment).results().map(MatchResult::group).toList();
      var relTypes = relTypePattern.matcher(segment).results().map(MatchResult::group).toList();

      if (pathExpressions.size() != 1) throw new ServiceError(INVALID_PATH_SEGMENT + segment);

      if (relTypes.size() != 1) throw new ServiceError(INVALID_PATH_SEGMENT + segment);

      var relTypeStr = relTypes.getFirst();

      var enumSet =
          Arrays.stream(RelationType.values()).map(Enum::name).collect(Collectors.toSet());
      if (!enumSet.contains(relTypeStr)) throw new ServiceError(INVALID_PATH_SEGMENT + segment);

      var relType = RelationType.parse(relTypeStr);
      var pathExpression = pathExpressions.getFirst().trim();

      List<Entity> relationSources =
          switch (relType.category()) {
            case MAPPING ->
                mappingEntityRelationshipRepository
                    .findByTargetAndRelationType(currentEntity, relType.inverse())
                    .stream()
                    .map(MappingEntityRelationship::getSource)
                    .toList();
            case ENTITY ->
                entityRelationshipRepository
                    .findByTargetAndRelationType(currentEntity, relType)
                    .stream()
                    .map(EntityRelationship::getSource)
                    .toList();
          };

      if (relationSources.isEmpty()) return Optional.empty();

      if (!pathExpression.equalsIgnoreCase("$")) {
        var found = false;
        for (var relationSource : relationSources) {
          var json = relationSource.getValues().toPrettyString();
          var dc = JsonPath.using(jsonPathConfiguration).parse(json);
          Object res = dc.read(pathExpression);
          int matchCount;
          if (res instanceof ArrayNode arr) {
            matchCount = arr.size();
          } else if (res == null
              || (res instanceof JsonNode node && (node.isMissingNode() || node.isNull()))) {
            matchCount = 0;
          } else {
            matchCount = 1;
          }
          if (matchCount > 1) throw new ServiceError("Ambiguous path expression: " + segment);
          if (matchCount == 1) {
            currentEntity = relationSource;
            found = true;
            break;
          }
        }
        if (!found) return Optional.empty();
      } else {
        if (relationSources.size() > 1) throw new ServiceError("Ambiguous path: " + segment);
        currentEntity = relationSources.getFirst();
      }
    }

    if (currentEntity.getId().equals(startEntityId)) return Optional.empty();
    else return Optional.of(currentEntity);
  }
}
