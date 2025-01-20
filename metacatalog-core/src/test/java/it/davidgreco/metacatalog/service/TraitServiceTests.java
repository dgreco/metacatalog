package it.davidgreco.metacatalog.service;

import static it.davidgreco.metacatalog.entity.RelationType.DEPENDS_ON;

import it.davidgreco.metacatalog.entity.Trait;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class TraitServiceTests extends CommonServiceTests {

    @Test
    void testCreation() throws ServiceError {
        final TraitService traitService = new TraitService(traitRepository, traitRelationshipRepository);

        traitService.create("trait1", Optional.empty());
        traitService.create("trait2", Optional.empty());
        traitService.create("trait3", Optional.empty());

        traitService.link("trait1", DEPENDS_ON, "trait2");

        traitService.link("trait1", DEPENDS_ON, "trait3");

        Assertions.assertThrows(ServiceError.class, () -> traitService.link("trait1", DEPENDS_ON, "trait3"));

        var list = traitService.linked("trait1", DEPENDS_ON);

        Assertions.assertEquals(2, list.size());

        Assertions.assertEquals(
                Set.of("trait2", "trait3"), list.stream().map(Trait::getName).collect(Collectors.toSet()));

        Assertions.assertThrows(ServiceError.class, () -> traitService.delete("trait1"));

        traitService.unlink("trait1", DEPENDS_ON, "trait2");
        traitService.unlink("trait1", DEPENDS_ON, "trait3");

        traitService.delete("trait1");
        traitService.delete("trait2");
    }
}
