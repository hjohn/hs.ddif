package hs.ddif.core.store;

import hs.ddif.core.util.AnnotationDescriptor;

import java.util.Collections;
import java.util.Set;

public class Injectables {

  public static Injectable create() {
    return new Injectable() {

      @Override
      public Class<?> getType() {
        return String.class;
      }

      @Override
      public Set<AnnotationDescriptor> getQualifiers() {
        return Collections.emptySet();
      }

      @Override
      public String toString() {
        return "Injectable(String.class)";
      }
    };
  }
}
