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

package com.google.devtools.build.lib.rules.objc;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.devtools.build.lib.packages.ImplicitOutputsFunction.fromTemplates;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.Flag.USES_SWIFT;
import static com.google.devtools.build.lib.rules.objc.TargetDeviceFamily.UI_DEVICE_FAMILY_VALUES;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.BuildInfo;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.RunfilesSupport;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.actions.BinaryFileWriteAction;
import com.google.devtools.build.lib.analysis.actions.CustomCommandLine;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.actions.TemplateExpansionAction;
import com.google.devtools.build.lib.analysis.actions.TemplateExpansionAction.Substitution;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.Attribute.SplitTransition;
import com.google.devtools.build.lib.packages.ImplicitOutputsFunction.SafeImplicitOutputsFunction;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.rules.objc.BundleSupport.ExtraActoolArgs;
import com.google.devtools.build.lib.shell.ShellUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.xcode.xcodegen.proto.XcodeGenProtos.XcodeprojBuildSetting;

import java.util.List;
import java.util.Map.Entry;

import javax.annotation.Nullable;

/**
 * Support for released bundles, such as an application or extension. Such a bundle is generally
 * composed of a top-level {@link BundleSupport bundle}, potentially signed, as well as some debug
 * information, if {@link ObjcConfiguration#generateDebugSymbols() requested}.
 *
 * <p>Contains actions, validation logic and provider value generation.
 *
 * <p>Methods on this class can be called in any order without impacting the result.
 */
public final class ReleaseBundlingSupport {

  /**
   * Template for the containing application folder.
   */
  public static final SafeImplicitOutputsFunction IPA = fromTemplates("%{name}.ipa");

  @VisibleForTesting
  static final String NO_ASSET_CATALOG_ERROR_FORMAT =
      "a value was specified (%s), but this app does not have any asset catalogs";
  @VisibleForTesting
  static final String INVALID_FAMILIES_ERROR =
      "Expected one or two strings from the list 'iphone', 'ipad'";
  @VisibleForTesting
  static final String DEVICE_NO_PROVISIONING_PROFILE =
      "Provisioning profile must be set for device build";

  @VisibleForTesting
  static final String PROVISIONING_PROFILE_BUNDLE_FILE = "embedded.mobileprovision";
  @VisibleForTesting
  static final String APP_BUNDLE_DIR_FORMAT = "Payload/%s.app";
  @VisibleForTesting
  static final String EXTENSION_BUNDLE_DIR_FORMAT = "PlugIns/%s.appex";

  /**
   * Command string for "sed" that tries to extract the application version number from a larger
   * string. For example, from "foo_1.2.3_RC00" this would extract "1.2.3". This regex looks for
   * versions of the format "x.y" or "x.y.z", which may be preceded and/or followed by other text,
   * such as a project name or release candidate number.
   *
   * <p>This command also preserves double quotes around the string, if any.
   */
  private static final String EXTRACT_VERSION_NUMBER_SED_COMMAND =
      "s#\\(\"\\)\\{0,1\\}\\(.*_\\)\\{0,1\\}\\([0-9][0-9]*\\(\\.[0-9][0-9]*\\)\\{1,2\\}\\)"
      + "\\(_[^\"]*\\)\\{0,1\\}\\(\"\\)\\{0,1\\}#\\1\\3\\6#";

  private final Attributes attributes;
  private final BundleSupport bundleSupport;
  private final RuleContext ruleContext;
  private final Bundling bundling;
  private final ObjcProvider objcProvider;
  private final LinkedBinary linkedBinary;
  private final IntermediateArtifacts intermediateArtifacts;

  /**
   * Indicator as to whether this rule generates a binary directly or whether only dependencies
   * should be considered.
   */
  enum LinkedBinary {
    /**
     * This rule generates its own binary which should be included as well as dependency-generated
     * binaries.
     */
    LOCAL_AND_DEPENDENCIES,

    /**
     * This rule does not generate its own binary, only consider binaries from dependencies.
     */
    DEPENDENCIES_ONLY
  }

  /**
   * Creates a new application support within the given rule context.
   *
   * @param ruleContext context for the application-generating rule
   * @param objcProvider provider containing all dependencies' information as well as some of this
   *    rule's
   * @param linkedBinary whether to look for a linked binary from this rule and dependencies or just
   *    the latter
   * @param bundleDirFormat format string representing the bundle's directory with a single
   *    placeholder for the target name (e.g. {@code "Payload/%s.app"})
   * @param bundleMinimumOsVersion the minimum OS version this bundle's plist should be generated
   *    for (<b>not</b> the minimum OS version its binary is compiled with, that needs to be set
   *    through the configuration)
   */
  ReleaseBundlingSupport(RuleContext ruleContext, ObjcProvider objcProvider,
      LinkedBinary linkedBinary, String bundleDirFormat, String bundleMinimumOsVersion) {
    this.linkedBinary = linkedBinary;
    this.attributes = new Attributes(ruleContext);
    this.ruleContext = ruleContext;
    this.objcProvider = objcProvider;
    this.intermediateArtifacts = ObjcRuleClasses.intermediateArtifacts(ruleContext);
    bundling = bundling(ruleContext, objcProvider, bundleDirFormat, bundleMinimumOsVersion);
    bundleSupport = new BundleSupport(ruleContext, bundling, extraActoolArgs());
  }

