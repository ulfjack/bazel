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
package com.google.devtools.build.lib.util;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.collect.CompactHashSet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Encapsulates a list of lists. Is intended to be used in "batch" mode -- to set the value of a
 * GroupedList, users should first construct a {@link GroupedListHelper}, add elements to it, and
 * then {@link #append} the helper to a new GroupedList instance. The generic type T <i>must not</i>
 * be a {@link List}.
 *
 * <p>Despite the "list" name, it is an error for the same element to appear multiple times in the
 * list. Users are responsible for not trying to add the same element to a GroupedList twice.
 */
public class GroupedList<T> implements Iterable<Iterable<T>> {
  // Total number of items in the list. At least elements.size(), but might be larger if there are
  // any nested lists.
  private int size = 0;
  // Items in this GroupedList. Each element is either of type T or List<T>.
  // Non-final only for #remove.
  private List<Object> elements;

  public GroupedList() {
    // We optimize for small lists.
    this.elements = new ArrayList<>(1);
  }

  // Only for use when uncompressing a GroupedList.
  private GroupedList(int size, List<Object> elements) {
    this.size = size;
    this.elements = new ArrayList<>(elements);
  }

  /** Appends the list constructed in helper to this list. */
  public void append(GroupedListHelper<T> helper) {
    Preconditions.checkState(helper.currentGroup == null, "%s %s", this, helper);
    // Do a check to make sure we don't have lists here. Note that if helper.elements is empty,
    // Iterables.getFirst will return null, and null is not instanceof List.
    Preconditions.checkState(!(Iterables.getFirst(helper.elements, null) instanceof List),
        "Cannot make grouped list of lists: %s", helper);
    elements.addAll(helper.groupedList);
    size += helper.size();
  }

  /**
   * Removes the elements in toRemove from this list. Takes time proportional to the size of the
   * list, so should not be called often.
   */
  public void remove(Set<T> toRemove) {
    elements = remove(elements, toRemove);
    size -= toRemove.size();
  }

  /** Returns the number of elements in this list. */
  public int size() {
    return size;
  }

  /** Returns true if this list contains no elements. */
  public boolean isEmpty() {
    return elements.isEmpty();
  }

  private static final Object EMPTY_LIST = new Object();

  public Object compress() {
    switch (size()) {
      case 0:
        return EMPTY_LIST;
      case 1:
        return Iterables.getOnlyElement(elements);
      default:
        return elements.toArray();
    }
  }

  @SuppressWarnings("unchecked")
  public Set<T> toSet() {
    ImmutableSet.Builder<T> builder = ImmutableSet.builder();
    for (Object obj : elements) {
      if (obj instanceof List) {
        builder.addAll((List<T>) obj);
      } else {
        builder.add((T) obj);
      }
    }
    return builder.build();
  }

  private static int sizeOf(Object obj) {
    return obj instanceof List ? ((List<?>) obj).size() : 1;
  }

  public static <E> GroupedList<E> create(Object compressed) {
    if (compressed == EMPTY_LIST) {
      return new GroupedList<>();
    }
    if (compressed.getClass().isArray()) {
      List<Object> elements = new ArrayList<>();
      int size = 0;
      for (Object item : (Object[]) compressed) {
        size += sizeOf(item);
        elements.add(item);
      }
      return new GroupedList<>(size, elements);
    }
    // Just a single element.
    return new GroupedList<>(1, ImmutableList.<Object>of(compressed));
  }

  @Override
  public boolean equals(Object other) {
    if (other == null) {
      return false;
    }
    if (this.getClass() != other.getClass()) {
      return false;
    }
    GroupedList<?> that = (GroupedList<?>) other;
    return elements.equals(that.elements);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("elements", elements)
        .add("size", size).toString();

  }

  /**
   * Iterator that returns the next group in elements for each call to {@link #next}. A custom
   * iterator is needed here because, to optimize memory, we store single-element lists as elements
   * internally, and so they must be wrapped before they're returned.
   */
  private class GroupedIterator implements Iterator<Iterable<T>> {
    private final Iterator<Object> iter = elements.iterator();

    @Override
    public boolean hasNext() {
      return iter.hasNext();
    }

    @SuppressWarnings("unchecked") // Cast of Object to List<T> or T.
    @Override
    public Iterable<T> next() {
      Object obj = iter.next();
      if (obj instanceof List) {
        return (List<T>) obj;
      }
      return ImmutableList.of((T) obj);
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException();
    }
  }

  @Override
  public Iterator<Iterable<T>> iterator() {
    return new GroupedIterator();
  }

  /**
   * Removes everything in toRemove from the list of lists, elements. Called both by GroupedList and
   * GroupedListHelper.
   */
  private static <E> List<Object> remove(List<Object> elements, Set<E> toRemove) {
    int removedCount = 0;
    // elements.size is an upper bound of the needed size. Since normally removal happens just
    // before the list is finished and compressed, optimizing this size isn't a concern.
    List<Object> newElements = new ArrayList<>(elements.size());
    for (Object obj : elements) {
      if (obj instanceof List) {
        ImmutableList.Builder<E> newGroup = new ImmutableList.Builder<>();
        @SuppressWarnings("unchecked")
        List<E> oldGroup = (List<E>) obj;
        for (E elt : oldGroup) {
          if (toRemove.contains(elt)) {
            removedCount++;
          } else {
            newGroup.add(elt);
          }
        }
        ImmutableList<E> group = newGroup.build();
        addItem(group, newElements);
      } else {
        if (toRemove.contains(obj)) {
          removedCount++;
        } else {
          newElements.add(obj);
        }
      }
    }
    Preconditions.checkState(removedCount == toRemove.size(),
        "%s %s %s %s", removedCount, removedCount, elements, newElements);
    return newElements;
  }

  private static void addItem(Collection<?> item, List<Object> elements) {
    switch (item.size()) {
      case 0:
        return;
      case 1:
        elements.add(Preconditions.checkNotNull(Iterables.getOnlyElement(item), elements));
        return;
      default:
        elements.add(ImmutableList.copyOf(item));
    }
  }

  /**
   * Builder-like object for GroupedLists. An already-existing grouped list is appended to by
   * constructing a helper, mutating it, and then appending that helper to the grouped list.
   */
  public static class GroupedListHelper<E> implements Iterable<E> {
    // Non-final only for removal.
    private List<Object> groupedList;
    private List<E> currentGroup = null;
    private final Set<E> elements = CompactHashSet.create();

    public GroupedListHelper() {
      // Optimize for short lists.
      groupedList = new ArrayList<>(1);
    }

    /**
     * Add an element to this list. If in a group, will be added to the current group. Otherwise,
     * goes in a group of its own.
     */
    public void add(E elt) {
      Preconditions.checkState(elements.add(Preconditions.checkNotNull(elt)), "%s %s", elt, this);
      if (currentGroup == null) {
        groupedList.add(elt);
      } else {
        currentGroup.add(elt);
      }
    }

    /**
     * Remove all elements of toRemove from this list. It is a fatal error if any elements of
     * toRemove are not present. Takes time proportional to the size of the list, so should not be
     * called often.
     */
    public void remove(Set<E> toRemove) {
      groupedList = GroupedList.remove(groupedList, toRemove);
      int oldSize = size();
      elements.removeAll(toRemove);
      Preconditions.checkState(oldSize == size() + toRemove.size(),
          "%s %s %s", oldSize, toRemove, this);
    }

    /**
     * Starts a group. All elements added until {@link #endGroup} will be in the same group. Each
     * call of {@link #startGroup} must be paired with a following {@link #endGroup} call.
     */
    public void startGroup() {
      Preconditions.checkState(currentGroup == null, this);
      currentGroup = new ArrayList<>();
    }

    private void addList(Collection<E> group) {
      addItem(group, groupedList);
    }

    /** Ends a group started with {@link #startGroup}. */
    public void endGroup() {
      Preconditions.checkNotNull(currentGroup);
      addList(currentGroup);
      currentGroup = null;
    }

    /** Returns true if elt is present in the list. */
    public boolean contains(E elt) {
      return elements.contains(elt);
    }

    private int size() {
      return elements.size();
    }

    /** Returns true if list is empty. */
    public boolean isEmpty() {
      return elements.isEmpty();
    }

    @Override
    public Iterator<E> iterator() {
      return elements.iterator();
    }

    /** Create a GroupedListHelper from a collection of elements, all put in the same group.*/
    public static <F> GroupedListHelper<F> create(Collection<F> elements) {
      GroupedListHelper<F> helper = new GroupedListHelper<>();
      helper.addList(elements);
      helper.elements.addAll(elements);
      Preconditions.checkState(helper.elements.size() == elements.size(),
          "%s %s", helper, elements);
      return helper;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("groupedList", groupedList)
          .add("elements", elements)
          .add("currentGroup", currentGroup).toString();

    }
  }
}
