package it.davidgreco.metacatalog.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

/**
 * Verifies that {@link TraitLinkView} record components resolve as properties in Spring EL — the
 * same mechanism Thymeleaf uses to evaluate {@code ${link.source}} etc. in the relationship
 * templates. Guards against the record being rendered as an opaque object.
 */
class TraitLinkViewTest {

  @Test
  void recordComponentsResolveInSpel() {
    var view = new TraitLinkView("A", "DEPENDS_ON", "B");
    var parser = new SpelExpressionParser();
    var context = new StandardEvaluationContext(view);

    assertThat(parser.parseExpression("source").getValue(context, String.class)).isEqualTo("A");
    assertThat(parser.parseExpression("target").getValue(context, String.class)).isEqualTo("B");
    // The relation type is the name posted back to the server when the link is deleted.
    assertThat(parser.parseExpression("relationType").getValue(context, String.class))
        .isEqualTo("DEPENDS_ON");
  }
}
