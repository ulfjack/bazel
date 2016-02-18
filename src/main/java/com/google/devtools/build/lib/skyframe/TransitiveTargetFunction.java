// Copyright 2014 The Bazel Authors. All rights reserved.
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

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration.Fragment;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.packages.AspectDefinition;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.ConfigurationFragmentPolicy;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.NoSuchThingException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClassProvider;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.skyframe.TransitiveTargetFunction.TransitiveTargetValueBuilder;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.ValueOrException2;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map.Entry;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * This class builds transitive Target values such that evaluating a Target value is similar to
 * running it through the LabelVisitor.
 */
public class TransitiveTargetFunction
    extends TransitiveBaseTraversalFunction<TransitiveTargetValueBuilder> {

  private final ConfiguredRuleClassProvider ruleClassProvider;

  TransitiveTargetFunction(RuleClassProvider ruleClassProvider) {
    this.ruleClassProvider = (ConfiguredRuleClassProvider) ruleClassProvider;
  }

  @Override
  SkyKey getKey(Label label) {
    return TransitiveTargetValue.key(label);
  }

  @Override
  TransitiveTargetValueBuilder processTarget(Label label, TargetAndErrorIfAny targetAndErrorIfAny) {
    Target target = targetAndErrorIfAny.getTarget();
    boolean packageLoadedSuccessfully = targetAndErrorIfAny.isPackageLoadedSuccessfully();
    return new TransitiveTargetValueBuilder(label, target, packageLoadedSuccessfully);
  }

  @Override
  void processDeps(TransitiveTargetValueBuilder builder, EventHandler eventHandler,
      TargetAndErrorIfAny targetAndErrorIfAny,
      Iterable<Entry<SkyKey, ValueOrException2<NoSuchPackageException, NoSuchTargetException>>>
          depEntries) {
    boolean successfulTransitiveLoading = builder.isSuccessfulTransitiveLoading();
    Target target = targetAndErrorIfAny.getTarget();
    NestedSetBuilder<Label> transitiveRootCauses = builder.getTransitiveRootCauses();

    for (Entry<SkyKey, ValueOrException2<NoSuchPackageException, NoSuchTargetException>> entry :
        depEntries) {
      Label depLabel = (Label) entry.getKey().argument();
      TransitiveTargetValue transitiveTargetValue;
      try {
        transitiveTargetValue = (TransitiveTargetValue) entry.getValue().get();
        if (transitiveTargetValue == null) {
          continue;
        }
      } catch (NoSuchPackageException | NoSuchTargetException e) {
        successfulTransitiveLoading = false;
        transitiveRootCauses.add(depLabel);
        maybeReportErrorAboutMissingEdge(target, depLabel, e, eventHandler);
        continue;
      }
      builder.getTransitiveSuccessfulPkgs().addTransitive(
          transitiveTargetValue.getTransitiveSuccessfulPackages());
      builder.getTransitiveUnsuccessfulPkgs().addTransitive(
          transitiveTargetValue.getTransitiveUnsuccessfulPackages());
      builder.getTransitiveTargets().addTransitive(transitiveTargetValue.getTransitiveTargets());
      NestedSet<Label> rootCauses = transitiveTargetValue.getTransitiveRootCauses();
      if (rootCauses != null) {
        successfulTransitiveLoading = false;
        transitiveRootCauses.addTransitive(rootCauses);
        if (transitiveTargetValue.getErrorLoadingTarget() != null) {
          maybeReportErrorAboutMissingEdge(target, depLabel,
              transitiveTargetValue.getErrorLoadingTarget(), eventHandler);
        }
      }

      NestedSet<Class<? extends Fragment>> depFragments =
          transitiveTargetValue.getTransitiveConfigFragments();
      Collection<Class<? extends Fragment>> depFragmentsAsCollection =
          depFragments.toCollection();
      // The simplest collection technique would be to unconditionally add all deps' nested
      // sets to the current target's nested set. But when there's large overlap between their
      // fragment needs, this produces unnecessarily bloated nested sets and a lot of references
      // that don't contribute anything unique to the required fragment set. So we optimize here
      // by completely skipping sets that don't offer anything new. More fine-tuned optimization
      // is possible, but this offers a good balance between simplicity and practical efficiency.
      Set<Class<? extends Fragment>> addedConfigFragments = builder.getConfigFragmentsFromDeps();
      if (!addedConfigFragments.containsAll(depFragmentsAsCollection)) {
        builder.getTransitiveConfigFragments().addTransitive(depFragments);
        addedConfigFragments.addAll(depFragmentsAsCollection);
      }
    }
    builder.setSuccessfulTransitiveLoading(successfulTransitiveLoading);
  }

  @Override
  public SkyValue computeSkyValue(TargetAndErrorIfAny targetAndErrorIfAny,
      TransitiveTargetValueBuilder builder) {
    Target target = targetAndErrorIfAny.getTarget();
    NoSuchTargetException errorLoadingTarget = targetAndErrorIfAny.getErrorLoadingTarget();

    // Get configuration fragments directly required by this target.
    if (target instanceof Rule) {
      ConfigurationFragmentPolicy configurationFragmentPolicy =
          target.getAssociatedRule().getRuleClassObject().getConfigurationFragmentPolicy();
      for (Class<?> fragment : configurationFragmentPolicy.getRequiredConfigurationFragments()) {
        if (!builder.getConfigFragmentsFromDeps().contains(fragment)) {
          builder.getTransitiveConfigFragments().add(
              fragment.asSubclass(BuildConfiguration.Fragment.class));
        }
      }
      builder.getTransitiveConfigFragments().add(
          ruleClassProvider.getUniversalFragment().asSubclass(BuildConfiguration.Fragment.class));
    }

    return builder.build(errorLoadingTarget);
  }

  protected Collection<Label> getAspectLabels(Rule fromRule, Attribute attr, Label toLabel,
      ValueOrException2<NoSuchPackageException, NoSuchTargetException> toVal,
      Environment env) {
    SkyKey packageKey = PackageValue.key(toLabel.getPackageIdentifier());
    try {
      PackageValue pkgValue =
          (PackageValue) env.getValueOrThrow(packageKey, NoSuchPackageException.class);
      if (pkgValue == null) {
        return ImmutableList.of();
      }
      Package pkg = pkgValue.getPackage();
      if (pkg.containsErrors()) {
        // Do nothing. This error was handled when we computed the corresponding
        // TransitiveTargetValue.
        return ImmutableList.of();
      }
      Target dependedTarget = pkgValue.getPackage().getTarget(toLabel.getName());
      return AspectDefinition.visitAspectsIfRequired(fromRule, attr, dependedTarget).values();
    } catch (NoSuchThingException e) {
      // Do nothing. This error was handled when we computed the corresponding
      // TransitiveTargetValue.
      return ImmutableList.of();
    }
  }

  @Override
  TargetMarkerValue getTargetMarkerValue(SkyKey targetMarkerKey, Environment env)
      throws NoSuchTargetException, NoSuchPackageException {
    return (TargetMarkerValue)
        env.getValueOrThrow(
            targetMarkerKey, NoSuchTargetException.class, NoSuchPackageException.class);
  }

  private void maybeReportErrorAboutMissingEdge(
      Target target, Label depLabel, NoSuchThingException e, EventHandler eventHandler) {
    if (e instanceof NoSuchTargetException) {
      NoSuchTargetException nste = (NoSuchTargetException) e;
      if (depLabel.equals(nste.getLabel())) {
        eventHandler.handle(
            Event.error(
                TargetUtils.getLocationMaybe(target),
                TargetUtils.formatMissingEdge(target, depLabel, e)));
      }
    } else if (e instanceof NoSuchPackageException) {
      NoSuchPackageException nspe = (NoSuchPackageException) e;
      if (nspe.getPackageId().equals(depLabel.getPackageIdentifier())) {
        eventHandler.handle(
            Event.error(
                TargetUtils.getLocationMaybe(target),
                TargetUtils.formatMissingEdge(target, depLabel, e)));
      }
    }
  }

  /**
   * Holds values accumulated across the given target and its transitive dependencies for the
   * purpose of constructing a {@link TransitiveTargetValue}.
   *
   * <p>Note that this class is mutable! The {@code successfulTransitiveLoading} property is
   * initialized with the {@code packageLoadedSuccessfully} constructor parameter, and may be
   * modified if a transitive dependency is found to be in error.
   */
  static class TransitiveTargetValueBuilder {
    private boolean successfulTransitiveLoading;
    private final NestedSetBuilder<PackageIdentifier> transitiveSuccessfulPkgs;
    private final NestedSetBuilder<PackageIdentifier> transitiveUnsuccessfulPkgs;
    private final NestedSetBuilder<Label> transitiveTargets;
    private final NestedSetBuilder<Class<? extends Fragment>> transitiveConfigFragments;
    private final Set<Class<? extends Fragment>> configFragmentsFromDeps;
    private final NestedSetBuilder<Label> transitiveRootCauses;

    public TransitiveTargetValueBuilder(Label label, Target target,
        boolean packageLoadedSuccessfully) {
      this.transitiveSuccessfulPkgs = NestedSetBuilder.stableOrder();
      this.transitiveUnsuccessfulPkgs = NestedSetBuilder.stableOrder();
      this.transitiveTargets = NestedSetBuilder.stableOrder();
      this.transitiveConfigFragments = NestedSetBuilder.stableOrder();
      // No need to store directly required fragments that are also required by deps.
      this.configFragmentsFromDeps = new LinkedHashSet<>();
      this.transitiveRootCauses = NestedSetBuilder.stableOrder();

      this.successfulTransitiveLoading = packageLoadedSuccessfully;
      PackageIdentifier packageId = target.getPackage().getPackageIdentifier();
      if (packageLoadedSuccessfully) {
        transitiveSuccessfulPkgs.add(packageId);
      } else {
        transitiveRootCauses.add(label);
        transitiveUnsuccessfulPkgs.add(packageId);
      }
      transitiveTargets.add(target.getLabel());
    }

    public NestedSetBuilder<PackageIdentifier> getTransitiveSuccessfulPkgs() {
      return transitiveSuccessfulPkgs;
    }

    public NestedSetBuilder<PackageIdentifier> getTransitiveUnsuccessfulPkgs() {
      return transitiveUnsuccessfulPkgs;
    }

    public NestedSetBuilder<Label> getTransitiveTargets() {
      return transitiveTargets;
    }

    public NestedSetBuilder<Class<? extends Fragment>> getTransitiveConfigFragments() {
      return transitiveConfigFragments;
    }

    public Set<Class<? extends Fragment>> getConfigFragmentsFromDeps() {
      return configFragmentsFromDeps;
    }

    public NestedSetBuilder<Label> getTransitiveRootCauses() {
      return transitiveRootCauses;
    }

    public boolean isSuccessfulTransitiveLoading() {
      return successfulTransitiveLoading;
    }

    public void setSuccessfulTransitiveLoading(boolean successfulTransitiveLoading) {
      this.successfulTransitiveLoading = successfulTransitiveLoading;
    }

    public SkyValue build(@Nullable NoSuchTargetException errorLoadingTarget) {
      NestedSet<PackageIdentifier> successfullyLoadedPkgs = transitiveSuccessfulPkgs.build();
      NestedSet<PackageIdentifier> unsuccessfullyLoadedPkgs = transitiveUnsuccessfulPkgs.build();
      NestedSet<Label> loadedTargets = transitiveTargets.build();
      NestedSet<Class<? extends Fragment>> configFragments = transitiveConfigFragments.build();
      return successfulTransitiveLoading
          ? TransitiveTargetValue.successfulTransitiveLoading(successfullyLoadedPkgs,
          unsuccessfullyLoadedPkgs, loadedTargets, configFragments)
          : TransitiveTargetValue.unsuccessfulTransitiveLoading(successfullyLoadedPkgs,
              unsuccessfullyLoadedPkgs, loadedTargets, transitiveRootCauses.build(),
              errorLoadingTarget, configFragments);
    }
  }
}
