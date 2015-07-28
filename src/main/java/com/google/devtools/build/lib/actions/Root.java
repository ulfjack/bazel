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

package com.google.devtools.build.lib.actions;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.syntax.SkylarkCallable;
import com.google.devtools.build.lib.syntax.SkylarkModule;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;

import java.io.Serializable;
import java.util.Objects;

import javax.annotation.Nullable;

/**
 * A root for an artifact. The roots are the directories containing artifacts, and they are mapped
 * together into a single directory tree to form the execution environment. There are two kinds of
 * roots, source roots and derived roots. Source roots correspond to entries of the package path,
 * and they can be anywhere on disk. Derived roots correspond to output directories; there are
 * generally different output directories for different configurations, and different types of
 * output (bin, genfiles, includes, etc.).
 *
 * <p>When mapping the roots into a single directory tree, the source roots are merged, such that
 * each package is accessed in its entirety from a single source root. The package cache is
 * responsible for determining that mapping. The derived roots, on the other hand, have to be
 * distinct. (It is currently allowed to have a derived root that is the prefix of another one.)
 *
 * <p>The derived roots must have paths that point inside the exec root, i.e. below the directory
 * that is the root of the merged directory tree.
 */
@SkylarkModule(name = "root",
    doc = "A root for files. The roots are the directories containing files, and they are mapped "
        + "together into a single directory tree to form the execution environment.")
public final class Root implements Comparable<Root>, Serializable {

  /**
   * Returns the given path as a source root. The path may not be {@code null}.
   */
  public static Root asSourceRoot(Path path) {
    return new Root(null, path);
  }

  /**
   * DO NOT USE IN PRODUCTION CODE!
   *
   * <p>Returns the given path as a derived root. This method only exists as a convenience for
   * tests, which don't need a proper Root object.
   */
  @VisibleForTesting
  public static Root asDerivedRoot(Path path) {
    return new Root(path, path);
  }

  /**
   * Returns the given path as a derived root, relative to the given exec root. The root must be a
   * proper sub-directory of the exec root (i.e. not equal). Neither may be {@code null}.
   *
   * <p>Be careful with this method - all derived roots must be registered with the artifact factory
   * before the analysis phase.
   */
  public static Root asDerivedRoot(Path execRoot, Path root) {
    Preconditions.checkArgument(root.startsWith(execRoot));
    Preconditions.checkArgument(!root.equals(execRoot));
    return new Root(execRoot, root);
  }

  public static Root middlemanRoot(Path execRoot, Path outputDir) {
    Path root = outputDir.getRelative("internal");
    Preconditions.checkArgument(root.startsWith(execRoot));
    Preconditions.checkArgument(!root.equals(execRoot));
    return new Root(execRoot, root, true);
  }

  /**
   * Returns the exec root as a derived root. The exec root should never be treated as a derived
   * root, but this is currently allowed. Do not add any further uses besides the ones that already
   * exist!
   */
  static Root execRootAsDerivedRoot(Path execRoot) {
    return new Root(execRoot, execRoot);
  }

  @Nullable private final Path execRoot;
  private final Path path;
  private final boolean isMiddlemanRoot;
  private final PathFragment execPath;

  private Root(@Nullable Path execRoot, Path path, boolean isMiddlemanRoot) {
    this.execRoot = execRoot;
    this.path = Preconditions.checkNotNull(path);
    this.isMiddlemanRoot = isMiddlemanRoot;
    this.execPath = isSourceRoot() ? PathFragment.EMPTY_FRAGMENT : path.relativeTo(execRoot);
  }

  private Root(@Nullable Path execRoot, Path path) {
    this(execRoot, path, false);
  }

  public Path getPath() {
    return path;
  }

  /**
   * Returns the path fragment from the exec root to the actual root. For source roots, this returns
   * the empty fragment.
   */
  public PathFragment getExecPath() {
    return execPath;
  }

  @SkylarkCallable(name = "path", structField = true,
      doc = "Returns the relative path from the exec root to the actual root.")
  public String getExecPathString() {
    return getExecPath().getPathString();
  }

  public boolean isSourceRoot() {
    return execRoot == null;
  }

  public boolean isMiddlemanRoot() {
    return isMiddlemanRoot;
  }

  @Override
  public int compareTo(Root o) {
    return path.compareTo(o.path);
  }

  @Override
  public int hashCode() {
    return Objects.hash(execRoot, path.hashCode());
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (!(o instanceof Root)) {
      return false;
    }
    Root r = (Root) o;
    return path.equals(r.path) && Objects.equals(execRoot, r.execRoot);
  }

  @Override
  public String toString() {
    return path + (isSourceRoot() ? "[source]" : "[derived]");
  }
}
