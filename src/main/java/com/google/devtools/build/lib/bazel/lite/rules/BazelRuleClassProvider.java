// Copyright 2016 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.bazel.lite.rules;

import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.AliasProvider;
import com.google.devtools.build.lib.analysis.BaseRuleClasses;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider.Builder;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider.DeprecationValidator;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider.PrerequisiteValidator;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider.RuleSet;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.config.ConfigRuleClasses;
import com.google.devtools.build.lib.analysis.constraints.EnvironmentRule;
import com.google.devtools.build.lib.bazel.config.BazelConfigurationCollection;
import com.google.devtools.build.lib.bazel.lite.rules.BazelToolchainLookup.BazelToolchainLookupRule;
import com.google.devtools.build.lib.bazel.rules.common.BazelFilegroupRule;
import com.google.devtools.build.lib.bazel.rules.config.BazelConfiguration;
import com.google.devtools.build.lib.bazel.rules.cpp.BazelCcBinaryRule;
import com.google.devtools.build.lib.bazel.rules.cpp.BazelCcIncLibraryRule;
import com.google.devtools.build.lib.bazel.rules.cpp.BazelCcLibraryRule;
import com.google.devtools.build.lib.bazel.rules.cpp.BazelCcTestRule;
import com.google.devtools.build.lib.bazel.rules.cpp.BazelCppRuleClasses;
import com.google.devtools.build.lib.bazel.rules.genrule.BazelGenRuleRule;
import com.google.devtools.build.lib.bazel.rules.sh.BazelShBinaryRule;
import com.google.devtools.build.lib.bazel.rules.sh.BazelShLibraryRule;
import com.google.devtools.build.lib.bazel.rules.sh.BazelShRuleClasses;
import com.google.devtools.build.lib.bazel.rules.sh.BazelShTestRule;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.NonconfigurableAttributeMapper;
import com.google.devtools.build.lib.packages.PackageGroup;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.rules.Alias.AliasRule;
import com.google.devtools.build.lib.rules.cpp.CcIncLibraryRule;
import com.google.devtools.build.lib.rules.cpp.CcToolchainRule;
import com.google.devtools.build.lib.rules.cpp.CcToolchainSuiteRule;
import com.google.devtools.build.lib.rules.cpp.CppBuildInfo;
import com.google.devtools.build.lib.rules.cpp.CppConfigurationLoader;
import com.google.devtools.build.lib.rules.cpp.CppOptions;
import com.google.devtools.build.lib.rules.extra.ActionListenerRule;
import com.google.devtools.build.lib.rules.extra.ExtraActionRule;
import com.google.devtools.build.lib.rules.genquery.GenQueryRule;
import com.google.devtools.build.lib.rules.repository.BindRule;
import com.google.devtools.build.lib.rules.repository.LocalRepositoryRule;
import com.google.devtools.build.lib.rules.repository.NewLocalRepositoryRule;
import com.google.devtools.build.lib.rules.repository.WorkspaceBaseRule;
import com.google.devtools.build.lib.rules.test.TestSuiteRule;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.util.ResourceFileLoader;
import java.io.IOException;

/**
 * A rule class provider implementing the rules Bazel knows.
 */
public class BazelRuleClassProvider {
  public static final String TOOLS_REPOSITORY = "@bazel_tools";

  /** Used by the build encyclopedia generator. */
  public static ConfiguredRuleClassProvider create() {
    ConfiguredRuleClassProvider.Builder builder =
        new ConfiguredRuleClassProvider.Builder();
    builder.setToolsRepository(TOOLS_REPOSITORY);
    setup(builder);
    return builder.build();
  }

  private static class BazelPrerequisiteValidator implements PrerequisiteValidator {
    @Override
    public void validate(RuleContext.Builder context,
        ConfiguredTarget prerequisite, Attribute attribute) {
      validateDirectPrerequisiteVisibility(context, prerequisite, attribute.getName());
      validateDirectPrerequisiteForTestOnly(context, prerequisite);
      DeprecationValidator.validateDirectPrerequisiteForDeprecation(
          context, context.getRule(), prerequisite, context.forAspect());
    }

