package it.davidgreco.metacatalog.service;

/**
 * Result of reading a versioned type — either the live (current) row or a frozen snapshot.
 *
 * <p>Replaces the previous {@code Object} return type that forced every caller into an {@code
 * instanceof} check. Callers pattern-match exhaustively:
 *
 * <pre>{@code
 * switch (result) {
 *   case VersionResult.Live<T> l -> ...
 *   case VersionResult.Snapshot<S> s -> ...
 * }
 * }</pre>
 *
 * @param <L> the live type
 * @param <S> the snapshot type
 */
public sealed interface VersionResult<L, S> permits VersionResult.Live, VersionResult.Snapshot {

  /** The live (current) row. */
  record Live<L, S>(L value) implements VersionResult<L, S> {}

  /** A frozen snapshot row. */
  record Snapshot<L, S>(S value) implements VersionResult<L, S> {}

  /**
   * Wraps a raw object (as returned by {@link TypeServiceSupport#resolveVersion}) into the
   * appropriate {@code Live} or {@code Snapshot} variant.
   *
   * @param result the raw object (either a live row or a snapshot row)
   * @param liveType the expected live type
   * @param snapshotType the expected snapshot type
   * @param <L> the live type
   * @param <S> the snapshot type
   * @return a {@code Live} or {@code Snapshot} wrapping the result
   */
  @SuppressWarnings("unchecked")
  static <L, S> VersionResult<L, S> wrap(Object result, Class<L> liveType, Class<S> snapshotType) {
    if (liveType.isInstance(result)) {
      return new Live<>(liveType.cast(result));
    }
    if (snapshotType.isInstance(result)) {
      return new Snapshot<>(snapshotType.cast(result));
    }
    throw new IllegalStateException("Unexpected version result type: " + result.getClass());
  }
}