  /**
   * Validates application-related attributes set on this rule and registers any errors with the
   * rule context.
   *
   * @return this application support
   */
  ReleaseBundlingSupport validateAttributes() {
    // No asset catalogs. That means you cannot specify app_icon or
    // launch_image attributes, since they must not exist. However, we don't
    // run actool in this case, which means it does not do validity checks,
    // and we MUST raise our own error somehow...
    if (!objcProvider.hasAssetCatalogs()) {
      if (attributes.appIcon() != null) {
        ruleContext.attributeError("app_icon",
            String.format(NO_ASSET_CATALOG_ERROR_FORMAT, attributes.appIcon()));
      }
      if (attributes.launchImage() != null) {
        ruleContext.attributeError("launch_image",
            String.format(NO_ASSET_CATALOG_ERROR_FORMAT, attributes.launchImage()));
      }
    }

    if (bundleSupport.targetDeviceFamilies().isEmpty()) {
      ruleContext.attributeError("families", INVALID_FAMILIES_ERROR);
    }

    return this;
  }

  /**
   * Validates that resources defined in this rule and its dependencies and written to this bundle
   * are legal.
   *
   * @return this release bundling support
   */
  ReleaseBundlingSupport validateResources() {
    bundleSupport.validateResources(objcProvider);
    return this;
  }

  /**
   * Registers actions required to build an application. This includes any
   * {@link BundleSupport#registerActions(ObjcProvider) bundle} and bundle merge actions, signing
   * this application if appropriate and combining several single-architecture binaries into one
   * multi-architecture binary.
   *
   * @return this application support
   */
  ReleaseBundlingSupport registerActions() {
    bundleSupport.registerActions(objcProvider);

    registerCombineArchitecturesAction();
    registerTransformAndCopyBreakpadFilesAction();
    registerSwiftStdlibActionsIfNecessary();

    ObjcConfiguration objcConfiguration = ObjcRuleClasses.objcConfiguration(ruleContext);
    Artifact ipaOutput = ruleContext.getImplicitOutputArtifact(IPA);

    Artifact maybeSignedIpa;
    if (objcConfiguration.getBundlingPlatform() == Platform.SIMULATOR) {
      maybeSignedIpa = ipaOutput;
    } else if (attributes.provisioningProfile() == null) {
      throw new IllegalStateException(DEVICE_NO_PROVISIONING_PROFILE);
    } else {
      maybeSignedIpa = registerBundleSigningActions(ipaOutput);
    }

    registerEmbedLabelPlistAction();
    registerEnvironmentPlistAction();

    BundleMergeControlBytes bundleMergeControlBytes = new BundleMergeControlBytes(
        bundling, maybeSignedIpa, objcConfiguration, bundleSupport.targetDeviceFamilies());
    registerBundleMergeActions(
        maybeSignedIpa, bundling.getBundleContentArtifacts(), bundleMergeControlBytes);

    return this;
  }

  private void registerEmbedLabelPlistAction() {
    Artifact buildInfo = Iterables.getOnlyElement(
        ruleContext.getBuildInfo(ObjcBuildInfoFactory.KEY));
    String generatedVersionPlistPath = getGeneratedVersionPlist().getShellEscapedExecPathString();
    String shellCommand = "VERSION=\"$("
        + "grep \"^" + BuildInfo.BUILD_EMBED_LABEL + "\" "
        + buildInfo.getShellEscapedExecPathString()
        + " | cut -d' ' -f2- | sed -e '" + EXTRACT_VERSION_NUMBER_SED_COMMAND + "' | "
        + "sed -e 's#\"#\\\"#g')\" && "
        + "cat >" + generatedVersionPlistPath + " <<EOF\n"
        + "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" "
        + "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
        + "<plist version=\"1.0\">\n"
        + "<dict>\n"
        + "EOF\n"

            + "if [[ -n \"${VERSION}\" ]]; then\n"
            + "  for KEY in CFBundleVersion CFBundleShortVersionString; do\n"
            + "    echo \"  <key>${KEY}</key>\n\" >> "
            + generatedVersionPlistPath + "\n"
            + "    echo \"  <string>${VERSION}</string>\n\" >> "
            + generatedVersionPlistPath + "\n"
            + "  done\n"
            + "fi\n"

            + "cat >>" + generatedVersionPlistPath + " <<EOF\n"
            + "</dict>\n"
            + "</plist>\n"
            + "EOF\n";
    ruleContext.registerAction(new SpawnAction.Builder()
        .setMnemonic("ObjcVersionPlist")
        .setShellCommand(shellCommand)
        .addInput(buildInfo)
        .addOutput(getGeneratedVersionPlist())
        .build(ruleContext));
  }

