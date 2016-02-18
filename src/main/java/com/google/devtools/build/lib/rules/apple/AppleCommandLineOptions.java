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

package com.google.devtools.build.lib.rules.apple;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Multimap;
import com.google.devtools.build.lib.Constants;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration.DefaultLabelConverter;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.common.options.Converters.CommaSeparatedOptionListConverter;
import com.google.devtools.common.options.Option;

import java.util.List;

/**
 * Command-line options for building for Apple platforms.
 */
public class AppleCommandLineOptions extends FragmentOptions {

  // TODO(bazel-team): Validate version flag value.
  @Option(name = "xcode_version",
      defaultValue = "",
      category = "undocumented",
      help = "If specified, uses xcode of the given version for relevant build actions. "
          + "If unspecified, uses the executor default version of xcode."
      )
  public String xcodeVersion;

  // TODO(bazel-team): Validate version flag value.
  @Option(name = "ios_sdk_version",
      // TODO(bazel-team): Make this flag optional, and infer SDKROOT based on executor default.
      defaultValue = DEFAULT_IOS_SDK_VERSION,
      category = "build",
      help = "Specifies the version of the iOS SDK to use to build iOS applications."
      )
  public String iosSdkVersion;

  @VisibleForTesting public static final String DEFAULT_IOS_SDK_VERSION = "8.4";
  
  @Option(name = "ios_cpu",
      defaultValue = DEFAULT_IOS_CPU,
      category = "build",
      help = "Specifies to target CPU of iOS compilation.")
  public String iosCpu;

  @Option(name = "ios_multi_cpus",
      converter = CommaSeparatedOptionListConverter.class,
      defaultValue = "",
      category = "flags",
      help = "Comma-separated list of architectures to build an ios_application with. The result "
          + "is a universal binary containing all specified architectures.")
  public List<String> iosMultiCpus;
  
  @VisibleForTesting static final String DEFAULT_IOS_CPU = "x86_64";
  
  @Option(name = "default_ios_provisiong_profile",
      defaultValue = "",
      category = "undocumented",
      converter = DefaultProvisioningProfileConverter.class)
  public Label defaultProvisioningProfile;

  /** Converter for --default_ios_provisioning_profile. */
  public static class DefaultProvisioningProfileConverter extends DefaultLabelConverter {
    public DefaultProvisioningProfileConverter() {
      super(Constants.TOOLS_REPOSITORY + "//tools/objc:default_provisioning_profile");
    }
  }

  private Platform getPlatform() {
    for (String architecture : iosMultiCpus) {
      if (Platform.forArch(architecture) == Platform.IOS_DEVICE) {
        return Platform.IOS_DEVICE;
      }
    }
    return Platform.forArch(iosCpu);
  }
  
  @Override
  public void addAllLabels(Multimap<String, Label> labelMap) {
    if (getPlatform() == Platform.IOS_DEVICE) {
      labelMap.put("default_provisioning_profile", defaultProvisioningProfile);
    }
  }
}