    private void validateDirectPrerequisiteVisibility(
        RuleContext.Builder context, ConfiguredTarget prerequisite, String attrName) {
      Rule rule = context.getRule();
      if (rule.getLabel().getPackageFragment().equals(Label.EXTERNAL_PACKAGE_NAME)) {
        // //external: labels are special. They have access to everything and visibility is checked
        // at the edge that points to the //external: label.
        return;
      }
      Target prerequisiteTarget = prerequisite.getTarget();
      Label prerequisiteLabel = prerequisiteTarget.getLabel();
      if (!context.getRule().getLabel().getPackageIdentifier().equals(
              prerequisite.getTarget().getLabel().getPackageIdentifier())
          && !context.isVisible(prerequisite)) {
        if (!context.getConfiguration().checkVisibility()) {
          context.ruleWarning(String.format("Target '%s' violates visibility of target "
              + "'%s'. Continuing because --nocheck_visibility is active",
              rule.getLabel(), prerequisiteLabel));
        } else {
          // Oddly enough, we use reportError rather than ruleError here.
          context.reportError(rule.getLocation(),
              String.format("Target '%s' is not visible from target '%s'%s. Check "
                  + "the visibility declaration of the former target if you think "
                  + "the dependency is legitimate",
                  prerequisiteLabel,
                  rule.getLabel(),
                  AliasProvider.printVisibilityChain(prerequisite)));
        }
      }

      if (prerequisiteTarget instanceof PackageGroup && !attrName.equals("visibility")) {
        context.reportError(
            rule.getAttributeLocation(attrName),
            "in "
                + attrName
                + " attribute of "
                + rule.getRuleClass()
                + " rule "
                + rule.getLabel()
                + ": package group '"
                + prerequisiteLabel
                + "' is misplaced here "
                + "(they are only allowed in the visibility attribute)");
      }
    }

    private void validateDirectPrerequisiteForTestOnly(
        RuleContext.Builder context, ConfiguredTarget prerequisite) {
      Rule rule = context.getRule();

      if (rule.getRuleClassObject().canHaveAnyProvider()) {
        // testonly-ness will be checked directly between the depender and the target of the alias;
        // getTarget() called by the depender will not return the alias rule, but its actual target
        return;
      }

      Target prerequisiteTarget = prerequisite.getTarget();
      Label prerequisiteLabel = prerequisiteTarget.getLabel();
      String thisPackage = rule.getLabel().getPackageName();

      if (isTestOnlyRule(prerequisiteTarget) && !isTestOnlyRule(rule)) {
        String message = "non-test target '" + rule.getLabel() + "' depends on testonly target '"
            + prerequisiteLabel + "'" + AliasProvider.printVisibilityChain(prerequisite)
            + " and doesn't have testonly attribute set";
        if (thisPackage.startsWith("experimental/")) {
          context.ruleWarning(message);
        } else {
          context.ruleError(message);
        }
      }
    }

    private static boolean isTestOnlyRule(Target target) {
      return (target instanceof Rule)
          && (NonconfigurableAttributeMapper.of((Rule) target)).get("testonly", Type.BOOLEAN);
    }
  }

  public static void setup(ConfiguredRuleClassProvider.Builder builder) {
    BAZEL_SETUP.init(builder);
    CORE_RULES.init(builder);
    CORE_WORKSPACE_RULES.init(builder);
    BASIC_RULES.init(builder);
    SH_RULES.init(builder);
    CPP_RULES.init(builder);
    EXTRA_ACTION_RULES.init(builder);
    VARIOUS_WORKSPACE_RULES.init(builder);

    // This rule is a little special: it needs to depend on every configuration fragment that has
    // Make variables, so we can't put it in any of the above buckets.
    builder.addRuleDefinition(new BazelToolchainLookupRule());
  }

  public static final RuleSet BAZEL_SETUP =
      new RuleSet() {
        @Override
        public void init(Builder builder) {
          builder
              .setProductName("bazel")
              .setConfigurationCollectionFactory(new BazelConfigurationCollection())
              .setPrelude("//tools/build_rules:prelude_bazel")
              .setRunfilesPrefix(Label.DEFAULT_REPOSITORY_DIRECTORY)
              .setPrerequisiteValidator(new BazelPrerequisiteValidator());

          builder.setUniversalConfigurationFragment(BazelConfiguration.class);
          builder.addConfigurationFragment(new BazelConfiguration.Loader());
          builder.addConfigurationOptions(BuildConfiguration.Options.class);
        }

        @Override
        public ImmutableList<RuleSet> requires() {
          return ImmutableList.of();
        }
      };

  public static final RuleSet CORE_RULES =
      new RuleSet() {
        @Override
        public void init(Builder builder) {
          builder.addRuleDefinition(new BaseRuleClasses.BaseRule());
          builder.addRuleDefinition(new BaseRuleClasses.RuleBase());
          builder.addRuleDefinition(new BaseRuleClasses.BinaryBaseRule());
          builder.addRuleDefinition(new BaseRuleClasses.TestBaseRule());
          builder.addRuleDefinition(new BaseRuleClasses.ErrorRule());
        }

        @Override
        public ImmutableList<RuleSet> requires() {
          return ImmutableList.of();
        }
      };