  private void registerEnvironmentPlistAction() {
    ObjcConfiguration configuration = ObjcRuleClasses.objcConfiguration(ruleContext);
    // Generates a .plist that contains environment values (such as the SDK used to build, the Xcode
    // version, etc), which are parsed from various .plist files of the OS, namely XCodes' and
    // Platforms' plists.
    // The resulting file is meant to be merged with the final bundle.
    String command = Joiner.on(" && ").join(
        "PLATFORM_PLIST=" + IosSdkCommands.platformDir(configuration) + "/Info.plist",
        "PLIST=$(mktemp -d -t bazel_environment)/env.plist",
        "os_build=$(/usr/bin/defaults read \"${PLATFORM_PLIST}\" BuildMachineOSBuild)",
        "compiler=$(/usr/bin/defaults read \"${PLATFORM_PLIST}\" DTCompiler)",
        "platform_version=$(/usr/bin/defaults read \"${PLATFORM_PLIST}\" Version)",
        "sdk_build=$(/usr/bin/defaults read \"${PLATFORM_PLIST}\" DTSDKBuild)",
        "platform_build=$(/usr/bin/defaults read \"${PLATFORM_PLIST}\" DTPlatformBuild)",
        "xcode_build=$(/usr/bin/defaults read \"${PLATFORM_PLIST}\" DTXcodeBuild)",
        "xcode_version=$(/usr/bin/defaults read \"${PLATFORM_PLIST}\" DTXcode)",
        "/usr/bin/defaults write \"${PLIST}\" DTPlatformBuild -string ${platform_build}",
        "/usr/bin/defaults write \"${PLIST}\" DTSDKBuild -string ${sdk_build}",
        "/usr/bin/defaults write \"${PLIST}\" DTPlatformVersion -string ${platform_version}",
        "/usr/bin/defaults write \"${PLIST}\" DTXcode -string ${xcode_version}",
        "/usr/bin/defaults write \"${PLIST}\" DTXCodeBuild -string ${xcode_build}",
        "/usr/bin/defaults write \"${PLIST}\" DTCompiler -string ${compiler}",
        "/usr/bin/defaults write \"${PLIST}\" BuildMachineOSBuild -string ${os_build}",
        "cat \"${PLIST}\" > " + getGeneratedEnvironmentPlist().getShellEscapedExecPathString(),
        "rm -rf \"${PLIST}\"");
    ruleContext.registerAction(ObjcRuleClasses.spawnBashOnDarwinActionBuilder(command)
        .setMnemonic("EnvironmentPlist")
        .addOutput(getGeneratedEnvironmentPlist())
        .build(ruleContext));
  }

  private Artifact registerBundleSigningActions(Artifact ipaOutput) {
    PathFragment entitlementsDirectory = ruleContext.getUniqueDirectory("entitlements");
    Artifact teamPrefixFile =
        ruleContext.getRelatedArtifact(entitlementsDirectory, ".team_prefix_file");
    registerExtractTeamPrefixAction(teamPrefixFile);

    Artifact entitlementsNeedingSubstitution = attributes.entitlements();
    if (entitlementsNeedingSubstitution == null) {
      entitlementsNeedingSubstitution = ruleContext.getRelatedArtifact(
          entitlementsDirectory, ".entitlements_with_variables");
      registerExtractEntitlementsAction(entitlementsNeedingSubstitution);
    }
    Artifact entitlements =
        ruleContext.getRelatedArtifact(entitlementsDirectory, ".entitlements");
    registerEntitlementsVariableSubstitutionAction(
        entitlementsNeedingSubstitution, entitlements, teamPrefixFile);
    Artifact ipaUnsigned = ObjcRuleClasses.artifactByAppendingToRootRelativePath(
        ruleContext, ipaOutput.getExecPath(), ".unsigned");
    registerSignBundleAction(entitlements, ipaOutput, ipaUnsigned);
    return ipaUnsigned;
  }

  /**
   * Adds bundle- and application-related settings to the given Xcode provider builder.
   *
   * @return this application support
   */
  ReleaseBundlingSupport addXcodeSettings(XcodeProvider.Builder xcodeProviderBuilder) {
    bundleSupport.addXcodeSettings(xcodeProviderBuilder);
    // Add application-related Xcode build settings to the main target only. The companion library
    // target does not need them.
    xcodeProviderBuilder.addMainTargetXcodeprojBuildSettings(buildSettings());

    return this;
  }

  /**
   * Adds any files to the given nested set builder that should be built if this application is the
   * top level target in a blaze invocation.
   *
   * @return this application support
   */
  ReleaseBundlingSupport addFilesToBuild(NestedSetBuilder<Artifact> filesToBuild) {
    NestedSetBuilder<Artifact> debugSymbolBuilder = NestedSetBuilder.<Artifact>stableOrder()
        .addTransitive(objcProvider.get(ObjcProvider.DEBUG_SYMBOLS));

    for (Artifact breakpadFile : getBreakpadFiles().values()) {
      filesToBuild.add(breakpadFile);
    }

    if (linkedBinary == LinkedBinary.LOCAL_AND_DEPENDENCIES
        && ObjcRuleClasses.objcConfiguration(ruleContext).generateDebugSymbols()) {
      IntermediateArtifacts intermediateArtifacts =
          ObjcRuleClasses.intermediateArtifacts(ruleContext);
      debugSymbolBuilder.add(intermediateArtifacts.dsymPlist())
          .add(intermediateArtifacts.dsymSymbol())
          .add(intermediateArtifacts.breakpadSym());
    }

    filesToBuild.add(ruleContext.getImplicitOutputArtifact(ReleaseBundlingSupport.IPA))
        // TODO(bazel-team): Fat binaries may require some merging of these file rather than just
        // making them available.
        .addTransitive(debugSymbolBuilder.build());
    return this;
  }

