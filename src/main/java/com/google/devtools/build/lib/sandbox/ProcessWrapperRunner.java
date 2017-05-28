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

import com.google.devtools.build.lib.process.Command;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.util.OsUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * This runner runs process-wrapper inside a sandboxed execution root, which should work on most
 * platforms and gives at least some isolation between running actions.
 */
final class ProcessWrapperRunner extends SandboxRunner {
  private static final String PROCESS_WRAPPER = "process-wrapper" + OsUtils.executableExtension();

  private final Path sandboxExecRoot;

  ProcessWrapperRunner(Path sandboxExecRoot, boolean verboseFailures) {
    super(verboseFailures);
    this.sandboxExecRoot = sandboxExecRoot;
  }

  static boolean isSupported(CommandEnvironment cmdEnv) {
    // We can only use this runner, if the process-wrapper exists in the embedded tools.
    // This might not always be the case, e.g. while bootstrapping.
    return getProcessWrapper(cmdEnv) != null;
  }

  /** Returns the PathFragment of the process wrapper binary, or null if it doesn't exist. */
  static Path getProcessWrapper(CommandEnvironment cmdEnv) {
    PathFragment execPath = cmdEnv.getBlazeWorkspace().getBinTools().getExecPath(PROCESS_WRAPPER);
    return execPath != null ? cmdEnv.getExecRoot().getRelative(execPath) : null;
  }

  @Override
  protected Command getCommand(
      CommandEnvironment cmdEnv,
      List<String> spawnArguments,
      Map<String, String> env,
      int timeout,
      boolean allowNetwork,
      boolean useFakeHostname,
      boolean useFakeUsername) {
    List<String> commandLineArgs = getCommandLine(cmdEnv, spawnArguments, timeout);
    return new Command(commandLineArgs.toArray(new String[0]), env, sandboxExecRoot.getPathFile());
  }

  static List<String> getCommandLine(
      CommandEnvironment cmdEnv, List<String> spawnArguments, int timeout) {
    List<String> commandLineArgs = new ArrayList<>(5 + spawnArguments.size());
    commandLineArgs.add(getProcessWrapper(cmdEnv).getPathString());
    commandLineArgs.add(Integer.toString(timeout));
    commandLineArgs.add("5"); /* kill delay: give some time to print stacktraces and whatnot. */
    commandLineArgs.add("-"); /* stdout. */
    commandLineArgs.add("-"); /* stderr. */
    commandLineArgs.addAll(spawnArguments);
    return commandLineArgs;
  }
}
