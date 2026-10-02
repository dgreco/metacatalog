package it.davidgreco.metacatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import it.davidgreco.metacatalog.sparql.OntopRepositoryConfig;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/**
 * Ontop reaches the application only inside the shaded {@code metacatalog-sparql} jar, where its
 * JGraphT 0.9.3 is relocated to a private package. The shade plugin's dependency-reduced POM keeps
 * Ontop off the classpath of consumers that resolve the module from a repository, but not of the
 * reactor builds (Docker, CI, {@code mvn install}) that produce the application jar; there the
 * exclusion on the {@code metacatalog-sparql} dependency is what does it.
 *
 * <p>No Spring context: the assertions are about the test classpath, which Maven resolves exactly
 * as it resolves the runtime one packaged into the jar.
 */
class ShadedOntopClasspathTest {

  /**
   * Ontop's {@code org.javabits.jgrapht:jgrapht-core:0.9.3} declares the same {@code org.jgrapht}
   * package as the 1.5.3 core uses. With both jars present, classpath order decides which {@code
   * Graph} loads, and when the old one wins {@code CycleDetector} fails with {@code
   * NoSuchMethodError: Graph.getType()} while provisioning an aggregate.
   */
  @Test
  void jgraphtIsOnTheClasspathOnce() throws IOException {
    assertThat(resources("org/jgrapht/Graph.class")).hasSize(1);
  }

  /**
   * Excluding only the old JGraphT is not enough: Ontop is compiled against its {@code
   * DirectedGraph}, which 1.5.3 removed, so an unshaded Ontop jar next to the shaded one fails with
   * {@code NoClassDefFoundError} as soon as its copy of a class loads first.
   */
  @Test
  void ontopComesOnlyFromTheSparqlModule() throws IOException {
    assertThat(resources("it/unibz/inf/ontop/spec/ontology/impl/ClassifiedTBoxImpl.class"))
        .allSatisfy(url -> assertThat(url.toString()).contains("metacatalog-sparql"));
  }

  /**
   * log4j 1.x is end-of-life, with unfixed CVEs in classes such as {@code JMSAppender}
   * (CVE-2021-4104) and {@code SocketServer} (CVE-2019-17571). Ontop pulls it in through {@code
   * org.protege.xmlcatalog}, whose log4j calls {@code log4j-over-slf4j} answers, and the shade
   * plugin bundled it into the sparql jar, unrelocated, where its {@code Logger} won over the
   * bridge's.
   */
  @Test
  void log4jOneIsNotOnTheClasspath() throws IOException {
    assertThat(resources("org/apache/log4j/net/JMSAppender.class")).isEmpty();
    assertThat(resources("org/apache/log4j/net/SocketServer.class")).isEmpty();
  }

  /**
   * The shaded jar holds Ontop and the libraries only Ontop needs. Tomcat, Jackson and logback
   * reached it as transitive dependencies of the Spring Boot starters and of Ontop, unrelocated,
   * beside the jars the application ships anyway: two copies of each class, with classpath order
   * deciding which loads, the way it did for JGraphT.
   */
  @Test
  void sparqlJarBundlesNoTomcatJacksonOrLogback() throws IOException, URISyntaxException {
    Path sparql =
        Path.of(
            OntopRepositoryConfig.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
    assumeTrue(
        Files.isRegularFile(sparql),
        "a reactor build that stops before `package` hands over target/classes, which bundles"
            + " nothing");
    List<String> bundledElsewhere =
        List.of(
            "org/apache/catalina/",
            "org/apache/coyote/",
            "org/apache/tomcat/",
            "org/apache/juli/",
            "com/fasterxml/jackson/",
            "tools/jackson/",
            "ch/qos/logback/");
    try (ZipFile jar = new ZipFile(sparql.toFile())) {
      assertThat(jar.stream().map(ZipEntry::getName))
          .filteredOn(name -> bundledElsewhere.stream().anyMatch(name::startsWith))
          .isEmpty();
    }
  }

  private static List<URL> resources(String name) throws IOException {
    return Collections.list(ShadedOntopClasspathTest.class.getClassLoader().getResources(name));
  }
}
