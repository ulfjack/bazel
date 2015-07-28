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
package com.google.devtools.build.lib.analysis;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.config.ConfigMatchingProvider;
import com.google.devtools.build.lib.collect.ImmutableSortedKeyListMultimap;
import com.google.devtools.build.lib.packages.AspectDefinition;
import com.google.devtools.build.lib.packages.AspectFactory;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.Attribute.ConfigurationTransition;
import com.google.devtools.build.lib.packages.Attribute.LateBoundDefault;
import com.google.devtools.build.lib.packages.Attribute.SplitTransition;
import com.google.devtools.build.lib.packages.AttributeMap;
import com.google.devtools.build.lib.packages.EnvironmentGroup;
import com.google.devtools.build.lib.packages.InputFile;
import com.google.devtools.build.lib.packages.NoSuchThingException;
import com.google.devtools.build.lib.packages.OutputFile;
import com.google.devtools.build.lib.packages.PackageGroup;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.skyframe.ConfiguredTargetKey;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.EvalUtils;
import com.google.devtools.build.lib.syntax.Label;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.annotation.Nullable;

/**
 * Resolver for dependencies between configured targets.
 *
 * <p>Includes logic to derive the right configurations depending on transition type.
 */
public abstract class DependencyResolver {
  /**
   * A dependency of a configured target through a label.
   *
   * <p>Includes the target and the configuration of the dependency configured target and any
   * aspects that may be required.
   *
   * <p>Note that the presence of an aspect here does not necessarily mean that it will be created.
   * They will be filtered based on the {@link TransitiveInfoProvider} instances their associated
   * configured targets have (we cannot do that here because the configured targets are not
   * available yet). No error or warning is reported in this case, because it is expected that rules
   * sometimes over-approximate the providers they supply in their definitions.
   */
  public static final class Dependency {

    /**
     * Returns the {@link ConfiguredTargetKey} for a direct dependency.
     *
     * <p>Essentially the same information as {@link Dependency} minus the aspects.
     */
    public static final Function<Dependency, ConfiguredTargetKey>
        TO_CONFIGURED_TARGET_KEY = new Function<Dependency, ConfiguredTargetKey>() {
          @Override
          public ConfiguredTargetKey apply(Dependency input) {
            return new ConfiguredTargetKey(input.getLabel(), input.getConfiguration());
          }
        };

    private final Label label;

    @Nullable private final BuildConfiguration configuration;

    private final ImmutableSet<Class<? extends ConfiguredAspectFactory>> aspects;

    public Dependency(
        Label label,
        @Nullable BuildConfiguration configuration,
        ImmutableSet<Class<? extends ConfiguredAspectFactory>> aspects) {
      this.label = Preconditions.checkNotNull(label);
      this.configuration = configuration;
      this.aspects = Preconditions.checkNotNull(aspects);
    }

    public Dependency(Label label, @Nullable BuildConfiguration configuration) {
      this(label, configuration, ImmutableSet.<Class<? extends ConfiguredAspectFactory>>of());
    }

    public Label getLabel() {
      return label;
    }

    @Nullable
    public BuildConfiguration getConfiguration() {
      return configuration;
    }

    public ImmutableSet<Class<? extends ConfiguredAspectFactory>> getAspects() {
      return aspects;
    }