  /**
   * Creates the {@link XcTestAppProvider} that can be used if this application is used as an
   * {@code xctest_app}.
   */
  XcTestAppProvider xcTestAppProvider() {
    // We want access to #import-able things from our test rig's dependency graph, but we don't
    // want to link anything since that stuff is shared automatically by way of the
    // -bundle_loader linker flag.
    ObjcProvider partialObjcProvider = new ObjcProvider.Builder()
        .addTransitiveAndPropagate(ObjcProvider.GCNO, objcProvider)
        .addTransitiveAndPropagate(ObjcProvider.HEADER, objcProvider)
        .addTransitiveAndPropagate(ObjcProvider.INCLUDE, objcProvider)
        .addTransitiveAndPropagate(ObjcProvider.INSTRUMENTED_SOURCE, objcProvider)
        .addTransitiveAndPropagate(ObjcProvider.SDK_DYLIB, objcProvider)
        .addTransitiveAndPropagate(ObjcProvider.SDK_FRAMEWORK, objcProvider)
        .addTransitiveAndPropagate(ObjcProvider.SOURCE, objcProvider)
        .addTransitiveAndPropagate(ObjcProvider.WEAK_SDK_FRAMEWORK, objcProvider)
        .addTransitiveAndPropagate(ObjcProvider.FRAMEWORK_DIR, objcProvider)
        .addTransitiveAndPropagate(ObjcProvider.FRAMEWORK_FILE, objcProvider)
        .build();
    // TODO(bazel-team): Handle the FRAMEWORK_DIR key properly. We probably want to add it to
    // framework search paths, but not actually link it with the -framework flag.
    return new XcTestAppProvider(intermediateArtifacts.combinedArchitectureBinary(),
        ruleContext.getImplicitOutputArtifact(IPA), partialObjcProvider);
  }

  /**
   * Registers an action to generate a runner script based on a template.
   */
  ReleaseBundlingSupport registerGenerateRunnerScriptAction(Artifact runnerScript,
      Artifact ipaInput) {
    ObjcConfiguration objcConfiguration = ObjcRuleClasses.objcConfiguration(ruleContext);
    String escapedSimDevice = ShellUtils.shellEscape(objcConfiguration.getIosSimulatorDevice());
    String escapedSdkVersion = ShellUtils.shellEscape(objcConfiguration.getIosSimulatorVersion());
    ImmutableList<Substitution> substitutions = ImmutableList.of(
        Substitution.of("%app_name%", ruleContext.getLabel().getName()),
        Substitution.of("%ipa_file%", ipaInput.getRootRelativePath().getPathString()),
        Substitution.of("%sim_device%", escapedSimDevice),
        Substitution.of("%sdk_version%", escapedSdkVersion),
        Substitution.of("%iossim%", attributes.iossim().getRootRelativePath().getPathString()));

    ruleContext.registerAction(
        new TemplateExpansionAction(ruleContext.getActionOwner(), attributes.runnerScriptTemplate(),
            runnerScript, substitutions, true));
    return this;
  }

  /**
   * Returns a {@link RunfilesSupport} that uses the provided runner script as the executable.
   */
  RunfilesSupport runfilesSupport(Artifact runnerScript) {
    Artifact ipaFile = ruleContext.getImplicitOutputArtifact(ReleaseBundlingSupport.IPA);
    Runfiles runfiles = new Runfiles.Builder()
        .addArtifact(ipaFile)
        .addArtifact(runnerScript)
        .addArtifact(attributes.iossim())
        .build();
    return RunfilesSupport.withExecutable(ruleContext, runfiles, runnerScript);
  }

  private ExtraActoolArgs extraActoolArgs() {
    ImmutableList.Builder<String> extraArgs = ImmutableList.builder();
    if (attributes.appIcon() != null) {
      extraArgs.add("--app-icon", attributes.appIcon());
    }
    if (attributes.launchImage() != null) {
      extraArgs.add("--launch-image", attributes.launchImage());
    }
    return new ExtraActoolArgs(extraArgs.build());
  }

  private Bundling bundling(RuleContext ruleContext, ObjcProvider objcProvider,
      String bundleDirFormat, String minimumOsVersion) {
    ImmutableList<BundleableFile> extraBundleFiles;
    ObjcConfiguration objcConfiguration = ObjcRuleClasses.objcConfiguration(ruleContext);
    if (objcConfiguration.getBundlingPlatform() == Platform.DEVICE) {
      extraBundleFiles = ImmutableList.of(new BundleableFile(
          new Attributes(ruleContext).provisioningProfile(),
          PROVISIONING_PROFILE_BUNDLE_FILE));
    } else {
      extraBundleFiles = ImmutableList.of();
    }

    String primaryBundleId = null;
    String fallbackBundleId = null;

    if (ruleContext.attributes().isAttributeValueExplicitlySpecified("bundle_id")) {
      primaryBundleId = ruleContext.attributes().get("bundle_id", Type.STRING);
    } else {
      fallbackBundleId = ruleContext.attributes().get("bundle_id", Type.STRING);
    }

    return new Bundling.Builder()
        .setName(ruleContext.getLabel().getName())
        // Architecture that determines which nested bundles are kept.
        .setArchitecture(objcConfiguration.getDependencySingleArchitecture())
        .setBundleDirFormat(bundleDirFormat)
        .addExtraBundleFiles(extraBundleFiles)
        .setObjcProvider(objcProvider)
        .addInfoplistInputFromRule(ruleContext)
        .addInfoplistInput(getGeneratedVersionPlist())
        .addInfoplistInput(getGeneratedEnvironmentPlist())
        .setIntermediateArtifacts(ObjcRuleClasses.intermediateArtifacts(ruleContext))
        .setPrimaryBundleId(primaryBundleId)
        .setFallbackBundleId(fallbackBundleId)
        .setMinimumOsVersion(minimumOsVersion)
        .build();
  }

