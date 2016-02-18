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

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimap;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.Actions;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.MutableActionGraph.ActionConflictException;
import com.google.devtools.build.lib.analysis.CachingAnalysisEnvironment;
import com.google.devtools.build.lib.analysis.ConfiguredAspect;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.DependencyResolver.Dependency;
import com.google.devtools.build.lib.analysis.LabelAndConfiguration;
import com.google.devtools.build.lib.analysis.RuleConfiguredTarget;
import com.google.devtools.build.lib.analysis.TargetAndConfiguration;
import com.google.devtools.build.lib.analysis.TransitiveInfoProvider;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.ConfigMatchingProvider;
import com.google.devtools.build.lib.analysis.config.HostTransition;
import com.google.devtools.build.lib.analysis.config.InvalidConfigurationException;
import com.google.devtools.build.lib.analysis.config.PatchTransition;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.packages.Aspect;
import com.google.devtools.build.lib.packages.AspectDefinition;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.BuildFileContainsErrorsException;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.NoSuchPackageException;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.NoSuchThingException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.RawAttributeMapper;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClassProvider;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.skyframe.AspectFunction.AspectCreationException;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor.BuildViewProvider;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.ValueOrException;
import com.google.devtools.build.skyframe.ValueOrException2;
import com.google.devtools.build.skyframe.ValueOrException3;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * SkyFunction for {@link ConfiguredTargetValue}s.
 */
final class ConfiguredTargetFunction implements SkyFunction {

  /**
   * Exception class that signals an error during the evaluation of a dependency.
   */
  public static class DependencyEvaluationException extends Exception {
    private final SkyKey rootCauseSkyKey;

    public DependencyEvaluationException(Exception cause) {
      super(cause);
      this.rootCauseSkyKey = null;
    }

    public DependencyEvaluationException(SkyKey rootCauseSkyKey, Exception cause) {
      super(cause);
      this.rootCauseSkyKey = rootCauseSkyKey;
    }

    /**
     * Returns the key of the root cause or null if the problem was with this target.
     */
    public SkyKey getRootCauseSkyKey() {
      return rootCauseSkyKey;
    }

    @Override
    public Exception getCause() {
      return (Exception) super.getCause();
    }
  }

  private static final Function<Dependency, SkyKey> TO_KEYS =
      new Function<Dependency, SkyKey>() {
    @Override
    public SkyKey apply(Dependency input) {
      return ConfiguredTargetValue.key(input.getLabel(), input.getConfiguration());
    }
  };

  private final BuildViewProvider buildViewProvider;
  private final RuleClassProvider ruleClassProvider;

  ConfiguredTargetFunction(BuildViewProvider buildViewProvider,
      RuleClassProvider ruleClassProvider) {
    this.buildViewProvider = buildViewProvider;
    this.ruleClassProvider = ruleClassProvider;
  }

