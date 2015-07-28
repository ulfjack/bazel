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
package com.google.devtools.build.skyframe;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.util.GroupedList;
import com.google.devtools.build.lib.util.GroupedList.GroupedListHelper;
import com.google.devtools.build.lib.util.Pair;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * In-memory implementation of {@link NodeEntry}. All operations on this class are thread-safe.
 *
 * <p>Care was taken to provide certain compound operations to avoid certain check-then-act races.
 * That means this class is somewhat closely tied to the exact Evaluator implementation.
 *
 * <p>Consider the example with two threads working on two nodes, where one depends on the other,
 * say b depends on a. If a completes first, it's done. If it completes second, it needs to signal
 * b, and potentially re-schedule it. If b completes first, it must exit, because it will be
 * signaled (and re-scheduled) by a. If it completes second, it must signal (and re-schedule)
 * itself. However, if the Evaluator supported re-entrancy for a node, then this wouldn't have to
 * be so strict, because duplicate scheduling would be less problematic.
 *
 * <p>The transient state of an {@code InMemoryNodeEntry} is kept in a {@link BuildingState} object.
 * Many of the methods of {@code InMemoryNodeEntry} are just wrappers around the corresponding
 * {@link BuildingState} methods.
 *
 * <p>This class is public only for the benefit of alternative graph implementations outside of the
 * package.
 */
public class InMemoryNodeEntry implements NodeEntry {

  /** Actual data stored in this entry when it is done. */
  private SkyValue value = null;

  /**
   * The last version of the graph at which this node entry was changed. In {@link #setValue} it
   * may be determined that the data being written to the graph at a given version is the same as
   * the already-stored data. In that case, the version will remain the same. The version can be
   * thought of as the latest timestamp at which this entry was changed.
   */
  private Version version = MinimalVersion.INSTANCE;

  /**
   * This object represents a {@link GroupedList}<SkyKey> in a memory-efficient way. It stores the
   * direct dependencies of this node, in groups if the {@code SkyFunction} requested them that way.
   */
  private Object directDeps = null;

  /**
   * This list stores the reverse dependencies of this node that have been declared so far.
   *
   * <p>In case of a single object we store the object unwrapped, without the list, for
   * memory-efficiency.
   */
  @VisibleForTesting
  protected Object reverseDeps = ImmutableList.of();

  /**
   * We take advantage of memory alignment to avoid doing a nasty {@code instanceof} for knowing
   * if {@code reverseDeps} is a single object or a list.
   */
  protected boolean reverseDepIsSingleObject = false;

  /**
   * During the invalidation we keep the reverse deps to be removed in this list instead of directly
   * removing them from {@code reverseDeps}. That is because removals from reverseDeps are O(N).
   * Originally reverseDeps was a HashSet, but because of memory consumption we switched to a list.
   *
   * <p>This requires that any usage of reverseDeps (contains, add, the list of reverse deps) call
   * {@code consolidateReverseDepsRemovals} first. While this operation is not free, it can be done
   * more effectively than trying to remove each dirty reverse dependency individually (O(N) each
   * time).
   */
  private List<SkyKey> reverseDepsToRemove = null;

  private static final ReverseDepsUtil<InMemoryNodeEntry> REVERSE_DEPS_UTIL =
      new ReverseDepsUtil<InMemoryNodeEntry>() {
    @Override
    void setReverseDepsObject(InMemoryNodeEntry container, Object object) {
      container.reverseDeps = object;
    }

    @Override
    void setSingleReverseDep(InMemoryNodeEntry container, boolean singleObject) {
      container.reverseDepIsSingleObject = singleObject;
    }

    @Override
    void setReverseDepsToRemove(InMemoryNodeEntry container, List<SkyKey> object) {
      container.reverseDepsToRemove = object;
    }

    @Override
    Object getReverseDepsObject(InMemoryNodeEntry container) {
      return container.reverseDeps;
    }

    @Override
    boolean isSingleReverseDep(InMemoryNodeEntry container) {
      return container.reverseDepIsSingleObject;
    }

    @Override
    List<SkyKey> getReverseDepsToRemove(InMemoryNodeEntry container) {
      return container.reverseDepsToRemove;
    }
  };

  /**
   * The transient state of this entry, after it has been created but before it is done. It allows
   * us to keep the current state of the entry across invalidation and successive evaluations.
   */
  @VisibleForTesting
  protected BuildingState buildingState = new BuildingState();

  /**
   * Construct a InMemoryNodeEntry. Use ONLY in Skyframe evaluation and graph implementations.
   */
  public InMemoryNodeEntry() {
  }

  @Override
  public boolean keepEdges() {
    return true;
  }

  @Override
  public synchronized boolean isDone() {
    return buildingState == null;
  }

  @Override
  public synchronized SkyValue getValue() {
    Preconditions.checkState(isDone(), "no value until done. ValueEntry: %s", this);
    return ValueWithMetadata.justValue(value);
  }

  @Override
  public synchronized ValueWithMetadata getValueWithMetadata() {
    Preconditions.checkState(isDone(), "no value until done: %s", this);
    return ValueWithMetadata.wrapWithMetadata(value);
  }

