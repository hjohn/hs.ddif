package hs.ddif.core.inject.consistency;

import hs.ddif.core.bind.Binding;
import hs.ddif.core.bind.Key;
import hs.ddif.core.store.InjectableStore;
import hs.ddif.core.store.Resolver;
import hs.ddif.core.store.StoreConsistencyPolicy;
import hs.ddif.core.util.AnnotationDescriptor;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Singleton;

import org.apache.commons.lang3.reflect.TypeUtils;

/**
 * Policy that makes sure the InjectableStore at all times contains
 * injectables that can be fully resolved.
 */
public class InjectorStoreConsistencyPolicy<T extends ScopedInjectable> implements StoreConsistencyPolicy<T> {

  /**
   * Map containing the number of times a specific Key (a reference to a specific class
   * with qualifiers) is referenced.
   */
  private final Map<Key, Integer> referenceCounters = new HashMap<>();

  @Override
  public void addAll(Resolver<T> baseResolver, List<T> injectables) {
    InjectableStore<T> tempStore = new InjectableStore<>();
    IncludingResolver resolver = new IncludingResolver(baseResolver, tempStore);

    tempStore.putAll(injectables);

    /*
     * In order to register a group of injectables at once, changes in reference counters
     * are tracked and used to adjust singular dependency checks.
     *
     * Since all injectables are part of the internal resolver (and thus all dependencies
     * should be correct assuming a valid set of injectables was provided), any error
     * while updating the reference counters can immediately be aborted.
     */

    Map<Key, Integer> referenceCounterAdjustments = new HashMap<>();
    List<T> injectablesAdded = new ArrayList<>();

    // Attempt adding all injectables, while keeping track of injectables with errors:
    try {
      for(T injectable : injectables) {
        checkAddition(resolver, injectable, referenceCounterAdjustments);
        add(injectable);
        increaseReferenceCounters(injectable, referenceCounterAdjustments);
        injectablesAdded.add(injectable);
      }

      ensureNoCyclicDependencies(resolver, injectables);
    }
    catch(Exception e) {
      for(T addedInjectable : injectablesAdded) {
        remove(addedInjectable);
      }

      throw e;
    }
  }

  @Override
  public void removeAll(Resolver<T> baseResolver, List<T> injectables) {

    /*
     * In order to remove a group of injectables at once, changes in reference counters
     * are tracked and used to adjust singular dependency checks.
     *
     * Errors are kept track of while modifying the counters and checked again at the end
     * of the process.  If the error persists, the counters are restored to their
     * original values and the last error is thrown as an exception.
     */

    Map<Key, Integer> referenceCounterAdjustments = new HashMap<>();
    Set<T> injectablesWithErrors = new HashSet<>();

    // Attempt removing all injectables, while keeping track of injectables with errors:
    for(T injectable : injectables) {
      try {
        checkRemoval(injectable, referenceCounterAdjustments);
      }
      catch(Exception e) {
        injectablesWithErrors.add(injectable);
      }

      // Remove always, even if there was an error:
      remove(injectable);

      // Track reference counter changes:
      decreaseReferenceCounters(injectable, referenceCounterAdjustments, true);
    }

    /*
     * All injectables are removed now, but there may be errors.  Since all relevant injectables
     * are not part of the store at this point (and thus all dependencies should be correct
     * assuming a valid set of injectables was provided), rechecking of errors simply involves
     * adding and again removing the injectable that was in error before and see if it is now
     * valid.
     */

    for(T injectable : injectables) {
      if(injectablesWithErrors.contains(injectable)) {
        add(injectable);
        increaseReferenceCounters(injectable, referenceCounterAdjustments);

        try {
          checkRemoval(injectable, referenceCounterAdjustments);
          remove(injectable);
          decreaseReferenceCounters(injectable, referenceCounterAdjustments, true);
        }
        catch(Exception e) {
          // Given group of injectables was not valid to remove, add all except the one that could not be removed again and rethrow last exception:
          for(T injectableToRemove : injectables) {
            if(!injectableToRemove.equals(injectable)) {
              add(injectableToRemove);
            }
          }

          throw e;
        }
      }
    }
  }

  private void checkAddition(Resolver<T> resolver, T injectable, Map<Key, Integer> referenceCounterAdjustments) {
    ensureSingularDependenciesHold(injectable.getType(), injectable.getQualifiers(), referenceCounterAdjustments);
    ensureRequiredBindingsAreAvailable(resolver, injectable);
  }

  private void checkRemoval(T injectable, Map<Key, Integer> referenceCounterAdjustments) {
    ensureSingularDependenciesHold(injectable.getType(), injectable.getQualifiers(), referenceCounterAdjustments);
  }

  private void add(T injectable) {
    increaseReferenceCounters(injectable, referenceCounters);
  }

  private void remove(T injectable) {
    decreaseReferenceCounters(injectable, referenceCounters, false);
  }

  private static void increaseReferenceCounters(ScopedInjectable injectable, Map<Key, Integer> referenceCounters) {
    for(Binding binding : injectable.getBindings()) {
      Key requiredKey = binding.getRequiredKey();

      if(requiredKey != null) {
        addReference(requiredKey, referenceCounters);
      }
    }
  }

  private static void decreaseReferenceCounters(ScopedInjectable injectable, Map<Key, Integer> referenceCounters, boolean allowNegativeReferenceCount) {
    for(Binding binding : injectable.getBindings()) {
      Key requiredKey = binding.getRequiredKey();

      if(requiredKey != null) {
        removeReference(requiredKey, referenceCounters, allowNegativeReferenceCount);
      }
    }
  }

  private static void addReference(Key key, Map<Key, Integer> referenceCounters) {
    referenceCounters.merge(key, 1, Integer::sum);
  }

