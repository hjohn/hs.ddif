package hs.ddif.test.injectables;

import javax.inject.Inject;

public class SampleWithMultipleAnnotatedConstructors {

  @Inject
  public SampleWithMultipleAnnotatedConstructors() {
  }

  @Inject
  public SampleWithMultipleAnnotatedConstructors(@SuppressWarnings("unused") SampleWithoutConstructor parameter) {
  }
}