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
package com.google.devtools.build.lib.packages;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.devtools.build.lib.packages.Attribute.ConfigurationTransition;
import com.google.devtools.build.lib.syntax.FragmentClassNameResolver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Policy used to express the set of configuration fragments which are legal for a rule or aspect
 * to access.
 */
public final class ConfigurationFragmentPolicy {

  /**
   * How to handle the case if the configuration is missing fragments that are required according
   * to the rule class.
   */
  public enum MissingFragmentPolicy {
    /**
     * Some rules are monolithic across languages, and we want them to continue to work even when
     * individual languages are disabled. Use this policy if the rule implementation is handling
     * missing fragments.
     */
    IGNORE,

    /**
     * Use this policy to generate fail actions for the target rather than failing the analysis
     * outright. Again, this is used when rules are monolithic across languages, but we still need
     * to analyze the dependent libraries. (Instead of this mechanism, consider annotating
     * attributes as unused if certain fragments are unavailable.)
     */
    CREATE_FAIL_ACTIONS,

    /**
     * Use this policy to fail the analysis of that target with an error message; this is the
     * default.
     */
    FAIL_ANALYSIS;
  }

  /**
   * Builder to construct a new ConfigurationFragmentPolicy.
   */
  public static final class Builder {
    private final Multimap<ConfigurationTransition, Class<?>> requiredConfigurationFragments
        = ArrayListMultimap.create();
    private final Map<ConfigurationTransition, ImmutableSet<String>>
        requiredConfigurationFragmentNames = new LinkedHashMap<>();
    private MissingFragmentPolicy missingFragmentPolicy = MissingFragmentPolicy.FAIL_ANALYSIS;
    private FragmentClassNameResolver fragmentNameResolver;

    /**
     * Declares that the implementation of the associated rule class requires the given
     * fragments to be present in this rule's host and target configurations.
     *
     * <p>The value is inherited by subclasses.
     */
    public Builder requiresConfigurationFragments(Collection<Class<?>> configurationFragments) {
      requiredConfigurationFragments.putAll(ConfigurationTransition.HOST, configurationFragments);
      requiredConfigurationFragments.putAll(ConfigurationTransition.NONE, configurationFragments);
      return this;
    }

    /**
     * Declares that the implementation of the associated rule class requires the given
     * fragments to be present in this rule's host and target configurations.
     *
     * <p>The value is inherited by subclasses.
     */
    public Builder requiresConfigurationFragments(Class<?>... configurationFragments) {
      Collection<Class<?>> asList = new ArrayList();
      Collections.addAll(asList, configurationFragments);
      return requiresConfigurationFragments(asList);
    }

    /**
     * Declares that the implementation of the associated rule class requires the given
     * fragments to be present in the specified configuration. Valid transition values are
     * HOST for the host configuration and NONE for the target configuration.
     *
     * <p>The value is inherited by subclasses.
     */
    public Builder requiresConfigurationFragments(ConfigurationTransition transition,
        Class<?>... configurationFragments) {
      for (Class<?> fragment : configurationFragments) {
        requiredConfigurationFragments.put(transition, fragment);
      }
      return this;
    }

    /**
     * Declares the configuration fragments that are required by this rule for the specified
     * configuration. Valid transition values are HOST for the host configuration and NONE for
     * the target configuration.
     *
     * <p>In contrast to {@link #requiresConfigurationFragments(Class...)}, this method takes the
     * names of fragments instead of their classes.
     */
    public Builder requiresConfigurationFragments(
        FragmentClassNameResolver fragmentNameResolver,
        Map<ConfigurationTransition, ImmutableSet<String>> configurationFragmentNames) {
      requiredConfigurationFragmentNames.putAll(configurationFragmentNames);
      this.fragmentNameResolver = fragmentNameResolver;
      return this;
    }

    /**
     * Sets the policy for the case where the configuration is missing required fragments (see
     * {@link #requiresConfigurationFragments}).
     */
    public Builder setMissingFragmentPolicy(MissingFragmentPolicy missingFragmentPolicy) {
      this.missingFragmentPolicy = missingFragmentPolicy;
      return this;
    }

    public ConfigurationFragmentPolicy build() {
      return new ConfigurationFragmentPolicy(
          ImmutableMultimap.copyOf(requiredConfigurationFragments),
          ImmutableMap.copyOf(requiredConfigurationFragmentNames),
          fragmentNameResolver,
          missingFragmentPolicy);
    }
  }

  /**
   * A dictionary that maps configurations (NONE for target configuration, HOST for host
   * configuration) to required configuration fragments.
   */
  private final ImmutableMultimap<ConfigurationTransition, Class<?>> requiredConfigurationFragments;

  /**
   * A dictionary that maps configurations (NONE for target configuration, HOST for host
   * configuration) to lists of names of required configuration fragments.
   */
  private final ImmutableMap<ConfigurationTransition, ImmutableSet<String>>
      requiredConfigurationFragmentNames;

  /**
   * Used to resolve the names of fragments in order to compare them to values in {@link
   * #requiredConfigurationFragmentNames}
   */
  private final FragmentClassNameResolver fragmentNameResolver;

  /**
   * What to do during analysis if a configuration fragment is missing.
   */
  private final MissingFragmentPolicy missingFragmentPolicy;

  private ConfigurationFragmentPolicy(
      ImmutableMultimap<ConfigurationTransition, Class<?>> requiredConfigurationFragments,
      ImmutableMap<ConfigurationTransition, ImmutableSet<String>>
          requiredConfigurationFragmentNames,
      FragmentClassNameResolver fragmentNameResolver,
      MissingFragmentPolicy missingFragmentPolicy) {
    this.requiredConfigurationFragments = requiredConfigurationFragments;
    this.requiredConfigurationFragmentNames = requiredConfigurationFragmentNames;
    this.fragmentNameResolver = fragmentNameResolver;
    this.missingFragmentPolicy = missingFragmentPolicy;
  }

  /**
   * The set of required configuration fragments; this contains all fragments that can be
   * accessed by the rule implementation under any configuration.
   */
  public Set<Class<?>> getRequiredConfigurationFragments() {
    return ImmutableSet.copyOf(requiredConfigurationFragments.values());
  }

  /**
   * Checks if the configuration fragment may be accessed (i.e., if it's declared) in the specified
   * configuration (target or host).
   */
  public boolean isLegalConfigurationFragment(
      Class<?> configurationFragment, ConfigurationTransition config) {
    return getRequiredConfigurationFragments().contains(configurationFragment)
        || hasLegalFragmentName(configurationFragment, config);
  }

  public boolean isLegalConfigurationFragment(Class<?> configurationFragment) {
    // NONE means target configuration.
    return isLegalConfigurationFragment(configurationFragment, ConfigurationTransition.NONE);
  }

  /**
   * Checks whether the name of the given fragment class was declared as required in the
   * specified configuration (target or host).
   */
  private boolean hasLegalFragmentName(
      Class<?> configurationFragment, ConfigurationTransition config) {
    if (fragmentNameResolver == null) {
      return false;
    }

    String name = fragmentNameResolver.resolveName(configurationFragment);
    ImmutableSet<String> fragmentNames = requiredConfigurationFragmentNames.get(config);
    return (name != null && fragmentNames != null && fragmentNames.contains(name));
  }

  /**
   * Whether to fail analysis if any of the required configuration fragments are missing.
   */
  public MissingFragmentPolicy getMissingFragmentPolicy() {
    return missingFragmentPolicy;
  }
}
