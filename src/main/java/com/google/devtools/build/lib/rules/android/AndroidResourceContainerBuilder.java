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
package com.google.devtools.build.lib.rules.android;

import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.rules.android.AndroidResourcesProvider.ResourceContainer;
import com.google.devtools.build.lib.rules.java.JavaUtil;

import javax.annotation.Nullable;

/**
 * Encapsulates the process of building the AndroidResourceContainer.
 */
public final class AndroidResourceContainerBuilder {
  private LocalResourceContainer data;
  private Artifact manifest;
  private Artifact rOutput;
  private boolean inlineConstants = false;
  private Artifact symbolsFile;

  /** Provides the resources and assets for the ResourceContainer. */
  public AndroidResourceContainerBuilder withData(LocalResourceContainer data) {
    this.data = data;
    return this;
  }

  public AndroidResourceContainerBuilder withManifest(Artifact manifest) {
    this.manifest = manifest;
    return this;
  }

  public AndroidResourceContainerBuilder withROutput(Artifact rOutput) {
    this.rOutput = rOutput;
    return this;
  }

  public AndroidResourceContainerBuilder withInlinedConstants(boolean inlineContants) {
    this.inlineConstants = inlineContants;
    return this;
  }

  /** Creates a {@link ResourceContainer} from a {@link RuleContext}. */
  public ResourceContainer buildFromRule(RuleContext ruleContext, Artifact apk) {
    Preconditions.checkNotNull(this.manifest);
    Preconditions.checkNotNull(this.data);
    return new AndroidResourcesProvider.ResourceContainer(
            ruleContext.getLabel(),
            getJavaPackage(ruleContext, apk),
            getRenameManifestPackage(ruleContext),
            inlineConstants,
            apk,
            manifest,
            ruleContext.getImplicitOutputArtifact(
                AndroidRuleClasses.ANDROID_JAVA_SOURCE_JAR),
            data.getAssets(),
            data.getResources(),
            data.getAssetRoots(),
            data.getResourceRoots(),
            ruleContext.attributes().get("exports_manifest", Type.BOOLEAN),
            rOutput,
            symbolsFile);
  }

  private String getJavaPackage(RuleContext ruleContext, Artifact apk) {
    if (hasCustomPackage(ruleContext)) {
      return ruleContext.attributes().get("custom_package", Type.STRING);
    }
    // TODO(bazel-team): JavaUtil.getJavaPackageName does not check to see if the path is valid.
    // So we need to check for the JavaRoot.
    if (JavaUtil.getJavaRoot(apk.getExecPath()) == null) {
      ruleContext.ruleError("You must place your code under a directory named 'java' or "
          + "'javatests' for blaze to work. That directory (java,javatests) will be treated as "
          + "your java source root. Alternatively, you can set the 'custom_package' attribute.");
    }
    return JavaUtil.getJavaPackageName(apk.getExecPath());
  }

  private boolean hasCustomPackage(RuleContext ruleContext) {
    return ruleContext.attributes().isAttributeValueExplicitlySpecified("custom_package");
  }

  @Nullable
  private String getRenameManifestPackage(RuleContext ruleContext) {
    return ruleContext.attributes().isAttributeValueExplicitlySpecified("rename_manifest_package")
        ? ruleContext.attributes().get("rename_manifest_package", Type.STRING)
        : null;
  }

  public AndroidResourceContainerBuilder withSymbolsFile(Artifact symbolsFile) {
    this.symbolsFile = symbolsFile;
    return this;
  }
}