  private void registerCombineArchitecturesAction() {
    Artifact resultingLinkedBinary = intermediateArtifacts.combinedArchitectureBinary();
    NestedSet<Artifact> linkedBinaries = linkedBinaries();

    ruleContext.registerAction(ObjcRuleClasses.spawnOnDarwinActionBuilder()
        .setMnemonic("ObjcCombiningArchitectures")
        .addTransitiveInputs(linkedBinaries)
        .addOutput(resultingLinkedBinary)
        .setExecutable(ObjcRuleClasses.LIPO)
        .setCommandLine(CustomCommandLine.builder()
            .addExecPaths("-create", linkedBinaries)
            .addExecPath("-o", resultingLinkedBinary)
            .build())
        .build(ruleContext));
  }

  private NestedSet<Artifact> linkedBinaries() {
    NestedSetBuilder<Artifact> linkedBinariesBuilder = NestedSetBuilder.<Artifact>stableOrder()
        .addTransitive(attributes.dependentLinkedBinaries());
    if (linkedBinary == LinkedBinary.LOCAL_AND_DEPENDENCIES) {
      linkedBinariesBuilder.add(intermediateArtifacts.strippedSingleArchitectureBinary());
    }
    return linkedBinariesBuilder.build();
  }

  /** Returns this target's Xcode build settings. */
  private Iterable<XcodeprojBuildSetting> buildSettings() {
    ImmutableList.Builder<XcodeprojBuildSetting> buildSettings = new ImmutableList.Builder<>();
    if (attributes.appIcon() != null) {
      buildSettings.add(XcodeprojBuildSetting.newBuilder()
          .setName("ASSETCATALOG_COMPILER_APPICON_NAME")
          .setValue(attributes.appIcon())
          .build());
    }
    if (attributes.launchImage() != null) {
      buildSettings.add(XcodeprojBuildSetting.newBuilder()
          .setName("ASSETCATALOG_COMPILER_LAUNCHIMAGE_NAME")
          .setValue(attributes.launchImage())
          .build());
    }

    // Convert names to a sequence containing "1" and/or "2" for iPhone and iPad, respectively.
    ImmutableSet<TargetDeviceFamily> families = bundleSupport.targetDeviceFamilies();
    Iterable<Integer> familyIndexes =
        families.isEmpty() ? ImmutableList.<Integer>of() : UI_DEVICE_FAMILY_VALUES.get(families);
    buildSettings.add(XcodeprojBuildSetting.newBuilder()
        .setName("TARGETED_DEVICE_FAMILY")
        .setValue(Joiner.on(',').join(familyIndexes))
        .build());

    Artifact entitlements = attributes.entitlements();
    if (entitlements != null) {
      buildSettings.add(XcodeprojBuildSetting.newBuilder()
          .setName("CODE_SIGN_ENTITLEMENTS")
          .setValue("$(WORKSPACE_ROOT)/" + entitlements.getExecPathString())
          .build());
    }

    return buildSettings.build();
  }

  private ReleaseBundlingSupport registerSignBundleAction(
      Artifact entitlements, Artifact ipaOutput, Artifact ipaUnsigned) {
    // TODO(bazel-team): Support variable substitution

    ImmutableList.Builder<String> dirsToSign = new ImmutableList.Builder<>();

    // Explicitly sign Swift frameworks. Unfortunately --deep option on codesign doesn't do this
    // automatically.
    // The order here is important. The innermost code must singed first.
    String bundleDir = ShellUtils.shellEscape(bundling.getBundleDir());
    if (objcProvider.is(USES_SWIFT)) {
      dirsToSign.add(bundleDir + "/Frameworks/*");
    }
    dirsToSign.add(bundleDir);

    StringBuilder codesignCommandLineBuilder = new StringBuilder();
    for (String dir : dirsToSign.build()) {
      codesignCommandLineBuilder
          .append(codesignCommand(attributes.provisioningProfile(), entitlements, "${t}/" + dir))
          .append(" && ");
    }

    // TODO(bazel-team): Support nested code signing.
    String shellCommand = "set -e && "
        + "t=$(mktemp -d -t signing_intermediate) && "
        + "trap \"rm -rf ${t}\" EXIT && "
        // Get an absolute path since we need to cd into the temp directory for zip.
        + "signed_ipa=${PWD}/" + ipaOutput.getShellEscapedExecPathString() + " && "
        + "/usr/bin/unzip -qq " + ipaUnsigned.getShellEscapedExecPathString() + " -d ${t} && "
        + codesignCommandLineBuilder.toString()
        // Using zip since we need to preserve permissions
        + "cd ${t} && /usr/bin/zip -q -r \"${signed_ipa}\" .";
    ruleContext.registerAction(ObjcRuleClasses.spawnBashOnDarwinActionBuilder(shellCommand)
        .setMnemonic("IosSignBundle")
        .setProgressMessage("Signing iOS bundle: " + ruleContext.getLabel())
        .addInput(ipaUnsigned)
        .addInput(attributes.provisioningProfile())
        .addInput(entitlements)
        .addOutput(ipaOutput)
        .build(ruleContext));

    return this;
  }

