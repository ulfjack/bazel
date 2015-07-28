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
package com.google.devtools.build.lib.skyframe;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.PackageIdentifier;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.skyframe.RecursivePkgValue.RecursivePkgKey;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.Dirent.Type;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RecursiveDirectoryTraversalFunction traverses the subdirectories of a directory, looking for
 * and loading packages, and builds up a value from these packages in a manner customized by
 * classes that derive from it.
 */
abstract class RecursiveDirectoryTraversalFunction
    <TVisitor extends RecursiveDirectoryTraversalFunction.Visitor, TReturn> {

  /**
   * Returned from {@link #visitDirectory} if its {@code recursivePkgKey} is a symlink or not a
   * directory, or if a dependency value lookup returns an error.
   */
  protected abstract TReturn getEmptyReturn();

  /**
   * Called by {@link #visitDirectory}, which will next call {@link Visitor#visitPackageValue} if
   * the {@code recursivePkgKey} specifies a directory with a package, and which will lastly be
   * provided to {@link #aggregateWithSubdirectorySkyValues} to compute the {@code TReturn} value
   * returned by {@link #visitDirectory}.
   */
  protected abstract TVisitor getInitialVisitor();

  /**
   * Called by {@link #visitDirectory} to get the {@link SkyKey}s associated with recursive
   * computation in subdirectories of {@param subdirectory}, excluding directories in
   * {@param excludedSubdirectoriesBeneathSubdirectory}, all of which must be proper subdirectories
   * of {@param subdirectory}.
   */
  protected abstract SkyKey getSkyKeyForSubdirectory(RootedPath subdirectory,
      ImmutableSet<PathFragment> excludedSubdirectoriesBeneathSubdirectory);

  /**
   * Called by {@link #visitDirectory} to compute the {@code TReturn} value it returns, as a
   * function of {@param visitor} and the {@link SkyValue}s computed for subdirectories
   * of the directory specified by {@code recursivePkgKey}, contained in
   * {@param subdirectorySkyValues}.
   */
  protected abstract TReturn aggregateWithSubdirectorySkyValues(
      TVisitor visitor, Map<SkyKey, SkyValue> subdirectorySkyValues);

  /**
   * A type of value used by {@link #visitDirectory} as it checks for a package in the directory
   * specified by {@code recursivePkgKey}; if such a package exists, {@link #visitPackageValue}
   * is called.
   *
   * <p>The value is then provided to {@link #aggregateWithSubdirectorySkyValues} to compute the
   * value returned by {@link #visitDirectory}.
   */
  interface Visitor {

    /**
     * Called iff the directory contains a package. Provides an {@link Environment} {@param env}
     * so that the visitor may do additional lookups. {@link Environment#valuesMissing} will be
     * checked afterwards.
     */
    void visitPackageValue(Package pkg, Environment env);
  }

  /**
   * Looks in the directory specified by {@param recursivePkgKey} for a package, does some work
   * as specified by {@link Visitor} if such a package exists, then recursively does work in each
   * non-excluded subdirectory as specified by {@link #getSkyKeyForSubdirectory}, and finally
   * aggregates the {@link Visitor} value along with values from each subdirectory as specified
   * by {@link #aggregateWithSubdirectorySkyValues}, and returns that aggregation.
   *
   * <p>Returns null if {@code env.valuesMissing()} is true, checked after each call to one of
   * {@link RecursiveDirectoryTraversalFunction}'s abstract methods except for {@link
   * #getEmptyReturn}. (And after each of {@code visitDirectory}'s own uses of {@param env}, of
   * course.)
   */
  TReturn visitDirectory(RecursivePkgKey recursivePkgKey, Environment env) {
    RootedPath rootedPath = recursivePkgKey.getRootedPath();
    Set<PathFragment> excludedPaths = recursivePkgKey.getExcludedPaths();
    Path root = rootedPath.getRoot();
    PathFragment rootRelativePath = rootedPath.getRelativePath();

    SkyKey fileKey = FileValue.key(rootedPath);
    FileValue fileValue;
    try {
      fileValue = (FileValue) env.getValueOrThrow(fileKey, InconsistentFilesystemException.class,
          FileSymlinkCycleException.class, IOException.class);
    } catch (InconsistentFilesystemException | FileSymlinkCycleException | IOException e) {
      return reportErrorAndReturn("Failed to get information about path", e, rootRelativePath,
          env.getListener());
    }
    if (fileValue == null) {
      return null;
    }

    if (!fileValue.isDirectory()) {
      return getEmptyReturn();
    }

    if (fileValue.isSymlink()) {
      // We do not follow directory symlinks. It prevents symlink loops.
      return getEmptyReturn();
    }

    PackageIdentifier packageId =
        PackageIdentifier.createInDefaultRepo(rootRelativePath.getPathString());
    PackageLookupValue pkgLookupValue;
    try {
      pkgLookupValue = (PackageLookupValue) env.getValueOrThrow(PackageLookupValue.key(packageId),
          NoSuchPackageException.class, InconsistentFilesystemException.class);
    } catch (NoSuchPackageException | InconsistentFilesystemException e) {
      return reportErrorAndReturn("Failed to load package", e, rootRelativePath,
          env.getListener());
    }
    if (pkgLookupValue == null) {
      return null;
    }

    TVisitor visitor = getInitialVisitor();
    if (env.valuesMissing()) {
      return null;
    }

    if (pkgLookupValue.packageExists()) {
      if (pkgLookupValue.getRoot().equals(root)) {
        Package pkg = null;
        try {
          PackageValue pkgValue = (PackageValue)
              env.getValueOrThrow(PackageValue.key(packageId), NoSuchPackageException.class);
          if (pkgValue == null) {
            return null;
          }
          pkg = pkgValue.getPackage();
        } catch (NoSuchPackageException e) {
          // The package had errors, but don't fail-fast as there might be subpackages below the
          // current directory, and there might be targets in the package that were successfully
          // loaded.
          env.getListener().handle(Event.error(
              "package contains errors: " + rootRelativePath.getPathString()));
          if (e.getPackage() != null) {
            pkg = e.getPackage();
          }
        }
        if (pkg != null) {
          visitor.visitPackageValue(pkg, env);
          if (env.valuesMissing()) {
            return null;
          }
        }
      }
      // The package lookup succeeded, but was under a different root. We still, however, need to
      // recursively consider subdirectories. For example:
      //
      //  Pretend --package_path=rootA/workspace:rootB/workspace and these are the only files:
      //    rootA/workspace/foo/
      //    rootA/workspace/foo/bar/BUILD
      //    rootB/workspace/foo/BUILD
      //  If we're doing a recursive package lookup under 'rootA/workspace' starting at 'foo', note
      //  that even though the package 'foo' is under 'rootB/workspace', there is still a package
      //  'foo/bar' under 'rootA/workspace'.
    }

    DirectoryListingValue dirValue;
    try {
      dirValue = (DirectoryListingValue) env.getValueOrThrow(DirectoryListingValue.key(rootedPath),
          InconsistentFilesystemException.class, IOException.class,
          FileSymlinkCycleException.class);
    } catch (InconsistentFilesystemException | IOException e) {
      return reportErrorAndReturn("Failed to list directory contents", e, rootRelativePath,
          env.getListener());
    } catch (FileSymlinkCycleException e) {
      // DirectoryListingFunction only throws FileSymlinkCycleException when FileFunction throws it,
      // but FileFunction was evaluated for rootedPath above, and didn't throw there. It shouldn't
      // be able to avoid throwing there but throw here.
      throw new IllegalStateException("Symlink cycle found after not being found for \""
          + rootedPath + "\"");
    }
    if (dirValue == null) {
      return null;
    }

    List<SkyKey> childDeps = Lists.newArrayList();
    for (Dirent dirent : dirValue.getDirents()) {
      if (dirent.getType() != Type.DIRECTORY) {
        // Non-directories can never host packages, and we do not follow symlinks (see above).
        continue;
      }
      String basename = dirent.getName();
      if (rootRelativePath.equals(PathFragment.EMPTY_FRAGMENT)
          && PathPackageLocator.DEFAULT_TOP_LEVEL_EXCLUDES.contains(basename)) {
        continue;
      }
      PathFragment subdirectory = rootRelativePath.getRelative(basename);

      // If this subdirectory is one of the excluded paths, don't recurse into it.
      if (excludedPaths.contains(subdirectory)) {
        continue;
      }

      // If we have an excluded path that isn't below this subdirectory, we shouldn't pass that
      // excluded path to our evaluation of the subdirectory, because the exclusion can't
      // possibly match anything beneath the subdirectory.
      //
      // For example, if we're currently evaluating directory "a", are looking at its subdirectory
      // "a/b", and we have an excluded path "a/c/d", there's no need to pass the excluded path
      // "a/c/d" to our evaluation of "a/b".
      //
      // This strategy should help to get more skyframe sharing. Consider the example above. A
      // subsequent request of "a/b/...", without any excluded paths, will be a cache hit.
      //
      // TODO(bazel-team): Replace the excludedPaths set with a trie or a SortedSet for better
      // efficiency.
      ImmutableSet<PathFragment> excludedSubdirectoriesBeneathThisSubdirectory =
          PathFragment.filterPathsStartingWith(excludedPaths, subdirectory);
      RootedPath subdirectoryRootedPath = RootedPath.toRootedPath(root, subdirectory);
      childDeps.add(getSkyKeyForSubdirectory(subdirectoryRootedPath,
          excludedSubdirectoriesBeneathThisSubdirectory));
      if (env.valuesMissing()) {
        return null;
      }
    }
    Map<SkyKey, SkyValue> subdirectorySkyValues = env.getValues(childDeps);
    if (env.valuesMissing()) {
      return null;
    }
    TReturn aggregation = aggregateWithSubdirectorySkyValues(visitor, subdirectorySkyValues);
    if (env.valuesMissing()) {
      return null;
    }
    return aggregation;
  }

  // Ignore all errors in traversal and return an empty value.
  private TReturn reportErrorAndReturn(String errorPrefix, Exception e,
      PathFragment rootRelativePath, EventHandler handler) {
    handler.handle(Event.warn(errorPrefix + ", for " + rootRelativePath
        + ", skipping: " + e.getMessage()));
    return getEmptyReturn();
  }
}