  public static final RuleSet BASIC_RULES =
      new RuleSet() {
        @Override
        public void init(Builder builder) {
          builder.addRuleDefinition(new EnvironmentRule());

          builder.addRuleDefinition(new ConfigRuleClasses.ConfigBaseRule());
          builder.addRuleDefinition(new ConfigRuleClasses.ConfigSettingRule());

          builder.addRuleDefinition(new AliasRule());
          builder.addRuleDefinition(new BazelFilegroupRule());
          builder.addRuleDefinition(new TestSuiteRule());
          builder.addRuleDefinition(new BazelGenRuleRule());
          builder.addRuleDefinition(new GenQueryRule());

          try {
            builder.addWorkspaceFilePrefix(
                ResourceFileLoader.loadResource(BazelRuleClassProvider.class, "tools.WORKSPACE"));
          } catch (IOException e) {
            throw new IllegalStateException(e);
          }
        }

        @Override
        public ImmutableList<RuleSet> requires() {
          return ImmutableList.of(CORE_RULES);
        }
      };

  public static final RuleSet CORE_WORKSPACE_RULES =
      new RuleSet() {
        @Override
        public void init(Builder builder) {
          builder.addRuleDefinition(new BindRule());
          builder.addRuleDefinition(new WorkspaceBaseRule());
          builder.addRuleDefinition(new LocalRepositoryRule());
        }

        @Override
        public ImmutableList<RuleSet> requires() {
          return ImmutableList.of(CORE_RULES);
        }
      };

  public static final RuleSet SH_RULES =
      new RuleSet() {
        @Override
        public void init(Builder builder) {
          builder.addRuleDefinition(new BazelShRuleClasses.ShRule());
          builder.addRuleDefinition(new BazelShLibraryRule());
          builder.addRuleDefinition(new BazelShBinaryRule());
          builder.addRuleDefinition(new BazelShTestRule());
        }

        @Override
        public ImmutableList<RuleSet> requires() {
          return ImmutableList.of(CORE_RULES);
        }
      };

  public static final RuleSet CPP_RULES =
      new RuleSet() {
        @Override
        public void init(Builder builder) {
          builder.addConfig(
              CppOptions.class, new CppConfigurationLoader(Functions.<String>identity()));

          builder.addBuildInfoFactory(new CppBuildInfo());

          builder.addRuleDefinition(new CcToolchainRule());
          builder.addRuleDefinition(new CcToolchainSuiteRule());
          builder.addRuleDefinition(new CcIncLibraryRule());
          builder.addRuleDefinition(new BazelCppRuleClasses.CcLinkingRule());
          builder.addRuleDefinition(new BazelCppRuleClasses.CcDeclRule());
          builder.addRuleDefinition(new BazelCppRuleClasses.CcBaseRule());
          builder.addRuleDefinition(new BazelCppRuleClasses.CcRule());
          builder.addRuleDefinition(new BazelCppRuleClasses.CcBinaryBaseRule());
          builder.addRuleDefinition(new BazelCcBinaryRule());
          builder.addRuleDefinition(new BazelCcTestRule());
          builder.addRuleDefinition(new BazelCppRuleClasses.CcLibraryBaseRule());
          builder.addRuleDefinition(new BazelCcLibraryRule());
          builder.addRuleDefinition(new BazelCcIncLibraryRule());
        }

        @Override
        public ImmutableList<RuleSet> requires() {
          return ImmutableList.of(CORE_RULES);
        }
      };

  public static final RuleSet EXTRA_ACTION_RULES =
      new RuleSet() {
        @Override
        public void init(Builder builder) {
          builder.addRuleDefinition(new ExtraActionRule());
          builder.addRuleDefinition(new ActionListenerRule());
        }

        @Override
        public ImmutableList<RuleSet> requires() {
          return ImmutableList.of(CORE_RULES);
        }
      };

  public static final RuleSet VARIOUS_WORKSPACE_RULES =
      new RuleSet() {
        @Override
        public void init(Builder builder) {
          builder.addRuleDefinition(new NewLocalRepositoryRule());
        }

        @Override
        public ImmutableList<RuleSet> requires() {
          return ImmutableList.of(CORE_RULES, CORE_WORKSPACE_RULES);
        }
      };
}
