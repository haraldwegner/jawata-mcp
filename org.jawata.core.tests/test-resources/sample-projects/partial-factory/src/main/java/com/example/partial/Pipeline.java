package com.example.partial;

/**
 * The call sites, in a SECOND file — the shape that matters, because the AWAY leg
 * has to fold the factory back into callers it does not declare.
 *
 * <p>Both factories are called, more than once each, and with arguments that are not
 * all literals. The real {@code ChapterResult} has twelve call sites across four
 * types in one package; five here is enough to tell a total rewrite from a partial
 * one, which is the failure that compiles today and diverges forever.</p>
 */
public class Pipeline {

  public Outcome<String> begin(String order) {
    return Outcome.success(order);
  }

  public Outcome<String> reject(String order, String reason) {
    return Outcome.failure(order + ": " + reason);
  }

  public Outcome<Integer> count(int items) {
    if (items < 0) {
      return Outcome.failure(items);
    }
    return Outcome.success(items);
  }

  public Outcome<String> finish(Outcome<String> previous) {
    if (previous.isSuccess()) {
      return Outcome.success(previous.getValue());
    }
    return previous;
  }
}