  @Override
  public synchronized SkyValue toValue() {
    if (isDone()) {
      return getErrorInfo() == null ? getValue() : null;
    } else if (isChanged() || isDirty()) {
      return (buildingState.getLastBuildValue() == null)
              ? null
          : ValueWithMetadata.justValue(buildingState.getLastBuildValue());
    }
    throw new AssertionError("Value in bad state: " + this);
  }

  @Override
  public synchronized Iterable<SkyKey> getDirectDeps() {
    assertKeepEdges();
    Preconditions.checkState(isDone(), "no deps until done. ValueEntry: %s", this);
    return GroupedList.<SkyKey>create(directDeps).toSet();
  }

  /**
   * If {@code isDone()}, returns the ordered list of sets of grouped direct dependencies that were
   * added in {@link addTemporaryDirectDeps}.
   */
  public synchronized Iterable<Iterable<SkyKey>> getGroupedDirectDeps() {
    assertKeepEdges();
    Preconditions.checkState(isDone(), "no deps until done. ValueEntry: %s", this);
    return GroupedList.<SkyKey>create(directDeps);
  }

  @Override
  @Nullable
  public synchronized ErrorInfo getErrorInfo() {
    Preconditions.checkState(isDone(), "no errors until done. ValueEntry: %s", this);
    return ValueWithMetadata.getMaybeErrorInfo(value);
  }

  private synchronized Set<SkyKey> setStateFinishedAndReturnReverseDeps() {
    // Get reverse deps that need to be signaled.
    ImmutableSet<SkyKey> reverseDepsToSignal = buildingState.getReverseDepsToSignal();
    REVERSE_DEPS_UTIL.consolidateReverseDepsRemovals(this);
    REVERSE_DEPS_UTIL.addReverseDeps(this, reverseDepsToSignal);
    this.directDeps = buildingState.getFinishedDirectDeps().compress();

    // Set state of entry to done.
    buildingState = null;

    if (!keepEdges()) {
      this.directDeps = null;
      this.reverseDeps = null;
    }
    return reverseDepsToSignal;
  }

  @Override
  public synchronized Set<SkyKey> getInProgressReverseDeps() {
    Preconditions.checkState(!isDone(), this);
    return buildingState.getReverseDepsToSignal();
  }

  @Override
  public synchronized Set<SkyKey> setValue(SkyValue value, Version version) {
    Preconditions.checkState(isReady(), "%s %s", this, value);
    // This check may need to be removed when we move to a non-linear versioning sequence.
    Preconditions.checkState(this.version.atMost(version),
        "%s %s %s", this, version, value);

    if (isDirty() && buildingState.unchangedFromLastBuild(value)) {
      // If the value is the same as before, just use the old value. Note that we don't use the new
      // value, because preserving == equality is even better than .equals() equality.
      this.value = buildingState.getLastBuildValue();
    } else {
      // If this is a new value, or it has changed since the last build, set the version to the
      // current graph version.
      this.version = version;
      this.value = value;
    }

    return setStateFinishedAndReturnReverseDeps();
  }

  @Override
  public synchronized DependencyState addReverseDepAndCheckIfDone(SkyKey reverseDep) {
    if (reverseDep != null) {
      if (keepEdges()) {
        REVERSE_DEPS_UTIL.consolidateReverseDepsRemovals(this);
        REVERSE_DEPS_UTIL.maybeCheckReverseDepNotPresent(this, reverseDep);
      }
      if (isDone()) {
        if (keepEdges()) {
          REVERSE_DEPS_UTIL.addReverseDeps(this, ImmutableList.of(reverseDep));
        }
      } else {
        // Parent should never register itself twice in the same build.
        buildingState.addReverseDepToSignal(reverseDep);
      }
    }
    if (isDone()) {
      return DependencyState.DONE;
    }
    return buildingState.startEvaluating() ? DependencyState.NEEDS_SCHEDULING
                                           : DependencyState.ADDED_DEP;
  }

  @Override
  public synchronized void removeReverseDep(SkyKey reverseDep) {
    if (!keepEdges()) {
      return;
    }
    REVERSE_DEPS_UTIL.removeReverseDep(this, reverseDep);
    if (!isDone()) {
      // This is currently unnecessary -- the only time we remove a reverse dep that was added this
      // build is during the clean following a build failure. In that case, this node that is not
      // done will be deleted soon, so clearing the reverse dep is not required.
      buildingState.removeReverseDepToSignal(reverseDep);
    }
  }

  @Override
  public synchronized Iterable<SkyKey> getReverseDeps() {
    assertKeepEdges();
    Preconditions.checkState(isDone() || buildingState.getReverseDepsToSignal().isEmpty(),
        "Reverse deps should only be queried before the build has begun "
            + "or after the node is done %s", this);
    return REVERSE_DEPS_UTIL.getReverseDeps(this);
  }

  @Override
  public synchronized boolean signalDep() {
    return signalDep(/*childVersion=*/new IntVersion(Long.MAX_VALUE));
  }