  @Override
  public SkyValue compute(SkyKey key, Environment env) throws ConfiguredTargetFunctionException,
      InterruptedException {
    SkyframeBuildView view = buildViewProvider.getSkyframeBuildView();
    NestedSetBuilder<Package> transitivePackages = NestedSetBuilder.stableOrder();
    ConfiguredTargetKey configuredTargetKey = (ConfiguredTargetKey) key.argument();
    LabelAndConfiguration lc = LabelAndConfiguration.of(
        configuredTargetKey.getLabel(), configuredTargetKey.getConfiguration());

    BuildConfiguration configuration = lc.getConfiguration();

    PackageValue packageValue =
        (PackageValue) env.getValue(PackageValue.key(lc.getLabel().getPackageIdentifier()));
    if (packageValue == null) {
      return null;
    }

    Package pkg = packageValue.getPackage();
    if (pkg.containsErrors()) {
      throw new ConfiguredTargetFunctionException(
          new BuildFileContainsErrorsException(lc.getLabel().getPackageIdentifier()),
          Transience.PERSISTENT);
    }
    Target target;
    try {
      target = packageValue.getPackage().getTarget(lc.getLabel().getName());
    } catch (NoSuchTargetException e1) {
      throw new ConfiguredTargetFunctionException(new NoSuchTargetException(lc.getLabel(),
          "No such target"));
    }
    transitivePackages.add(packageValue.getPackage());
    // TODO(bazel-team): This is problematic - we create the right key, but then end up with a value
    // that doesn't match; we can even have the same value multiple times. However, I think it's
    // only triggered in tests (i.e., in normal operation, the configuration passed in is already
    // null).
    if (!target.isConfigurable()) {
      configuration = null;
    }
    TargetAndConfiguration ctgValue =
        new TargetAndConfiguration(target, configuration);

    SkyframeDependencyResolver resolver = view.createDependencyResolver(env);
    if (resolver == null) {
      return null;
    }

    try {
      // Get the configuration targets that trigger this rule's configurable attributes.
      Set<ConfigMatchingProvider> configConditions =
          getConfigConditions(ctgValue.getTarget(), env, resolver, ctgValue, transitivePackages);
      if (env.valuesMissing()) {
        return null;
      }

      ListMultimap<Attribute, ConfiguredTarget> depValueMap =
          computeDependencies(
              env,
              resolver,
              ctgValue,
              null,
              configConditions,
              ruleClassProvider,
              view.getHostConfiguration(configuration),
              transitivePackages);
      ConfiguredTargetValue ans = createConfiguredTarget(
          view, env, target, configuration, depValueMap, configConditions, transitivePackages);
      return ans;
    } catch (DependencyEvaluationException e) {
      throw new ConfiguredTargetFunctionException(e.getRootCauseSkyKey(), e.getCause());
    } catch (AspectCreationException e) {
      throw new ConfiguredTargetFunctionException(
          new ConfiguredValueCreationException(e.getMessage()));
    }
  }

  /**
   * Computes the direct dependencies of a node in the configured target graph (a configured
   * target or an aspect).
   *
   * <p>Returns null if Skyframe hasn't evaluated the required dependencies yet. In this case, the
   * caller should also return null to Skyframe.
   *  @param env the Skyframe environment
   * @param resolver The dependency resolver
   * @param ctgValue The label and the configuration of the node
   * @param aspect
   * @param configConditions the configuration conditions for evaluating the attributes of the node
   * @param ruleClassProvider rule class provider for determining the right configuration fragments
   *   to apply to deps
   * @param hostConfiguration the host configuration. There's a noticeable performance hit from
   *     instantiating this on demand for every dependency that wants it, so it's best to compute
   *     the host configuration as early as possible and pass this reference to all consumers
   * */
  @Nullable
  static ListMultimap<Attribute, ConfiguredTarget> computeDependencies(
      Environment env,
      SkyframeDependencyResolver resolver,
      TargetAndConfiguration ctgValue,
      Aspect aspect,
      Set<ConfigMatchingProvider> configConditions,
      RuleClassProvider ruleClassProvider,
      BuildConfiguration hostConfiguration,
      NestedSetBuilder<Package> transitivePackages)
      throws DependencyEvaluationException, AspectCreationException, InterruptedException {
    // Create the map from attributes to list of (target, configuration) pairs.
    ListMultimap<Attribute, Dependency> depValueNames;
    try {
      depValueNames =
          resolver.dependentNodeMap(ctgValue, hostConfiguration, aspect, configConditions);
    } catch (EvalException e) {
      env.getListener().handle(Event.error(e.getLocation(), e.getMessage()));
      throw new DependencyEvaluationException(new ConfiguredValueCreationException(e.print()));
    }

    // Trim each dep's configuration so it only includes the fragments needed by its transitive
    // closure (only dynamic configurations support this).
    if (ctgValue.getConfiguration() != null
        && ctgValue.getConfiguration().useDynamicConfigurations()) {
      depValueNames = trimConfigurations(env, ctgValue, depValueNames, hostConfiguration,
          ruleClassProvider);
      if (depValueNames == null) {
        return null;
      }
    }

    // Resolve configured target dependencies and handle errors.
    Map<SkyKey, ConfiguredTarget> depValues = resolveConfiguredTargetDependencies(env,
        depValueNames.values(), ctgValue.getTarget(), transitivePackages);
    if (depValues == null) {
      return null;
    }

    // Resolve required aspects.
    ListMultimap<SkyKey, ConfiguredAspect> depAspects =
        resolveAspectDependencies(env, depValues, depValueNames.values(), transitivePackages);
    if (depAspects == null) {
      return null;
    }

    // Merge the dependent configured targets and aspects into a single map.
    return mergeAspects(depValueNames, depValues, depAspects);
  }