  private void registerBundleMergeActions(Artifact ipaUnsigned,
      NestedSet<Artifact> bundleContentArtifacts, BundleMergeControlBytes controlBytes) {
    Artifact bundleMergeControlArtifact =
        ObjcRuleClasses.artifactByAppendingToBaseName(ruleContext, ".ipa-control");

    ruleContext.registerAction(
        new BinaryFileWriteAction(
            ruleContext.getActionOwner(), bundleMergeControlArtifact, controlBytes,
            /*makeExecutable=*/false));

    ruleContext.registerAction(new SpawnAction.Builder()
        .setMnemonic("IosBundle")
        .setProgressMessage("Bundling iOS application: " + ruleContext.getLabel())
        .setExecutable(attributes.bundleMergeExecutable())
        .addInputArgument(bundleMergeControlArtifact)
        .addTransitiveInputs(bundleContentArtifacts)
        .addOutput(ipaUnsigned)
        .build(ruleContext));
  }

  /**
   * Registers the actions that transform and copy the breakpad files from the CPU-specific binaries
   * that are part of this application. There are two steps involved: 1) The breakpad files have to
   * be renamed to include their corresponding CPU architecture as a suffix. 2) The first line of
   * the breakpad file has to be rewritten, as it has to include the name of the application instead
   * of the name of the binary artifact.
   *
   * <p>Example:<br>
   * The ios_application "PrenotCalculator" is specified to use "PrenotCalculatorBinary" as its
   * binary. Assuming that the application is built for armv7 and arm64 CPUs, in the build process
   * two binaries with a corresponding breakpad file each will be built:
   *
   * <pre>blaze-out/xyz-crosstool-ios-arm64/.../PrenotCalculatorBinary_bin
   * blaze-out/xyz-crosstool-ios-arm64/.../PrenotCalculatorBinary.breakpad
   * blaze-out/xyz-crosstool-ios-armv7/.../PrenotCalculatorBinary_bin
   * blaze-out/xyz-crosstool-ios-armv7/.../PrenotCalculatorBinary.breakpad</pre>
   *
   * <p>The first line of the breakpad files will look like this:
   * <pre>MODULE mac arm64 8A7A2DDD28E83E27B339E63631ADBEF30 PrenotCalculatorBinary_bin</pre>
   *
   * <p>For our application, we have to transform & copy these breakpad files like this:
   * <pre>$ head -n1 blaze-bin/.../PrenotCalculator_arm64.breakpad
   * MODULE mac arm64 8A7A2DDD28E83E27B339E63631ADBEF30 PrenotCalculator</pre>
   */
  private void registerTransformAndCopyBreakpadFilesAction() {
    for (Entry<Artifact, Artifact> breakpadFiles : getBreakpadFiles().entrySet()) {
      ruleContext.registerAction(
          new SpawnAction.Builder().setMnemonic("CopyBreakpadFile")
              .setShellCommand(String.format(
                  // This sed command replaces the last word of the first line with the application
                  // name.
                  "sed -r \"1 s/^(MODULE \\w* \\w* \\w*).*$/\\1 %s/\" < %s > %s",
                  ruleContext.getLabel().getName(), breakpadFiles.getKey().getExecPathString(),
                  breakpadFiles.getValue().getExecPathString()))
              .addInput(breakpadFiles.getKey())
              .addOutput(breakpadFiles.getValue())
              .build(ruleContext));
    }
  }

  /**
   * Returns a map of input breakpad artifacts from the CPU-specific binaries built for this
   * ios_application to the new output breakpad artifacts.
   */
  private ImmutableMap<Artifact, Artifact> getBreakpadFiles() {
    ImmutableMap.Builder<Artifact, Artifact> results = ImmutableMap.builder();
    for (Entry<String, Artifact> breakpadFile : attributes.cpuSpecificBreakpadFiles().entrySet()) {
      Artifact destBreakpad = intermediateArtifacts.breakpadSym(breakpadFile.getKey());
      results.put(breakpadFile.getValue(), destBreakpad);
    }
    return results.build();
  }

  private void registerExtractTeamPrefixAction(Artifact teamPrefixFile) {
    String shellCommand = "set -e && "
        + "PLIST=$(mktemp -t teamprefix.plist) && trap \"rm ${PLIST}\" EXIT && "
        + extractPlistCommand(attributes.provisioningProfile()) + " > ${PLIST} && "
        + "/usr/libexec/PlistBuddy -c 'Print ApplicationIdentifierPrefix:0' ${PLIST} > "
        + teamPrefixFile.getShellEscapedExecPathString();
    ruleContext.registerAction(ObjcRuleClasses.spawnBashOnDarwinActionBuilder(shellCommand)
        .setMnemonic("ExtractIosTeamPrefix")
        .addInput(attributes.provisioningProfile())
        .addOutput(teamPrefixFile)
        .build(ruleContext));
  }

  private ReleaseBundlingSupport registerExtractEntitlementsAction(Artifact entitlements) {
    // See Apple Glossary (http://goo.gl/EkhXOb)
    // An Application Identifier is constructed as: TeamID.BundleID
    // TeamID is extracted from the provisioning profile.
    // BundleID consists of a reverse-DNS string to identify the app, where the last component
    // is the application name, and is specified as an attribute.
    String shellCommand = "set -e && "
        + "PLIST=$(mktemp -t entitlements.plist) && trap \"rm ${PLIST}\" EXIT && "
        + extractPlistCommand(attributes.provisioningProfile()) + " > ${PLIST} && "
        + "/usr/libexec/PlistBuddy -x -c 'Print Entitlements' ${PLIST} > "
        + entitlements.getShellEscapedExecPathString();
    ruleContext.registerAction(ObjcRuleClasses.spawnBashOnDarwinActionBuilder(shellCommand)
        .setMnemonic("ExtractIosEntitlements")
        .setProgressMessage("Extracting entitlements: " + ruleContext.getLabel())
        .addInput(attributes.provisioningProfile())
        .addOutput(entitlements)
        .build(ruleContext));

    return this;
  }

