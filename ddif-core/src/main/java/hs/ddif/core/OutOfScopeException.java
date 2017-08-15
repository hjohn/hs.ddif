package hs.ddif.core;

/**
 * Thrown when a scoped bean is required without the appropriate scope being active.
 */
public class OutOfScopeException extends RuntimeException {

  public OutOfScopeException(String message) {
    super(message);
  }
}
