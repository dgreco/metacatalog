package it.witboost.dataplatformshaper.service;

import static it.witboost.dataplatformshaper.entity.RelationType.DEPENDS_ON;

import java.io.IOException;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

@SpringBootTest
class TraitServiceTests extends CommonServiceTests {

    @Test
    void testCreation() throws IOException, ServiceError {
        final TraitService traitService = new TraitService(traitRepository, traitRelationshipRepository);

        var trait1 = traitService.create("trait1", Optional.empty());
        traitService.create("trait2", Optional.empty());
        traitService.create("trait3", Optional.empty());

        traitService.link("trait1", DEPENDS_ON, "trait2");

        traitService.link("trait1", DEPENDS_ON, "trait3");

        var list = traitService.linked("trait1", DEPENDS_ON);

        Assertions.assertEquals(2, list.size());

        Assertions.assertEquals("trait2", list.get(0).getName());

        Assertions.assertEquals("trait3", list.get(1).getName());

        Assertions.assertThrows(DataIntegrityViolationException.class, () -> traitService.delete("trait1"));

        traitService.unlink("trait1", DEPENDS_ON, "trait2");
        traitService.unlink("trait1", DEPENDS_ON, "trait3");

        traitService.delete("trait1");
        traitService.delete("trait2");
    }
}
