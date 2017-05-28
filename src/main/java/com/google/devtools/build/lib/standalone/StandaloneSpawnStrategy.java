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
package com.google.devtools.build.lib.standalone;

import com.google.common.collect.ImmutableMap;
import com.google.common.eventbus.EventBus;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionMetadata;
import com.google.devtools.build.lib.actions.ActionStatusMessage;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ExecutionStrategy;
import com.google.devtools.build.lib.actions.Executor;
import com.google.devtools.build.lib.actions.ResourceManager;
import com.google.devtools.build.lib.actions.ResourceManager.ResourceHandle;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnActionContext;
import com.google.devtools.build.lib.actions.Spawns;
import com.google.devtools.build.lib.actions.UserExecException;
import com.google.devtools.build.lib.process.AbnormalTerminationException;
import com.google.devtools.build.lib.process.Command;
import com.google.devtools.build.lib.process.CommandException;
import com.google.devtools.build.lib.process.TerminationStatus;
import com.google.devtools.build.lib.rules.apple.AppleConfiguration;
import com.google.devtools.build.lib.rules.apple.AppleHostInfo;
import com.google.devtools.build.lib.rules.apple.DottedVersion;
import com.google.devtools.build.lib.util.CommandFailureUtils;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.util.OsUtils;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.Path;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Strategy that uses subprocessing to execute a process.
 */
@ExecutionStrategy(name = { "standalone", "local" }, contextType = SpawnActionContext.class)
public class StandaloneSpawnStrategy implements SpawnActionContext {
  private final boolean verboseFailures;
  private final Path processWrapper;
  private final Path execRoot;
  private final String productName;
  private final ResourceManager resourceManager;

  public StandaloneSpawnStrategy(Path execRoot, boolean verboseFailures, String productName) {
    this(execRoot, verboseFailures, productName, ResourceManager.instance());
  }

  public StandaloneSpawnStrategy(
      Path execRoot, boolean verboseFailures, String productName, ResourceManager resourceManager) {
    this.verboseFailures = verboseFailures;
    this.execRoot = execRoot;
    this.processWrapper = execRoot.getRelative(
        "_bin/process-wrapper" + OsUtils.executableExtension());
    this.productName = productName;
    this.resourceManager = resourceManager;
  }

  /**
   * Executes the given {@code spawn}.
   */
  @Override
  public void exec(Spawn spawn,
      ActionExecutionContext actionExecutionContext)
      throws ExecException, InterruptedException {
    EventBus eventBus = actionExecutionContext.getExecutor().getEventBus();
    ActionExecutionMetadata owner = spawn.getResourceOwner();
    eventBus.post(ActionStatusMessage.schedulingStrategy(owner));
    try (ResourceHandle handle =
        resourceManager.acquireResources(owner, spawn.getLocalResources())) {
      eventBus.post(ActionStatusMessage.runningStrategy(owner, "standalone"));
      actuallyExec(spawn, actionExecutionContext);
    }
  }

  /**
   * Executes the given {@code spawn}.
   */
  private void actuallyExec(Spawn spawn,
      ActionExecutionContext actionExecutionContext)
      throws ExecException {
    Executor executor = actionExecutionContext.getExecutor();

    if (executor.reportsSubcommands()) {
      executor.reportSubcommand(spawn);
    }

    int timeoutSeconds = Spawns.getTimeoutSeconds(spawn);

    // We must wrap the subprocess with process-wrapper to kill the process tree.
    // All actions therefore depend on the process-wrapper file. Since it's embedded,
    // we don't bother with declaring it as an input.
    List<String> args = new ArrayList<>();
    if (OS.getCurrent() != OS.WINDOWS) {
      // TODO(bazel-team): process-wrapper seems to work on Windows, but requires
      // additional setup as it is an msys2 binary, so it needs msys2 DLLs on %PATH%.
      // Disable it for now to make the setup easier and to avoid further PATH hacks.
      // Ideally we should have a native implementation of process-wrapper for Windows.
      args.add(processWrapper.getPathString());
      args.add(Integer.toString(timeoutSeconds));
      args.add("5"); /* kill delay: give some time to print stacktraces and whatnot. */

      // TODO(bazel-team): use process-wrapper redirection so we don't have to
      // pass test logs through the Java heap.
      args.add("-"); /* stdout. */
      args.add("-"); /* stderr. */
    }
    args.addAll(spawn.getArguments());

    String cwd = executor.getExecRoot().getPathString();
    Command cmd =
        new Command(
            args.toArray(new String[] {}),
            locallyDeterminedEnv(execRoot, productName, spawn.getEnvironment()),
            new File(cwd),
            OS.getCurrent() == OS.WINDOWS && timeoutSeconds >= 0 ? timeoutSeconds * 1000 : -1);

    FileOutErr outErr = actionExecutionContext.getFileOutErr();
    try {
      cmd.execute(
          /* stdin */ new byte[] {},
          Command.NO_OBSERVER,
          outErr.getOutputStream(),
          outErr.getErrorStream(),
          /*killSubprocessOnInterrupt*/ true);
    } catch (AbnormalTerminationException e) {
      TerminationStatus status = e.getResult().getTerminationStatus();
      boolean timedOut = !status.exited() && (
          status.timedout() || status.getTerminatingSignal() == 14 /* SIGALRM */);
      String message =
          CommandFailureUtils.describeCommandFailure(
              verboseFailures, spawn.getArguments(), spawn.getEnvironment(), cwd);
      throw new UserExecException(String.format("%s: %s", message, e), timedOut);
    } catch (CommandException e) {
      String message = CommandFailureUtils.describeCommandFailure(
          verboseFailures, spawn.getArguments(), spawn.getEnvironment(), cwd);
      throw new UserExecException(message, e);
    }
  }

