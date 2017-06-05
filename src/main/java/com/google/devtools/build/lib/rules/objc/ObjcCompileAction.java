// Copyright 2016 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.rules.objc;

import static com.google.devtools.build.lib.collect.nestedset.Order.STABLE_ORDER;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ArtifactResolver;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.Executor;
import com.google.devtools.build.lib.actions.RunfilesSupplier;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.extra.SpawnInfo;
import com.google.devtools.build.lib.analysis.actions.CommandLine;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.cpp.IncludeScanningContext;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadCompatible;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.rules.apple.AppleConfiguration;
import com.google.devtools.build.lib.rules.apple.Platform;
import com.google.devtools.build.lib.rules.cpp.CppCompileAction.DotdFile;
import com.google.devtools.build.lib.rules.cpp.CppFileTypes;
import com.google.devtools.build.lib.rules.cpp.HeaderDiscovery;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.util.deps.DependencySet;
import com.google.devtools.build.lib.util.resources.ResourceSet;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * An action that compiles objc or objc++ source.
 *
 * <p>We don't use a plain SpawnAction here because we implement .d input pruning, which requires
 * post-execution filtering of input artifacts.
 *
 * <p>Additionally the header thinning feature is implemented here which, like .d input pruning,
 * reduces the action inputs to just the required set of headers for improved performance. Unlike
 * dotd it does so via the discoverInputs mechanism before execution.
 *
 * <p>We don't use a CppCompileAction because the ObjcCompileAction uses custom logic instead of the
 * CROSSTOOL to construct its command line.
 */
public class ObjcCompileAction extends SpawnAction {

 /**
   * A spawn that provides all headers to sandboxed execution to allow pruned headers to be
   * re-introduced into action inputs.
   */
  public class ObjcCompileActionSpawn extends ActionSpawn {

    public ObjcCompileActionSpawn(Map<String, String> clientEnv) {
      super(clientEnv);
    }

    @Override
    public Iterable<? extends ActionInput> getInputFiles() {
      ImmutableList.Builder<ActionInput> listBuilder =
          ImmutableList.<ActionInput>builder().addAll(super.getInputFiles());
      // Normally discoveredInputs should not be null when this is called, however that may occur if
      // the extra action feature is used
      if (discoveredInputs != null) {
        listBuilder.addAll(discoveredInputs);
      }
      return listBuilder.build();
    }
  }

  private final DotdFile dotdFile;
  private final Artifact sourceFile;
  private final NestedSet<Artifact> mandatoryInputs;
  private final HeaderDiscovery.DotdPruningMode dotdPruningPlan;
  private final NestedSet<Artifact> headers;
  private final Artifact headersListFile;

  private Iterable<Artifact> discoveredInputs;

  private static final String GUID = "a00d5bac-a72c-4f0f-99a7-d5fdc6072137";

  private ObjcCompileAction(
      ActionOwner owner,
      Iterable<Artifact> tools,
      Iterable<Artifact> inputs,
      Iterable<Artifact> outputs,
      ResourceSet resourceSet,
      CommandLine argv,
      boolean isShellCommand,
      ImmutableMap<String, String> environment,
      ImmutableMap<String, String> executionInfo,
      String progressMessage,
      RunfilesSupplier runfilesSupplier,
      String mnemonic,
      boolean executeUnconditionally,
      ExtraActionInfoSupplier<?> extraActionInfoSupplier,
      DotdFile dotdFile,
      Artifact sourceFile,
      NestedSet<Artifact> mandatoryInputs,
      HeaderDiscovery.DotdPruningMode dotdPruningPlan,
      NestedSet<Artifact> headers,
      @Nullable Artifact headersListFile) {
    super(
        owner,
        tools,
        headersListFile == null ? inputs : mandatoryInputs,
        outputs,
        resourceSet,
        argv,
        isShellCommand,
        environment,
        ImmutableSet.<String>of(),
        executionInfo,
        progressMessage,
        runfilesSupplier,
        mnemonic,
        executeUnconditionally,
        extraActionInfoSupplier);

    this.dotdFile = dotdFile;
    this.sourceFile = sourceFile;
    this.mandatoryInputs = mandatoryInputs;
    this.dotdPruningPlan = dotdPruningPlan;
    this.headers = headers;
    this.headersListFile = headersListFile;
  }

