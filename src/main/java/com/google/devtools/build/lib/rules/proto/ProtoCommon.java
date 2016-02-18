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

package com.google.devtools.build.lib.rules.proto;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Root;
import com.google.devtools.build.lib.analysis.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;

/**
 * Utility functions for proto_library and proto aspect implementations.
 */
public class ProtoCommon {
  private ProtoCommon() {
    throw new UnsupportedOperationException();
  }

  /**
   * Gets the direct sources of a proto library. If protoSources is not empty,
   * the value is just protoSources. Otherwise, it's the combined sources of all direct dependencies
   * of the given RuleContext.
   * @param ruleContext the proto library rule context.
   * @param protoSources the direct proto sources.
   * @return the direct sources of a proto library.
   */
  // TODO(bazel-team): Proto sources should probably be a NestedSet.
  public static ImmutableList<Artifact> getCheckDepsProtoSources(
      RuleContext ruleContext, ImmutableList<Artifact> protoSources) {

    if (protoSources.isEmpty()) {
      /* a proxy/alias library, return the sources of the direct deps */
      ImmutableList.Builder<Artifact> builder = new ImmutableList.Builder<>();
      for (TransitiveInfoCollection provider : ruleContext
          .getPrerequisites("deps", Mode.TARGET)) {
        ProtoSourcesProvider sources = provider.getProvider(ProtoSourcesProvider.class);
        if (sources != null) {
          builder.addAll(sources.getCheckDepsProtoSources());
        }
      }
      return builder.build();
    } else {
      return protoSources;
    }
  }

  /**
   * Collects all .proto files in this lib and its transitive dependencies.
   *
   * <p>Each import is a Artifact/Label pair.
   */
  public static NestedSet<Artifact> collectTransitiveImports(RuleContext ruleContext,
      ImmutableList<Artifact> protoSources) {
    NestedSetBuilder<Artifact> importsBuilder = NestedSetBuilder.naiveLinkOrder();

    importsBuilder.addAll(protoSources);

    for (ProtoSourcesProvider dep : ruleContext.getPrerequisites(
        "deps", Mode.TARGET, ProtoSourcesProvider.class)) {
      importsBuilder.addTransitive(dep.getTransitiveImports());
    }

    return importsBuilder.build();
  }

  /**
   * Check that .proto files in sources are from the same package. This is done to avoid clashes
   * with the generated sources.
   */
  public static void checkSourceFilesAreInSamePackage(RuleContext ruleContext) {
    // TODO(bazel-team): this does not work with filegroups that contain files
    // that are not in the package
    for (Label source : ruleContext.attributes().get("srcs", BuildType.LABEL_LIST)) {
      if (!isConfiguredTargetInSamePackage(ruleContext, source)) {
        ruleContext.attributeError(
            "srcs",
            "Proto source with label '" + source + "' must be in same package as consuming rule.");
      }
    }
  }

  private static boolean isConfiguredTargetInSamePackage(RuleContext ruleContext, Label source) {
    return ruleContext.getLabel().getPackageIdentifier().equals(source.getPackageIdentifier());
  }

  public static RunfilesProvider createRunfilesProvider(
      final NestedSet<Artifact> transitiveProtoSources, RuleContext ruleContext) {
    return RunfilesProvider.withData(
        Runfiles.EMPTY,
        new Runfiles.Builder(ruleContext.getWorkspaceName())
            // TODO(bazel-team): addArtifacts is deprecated, but addTransitive fails
            // due to nested set ordering restrictions. Figure this out.
            .addArtifacts(transitiveProtoSources)
            .build());
  }

  // =================================================================
  // Protocol compiler invocation stuff.

  /**
   * Each language-specific initialization method will call this to construct
   * Artifacts representing its protocol compiler outputs.
   *
   * @param extension Remove ".proto" and replace it with this to produce
   *                  the output file name, e.g. ".pb.cc".
   * @param pythonNames If true, replace hyphens in the file name
   *              with underscores, as required for Python modules.
   */
  public static ImmutableList<Artifact> getGeneratedOutputs(RuleContext ruleContext,
      ImmutableList<Artifact> protoSources, String extension, boolean pythonNames) {
    ImmutableList.Builder<Artifact> outputsBuilder = new ImmutableList.Builder<>();
    Root genfiles = ruleContext.getConfiguration().getGenfilesDirectory();
    for (Artifact src : protoSources) {
      PathFragment srcPath = src.getRootRelativePath();
      if (pythonNames) {
        srcPath = srcPath.replaceName(srcPath.getBaseName().replace('-', '_'));
      }
      // Note that two proto_library rules can have the same source file, so this is actually a
      // shared action. NB: This can probably result in action conflicts if the proto_library rules
      // are not the same.
      outputsBuilder.add(
          ruleContext.getShareableArtifact(FileSystemUtils.replaceExtension(srcPath, extension),
              genfiles));
    }
    return outputsBuilder.build();
  }

  /**
   * Each language-specific initialization method will call this to construct
   * Artifacts representing its protocol compiler outputs.
   *
   * @param extension Remove ".proto" and replace it with this to produce
   *                  the output file name, e.g. ".pb.cc".
   */
  public static ImmutableList<Artifact> getGeneratedOutputs(RuleContext ruleContext,
      ImmutableList<Artifact> protoSources, String extension) {
    return getGeneratedOutputs(ruleContext, protoSources, extension, false);
  }
}
