// Copyright 2015 Google Inc. All rights reserved.
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

package com.google.devtools.build.lib.analysis.constraints;

import static com.google.devtools.build.lib.analysis.BaseRuleClasses.COMPATIBLE_ENVIRONMENT_ATTR;
import static com.google.devtools.build.lib.analysis.BaseRuleClasses.RESTRICTED_ENVIRONMENT_ATTR;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.analysis.BaseRuleClasses;
import com.google.devtools.build.lib.analysis.RuleConfiguredTarget;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.constraints.EnvironmentCollection.EnvironmentWithGroup;
import com.google.devtools.build.lib.packages.AttributeMap;
import com.google.devtools.build.lib.packages.EnvironmentGroup;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.syntax.Label;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Implementation of the semantics of Bazel's constraint specification and enforcement system.
 *
 * <p>This is how the system works:
 *
 * <p>All build rules can declare which "environments" they can be built for, where an "environment"
 * is a label instance of an {@link EnvironmentRule} rule declared in a BUILD file. There are
 * three ways to do this:
 *
 * <ul>
 *   <li>Through a "restricted to" attribute setting
 *   ({@link BaseRuleClasses#RESTRICTED_ENVIRONMENT_ATTR}). This is the most direct form of
 *   specification - it declares the exact set of environments the rule supports (for its group -
 *   see precise details below).
 *   <li>Through a "compatible with" attribute setting
 *   ({@link BaseRuleClasses#COMPATIBLE_ENVIRONMENT_ATTR}. This declares <b>additional</b>
 *   environments a rule supports in addition to "standard" environments that are supported by
 *   default (see below).
 *   <li>Through "default" specifications in {@link EnvironmentGroup} rules. Every environment
 *   belongs to a group of thematically related peers (e.g. "target architectures", "JDK versions",
 *   or "mobile devices"). An environment group's definition includes which of these
 *   environments should be supported "by default" if not otherwise specified by one of the above
 *   mechanisms. In particular, a rule with no environment-related attributes automatically
 *   inherits all defaults.
 * </ul>
 *
 * <p>Groups exist to model the idea that some environments are related while others have nothing
 * to do with each other. Say, for example, we want to say a rule works for PowerPC platforms but
 * not x86. We can do so by setting its "restricted to" attribute to
 * {@code ['//sample/path:powerpc']}. Because both PowerPC and x86 are in the same
 * "target architectures" group, this setting removes x86 from the set of supported environments.
 * But since JDK support belongs to its own group ("JDK versions") it says nothing about which JDK
 * the rule supports.
 *
 * <p>More precisely, if a rule has a "restricted to" value of [A, B, C], this removes support
 * for all default environments D such that group(D) is in [group(A), group(B), group(C)] AND
 * D is not in [A, B, C] (in other words, D isn't explicitly opted back in). The rule's full
 * set of supported environments thus becomes [A, B, C] + all defaults that belong to unrelated
 * groups.
 *
 * <p>If the rule has a "compatible with" value of [E, F, G], these are unconditionally
 * added to its set of supported environments (in addition to the results from above).
 *
 * <p>An environment may not appear in both a rule's "restricted to" and "compatible with" values.
 * If two environments belong to the same group, they must either both be in "restricted to",
 * both be in "compatible with", or not explicitly specified.
 *
 * <p>Given all the above, constraint enforcement is this: rule A can depend on rule B if, for
 * every environment A supports, B also supports that environment.
 */
public class ConstraintSemantics {
  private ConstraintSemantics() {
  }

  /**
   * Returns the set of environments this rule supports, applying the logic described in
   * {@link ConstraintSemantics}.
   *
   * <p>Note this set is <b>not complete</b> - it doesn't include environments from groups we don't
   * "know about". Environments and groups can be declared in any package. If the rule includes
   * no references to that package, then it simply doesn't know anything about them. But the
   * constraint semantics say the rule should support the defaults for that group. We encode this
   * implicitly: given the returned set, for any group that's not in the set the rule is also
   * considered to support that group's defaults.
   *
   * @param ruleContext analysis context for the rule. A rule error is triggered here if
   *     invalid constraint settings are discovered.
   * @return the environments this rule supports, not counting defaults "unknown" to this rule
   *     as described above. Returns null if any errors are encountered.
   */
  @Nullable
  public static EnvironmentCollection getSupportedEnvironments(RuleContext ruleContext) {
    if (!validateAttributes(ruleContext)) {
      return null;
    }

    EnvironmentCollection.Builder supportedEnvironments = new EnvironmentCollection.Builder();

    EnvironmentCollection restrictionEnvironments =
        collectRestrictionEnvironments(ruleContext, supportedEnvironments);
    EnvironmentCollection compatibilityEnvironments =
        collectCompatibilityEnvironments(ruleContext, supportedEnvironments);
    if (!validateEnvironmentSpecifications(ruleContext, restrictionEnvironments,
        compatibilityEnvironments)) {
      return null;
    }

    return supportedEnvironments.build();
  }

  /**
   * Validity-checks this rule's constraint-related attributes. Returns true if all is good,
   * returns false and reports appropriate errors if there are any problems.
   */
  private static boolean validateAttributes(RuleContext ruleContext) {
    AttributeMap attributes = ruleContext.attributes();

    // Report an error if "restricted to" is explicitly set to nothing. Even if this made
    // conceptual sense, we don't know which groups we should apply that to.
    List<? extends TransitiveInfoCollection> restrictionEnvironments = ruleContext
        .getPrerequisites(RESTRICTED_ENVIRONMENT_ATTR, RuleConfiguredTarget.Mode.DONT_CHECK);
    if (restrictionEnvironments.isEmpty()
        && attributes.isAttributeValueExplicitlySpecified(RESTRICTED_ENVIRONMENT_ATTR)) {
      ruleContext.attributeError(RESTRICTED_ENVIRONMENT_ATTR, "attribute cannot be empty");
      return false;
    }

    return true;
  }

  /**
   * Adds environments specified in the "restricted to" attribute to the set of supported
   * environments and returns the environments added.
   */
  private static EnvironmentCollection collectRestrictionEnvironments(RuleContext ruleContext,
      EnvironmentCollection.Builder supportedEnvironments) {
    return collectEnvironments(ruleContext, RESTRICTED_ENVIRONMENT_ATTR, supportedEnvironments);
  }

  /**
   * Adds environments specified in the "compatible with" attribute to the set of supported
   * environments, along with all defaults from the groups they belong to. Returns these
   * environments, not including the defaults.
   */
  private static EnvironmentCollection collectCompatibilityEnvironments(RuleContext ruleContext,
      EnvironmentCollection.Builder supportedEnvironments) {
    EnvironmentCollection compatibilityEnvironments =
        collectEnvironments(ruleContext, COMPATIBLE_ENVIRONMENT_ATTR, supportedEnvironments);
    for (EnvironmentGroup group : compatibilityEnvironments.getGroups()) {
      supportedEnvironments.putAll(group, group.getDefaults());
    }
    return compatibilityEnvironments;
  }

  /**
   * Adds environments specified by the given attribute to the set of supported environments and
   * returns the environments added.
   */
  private static EnvironmentCollection collectEnvironments(RuleContext ruleContext,
      String attrName, EnvironmentCollection.Builder supportedEnvironments) {
    EnvironmentCollection.Builder environments = new EnvironmentCollection.Builder();
    for (TransitiveInfoCollection envTarget :
        ruleContext.getPrerequisites(attrName, RuleConfiguredTarget.Mode.DONT_CHECK)) {
      EnvironmentWithGroup envInfo = resolveEnvironment(envTarget);
      environments.put(envInfo.group(), envInfo.environment());
      supportedEnvironments.put(envInfo.group(), envInfo.environment());
    }
    return environments.build();
  }

  /**
   * Validity-checks that no group has its environment referenced in both the "compatible with" and
   * "restricted to" attributes. Returns true if all is good, returns false and reports appropriate
   * errors if there are any problems.
   */
  private static boolean validateEnvironmentSpecifications(RuleContext ruleContext,
      EnvironmentCollection restrictionEnvironments,
      EnvironmentCollection compatibilityEnvironments) {

    ImmutableCollection<EnvironmentGroup> restrictionGroups = restrictionEnvironments.getGroups();
    boolean hasErrors = false;

    for (EnvironmentGroup group : compatibilityEnvironments.getGroups()) {
      if (restrictionGroups.contains(group)) {
        // To avoid error-spamming the user, when we find a conflict we only report one example
        // environment from each attribute for that group.
        Label compatibilityEnv = compatibilityEnvironments.getEnvironments(group).iterator().next();
        Label restrictionEnv = restrictionEnvironments.getEnvironments(group).iterator().next();

        if (compatibilityEnv.equals(restrictionEnv)) {
          ruleContext.attributeError(COMPATIBLE_ENVIRONMENT_ATTR, compatibilityEnv
              + " cannot appear both here and in " + RESTRICTED_ENVIRONMENT_ATTR);
        } else {
          ruleContext.attributeError(COMPATIBLE_ENVIRONMENT_ATTR, compatibilityEnv + " and "
              + restrictionEnv + " belong to the same environment group. They should be declared "
              + "together either here or in " + RESTRICTED_ENVIRONMENT_ATTR);
        }
        hasErrors = true;
      }
    }

    return !hasErrors;
  }

  /**
   * Performs constraint checking on the given rule's dependencies and reports any errors.
   *
   * @param ruleContext the rule to analyze
   * @param supportedEnvironments the rule's supported environments, as defined by the return
   *     value of {@link #getSupportedEnvironments}. In particular, for any environment group that's
   *     not in this collection, the rule is assumed to support the defaults for that group.
   */
  public static void checkConstraints(RuleContext ruleContext,
      EnvironmentCollection supportedEnvironments) {

    Set<EnvironmentGroup> knownGroups = supportedEnvironments.getGroups();

    for (TransitiveInfoCollection dependency : getAllPrerequisites(ruleContext)) {
      SupportedEnvironmentsProvider depProvider =
          dependency.getProvider(SupportedEnvironmentsProvider.class);
      if (depProvider == null) {
        // Input files (InputFileConfiguredTarget) don't support environments. We may subsequently
        // opt them into constraint checking, but for now just pass them by.
        continue;
      }
      Collection<Label> depEnvironments = depProvider.getEnvironments().getEnvironments();
      Set<EnvironmentGroup> groupsKnownToDep = depProvider.getEnvironments().getGroups();

      // Environments we support that the dependency does not support.
      Set<Label> disallowedEnvironments = new LinkedHashSet<>();

      // For every environment we support, either the dependency must also support it OR it must be
      // a default for a group the dependency doesn't know about.
      for (EnvironmentWithGroup supportedEnv : supportedEnvironments.getGroupedEnvironments()) {
        EnvironmentGroup group = supportedEnv.group();
        Label environment = supportedEnv.environment();
        if (!depEnvironments.contains(environment)
          && (groupsKnownToDep.contains(group) || !group.isDefault(environment))) {
          disallowedEnvironments.add(environment);
        }
      }

      // For any environment group we don't know about, we implicitly support its defaults. Check
      // that the dep does, too.
      for (EnvironmentGroup depGroup : groupsKnownToDep) {
        if (!knownGroups.contains(depGroup)) {
          for (Label defaultEnv : depGroup.getDefaults()) {
            if (!depEnvironments.contains(defaultEnv)) {
              disallowedEnvironments.add(defaultEnv);
            }
          }
        }
      }

      // Report errors on bad environments.
      if (!disallowedEnvironments.isEmpty()) {
        ruleContext.ruleError("dependency " + dependency.getLabel()
            + " doesn't support expected environment"
            + (disallowedEnvironments.size() == 1 ? "" : "s")
            + ": " + Joiner.on(", ").join(disallowedEnvironments));
      }
    }
  }

  /**
   * Returns the environment and its group. An {@link Environment} rule only "supports" one
   * environment: itself. Extract that from its more generic provider interface and sanity
   * check that that's in fact what we see.
   */
  private static EnvironmentWithGroup resolveEnvironment(TransitiveInfoCollection envRule) {
    SupportedEnvironmentsProvider prereq =
        Preconditions.checkNotNull(envRule.getProvider(SupportedEnvironmentsProvider.class));
    return Iterables.getOnlyElement(prereq.getEnvironments().getGroupedEnvironments());
  }

  /**
   * Returns all dependencies that should be constraint-checked against the current rule.
   */
  private static Iterable<TransitiveInfoCollection> getAllPrerequisites(RuleContext ruleContext) {
    Set<TransitiveInfoCollection> prerequisites = new LinkedHashSet<>();
    AttributeMap attributes = ruleContext.attributes();

    for (String attr : attributes.getAttributeNames()) {
      Type<?> attrType = attributes.getAttributeType(attr);
      // TODO(bazel-team): support specifying which attributes are subject to constraint checking
      if ((attrType == Type.LABEL || attrType == Type.LABEL_LIST)
          && !BaseRuleClasses.isConstraintAttribute(attr)
          && !attr.equals("visibility")) {
        prerequisites.addAll(
            ruleContext.getPrerequisites(attr, RuleConfiguredTarget.Mode.DONT_CHECK));
      }
    }
    return prerequisites;
  }
}
