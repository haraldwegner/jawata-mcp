package com.example.partial;

/**
 * Call sites for {@link Verdict}, mirroring {@link Pipeline}'s shapes exactly — both
 * factories, more than once each, arguments that are not all literals, in a second
 * file. Anything that differs between this and {@code Pipeline} would confound the
 * discriminator, so nothing does except the missing type parameter.
 */
public class Review {

  public Verdict begin(String order) {
    return Verdict.success(order);
  }

  public Verdict reject(String order, String reason) {
    return Verdict.failure(order + ": " + reason);
  }

  public Verdict count(int items) {
    if (items < 0) {
      return Verdict.failure(String.valueOf(items));
    }
    return Verdict.success(String.valueOf(items));
  }

  public Verdict finish(Verdict previous) {
    if (previous.isSuccess()) {
      return Verdict.success(previous.getValue());
    }
    return previous;
  }
}