  private static void removeReference(Key key, Map<Key, Integer> referenceCounters, boolean allowNegativeReferenceCount) {
    referenceCounters.merge(key, -1, (a, b) -> a + b == 0 ? null : a + b);

    if(referenceCounters.getOrDefault(key, 0) < 0 && !allowNegativeReferenceCount) {
      throw new IllegalStateException("Assertion error");
    }
  }

  /*
   * Checks if adding (or removing) the given type with qualifiers would cause any direct bindings dependent
   * on the type to become unresolvable.  The existence of the key in the referenceCounters map means there
   * is exactly one of the given type with qualifiers available already (reference counter is never 0, the key
   * is not present in that case).
   *
   * Therefore adding (or removing) a type which is assignable to one of the keys in the map (and has the
   * same qualifiers) would cause some bindings to become unresolvable.
   */
  // Note: loops through all bindings made, quite expensive when there are many keys due to generic assignment check
  private void ensureSingularDependenciesHold(Type type, Set<AnnotationDescriptor> qualifiers, Map<Key, Integer> referenceCounterAdjustments) {
    for(Key key : referenceCounters.keySet()) {
      if(TypeUtils.isAssignable(type, key.getType()) && qualifiers.containsAll(key.getQualifiers())) {
        int count = referenceCounters.get(key);

        Integer adjustment = referenceCounterAdjustments.get(key);

        if(adjustment != null) {
          count -= adjustment;

          if(count < 0) {
            throw new IllegalStateException("reference count (" + referenceCounters.get(key) + ") became negative after adjustment (" + adjustment + "): " + key);
          }
        }

        if(count > 0) {
          // since only a count is tracked, it is not possible to show which dependencies would be unresolvable
          throw new ViolatesSingularDependencyException(type, key, true);
        }
      }
    }
  }

  private void ensureRequiredBindingsAreAvailable(Resolver<T> resolver, T injectable) {

    /*
     * Check the created bindings for unresolved or ambigious dependencies and scope problems:
     */

    for(Binding binding : injectable.getBindings()) {
      Key requiredKey = binding.getRequiredKey();

      if(requiredKey != null) {
        Set<T> injectables = resolver.resolve(requiredKey.getType(), (Object[])requiredKey.getQualifiersAsArray());

        ensureBindingIsSingular(binding, injectables);

        T dependency = injectables.iterator().next();  // Previous ensureBindingIsSingular check ensures there is only a single element in the set

        if(dependency.isTemplate()) {  // When there is only a single instance (with no way to create more), there are never any scope conflicts
          ensureBindingScopeIsValid(injectable, dependency);
        }
      }
    }
  }

  private void ensureBindingIsSingular(Binding binding, Set<T> injectables) {
    if(injectables.size() != 1) {
      throw new UnresolvableDependencyException(binding, injectables);
    }
  }

  private static void ensureBindingScopeIsValid(ScopedInjectable injectable, ScopedInjectable dependentInjectable) {

    /*
     * Perform scope check.  Having a dependency on a narrower scoped injectable would mean the injected
     * dependency of narrower scope is not updated when the scope changes, resulting in unpredictable
     * behaviour.
     *
     * Other frameworks solve this by injecting an adapter instead that relays calls to a specific instance
     * of the dependency based on current scope.  As this is non-trivial a ScopeConflictException is
     * thrown instead.
     */

    Annotation dependencyScopeAnnotation = dependentInjectable.getScope();
    Annotation injectableScopeAnnotation = injectable.getScope();

    if(isNarrowerScope(injectableScopeAnnotation, dependencyScopeAnnotation)) {
      throw new ScopeConflictException(injectable + " is dependent on narrower scoped dependency: " + dependentInjectable.getType());
    }
  }

  private void ensureNoCyclicDependencies(Resolver<T> resolver, List<T> injectables) {
    class CycleDetector {
      Set<T> input = new HashSet<>(injectables);
      Set<T> visited = new HashSet<>();
      List<T> visiting = new ArrayList<>();

      List<T> hasCycle() {
        for(T injectable : injectables) {
          if(!visited.contains(injectable) && hasCycle(injectable)) {
            return visiting;
          }
        }

        return visiting;
      }

      boolean hasCycle(T injectable) {
        visiting.add(injectable);

        for(Binding binding : injectable.getBindings()) {
          Key key = binding.getRequiredKey();

          if(key != null) {
            for(T boundInjectable : resolver.resolve(key.getType(), (Object[])key.getQualifiersAsArray())) {
              if(visiting.contains(boundInjectable)) {
                return true;
              }
              else if(!visited.contains(boundInjectable) && input.contains(boundInjectable) && hasCycle(boundInjectable)) {
                return true;
              }
            }
          }
        }

        visiting.remove(injectable);
        visited.add(injectable);

        return false;
      }
    }

    List<T> cycle = new CycleDetector().hasCycle();

    if(!cycle.isEmpty()) {
      throw new CyclicDependencyException(cycle);
    }
  }

  private static boolean isNarrowerScope(Annotation scope, Annotation dependencyScope) {
    if(scope == null) {
      return false;
    }

    if(dependencyScope == null) {
      return true;
    }

    return !dependencyScope.annotationType().equals(Singleton.class) && !scope.annotationType().equals(dependencyScope.annotationType());
  }

  private class IncludingResolver implements Resolver<T> {
    final Resolver<T> base;
    final Resolver<T> include;

    IncludingResolver(Resolver<T> base, Resolver<T> include) {
      this.base = base;
      this.include = include;
    }

    @Override
    public Set<T> resolve(Type type, Object... criteria) {
      Set<T> set = new HashSet<>(base.resolve(type, criteria));

      set.addAll(include.resolve(type, criteria));

      return set;
    }
  }
}
