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

package com.google.devtools.build.lib.bazel;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.bazel.repository.HttpArchiveFunction;
import com.google.devtools.build.lib.bazel.repository.HttpJarFunction;
import com.google.devtools.build.lib.bazel.repository.LocalRepositoryFunction;
import com.google.devtools.build.lib.bazel.repository.NewLocalRepositoryFunction;
import com.google.devtools.build.lib.bazel.repository.RepositoryDelegatorFunction;
import com.google.devtools.build.lib.bazel.repository.RepositoryFunction;
import com.google.devtools.build.lib.bazel.rules.workspace.HttpArchiveRule;
import com.google.devtools.build.lib.bazel.rules.workspace.HttpJarRule;
import com.google.devtools.build.lib.bazel.rules.workspace.LocalRepositoryRule;
import com.google.devtools.build.lib.bazel.rules.workspace.NewLocalRepositoryRule;
import com.google.devtools.build.lib.blaze.BlazeDirectories;
import com.google.devtools.build.lib.blaze.BlazeModule;
import com.google.devtools.build.lib.blaze.BlazeVersionInfo;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.util.Clock;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.common.options.OptionsProvider;

import java.util.Map.Entry;
import java.util.UUID;

/**
 * Adds support for fetching external code.
 */
public class BazelRepositoryModule extends BlazeModule {

  // A map of repository handlers that can be looked up by rule class name.
  private final ImmutableMap<String, RepositoryFunction> repositoryHandlers;

  public BazelRepositoryModule() {
    repositoryHandlers = ImmutableMap.of(
        LocalRepositoryRule.NAME, new LocalRepositoryFunction(),
        HttpArchiveRule.NAME, new HttpArchiveFunction(),
        HttpJarRule.NAME, new HttpJarFunction(),
        NewLocalRepositoryRule.NAME, new NewLocalRepositoryFunction());
  }

  @Override
  public void blazeStartup(OptionsProvider startupOptions,
      BlazeVersionInfo versionInfo, UUID instanceId, BlazeDirectories directories,
      Clock clock) {
    for (RepositoryFunction handler : repositoryHandlers.values()) {
      handler.setDirectories(directories);
    }
  }

  @Override
  public void initializeRuleClasses(ConfiguredRuleClassProvider.Builder builder) {
    for (Entry<String, RepositoryFunction> handler : repositoryHandlers.entrySet()) {
      builder.addRuleDefinition(handler.getValue().getRuleDefinition());
    }
  }

  @Override
  public ImmutableMap<SkyFunctionName, SkyFunction> getSkyFunctions(BlazeDirectories directories) {
    ImmutableMap.Builder<SkyFunctionName, SkyFunction> builder = ImmutableMap.builder();

    // Bazel-specific repository downloaders.
    for (RepositoryFunction handler : repositoryHandlers.values()) {
      builder.put(handler.getSkyFunctionName(), handler);
    }

    // Create the delegator everything flows through.
    builder.put(SkyFunctions.REPOSITORY,
        new RepositoryDelegatorFunction(repositoryHandlers));
    return builder.build();
  }
}
