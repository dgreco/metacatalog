package it.davidgreco.metacatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The shaded metacatalog-sparql jar holds Ontop and the libraries only Ontop needs. Everything
 * Ontop shares with the application (Tomcat, Jackson, Guava, the Commons libraries, ...) is left
 * out of it, and Ontop runs on the application's copy. Both halves of that can break silently: a
 * library bundled again puts two copies of its classes on the classpath, with classpath order
 * deciding which loads, the way an old JGraphT once broke aggregate provisioning; and a library the
 * application stops shipping is missing only when Ontop's code that uses it first runs.
 *
 * <p>Both are checked against the application's runtime classpath, which the build writes to {@code
 * target/runtime-classpath.txt} before the tests run, and both read jar entries and class files
 * only, without loading anything.
 */
class ShadedSparqlJarTest {

  /**
   * The packages of the libraries the shaded jar leaves to the application: the groups the shade
   * plugin excludes in metacatalog-sparql/pom.xml, by package.
   */
  private static final List<String> SHARED_PACKAGES =
      List.of(
          "org/apache/tomcat/",
          "org/apache/juli/",
          "com/fasterxml/jackson/",
          "org/aopalliance/",
          "com/github/jsonldjava/",
          "javax/annotation/",
          "com/google/common/",
          "com/google/errorprone/",
          "com/google/j2objc/",
          "com/opencsv/",
          "com/zaxxer/hikari/",
          "org/apache/commons/beanutils/",
          "org/apache/commons/codec/",
          "org/apache/commons/collections/",
          "org/apache/commons/collections4/",
          "org/apache/commons/io/",
          "org/apache/commons/lang3/",
          "org/apache/commons/logging/",
          "org/apache/commons/text/",
          "no/hasmac/",
          "org/antlr/v4/",
          "org/apache/http/",
          "org/checkerframework/",
          "org/jetbrains/annotations/",
          "org/intellij/lang/",
          "org/jspecify/",
          "org/slf4j/");

  private static Path sparqlJar;
  private static Set<String> bundledClasses;
  private static Set<String> shippedClasses;

  @BeforeAll
  static void readRuntimeClasspath() throws IOException {
    List<Path> runtime =
        Stream.of(
                Files.readString(Path.of("target/runtime-classpath.txt"))
                    .strip()
                    .split(File.pathSeparator))
            .map(Path::of)
            .toList();
    sparqlJar =
        runtime.stream()
            .filter(p -> p.getFileName().toString().startsWith("metacatalog-sparql"))
            .findFirst()
            .orElseThrow();
    assumeTrue(
        Files.isRegularFile(sparqlJar),
        "a reactor build that stops before `package` hands over target/classes, which bundles"
            + " nothing");

    bundledClasses = new HashSet<>();
    shippedClasses = new HashSet<>();
    for (Path entry : runtime) {
      Set<String> classes = entry.equals(sparqlJar) ? bundledClasses : shippedClasses;
      if (Files.isRegularFile(entry)) {
        try (ZipFile jar = new ZipFile(entry.toFile())) {
          jar.stream()
              .map(ZipEntry::getName)
              .filter(ShadedSparqlJarTest::isClass)
              .forEach(classes::add);
        }
      }
    }
    // The module's own classes and its relocated copies are its own business.
    bundledClasses.removeIf(name -> name.startsWith("it/davidgreco/"));
  }

  @Test
  void sharesNoClassWithTheJarsTheApplicationShips() {
    Set<String> duplicated = new TreeSet<>(bundledClasses);
    duplicated.retainAll(shippedClasses);

    assertThat(duplicated).isEmpty();
  }

  @Test
  void findsEveryClassItTakesFromTheApplication() throws IOException {
    Set<String> missing = new TreeSet<>();
    try (ZipFile jar = new ZipFile(sparqlJar.toFile())) {
      for (ZipEntry entry : jar.stream().filter(e -> isClass(e.getName())).toList()) {
        try (InputStream in = jar.getInputStream(entry)) {
          for (String referenced : referencedClasses(in)) {
            String file = referenced + ".class";
            if (SHARED_PACKAGES.stream().anyMatch(referenced::startsWith)
                && !shippedClasses.contains(file)
                && !bundledClasses.contains(file)) {
              missing.add(referenced + " (from " + entry.getName() + ")");
            }
          }
        }
      }
    }

    assertThat(missing).isEmpty();
  }

  private static boolean isClass(String name) {
    return name.endsWith(".class") && !name.endsWith("module-info.class");
  }

  /** The classes named by CONSTANT_Class entries, which every field and method reference uses. */
  private static List<String> referencedClasses(InputStream stream) throws IOException {
    DataInputStream in = new DataInputStream(stream);
    // Past the magic number and the minor and major versions to constant_pool_count (JVMS 4.1).
    in.readInt();
    in.readUnsignedShort();
    in.readUnsignedShort();
    int count = in.readUnsignedShort();
    String[] utf8 = new String[count];
    List<Integer> classNameIndexes = new ArrayList<>();
    for (int i = 1; i < count; i++) {
      int tag = in.readUnsignedByte();
      switch (tag) {
        case 1 -> utf8[i] = in.readUTF();
        case 7 -> classNameIndexes.add(in.readUnsignedShort());
        case 8, 16, 19, 20 -> in.readUnsignedShort();
        case 3, 4, 9, 10, 11, 12, 17, 18 -> in.readInt();
        case 5, 6 -> {
          in.readLong();
          i++; // longs and doubles take two slots
        }
        case 15 -> {
          in.readUnsignedByte();
          in.readUnsignedShort();
        }
        default -> throw new IOException("unknown constant pool tag " + tag);
      }
    }
    return classNameIndexes.stream()
        .map(index -> utf8[index])
        .filter(name -> !name.startsWith("["))
        .toList();
  }
}
