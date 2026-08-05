package it.davidgreco.metacatalog.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

/**
 * Verifies that {@link EntityLinkView#containment()} resolves as a property in Spring EL — the
 * mechanism Thymeleaf uses for {@code ${link.containment}} on the entity-link page. Unlike the
 * record components it sits beside, this one is a derived method, so it is worth pinning: silently
 * failing to resolve would put the remove control back on structural links.
 */
class EntityLinkViewTest {

  private static Boolean containmentInSpel(String relationType) {
    var view = new EntityLinkView("a", "A", relationType, "b", "B", "source");
    return new SpelExpressionParser()
        .parseExpression("containment")
        .getValue(new StandardEvaluationContext(view), Boolean.class);
  }

  @Test
  void containmentResolvesInSpelForContainmentLinks() {
    assertThat(containmentInSpel("HAS_PART")).isTrue();
    assertThat(containmentInSpel("IS_PART_OF")).isTrue();
  }

  @Test
  void containmentIsFalseForEveryOtherRelationType() {
    assertThat(containmentInSpel("DEPENDS_ON")).isFalse();
    assertThat(containmentInSpel("MAPPED_TO")).isFalse();
  }
}
