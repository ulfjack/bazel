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

import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.PackageIdentifier.RepositoryName;
import com.google.devtools.build.lib.cmdline.ResolvedTargets;
import com.google.devtools.build.lib.cmdline.TargetParsingException;
import com.google.devtools.build.lib.cmdline.TargetPatternResolver;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.NoSuchThingException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.pkgcache.FilteringPolicies;
import com.google.devtools.build.lib.pkgcache.FilteringPolicy;
import com.google.devtools.build.lib.pkgcache.RecursivePackageProvider;
import com.google.devtools.build.lib.pkgcache.TargetPatternResolverUtil;
import com.google.devtools.build.lib.vfs.PathFragment;

/**
 * A {@link TargetPatternResolver} backed by a {@link RecursivePackageProvider}.
 */
public class RecursivePackageProviderBackedTargetPatternResolver
    implements TargetPatternResolver<Target> {

  private final RecursivePackageProvider recursivePackageProvider;
  private final EventHandler eventHandler;
  private final FilteringPolicy policy;

  public RecursivePackageProviderBackedTargetPatternResolver(
      RecursivePackageProvider recursivePackageProvider,
      EventHandler eventHandler,
      FilteringPolicy policy) {
    this.recursivePackageProvider = recursivePackageProvider;
    this.eventHandler = eventHandler;
    this.policy = policy;
  }

  @Override
  public void warn(String msg) {
    eventHandler.handle(Event.warn(msg));
  }

  /**
   * Gets a {@link Package} from the {@link RecursivePackageProvider}. May return a {@link Package}
   * that has errors.
   */
  private Package getPackage(PackageIdentifier pkgIdentifier)
      throws NoSuchPackageException, InterruptedException {
    return recursivePackageProvider.getPackage(eventHandler, pkgIdentifier);
  }

  @Override
  public Target getTargetOrNull(Label label) throws InterruptedException {
    try {
      if (!isPackage(label.getPackageIdentifier())) {
        return null;
      }
      return recursivePackageProvider.getTarget(eventHandler, label);
    } catch (NoSuchThingException e) {
      return null;
    }
  }

  @Override
  public ResolvedTargets<Target> getExplicitTarget(Label label)
      throws TargetParsingException, InterruptedException {
    try {
      Target target = recursivePackageProvider.getTarget(eventHandler, label);
      return policy.shouldRetain(target, true)
          ? ResolvedTargets.of(target)
          : ResolvedTargets.<Target>empty();
    } catch (NoSuchThingException e) {
      throw new TargetParsingException(e.getMessage(), e);
    }
  }

  @Override
  public ResolvedTargets<Target> getTargetsInPackage(
      String originalPattern, PackageIdentifier packageIdentifier, boolean rulesOnly)
      throws TargetParsingException, InterruptedException {
    FilteringPolicy actualPolicy = rulesOnly
        ? FilteringPolicies.and(FilteringPolicies.RULES_ONLY, policy)
        : policy;
    return getTargetsInPackage(originalPattern, packageIdentifier, actualPolicy);
  }

  private ResolvedTargets<Target> getTargetsInPackage(String originalPattern,
      PackageIdentifier packageIdentifier, FilteringPolicy policy)
      throws TargetParsingException, InterruptedException {
    try {
      Package pkg = getPackage(packageIdentifier);
      return TargetPatternResolverUtil.resolvePackageTargets(pkg, policy);
    } catch (NoSuchThingException e) {
      String message = TargetPatternResolverUtil.getParsingErrorMessage(
          e.getMessage(), originalPattern);
      throw new TargetParsingException(message, e);
    }
  }

  @Override
  public boolean isPackage(PackageIdentifier packageIdentifier) {
    return recursivePackageProvider.isPackage(eventHandler, packageIdentifier);
  }

  @Override
  public String getTargetKind(Target target) {
    return target.getTargetKind();
  }

  @Override
  public ResolvedTargets<Target> findTargetsBeneathDirectory(RepositoryName repository,
      String originalPattern, String directory, boolean rulesOnly,
      ImmutableSet<String> excludedSubdirectories)
      throws TargetParsingException, InterruptedException {
    FilteringPolicy actualPolicy = rulesOnly
        ? FilteringPolicies.and(FilteringPolicies.RULES_ONLY, policy)
        : policy;
    ImmutableSet<PathFragment> excludedPathFragments =
        TargetPatternResolverUtil.getPathFragments(excludedSubdirectories);
    PathFragment pathFragment = TargetPatternResolverUtil.getPathFragment(directory);
    ResolvedTargets.Builder<Target> targetBuilder = ResolvedTargets.builder();
    Iterable<PathFragment> packagesUnderDirectory =
        recursivePackageProvider.getPackagesUnderDirectory(
            repository, pathFragment, excludedPathFragments);
    for (PathFragment pkg : packagesUnderDirectory) {
      targetBuilder.merge(getTargetsInPackage(originalPattern,
          PackageIdentifier.create(repository, pkg),
          FilteringPolicies.NO_FILTER));
    }

    // Perform the no-targets-found check before applying the filtering policy so we only return the
    // error if the input directory's subtree really contains no targets.
    if (targetBuilder.isEmpty()) {
      throw new TargetParsingException("no targets found beneath '" + pathFragment + "'");
    }
    ResolvedTargets<Target> prefilteredTargets = targetBuilder.build();
     
    ResolvedTargets.Builder<Target> filteredBuilder = ResolvedTargets.builder();
    if (prefilteredTargets.hasError()) {
      filteredBuilder.setError();
    }
    for (Target target : prefilteredTargets.getTargets()) {
      if (actualPolicy.shouldRetain(target, false)) {
        filteredBuilder.add(target);
      }
    }
    return filteredBuilder.build();
  }
}

