// Copyright 2014 The Bazel Authors. All rights reserved.
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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import com.google.devtools.build.lib.process.Command;
import com.google.devtools.build.lib.process.CommandException;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.vfs.Path;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Helper class for running the namespace sandbox. This runner prepares environment inside the
 * sandbox, handles sandbox output, performs cleanup and changes invocation if necessary.
 */
final class DarwinSandboxRunner extends SandboxRunner {
  private static final String SANDBOX_EXEC = "/usr/bin/sandbox-exec";

  private final Path sandboxExecRoot;
  private final Path sandboxConfigPath;
  private final Set<Path> writableDirs;
  private final Set<Path> inaccessiblePaths;

  DarwinSandboxRunner(
      Path sandboxPath,
      Path sandboxExecRoot,
      Set<Path> writableDirs,
      Set<Path> inaccessiblePaths,
      boolean verboseFailures) {
    super(verboseFailures);
    this.sandboxExecRoot = sandboxExecRoot;
    this.sandboxConfigPath = sandboxPath.getRelative("sandbox.sb");
    this.writableDirs = writableDirs;
    this.inaccessiblePaths = inaccessiblePaths;
  }

  static boolean isSupported(CommandEnvironment cmdEnv) {
    if (!ProcessWrapperRunner.isSupported(cmdEnv)) {
      return false;
    }

    List<String> args = new ArrayList<>();
    args.add(SANDBOX_EXEC);
    args.add("-p");
    args.add("(version 1) (allow default)");
    args.add("/usr/bin/true");

    ImmutableMap<String, String> env = ImmutableMap.of();
    File cwd = new File("/usr/bin");

    Command cmd = new Command(args.toArray(new String[0]), env, cwd);
    try {
      cmd.execute(
          /* stdin */ new byte[] {},
          Command.NO_OBSERVER,
          ByteStreams.nullOutputStream(),
          ByteStreams.nullOutputStream(),
          /* killSubprocessOnInterrupt */ true);
    } catch (CommandException e) {
      return false;
    }

    return true;
  }

  @Override
  protected Command getCommand(
      CommandEnvironment cmdEnv,
      List<String> arguments,
      Map<String, String> env,
      int timeout,
      boolean allowNetwork,
      boolean useFakeHostname,
      boolean useFakeUsername)
      throws IOException {
    writeConfig(allowNetwork);

    List<String> commandLineArgs = new ArrayList<>();
    commandLineArgs.add(SANDBOX_EXEC);
    commandLineArgs.add("-f");
    commandLineArgs.add(sandboxConfigPath.getPathString());
    commandLineArgs.addAll(ProcessWrapperRunner.getCommandLine(cmdEnv, arguments, timeout));
    return new Command(commandLineArgs.toArray(new String[0]), env, sandboxExecRoot.getPathFile());
  }

  private void writeConfig(boolean allowNetwork) throws IOException {
    try (PrintWriter out =
        new PrintWriter(
            new BufferedWriter(
                new OutputStreamWriter(sandboxConfigPath.getOutputStream(), UTF_8)))) {
      // Note: In Apple's sandbox configuration language, the *last* matching rule wins.
      out.println("(version 1)");
      out.println("(debug deny)");
      out.println("(allow default)");

      if (!allowNetwork) {
        out.println("(deny network*)");
        out.println("(allow network* (local ip \"localhost:*\"))");
        out.println("(allow network* (remote ip \"localhost:*\"))");
      }

      // By default, everything is read-only.
      out.println("(deny file-write*)");

      out.println("(allow file-write*");
      for (Path path : writableDirs) {
        out.println("    (subpath \"" + path.getPathString() + "\")");
      }
      out.println(")");

      if (!inaccessiblePaths.isEmpty()) {
        out.println("(deny file-read*");
        // The sandbox configuration file is not part of a cache key and sandbox-exec doesn't care
        // about ordering of paths in expressions, so it's fine if the iteration order is random.
        for (Path inaccessiblePath : inaccessiblePaths) {
          out.println("    (subpath \"" + inaccessiblePath + "\")");
        }
        out.println(")");
      }
    }
  }
}
