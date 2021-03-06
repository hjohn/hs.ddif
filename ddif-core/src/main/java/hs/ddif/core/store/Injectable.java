package hs.ddif.core.store;

import hs.ddif.core.util.AnnotationDescriptor;

import java.lang.reflect.Type;
import java.util.Set;

/**
 * Represents a source for an injectable dependency.  Injectables can be
 * provided in various ways, either by creating a new instance of a class,
 * by using a known instance or by asking a provider for an instance.
 */
public interface Injectable {

  /**
   * Returns the type of the resulting instance provided by this {@link Injectable}.
   *
   * @return the type of the resulting instance provided by this {@link Injectable}, never null
   */
  Type getType();

  /**
   * Returns the qualifiers associated with this Injectable.
   *
   * @return the qualifiers associated with this Injectable, never null but can be empty
   */
  Set<AnnotationDescriptor> getQualifiers();
}
