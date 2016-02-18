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

package com.google.devtools.build.lib.rules.objc;

import static com.google.devtools.build.lib.packages.Attribute.ConfigurationTransition.HOST;
import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.BuildType.LABEL;
import static com.google.devtools.build.lib.packages.BuildType.LABEL_LIST;
import static com.google.devtools.build.lib.syntax.Type.BOOLEAN;
import static com.google.devtools.build.lib.syntax.Type.STRING;
import static com.google.devtools.build.lib.syntax.Type.STRING_LIST;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.Constants;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.BaseRuleClasses;
import com.google.devtools.build.lib.analysis.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.analysis.RuleDefinitionEnvironment;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.Attribute.LateBoundLabel;
import com.google.devtools.build.lib.packages.AttributeMap;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClass.Builder;
import com.google.devtools.build.lib.packages.RuleClass.Builder.RuleClassType;
import com.google.devtools.build.lib.rules.apple.AppleConfiguration;
import com.google.devtools.build.lib.rules.apple.Platform;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.util.FileType;
import com.google.devtools.build.lib.util.FileTypeSet;
import com.google.devtools.build.lib.vfs.PathFragment;

/**
 * Shared rule classes and associated utility code for Objective-C rules.
 */
public class ObjcRuleClasses {
  static final String CLANG = "clang";
  static final String CLANG_PLUSPLUS = "clang++";
  static final String SWIFT = "swift";
  static final String LIBTOOL = "libtool";
  static final String DSYMUTIL = "dsymutil";
  static final String LIPO = "lipo";
  static final String STRIP = "strip";

  private static final PathFragment JAVA = new PathFragment("/usr/bin/java");

  private ObjcRuleClasses() {
    throw new UnsupportedOperationException("static-only");
  }

  public static IntermediateArtifacts intermediateArtifacts(RuleContext ruleContext) {
    return new IntermediateArtifacts(ruleContext, /*archiveFileNameSuffix=*/ "");
  }

  /**
   * Returns a {@link IntermediateArtifacts} to be used to compile and link the ObjC source files
   * in {@code j2ObjcSource}.
   */
  static IntermediateArtifacts j2objcIntermediateArtifacts(RuleContext ruleContext,
      J2ObjcSource j2ObjcSource) {
    // We need to append "_j2objc" to the name of the generated archive file to distinguish it from
    // the C/C++ archive file created by proto_library targets with attribute cc_api_version
    // specified.
    return new IntermediateArtifacts(
        ruleContext,
        j2ObjcSource.getTargetLabel(),
        /*archiveFileNameSuffix=*/"_j2objc");
  }

  /**
   * Returns an {@link Iterable} of {@link Artifact}s containing all the j2objc archives from the
   * transitive closure of the rule through the "deps" attribute. This is useful for ensuring that
   * the j2objc archives are present for linking.
   *
   * @param ruleContext the {@link RuleContext} of the current rule
   * @return an {@link Iterable} of j2objc library archive artifacts.
   */
  static Iterable<Artifact> j2ObjcLibraries(RuleContext ruleContext) {
    ImmutableList.Builder<Artifact> j2objcLibraries = new ImmutableList.Builder<>();

    // TODO(bazel-team): Refactor the code to stop flattening the nested set here.
    for (J2ObjcSource j2ObjcSource : J2ObjcSrcsProvider.buildFrom(ruleContext).getSrcs()) {
      if (j2ObjcSource.hasSourceFiles()) {
        j2objcLibraries.add(j2objcIntermediateArtifacts(ruleContext, j2ObjcSource).archive());
      }
    }

    return j2objcLibraries.build();
  }

  /**
   * Returns a {@link J2ObjcMappingFileProvider} containing J2ObjC mapping files from rules
   * that can be reached transitively through the "deps" attribute.
   *
   * @param ruleContext the rule context of the current rule
   * @return a {@link J2ObjcMappingFileProvider} containing J2ObjC mapping files information from
   *     the transitive closure.
   */
  public static J2ObjcMappingFileProvider j2ObjcMappingFileProvider(RuleContext ruleContext) {
    J2ObjcMappingFileProvider.Builder builder = new J2ObjcMappingFileProvider.Builder();
    Iterable<J2ObjcMappingFileProvider> providers =
        ruleContext.getPrerequisites("deps", Mode.TARGET, J2ObjcMappingFileProvider.class);
    for (J2ObjcMappingFileProvider provider : providers) {
      builder.addTransitive(provider);
    }

    return builder.build();
  }

  public static Artifact artifactByAppendingToBaseName(RuleContext context, String suffix) {
    return context.getPackageRelativeArtifact(
        context.getLabel().getName() + suffix, context.getBinOrGenfilesDirectory());
  }

  public static ObjcConfiguration objcConfiguration(RuleContext ruleContext) {
    return ruleContext.getFragment(ObjcConfiguration.class);
  }

  /**
   * Returns true if the source file can be instrumented for coverage.
   */
  public static boolean isInstrumentable(Artifact sourceArtifact) {
    return !ASSEMBLY_SOURCES.matches(sourceArtifact.getFilename());
  }


  @VisibleForTesting
  static final Iterable<SdkFramework> AUTOMATIC_SDK_FRAMEWORKS = ImmutableList.of(
      new SdkFramework("Foundation"), new SdkFramework("UIKit"));

