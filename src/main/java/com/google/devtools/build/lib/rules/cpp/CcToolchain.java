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
package com.google.devtools.build.lib.rules.cpp;

import static com.google.devtools.build.lib.syntax.Type.BOOLEAN;

import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Actions;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.AnalysisUtils;
import com.google.devtools.build.lib.analysis.CompilationHelper;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.LicensesProvider;
import com.google.devtools.build.lib.analysis.LicensesProvider.TargetLicense;
import com.google.devtools.build.lib.analysis.MiddlemanProvider;
import com.google.devtools.build.lib.analysis.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.License;
import com.google.devtools.build.lib.rules.RuleConfiguredTargetFactory;
import com.google.devtools.build.lib.vfs.PathFragment;

import java.util.List;

/**
 * Implementation for the cc_toolchain rule.
 */
public class CcToolchain implements RuleConfiguredTargetFactory {

  @Override
  public ConfiguredTarget create(RuleContext ruleContext) {
    final Label label = ruleContext.getLabel();
    final NestedSet<Artifact> crosstool = ruleContext.getPrerequisite("all_files", Mode.HOST)
        .getProvider(FileProvider.class).getFilesToBuild();
    final NestedSet<Artifact> crosstoolMiddleman = getFiles(ruleContext, "all_files");
    final NestedSet<Artifact> compile = getFiles(ruleContext, "compiler_files");
    final NestedSet<Artifact> strip = getFiles(ruleContext, "strip_files");
    final NestedSet<Artifact> objcopy = getFiles(ruleContext, "objcopy_files");
    final NestedSet<Artifact> link = getFiles(ruleContext, "linker_files");
    final NestedSet<Artifact> dwp = getFiles(ruleContext, "dwp_files");
    final NestedSet<Artifact> libcLink = inputsForLibcLink(ruleContext);
    String purposePrefix = Actions.escapeLabel(label) + "_";
    String runtimeSolibDirBase = "_solib_" + "_" + Actions.escapeLabel(label);
    final PathFragment runtimeSolibDir = ruleContext.getConfiguration()
        .getBinFragment().getRelative(runtimeSolibDirBase);

    CppConfiguration cppConfiguration = ruleContext.getFragment(CppConfiguration.class);
    // Static runtime inputs.
    TransitiveInfoCollection staticRuntimeLibDep = selectDep(ruleContext, "static_runtime_libs",
        cppConfiguration.getStaticRuntimeLibsLabel());
    final NestedSet<Artifact> staticRuntimeLinkInputs;
    final Artifact staticRuntimeLinkMiddleman;
    if (cppConfiguration.supportsEmbeddedRuntimes()) {
      staticRuntimeLinkInputs = staticRuntimeLibDep
          .getProvider(FileProvider.class)
          .getFilesToBuild();
    } else {
      staticRuntimeLinkInputs = NestedSetBuilder.emptySet(Order.STABLE_ORDER);
    }

    if (!staticRuntimeLinkInputs.isEmpty()) {
      NestedSet<Artifact> staticRuntimeLinkMiddlemanSet = CompilationHelper.getAggregatingMiddleman(
          ruleContext,
          purposePrefix + "static_runtime_link",
          staticRuntimeLibDep);
      staticRuntimeLinkMiddleman = staticRuntimeLinkMiddlemanSet.isEmpty()
          ? null : Iterables.getOnlyElement(staticRuntimeLinkMiddlemanSet);
    } else {
      staticRuntimeLinkMiddleman = null;
    }

    Preconditions.checkState(
        (staticRuntimeLinkMiddleman == null) == staticRuntimeLinkInputs.isEmpty());

    // Dynamic runtime inputs.
    TransitiveInfoCollection dynamicRuntimeLibDep = selectDep(ruleContext, "dynamic_runtime_libs",
        cppConfiguration.getDynamicRuntimeLibsLabel());
    final NestedSet<Artifact> dynamicRuntimeLinkInputs;
    final Artifact dynamicRuntimeLinkMiddleman;
    if (cppConfiguration.supportsEmbeddedRuntimes()) {
      NestedSetBuilder<Artifact> dynamicRuntimeLinkInputsBuilder = NestedSetBuilder.stableOrder();
      for (Artifact artifact : dynamicRuntimeLibDep
          .getProvider(FileProvider.class).getFilesToBuild()) {
        if (CppHelper.SHARED_LIBRARY_FILETYPES.matches(artifact.getFilename())) {
          dynamicRuntimeLinkInputsBuilder.add(SolibSymlinkAction.getCppRuntimeSymlink(
              ruleContext, artifact, runtimeSolibDirBase,
              ruleContext.getConfiguration()).getArtifact());
        } else {
          dynamicRuntimeLinkInputsBuilder.add(artifact);
        }
      }
      dynamicRuntimeLinkInputs = dynamicRuntimeLinkInputsBuilder.build();
    } else {
      dynamicRuntimeLinkInputs = NestedSetBuilder.emptySet(Order.STABLE_ORDER);
    }

    if (!dynamicRuntimeLinkInputs.isEmpty()) {
      List<Artifact> dynamicRuntimeLinkMiddlemanSet =
          CppHelper.getAggregatingMiddlemanForCppRuntimes(
              ruleContext,
              purposePrefix + "dynamic_runtime_link",
              dynamicRuntimeLibDep,
              runtimeSolibDirBase,
              ruleContext.getConfiguration());
      dynamicRuntimeLinkMiddleman = dynamicRuntimeLinkMiddlemanSet.isEmpty()
          ? null : Iterables.getOnlyElement(dynamicRuntimeLinkMiddlemanSet);
    } else {
      dynamicRuntimeLinkMiddleman = null;
    }

    Preconditions.checkState(
        (dynamicRuntimeLinkMiddleman == null) == dynamicRuntimeLinkInputs.isEmpty());

    CppCompilationContext.Builder contextBuilder =
        new CppCompilationContext.Builder(ruleContext);
    CppModuleMap moduleMap = createCrosstoolModuleMap(ruleContext);
    if (moduleMap != null) {
      contextBuilder.setCppModuleMap(moduleMap);
    }
    final CppCompilationContext context = contextBuilder.build();
    boolean supportsParamFiles = ruleContext.attributes().get("supports_param_files", BOOLEAN);
    boolean supportsHeaderParsing =
        ruleContext.attributes().get("supports_header_parsing", BOOLEAN);

    CcToolchainProvider provider = new CcToolchainProvider(
        Preconditions.checkNotNull(ruleContext.getFragment(CppConfiguration.class)),
        crosstool,
        fullInputsForCrosstool(ruleContext, crosstoolMiddleman),
        compile,
        strip,
        objcopy,
        fullInputsForLink(ruleContext, link),
        dwp,
        libcLink,
        staticRuntimeLinkInputs,
        staticRuntimeLinkMiddleman,
        dynamicRuntimeLinkInputs,
        dynamicRuntimeLinkMiddleman,
        runtimeSolibDir,
        context,
        supportsParamFiles,
        supportsHeaderParsing);
    RuleConfiguredTargetBuilder builder =
        new RuleConfiguredTargetBuilder(ruleContext)
            .add(CcToolchainProvider.class, provider)
            .setFilesToBuild(new NestedSetBuilder<Artifact>(Order.STABLE_ORDER).build())
            .add(RunfilesProvider.class, RunfilesProvider.simple(Runfiles.EMPTY));

    // If output_license is specified on the cc_toolchain rule, override the transitive licenses
    // with that one. This is necessary because cc_toolchain is used in the target configuration,
    // but it is sort-of-kind-of a tool, but various parts of it are linked into the output...
    // ...so we trust the judgment of the author of the cc_toolchain rule to figure out what
    // licenses should be propagated to C++ targets.
    License outputLicense = ruleContext.getRule().getToolOutputLicense(ruleContext.attributes());
    if (outputLicense != null && outputLicense != License.NO_LICENSE) {
      final NestedSet<TargetLicense> license = NestedSetBuilder.create(Order.STABLE_ORDER,
          new TargetLicense(ruleContext.getLabel(), outputLicense));
      LicensesProvider licensesProvider = new LicensesProvider() {
        @Override
        public NestedSet<TargetLicense> getTransitiveLicenses() {
          return license;
        }
      };

      builder.add(LicensesProvider.class, licensesProvider);
    }

    return builder.build();
  }

