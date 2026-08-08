package it.davidgreco.metacatalog.service;

import it.davidgreco.metacatalog.entity.RelationType;
import it.davidgreco.metacatalog.entity.Trait;
import java.util.Optional;

/**
 * The ability to create immutable traits and trait relationships, kept deliberately out of {@link
 * TraitService}.
 *
 * <p>This is the mechanism behind "immutable rows come from a startup contributor and nowhere
 * else". Everything in the application injects {@link TraitService} — the REST delegate, the bulk
 * loader, the UI's path through the API — and that interface simply has no method capable of
 * producing an immutable row. Only {@link
 * it.davidgreco.metacatalog.bootstrap.ImmutableModelInstaller} injects this one.
 *
 * <p>It is a separate interface rather than extra methods on the implementation class because the
 * services are exposed as JDK dynamic proxies ({@code proxyTargetClass=false}, see {@code
 * CoreConfig}): the bean is not an instance of {@code TraitServiceImpl}, so a caller could not
 * reach the methods by injecting the class even if they tried. A proxy does implement every
 * interface of its target, which is what makes this one reachable — and reachable only by asking
 * for it by name.
 */
public interface ImmutableTraitWriter {

  /**
   * Creates a trait that can afterwards be neither deleted nor versioned.
   *
   * @param name the trait name
   * @param schema an optional JSON schema string
   * @param fatherName an optional father trait name
   * @return the created immutable trait
   */
  Trait createImmutable(String name, Optional<String> schema, Optional<String> fatherName);

  /**
   * Links two traits with a relationship that can afterwards never be removed. The inverse row is
   * created alongside it and frozen too.
   *
   * @param sourceTraitName the source trait name
   * @param relType the relation type
   * @param targetTraitName the target trait name
   */
  void linkImmutable(String sourceTraitName, RelationType relType, String targetTraitName);
}