  private void registerEntitlementsVariableSubstitutionAction(Artifact in, Artifact out,
      Artifact prefix) {
    String escapedBundleId = ShellUtils.shellEscape(attributes.bundleId());
    String shellCommand = "set -e && "
        + "PREFIX=\"$(cat " + prefix.getShellEscapedExecPathString() + ")\" && "
        + "sed "
        // Replace .* from default entitlements file with bundle ID where suitable.
        + "-e \"s#${PREFIX}\\.\\*#${PREFIX}." + escapedBundleId + "#g\" "

        // Replace some variables that people put in their own entitlements files
        + "-e \"s#\\$(AppIdentifierPrefix)#${PREFIX}.#g\" "
        + "-e \"s#\\$(CFBundleIdentifier)#" + escapedBundleId + "#g\" "

        + in.getShellEscapedExecPathString() + " "
        + "> " + out.getShellEscapedExecPathString();
    ruleContext.registerAction(new SpawnAction.Builder()
        .setMnemonic("SubstituteIosEntitlements")
        .setShellCommand(shellCommand)
        .addInput(in)
        .addInput(prefix)
        .addOutput(out)
        .build(ruleContext));
  }

  /**
   * Registers an action to copy Swift standard library dylibs into app bundle.
   */
  private void registerSwiftStdlibActionsIfNecessary() {
    if (!objcProvider.is(USES_SWIFT)) {
      return;
    }

    ObjcConfiguration objcConfiguration = ObjcRuleClasses.objcConfiguration(ruleContext);

    CustomCommandLine.Builder commandLine = CustomCommandLine.builder()
        .addPath(intermediateArtifacts.swiftFrameworksFileZip().getExecPath())
        .add("Frameworks")
        .addPath(ObjcRuleClasses.SWIFT_STDLIB_TOOL)
        .add("--platform").add(IosSdkCommands.swiftPlatform(objcConfiguration))
        .addExecPath("--scan-executable", intermediateArtifacts.strippedSingleArchitectureBinary());

    ruleContext.registerAction(
        ObjcRuleClasses.spawnJavaOnDarwinActionBuilder(attributes.swiftStdlibToolDeployJar())
            .setMnemonic("SwiftStdlibCopy")
            .setCommandLine(commandLine.build())
            .addOutput(intermediateArtifacts.swiftFrameworksFileZip())
            .addInput(intermediateArtifacts.strippedSingleArchitectureBinary())
            .build(ruleContext));
  }

  private String extractPlistCommand(Artifact provisioningProfile) {
    return "security cms -D -i " + ShellUtils.shellEscape(provisioningProfile.getExecPathString());
  }

  private String codesignCommand(
      Artifact provisioningProfile, Artifact entitlements, String appDir) {
    String fingerprintCommand =
        "PLIST=$(mktemp -t cert.plist) && trap \"rm ${PLIST}\" EXIT && "
            + extractPlistCommand(provisioningProfile) + " > ${PLIST} && "
            + "/usr/libexec/PlistBuddy -c 'Print DeveloperCertificates:0' ${PLIST} | "
            + "openssl x509 -inform DER -noout -fingerprint | "
            + "cut -d= -f2 | sed -e 's#:##g'";
    return String.format(
        "/usr/bin/codesign --force --sign $(%s) --entitlements %s %s",
        fingerprintCommand,
        entitlements.getShellEscapedExecPathString(),
        appDir);
  }

  private Artifact getGeneratedVersionPlist() {
    return ruleContext.getRelatedArtifact(
        ruleContext.getUniqueDirectory("plists"), "-version.plist");
  }

  private Artifact getGeneratedEnvironmentPlist() {
    return ruleContext.getRelatedArtifact(
        ruleContext.getUniqueDirectory("plists"), "-environment.plist");
  }

  /**
   * Logic to access attributes required by application support. Attributes are required and
   * guaranteed to return a value or throw unless they are annotated with {@link Nullable} in which
   * case they can return {@code null} if no value is defined.
   */
  private static class Attributes {
    private final RuleContext ruleContext;

    private Attributes(RuleContext ruleContext) {
      this.ruleContext = ruleContext;
    }

    @Nullable
    String appIcon() {
      return stringAttribute("app_icon");
    }

    @Nullable
    String launchImage() {
      return stringAttribute("launch_image");
    }

    @Nullable
    Artifact provisioningProfile() {
      Artifact explicitProvisioningProfile =
          ruleContext.getPrerequisiteArtifact("provisioning_profile", Mode.TARGET);
      if (explicitProvisioningProfile != null) {
        return explicitProvisioningProfile;
      }
      return ruleContext.getPrerequisiteArtifact(":default_provisioning_profile", Mode.TARGET);
    }

    @Nullable
    Artifact entitlements() {
      return ruleContext.getPrerequisiteArtifact("entitlements", Mode.TARGET);
    }