  /**
   * Creates a new spawn action builder that requires a darwin architecture to run.
   */
  static SpawnAction.Builder spawnOnDarwinActionBuilder(RuleContext ruleContext) {
    AppleConfiguration appleConfiguration = ruleContext.getFragment(AppleConfiguration.class);
    return new SpawnAction.Builder()
        .setEnvironment(appleConfiguration.getEnvironmentForIosAction())
        .setExecutionInfo(ImmutableMap.of(ExecutionRequirements.REQUIRES_DARWIN, ""));
  }

  /**
   * Creates a new spawn action builder that requires a darwin architecture to run and is executed
   * with the given jar.
   */
  // TODO(bazel-team): Reference a rule target rather than a jar file when Darwin runfiles work
  // better.
  static SpawnAction.Builder spawnJavaOnDarwinActionBuilder(RuleContext ruleContext,
      Artifact deployJarArtifact) {
    return spawnOnDarwinActionBuilder(ruleContext)
        .setExecutable(JAVA)
        .addExecutableArguments("-jar", deployJarArtifact.getExecPathString())
        .addInput(deployJarArtifact);
  }

  /**
   * Creates a new spawn action builder that requires a darwin architecture to run and calls bash
   * to execute cmd.
   * Once we have a fix for b/21874752  we should be able to call setShellCommand(cmd)
   * directly, but right now we don't have a buildhelpers package on Macs so we must specify
   * the path to /bin/bash explicitly.
   */
  static SpawnAction.Builder spawnBashOnDarwinActionBuilder(RuleContext ruleContext, String cmd) {
    return spawnOnDarwinActionBuilder(ruleContext)
        .setShellCommand(ImmutableList.of("/bin/bash", "-c", cmd));
  }

  /**
   * Creates a new configured target builder with the given {@code filesToBuild}, which are also
   * used as runfiles.
   *
   * @param ruleContext the current rule context
   * @param filesToBuild files to build for this target. These also become the data runfiles
   */
  static RuleConfiguredTargetBuilder ruleConfiguredTarget(RuleContext ruleContext,
      NestedSet<Artifact> filesToBuild) {
    RunfilesProvider runfilesProvider = RunfilesProvider.withData(
        new Runfiles.Builder(ruleContext.getWorkspaceName())
            .addRunfiles(ruleContext, RunfilesProvider.DEFAULT_RUNFILES).build(),
        new Runfiles.Builder(ruleContext.getWorkspaceName())
            .addTransitiveArtifacts(filesToBuild).build());

    return new RuleConfiguredTargetBuilder(ruleContext)
        .setFilesToBuild(filesToBuild)
        .add(RunfilesProvider.class, runfilesProvider);
  }

