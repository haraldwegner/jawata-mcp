package com.example.partial;

/**
 * Shaped on the fork's {@code com.iluwatar.saga.orchestration.ChapterResult} — the
 * one human-written self-returning factory the S9a survey leaves reachable.
 *
 * <p>Three properties are copied deliberately, because each one is a thing the round
 * trip could break on:</p>
 *
 * <ul>
 *   <li><b>Generic.</b> The real one is {@code ChapterResult<K>}, and the earlier
 *       surveys were blind to generic factories twice, so genericity is not an
 *       incidental detail of this corpus.</li>
 *   <li><b>A two-argument, package-private constructor.</b> Package-private is what
 *       makes the AWAY leg legal at all: inlining folds the factory back into its
 *       callers, which must then be able to reach the constructor.</li>
 *   <li><b>TWO factories that each fix one argument to a different constant.</b> This
 *       is the property under test. A human's factory here is a PARTIAL APPLICATION —
 *       it hides {@code State} and exposes only the value — and its name states the
 *       intention rather than the construction.</li>
 * </ul>
 *
 * <p>The one thing NOT copied is Lombok. The real class carries {@code @Getter}, which
 * is why it needs a vendored fixture; the accessor is written out here instead. That
 * substitution is safe for THIS question and unsafe for D3's: annotation processing
 * cannot change whether a partial application survives a round trip, but it is exactly
 * what makes the real file human-authored rather than authored by us.</p>
 */
public class Outcome<K> {

  private final K value;
  private final State state;

  Outcome(K value, State state) {
    this.value = value;
    this.state = state;
  }

  public K getValue() {
    return value;
  }

  public boolean isSuccess() {
    return state == State.SUCCESS;
  }

  public static <K> Outcome<K> success(K val) {
    return new Outcome<>(val, State.SUCCESS);
  }

  public static <K> Outcome<K> failure(K val) {
    return new Outcome<>(val, State.FAILURE);
  }

  /** State for an outcome. */
  public enum State {
    SUCCESS,
    FAILURE
  }
}