  /**
   * Helper class for {@link #trimConfigurations} - encapsulates a set of config fragments and
   * a dynamic transition. This can be used to determine the exact build options needed to
   * set a dynamic configuration.
   */
  private static final class FragmentsAndTransition {
    // Treat this as immutable. The only reason this isn't an ImmutableSet is because it
    // gets bound to a NestedSet.toSet() reference, which returns a Set interface.
    final Set<Class<? extends BuildConfiguration.Fragment>> fragments;
    final Attribute.Transition transition;
    private final int hashCode;

    FragmentsAndTransition(Set<Class<? extends BuildConfiguration.Fragment>> fragments,
        Attribute.Transition transition) {
      this.fragments = fragments;
      this.transition = transition;
      hashCode = Objects.hash(this.fragments, this.transition);
    }

    @Override
    public boolean equals(Object o) {
      if (o == this) {
        return true;
      } else if (o == null) {
        return false;
      } else {
        FragmentsAndTransition other = (FragmentsAndTransition) o;
        return other.transition.equals(transition) && other.fragments.equals(fragments);
      }
    }

    @Override
    public int hashCode() {
      return hashCode;
    }
  }

  /**
   * Creates a dynamic configuration for each dep that's custom-fitted specifically for that dep.
   *
   * <p>More specifically: given a set of {@link Dependency} instances holding dynamic config
   * transition requests (e.g. {@link Dependency#hasStaticConfiguration()} == false}), returns
   * equivalent dependencies containing dynamically created configurations that a) apply those
   * transitions and b) only contain the fragments needed by the dep and everything in its
   * transitive closure.
   *
   * <p>This method is heavily performance-optimized. Because it, in aggregate, reads over every
   * edge in the configured target graph, small inefficiencies can have observable impact on
   * build analysis time. Keep this in mind when making modifications and performance-test any
   * changes you make.
   */
  @Nullable
  static ListMultimap<Attribute, Dependency> trimConfigurations(Environment env,
      TargetAndConfiguration ctgValue, ListMultimap<Attribute, Dependency> originalDeps,
      BuildConfiguration hostConfiguration, RuleClassProvider ruleClassProvider)
      throws DependencyEvaluationException {

    // Maps each Skyframe-evaluated BuildConfiguration to the dependencies that need that
    // configuration. For cases where Skyframe isn't needed to get the configuration (e.g. when
    // we just re-used the original rule's configuration), we should skip this outright.
    Multimap<SkyKey, Map.Entry<Attribute, Dependency>> keysToEntries = ArrayListMultimap.create();

    // Stores the result of applying a dynamic transition to the current configuration using a
    // particular subset of fragments. By caching this, we save from redundantly computing the
    // same transition for every dependency edge that requests that transition. This can have
    // real effect on analysis time for commonly triggered transitions.
    Map<FragmentsAndTransition, BuildOptions> transitionsMap = new HashMap<>();

    // The fragments used by the current target's configuration.
    Set<Class<? extends BuildConfiguration.Fragment>> ctgFragments =
        ctgValue.getConfiguration().fragmentClasses();
    BuildOptions ctgOptions = ctgValue.getConfiguration().getOptions();

    // Our output map.
    ListMultimap<Attribute, Dependency> trimmedDeps = ArrayListMultimap.create();

    for (Map.Entry<Attribute, Dependency> depsEntry : originalDeps.entries()) {
      Dependency dep = depsEntry.getValue();

      if (dep.hasStaticConfiguration()) {
        // Certain targets (like output files) trivially pass their configurations to their deps.
        // So no need to transform them in any way.
        trimmedDeps.put(depsEntry.getKey(),
            new Dependency(dep.getLabel(), dep.getConfiguration(), dep.getAspects()));
        continue;
      } else if (dep.getTransition() == Attribute.ConfigurationTransition.NULL) {
        trimmedDeps.put(depsEntry.getKey(),
            new Dependency(dep.getLabel(), (BuildConfiguration) null, dep.getAspects()));
        continue;
      }

      // Figure out the required fragments for this dep and its transitive closure.
      SkyKey fragmentsKey = TransitiveTargetValue.key(dep.getLabel());
      TransitiveTargetValue transitiveDepInfo = (TransitiveTargetValue) env.getValue(fragmentsKey);
      if (transitiveDepInfo == null) {
        // This should only be possible for tests. In actual runs, this was already called
        // as a routine part of the loading phase.
        // TODO(bazel-team): check this only occurs in a test context.
        return null;
      }
      Set<Class<? extends BuildConfiguration.Fragment>> depFragments =
          transitiveDepInfo.getTransitiveConfigFragments().toSet();

      boolean sameFragments = depFragments.equals(ctgFragments);
      Attribute.Transition transition = dep.getTransition();

      if (sameFragments) {
        if (transition == Attribute.ConfigurationTransition.NONE) {
          // The dep uses the same exact configuration.
          trimmedDeps.put(depsEntry.getKey(),
              new Dependency(dep.getLabel(), ctgValue.getConfiguration(), dep.getAspects()));
          continue;
        } else if (transition == HostTransition.INSTANCE) {
          // The current rule's host configuration can also be used for the dep. We short-circuit
          // the standard transition logic for host transitions because these transitions are
          // uniquely frequent. It's possible, e.g., for every node in the configured target graph
          // to incur multiple host transitions. So we aggressively optimize to avoid hurting
          // analysis time.
          trimmedDeps.put(depsEntry.getKey(),
              new Dependency(dep.getLabel(), hostConfiguration, dep.getAspects()));
          continue;
        }
      }

      // Apply the transition or use the cached result if it was already applied.
      FragmentsAndTransition transitionKey = new FragmentsAndTransition(depFragments, transition);
      BuildOptions toOptions = transitionsMap.get(transitionKey);
      if (toOptions == null) {
        Verify.verify(transition == Attribute.ConfigurationTransition.NONE
            || transition instanceof PatchTransition);
        BuildOptions fromOptions = ctgOptions;
        // TODO(bazel-team): safety-check that the below call never mutates fromOptions.
        toOptions = transition == Attribute.ConfigurationTransition.NONE
            ? fromOptions
            : ((PatchTransition) transition).apply(fromOptions);
        if (!sameFragments) {
          // TODO(bazel-team): pre-compute getOptionsClasses in the constructor.
          toOptions = toOptions.trim(BuildConfiguration.getOptionsClasses(
              transitiveDepInfo.getTransitiveConfigFragments(), ruleClassProvider));
        }
        transitionsMap.put(transitionKey, toOptions);
      }

      // If the transition doesn't change the configuration, trivially re-use the original
      // configuration.
      if (sameFragments && toOptions.equals(ctgOptions)) {
        trimmedDeps.put(depsEntry.getKey(),
            new Dependency(dep.getLabel(), ctgValue.getConfiguration(), dep.getAspects()));
        continue;
      }

      // If we get here, we have to get the configuration from Skyframe.
      keysToEntries.put(BuildConfigurationValue.key(depFragments, toOptions), depsEntry);
    }

    // Get all BuildConfigurations we need to get from Skyframe.
    Map<SkyKey, ValueOrException<InvalidConfigurationException>> depConfigValues =
        env.getValuesOrThrow(keysToEntries.keySet(), InvalidConfigurationException.class);
    if (env.valuesMissing()) {
      return null;
    }

    // Now fill in the remaining unresolved deps with the now-resolved configurations.
    try {
      for (Map.Entry<SkyKey, ValueOrException<InvalidConfigurationException>> entry :
          depConfigValues.entrySet()) {
        SkyKey key = entry.getKey();
        BuildConfigurationValue trimmedConfig = (BuildConfigurationValue) entry.getValue().get();
        for (Map.Entry<Attribute, Dependency> info : keysToEntries.get(key)) {
          Attribute attribute = info.getKey();
          Dependency originalDep = info.getValue();
          trimmedDeps.put(attribute,
              new Dependency(originalDep.getLabel(), trimmedConfig.getConfiguration(),
                  originalDep.getAspects()));
        }
      }
    } catch (InvalidConfigurationException e) {
      throw new DependencyEvaluationException(e);
    }

    return trimmedDeps;
  }

