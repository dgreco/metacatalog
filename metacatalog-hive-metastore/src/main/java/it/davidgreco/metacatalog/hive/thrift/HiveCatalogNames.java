package it.davidgreco.metacatalog.hive.thrift;

/**
 * Hive's catalog-qualified database naming, which the flat protocol operations carry inline.
 *
 * <p>Hive 4 has a catalog above databases, but most of the older Thrift operations take a single
 * database string. Rather than add a parameter, the client encodes the pair into that string:
 * {@code MetaStoreUtils.prependCatalogToDbName} renders {@code @hive#sales}, and "every database in
 * the catalog" as {@code @hive#} with the pattern appended.
 *
 * <p>This matters more than it looks. {@code HiveMetaStoreClient.getAllDatabases()} sends a
 * catalog-qualified <em>pattern</em>, so a server that treats the whole string as a database name
 * quietly returns nothing — no error, just an empty catalog, which is exactly what a real client
 * saw from this metastore before it understood the encoding. Thrift's generated client never sends
 * the prefix, so the in-build test could not have caught it.
 *
 * <p>This metastore has one catalog, so the prefix is stripped and the catalog part ignored rather
 * than modelled. Names are stored and returned unqualified, which is what Hive's own metastore
 * does.
 */
final class HiveCatalogNames {

  /** {@code MetaStoreUtils.CATALOG_DB_THRIFT_NAME_MARKER}. */
  private static final char MARKER = '@';

  /** {@code MetaStoreUtils.CATALOG_DB_SEPARATOR}. */
  private static final char SEPARATOR = '#';

  private HiveCatalogNames() {}

  /**
   * The database name or pattern with any catalog qualification removed.
   *
   * @param name a name or pattern, possibly of the form {@code @catalog#name}
   * @return the name part, or the input unchanged when it carries no prefix
   */
  static String database(String name) {
    if (name == null || name.isEmpty() || name.charAt(0) != MARKER) return name;
    var separator = name.indexOf(SEPARATOR);
    return separator < 0 ? name : name.substring(separator + 1);
  }

  /**
   * The same, for a pattern, where what remains after the prefix may be empty.
   *
   * @param pattern a pattern, possibly catalog-qualified
   * @return the pattern part, with an empty remainder meaning "everything"
   */
  static String pattern(String pattern) {
    var stripped = database(pattern);
    return stripped == null || stripped.isEmpty() ? "*" : stripped;
  }
}
