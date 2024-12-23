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

        traitService.create("trait1", Optional.empty());
        traitService.create("trait2", Optional.empty());

        traitService.link("trait1", DEPENDS_ON, "trait2");

        Assertions.assertThrows(DataIntegrityViolationException.class, () -> traitService.delete("trait1"));

        traitService.unlink("trait1", DEPENDS_ON);

        traitService.delete("trait1");
        traitService.delete("trait2");
    }
}
