package com.example.partial;

/**
 * The DISCRIMINATOR for {@link Outcome}, and the only thing it varies is genericity.
 *
 * <p>Inlining {@code Outcome.success} was refused with
 * {@code Cannot infer type arguments for Outcome<>} — the factory is
 * {@code static <K> Outcome<K> success(K val)} and its body's diamond infers from the
 * method's own type parameter, which stops existing once the body is folded into a
 * caller. That refusal has two possible causes and they call for different answers:</p>
 *
 * <ul>
 *   <li><b>Genericity.</b> The AWAY leg cannot invert a GENERIC factory. Then D3's
 *       direction is blocked for `ChapterResult` too — it is generic — and no fixture
 *       choice rescues it.</li>
 *   <li><b>Partial application.</b> The AWAY leg cannot invert a factory that fixes a
 *       constructor argument, generic or not. A wider and more serious finding.</li>
 * </ul>
 *
 * <p>This class is {@code Outcome} with the type parameter removed and nothing else
 * changed: same two-argument package-private constructor, same two intention-named
 * factories each fixing {@code State} to a different constant, same call-site shapes.
 * <b>So whichever way its inline goes, it names the cause</b> — a refusal here means
 * partial application, a success here means genericity.</p>
 */
public class Verdict {

  private final String value;
  private final Outcome.State state;

  Verdict(String value, Outcome.State state) {
    this.value = value;
    this.state = state;
  }

  public String getValue() {
    return value;
  }

  public boolean isSuccess() {
    return state == Outcome.State.SUCCESS;
  }

  public static Verdict success(String val) {
    return new Verdict(val, Outcome.State.SUCCESS);
  }

  public static Verdict failure(String val) {
    return new Verdict(val, Outcome.State.FAILURE);
  }
}
