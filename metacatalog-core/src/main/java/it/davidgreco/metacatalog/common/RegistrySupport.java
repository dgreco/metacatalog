package it.davidgreco.metacatalog.common;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * The two things every registry built on metacatalog entities has to get right, in one place.
 *
 * <p>A registry — the Iceberg REST catalog, the Hive metastore — stores its objects as ordinary
 * entities. Entities have no name column and no uniqueness constraint on their {@code jsonb}
 * values, so each registry has to find its rows by a jsonpath predicate and serialise its writes
 * with an advisory lock derived from a name. Both jobs have a wrong-looking-but-correct answer that
 * is easy to reimplement badly, which is why they live here rather than being copied.
 */
public final class RegistrySupport {

  private RegistrySupport() {}

  /**
   * Renders a string as a PostgreSQL jsonpath string literal.
   *
   * <p>Control characters and jsonpath metacharacters are emitted as {@code \\uXXXX} escapes, which
   * the jsonpath grammar accepts inside string literals. This is not cosmetic: the Iceberg registry
   * joins namespace levels with {@code } — the one character illegal inside a level, chosen so
   * that a dotted level cannot collide with two levels — and an unescaped one produces a jsonpath
   * the server rejects. Escaping {@code "} and {@code \} is what stops a name from terminating the
   * literal early.
   *
   * @param value the raw string
   * @return the string as a quoted jsonpath literal, escapes included
   */
  public static String jsonPathString(String value) {
    var sb = new StringBuilder(value.length() + 2).append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < 0x20 || c == '"' || c == '\\') {
        sb.append(String.format("\\u%04x", (int) c));
      } else {
        sb.append(c);
      }
    }
    return sb.append('"').toString();
  }

  /**
   * Derives a stable advisory-lock id from a registry name.
   *
   * <p>Steers clear of the ids core reserves — 1 for {@code MappingUpdaterService}, 2 for {@code
   * ImmutableModelInstaller} — because the advisory-lock space is global to the database and a
   * collision there would have a registry serialising against the model installer.
   *
   * <p>CRC32 collisions between two different names are possible and tolerated: the lock is not
   * what makes a registry correct. Callers pair it with a compare-and-swap on the row they are
   * about to write, and that is the guard that holds even when two names happen to hash alike.
   *
   * @param name the name to derive a lock id from, conventionally prefixed by object kind
   * @return the advisory lock id
   */
  public static int lockId(String name) {
    var crc = new CRC32();
    crc.update(name.getBytes(StandardCharsets.UTF_8));
    int id = (int) crc.getValue();
    if (id == 1 || id == 2) {
      id ^= 0x5f3759df;
    }
    return id;
  }
}