  private Iterable<Artifact> filterHeaderFiles() {
    ImmutableList.Builder<Artifact> inputs = ImmutableList.<Artifact>builder();

    for (Artifact headerArtifact : headers) {
      if (CppFileTypes.OBJC_HEADER.matches(headerArtifact.getFilename())
          // C++ headers can be extensionless
          || (!headerArtifact.isFileset() && headerArtifact.getExtension().isEmpty())) {
          inputs.add(headerArtifact);
      }
    }
    return inputs.build();
  }

  /** Returns the DotdPruningPlan for this compile */
  @VisibleForTesting
  public HeaderDiscovery.DotdPruningMode getDotdPruningPlan() {
    return dotdPruningPlan;
  }

  @Override
  public final Spawn getSpawn(Map<String, String> clientEnv) {
    return new ObjcCompileActionSpawn(clientEnv);
  }

  @Override
  public boolean discoversInputs() {
    return true;
  }

  @Override
  public synchronized Iterable<Artifact> discoverInputs(
      ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException, InterruptedException {
    if (headersListFile != null) {
      try {
        discoveredInputs =
            HeaderThinning.findRequiredHeaderInputs(
                sourceFile, headersListFile, getAllowedDerivedInputsMap(false));
      } catch (ExecException e) {
        throw e.toActionExecutionException(
            "Header thinning of rule '" + getOwner().getLabel() + "'",
            actionExecutionContext.getExecutor().getVerboseFailures(),
            this);
      }
    } else {
      discoveredInputs = filterHeaderFiles();
    }
    return discoveredInputs;
  }

  @Override
  public ImmutableSet<Artifact> getMandatoryOutputs() {
    return ImmutableSet.of(dotdFile.artifact());
  }

  @Override
  public void execute(ActionExecutionContext actionExecutionContext)
      throws ActionExecutionException, InterruptedException {
    super.execute(actionExecutionContext);

    if (dotdPruningPlan == HeaderDiscovery.DotdPruningMode.USE) {
      Executor executor = actionExecutionContext.getExecutor();
      IncludeScanningContext scanningContext = executor.getContext(IncludeScanningContext.class);
      NestedSet<Artifact> discoveredInputs =
          discoverInputsFromDotdFiles(
              executor.getExecRoot(), scanningContext.getArtifactResolver());

      updateActionInputs(discoveredInputs);
    } else {
      // TODO(lberki): This is awkward, but necessary since updateInputs() must be called when
      // input discovery is in effect. I *think* it's possible to avoid setting discoversInputs()
      // to true if the header list file is null and then we'd not need to have this here, but I
      // haven't quite managed to get that right yet.
      updateActionInputs(getInputs());
    }
  }

  @VisibleForTesting
  public NestedSet<Artifact> discoverInputsFromDotdFiles(
      Path execRoot, ArtifactResolver artifactResolver) throws ActionExecutionException {
    if (dotdFile == null) {
      return NestedSetBuilder.<Artifact>emptySet(Order.STABLE_ORDER);
    }
    return new HeaderDiscovery.Builder()
        .setAction(this)
        .setSourceFile(sourceFile)
        .setDependencies(processDepset(execRoot).getDependencies())
        .setPermittedSystemIncludePrefixes(ImmutableList.<Path>of())
        .setAllowedDerivedinputsMap(getAllowedDerivedInputsMap(true))
        .build()
        .discoverInputsFromDependencies(execRoot, artifactResolver);
  }

  private DependencySet processDepset(Path execRoot) throws ActionExecutionException {
    try {
      DependencySet depSet = new DependencySet(execRoot);
      return depSet.read(dotdFile.getPath());
    } catch (IOException e) {
      // Some kind of IO or parse exception--wrap & rethrow it to stop the build.
      throw new ActionExecutionException("error while parsing .d file", e, this, false);
    }
  }

  /**
   * Returns a map of input and header artifacts for this action.
   *
   * @param excludeSourceArtifacts If true artifacts where {@link Artifact#isSourceArtifact()} is
   *     true are excluded from the map
   */
  private Map<PathFragment, Artifact> getAllowedDerivedInputsMap(boolean excludeSourceArtifacts) {
    // LinkedHashMap required here as it is not guaranteed that entries are unique and to preserve
    // insertion order
    Map<PathFragment, Artifact> allowedDerivedInputMap = new LinkedHashMap<>();
    for (Artifact artifact : Iterables.concat(mandatoryInputs, headers)) {
      if (!excludeSourceArtifacts || !artifact.isSourceArtifact()) {
        allowedDerivedInputMap.put(artifact.getExecPath(), artifact);
      }
    }
    return allowedDerivedInputMap;
  }

  @Override
  public Iterable<Artifact> getAllowedDerivedInputs() {
    return getAllowedDerivedInputsMap(true).values();
  }

  /**
   * Recalculates this action's live input collection, including sources, middlemen.
   *
   * @throws ActionExecutionException iff any errors happen during update.
   */
  @VisibleForTesting  // productionVisibility = Visibility.PRIVATE
  @ThreadCompatible
  final void updateActionInputs(Iterable<Artifact> discoveredInputs)
      throws ActionExecutionException {
    Profiler.instance().startTask(ProfilerTask.ACTION_UPDATE, this);
    try {
      updateInputs(Iterables.concat(mandatoryInputs, discoveredInputs));
    } finally {
      Profiler.instance().completeTask(ProfilerTask.ACTION_UPDATE);
    }
  }

  @Override
  protected SpawnInfo getExtraActionSpawnInfo() {
    SpawnInfo.Builder info = SpawnInfo.newBuilder(super.getExtraActionSpawnInfo());
    if (!inputsDiscovered()) {
      for (Artifact headerArtifact : filterHeaderFiles()) {
        // As in SpawnAction#getExtraActionSpawnInfo explicitly ignore middleman artifacts here.
        if (!headerArtifact.isMiddlemanArtifact()) {
          info.addInputFile(headerArtifact.getExecPathString());
        }
      }
    }
    return info.build();
  }

  @Override
  public String computeKey() {
    Fingerprint f = new Fingerprint();
    f.addString(GUID);
    f.addString(super.computeKey());
    f.addBoolean(dotdFile == null || dotdFile.artifact() == null);
    f.addBoolean(dotdPruningPlan == HeaderDiscovery.DotdPruningMode.USE);
    f.addBoolean(headersListFile == null);
    if (dotdFile != null) {
      f.addPath(dotdFile.getSafeExecPath());
    }
    return f.hexDigestAndReset();
  }

  /** A Builder for ObjcCompileAction */
  public static class Builder extends SpawnAction.Builder {

    private DotdFile dotdFile;
    private Artifact sourceFile;
    private Artifact headersListFile;
    private final NestedSetBuilder<Artifact> mandatoryInputs = new NestedSetBuilder<>(STABLE_ORDER);
    private HeaderDiscovery.DotdPruningMode dotdPruningPlan;
    private final NestedSetBuilder<Artifact> headers = NestedSetBuilder.stableOrder();

    /**
     * Creates a new compile action builder with apple environment variables set that are typically
     * needed by the apple toolchain.
     */
    public static ObjcCompileAction.Builder createObjcCompileActionBuilderWithAppleEnv(
        AppleConfiguration appleConfiguration, Platform targetPlatform) {
      return (Builder)
          new ObjcCompileAction.Builder()
              .setExecutionInfo(ObjcRuleClasses.darwinActionExecutionRequirement())
              .setEnvironment(
                  ObjcRuleClasses.appleToolchainEnvironment(appleConfiguration, targetPlatform));
    }

    @Override
    public Builder addTools(Iterable<Artifact> artifacts) {
      super.addTools(artifacts);
      mandatoryInputs.addAll(artifacts);
      return this;
    }

    @Override
    public Builder addTransitiveTools(NestedSet<Artifact> artifacts) {
      super.addTransitiveTools(artifacts);
      mandatoryInputs.addTransitive(artifacts);
      return this;
    }

    /** Sets a .d file that will used to prune input headers */
    public Builder setDotdFile(DotdFile dotdFile) {
      Preconditions.checkNotNull(dotdFile);
      this.dotdFile = dotdFile;
      return this;
    }

    /**
     * Sets a .headers_list file that is generated for the header thinning feature. File is used to
     * discover required inputs to compile action and update action inputs.
     */
    public Builder setHeadersListFile(Artifact headersListFile) {
      Preconditions.checkNotNull(headersListFile);
      this.headersListFile = headersListFile;
      this.addMandatoryInput(headersListFile);
      return this;
    }

    /** Sets the source file that is being compiled in this action */
    public Builder setSourceFile(Artifact sourceFile) {
      Preconditions.checkNotNull(sourceFile);
      this.sourceFile = sourceFile;
      this.mandatoryInputs.add(sourceFile);
      this.addInput(sourceFile);
      return this;
    }

    /** Add an input that cannot be pruned */
    public Builder addMandatoryInput(Artifact input) {
      Preconditions.checkNotNull(input);
      this.mandatoryInputs.add(input);
      this.addInput(input);
      return this;
    }

    /** Add inputs that cannot be pruned */
    public Builder addMandatoryInputs(Iterable<Artifact> input) {
      Preconditions.checkNotNull(input);
      this.mandatoryInputs.addAll(input);
      this.addInputs(input);
      return this;
    }

    /** Add inputs that cannot be pruned */
    public Builder addTransitiveMandatoryInputs(NestedSet<Artifact> input) {
      Preconditions.checkNotNull(input);
      this.mandatoryInputs.addTransitive(input);
      this.addTransitiveInputs(input);
      return this;
    }

    /** Indicates that this compile action should perform .d pruning */
    public Builder setDotdPruningPlan(HeaderDiscovery.DotdPruningMode dotdPruningPlan) {
      Preconditions.checkNotNull(dotdPruningPlan);
      this.dotdPruningPlan = dotdPruningPlan;
      return this;
    }

    /** Adds to the set of all possible headers that could be required by this compile action. */
    public Builder addTransitiveHeaders(NestedSet<Artifact> headers) {
      this.headers.addTransitive(Preconditions.checkNotNull(headers));
      this.addTransitiveInputs(headers);
      return this;
    }

    /** Adds to the set of all possible headers that could be required by this compile action. */
    public Builder addHeaders(Iterable<Artifact> headers) {
      this.headers.addAll(Preconditions.checkNotNull(headers));
      this.addInputs(headers);
      return this;
    }

    @Override
    protected SpawnAction createSpawnAction(
        ActionOwner owner,
        NestedSet<Artifact> tools,
        NestedSet<Artifact> inputsAndTools,
        ImmutableList<Artifact> outputs,
        ResourceSet resourceSet,
        CommandLine actualCommandLine,
        boolean isShellCommand,
        ImmutableMap<String, String> env,
        ImmutableSet<String> clientEnvironmentVariables,
        ImmutableMap<String, String> executionInfo,
        String progressMessage,
        RunfilesSupplier runfilesSupplier,
        String mnemonic) {
      return new ObjcCompileAction(
          owner,
          tools,
          inputsAndTools,
          outputs,
          resourceSet,
          actualCommandLine,
          isShellCommand,
          env,
          executionInfo,
          progressMessage,
          runfilesSupplier,
          mnemonic,
          executeUnconditionally,
          extraActionInfoSupplier,
          dotdFile,
          sourceFile,
          mandatoryInputs.build(),
          dotdPruningPlan,
          headers.build(),
          headersListFile);
    }
  }
}