    @Override
    public int hashCode() {
      return Objects.hash(label, configuration, aspects);
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof Dependency)) {
        return false;
      }
      Dependency otherDep = (Dependency) other;
      return label.equals(otherDep.label)
          && (configuration == otherDep.configuration
              || (configuration != null && configuration.equals(otherDep.configuration)))
          && aspects.equals(otherDep.aspects);
    }

    @Override
    public String toString() {
      return String.format(
          "Dependency{label=%s, configuration=%s, aspects=%s}", label, configuration, aspects);
    }
  }

  protected DependencyResolver() {
  }

  /**
   * Returns ids for dependent nodes of a given node, sorted by attribute. Note that some
   * dependencies do not have a corresponding attribute here, and we use the null attribute to
   * represent those edges. Visibility attributes are only visited if {@code visitVisibility} is
   * {@code true}.
   *
   * <p>If {@code aspect} is null, returns the dependent nodes of the configured target node
   * representing the given target and configuration, otherwise that of the aspect node accompanying
   * the aforementioned configured target node for the specified aspect.
   *
   * <p>The values are not simply labels because this also implements the first step of applying
   * configuration transitions, namely, split transitions. This needs to be done before the labels
   * are resolved because late bound attributes depend on the configuration. A good example for this
   * is @{code :cc_toolchain}.
   *
   * <p>The long-term goal is that most configuration transitions be applied here. However, in order
   * to do that, we first have to eliminate transitions that depend on the rule class of the
   * dependency.
   */
  public final ListMultimap<Attribute, Dependency> dependentNodeMap(
      TargetAndConfiguration node, AspectDefinition aspect,
      Set<ConfigMatchingProvider> configConditions)
      throws EvalException {
    Target target = node.getTarget();
    BuildConfiguration config = node.getConfiguration();
    ListMultimap<Attribute, Dependency> outgoingEdges = ArrayListMultimap.create();
    if (target instanceof OutputFile) {
      Preconditions.checkNotNull(config);
      visitTargetVisibility(node, outgoingEdges.get(null));
      Rule rule = ((OutputFile) target).getGeneratingRule();
      outgoingEdges.put(null, new Dependency(rule.getLabel(), config));
    } else if (target instanceof InputFile) {
      visitTargetVisibility(node, outgoingEdges.get(null));
    } else if (target instanceof EnvironmentGroup) {
      visitTargetVisibility(node, outgoingEdges.get(null));
    } else if (target instanceof Rule) {
      Preconditions.checkNotNull(config);
      visitTargetVisibility(node, outgoingEdges.get(null));
      Rule rule = (Rule) target;
      ListMultimap<Attribute, LabelAndConfiguration> labelMap =
          resolveAttributes(rule, aspect, config, configConditions);
      visitRule(rule, aspect, labelMap, outgoingEdges);
    } else if (target instanceof PackageGroup) {
      visitPackageGroup(node, (PackageGroup) target, outgoingEdges.get(null));
    } else {
      throw new IllegalStateException(target.getLabel().toString());
    }
    return outgoingEdges;
  }

  private ListMultimap<Attribute, LabelAndConfiguration> resolveAttributes(
      Rule rule, AspectDefinition aspect, BuildConfiguration configuration,
      Set<ConfigMatchingProvider> configConditions)
      throws EvalException {
    ConfiguredAttributeMapper attributeMap = ConfiguredAttributeMapper.of(rule, configConditions);
    attributeMap.validateAttributes();
    List<Attribute> attributes;
    if (aspect == null) {
      attributes = rule.getRuleClassObject().getAttributes();
    } else {
      attributes = new ArrayList<>();
      attributes.addAll(rule.getRuleClassObject().getAttributes());
      if (aspect != null) {
        attributes.addAll(aspect.getAttributes().values());
      }
    }

    ImmutableSortedKeyListMultimap.Builder<Attribute, LabelAndConfiguration> result =
        ImmutableSortedKeyListMultimap.builder();

    resolveExplicitAttributes(rule, configuration, attributeMap, result);
    resolveImplicitAttributes(rule, configuration, attributeMap, attributes, result);
    resolveLateBoundAttributes(rule, configuration, attributeMap, attributes, result);

    // Add the rule's visibility labels (which may come from the rule or from package defaults).
    addExplicitDeps(result, rule, "visibility", rule.getVisibility().getDependencyLabels(),
        configuration);

    // Add package default constraints when the rule doesn't explicitly declare them.
    //
    // Note that this can have subtle implications for constraint semantics. For example: say that
    // package defaults declare compatibility with ':foo' and rule R declares compatibility with
    // ':bar'. Does that mean that R is compatible with [':foo', ':bar'] or just [':bar']? In other
    // words, did R's author intend to add additional compatibility to the package defaults or to
    // override them? More severely, what if package defaults "restrict" support to just [':baz']?
    // Should R's declaration signify [':baz'] + ['bar'], [ORIGINAL_DEFAULTS] + ['bar'], or
    // something else?
    //
    // Rather than try to answer these questions with possibly confusing logic, we take the
    // simple approach of assigning the rule's "restriction" attribute to the rule-declared value if
    // it exists, else the package defaults value (and likewise for "compatibility"). This may not
    // always provide what users want, but it makes it easy for them to understand how rule
    // declarations and package defaults intermix (and how to refactor them to get what they want).
    //
    // An alternative model would be to apply the "rule declaration" / "rule class defaults"
    // relationship, i.e. the rule class' "compatibility" and "restriction" declarations are merged
    // to generate a set of default environments, then the rule's declarations are independently
    // processed on top of that. This protects against obscure coupling behavior between
    // declarations from wildly different places (e.g. it offers clear answers to the examples posed
    // above). But within the scope of a single package it seems better to keep the model simple and
    // make the user responsible for resolving ambiguities.
    if (!rule.isAttributeValueExplicitlySpecified(RuleClass.COMPATIBLE_ENVIRONMENT_ATTR)) {
      addExplicitDeps(result, rule, RuleClass.COMPATIBLE_ENVIRONMENT_ATTR,
          rule.getPackage().getDefaultCompatibleWith(), configuration);
    }
    if (!rule.isAttributeValueExplicitlySpecified(RuleClass.RESTRICTED_ENVIRONMENT_ATTR)) {
      addExplicitDeps(result, rule, RuleClass.RESTRICTED_ENVIRONMENT_ATTR,
          rule.getPackage().getDefaultRestrictedTo(), configuration);
    }

    return result.build();
  }

  /**
   * Adds new dependencies to the given rule under the given attribute name
   *
   * @param result the builder for the attribute --> dependency labels map
   * @param rule the rule being evaluated
   * @param attrName the name of the attribute to add dependency labels to
   * @param labels the dependencies to add
   * @param configuration the configuration to apply to those dependencies
   */
  private void addExplicitDeps(
      ImmutableSortedKeyListMultimap.Builder<Attribute, LabelAndConfiguration> result, Rule rule,
      String attrName, Iterable<Label> labels, BuildConfiguration configuration) {
    if (!rule.isAttrDefined(attrName, Type.LABEL_LIST)
        && !rule.isAttrDefined(attrName, Type.NODEP_LABEL_LIST)) {
      return;
    }
    Attribute attribute = rule.getRuleClassObject().getAttributeByName(attrName);
    for (Label label : labels) {
      // The configuration must be the configuration after the first transition step (applying
      // split configurations). The proper configuration (null) for package groups will be set
      // later.
      result.put(attribute, LabelAndConfiguration.of(label, configuration));
    }
  }

  private void resolveExplicitAttributes(Rule rule, final BuildConfiguration configuration,
      AttributeMap attributes,
      final ImmutableSortedKeyListMultimap.Builder<Attribute, LabelAndConfiguration> builder) {
    attributes.visitLabels(
        new AttributeMap.AcceptsLabelAttribute() {
          @Override
          public void acceptLabelAttribute(Label label, Attribute attribute) {
            String attributeName = attribute.getName();
            if (attributeName.equals("abi_deps")) {
              // abi_deps is handled specially: we visit only the branch that
              // needs to be taken based on the configuration.
              return;
            }

            if (attribute.getType() == Type.NODEP_LABEL) {
              return;
            }

            if (attribute.isImplicit() || attribute.isLateBound()) {
              return;
            }

            builder.put(attribute, LabelAndConfiguration.of(label, configuration));
          }
        });

    // TODO(bazel-team): Remove this in favor of the new configurable attributes.
    if (attributes.getAttributeDefinition("abi_deps") != null) {
      Attribute depsAttribute = attributes.getAttributeDefinition("deps");
      MakeVariableExpander.Context context = new ConfigurationMakeVariableContext(
          rule.getPackage(), configuration);
      String abi = null;
      try {
        abi = MakeVariableExpander.expand(attributes.get("abi", Type.STRING), context);
      } catch (MakeVariableExpander.ExpansionException e) {
        // Ignore this. It will be handled during the analysis phase.
      }

      if (abi != null) {
        for (Map.Entry<String, List<Label>> entry
            : attributes.get("abi_deps", Type.LABEL_LIST_DICT).entrySet()) {
          try {
            if (Pattern.matches(entry.getKey(), abi)) {
              for (Label label : entry.getValue()) {
                builder.put(depsAttribute, LabelAndConfiguration.of(label, configuration));
              }
            }
          } catch (PatternSyntaxException e) {
            // Ignore this. It will be handled during the analysis phase.
          }
        }
      }
    }
  }

  private void resolveImplicitAttributes(Rule rule, BuildConfiguration configuration,
      AttributeMap attributeMap, Iterable<Attribute> attributes,
      ImmutableSortedKeyListMultimap.Builder<Attribute, LabelAndConfiguration> builder) {
    // Since the attributes that come from aspects do not appear in attributeMap, we have to get
    // their values from somewhere else. This incidentally means that aspects attributes are not
    // configurable. It would be nice if that wasn't the case, but we'd have to revamp how
    // attribute mapping works, which is a large chunk of work.
    ImmutableSet<String> mappedAttributes = ImmutableSet.copyOf(attributeMap.getAttributeNames());
    for (Attribute attribute : attributes) {
      if (!attribute.isImplicit() || !attribute.getCondition().apply(attributeMap)) {
        continue;
      }

      if (attribute.getType() == Type.LABEL) {
        Label label = mappedAttributes.contains(attribute.getName())
            ? attributeMap.get(attribute.getName(), Type.LABEL)
            : Type.LABEL.cast(attribute.getDefaultValue(rule));

        if (label != null) {
          builder.put(attribute, LabelAndConfiguration.of(label, configuration));
        }
      } else if (attribute.getType() == Type.LABEL_LIST) {
        List<Label> labelList = mappedAttributes.contains(attribute.getName())
            ? attributeMap.get(attribute.getName(), Type.LABEL_LIST)
            : Type.LABEL_LIST.cast(attribute.getDefaultValue(rule));

        for (Label label : labelList) {
          builder.put(attribute, LabelAndConfiguration.of(label, configuration));
        }
      }
    }
  }

  private void resolveLateBoundAttributes(
      Rule rule,
      BuildConfiguration configuration,
      AttributeMap attributeMap,
      Iterable<Attribute> attributes,
      ImmutableSortedKeyListMultimap.Builder<Attribute, LabelAndConfiguration> builder)
      throws EvalException {
    for (Attribute attribute : attributes) {
      if (!attribute.isLateBound() || !attribute.getCondition().apply(attributeMap)) {
        continue;
      }

      List<BuildConfiguration> actualConfigurations = ImmutableList.of(configuration);
      if (attribute.getConfigurationTransition() instanceof SplitTransition<?>) {
        Preconditions.checkState(attribute.getConfigurator() == null);
        // TODO(bazel-team): This ends up applying the split transition twice, both here and in the
        // visitRule method below - this is not currently a problem, because the configuration graph
        // never contains nested split transitions, so the second application is idempotent.
        actualConfigurations = configuration.getSplitConfigurations(
            (SplitTransition<?>) attribute.getConfigurationTransition());
      }

      for (BuildConfiguration actualConfig : actualConfigurations) {
        @SuppressWarnings("unchecked")
        LateBoundDefault<BuildConfiguration> lateBoundDefault =
            (LateBoundDefault<BuildConfiguration>) attribute.getLateBoundDefault();
        if (lateBoundDefault.useHostConfiguration()) {
          actualConfig =
              actualConfig.getConfiguration(ConfigurationTransition.HOST);
        }
        // TODO(bazel-team): This might be too expensive - can we cache this somehow?
        if (!lateBoundDefault.getRequiredConfigurationFragments().isEmpty()) {
          if (!actualConfig.hasAllFragments(lateBoundDefault.getRequiredConfigurationFragments())) {
            continue;
          }
        }

        // TODO(bazel-team): We should check if the implementation tries to access an undeclared
        // fragment.
        Object actualValue = lateBoundDefault.getDefault(rule, actualConfig);
        if (EvalUtils.isNullOrNone(actualValue)) {
          continue;
        }
        try {
          if (attribute.getType() == Type.LABEL) {
            Label label = Type.LABEL.cast(actualValue);
            builder.put(attribute, LabelAndConfiguration.of(label, actualConfig));
          } else if (attribute.getType() == Type.LABEL_LIST) {
            for (Label label : Type.LABEL_LIST.cast(actualValue)) {
              builder.put(attribute, LabelAndConfiguration.of(label, actualConfig));
            }
          } else {
            throw new IllegalStateException(
                String.format(
                    "Late bound attribute '%s' is not a label or a label list",
                    attribute.getName()));
          }
        } catch (ClassCastException e) {
          throw new EvalException(
              rule.getLocation(),
              String.format(
                  "When computing the default value of %s, expected '%s', got '%s'",
                  attribute.getName(),
                  attribute.getType(),
                  EvalUtils.getDataTypeName(actualValue, true)));
        }
      }
    }
  }

  /**
   * A variant of {@link #dependentNodeMap} that only returns the values of the resulting map, and
   * also converts any internally thrown {@link EvalException} instances into {@link
   * IllegalStateException}.
   */
  public final Collection<Dependency> dependentNodes(
      TargetAndConfiguration node, Set<ConfigMatchingProvider> configConditions) {
    try {
      return dependentNodeMap(node, null, configConditions).values();
    } catch (EvalException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Converts the given multimap of attributes to labels into a multi map of attributes to
   * {@link Dependency} objects using the proper configuration transition for each attribute.
   *
   * @throws IllegalArgumentException if the {@code node} does not refer to a {@link Rule} instance
   */
  public final Collection<Dependency> resolveRuleLabels(
      TargetAndConfiguration node, AspectDefinition aspect, ListMultimap<Attribute,
      LabelAndConfiguration> labelMap) {
    Preconditions.checkArgument(node.getTarget() instanceof Rule);
    Rule rule = (Rule) node.getTarget();
    ListMultimap<Attribute, Dependency> outgoingEdges = ArrayListMultimap.create();
    visitRule(rule, aspect, labelMap, outgoingEdges);
    return outgoingEdges.values();
  }

  private void visitPackageGroup(TargetAndConfiguration node, PackageGroup packageGroup,
      Collection<Dependency> outgoingEdges) {
    for (Label label : packageGroup.getIncludes()) {
      try {
        Target target = getTarget(label);
        if (target == null) {
          return;
        }
        if (!(target instanceof PackageGroup)) {
          // Note that this error could also be caught in PackageGroupConfiguredTarget, but since
          // these have the null configuration, visiting the corresponding target would trigger an
          // analysis of a rule with a null configuration, which doesn't work.
          invalidPackageGroupReferenceHook(node, label);
          continue;
        }

        outgoingEdges.add(new Dependency(label, node.getConfiguration()));
      } catch (NoSuchThingException e) {
        // Don't visit targets that don't exist (--keep_going)
      }
    }
  }

  private ImmutableSet<Class<? extends ConfiguredAspectFactory>> requiredAspects(
      AspectDefinition aspect, Attribute attribute, Target target) {
    if (!(target instanceof Rule)) {
      return ImmutableSet.of();
    }

    RuleClass ruleClass = ((Rule) target).getRuleClassObject();

    // The order of this set will be deterministic. This is necessary because this order eventually
    // influences the order in which aspects are merged into the main configured target, which in
    // turn influences which aspect takes precedence if two emit the same provider (maybe this
    // should be an error)
    Set<Class<? extends AspectFactory<?, ?, ?>>> aspectCandidates = new LinkedHashSet<>();
    aspectCandidates.addAll(attribute.getAspects());
    if (aspect != null) {
      aspectCandidates.addAll(aspect.getAttributeAspects().get(attribute.getName()));
    }

    ImmutableSet.Builder<Class<? extends ConfiguredAspectFactory>> result = ImmutableSet.builder();
    for (Class<? extends AspectFactory<?, ?, ?>> candidateClass : aspectCandidates) {
      ConfiguredAspectFactory candidate =
          (ConfiguredAspectFactory) AspectFactory.Util.create(candidateClass);
      if (Sets.difference(
          candidate.getDefinition().getRequiredProviders(),
          ruleClass.getAdvertisedProviders()).isEmpty()) {
        result.add(candidateClass.asSubclass(ConfiguredAspectFactory.class));
      }
    }

    return result.build();
  }

  private void visitRule(Rule rule, AspectDefinition aspect,
      ListMultimap<Attribute, LabelAndConfiguration> labelMap,
      ListMultimap<Attribute, Dependency> outgoingEdges) {
    Preconditions.checkNotNull(labelMap);
    for (Map.Entry<Attribute, Collection<LabelAndConfiguration>> entry :
        labelMap.asMap().entrySet()) {
      Attribute attribute = entry.getKey();
      for (LabelAndConfiguration dep : entry.getValue()) {
        Label label = dep.getLabel();
        BuildConfiguration config = dep.getConfiguration();

        Target toTarget;
        try {
          toTarget = getTarget(label);
        } catch (NoSuchThingException e) {
          throw new IllegalStateException("not found: " + label + " from " + rule + " in "
              + attribute.getName());
        }
        if (toTarget == null) {
          continue;
        }
        Iterable<BuildConfiguration> toConfigurations = config.evaluateTransition(
            rule, attribute, toTarget);
        for (BuildConfiguration toConfiguration : toConfigurations) {
          outgoingEdges.put(
              entry.getKey(),
              new Dependency(label, toConfiguration, requiredAspects(aspect, attribute, toTarget)));
        }
      }
    }
  }

  private void visitTargetVisibility(TargetAndConfiguration node,
      Collection<Dependency> outgoingEdges) {
    for (Label label : node.getTarget().getVisibility().getDependencyLabels()) {
      try {
        Target visibilityTarget = getTarget(label);
        if (visibilityTarget == null) {
          return;
        }
        if (!(visibilityTarget instanceof PackageGroup)) {
          // Note that this error could also be caught in
          // AbstractConfiguredTarget.convertVisibility(), but we have an
          // opportunity here to avoid dependency cycles that result from
          // the visibility attribute of a rule referring to a rule that
          // depends on it (instead of its package)
          invalidVisibilityReferenceHook(node, label);
          continue;
        }

        // Visibility always has null configuration
        outgoingEdges.add(new Dependency(label, null));
      } catch (NoSuchThingException e) {
        // Don't visit targets that don't exist (--keep_going)
      }
    }
  }

  /**
   * Hook for the error case when an invalid visibility reference is found.
   *
   * @param node the node with the visibility attribute
   * @param label the invalid visibility reference
   */
  protected abstract void invalidVisibilityReferenceHook(TargetAndConfiguration node, Label label);

  /**
   * Hook for the error case when an invalid package group reference is found.
   *
   * @param node the package group node with the includes attribute
   * @param label the invalid reference
   */
  protected abstract void invalidPackageGroupReferenceHook(TargetAndConfiguration node,
      Label label);

  /**
   * Returns the target by the given label.
   *
   * <p>Throws {@link NoSuchThingException} if the target is known not to exist.
   *
   * <p>Returns null if the target is not ready to be returned at this moment. If getTarget returns
   * null once or more during a {@link #dependentNodeMap} call, the results of that call will be
   * incomplete. For use within Skyframe, where several iterations may be needed to discover
   * all dependencies.
   */
  @Nullable
  protected abstract Target getTarget(Label label) throws NoSuchThingException;
}