  @Override
  public String toString() {
    return "standalone";
  }

  /**
   * Adds to the given environment all variables that are dependent on system state of the host
   * machine.
   *
   * <p>Admittedly, hermeticity is "best effort" in such cases; these environment values should be
   * as tied to configuration parameters as possible.
   *
   * <p>For example, underlying iOS toolchains require that SDKROOT resolve to an absolute system
   * path, but, when selecting which SDK to resolve, the version number comes from build
   * configuration.
   *
   * @return the new environment, comprised of the old environment plus any new variables
   * @throws UserExecException if any variables dependent on system state could not be resolved
   */
  public static ImmutableMap<String, String> locallyDeterminedEnv(
      Path execRoot, String productName, ImmutableMap<String, String> env)
      throws UserExecException {
    // TODO(bazel-team): Remove apple-specific logic from this class.
    ImmutableMap.Builder<String, String> newEnvBuilder = ImmutableMap.builder();
    newEnvBuilder.putAll(env);
    // Empty developer dir indicates to use the system default.
    // TODO(bazel-team): Bazel's view of the xcode version and developer dir
    // should be explicitly set for build hermiticity.
    String developerDir = "";
    if (env.containsKey(AppleConfiguration.XCODE_VERSION_ENV_NAME)) {
      developerDir =
          getDeveloperDir(
              execRoot, productName, env.get(AppleConfiguration.XCODE_VERSION_ENV_NAME));
      newEnvBuilder.put("DEVELOPER_DIR", developerDir);
    }
    if (env.containsKey(AppleConfiguration.APPLE_SDK_VERSION_ENV_NAME)) {
      // The Apple platform is needed to select the appropriate SDK.
      if (!env.containsKey(AppleConfiguration.APPLE_SDK_PLATFORM_ENV_NAME)) {
        throw new UserExecException("Could not resolve apple platform for determining SDK");
      }
      String iosSdkVersion = env.get(AppleConfiguration.APPLE_SDK_VERSION_ENV_NAME);
      String appleSdkPlatform = env.get(AppleConfiguration.APPLE_SDK_PLATFORM_ENV_NAME);
      newEnvBuilder.put(
          "SDKROOT",
          getSdkRootEnv(execRoot, productName, developerDir, iosSdkVersion, appleSdkPlatform));
    }
    return newEnvBuilder.build();
  }

  private static String getDeveloperDir(Path execRoot, String productName, String xcodeVersion)
      throws UserExecException {
    if (OS.getCurrent() != OS.DARWIN) {
      throw new UserExecException(
          "Cannot locate xcode developer directory on non-darwin operating system");
    }
    return AppleHostInfo.getDeveloperDir(execRoot, DottedVersion.fromString(xcodeVersion),
        productName);
  }

  private static String getSdkRootEnv(
      Path execRoot,
      String productName,
      String developerDir,
      String iosSdkVersion,
      String appleSdkPlatform)
      throws UserExecException {
    if (OS.getCurrent() != OS.DARWIN) {
      throw new UserExecException("Cannot locate iOS SDK on non-darwin operating system");
    }
    return AppleHostInfo.getSdkRoot(execRoot, developerDir, iosSdkVersion, appleSdkPlatform,
        productName);
  }

  @Override
  public boolean shouldPropagateExecException() {
    return false;
  }
}
