// Copyright 2014 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.syntax;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.events.Location;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Utilities used by the evaluator.
 */
public abstract class EvalUtils {

  /**
   * The exception that SKYLARK_COMPARATOR might throw. This is an unchecked exception
   * because Comparator doesn't let us declare exceptions. It should normally be caught
   * and wrapped in an EvalException.
   */
  public static class ComparisonException extends RuntimeException {
    public ComparisonException(String msg) {
      super(msg);
    }
  }

  /**
   * Compare two Skylark objects.
   *
   * <p> It may throw an unchecked exception ComparisonException that should be wrapped in
   * an EvalException.
   */
  public static final Comparator<Object> SKYLARK_COMPARATOR = new Comparator<Object>() {
    private int compareLists(SkylarkList o1, SkylarkList o2) {
      for (int i = 0; i < Math.min(o1.size(), o2.size()); i++) {
        int cmp = compare(o1.get(i), o2.get(i));
        if (cmp != 0) {
          return cmp;
        }
      }
      return Integer.compare(o1.size(), o2.size());
    }

    @Override
    @SuppressWarnings("unchecked")
    public int compare(Object o1, Object o2) {
      Location loc = null;
      try {
        o1 = SkylarkType.convertToSkylark(o1, loc);
        o2 = SkylarkType.convertToSkylark(o2, loc);
      } catch (EvalException e) {
        throw new ComparisonException(e.getMessage());
      }

      if (o1 instanceof SkylarkList && o2 instanceof SkylarkList) {
        return compareLists((SkylarkList) o1, (SkylarkList) o2);
      }
      try {
        return ((Comparable<Object>) o1).compareTo(o2);
      } catch (ClassCastException e) {
        throw new ComparisonException(
            "Cannot compare " + getDataTypeName(o1) + " with " + EvalUtils.getDataTypeName(o2));
      }
    }
  };

