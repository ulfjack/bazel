// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.pkgcache.LoadingResult;
import com.google.devtools.build.lib.pkgcache.TestFilter;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Objects;

import javax.annotation.Nullable;

/**
 * A value referring to a computed set of resolved targets. This is used for the results of target
 * pattern parsing.
 */
@Immutable
@ThreadSafe
@VisibleForTesting
public final class TargetPatternPhaseValue implements SkyValue {

  private final ImmutableSet<Target> targets;
  @Nullable private final ImmutableSet<Target> testsToRun;
  private final boolean hasError;
  private final boolean hasPostExpansionError;

  private final ImmutableSet<Target> filteredTargets;
  private final ImmutableSet<Target> testFilteredTargets;

  TargetPatternPhaseValue(ImmutableSet<Target> targets, @Nullable ImmutableSet<Target> testsToRun,
      boolean hasError, boolean hasPostExpansionError, ImmutableSet<Target> filteredTargets,
      ImmutableSet<Target> testFilteredTargets) {
    this.targets = Preconditions.checkNotNull(targets);
    this.testsToRun = testsToRun;
    this.hasError = hasError;
    this.hasPostExpansionError = hasPostExpansionError;
    this.filteredTargets = Preconditions.checkNotNull(filteredTargets);
    this.testFilteredTargets = Preconditions.checkNotNull(testFilteredTargets);
  }

  public ImmutableSet<Target> getTargets() {
    return targets;
  }

  @Nullable
  public ImmutableSet<Target> getTestsToRun() {
    return testsToRun;
  }

  public boolean hasError() {
    return hasError;
  }

  public boolean hasPostExpansionError() {
    return hasPostExpansionError;
  }

  public ImmutableSet<Target> getFilteredTargets() {
    return filteredTargets;
  }

  public ImmutableSet<Target> getTestFilteredTargets() {
    return testFilteredTargets;
  }

  public LoadingResult toLoadingResult() {
    return new LoadingResult(hasError(), hasPostExpansionError(), getTargets(), getTestsToRun(),
        ImmutableMap.<PackageIdentifier, Path>of());
  }

  @SuppressWarnings("unused")
  private void writeObject(ObjectOutputStream out) {
    throw new UnsupportedOperationException();
  }

  @SuppressWarnings("unused")
  private void readObject(ObjectInputStream in) {
    throw new UnsupportedOperationException();
  }

  @SuppressWarnings("unused")
  private void readObjectNoData() {
    throw new IllegalStateException();
  }

  /** Create a target pattern phase value key. */
  @ThreadSafe
  public static SkyKey key(ImmutableList<String> targetPatterns, String offset,
      boolean compileOneDependency, boolean buildTestsOnly, boolean determineTests,
      TestFilter testFilter) {
    return new SkyKey(SkyFunctions.TARGET_PATTERN_PHASE, new TargetPatternList(
        targetPatterns, offset, compileOneDependency, buildTestsOnly, determineTests, testFilter));
  }

  /**
   * A TargetPattern is a tuple of pattern (eg, "foo/..."), filtering policy, a relative pattern
   * offset, and whether it is a positive or negative match.
   */
  @ThreadSafe
  static final class TargetPatternList implements Serializable {
    private final ImmutableList<String> targetPatterns;
    private final String offset;
    private final boolean compileOneDependency;
    private final boolean buildTestsOnly;
    private final boolean determineTests;
    private final TestFilter testFilter;

    public TargetPatternList(ImmutableList<String> targetPatterns, String offset,
        boolean compileOneDependency, boolean buildTestsOnly, boolean determineTests,
        TestFilter testFilter) {
      this.targetPatterns = targetPatterns;
      this.offset = offset;
      this.compileOneDependency = compileOneDependency;
      this.buildTestsOnly = buildTestsOnly;
      this.determineTests = determineTests;
      this.testFilter = testFilter;
    }

    public ImmutableList<String> getTargetPatterns() {
      return targetPatterns;
    }

    public String getOffset() {
      return offset;
    }

    public boolean getCompileOneDependency() {
      return compileOneDependency;
    }

    public boolean getBuildTestsOnly() {
      return buildTestsOnly;
    }

    public boolean getDetermineTests() {
      return determineTests;
    }

    public TestFilter getTestFilter() {
      return testFilter;
    }

    @Override
    public String toString() {
      return targetPatterns.toString();
    }

    @Override
    public int hashCode() {
      return Objects.hash(targetPatterns, compileOneDependency, buildTestsOnly, determineTests,
          testFilter);
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (!(obj instanceof TargetPatternList)) {
        return false;
      }
      TargetPatternList other = (TargetPatternList) obj;
      return other.targetPatterns.equals(this.targetPatterns)
          && other.compileOneDependency == compileOneDependency
          && other.buildTestsOnly == buildTestsOnly
          && other.determineTests == determineTests
          && other.testFilter.equals(testFilter);
    }
  }
}