  /**
   * Merges the each direct dependency configured target with the aspects associated with it.
   *
   * <p>Note that the combination of a configured target and its associated aspects are not
   * represented by a Skyframe node. This is because there can possibly be many different
   * combinations of aspects for a particular configured target, so it would result in a
   * combinatiorial explosion of Skyframe nodes.
   */
  private static ListMultimap<Attribute, ConfiguredTarget> mergeAspects(
      ListMultimap<Attribute, Dependency> depValueNames,
      Map<SkyKey, ConfiguredTarget> depConfiguredTargetMap,
      ListMultimap<SkyKey, ConfiguredAspect> depAspectMap) {
    ListMultimap<Attribute, ConfiguredTarget> result = ArrayListMultimap.create();

    for (Map.Entry<Attribute, Dependency> entry : depValueNames.entries()) {
      Dependency dep = entry.getValue();
      SkyKey depKey = TO_KEYS.apply(dep);
      ConfiguredTarget depConfiguredTarget = depConfiguredTargetMap.get(depKey);
      result.put(entry.getKey(),
          RuleConfiguredTarget.mergeAspects(depConfiguredTarget, depAspectMap.get(depKey)));
    }

    return result;
  }

  /**
   * Given a list of {@link Dependency} objects, returns a multimap from the {@link SkyKey} of the
   * dependency to the {@link ConfiguredAspect} instances that should be merged into it.
   *
   * <p>Returns null if the required aspects are not computed yet.
   */
  @Nullable
  private static ListMultimap<SkyKey, ConfiguredAspect> resolveAspectDependencies(
      Environment env,
      Map<SkyKey, ConfiguredTarget> configuredTargetMap,
      Iterable<Dependency> deps,
      NestedSetBuilder<Package> transitivePackages)
      throws AspectCreationException {
    ListMultimap<SkyKey, ConfiguredAspect> result = ArrayListMultimap.create();
    Set<SkyKey> aspectKeys = new HashSet<>();
    for (Dependency dep : deps) {
      for (Aspect depAspect : dep.getAspects()) {
        aspectKeys.add(createAspectKey(dep.getLabel(), dep.getConfiguration(), depAspect));
      }
    }

    Map<SkyKey, ValueOrException3<
        AspectCreationException, NoSuchThingException, ConfiguredValueCreationException>>
        depAspects = env.getValuesOrThrow(aspectKeys, AspectCreationException.class,
            NoSuchThingException.class, ConfiguredValueCreationException.class);

    for (Dependency dep : deps) {
      SkyKey depKey = TO_KEYS.apply(dep);
      // If the same target was declared in different attributes of rule, we should not process it
      // twice.
      if (result.containsKey(depKey)) {
        continue;
      }
      ConfiguredTarget depConfiguredTarget = configuredTargetMap.get(depKey);
      for (Aspect depAspect : dep.getAspects()) {
        if (!aspectMatchesConfiguredTarget(depConfiguredTarget, depAspect)) {
          continue;
        }

        SkyKey aspectKey = createAspectKey(dep.getLabel(), dep.getConfiguration(), depAspect);
        AspectValue aspectValue = null;
        try {
          aspectValue = (AspectValue) depAspects.get(aspectKey).get();
        } catch (ConfiguredValueCreationException e) {
          // The configured target should have been created in resolveConfiguredTargetDependencies()
          throw new IllegalStateException(e);
        } catch (NoSuchThingException e) {
          throw new AspectCreationException(
              String.format(
                  "Evaluation of aspect %s on %s failed: %s",
                  depAspect.getDefinition().getName(),
                  dep.getLabel(),
                  e.toString()));
        }

        if (aspectValue == null) {
          // Dependent aspect has either not been computed yet or is in error.
          return null;
        }
        result.put(depKey, aspectValue.getConfiguredAspect());
        transitivePackages.addTransitive(aspectValue.getTransitivePackages());
      }
    }
    return result;
  }

