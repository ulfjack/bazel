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

package com.google.devtools.build.lib.rules.repository;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleFormatter;
import com.google.devtools.build.lib.rules.repository.RepositoryFunction.RepositoryFunctionException;
import com.google.devtools.build.lib.skyframe.FileValue;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

/**
 * A {@link SkyFunction} that implements delegation to the correct repository fetcher.
 *
 * <p>Each repository in the WORKSPACE file is represented by a {@link SkyValue} that is computed
 * by this function.
 */
public final class RepositoryDelegatorFunction implements SkyFunction {

  // A special repository delegate used to handle Skylark remote repositories if present.
  public static final String SKYLARK_DELEGATE_NAME = "$skylark";

  // Mapping of rule class name to RepositoryFunction.
  private final ImmutableMap<String, RepositoryFunction> handlers;

  // Delegate function to handle skylark remote repositories
  private final RepositoryFunction skylarkHandler;

  // This is a reference to isFetch in BazelRepositoryModule, which tracks whether the current
  // command is a fetch. Remote repository lookups are only allowed during fetches.
  private final AtomicBoolean isFetch;

  private Map<String, String> clientEnvironment;

  public RepositoryDelegatorFunction(
      ImmutableMap<String, RepositoryFunction> handlers,
      @Nullable RepositoryFunction skylarkHandler,
      AtomicBoolean isFetch) {
    this.handlers = handlers;
    this.skylarkHandler = skylarkHandler;
    this.isFetch = isFetch;
  }

  public void setClientEnvironment(Map<String, String> clientEnvironment) {
    this.clientEnvironment = clientEnvironment;
  }

  private void setupRepositoryRoot(Path repoRoot) throws RepositoryFunctionException {
    try {
      FileSystemUtils.deleteTree(repoRoot);
      FileSystemUtils.createDirectoryAndParents(repoRoot.getParentDirectory());
    } catch (IOException e) {
      throw new RepositoryFunctionException(e, Transience.TRANSIENT);
    }
  }

  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws SkyFunctionException, InterruptedException {
    RepositoryName repositoryName = (RepositoryName) skyKey.argument();
    Rule rule = RepositoryFunction.getRule(repositoryName, null, env);
    if (rule == null) {
      return null;
    }

    BlazeDirectories directories = PrecomputedValue.BLAZE_DIRECTORIES.get(env);
    if (directories == null) {
      return null;
    }
    RepositoryFunction handler;
    if (rule.getRuleClassObject().isSkylark()) {
      handler = skylarkHandler;
    } else {
      handler = handlers.get(rule.getRuleClass());
    }
    if (handler == null) {
      throw new RepositoryFunctionException(new EvalException(
          Location.fromFile(directories.getWorkspace().getRelative("WORKSPACE")),
          "Could not find handler for " + rule), Transience.PERSISTENT);
    }

    Path repoRoot =
        RepositoryFunction.getExternalRepositoryDirectory(directories).getRelative(rule.getName());
    byte[] ruleSpecificData = handler.getRuleSpecificMarkerData(rule, env);
    if (ruleSpecificData == null) {
      return null;
    }
    byte[] ruleKey = computeRuleKey(rule, ruleSpecificData);
    Path markerPath = getMarkerPath(directories, rule);

    handler.setClientEnvironment(clientEnvironment);
    if (handler.isLocal(rule)) {
      // Local repositories are always fetched because the operation is generally fast and they do
      // not depend on non-local data, so it does not make much sense to try to cache from across
      // server instances.
      setupRepositoryRoot(repoRoot);
      SkyValue localRepo = handler.fetch(rule, repoRoot, directories, env);
      if (localRepo != null) {
        writeMarkerFile(markerPath, ruleKey);
      }
      return localRepo;
    }

    // We check the repository root for existence here, but we can't depend on the FileValue,
    // because it's possible that we eventually create that directory in which case the FileValue
    // and the state of the file system would be inconsistent.

    boolean markerUpToDate = isFilesystemUpToDate(markerPath, ruleKey);
    if (markerUpToDate && repoRoot.exists()) {
      // Now that we know that it exists, we can declare a Skyframe dependency on the repository
      // root.
      RepositoryFunction.getRepositoryDirectory(repoRoot, env);
      if (env.valuesMissing()) {
        return null;
      }

      return RepositoryDirectoryValue.create(repoRoot);
    }

    if (isFetch.get()) {
      // Fetching enabled, go ahead.
      setupRepositoryRoot(repoRoot);
      SkyValue result = handler.fetch(rule, repoRoot, directories, env);
      if (env.valuesMissing()) {
        return null;
      }

      // No new Skyframe dependencies must be added between calling the repository implementation
      // and writing the marker file because if they aren't computed, it would cause a Skyframe
      // restart thus calling the possibly very slow (networking, decompression...) fetch()
      // operation again. So we write the marker file here immediately.
      writeMarkerFile(markerPath, ruleKey);
      return result;
    }

    if (!repoRoot.exists()) {
      // The repository isn't on the file system, there is nothing we can do.
      throw new RepositoryFunctionException(new IOException(
          "to fix, run\n\tbazel fetch //...\nExternal repository " + repositoryName
              + " not found and fetching repositories is disabled."),
          Transience.TRANSIENT);
    }

    // Declare a Skyframe dependency so that this is re-evaluated when something happens to the
    // directory.
    FileValue repoRootValue = RepositoryFunction.getRepositoryDirectory(repoRoot, env);
    if (env.valuesMissing()) {
      return null;
    }

    // Try to build with whatever is on the file system and emit a warning.
    env.getListener().handle(Event.warn(rule.getLocation(), String.format(
        "External repository '%s' is not up-to-date and fetching is disabled. To update, "
        + "run the build without the '--nofetch' command line option.",
        rule.getName())));

    return RepositoryDirectoryValue.fetchingDelayed(repoRootValue.realRootedPath().asPath());
  }