    NestedSet<? extends Artifact> dependentLinkedBinaries() {
      if (ruleContext.attributes().getAttributeDefinition("binary") == null) {
        return NestedSetBuilder.emptySet(Order.STABLE_ORDER);
      }

      NestedSetBuilder<Artifact> linkedBinaries = NestedSetBuilder.stableOrder();
      for (ObjcProvider provider
          : ruleContext.getPrerequisites("binary", Mode.DONT_CHECK, ObjcProvider.class)) {
        linkedBinaries.addTransitive(provider.get(ObjcProvider.LINKED_BINARY));
      }

      return linkedBinaries.build();
    }

    FilesToRunProvider bundleMergeExecutable() {
      return checkNotNull(ruleContext.getExecutablePrerequisite("$bundlemerge", Mode.HOST));
    }

    Artifact iossim() {
      return checkNotNull(ruleContext.getPrerequisiteArtifact("$iossim", Mode.HOST));
    }

    Artifact runnerScriptTemplate() {
      return checkNotNull(
          ruleContext.getPrerequisiteArtifact("$runner_script_template", Mode.HOST));
    }

    /**
     * Returns the location of the swiftstdlibtoolzip deploy jar.
     */
    Artifact swiftStdlibToolDeployJar() {
      return ruleContext.getPrerequisiteArtifact("$swiftstdlibtoolzip_deploy", Mode.HOST);
    }

    String bundleId() {
      return checkNotNull(stringAttribute("bundle_id"));
    }

    ImmutableMap<String, Artifact> cpuSpecificBreakpadFiles() {
      ImmutableMap.Builder<String, Artifact> results = ImmutableMap.builder();
      if (ruleContext.attributes().has("binary", Type.LABEL)) {
        for (TransitiveInfoCollection prerequisite
            : ruleContext.getPrerequisites("binary", Mode.DONT_CHECK)) {
          ObjcProvider prerequisiteProvider =  prerequisite.getProvider(ObjcProvider.class);
          if (prerequisiteProvider != null) {
            Artifact sourceBreakpad = Iterables.getOnlyElement(
                prerequisiteProvider.get(ObjcProvider.BREAKPAD_FILE), null);
            if (sourceBreakpad != null) {
              String cpu =
                  prerequisite.getConfiguration().getFragment(ObjcConfiguration.class).getIosCpu();
              results.put(cpu, sourceBreakpad);
            }
          }
        }
      }
      return results.build();
    }

    @Nullable
    private String stringAttribute(String attribute) {
      String value = ruleContext.attributes().get(attribute, Type.STRING);
      return value.isEmpty() ? null : value;
    }
  }

  /**
   * Transition that results in one configured target per architecture set in {@code
   * --ios_multi_cpus}.
   */
  protected static class SplitArchTransition implements SplitTransition<BuildOptions> {

    @Override
    public final List<BuildOptions> split(BuildOptions buildOptions) {
      List<String> iosMultiCpus = buildOptions.get(ObjcCommandLineOptions.class).iosMultiCpus;
      if (iosMultiCpus.isEmpty()) {
        return defaultOptions(buildOptions);
      }

      ImmutableList.Builder<BuildOptions> splitBuildOptions = ImmutableList.builder();
      for (String iosCpu : iosMultiCpus) {
        BuildOptions splitOptions = buildOptions.clone();
        setArchitectureOptions(splitOptions, iosCpu);
        setAdditionalOptions(splitOptions, buildOptions);
        splitOptions.get(ObjcCommandLineOptions.class).configurationDistinguisher =
            getConfigurationDistinguisher();
        splitBuildOptions.add(splitOptions);
      }
      return splitBuildOptions.build();
    }

    /**
     * Returns the default options to use if no split architectures are specified.
     *
     * @param originalOptions original options before this transition
     */
    protected ImmutableList<BuildOptions> defaultOptions(BuildOptions originalOptions) {
      return ImmutableList.of();
    }

    /**
     * Sets or overwrites flags on the given split options.
     *
     * <p>Invoked once for each configuration produced by this transition.
     *
     * @param splitOptions options to use after this transition
     * @param originalOptions original options before this transition
     */
    protected void setAdditionalOptions(BuildOptions splitOptions, BuildOptions originalOptions) {}

    private void setArchitectureOptions(BuildOptions splitOptions, String iosCpu) {
      splitOptions.get(ObjcCommandLineOptions.class).iosSplitCpu = iosCpu;
      splitOptions.get(ObjcCommandLineOptions.class).iosCpu = iosCpu;
      if (splitOptions.get(ObjcCommandLineOptions.class).enableCcDeps) {
        // Only set the (CC-compilation) CPU for dependencies if explicitly required by the user.
        // This helps users of the iOS rules who do not depend on CC rules as these CPU values
        // require additional flags to work (e.g. a custom crosstool) which now only need to be set
        // if this feature is explicitly requested.
        splitOptions.get(BuildConfiguration.Options.class).cpu = "ios_" + iosCpu;
      }
    }

    @Override
    public boolean defaultsToSelf() {
      return true;
    }

    /**
     * Returns the configuration distinguisher for this transition instance.
     */
    protected ConfigurationDistinguisher getConfigurationDistinguisher() {
      return ConfigurationDistinguisher.APPLICATION;
    }

    /**
     * Value used to avoid multiple configurations from conflicting. No two instances of this
     * transition may exist with the same value in a single Bazel invocation.
     */
    enum ConfigurationDistinguisher {
      EXTENSION, APPLICATION, UNKNOWN
    }
  }
}