  @Override
  public synchronized boolean signalDep(Version childVersion) {
    Preconditions.checkState(!isDone(), "Value must not be done in signalDep %s", this);
    return buildingState.signalDep(/*childChanged=*/!childVersion.atMost(getVersion()));
  }

  @Override
  public synchronized boolean isDirty() {
    return !isDone() && buildingState.isDirty();
  }

  @Override
  public synchronized boolean isChanged() {
    return !isDone() && buildingState.isChanged();
  }

  /** Checks that a caller is not trying to access not-stored graph edges. */
  private void assertKeepEdges() {
    Preconditions.checkState(keepEdges(), "Graph edges not stored. %s", this);
  }

  @Override
  @Nullable
  public synchronized Pair<? extends Iterable<SkyKey>, ? extends SkyValue> markDirty(
      boolean isChanged) {
    assertKeepEdges();
    if (isDone()) {
      GroupedList<SkyKey> lastDirectDeps = GroupedList.create(directDeps);
      buildingState = BuildingState.newDirtyState(isChanged, lastDirectDeps, value);
      Pair<? extends Iterable<SkyKey>, ? extends SkyValue> result =
          Pair.of(lastDirectDeps.toSet(), value);
      value = null;
      directDeps = null;
      return result;
    }
    // The caller may be simultaneously trying to mark this node dirty and changed, and the dirty
    // thread may have lost the race, but it is the caller's responsibility not to try to mark
    // this node changed twice. The end result of racing markers must be a changed node, since one
    // of the markers is trying to mark the node changed.
    Preconditions.checkState(isChanged != isChanged(),
        "Cannot mark node dirty twice or changed twice: %s", this);
    Preconditions.checkState(value == null, "Value should have been reset already %s", this);
    Preconditions.checkState(directDeps == null, "direct deps not already reset %s", this);
    if (isChanged) {
      // If the changed marker lost the race, we just need to mark changed in this method -- all
      // other work was done by the dirty marker.
      buildingState.markChanged();
    }
    return null;
  }

  @Override
  public synchronized Set<SkyKey> markClean() {
    this.value = buildingState.getLastBuildValue();
    // This checks both the value and the direct deps, but since we're passing in the same value,
    // the value check should be trivial.
    Preconditions.checkState(buildingState.unchangedFromLastBuild(this.value),
        "Direct deps must be the same as those found last build for node to be marked clean: %s",
        this);
    Preconditions.checkState(isDirty(), this);
    Preconditions.checkState(!buildingState.isChanged(), "shouldn't be changed: %s", this);
    return setStateFinishedAndReturnReverseDeps();
  }

  @Override
  public synchronized void forceRebuild() {
    buildingState.forceChanged();
  }

  @Override
  public synchronized Version getVersion() {
    return version;
  }

  /**  @see BuildingState#getDirtyState() */
  @Override
  public synchronized NodeEntry.DirtyState getDirtyState() {
    return buildingState.getDirtyState();
  }

  /**  @see BuildingState#getNextDirtyDirectDeps() */
  @Override
  public synchronized Collection<SkyKey> getNextDirtyDirectDeps() {
    return buildingState.getNextDirtyDirectDeps();
  }

  @Override
  public synchronized Set<SkyKey> getTemporaryDirectDeps() {
    Preconditions.checkState(!isDone(), "temporary shouldn't be done: %s", this);
    return buildingState.getDirectDepsForBuild();
  }

  @Override
  public synchronized boolean noDepsLastBuild() {
    return buildingState.noDepsLastBuild();
  }

  @Override
  public synchronized void removeUnfinishedDeps(Set<SkyKey> unfinishedDeps) {
    buildingState.removeDirectDeps(unfinishedDeps);
  }

  @Override
  public synchronized void addTemporaryDirectDeps(GroupedListHelper<SkyKey> helper) {
    Preconditions.checkState(!isDone(), "add temp shouldn't be done: %s %s", helper, this);
    buildingState.addDirectDeps(helper);
  }

  @Override
  public synchronized boolean isReady() {
    Preconditions.checkState(!isDone(), "can't be ready if done: %s", this);
    return buildingState.isReady();
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("value", value)
        .add("version", version)
        .add("directDeps", directDeps == null ? null : GroupedList.create(directDeps))
        .add("reverseDeps", REVERSE_DEPS_UTIL.toString(this))
        .add("buildingState", buildingState).toString();
  }

  /**
   * Do not use except in custom evaluator implementations! Added only temporarily.
   *
   * <p>Clones a InMemoryMutableNodeEntry iff it is a done node. Otherwise it fails.
   */
  public synchronized InMemoryNodeEntry cloneNodeEntry() {
    // As this is temporary, for now lets limit to done nodes
    Preconditions.checkState(isDone(), "Only done nodes can be copied");
    InMemoryNodeEntry nodeEntry = new InMemoryNodeEntry();
    nodeEntry.value = value;
    nodeEntry.version = this.version;
    REVERSE_DEPS_UTIL.addReverseDeps(nodeEntry, REVERSE_DEPS_UTIL.getReverseDeps(this));
    nodeEntry.directDeps = directDeps;
    nodeEntry.buildingState = null;
    return nodeEntry;
  }
}