  private final byte[] computeRuleKey(Rule rule, byte[] ruleSpecificData) {
    return new Fingerprint()
        .addBytes(RuleFormatter.serializeRule(rule).build().toByteArray())
        .addBytes(ruleSpecificData)
        // This is to make the fingerprint different after adding names to the generated
        // WORKSPACE files so they will get re-created, because otherwise there are
        // annoying warnings for all of them.
        // TODO(bsilver16384@gmail.com): Remove this once everybody's upgraded to the
        // new WORKSPACE files.
        .addInt(1)
        .digestAndReset();
  }

  /**
   * Checks if the state of the repository in the file system is consistent with the rule in the
   * WORKSPACE file.
   *
   * <p>Deletes the marker file if not so that no matter what happens after, the state of the file
   * system stays consistent.
   */
  private final boolean isFilesystemUpToDate(Path markerPath, byte[] ruleKey)
      throws RepositoryFunctionException {
    try {
      if (!markerPath.exists()) {
        return false;
      }

      byte[] content = FileSystemUtils.readContent(markerPath);
      boolean result = Arrays.equals(ruleKey, content);
      if (!result) {
        // So that we are in a consistent state if something happens while fetching the repository
        markerPath.delete();
      }

      return result;
    } catch (IOException e) {
      throw new RepositoryFunctionException(e, Transience.TRANSIENT);
    }
  }

  private final void writeMarkerFile(Path markerPath, byte[] ruleKey)
      throws RepositoryFunctionException {
    try {
      FileSystemUtils.writeContent(markerPath, ruleKey);
    } catch (IOException e) {
      throw new RepositoryFunctionException(e, Transience.TRANSIENT);
    }
  }

  private static Path getMarkerPath(BlazeDirectories directories, Rule rule) {
    return RepositoryFunction.getExternalRepositoryDirectory(directories)
        .getChild("@" + rule.getName() + ".marker");
  }

  @Override
  public String extractTag(SkyKey skyKey) {
    return null;
  }
}
