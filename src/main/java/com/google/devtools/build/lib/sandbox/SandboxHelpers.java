// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.sandbox;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSet.Builder;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.Executor;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.fileset.FilesetActionContext;
import com.google.devtools.build.lib.buildtool.BuildRequest;
import com.google.devtools.build.lib.exec.SpawnInputExpander;
import com.google.devtools.build.lib.standalone.StandaloneSpawnStrategy;
import com.google.devtools.build.lib.util.Preconditions;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Helper methods that are shared by the different sandboxing strategies in this package. */
public final class SandboxHelpers {

  static void fallbackToNonSandboxedExecution(
      Spawn spawn, ActionExecutionContext actionExecutionContext, Executor executor)
      throws ExecException, InterruptedException {
    StandaloneSpawnStrategy standaloneStrategy =
        Preconditions.checkNotNull(executor.getContext(StandaloneSpawnStrategy.class));
    standaloneStrategy.exec(spawn, actionExecutionContext);
  }

  static void reportSubcommand(Executor executor, Spawn spawn) {
    if (executor.reportsSubcommands()) {
      executor.reportSubcommand(spawn);
    }
  }

  /**
   * Returns the inputs of a Spawn as a map of PathFragments relative to an execRoot to paths in the
   * host filesystem where the input files can be found.
   */
  public static Map<PathFragment, Path> getInputFiles(
      SpawnInputExpander spawnInputExpander,
      Path execRoot,
      Spawn spawn,
      ActionExecutionContext executionContext)
      throws IOException {
    Map<PathFragment, ActionInput> inputMap =
        spawnInputExpander.getInputMapping(
            spawn,
            executionContext.getArtifactExpander(),
            executionContext.getActionInputFileCache(),
            executionContext.getExecutor().getContext(FilesetActionContext.class));
    // SpawnInputExpander#getInputMapping uses ArtifactExpander#expandArtifacts to expand
    // middlemen and tree artifacts, which expands empty tree artifacts to no entry. However,
    // actions that accept TreeArtifacts as inputs generally expect that the empty directory is
    // created. So we add those explicitly here.
    // TODO(ulfjack): Move this code to SpawnInputExpander.
    for (ActionInput input : spawn.getInputFiles()) {
      if (input instanceof Artifact && ((Artifact) input).isTreeArtifact()) {
        List<Artifact> containedArtifacts = new ArrayList<>();
        executionContext.getArtifactExpander().expand((Artifact) input, containedArtifacts);
        // Attempting to mount a non-empty directory results in ERR_DIRECTORY_NOT_EMPTY, so we
        // only mount empty TreeArtifacts as directories.
        if (containedArtifacts.isEmpty()) {
          inputMap.put(input.getExecPath(), input);
        }
      }
    }

    Map<PathFragment, Path> inputFiles = new TreeMap<>();
    for (Map.Entry<PathFragment, ActionInput> e : inputMap.entrySet()) {
      Path inputPath =
          e.getValue() == SpawnInputExpander.EMPTY_FILE
              ? null
              : execRoot.getRelative(e.getValue().getExecPath());
      inputFiles.put(e.getKey(), inputPath);
    }
    return inputFiles;
  }

  public static ImmutableSet<PathFragment> getOutputFiles(Spawn spawn) {
    Builder<PathFragment> outputFiles = ImmutableSet.builder();
    for (ActionInput output : spawn.getOutputFiles()) {
      outputFiles.add(PathFragment.create(output.getExecPathString()));
    }
    return outputFiles.build();
  }

  static boolean shouldAllowNetwork(BuildRequest buildRequest, Spawn spawn) {
    // Allow network access, when --java_debug is specified, otherwise we can't connect to the
    // remote debug server of the test. This intentionally overrides the "block-network" execution
    // tag.
    if (buildRequest
        .getOptions(BuildConfiguration.Options.class)
        .testArguments
        .contains("--wrapper_script_flag=--debug")) {
      return true;
    }

    // If the Spawn requests to block network access, do so.
    if (spawn.getExecutionInfo().containsKey("block-network")) {
      return false;
    }

    // Network access is allowed by default.
    return true;
  }
}
