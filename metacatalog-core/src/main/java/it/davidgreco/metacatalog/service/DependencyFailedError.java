package it.davidgreco.metacatalog.service;

/**
 * The result given to a task that never ran because something it depends on failed.
 *
 * <p>A type of its own rather than a plain {@link ServiceError} with that message, because a
 * schedule has to report one failure out of possibly several and this is the one worth reporting
 * last: it says only that something else went wrong, never what. In a chain of three resources
 * where the first fails, the other two carry this and the first carries the real cause — picking by
 * arbitrary order would hide the cause behind a restatement of the symptom two times out of three.
 */
public class DependencyFailedError extends ServiceError {

  /** The message every instance carries; the class exists to make it recognisable, not to vary. */
  public static final String MESSAGE = "One or more of the depending tasks failed";

  /** Creates the error. */
  public DependencyFailedError() {
    super(MESSAGE);
  }
}
