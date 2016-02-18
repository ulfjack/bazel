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

package com.google.devtools.build.lib.rules.test;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Root;
import com.google.devtools.build.lib.analysis.AnalysisEnvironment;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.PrerequisiteArtifacts;
import com.google.devtools.build.lib.analysis.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.RunfilesSupport;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.packages.TestSize;
import com.google.devtools.build.lib.packages.TestTimeout;
import com.google.devtools.build.lib.rules.test.TestProvider.TestParams;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.common.options.EnumConverter;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * Helper class to create test actions.
 */
public final class TestActionBuilder {

  private final RuleContext ruleContext;
  private RunfilesSupport runfilesSupport;
  private Artifact executable;
  private ExecutionInfoProvider executionRequirements;
  private InstrumentedFilesProvider instrumentedFiles;
  private int explicitShardCount;
  private Map<String, String> extraEnv;

  public TestActionBuilder(RuleContext ruleContext) {
    this.ruleContext = ruleContext;
    this.extraEnv = ImmutableMap.of();
  }

  /**
   * Creates the test actions and artifacts using the previously set parameters.
   *
   * @return ordered list of test status artifacts
   */
  public TestParams build() {
    Preconditions.checkState(runfilesSupport != null);
    boolean local = TargetUtils.isTestRuleAndRunsLocally(ruleContext.getRule());
    TestShardingStrategy strategy = ruleContext.getConfiguration().testShardingStrategy();
    int shards = strategy.getNumberOfShards(
        local, explicitShardCount, isTestShardingCompliant(),
        TestSize.getTestSize(ruleContext.getRule()));
    Preconditions.checkState(shards >= 0);
    return createTestAction(shards);
  }

  private boolean isTestShardingCompliant() {
    // See if it has a data dependency on the special target
    // //tools:test_sharding_compliant. Test runners add this dependency
    // to show they speak the sharding protocol.
    // There are certain cases where this heuristic may fail, giving
    // a "false positive" (where we shard the test even though the
    // it isn't supported). We may want to refine this logic, but
    // heuristically sharding is currently experimental. Also, we do detect
    // false-positive cases and return an error.
    return runfilesSupport.getRunfilesSymlinkNames().contains(
        new PathFragment("tools/test_sharding_compliant"));
  }

  /**
   * Set the runfiles and executable to be run as a test.
   */
  public TestActionBuilder setFilesToRunProvider(FilesToRunProvider provider) {
    Preconditions.checkNotNull(provider.getRunfilesSupport());
    Preconditions.checkNotNull(provider.getExecutable());
    this.runfilesSupport = provider.getRunfilesSupport();
    this.executable = provider.getExecutable();
    return this;
  }

  public TestActionBuilder setInstrumentedFiles(
      @Nullable InstrumentedFilesProvider instrumentedFiles) {
    this.instrumentedFiles = instrumentedFiles;
    return this;
  }

  public TestActionBuilder setExecutionRequirements(
      @Nullable ExecutionInfoProvider executionRequirements) {
    this.executionRequirements = executionRequirements;
    return this;
  }

  public TestActionBuilder setExtraEnv(@Nullable Map<String, String> extraEnv) {
    this.extraEnv = extraEnv == null
        ? ImmutableMap.<String, String> of() : ImmutableMap.copyOf(extraEnv);
    return this;
  }

  /**
   * Set the explicit shard count. Note that this may be overridden by the sharding strategy.
   */
  public TestActionBuilder setShardCount(int explicitShardCount) {
    this.explicitShardCount = explicitShardCount;
    return this;
  }

  /**
   * Converts to {@link TestActionBuilder.TestShardingStrategy}.
   */
  public static class ShardingStrategyConverter extends EnumConverter<TestShardingStrategy> {
    public ShardingStrategyConverter() {
      super(TestShardingStrategy.class, "test sharding strategy");
    }
  }

  /**
   * A strategy for running the same tests in many processes.
   */
  public static enum TestShardingStrategy {
    EXPLICIT {
      @Override public int getNumberOfShards(boolean isLocal, int shardCountFromAttr,
          boolean testShardingCompliant, TestSize testSize) {
        return Math.max(shardCountFromAttr, 0);
      }
    },

    EXPERIMENTAL_HEURISTIC {
      @Override public int getNumberOfShards(boolean isLocal, int shardCountFromAttr,
          boolean testShardingCompliant, TestSize testSize) {
        if (shardCountFromAttr >= 0) {
          return shardCountFromAttr;
        }
        if (isLocal || !testShardingCompliant) {
          return 0;
        }
        return testSize.getDefaultShards();
      }
    },

    DISABLED {
      @Override public int getNumberOfShards(boolean isLocal, int shardCountFromAttr,
          boolean testShardingCompliant, TestSize testSize) {
        return 0;
      }
    };

    public abstract int getNumberOfShards(boolean isLocal, int shardCountFromAttr,
        boolean testShardingCompliant, TestSize testSize);
  }