  private NestedSet<Artifact> inputsForLibcLink(RuleContext ruleContext) {
    TransitiveInfoCollection libcLink = ruleContext.getPrerequisite(":libc_link", Mode.HOST);
    return libcLink != null
        ? libcLink.getProvider(FileProvider.class).getFilesToBuild()
        : NestedSetBuilder.<Artifact>emptySet(Order.STABLE_ORDER);
  }

  private NestedSet<Artifact> fullInputsForCrosstool(RuleContext ruleContext,
      NestedSet<Artifact> crosstoolMiddleman) {
    return NestedSetBuilder.<Artifact>stableOrder()
        .addTransitive(crosstoolMiddleman)
        // Use "libc_link" here, because it is functionally identical to the case
        // below. If we introduce separate filegroups for compiling and linking, we
        // need to fix that here.
        .addTransitive(AnalysisUtils.getMiddlemanFor(ruleContext, ":libc_link"))
        .build();
  }

  private NestedSet<Artifact> fullInputsForLink(RuleContext ruleContext, NestedSet<Artifact> link) {
    return NestedSetBuilder.<Artifact>stableOrder()
        .addTransitive(link)
        .addTransitive(AnalysisUtils.getMiddlemanFor(ruleContext, ":libc_link"))
        .add(ruleContext.getAnalysisEnvironment().getEmbeddedToolArtifact(
            CppRuleClasses.BUILD_INTERFACE_SO))
        .build();
  }

  private CppModuleMap createCrosstoolModuleMap(RuleContext ruleContext) {
    if (ruleContext.getPrerequisite("module_map", Mode.HOST) == null) {
      return null;
    }
    Artifact moduleMapArtifact = ruleContext.getPrerequisiteArtifact("module_map", Mode.HOST);
    if (moduleMapArtifact == null) {
      return null;
    }
    return new CppModuleMap(moduleMapArtifact, "crosstool");
  }

  private TransitiveInfoCollection selectDep(
      RuleContext ruleContext, String attribute, Label label) {
    for (TransitiveInfoCollection dep : ruleContext.getPrerequisites(attribute, Mode.TARGET)) {
      if (dep.getLabel().equals(label)) {
        return dep;
      }
    }

    return ruleContext.getPrerequisites(attribute, Mode.TARGET).get(0);
  }

  private NestedSet<Artifact> getFiles(RuleContext context, String attribute) {
    TransitiveInfoCollection dep = context.getPrerequisite(attribute, Mode.HOST);
    MiddlemanProvider middlemanProvider = dep.getProvider(MiddlemanProvider.class);
    // We use the middleman if we can (if the dep is a filegroup), otherwise, just the regular
    // filesToBuild (e.g. if it is a simple input file)
    return middlemanProvider != null
        ? middlemanProvider.getMiddlemanArtifact()
        : dep.getProvider(FileProvider.class).getFilesToBuild();
  }
}
