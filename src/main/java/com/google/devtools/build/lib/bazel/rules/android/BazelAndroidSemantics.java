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
package com.google.devtools.build.lib.bazel.rules.android;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.rules.android.AndroidCommon;
import com.google.devtools.build.lib.rules.android.AndroidSemantics;
import com.google.devtools.build.lib.rules.android.ApplicationManifest;
import com.google.devtools.build.lib.rules.android.ResourceApk;
import com.google.devtools.build.lib.rules.java.JavaCommon;
import com.google.devtools.build.lib.rules.java.JavaCompilationArtifacts;
import com.google.devtools.build.lib.rules.java.JavaSemantics;
import com.google.devtools.build.lib.rules.java.JavaTargetAttributes.Builder;

/**
 * Implementation of Bazel-specific behavior in Android rules.
 */
public class BazelAndroidSemantics implements AndroidSemantics {
  public static final BazelAndroidSemantics INSTANCE = new BazelAndroidSemantics();

  private BazelAndroidSemantics() {
  }

  @Override
  public void addTransitiveInfoProviders(RuleConfiguredTargetBuilder builder,
      RuleContext ruleContext, JavaCommon javaCommon, AndroidCommon androidCommon,
      Artifact jarWithAllClasses, ResourceApk resourceApk, Artifact zipAlignedApk,
      Iterable<Artifact> apksUnderTest) {
  }

  @Override
  public ApplicationManifest getManifestForRule(RuleContext ruleContext) {
    return ApplicationManifest.fromRule(ruleContext);
  }

  @Override
  public String getNativeDepsFileName() {
    return "nativedeps";
  }

  @Override
  public ImmutableList<String> getJavacArguments() {
    return ImmutableList.of(
        "-source", "7",
        "-target", "7");
  }

  @Override
  public ImmutableList<String> getDxJvmArguments() {
    return ImmutableList.of();
  }

  @Override
  public void addSigningArguments(
      RuleContext ruleContext, boolean sign, SpawnAction.Builder actionBuilder) {
    // ApkBuilder reads the signing key from the debug.keystore file, thus, we are at its mercy
    // for hermeticity. It turns out, it's not easy to coax ApkBuilder to read this key from a
    // file specified on the command line. Currently, it checks $ANDROID_SDK_HOME, $USER_HOME then
    // $HOME which means that we could make it hermetic by setting $ANDROID_SDK_HOME for the
    // ApkBuilder invocation.
    if (!sign) {
      actionBuilder.addArgument("-u");
    }
  }

  @Override
  public void addCoverageSupport(RuleContext ruleContext, AndroidCommon common,
      JavaSemantics javaSemantics, boolean forAndroidTest, Builder attributes,
      JavaCompilationArtifacts.Builder artifactsBuilder) {
  }
}