  /**
   * Attributes for {@code objc_*} rules that have compiler options.
   */
  public static class CoptsRule implements RuleDefinition {
    @Override
    public RuleClass build(Builder builder, RuleDefinitionEnvironment environment) {
      return builder
          /* <!-- #BLAZE_RULE($objc_opts_rule).ATTRIBUTE(copts) -->
          Extra flags to pass to the compiler.
          ${SYNOPSIS}
          Subject to <a href="#make_variables">"Make variable"</a> substitution and
          <a href="#sh-tokenization">Bourne shell tokenization</a>.
          These flags will only apply to this target, and not those upon which
          it depends, or those which depend on it.
          <p>
          Note that for the generated Xcode project, directory paths specified using "-I" flags in
          copts are parsed out, prepended with "$(WORKSPACE_ROOT)/" if they are relative paths, and
          added to the header search paths for the associated Xcode target.
          <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
          .add(attr("copts", STRING_LIST))
          .build();
    }

    @Override
    public Metadata getMetadata() {
      return RuleDefinition.Metadata.builder()
          .name("$objc_opts_rule")
          .type(RuleClassType.ABSTRACT)
          .build();
    }
  }

  /**
   * Common attributes for {@code objc_*} rules that use plists or copts.
   */
  public static class OptionsRule implements RuleDefinition {
    @Override
    public RuleClass build(Builder builder, RuleDefinitionEnvironment env) {
      return builder
          // TODO(bazel-team): Remove options and replace with: (a) a plists attribute (instead of
          // the current infoplist, defined on all rules and propagated to the next bundling rule)
          // and (b) a way to share copts e.g. by being able to include constants across package
          // boundaries in bazel.
          //
          // For now the semantics of this attribute are: any copts in the options will be used if
          // defined on a compiling/linking rule, otherwise ignored. Infoplists are merged in if
          // defined on a bundling rule, otherwise ignored.
          .add(attr("options", LABEL)
              .undocumented("objc_options will be removed")
              .allowedFileTypes()
              .allowedRuleClasses("objc_options"))
          .build();
    }

    @Override
    public Metadata getMetadata() {
      return RuleDefinition.Metadata.builder()
          .name("$objc_options_rule")
          .type(RuleClassType.ABSTRACT)
          .build();
    }
  }

  /**
   * Attributes for {@code objc_*} rules that can link in SDK frameworks.
   */
  public static class SdkFrameworksDependerRule implements RuleDefinition {
    @Override
    public RuleClass build(Builder builder, RuleDefinitionEnvironment environment) {
      return builder
          /* <!-- #BLAZE_RULE($objc_sdk_frameworks_depender_rule).ATTRIBUTE(sdk_frameworks) -->
          Names of SDK frameworks to link with.
          ${SYNOPSIS}
          For instance, "XCTest" or "Cocoa". "UIKit" and "Foundation" are always
          included and do not mean anything if you include them.

          <p>When linking a library, only those frameworks named in that library's
          sdk_frameworks attribute are linked in. When linking a binary, all
          SDK frameworks named in that binary's transitive dependency graph are
          used.
          <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
          .add(attr("sdk_frameworks", STRING_LIST))
          /* <!-- #BLAZE_RULE($objc_sdk_frameworks_depender_rule).ATTRIBUTE(weak_sdk_frameworks) -->
          Names of SDK frameworks to weakly link with. For instance,
          "MediaAccessibility".
          ${SYNOPSIS}

          In difference to regularly linked SDK frameworks, symbols
          from weakly linked frameworks do not cause an error if they
          are not present at runtime.
          <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
          .add(attr("weak_sdk_frameworks", STRING_LIST))
          /* <!-- #BLAZE_RULE($objc_sdk_frameworks_depender_rule).ATTRIBUTE(sdk_dylibs) -->
          Names of SDK .dylib libraries to link with. For instance, "libz" or
          "libarchive".
           ${SYNOPSIS}

          "libc++" is included automatically if the binary has any C++ or
          Objective-C++ sources in its dependency tree. When linking a binary,
          all libraries named in that binary's transitive dependency graph are
          used.
          <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
          .add(attr("sdk_dylibs", STRING_LIST))
          .build();
    }

    @Override
    public Metadata getMetadata() {
      return RuleDefinition.Metadata.builder()
          .name("$objc_sdk_frameworks_depender_rule")
          .type(RuleClassType.ABSTRACT)
          .build();
    }
  }

  /**
   * Iff a file matches this type, it is considered to use C++.
   */
  static final FileType CPP_SOURCES = FileType.of(".cc", ".cpp", ".mm", ".cxx", ".C");

  static final FileType NON_CPP_SOURCES = FileType.of(".m", ".c");

  static final FileType ASSEMBLY_SOURCES = FileType.of(".s", ".S", ".asm");

  static final FileType SWIFT_SOURCES = FileType.of(".swift");

  /**
   * Header files, which are not compiled directly, but may be included/imported from source files.
   */
  static final FileType HEADERS = FileType.of(".h", ".inc");

  /**
   * Files allowed in the srcs attribute. This includes private headers.
   */
  static final FileTypeSet SRCS_TYPE = FileTypeSet.of(NON_CPP_SOURCES, CPP_SOURCES,
      ASSEMBLY_SOURCES, SWIFT_SOURCES, HEADERS);

  /**
   * Files that should actually be compiled.
   */
  static final FileTypeSet COMPILABLE_SRCS_TYPE = FileTypeSet.of(NON_CPP_SOURCES, CPP_SOURCES,
      ASSEMBLY_SOURCES, SWIFT_SOURCES);

  static final FileTypeSet NON_ARC_SRCS_TYPE = FileTypeSet.of(FileType.of(".m", ".mm"));

  static final FileTypeSet PLIST_TYPE = FileTypeSet.of(FileType.of(".plist"));

  static final FileTypeSet STORYBOARD_TYPE = FileTypeSet.of(FileType.of(".storyboard"));

  static final FileType XIB_TYPE = FileType.of(".xib");

  // TODO(bazel-team): Restrict this to actual header files only.
  static final FileTypeSet HDRS_TYPE = FileTypeSet.ANY_FILE;

  /**
   * Coverage note files which contain information to reconstruct the basic block graphs and assign
   * source line numbers to blocks.
   */
  static final FileType COVERAGE_NOTES = FileType.of(".gcno");

  /**
   * Common attributes for {@code objc_*} rules that allow the definition of resources such as
   * storyboards. These resources are used during compilation of the declaring rule as well as when
   * bundling a dependent bundle (application, extension, etc.).
   */
  public static class ResourcesRule implements RuleDefinition {
    @Override
    public RuleClass build(Builder builder, RuleDefinitionEnvironment env) {
      return builder
          /* <!-- #BLAZE_RULE($objc_resources_rule).ATTRIBUTE(strings) -->
          Files which are plists of strings, often localizable.
          ${SYNOPSIS}

          These files are converted to binary plists (if they are not already)
          and placed in the bundle root of the final package. If this file's
          immediate containing directory is named *.lproj (e.g. en.lproj,
          Base.lproj), it will be placed under a directory of that name in the
          final bundle. This allows for localizable strings.
          <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
          .add(attr("strings", LABEL_LIST).legacyAllowAnyFileType()
              .direct_compile_time_input())
          /* <!-- #BLAZE_RULE($objc_resources_rule).ATTRIBUTE(xibs) -->
          Files which are .xib resources, possibly localizable.
          ${SYNOPSIS}

          These files are compiled to .nib files and placed the bundle root of
          the final package. If this file's immediate containing directory is
          named *.lproj (e.g. en.lproj, Base.lproj), it will be placed under a
          directory of that name in the final bundle. This allows for
          localizable UI.
          <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
          .add(attr("xibs", LABEL_LIST)
              .direct_compile_time_input()
              .allowedFileTypes(XIB_TYPE))
          /* <!-- #BLAZE_RULE($objc_resources_rule).ATTRIBUTE(storyboards) -->
          Files which are .storyboard resources, possibly localizable.
          ${SYNOPSIS}

          These files are compiled to .storyboardc directories, which are
          placed in the bundle root of the final package. If the storyboards's
          immediate containing directory is named *.lproj (e.g. en.lproj,
          Base.lproj), it will be placed under a directory of that name in the
          final bundle. This allows for localizable UI.
          <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
          .add(attr("storyboards", LABEL_LIST)
              .allowedFileTypes(STORYBOARD_TYPE))
          /* <!-- #BLAZE_RULE($objc_resources_rule).ATTRIBUTE(resources) -->
          Files to include in the final application bundle.
          ${SYNOPSIS}

          They are not processed or compiled in any way besides the processing
          done by the rules that actually generate them. These files are placed
          in the root of the bundle (e.g. Payload/foo.app/...) in most cases.
          However, if they appear to be localized (i.e. are contained in a
          directory called *.lproj), they will be placed in a directory of the
          same name in the app bundle.
          <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
          .add(attr("resources", LABEL_LIST).legacyAllowAnyFileType().direct_compile_time_input())
          /* <!-- #BLAZE_RULE($objc_resources_rule).ATTRIBUTE(structured_resources) -->
          Files to include in the final application bundle.
          ${SYNOPSIS}

          They are not processed or compiled in any way besides the processing
          done by the rules that actually generate them. In differences to
          <code>resources</code> these files are placed in the bundle root in
          the same structure passed to this argument, so
          <code>["res/foo.png"]</code> will end up in
          <code>Payload/foo.app/res/foo.png</code>.
          <p>Note that in the generated XCode project file, all files in the top directory of
          the specified files will be included in the Xcode-generated app bundle. So
          specifying <code>["res/foo.png"]</code> will lead to the inclusion of all files in
          directory <code>res</code>.
          <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
          .add(attr("structured_resources", LABEL_LIST)
              .legacyAllowAnyFileType()
              .direct_compile_time_input())
          /* <!-- #BLAZE_RULE($objc_resources_rule).ATTRIBUTE(datamodels) -->
          Files that comprise the data models of the final linked binary.
          ${SYNOPSIS}

          Each file must have a containing directory named *.xcdatamodel, which
          is usually contained by another *.xcdatamodeld (note the added d)
          directory.
          <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
          .add(attr("datamodels", LABEL_LIST).legacyAllowAnyFileType()
              .direct_compile_time_input())
          /* <!-- #BLAZE_RULE($objc_resources_rule).ATTRIBUTE(asset_catalogs) -->
          Files that comprise the asset catalogs of the final linked binary.
          ${SYNOPSIS}

          Each file must have a containing directory named *.xcassets. This
          containing directory becomes the root of one of the asset catalogs
          linked with any binary that depends directly or indirectly on this
          target.
          <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
          .add(attr("asset_catalogs", LABEL_LIST).legacyAllowAnyFileType()
              .direct_compile_time_input())
          /* <!-- #BLAZE_RULE($objc_resources_rule).ATTRIBUTE(bundles) -->
          The list of bundle targets that this target requires to be included
          in the final bundle.
          ${SYNOPSIS}
          <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
          .add(attr("bundles", LABEL_LIST)
              .direct_compile_time_input()
              .allowedRuleClasses("objc_bundle", "objc_bundle_library")
              .allowedFileTypes())
          .build();
    }
    @Override
    public Metadata getMetadata() {
      return RuleDefinition.Metadata.builder()
          .name("$objc_resources_rule")
          .type(RuleClassType.ABSTRACT)
          .ancestors(ResourceToolsRule.class, XcrunRule.class)
          .build();
    }
  }

  /**
   * Common attributes for {@code objc_*} rules that process resources (by defining or consuming
   * them).
   */
  public static class ResourceToolsRule implements RuleDefinition {
    @Override
    public RuleClass build(Builder builder, RuleDefinitionEnvironment env) {
      return builder
          .add(attr("$plmerge", LABEL).cfg(HOST).exec()
              .value(env.getLabel(Constants.TOOLS_REPOSITORY + "//tools/objc:plmerge")))
          .add(attr("$actoolwrapper", LABEL).cfg(HOST).exec()
              .value(env.getLabel(Constants.TOOLS_REPOSITORY + "//tools/objc:actoolwrapper")))
          .add(attr("$ibtoolwrapper", LABEL).cfg(HOST).exec()
              .value(env.getLabel(Constants.TOOLS_REPOSITORY + "//tools/objc:ibtoolwrapper")))
          // TODO(dmaclach): Adding realpath here should not be required once
          // https://github.com/bazelbuild/bazel/issues/285 is fixed.
          .add(attr("$realpath", LABEL).cfg(HOST).exec()
              .value(env.getLabel(Constants.TOOLS_REPOSITORY + "//tools/objc:realpath")))
          .build();
    }
    @Override
    public Metadata getMetadata() {
      return RuleDefinition.Metadata.builder()
          .name("$objc_resource_tools_rule")
          .type(RuleClassType.ABSTRACT)
          .build();
    }
  }

  /**
   * Common attributes for {@code objc_*} rules that export an xcode project.
   */
  public static class XcodegenRule implements RuleDefinition {
    @Override
    public RuleClass build(Builder builder, RuleDefinitionEnvironment env) {
      return builder
          .add(attr("$xcodegen", LABEL).cfg(HOST).exec()
              .value(env.getLabel(Constants.TOOLS_REPOSITORY + "//tools/objc:xcodegen")))
          .add(attr("$dummy_source", LABEL)
              .value(env.getLabel(Constants.TOOLS_REPOSITORY + "//tools/objc:dummy.c")))
          .build();
    }
    @Override
    public Metadata getMetadata() {
      return RuleDefinition.Metadata.builder()
          .name("$objc_xcodegen_rule")
          .type(RuleClassType.ABSTRACT)
          .build();
    }
  }

  /**
   * Common attributes for {@code objc_*} rules that can be input to compilation (i.e. can be
   * dependencies of other compiling rules).
   */
  public static class CompileDependencyRule implements RuleDefinition {
    @Override
    public RuleClass build(Builder builder, RuleDefinitionEnvironment env) {
      return builder
          /* <!-- #BLAZE_RULE($objc_compile_dependency_rule).ATTRIBUTE(hdrs) -->
          The list of C, C++, Objective-C, and Objective-C++ files that are
          included as headers by source files in this rule or by users of this
          library. These will be compiled separately from the source if modules
          are enabled.
          ${SYNOPSIS}
          <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
          .add(attr("hdrs", LABEL_LIST)
              .direct_compile_time_input()
              .allowedFileTypes(HDRS_TYPE))
          /* <!-- #BLAZE_RULE($objc_compile_dependency_rule).ATTRIBUTE(textual_hdrs) -->
          The list of C, C++, Objective-C, and Objective-C++ files that are
          included as headers by source files in this rule or by users of this
          library. Unlike hdrs, these will not be compiled separately from the
          sources.
          ${SYNOPSIS}
          <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
          .add(attr("textual_hdrs", LABEL_LIST)
              .direct_compile_time_input()
              .allowedFileTypes(HDRS_TYPE))
          /* <!-- #BLAZE_RULE($objc_compile_dependency_rule).ATTRIBUTE(bridging_header) -->
          A header defining the Objective-C interfaces to be exposed in Swift.
          ${SYNOPSIS}
          <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
          .add(attr("bridging_header", BuildType.LABEL)
              .direct_compile_time_input()
              .allowedFileTypes(HDRS_TYPE))
          /* <!-- #BLAZE_RULE($objc_compile_dependency_rule).ATTRIBUTE(includes) -->
          List of <code>#include/#import</code> search paths to add to this target
          and all depending targets.
          ${SYNOPSIS}

          This is to support third party and open-sourced libraries that do not
          specify the entire workspace path in their
          <code>#import/#include</code> statements.
          <p>
          The paths are interpreted relative to the package directory, and the
          genfiles and bin roots (e.g. <code>blaze-genfiles/pkg/includedir</code>
          and <code>blaze-out/pkg/includedir</code>) are included in addition to the
          actual client root.
          <p>
          Unlike <a href="#objc_library.copts">COPTS</a>, these flags are added for this rule
          and every rule that depends on it. (Note: not the rules it depends upon!) Be
          very careful, since this may have far-reaching effects.  When in doubt, add
          "-I" flags to <a href="#objc_library.copts">COPTS</a> instead.
          <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
          .add(attr("includes", Type.STRING_LIST))
          /* <!-- #BLAZE_RULE($objc_compile_dependency_rule).ATTRIBUTE(sdk_includes) -->
          List of <code>#include/#import</code> search paths to add to this target
          and all depending targets, where each path is relative to
          <code>$(SDKROOT)/usr/include</code>.
          ${SYNOPSIS}
          <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
          .add(attr("sdk_includes", Type.STRING_LIST))
          .build();
    }
    @Override
    public Metadata getMetadata() {
      return RuleDefinition.Metadata.builder()
          .name("$objc_compile_dependency_rule")
          .type(RuleClassType.ABSTRACT)
          .ancestors(ResourcesRule.class, SdkFrameworksDependerRule.class)
          .build();
    }
  }

  /**
   * Common attributes for {@code objc_*} rules that contain compilable content.
   */
  public static class CompilingRule implements RuleDefinition {
    private static final Iterable<String> ALLOWED_DEPS_RULE_CLASSES =
        ImmutableSet.of(
            "objc_library",
            "objc_import",
            "objc_framework",
            "objc_proto_library",
            "j2objc_library",
            "cc_library",
            "cc_inc_library",
            "ios_framework");

    @Override
    public RuleClass build(Builder builder, RuleDefinitionEnvironment env) {
      return builder
          /* <!-- #BLAZE_RULE($objc_compiling_rule).ATTRIBUTE(srcs) -->
           The list of C, C++, Objective-C, and Objective-C++ source and header
           files that are processed to create the library target.
           ${SYNOPSIS}
           These are your checked-in files, plus any generated files.
           Source files are compiled into .o files with Clang. Header files
           may be included/imported by any source or header in the srcs attribute
           of this target, but not by headers in hdrs or any targets that depend
           on this rule.
           <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
          .add(attr("srcs", LABEL_LIST).direct_compile_time_input().allowedFileTypes(SRCS_TYPE))
          /* <!-- #BLAZE_RULE($objc_compiling_rule).ATTRIBUTE(non_arc_srcs) -->
           The list of Objective-C files that are processed to create the
           library target that DO NOT use ARC.
           ${SYNOPSIS}
           The files in this attribute are treated very similar to those in the
           srcs attribute, but are compiled without ARC enabled.
           <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
          .add(
              attr("non_arc_srcs", LABEL_LIST)
                  .direct_compile_time_input()
                  .allowedFileTypes(NON_ARC_SRCS_TYPE))
          /* <!-- #BLAZE_RULE($objc_compiling_rule).ATTRIBUTE(pch) -->
           Header file to prepend to every source file being compiled (both arc
           and non-arc).
           ${SYNOPSIS}
           Note that the file will not be precompiled - this is simply a
           convenience, not a build-speed enhancement.
           <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
          .add(attr("pch", LABEL).direct_compile_time_input().allowedFileTypes(FileType.of(".pch")))
          /* <!-- #BLAZE_RULE($objc_compiling_rule).ATTRIBUTE(deps) -->
           The list of targets that are linked together to form the final bundle.
           ${SYNOPSIS}
           <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
          .override(
              attr("deps", LABEL_LIST)
                  .direct_compile_time_input()
                  .allowedRuleClasses(ALLOWED_DEPS_RULE_CLASSES)
                  .allowedFileTypes())
          /* <!-- #BLAZE_RULE($objc_compiling_rule).ATTRIBUTE(non_propagated_deps) -->
           The list of targets that are required in order to build this target,
           but which are not included in the final bundle.
           ${SYNOPSIS}
           This attribute should only rarely be used, and probably only for proto
           dependencies.
           ${SYNOPSIS}
           <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
          .add(
              attr("non_propagated_deps", LABEL_LIST)
                  .direct_compile_time_input()
                  .allowedRuleClasses(ALLOWED_DEPS_RULE_CLASSES)
                  .allowedFileTypes())
          /* <!-- #BLAZE_RULE($objc_compiling_rule).ATTRIBUTE(defines) -->
           Extra <code>-D</code> flags to pass to the compiler. They should be in
           the form <code>KEY=VALUE</code> or simply <code>KEY</code> and are
           passed not only the compiler for this target (as <code>copts</code>
           are) but also to all <code>objc_</code> dependers of this target.
           ${SYNOPSIS}
           Subject to <a href="#make_variables">"Make variable"</a> substitution and
           <a href="#sh-tokenization">Bourne shell tokenization</a>.
           <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
          .add(attr("defines", STRING_LIST))
          /* <!-- #BLAZE_RULE($objc_compiling_rule).ATTRIBUTE(enable_modules) -->
           Enables clang module support (via -fmodules).
           ${SYNOPSIS}
           Setting this to 1 will allow you to @import system headers and other targets:
           @import UIKit;
           @import path_to_package_target;
           <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
          .add(attr("enable_modules", BOOLEAN))
          .build();
    }
    @Override
    public Metadata getMetadata() {
      return RuleDefinition.Metadata.builder()
          .name("$objc_compiling_rule")
          .type(RuleClassType.ABSTRACT)
          .ancestors(
              BaseRuleClasses.RuleBase.class,
              CompileDependencyRule.class,
              OptionsRule.class,
              CoptsRule.class,
              XcrunRule.class)
          .build();
    }
  }

  /**
   * Common attributes for {@code objc_*} rules that can optionally be set to {@code alwayslink}.
   */
  public static class AlwaysLinkRule implements RuleDefinition {
    @Override
    public RuleClass build(Builder builder, RuleDefinitionEnvironment env) {
      return builder
          /* <!-- #BLAZE_RULE($objc_alwayslink_rule).ATTRIBUTE(alwayslink) -->
          If 1, any bundle or binary that depends (directly or indirectly) on this
          library will link in all the object files for the files listed in
          <code>srcs</code> and <code>non_arc_srcs</code>, even if some contain no
          symbols referenced by the binary.
          ${SYNOPSIS}
          This is useful if your code isn't explicitly called by code in
          the binary, e.g., if your code registers to receive some callback
          provided by some service.
          <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
          .add(attr("alwayslink", BOOLEAN))
          .build();
    }
    @Override
    public Metadata getMetadata() {
      return RuleDefinition.Metadata.builder()
          .name("$objc_alwayslink_rule")
          .type(RuleClassType.ABSTRACT)
          .ancestors(CompileDependencyRule.class)
          .build();
    }
  }

  /**
   * Common attributes for {@code objc_*} rules that link sources and dependencies.
   */
  public static class LinkingRule implements RuleDefinition {
    @Override
    public RuleClass build(Builder builder, RuleDefinitionEnvironment env) {
      return builder
          .add(attr(":dumpsyms", LABEL)
          .cfg(HOST)
          .singleArtifact()
          .value(new LateBoundLabel<BuildConfiguration>(ObjcConfiguration.class) {
            @Override
            public Label getDefault(Rule rule, BuildConfiguration configuration) {
              if (!configuration.getFragment(ObjcConfiguration.class).generateDebugSymbols()) {
                return null;
              }
              return configuration.getFragment(ObjcConfiguration.class).getDumpSymsLabel();
            }
          }))
          .add(attr("$j2objc_dead_code_pruner", LABEL)
              .allowedFileTypes(FileType.of(".py"))
              .cfg(HOST)
              .exec()
              .singleArtifact()
              .value(env.getLabel(
                  Constants.TOOLS_REPOSITORY + "//tools/objc:j2objc_dead_code_pruner")))
        .build();
    }
    @Override
    public Metadata getMetadata() {
      return RuleDefinition.Metadata.builder()
          .name("$objc_linking_rule")
          .type(RuleClassType.ABSTRACT)
          .ancestors(CompilingRule.class)
          .build();
    }
  }


  /**
   * Base rule definition for iOS test rules.
   */
  public static class IosTestBaseRule implements RuleDefinition {
    @Override
    public RuleClass build(Builder builder, final RuleDefinitionEnvironment env) {
      return builder
          /* <!-- #BLAZE_RULE($ios_test_base_rule).ATTRIBUTE(target_device) -->
           The device against which to run the test.
           ${SYNOPSIS}
           <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
          .add(
              attr(IosTest.TARGET_DEVICE, LABEL)
                  .allowedFileTypes()
                  .allowedRuleClasses("ios_device"))
          /* <!-- #BLAZE_RULE($ios_test_base_rule).ATTRIBUTE(xctest) -->
           Whether this target contains tests using the XCTest testing framework.
           ${SYNOPSIS}
           <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
          .add(attr(IosTest.IS_XCTEST, BOOLEAN).value(true))
          /* <!-- #BLAZE_RULE($ios_test_base_rule).ATTRIBUTE(xctest_app) -->
           A <code>objc_binary</code> or <code>ios_application</code> target that contains the
           app bundle to test against in XCTest.
           This attribute is only valid if <code>xctest</code> is true.
           ${SYNOPSIS}
           <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
          .add(
              attr(IosTest.XCTEST_APP, LABEL)
                  .value(
                      new Attribute.ComputedDefault(IosTest.IS_XCTEST) {
                        @Override
                        public Object getDefault(AttributeMap rule) {
                          return rule.get(IosTest.IS_XCTEST, Type.BOOLEAN)
                              // No TOOLS_REPOSITORY prefix for the xctest_app tool; xcode projects
                              // referencing a dependency under a repository do not work. Thus,
                              // this target must be available in the target depot.
                              ? env.getLabel("//tools/objc:xctest_app")
                              : null;
                        }
                      })
                  .allowedFileTypes()
                  // TODO(bazel-team): Remove objc_binary once it stops exporting XcTestAppProvider.
                  .allowedRuleClasses("objc_binary", "ios_application"))
          .override(
              attr("infoplist", LABEL)
                  .value(
                      new Attribute.ComputedDefault(IosTest.IS_XCTEST) {
                        @Override
                        public Object getDefault(AttributeMap rule) {
                          return rule.get(IosTest.IS_XCTEST, Type.BOOLEAN)
                              // No TOOLS_REPOSITORY prefix for the xctest_app tool; xcode projects
                              // referencing a dependency under a repository do not work. Thus,
                              // this target must be available in the target depot.
                              ? env.getLabel("//tools/objc:xctest_infoplist")
                              : null;
                        }
                      })
                  .allowedFileTypes(PLIST_TYPE))
          .build();
    }
    @Override
    public Metadata getMetadata() {
      return RuleDefinition.Metadata.builder()
          .name("$ios_test_base_rule")
          .type(RuleClassType.ABSTRACT)
          .ancestors(
              CompilingRule.class,
              ReleaseBundlingRule.class,
              LinkingRule.class,
              XcodegenRule.class,
              SimulatorRule.class)
          .build();
    }
  }

  /**
   * Common attributes for {@code objc_*} rules that create a bundle.
   */
  public static class BundlingRule implements RuleDefinition {
    @Override
    public RuleClass build(Builder builder, RuleDefinitionEnvironment env) {
      return builder
          /* <!-- #BLAZE_RULE($objc_bundling_rule).ATTRIBUTE(infoplist) -->
          The infoplist file. This corresponds to <i>appname</i>-Info.plist in Xcode projects.
          ${SYNOPSIS}
          Blaze will perform variable substitution on the plist file for the following values:
          <ul>
            <li><code>${EXECUTABLE_NAME}</code>: The name of the executable generated and included
               in the bundle by blaze, which can be used as the value for
               <code>CFBundleExecutable</code> within the plist.
            <li><code>${BUNDLE_NAME}</code>: This target's name and bundle suffix (.bundle or .app)
               in the form<code><var>name</var></code>.<code>suffix</code>.
            <li><code>${PRODUCT_NAME}</code>: This target's name.
         </ul>
         <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
        .add(attr("infoplist", LABEL)
            .allowedFileTypes(PLIST_TYPE))
        /* <!-- #BLAZE_RULE($objc_bundling_rule).ATTRIBUTE(families) -->
        The device families to which this bundle or binary is targeted.
        ${SYNOPSIS}

        This is known as the <code>TARGETED_DEVICE_FAMILY</code> build setting
        in Xcode project files. It is a list of one or more of the strings
        <code>"iphone"</code> and <code>"ipad"</code>.

        <p>By default this is set to <code>"iphone"</code>, if explicitly specified may not be
        empty.</p>
        <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
        .add(attr("families", STRING_LIST)
             .value(ImmutableList.of(TargetDeviceFamily.IPHONE.getNameInRule())))
        .add(attr("$momcwrapper", LABEL).cfg(HOST).exec()
            .value(env.getLabel(Constants.TOOLS_REPOSITORY + "//tools/objc:momcwrapper")))
        .add(attr("$swiftstdlibtoolwrapper", LABEL).cfg(HOST).exec()
            .value(env.getLabel(
                Constants.TOOLS_REPOSITORY + "//tools/objc:swiftstdlibtoolwrapper")))
        .build();
    }
    @Override
    public Metadata getMetadata() {
      return RuleDefinition.Metadata.builder()
          .name("$objc_bundling_rule")
          .type(RuleClassType.ABSTRACT)
          .ancestors(OptionsRule.class, ResourceToolsRule.class, XcrunRule.class)
          .build();
    }
  }

  /**
   * Common attributes for {@code objc_*} rules that create a bundle meant for release (e.g.
   * application or extension).
   */
  public static class ReleaseBundlingRule implements RuleDefinition {
    @Override
    public RuleClass build(Builder builder, RuleDefinitionEnvironment env) {
      return builder
          /* <!-- #BLAZE_RULE($objc_release_bundling_rule).ATTRIBUTE(entitlements) -->
          The entitlements file required for device builds of this application.
          ${SYNOPSIS}

          See
          <a href="https://developer.apple.com/library/mac/documentation/Miscellaneous/Reference/EntitlementKeyReference/Chapters/AboutEntitlements.html">the apple documentation</a>
          for more information. If absent, the default entitlements from the
          provisioning profile will be used.
          <p>
          The following variables are substituted as per
          <a href="https://developer.apple.com/library/ios/documentation/General/Reference/InfoPlistKeyReference/Articles/CoreFoundationKeys.html">their definitions in Apple's documentation</a>:
          $(AppIdentifierPrefix) and $(CFBundleIdentifier).
          <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
          .add(attr("entitlements", LABEL).legacyAllowAnyFileType())
          /* <!-- #BLAZE_RULE($objc_release_bundling_rule).ATTRIBUTE(provisioning_profile) -->
          The provisioning profile (.mobileprovision file) to use when bundling
          the application.
          ${SYNOPSIS}

          This is only used for non-simulator builds.
          <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
          .add(attr("provisioning_profile", LABEL)
              .singleArtifact().allowedFileTypes(FileType.of(".mobileprovision")))
          // Will be used if provisioning_profile is null.
          .add(attr(":default_provisioning_profile", LABEL)
              .singleArtifact()
              .allowedFileTypes(FileType.of(".mobileprovision"))
              .value(new LateBoundLabel<BuildConfiguration>(ObjcConfiguration.class) {
                @Override
                public Label getDefault(Rule rule, BuildConfiguration configuration) {
                  AppleConfiguration appleConfiguration =
                      configuration.getFragment(AppleConfiguration.class);
                  if (appleConfiguration.getBundlingPlatform() != Platform.IOS_DEVICE) {
                    return null;
                  }
                  if (rule.isAttributeValueExplicitlySpecified("provisioning_profile")) {
                    return null;
                  }
                  return appleConfiguration.getDefaultProvisioningProfileLabel();
                }
              }))
          /* <!-- #BLAZE_RULE($objc_release_bundling_rule).ATTRIBUTE(app_icon) -->
          The name of the application icon.
          ${SYNOPSIS}

          The icon should be in one of the asset catalogs of this target or
          a (transitive) dependency. In a new project, this is initialized
          to "AppIcon" by Xcode.
          <p>
          If the application icon is not in an asset catalog, do not use this
          attribute. Instead, add a CFBundleIcons entry to the Info.plist file.
          <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
          .add(attr("app_icon", STRING))
          /* <!-- #BLAZE_RULE($objc_release_bundling_rule).ATTRIBUTE(launch_image) -->
          The name of the launch image.
          ${SYNOPSIS}

          The icon should be in one of the asset catalogs of this target or
          a (transitive) dependency. In a new project, this is initialized
          to "LaunchImage" by Xcode.
          <p>
          If the launch image is not in an asset catalog, do not use this
          attribute. Instead, add an appropriately-named image resource to the
          bundle.
          <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
          .add(attr("launch_image", STRING))
          /* <!-- #BLAZE_RULE($objc_release_bundling_rule).ATTRIBUTE(bundle_id) -->
          The bundle ID (reverse-DNS path followed by app name) of the binary.
          ${SYNOPSIS}

          If specified, it will override the bundle ID specified in the associated plist file. If
          no bundle ID is specified on either this attribute or in the plist file, a junk value
          will be used.
          <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
          .add(attr("bundle_id", STRING)
              .value(new Attribute.ComputedDefault() {
                @Override
                public Object getDefault(AttributeMap rule) {
                  // For tests and similar, we don't want to force people to explicitly specify
                  // throw-away data.
                  return "example." + rule.getName();
                }
              }))
          .add(attr("$bundlemerge", LABEL).cfg(HOST).exec()
              .value(env.getLabel(Constants.TOOLS_REPOSITORY + "//tools/objc:bundlemerge")))
          .add(attr("$environment_plist_sh", LABEL).cfg(HOST)
              .value(env.getLabel(
                  Constants.TOOLS_REPOSITORY + "//tools/objc:environment_plist.sh")))
          .build();
    }
    @Override
    public Metadata getMetadata() {
      return RuleDefinition.Metadata.builder()
          .name("$objc_release_bundling_rule")
          .type(RuleClassType.ABSTRACT)
          .ancestors(BundlingRule.class)
          .build();
    }
  }

  /**
   * Common attributes for {@code objc_*} rules that use the iOS simulator.
   */
  public static class SimulatorRule implements RuleDefinition {
    @Override
    public RuleClass build(Builder builder, RuleDefinitionEnvironment env) {
      return builder
          // Needed to run the binary in the simulator.
          .add(attr("$iossim", LABEL).cfg(HOST).exec()
              .value(env.getLabel("//third_party/iossim:iossim")))
          .add(attr("$std_redirect_dylib", LABEL).cfg(HOST).exec()
              .value(env.getLabel(Constants.TOOLS_REPOSITORY + "//tools/objc:StdRedirect.dylib")))
          .build();
    }
    @Override
    public Metadata getMetadata() {
      return RuleDefinition.Metadata.builder()
          .name("$objc_simulator_rule")
          .type(RuleClassType.ABSTRACT)
          .build();
    }
  }

  /**
   * Common attributes for {@code objc_*} rules that need to call xcrun.
   */
  public static class XcrunRule implements RuleDefinition {
    @Override
    public RuleClass build(Builder builder, RuleDefinitionEnvironment env) {
      return builder
          .add(attr("$xcrunwrapper", LABEL).cfg(HOST).exec()
              .value(env.getLabel(Constants.TOOLS_REPOSITORY + "//tools/objc:xcrunwrapper")))
          .build();
    }
    @Override
    public Metadata getMetadata() {
      return RuleDefinition.Metadata.builder()
          .name("$objc_xcrun_rule")
          .type(RuleClassType.ABSTRACT)
          .build();
    }
  }
}

