package it.davidgreco.metacatalog.security;

/** Small shared helpers for the security configuration classes. */
final class SecurityConfigSupport {

  private SecurityConfigSupport() {}

  /**
   * Returns whether a configuration value is present and non-blank.
   *
   * @param value the value to check
   * @return {@code true} if {@code value} is non-null and not blank
   */
  static boolean isSet(final String value) {
    return value != null && !value.isBlank();
  }
}
