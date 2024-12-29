package it.witboost.dataplatformshaper.entity;

import static it.witboost.dataplatformshaper.common.JsonUtils.jsonFactory;

import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
@MappedSuperclass
public class CommonMappingRelationship<T> extends CommonRelationship<T> {
    @org.hibernate.annotations.Type(JsonBinaryType.class)
    @Column(name = "mapping_values", columnDefinition = "jsonb", nullable = false)
    @ToString.Include
    private JsonNode mappingValues;

    @org.hibernate.annotations.Type(JsonBinaryType.class)
    @Column(name = "entity_path_references", columnDefinition = "jsonb", nullable = false)
    @ToString.Include
    private JsonNode entityPathReferences;

    public record EntityPathReference(String alias, String referencePath) {}

    public void setEntityPathReferences(List<MappingEntityTypeRelationship.EntityPathReference> eprs) {
        var nodes = eprs.stream()
                .map(obj -> (JsonNode) jsonFactory.valueToTree(obj))
                .toList();
        var node = jsonFactory.createArrayNode();
        node.addAll(nodes);
        entityPathReferences = node;
    }

    public List<MappingEntityTypeRelationship.EntityPathReference> getEntityPathReferences() {
        var list = new ArrayList<MappingEntityTypeRelationship.EntityPathReference>();
        var nodes = entityPathReferences.elements();
        for (; nodes.hasNext(); ) {
            var node = nodes.next();
            var alias = node.get("alias").asText();
            var referencePath = node.get("referencePath").asText();
            list.add(new MappingEntityTypeRelationship.EntityPathReference(alias, referencePath));
        }
        return list;
    }
}
