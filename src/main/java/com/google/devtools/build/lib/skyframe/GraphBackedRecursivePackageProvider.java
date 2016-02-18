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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.PackageIdentifier.RepositoryName;
import com.google.devtools.build.lib.cmdline.TargetPattern;
import com.google.devtools.build.lib.cmdline.TargetPattern.Type;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.packages.BuildFileNotFoundException;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.pkgcache.FilteringPolicies;
import com.google.devtools.build.lib.pkgcache.FilteringPolicy;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.pkgcache.RecursivePackageProvider;
import com.google.devtools.build.lib.skyframe.TargetPatternValue.TargetPatternKey;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.WalkableGraph;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link RecursivePackageProvider} backed by a {@link WalkableGraph}, used by
 * {@code SkyQueryEnvironment} to look up the packages and targets matching the universe that's
 * been preloaded in {@code graph}.
 * */
public final class GraphBackedRecursivePackageProvider implements RecursivePackageProvider {

  private final WalkableGraph graph;
  private final PathPackageLocator pkgPath;
  private final ImmutableList<TargetPatternKey> universeTargetPatternKeys;

  public GraphBackedRecursivePackageProvider(WalkableGraph graph,
      ImmutableList<TargetPatternKey> universeTargetPatternKeys,
      PathPackageLocator pkgPath) {
    this.pkgPath = pkgPath;
    this.graph = Preconditions.checkNotNull(graph);
    this.universeTargetPatternKeys = Preconditions.checkNotNull(universeTargetPatternKeys);
  }

  @Override
  public Package getPackage(EventHandler eventHandler, PackageIdentifier packageName)
      throws NoSuchPackageException {
    SkyKey pkgKey = PackageValue.key(packageName);

    PackageValue pkgValue;
    if (graph.exists(pkgKey)) {
      pkgValue = (PackageValue) graph.getValue(pkgKey);
      if (pkgValue == null) {
        throw (NoSuchPackageException)
            Preconditions.checkNotNull(graph.getException(pkgKey), pkgKey);
      }
    } else {
      // If the package key does not exist in the graph, then it must not correspond to any package,
      // because the SkyQuery environment has already loaded the universe.
      throw new BuildFileNotFoundException(packageName, "BUILD file not found on package path");
    }
    return pkgValue.getPackage();
  }

  @Override
  public boolean isPackage(EventHandler eventHandler, PackageIdentifier packageName) {
    SkyKey packageLookupKey = PackageLookupValue.key(packageName);
    if (!graph.exists(packageLookupKey)) {
      // If the package lookup key does not exist in the graph, then it must not correspond to any
      // package, because the SkyQuery environment has already loaded the universe.
      return false;
    }
    PackageLookupValue packageLookupValue = (PackageLookupValue) graph.getValue(packageLookupKey);
    if (packageLookupValue == null) {
      Exception exception = Preconditions.checkNotNull(graph.getException(packageLookupKey),
          "During package lookup for '%s', got null for exception", packageName);
      if (exception instanceof NoSuchPackageException
          || exception instanceof InconsistentFilesystemException) {
        eventHandler.handle(Event.error(exception.getMessage()));
        return false;
      } else {
        throw new IllegalStateException("During package lookup for '" + packageName
            + "', got unexpected exception type", exception);
      }
    }
    return packageLookupValue.packageExists();
  }

  @Override
  public Iterable<PathFragment> getPackagesUnderDirectory(
      RepositoryName repository, PathFragment directory,
      ImmutableSet<PathFragment> excludedSubdirectories) {
    PathFragment.checkAllPathsAreUnder(excludedSubdirectories, directory);

    // Find the filtering policy of a TargetsBelowDirectory pattern, if any, in the universe that
    // contains this directory.
    FilteringPolicy filteringPolicy = null;
    for (TargetPatternKey patternKey : universeTargetPatternKeys) {
      TargetPattern pattern = patternKey.getParsedPattern();
      boolean isTBD = pattern.getType().equals(Type.TARGETS_BELOW_DIRECTORY);
      PackageIdentifier packageIdentifier = PackageIdentifier.create(
          repository, directory);
      if (isTBD && pattern.containsBelowDirectory(packageIdentifier)) {
        filteringPolicy =
            pattern.getRulesOnly() ? FilteringPolicies.RULES_ONLY : FilteringPolicies.NO_FILTER;
        break;
      }
    }

    List<Path> roots = new ArrayList<>();
    if (repository.isDefault()) {
      roots.addAll(pkgPath.getPathEntries());
    } else {
      RepositoryValue repositoryValue =
            (RepositoryValue) graph.getValue(RepositoryValue.key(repository));
      if (repositoryValue == null) {
        // If this key doesn't exist, the repository is outside the universe, so we return
        // "nothing".
        return ImmutableList.of();
      }
      roots.add(repositoryValue.getPath());
    }

    // If we found a TargetsBelowDirectory pattern in the universe that contains this directory,
    // then we can look for packages in and under it in the graph. If we didn't find one, then the
    // directory wasn't in the universe, so return an empty list.
    ImmutableList.Builder<PathFragment> builder = ImmutableList.builder();
    if (filteringPolicy != null) {
      for (Path root : roots) {
        collectPackagesUnder(repository, RootedPath.toRootedPath(root, directory),
            excludedSubdirectories, builder, filteringPolicy);
      }
    }
    return builder.build();
  }

  private void collectPackagesUnder(RepositoryName repository, RootedPath directory,
      ImmutableSet<PathFragment> excludedSubdirectories,
      ImmutableList.Builder<PathFragment> builder, FilteringPolicy policy) {
    SkyKey key =
        PrepareDepsOfTargetsUnderDirectoryValue.key(
            repository, directory, excludedSubdirectories, policy);
    // If the key does not exist in the graph, because the SkyQuery environment has
    // already loaded the universe, and we found a TargetsBelowDirectory pattern in the universe
    // that contained it, then we know the directory does not exist in the universe.
    if (!graph.exists(key)) {
      return;
    }

    // If the key exists in the graph, then it must have a value and must not have an exception,
    // because PrepareDepsOfTargetsUnderDirectoryFunction#compute never throws.
    PrepareDepsOfTargetsUnderDirectoryValue prepDepsValue =
        (PrepareDepsOfTargetsUnderDirectoryValue) Preconditions.checkNotNull(graph.getValue(key));
    if (prepDepsValue.isDirectoryPackage()) {
      builder.add(directory.getRelativePath());
    }
    ImmutableMap<RootedPath, Boolean> subdirectoryTransitivelyContainsPackages =
        prepDepsValue.getSubdirectoryTransitivelyContainsPackages();
    for (RootedPath subdirectory : subdirectoryTransitivelyContainsPackages.keySet()) {
      if (subdirectoryTransitivelyContainsPackages.get(subdirectory)) {
        PathFragment subdirectoryRelativePath = subdirectory.getRelativePath();
        ImmutableSet<PathFragment> excludedSubdirectoriesBeneathThisSubdirectory =
            PathFragment.filterPathsStartingWith(excludedSubdirectories, subdirectoryRelativePath);
        collectPackagesUnder(repository, subdirectory,
            excludedSubdirectoriesBeneathThisSubdirectory, builder, policy);
      }
    }
  }

  @Override
  public Target getTarget(EventHandler eventHandler, Label label)
      throws NoSuchPackageException, NoSuchTargetException {
    return getPackage(eventHandler, label.getPackageIdentifier()).getTarget(label.getName());
  }
}