  // TODO(bazel-team): Yet an other hack committed in the name of Skylark. One problem is that the
  // syntax package cannot depend on actions so we have to have this until Actions are immutable.
  // The other is that BuildConfigurations are technically not immutable but they cannot be modified
  // from Skylark.
  private static final ImmutableSet<Class<?>> quasiImmutableClasses;
  static {
    try {
      ImmutableSet.Builder<Class<?>> builder = ImmutableSet.builder();
      builder.add(Class.forName("com.google.devtools.build.lib.actions.Action"));
      builder.add(Class.forName("com.google.devtools.build.lib.analysis.config.BuildConfiguration"));
      builder.add(Class.forName("com.google.devtools.build.lib.actions.Root"));
      quasiImmutableClasses = builder.build();
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  private EvalUtils() {
  }

  /**
   * @return true if the specified sequence is a tuple; false if it's a modifiable list.
   */
  public static boolean isTuple(List<?> l) {
    return isTuple(l.getClass());
  }

  public static boolean isTuple(Class<?> c) {
    Preconditions.checkState(List.class.isAssignableFrom(c));
    return ImmutableList.class.isAssignableFrom(c);
  }

  /**
   * @return true if the specified value is immutable (suitable for use as a
   *         dictionary key) according to the rules of the Build language.
   */
  public static boolean isImmutable(Object o) {
    if (o instanceof Map<?, ?> || o instanceof BaseFunction
        || o instanceof FilesetEntry || o instanceof GlobList<?>) {
      return false;
    } else if (o instanceof List<?>) {
      return isTuple((List<?>) o); // tuples are immutable, lists are not.
    } else {
      return true; // string/int
    }
  }

  /**
   * Returns true if the type is immutable in the skylark language.
   */
  public static boolean isSkylarkImmutable(Class<?> c) {
    if (c.isAnnotationPresent(Immutable.class)) {
      return true;
    } else if (c.equals(String.class) || c.equals(Integer.class) || c.equals(Boolean.class)
        || SkylarkList.class.isAssignableFrom(c) || ImmutableMap.class.isAssignableFrom(c)
        || NestedSet.class.isAssignableFrom(c)) {
      return true;
    } else {
      for (Class<?> classObject : quasiImmutableClasses) {
        if (classObject.isAssignableFrom(c)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Returns a transitive superclass or interface implemented by c which is annotated
   * with SkylarkModule. Returns null if no such class or interface exists.
   */
  @VisibleForTesting
  static Class<?> getParentWithSkylarkModule(Class<?> c) {
    if (c == null) {
      return null;
    }
    if (c.isAnnotationPresent(SkylarkModule.class)) {
      return c;
    }
    Class<?> parent = getParentWithSkylarkModule(c.getSuperclass());
    if (parent != null) {
      return parent;
    }
    for (Class<?> ifparent : c.getInterfaces()) {
      ifparent = getParentWithSkylarkModule(ifparent);
      if (ifparent != null) {
        return ifparent;
      }
    }
    return null;
  }

  // TODO(bazel-team): move the following few type-related functions to SkylarkType
  /**
   * Return the Skylark-type of {@code c}
   *
   * <p>The result will be a type that Skylark understands and is either equal to {@code c}
   * or is a supertype of it. For example, all instances of (all subclasses of) SkylarkList
   * are considered to be SkylarkLists.
   *
   * <p>Skylark's type validation isn't equipped to deal with inheritance so we must tell it which
   * of the superclasses or interfaces of {@code c} is the one that matters for type compatibility.
   *
   * @param c a class
   * @return a super-class of c to be used in validation-time type inference.
   */
  public static Class<?> getSkylarkType(Class<?> c) {
    if (ImmutableList.class.isAssignableFrom(c)) {
      return ImmutableList.class;
    } else if (List.class.isAssignableFrom(c)) {
      return List.class;
    } else if (SkylarkList.class.isAssignableFrom(c)) {
      return SkylarkList.class;
    } else if (Map.class.isAssignableFrom(c)) {
      return Map.class;
    } else if (NestedSet.class.isAssignableFrom(c)) {
      // This could be removed probably
      return NestedSet.class;
    } else if (Set.class.isAssignableFrom(c)) {
      return Set.class;
    } else {
      // TODO(bazel-team): also unify all implementations of ClassObject,
      // that we used to all print the same as "struct"?
      //
      // Check if one of the superclasses or implemented interfaces has the SkylarkModule
      // annotation. If yes return that class.
      Class<?> parent = getParentWithSkylarkModule(c);
      if (parent != null) {
        return parent;
      }
    }
    return c;
  }

  /**
   * Returns a pretty name for the datatype of object 'o' in the Build language.
   */
  public static String getDataTypeName(Object o) {
    return getDataTypeName(o, false);
  }

  /**
   * Returns a pretty name for the datatype of object {@code object} in Skylark
   * or the BUILD language, with full details if the {@code full} boolean is true.
   */
  public static String getDataTypeName(Object object, boolean full) {
    Preconditions.checkNotNull(object);
    if (object instanceof SkylarkList) {
      SkylarkList list = (SkylarkList) object;
      if (list.isTuple()) {
        return "tuple";
      } else {
        return "list" + (full ? " of " + list.getContentType() + "s" : "");
      }
    } else if (object instanceof SkylarkNestedSet) {
      SkylarkNestedSet set = (SkylarkNestedSet) object;
      return "set" + (full ? " of " + set.getContentType() + "s" : "");
    } else {
      return getDataTypeNameFromClass(object.getClass());
    }
  }

  /**
   * Returns a pretty name for the datatype equivalent of class 'c' in the Build language.
   */
  public static String getDataTypeNameFromClass(Class<?> c) {
    if (c.equals(Object.class)) {
      return "unknown";
    } else if (c.equals(String.class)) {
      return "string";
    } else if (c.equals(Integer.class)) {
      return "int";
    } else if (c.equals(Boolean.class)) {
      return "bool";
    } else if (c.equals(Void.TYPE) || c.equals(Environment.NoneType.class)) {
      return "NoneType";
    } else if (List.class.isAssignableFrom(c)) {
      // NB: the capital here is a subtle way to distinguish java Tuple and java List
      // from native SkylarkList tuple and list.
      // TODO(bazel-team): refactor SkylarkList and use it everywhere.
      return isTuple(c) ? "Tuple" : "List";
    } else if (GlobList.class.isAssignableFrom(c)) {
      return "glob list";
    } else if (Map.class.isAssignableFrom(c)) {
      return "dict";
    } else if (BaseFunction.class.isAssignableFrom(c)) {
      return "function";
    } else if (c.equals(FilesetEntry.class)) {
      return "FilesetEntry";
    } else if (c.equals(SelectorValue.class)) {
      return "select";
    } else if (NestedSet.class.isAssignableFrom(c) || SkylarkNestedSet.class.isAssignableFrom(c)) {
      return "set";
    } else if (ClassObject.SkylarkClassObject.class.isAssignableFrom(c)) {
      return "struct";
    } else if (SkylarkList.class.isAssignableFrom(c)) {
      // TODO(bazel-team): Refactor the class hierarchy so we can distinguish list and tuple types.
      return "list";
    } else if (c.isAnnotationPresent(SkylarkModule.class)) {
      SkylarkModule module = c.getAnnotation(SkylarkModule.class);
      return c.getAnnotation(SkylarkModule.class).name()
          + (module.namespace() ? " (a language module)" : "");
    } else {
      if (c.getSimpleName().isEmpty()) {
        return c.getName();
      } else {
        return c.getSimpleName();
      }
    }
  }

  /**
   * Returns a sequence of the appropriate list/tuple datatype for 'seq', based on 'isTuple'.
   */
  public static List<?> makeSequence(List<?> seq, boolean isTuple) {
    return isTuple ? ImmutableList.copyOf(seq) : seq;
  }

  public static Object checkNotNull(Expression expr, Object obj) throws EvalException {
    if (obj == null) {
      throw new EvalException(expr.getLocation(),
          "Unexpected null value, please send a bug report. "
          + "This was generated by '" + expr + "'");
    }
    return obj;
  }

  /**
   * @return the truth value of an object, according to Python rules.
   * http://docs.python.org/2/library/stdtypes.html#truth-value-testing
   */
  public static boolean toBoolean(Object o) {
    if (o == null || o == Environment.NONE) {
      return false;
    } else if (o instanceof Boolean) {
      return (Boolean) o;
    } else if (o instanceof String) {
      return !((String) o).isEmpty();
    } else if (o instanceof Integer) {
      return (Integer) o != 0;
    } else if (o instanceof Collection<?>) {
      return !((Collection<?>) o).isEmpty();
    } else if (o instanceof Map<?, ?>) {
      return !((Map<?, ?>) o).isEmpty();
    } else if (o instanceof NestedSet<?>) {
      return !((NestedSet<?>) o).isEmpty();
    } else if (o instanceof SkylarkNestedSet) {
      return !((SkylarkNestedSet) o).isEmpty();
    } else if (o instanceof Iterable<?>) {
      return !(Iterables.isEmpty((Iterable<?>) o));
    } else {
      return true;
    }
  }

  @SuppressWarnings("unchecked")
  public static Collection<?> toCollection(Object o, Location loc) throws EvalException {
    if (o instanceof Collection) {
      return (Collection<Object>) o;
    } else if (o instanceof SkylarkList) {
      return ((SkylarkList) o).toList();
    } else if (o instanceof Map<?, ?>) {
      Map<Comparable<?>, Object> dict = (Map<Comparable<?>, Object>) o;
      // For dictionaries we iterate through the keys only
      // For determinism, we sort the keys.
      try {
        return Ordering.from(SKYLARK_COMPARATOR).sortedCopy(dict.keySet());
      } catch (ComparisonException e) {
        throw new EvalException(loc, e);
      }
    } else if (o instanceof SkylarkNestedSet) {
      return ((SkylarkNestedSet) o).toCollection();
    } else {
      throw new EvalException(loc,
          "type '" + getDataTypeName(o) + "' is not a collection");
    }
  }

  @SuppressWarnings("unchecked")
  public static Iterable<?> toIterable(Object o, Location loc) throws EvalException {
    if (o instanceof String) {
      // This is not as efficient as special casing String in for and dict and list comprehension
      // statements. However this is a more unified way.
      // The regex matches every character in the string until the end of the string,
      // so "abc" will be split into ["a", "b", "c"].
      return ImmutableList.<Object>copyOf(((String) o).split("(?!^)"));
    } else if (o instanceof Iterable) {
      return (Iterable<Object>) o;
    } else if (o instanceof Map<?, ?>) {
      return toCollection(o, loc);
    } else {
      throw new EvalException(loc,
          "type '" + getDataTypeName(o) + "' is not iterable");
    }
  }

  /**
   * @return the size of the Skylark object or -1 in case the object doesn't have a size.
   */
  public static int size(Object arg) {
    if (arg instanceof String) {
      return ((String) arg).length();
    } else if (arg instanceof Map) {
      return ((Map<?, ?>) arg).size();
    } else if (arg instanceof SkylarkList) {
      return ((SkylarkList) arg).size();
    } else if (arg instanceof Iterable) {
      // Iterables.size() checks if arg is a Collection so it's efficient in that sense.
      return Iterables.size((Iterable<?>) arg);
    }
    return -1;
  }

  /** @return true if x is Java null or Skylark None */
  public static boolean isNullOrNone(Object x) {
    return x == null || x == Environment.NONE;
  }

  /**
   * Build a map of kwarg arguments from a list, removing null-s or None-s.
   *
   * @param init a series of key, value pairs (as consecutive arguments)
   *   as in {@code optionMap(k1, v1, k2, v2, k3, v3, map)}
   *   where each key is a String, each value is an arbitrary Objet.
   * @return a {@code Map<String, Object>} that has all the specified entries,
   *   where key, value pairs appearing earlier have precedence,
   *   i.e. {@code k1, v1} may override {@code k3, v3}.
   *
   * Ignore any entry where the value is null or None.
   * Keys cannot be null.
   */
  @SuppressWarnings("unchecked")
  public static ImmutableMap<String, Object> optionMap(Object... init) {
    ImmutableMap.Builder<String, Object> b = new ImmutableMap.Builder<>();
    Preconditions.checkState(init.length % 2 == 0);
    for (int i = init.length - 2; i >= 0; i -= 2) {
      String key = (String) Preconditions.checkNotNull(init[i]);
      Object value = init[i + 1];
      if (!isNullOrNone(value)) {
        b.put(key, value);
      }
    }
    return b.build();
  }
}