  /**
   * Creates a test action and artifacts for the given rule. The test action will
   * use the specified executable and runfiles.
   *
   * @return ordered list of test artifacts, one per action. These are used to drive
   *    execution in Skyframe, and by AggregatingTestListener and
   *    TestResultAnalyzer to keep track of completed and pending test runs.
   */
  private TestParams createTestAction(int shards) {
    PathFragment targetName = new PathFragment(ruleContext.getLabel().getName());
    BuildConfiguration config = ruleContext.getConfiguration();
    AnalysisEnvironment env = ruleContext.getAnalysisEnvironment();
    Root root = config.getTestLogsDirectory();

    NestedSetBuilder<Artifact> inputsBuilder = NestedSetBuilder.stableOrder();
    inputsBuilder.addTransitive(
        NestedSetBuilder.create(Order.STABLE_ORDER, runfilesSupport.getRunfilesMiddleman()));
    NestedSet<Artifact> testRuntime = PrerequisiteArtifacts.nestedSet(
        ruleContext, "$test_runtime", Mode.HOST);
    inputsBuilder.addTransitive(testRuntime);
    TestTargetProperties testProperties = new TestTargetProperties(
        ruleContext, executionRequirements);

    // If the test rule does not provide InstrumentedFilesProvider, there's not much that we can do.
    final boolean collectCodeCoverage = config.isCodeCoverageEnabled()
        && instrumentedFiles != null;

    TestTargetExecutionSettings executionSettings;
    if (collectCodeCoverage) {
      // Add instrumented file manifest artifact to the list of inputs. This file will contain
      // exec paths of all source files that should be included into the code coverage output.
      Collection<Artifact> metadataFiles =
          ImmutableList.copyOf(instrumentedFiles.getInstrumentationMetadataFiles());
      inputsBuilder.addTransitive(NestedSetBuilder.wrap(Order.STABLE_ORDER, metadataFiles));
      for (TransitiveInfoCollection dep :
          ruleContext.getPrerequisites(":coverage_support", Mode.HOST)) {
        inputsBuilder.addTransitive(dep.getProvider(FileProvider.class).getFilesToBuild());
      }
      for (TransitiveInfoCollection dep :
          ruleContext.getPrerequisites(":gcov", Mode.HOST)) {
        inputsBuilder.addTransitive(dep.getProvider(FileProvider.class).getFilesToBuild());
      }
      Artifact instrumentedFileManifest =
          InstrumentedFileManifestAction.getInstrumentedFileManifest(ruleContext,
              ImmutableList.copyOf(instrumentedFiles.getInstrumentedFiles()),
              metadataFiles);
      executionSettings = new TestTargetExecutionSettings(ruleContext, runfilesSupport,
          executable, instrumentedFileManifest, shards);
      inputsBuilder.add(instrumentedFileManifest);
    } else {
      executionSettings = new TestTargetExecutionSettings(ruleContext, runfilesSupport,
          executable, null, shards);
    }

    if (config.getRunUnder() != null) {
      Artifact runUnderExecutable = executionSettings.getRunUnderExecutable();
      if (runUnderExecutable != null) {
        inputsBuilder.add(runUnderExecutable);
      }
    }

    int runsPerTest = config.getRunsPerTestForLabel(ruleContext.getLabel());

    Iterable<Artifact> inputs = inputsBuilder.build();
    int shardRuns = (shards > 0 ? shards : 1);
    List<Artifact> results = Lists.newArrayListWithCapacity(runsPerTest * shardRuns);
    ImmutableList.Builder<Artifact> coverageArtifacts = ImmutableList.builder();

    for (int run = 0; run < runsPerTest; run++) {
      // Use a 1-based index for user friendliness.
      String runSuffix =
          runsPerTest > 1 ? String.format("_run_%d_of_%d", run + 1, runsPerTest) : "";
      for (int shard = 0; shard < shardRuns; shard++) {
        String suffix = (shardRuns > 1 ? String.format("_shard_%d_of_%d", shard + 1, shards) : "")
            + runSuffix;
        Artifact testLog = ruleContext.getPackageRelativeArtifact(
            targetName.getRelative("test" + suffix + ".log"), root);
        Artifact cacheStatus = ruleContext.getPackageRelativeArtifact(
            targetName.getRelative("test" + suffix + ".cache_status"), root);

        Artifact coverageArtifact = null;
        if (collectCodeCoverage) {
          coverageArtifact = ruleContext.getPackageRelativeArtifact(
              targetName.getRelative("coverage" + suffix + ".dat"), root);
          coverageArtifacts.add(coverageArtifact);
        }

        Artifact microCoverageArtifact = null;
        if (collectCodeCoverage && config.isMicroCoverageEnabled()) {
          microCoverageArtifact = ruleContext.getPackageRelativeArtifact(
              targetName.getRelative("coverage" + suffix + ".micro.dat"), root);
        }

        env.registerAction(new TestRunnerAction(
            ruleContext.getActionOwner(), inputs, testRuntime,
            testLog, cacheStatus,
            coverageArtifact, microCoverageArtifact,
            testProperties, extraEnv, executionSettings,
            shard, run, config, ruleContext.getWorkspaceName()));
        results.add(cacheStatus);
      }
    }
    // TODO(bazel-team): Passing the reportGenerator to every TestParams is a bit strange.
    Artifact reportGenerator = collectCodeCoverage
        ? ruleContext.getPrerequisiteArtifact(":coverage_report_generator", Mode.HOST) : null;
    return new TestParams(runsPerTest, shards, TestTimeout.getTestTimeout(ruleContext.getRule()),
        ruleContext.getRule().getRuleClass(), ImmutableList.copyOf(results),
        coverageArtifacts.build(), reportGenerator);
  }
}