  public static SkyKey createAspectKey(
      Label label, BuildConfiguration buildConfiguration, Aspect depAspect) {
    return AspectValue.key(label,
        buildConfiguration,
        depAspect.getAspectClass(),
        depAspect.getParameters());
  }

  private static boolean aspectMatchesConfiguredTarget(ConfiguredTarget dep, Aspect aspectClass) {
    AspectDefinition aspectDefinition = aspectClass.getDefinition();
    for (Class<?> provider : aspectDefinition.getRequiredProviders()) {
      if (dep.getProvider(provider.asSubclass(TransitiveInfoProvider.class)) == null) {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns the set of {@link ConfigMatchingProvider}s that key the configurable attributes
   * used by this rule.
   *
   * <p>>If the configured targets supplying those providers aren't yet resolved by the
   * dependency resolver, returns null.
   */
  @Nullable
  static Set<ConfigMatchingProvider> getConfigConditions(Target target, Environment env,
      SkyframeDependencyResolver resolver, TargetAndConfiguration ctgValue,
      NestedSetBuilder<Package> transitivePackages)
      throws DependencyEvaluationException {
    if (!(target instanceof Rule)) {
      return ImmutableSet.of();
    }

    ImmutableSet.Builder<ConfigMatchingProvider> configConditions = ImmutableSet.builder();

    // Collect the labels of the configured targets we need to resolve.
    ListMultimap<Attribute, LabelAndConfiguration> configLabelMap = ArrayListMultimap.create();
    RawAttributeMapper attributeMap = RawAttributeMapper.of(((Rule) target));
    for (Attribute a : ((Rule) target).getAttributes()) {
      for (Label configLabel : attributeMap.getConfigurabilityKeys(a.getName(), a.getType())) {
        if (!BuildType.Selector.isReservedLabel(configLabel)) {
          configLabelMap.put(a, LabelAndConfiguration.of(
              configLabel, ctgValue.getConfiguration()));
        }
      }
    }
    if (configLabelMap.isEmpty()) {
      return ImmutableSet.of();
    }

    // Collect the corresponding Skyframe configured target values. Abort early if they haven't
    // been computed yet.
    Collection<Dependency> configValueNames = resolver.resolveRuleLabels(ctgValue, configLabelMap);

    // No need to get new configs from Skyframe - config_setting rules always use the current
    // target's config.
    // TODO(bazel-team): remove the need for this special transformation. We can probably do this by
    // simply passing this through trimConfigurations.
    BuildConfiguration targetConfig = ctgValue.getConfiguration();
    if (targetConfig != null && targetConfig.useDynamicConfigurations()) {
      ImmutableList.Builder<Dependency> staticConfigs = ImmutableList.builder();
      for (Dependency dep : configValueNames) {
        staticConfigs.add(new Dependency(dep.getLabel(), targetConfig, dep.getAspects()));
      }
      configValueNames = staticConfigs.build();
    }

    Map<SkyKey, ConfiguredTarget> configValues =
        resolveConfiguredTargetDependencies(env, configValueNames, target, transitivePackages);
    if (configValues == null) {
      return null;
    }

    // Get the configured targets as ConfigMatchingProvider interfaces.
    for (Dependency entry : configValueNames) {
      ConfiguredTarget value = configValues.get(TO_KEYS.apply(entry));
      // The code above guarantees that value is non-null here.
      ConfigMatchingProvider provider = value.getProvider(ConfigMatchingProvider.class);
      if (provider != null) {
        configConditions.add(provider);
      } else {
        // Not a valid provider for configuration conditions.
        String message =
            entry.getLabel() + " is not a valid configuration key for " + target.getLabel();
        env.getListener().handle(Event.error(TargetUtils.getLocationMaybe(target), message));
        throw new DependencyEvaluationException(new ConfiguredValueCreationException(message));
      }
    }

    return configConditions.build();
  }

  /***
   * Resolves the targets referenced in depValueNames and returns their ConfiguredTarget
   * instances.
   *
   * <p>Returns null if not all instances are available yet.
   *
   */
  @Nullable
  private static Map<SkyKey, ConfiguredTarget> resolveConfiguredTargetDependencies(
      Environment env, Collection<Dependency> deps, Target target,
      NestedSetBuilder<Package> transitivePackages)
      throws DependencyEvaluationException {
    boolean ok = !env.valuesMissing();
    String message = null;
    Iterable<SkyKey> depKeys = Iterables.transform(deps, TO_KEYS);
    Map<SkyKey, ValueOrException2<NoSuchTargetException,
        NoSuchPackageException>> depValuesOrExceptions = env.getValuesOrThrow(depKeys,
            NoSuchTargetException.class, NoSuchPackageException.class);
    Map<SkyKey, ConfiguredTarget> depValues = new HashMap<>(depValuesOrExceptions.size());
    SkyKey childKey = null;
    NoSuchThingException transitiveChildException = null;
    for (Map.Entry<SkyKey, ValueOrException2<NoSuchTargetException, NoSuchPackageException>> entry
        : depValuesOrExceptions.entrySet()) {
      ConfiguredTargetKey depKey = (ConfiguredTargetKey) entry.getKey().argument();
      LabelAndConfiguration depLabelAndConfiguration = LabelAndConfiguration.of(
          depKey.getLabel(), depKey.getConfiguration());
      Label depLabel = depLabelAndConfiguration.getLabel();
      ConfiguredTargetValue depValue = null;
      NoSuchThingException directChildException = null;
      try {
        depValue = (ConfiguredTargetValue) entry.getValue().get();
      } catch (NoSuchTargetException e) {
        if (depLabel.equals(e.getLabel())) {
          directChildException = e;
        } else {
          childKey = entry.getKey();
          transitiveChildException = e;
        }
      } catch (NoSuchPackageException e) {
        if (depLabel.getPackageIdentifier().equals(e.getPackageId())) {
          directChildException = e;
        } else {
          childKey = entry.getKey();
          transitiveChildException = e;
        }
      }
      // If an exception wasn't caused by a direct child target value, we'll treat it the same
      // as any other missing dep by setting ok = false below, and returning null at the end.
      if (directChildException != null) {
        // Only update messages for missing targets we depend on directly.
        message = TargetUtils.formatMissingEdge(target, depLabel, directChildException);
        env.getListener().handle(Event.error(TargetUtils.getLocationMaybe(target), message));
      }

      if (depValue == null) {
        ok = false;
      } else {
        depValues.put(entry.getKey(), depValue.getConfiguredTarget());
        transitivePackages.addTransitive(depValue.getTransitivePackages());
      }
    }
    if (message != null) {
      throw new DependencyEvaluationException(new NoSuchTargetException(message));
    }
    if (childKey != null) {
      throw new DependencyEvaluationException(childKey, transitiveChildException);
    }
    if (!ok) {
      return null;
    } else {
      return depValues;
    }
  }


  @Override
  public String extractTag(SkyKey skyKey) {
    return Label.print(((ConfiguredTargetKey) skyKey.argument()).getLabel());
  }

  @Nullable
  private ConfiguredTargetValue createConfiguredTarget(SkyframeBuildView view,
      Environment env, Target target, BuildConfiguration configuration,
      ListMultimap<Attribute, ConfiguredTarget> depValueMap,
      Set<ConfigMatchingProvider> configConditions,
      NestedSetBuilder<Package> transitivePackages)
      throws ConfiguredTargetFunctionException, InterruptedException {
    StoredEventHandler events = new StoredEventHandler();
    BuildConfiguration ownerConfig = (configuration == null)
        ? null : configuration.getArtifactOwnerConfiguration();
    CachingAnalysisEnvironment analysisEnvironment = view.createAnalysisEnvironment(
        new ConfiguredTargetKey(target.getLabel(), ownerConfig), false,
        events, env, configuration);
    if (env.valuesMissing()) {
      return null;
    }

    ConfiguredTarget configuredTarget = view.createConfiguredTarget(target, configuration,
        analysisEnvironment, depValueMap, configConditions);

    events.replayOn(env.getListener());
    if (events.hasErrors()) {
      analysisEnvironment.disable(target);
      throw new ConfiguredTargetFunctionException(new ConfiguredValueCreationException(
              "Analysis of target '" + target.getLabel() + "' failed; build aborted"));
    }
    Preconditions.checkState(!analysisEnvironment.hasErrors(),
        "Analysis environment hasError() but no errors reported");
    if (env.valuesMissing()) {
      return null;
    }

    analysisEnvironment.disable(target);
    Preconditions.checkNotNull(configuredTarget, target);

    try {
      return new ConfiguredTargetValue(configuredTarget,
          filterSharedActionsAndThrowIfConflict(analysisEnvironment.getRegisteredActions()),
          transitivePackages.build());
    } catch (ActionConflictException e) {
      throw new ConfiguredTargetFunctionException(e);
    }
  }

  static Map<Artifact, Action> filterSharedActionsAndThrowIfConflict(Iterable<Action> actions)
      throws ActionConflictException {
    Map<Artifact, Action> generatingActions = new HashMap<>();
    for (Action action : actions) {
      for (Artifact artifact : action.getOutputs()) {
        Action previousAction = generatingActions.put(artifact, action);
        if (previousAction != null && previousAction != action
            && !Actions.canBeShared(previousAction, action)) {
          throw new ActionConflictException(artifact, previousAction, action);
        }
      }
    }
    return generatingActions;
  }

  /**
   * An exception indicating that there was a problem during the construction of
   * a ConfiguredTargetValue.
   */
  public static final class ConfiguredValueCreationException extends Exception {

    public ConfiguredValueCreationException(String message) {
      super(message);
    }
  }

  /**
   * Used to declare all the exception types that can be wrapped in the exception thrown by
   * {@link ConfiguredTargetFunction#compute}.
   */
  public static final class ConfiguredTargetFunctionException extends SkyFunctionException {
    public ConfiguredTargetFunctionException(NoSuchTargetException e) {
      super(e, Transience.PERSISTENT);
    }

    public ConfiguredTargetFunctionException(
        BuildFileContainsErrorsException e, Transience transience) {
      super(e, transience);
    }

    private ConfiguredTargetFunctionException(ConfiguredValueCreationException error) {
      super(error, Transience.PERSISTENT);
    }

    private ConfiguredTargetFunctionException(ActionConflictException e) {
      super(e, Transience.PERSISTENT);
    }

    private ConfiguredTargetFunctionException(
        @Nullable SkyKey childKey, Exception transitiveError) {
      super(transitiveError, childKey);
    }
  }
}
