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

package com.google.devtools.build.lib.rules.objc;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.RuleConfiguredTarget;
import com.google.devtools.build.lib.analysis.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.rules.RuleConfiguredTargetFactory;
import com.google.devtools.build.lib.rules.objc.ObjcActionsBuilder.ExtraLinkArgs;
import com.google.devtools.build.lib.rules.objc.ObjcLibrary.InfoplistsFromRule;

/**
 * Contains information needed to create a {@link RuleConfiguredTarget} and invoke test runners
 * for some instantiation of this rule.
 */
public abstract class IosTest implements RuleConfiguredTargetFactory {
  private static final ImmutableList<SdkFramework> AUTOMATIC_SDK_FRAMEWORKS_FOR_XCTEST =
      ImmutableList.of(new SdkFramework("XCTest"));

  public static final String TARGET_DEVICE = "target_device";
  public static final String IS_XCTEST = "xctest";
  public static final String XCTEST_APP = "xctest_app";

  @VisibleForTesting
  public static final String REQUIRES_SOURCE_ERROR =
      "ios_test requires at least one source file in srcs or non_arc_srcs";

  public static final String DOT_IPA = ".ipa";

  /**
   * Creates a target, including registering actions, just as {@link #create(RuleContext} does.
   * The difference between {@link #create(RuleContext)} and this method is that this method does
   * only what is needed to support tests on the environment besides generate the Xcodeproj file
   * and build the app and test {@code .ipa}s. The {@link #create(RuleContext)} method delegates
   * to this method.
   */
  protected abstract ConfiguredTarget create(RuleContext ruleContext, ObjcCommon common,
      XcodeProvider xcodeProvider, NestedSet<Artifact> filesToBuild) throws InterruptedException;

  @Override
  public final ConfiguredTarget create(RuleContext ruleContext) throws InterruptedException {
    ObjcCommon common = getObjcCommon(ruleContext);
    OptionsProvider optionsProvider = ObjcLibrary.optionsProvider(ruleContext,
        new InfoplistsFromRule(
            ruleContext.getPrerequisiteArtifacts("infoplist", Mode.TARGET).list()));
    J2ObjcSrcsProvider j2ObjcSrcsProvider = ObjcRuleClasses.j2ObjcSrcsProvider(ruleContext);
    Bundling bundling =
        ObjcBinary.bundling(ruleContext, common.getObjcProvider(),  optionsProvider);

    ObjcBinary.checkAttributes(ruleContext, common, bundling);
    if (!common.getCompilationArtifacts().get().getArchive().isPresent()) {
      ruleContext.ruleError(REQUIRES_SOURCE_ERROR);
    }

    Optional<Artifact> testIpa = testIpaBundle(ruleContext);
    ExtraLinkArgs extraLinkArgs = testIpa.isPresent()
        ? new ExtraLinkArgs("-bundle") : new ExtraLinkArgs();

    XcodeProvider xcodeProvider = ObjcBinary.xcodeProvider(
        ruleContext, common, bundling.getInfoplistMerging(), optionsProvider);
    if (isXcTest(ruleContext)) {
      XcodeProvider appIpaXcodeProvider =
          ruleContext.getPrerequisite(XCTEST_APP, Mode.TARGET, XcodeProvider.class);
      xcodeProvider = xcodeProvider
          .toBuilder()
          .setTestHost(appIpaXcodeProvider)
          .setProductType(XcodeProductType.UNIT_TEST)
          .build();
    }

    ObjcBinary.registerActions(
        ruleContext, common, xcodeProvider, extraLinkArgs, optionsProvider, j2ObjcSrcsProvider,
        bundling);

    return create(ruleContext, common, xcodeProvider, commonFilesToBuild(ruleContext, common));
  }

  /**
   * Returns files to build that are present regardless of how tests are run.
   */
  private NestedSet<Artifact> commonFilesToBuild(RuleContext ruleContext, ObjcCommon common) {
    return NestedSetBuilder.<Artifact>stableOrder()
        .addAll(appIpaBundle(ruleContext).asSet())
        .addTransitive(common.getStoryboards().getOutputZips())
        .addAll(Xcdatamodel.outputZips(common.getDatamodels()))
        .add(ruleContext.getImplicitOutputArtifact(ObjcRuleClasses.PBXPROJ))
        .addAll(testIpaBundle(ruleContext).asSet())
        .build();
  }

  private static ObjcCommon getObjcCommon(RuleContext ruleContext) {
    ImmutableList<SdkFramework> extraSdkFrameworks = isXcTest(ruleContext)
        ? AUTOMATIC_SDK_FRAMEWORKS_FOR_XCTEST : ImmutableList.<SdkFramework>of();

    return ObjcLibrary.common(ruleContext, extraSdkFrameworks, /*alwayslink=*/false,
        new ObjcLibrary.ExtraImportLibraries(), new ObjcLibrary.Defines());
  }

  /**
   * Returns the application {@code .ipa}, or an {@link Optional#absent()} if an error occurred. For
   * xctests, this is the test harness. For other tests, this is simply the binary bundle generated
   * by this rule.
   */
  protected static Optional<Artifact> appIpaBundle(RuleContext ruleContext) {
    if (isXcTest(ruleContext)) {
      FileProvider fileProvider =
          ruleContext.getPrerequisite(XCTEST_APP, Mode.TARGET, FileProvider.class);
      for (Artifact artifact : fileProvider.getFilesToBuild().toList()) {
        if (artifact.getFilename().endsWith(DOT_IPA)) {
          return Optional.of(artifact);
        }
      }
      ruleContext.attributeError(XCTEST_APP,
          "No associated output .ipa file found on the rule referenced by this attribute");
      return Optional.absent();
    } else {
      return Optional.of(ruleContext.getImplicitOutputArtifact(ObjcBinaryRule.IPA));
    }
  }

  /**
   * Returns the test {@code .ipa}, which is only present for xctests. If present, it is the binary
   * bundle generated by this rule.
   */
  protected static Optional<Artifact> testIpaBundle(RuleContext ruleContext) {
    if (isXcTest(ruleContext)) {
      return Optional.of(ruleContext.getImplicitOutputArtifact(ObjcBinaryRule.IPA));
    } else {
      return Optional.absent();
    }
  }

  protected static IosDeviceProvider targetDevice(RuleContext ruleContext) {
    IosDeviceProvider targetDevice =
        ruleContext.getPrerequisite(IosTest.TARGET_DEVICE, Mode.TARGET, IosDeviceProvider.class);
    if (targetDevice == null) {
      targetDevice = new IosDeviceProvider.Builder()
          .setType("iPhone")
          .setIosVersion(ObjcRuleClasses.objcConfiguration(ruleContext).getIosSimulatorVersion())
          .setLocale("en")
          .build();
    }
    return targetDevice;
  }

  private static boolean isXcTest(RuleContext ruleContext) {
    return ruleContext.attributes().get(IS_XCTEST, Type.BOOLEAN);
  }
}
