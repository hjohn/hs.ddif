package hs.ddif.plugins;

import hs.ddif.core.Producer;
import hs.ddif.core.ProvidedInjectable;
import hs.ddif.core.bind.Binding;
import hs.ddif.core.bind.Key;
import hs.ddif.core.inject.store.ClassInjectable;
import hs.ddif.core.store.Injectable;
import hs.ddif.core.store.InjectableStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DependencySorter {

  public static List<Class<?>> getInTopologicalOrder(InjectableStore<Injectable> store, Set<ClassInjectable> classInjectables) {
    DirectedGraph<Class<?>> dg = new DirectedGraph<>();

    /*
     * Keep track of a Map which links a Producer class to the annotated production result class (the
     * class with the Producer annotation on it).  This is needed because the Producer class is often
     * a dependency injected into other classes, and if so, the class carrying the Producer annotation
     * must be registered first as this registration will automatically register the Producer class
     * (which is depended on) as well.
     */

    Map<Class<?>, Class<?>> annotatedClassByProducerClass = new HashMap<>();

    for(ClassInjectable injectable : classInjectables) {
      dg.addNode(injectable.getInjectableClass());

      Producer producer = injectable.getInjectableClass().getAnnotation(Producer.class);

      if(producer != null) {
        annotatedClassByProducerClass.put(producer.value(), injectable.getInjectableClass());
      }
    }

    for(ClassInjectable classInjectable : classInjectables) {
      for(Binding[] bindings : classInjectable.getBindings().values()) {
        for(Binding binding : bindings) {
          Key requiredKey = binding.getRequiredKey();

          if(requiredKey != null) {
            for(Injectable injectable : store.resolve(requiredKey.getType(), (Object[])requiredKey.getQualifiersAsArray())) {
              Class<?> requiredClass = injectable.getInjectableClass();

              if(injectable instanceof ProvidedInjectable) {
                ProvidedInjectable providedInjectable = (ProvidedInjectable)injectable;

                requiredClass = providedInjectable.getClassImplementingProvider();
              }

              dg.addEdge(requiredClass, classInjectable.getInjectableClass());
            }

            /*
             * Also create link between the class with Producer annotation and the class depending on the
             * Producer.  The store.resolve call won't find this relationship as the Producer itself was
             * not added to the store, only its annotated result class.
             */

            Class<?> producerAnnotatedClass = annotatedClassByProducerClass.get(requiredKey.getType());

            if(producerAnnotatedClass != null) {
              dg.addEdge(producerAnnotatedClass, classInjectable.getInjectableClass());
            }
          }
        }
      }
    }

    return TopologicalSort.sort(dg);
  }
}
