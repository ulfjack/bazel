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

package com.google.devtools.build.lib.runtime;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Range;
import com.google.common.collect.Sets;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.SubscriberExceptionContext;
import com.google.common.eventbus.SubscriberExceptionHandler;
import com.google.common.util.concurrent.Uninterruptibles;
import com.google.devtools.build.lib.actions.PackageRootResolver;
import com.google.devtools.build.lib.actions.cache.ActionCache;
import com.google.devtools.build.lib.actions.cache.CompactPersistentActionCache;
import com.google.devtools.build.lib.actions.cache.NullActionCache;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.BlazeVersionInfo;
import com.google.devtools.build.lib.analysis.BuildView;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.SkyframePackageRootResolver;
import com.google.devtools.build.lib.analysis.WorkspaceStatusAction;
import com.google.devtools.build.lib.analysis.config.BinTools;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationCollection;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.ConfigurationFactory;
import com.google.devtools.build.lib.analysis.config.DefaultsPackage;
import com.google.devtools.build.lib.analysis.config.InvalidConfigurationException;
import com.google.devtools.build.lib.buildtool.BuildTool;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.OutputFilter;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.exec.OutputService;
import com.google.devtools.build.lib.packages.NoSuchThingException;
import com.google.devtools.build.lib.packages.PackageFactory;
import com.google.devtools.build.lib.packages.Preprocessor;
import com.google.devtools.build.lib.packages.RuleClassProvider;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.pkgcache.LoadedPackageProvider;
import com.google.devtools.build.lib.pkgcache.LoadingPhaseRunner;
import com.google.devtools.build.lib.pkgcache.PackageCacheOptions;
import com.google.devtools.build.lib.pkgcache.PackageManager;
import com.google.devtools.build.lib.pkgcache.TargetPatternEvaluator;
import com.google.devtools.build.lib.profiler.MemoryProfiler;
import com.google.devtools.build.lib.profiler.ProfilePhase;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.Profiler.ProfiledTaskKinds;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.query2.output.OutputFormatter;
import com.google.devtools.build.lib.rules.test.CoverageReportActionFactory;
import com.google.devtools.build.lib.runtime.commands.BuildCommand;
import com.google.devtools.build.lib.runtime.commands.CanonicalizeCommand;
import com.google.devtools.build.lib.runtime.commands.CleanCommand;
import com.google.devtools.build.lib.runtime.commands.DumpCommand;
import com.google.devtools.build.lib.runtime.commands.HelpCommand;
import com.google.devtools.build.lib.runtime.commands.InfoCommand;
import com.google.devtools.build.lib.runtime.commands.MobileInstallCommand;
import com.google.devtools.build.lib.runtime.commands.ProfileCommand;
import com.google.devtools.build.lib.runtime.commands.QueryCommand;
import com.google.devtools.build.lib.runtime.commands.RunCommand;
import com.google.devtools.build.lib.runtime.commands.ShutdownCommand;
import com.google.devtools.build.lib.runtime.commands.TestCommand;
import com.google.devtools.build.lib.runtime.commands.VersionCommand;
import com.google.devtools.build.lib.server.RPCServer;
import com.google.devtools.build.lib.server.ServerCommand;
import com.google.devtools.build.lib.server.signal.InterruptSignalHandler;
import com.google.devtools.build.lib.skyframe.DiffAwareness;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.SequencedSkyframeExecutorFactory;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor;
import com.google.devtools.build.lib.skyframe.SkyframeExecutorFactory;
import com.google.devtools.build.lib.syntax.Label;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.BlazeClock;
import com.google.devtools.build.lib.util.Clock;
import com.google.devtools.build.lib.util.ExitCode;
import com.google.devtools.build.lib.util.LoggingUtil;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.util.OsUtils;
import com.google.devtools.build.lib.util.ThreadUtils;
import com.google.devtools.build.lib.util.io.OutErr;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.JavaIoFileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.UnixFileSystem;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionPriority;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsClassProvider;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.devtools.common.options.OptionsProvider;
import com.google.devtools.common.options.TriState;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

/**
 * The BlazeRuntime class encapsulates the runtime settings and services that
 * are available to most parts of any Blaze application for the duration of the
 * batch run or server lifetime. A single instance of this runtime will exist
 * and will be passed around as needed.
 */
public final class BlazeRuntime {
  /**
   * The threshold for memory reserved by a 32-bit JVM before trouble may be expected.
   *
   * <p>After the JVM starts, it reserves memory for heap (controlled by -Xmx) and non-heap
   * (code, PermGen, etc.). Furthermore, as Blaze spawns threads, each thread reserves memory
   * for the stack (controlled by -Xss). Thus even if Blaze starts fine, with high memory settings
   * it will die from a stack allocation failure in the middle of a build. We prefer failing
   * upfront by setting a safe threshold.
   *
   * <p>This does not apply to 64-bit VMs.
   */
  private static final long MAX_BLAZE32_RESERVED_MEMORY = 3400 * 1048576L;

  // Less than this indicates tampering with -Xmx settings.
  private static final long MIN_BLAZE32_HEAP_SIZE = 3000 * 1000000L;

  public static final String DO_NOT_BUILD_FILE_NAME = "DO_NOT_BUILD_HERE";

  private static final Pattern suppressFromLog = Pattern.compile(".*(auth|pass|cookie).*",
      Pattern.CASE_INSENSITIVE);

  private static final Logger LOG = Logger.getLogger(BlazeRuntime.class.getName());

  private final BlazeDirectories directories;
  private Path workingDirectory;
  private long commandStartTime;

  private Range<Long> lastExecutionStartFinish = null;

  private final SkyframeExecutor skyframeExecutor;

  private final Reporter reporter;
  private EventBus eventBus;
  private final LoadingPhaseRunner loadingPhaseRunner;
  private final PackageFactory packageFactory;
  private final PackageRootResolver packageRootResolver;
  private final ConfigurationFactory configurationFactory;
  private final ConfiguredRuleClassProvider ruleClassProvider;
  private final BuildView view;
  private ActionCache actionCache;
  private final TimestampGranularityMonitor timestampGranularityMonitor;
  private final Clock clock;
  private final BuildTool buildTool;

  private OutputService outputService;

  private final Iterable<BlazeModule> blazeModules;
  private final BlazeModule.ModuleEnvironment blazeModuleEnvironment;

  private UUID commandId;  // Unique identifier for the command being run

  private final AtomicInteger storedExitCode = new AtomicInteger();

  private final Map<String, String> clientEnv;

  // We pass this through here to make it available to the MasterLogWriter.
  private final OptionsProvider startupOptionsProvider;

  private String outputFileSystem;
  private Map<String, BlazeCommand> commandMap;

  private AbruptExitException pendingException;

  private final SubscriberExceptionHandler eventBusExceptionHandler;

  private final BinTools binTools;

  private final WorkspaceStatusAction.Factory workspaceStatusActionFactory;

  private final ProjectFile.Provider projectFileProvider;

  private class BlazeModuleEnvironment implements BlazeModule.ModuleEnvironment {
    @Override
    public Path getFileFromDepot(Label label)
        throws NoSuchThingException, InterruptedException, IOException {
      Target target = getPackageManager().getTarget(reporter, label);
      return (outputService != null)
          ? outputService.stageTool(target)
          : target.getPackage().getPackageDirectory().getRelative(target.getName());
    }

    @Override
    public void exit(AbruptExitException exception) {
      Preconditions.checkState(pendingException == null);
      pendingException = exception;
    }
  }

  private BlazeRuntime(BlazeDirectories directories, Reporter reporter,
      WorkspaceStatusAction.Factory workspaceStatusActionFactory,
      final SkyframeExecutor skyframeExecutor,
      PackageFactory pkgFactory, ConfiguredRuleClassProvider ruleClassProvider,
      ConfigurationFactory configurationFactory, Clock clock,
      OptionsProvider startupOptionsProvider, Iterable<BlazeModule> blazeModules,
      Map<String, String> clientEnv,
      TimestampGranularityMonitor timestampGranularityMonitor,
      SubscriberExceptionHandler eventBusExceptionHandler,
      BinTools binTools, ProjectFile.Provider projectFileProvider) {
    this.workspaceStatusActionFactory = workspaceStatusActionFactory;
    this.directories = directories;
    this.workingDirectory = directories.getWorkspace();
    this.reporter = reporter;
    this.packageFactory = pkgFactory;
    this.binTools = binTools;
    this.projectFileProvider = projectFileProvider;

    this.skyframeExecutor = skyframeExecutor;
    this.packageRootResolver = new SkyframePackageRootResolver(skyframeExecutor);
    this.loadingPhaseRunner = new LoadingPhaseRunner(
        skyframeExecutor.getPackageManager(),
        pkgFactory.getRuleClassNames());

    this.clientEnv = clientEnv;

    this.blazeModules = blazeModules;
    this.ruleClassProvider = ruleClassProvider;
    this.configurationFactory = configurationFactory;
    this.view = new BuildView(directories, getPackageManager(), ruleClassProvider,
        skyframeExecutor, binTools, getCoverageReportActionFactory(blazeModules));
    this.clock = clock;
    this.timestampGranularityMonitor = Preconditions.checkNotNull(timestampGranularityMonitor);
    this.startupOptionsProvider = startupOptionsProvider;

    this.eventBusExceptionHandler = eventBusExceptionHandler;
    this.blazeModuleEnvironment = new BlazeModuleEnvironment();
    this.buildTool = new BuildTool(this);
    initEventBus();

    if (inWorkspace()) {
      writeOutputBaseReadmeFile();
      writeOutputBaseDoNotBuildHereFile();
    }
    setupExecRoot();
  }

  @Nullable private CoverageReportActionFactory getCoverageReportActionFactory(
      Iterable<BlazeModule> blazeModules) {
    CoverageReportActionFactory firstFactory = null;
    for (BlazeModule module : blazeModules) {
      CoverageReportActionFactory factory = module.getCoverageReportFactory();
      if (factory != null) {
        Preconditions.checkState(firstFactory == null,
            "only one Blaze Module can have a Coverage Report Factory");
        firstFactory = factory;
      }
    }
    return firstFactory;
  }

  /**
   * Figures out what file system we are writing output to. Here we use
   * outputBase instead of outputPath because we need a file system to create the latter.
   */
  private String determineOutputFileSystem() {
    if (getOutputService() != null) {
      return getOutputService().getFilesSystemName();
    }
    long startTime = Profiler.nanoTimeMaybe();
    String fileSystem = FileSystemUtils.getFileSystem(getOutputBase());
    Profiler.instance().logSimpleTask(startTime, ProfilerTask.INFO, "Finding output file system");
    return fileSystem;
  }

  public String getOutputFileSystem() {
    return outputFileSystem;
  }

  @VisibleForTesting
  public void initEventBus() {
    setEventBus(new EventBus(eventBusExceptionHandler));
  }

  private void clearEventBus() {
    // EventBus does not have an unregister() method, so this is how we release memory associated
    // with handlers.
    setEventBus(null);
  }

  private void setEventBus(EventBus eventBus) {
    this.eventBus = eventBus;
    skyframeExecutor.setEventBus(eventBus);
  }

  /**
   * Conditionally enable profiling.
   */
  private final boolean initProfiler(CommonCommandOptions options,
      UUID buildID, long execStartTimeNanos) {
    OutputStream out = null;
    boolean recordFullProfilerData = false;
    ProfiledTaskKinds profiledTasks = ProfiledTaskKinds.NONE;

    try {
      if (options.profilePath != null) {
        Path profilePath = getWorkspace().getRelative(options.profilePath);

        recordFullProfilerData = options.recordFullProfilerData;
        out = new BufferedOutputStream(profilePath.getOutputStream(), 1024 * 1024);
        getReporter().handle(Event.info("Writing profile data to '" + profilePath + "'"));
        profiledTasks = ProfiledTaskKinds.ALL;
      } else if (options.alwaysProfileSlowOperations) {
        recordFullProfilerData = false;
        out = null;
        profiledTasks = ProfiledTaskKinds.SLOWEST;
      }
      if (profiledTasks != ProfiledTaskKinds.NONE) {
        Profiler.instance().start(profiledTasks, out,
            "Blaze profile for " + getOutputBase() + " at " + new Date()
            + ", build ID: " + buildID,
            recordFullProfilerData, clock, execStartTimeNanos);
        return true;
      }
    } catch (IOException e) {
      getReporter().handle(Event.error("Error while creating profile file: " + e.getMessage()));
    }
    return false;
  }

  /**
   * Generates a README file in the output base directory. This README file
   * contains the name of the workspace directory, so that users can figure out
   * which output base directory corresponds to which workspace.
   */
  private void writeOutputBaseReadmeFile() {
    Preconditions.checkNotNull(getWorkspace());
    Path outputBaseReadmeFile = getOutputBase().getRelative("README");
    try {
      FileSystemUtils.writeIsoLatin1(outputBaseReadmeFile, "WORKSPACE: " + getWorkspace(), "",
          "The first line of this file is intentionally easy to parse for various",
          "interactive scripting and debugging purposes.  But please DO NOT write programs",
          "that exploit it, as they will be broken by design: it is not possible to",
          "reverse engineer the set of source trees or the --package_path from the output",
          "tree, and if you attempt it, you will fail, creating subtle and",
          "hard-to-diagnose bugs, that will no doubt get blamed on changes made by the",
          "Blaze team.", "", "This directory was generated by Blaze.",
          "Do not attempt to modify or delete any files in this directory.",
          "Among other issues, Blaze's file system caching assumes that",
          "only Blaze will modify this directory and the files in it,",
          "so if you change anything here you may mess up Blaze's cache.");
    } catch (IOException e) {
      LOG.warning("Couldn't write to '" + outputBaseReadmeFile + "': " + e.getMessage());
    }
  }

  private void writeOutputBaseDoNotBuildHereFile() {
    Preconditions.checkNotNull(getWorkspace());
    Path filePath = getOutputBase().getRelative(DO_NOT_BUILD_FILE_NAME);
    try {
      FileSystemUtils.writeContent(filePath, ISO_8859_1, getWorkspace().toString());
    } catch (IOException e) {
      LOG.warning("Couldn't write to '" + filePath + "': " + e.getMessage());
    }
  }

  /**
   * Creates the execRoot dir under outputBase.
   */
  private void setupExecRoot() {
    try {
      FileSystemUtils.createDirectoryAndParents(directories.getExecRoot());
    } catch (IOException e) {
      LOG.warning("failed to create execution root '" + directories.getExecRoot() + "': "
          + e.getMessage());
    }
  }

  public void recordLastExecutionTime() {
    lastExecutionStartFinish = Range.closed(commandStartTime, clock.currentTimeMillis());
  }

  /**
   * Range that represents the last execution time of a build in millis since epoch.
   */
  @Nullable
  public Range<Long> getLastExecutionTimeRange() {
    return lastExecutionStartFinish;
  }
  public void recordCommandStartTime(long commandStartTime) {
    this.commandStartTime = commandStartTime;
  }

  public long getCommandStartTime() {
    return commandStartTime;
  }

  public String getWorkspaceName() {
    Path workspace = directories.getWorkspace();
    if (workspace == null) {
      return "";
    }
    return workspace.getBaseName();
  }

  /**
   * Returns the Blaze directories object for this runtime.
   */
  public BlazeDirectories getDirectories() {
    return directories;
  }

  /**
   * Returns the working directory of the server.
   *
   * <p>This is often the first entry on the {@code --package_path}, but not always.
   * Callers should certainly not make this assumption. The Path returned may be null.
   *
   * @see #getWorkingDirectory()
   */
  public Path getWorkspace() {
    return directories.getWorkspace();
  }

  /**
   * Returns the working directory of the {@code blaze} client process.
   *
   * <p>This may be equal to {@code getWorkspace()}, or beneath it.
   *
   * @see #getWorkspace()
   */
  public Path getWorkingDirectory() {
    return workingDirectory;
  }

  /**
   * Returns if the client passed a valid workspace to be used for the build.
   */
  public boolean inWorkspace() {
    return directories.inWorkspace();
  }

  /**
   * Returns the output base directory associated with this Blaze server
   * process. This is the base directory for shared Blaze state as well as tool
   * and strategy specific subdirectories.
   */
  public Path getOutputBase() {
    return directories.getOutputBase();
  }

  /**
   * Returns the output path associated with this Blaze server process..
   */
  public Path getOutputPath() {
    return directories.getOutputPath();
  }

  /**
   * The directory in which blaze stores the server state - that is, the socket
   * file and a log.
   */
  public Path getServerDirectory() {
    return getOutputBase().getChild("server");
  }

  /**
   * Returns the execution root directory associated with this Blaze server
   * process. This is where all input and output files visible to the actual
   * build reside.
   */
  public Path getExecRoot() {
    return directories.getExecRoot();
  }

  /**
   * Returns the reporter for events.
   */
  public Reporter getReporter() {
    return reporter;
  }

  /**
   * Returns the current event bus. Only valid within the scope of a single Blaze command.
   */
  public EventBus getEventBus() {
    return eventBus;
  }

  public BinTools getBinTools() {
    return binTools;
  }

  /**
   * Returns the skyframe executor.
   */
  public SkyframeExecutor getSkyframeExecutor() {
    return skyframeExecutor;
  }

  /**
   * Returns the package factory.
   */
  public PackageFactory getPackageFactory() {
    return packageFactory;
  }

  /**
   * Returns the build tool.
   */
  public BuildTool getBuildTool() {
    return buildTool;
  }

  public ImmutableList<OutputFormatter> getQueryOutputFormatters() {
    ImmutableList.Builder<OutputFormatter> result = ImmutableList.builder();
    result.addAll(OutputFormatter.getDefaultFormatters());
    for (BlazeModule module : blazeModules) {
      result.addAll(module.getQueryOutputFormatters());
    }

    return result.build();
  }

  /**
   * Returns the package manager.
   */
  public PackageManager getPackageManager() {
    return skyframeExecutor.getPackageManager();
  }

  public PackageRootResolver getPackageRootResolver() {
    return packageRootResolver;
  }

  public WorkspaceStatusAction.Factory getworkspaceStatusActionFactory() {
    return workspaceStatusActionFactory;
  }

  public BlazeModule.ModuleEnvironment getBlazeModuleEnvironment() {
    return blazeModuleEnvironment;
  }

  /**
   * Returns the rule class provider.
   */
  public ConfiguredRuleClassProvider getRuleClassProvider() {
    return ruleClassProvider;
  }

  public LoadingPhaseRunner getLoadingPhaseRunner() {
    return loadingPhaseRunner;
  }

  /**
   * Returns the build view.
   */
  public BuildView getView() {
    return view;
  }

  public Iterable<BlazeModule> getBlazeModules() {
    return blazeModules;
  }

  @SuppressWarnings("unchecked")
  public <T extends BlazeModule> T getBlazeModule(Class<T> moduleClass) {
    for (BlazeModule module : blazeModules) {
      if (module.getClass() == moduleClass) {
        return (T) module;
      }
    }

    return null;
  }

  public ConfigurationFactory getConfigurationFactory() {
    return configurationFactory;
  }

  /**
   * Returns the target pattern parser.
   */
  public TargetPatternEvaluator getTargetPatternEvaluator() {
    return loadingPhaseRunner.getTargetPatternEvaluator();
  }

  /**
   * Returns reference to the lazily instantiated persistent action cache
   * instance. Note, that method may recreate instance between different build
   * requests, so return value should not be cached.
   */
  public ActionCache getPersistentActionCache() throws IOException {
    if (actionCache == null) {
      if (OS.getCurrent() == OS.WINDOWS) {
        // TODO(bazel-team): Add support for a persistent action cache on Windows.
        actionCache = new NullActionCache();
        return actionCache;
      }
      long startTime = Profiler.nanoTimeMaybe();
      try {
        actionCache = new CompactPersistentActionCache(getCacheDirectory(), clock);
      } catch (IOException e) {
        LOG.log(Level.WARNING, "Failed to load action cache: " + e.getMessage(), e);
        LoggingUtil.logToRemote(Level.WARNING, "Failed to load action cache: "
            + e.getMessage(), e);
        getReporter().handle(
            Event.error("Error during action cache initialization: " + e.getMessage()
            + ". Corrupted files were renamed to '" + getCacheDirectory() + "/*.bad'. "
            + "Blaze will now reset action cache data, causing a full rebuild"));
        actionCache = new CompactPersistentActionCache(getCacheDirectory(), clock);
      } finally {
        long stopTime = Profiler.nanoTimeMaybe();
        long duration = stopTime - startTime;
        if (duration > 0) {
          LOG.info("Spent " + (duration / (1000 * 1000)) + " ms loading persistent action cache");
        }
        Profiler.instance().logSimpleTask(startTime, ProfilerTask.INFO, "Loading action cache");
      }
    }
    return actionCache;
  }

  /**
   * Removes in-memory caches.
   */
  public void clearCaches() throws IOException {
    clearSkyframeRelevantCaches();
    actionCache = null;
    FileSystemUtils.deleteTree(getCacheDirectory());
  }

  /** Removes skyframe cache and other caches that must be kept synchronized with skyframe. */
  private void clearSkyframeRelevantCaches() {
    skyframeExecutor.resetEvaluator();
    view.clear();
  }

  /**
   * Returns the TimestampGranularityMonitor. The same monitor object is used
   * across multiple Blaze commands, but it doesn't hold any persistent state
   * across different commands.
   */
  public TimestampGranularityMonitor getTimestampGranularityMonitor() {
    return timestampGranularityMonitor;
  }

  /**
   * Returns path to the cache directory. Path must be inside output base to
   * ensure that users can run concurrent instances of blaze in different
   * clients without attempting to concurrently write to the same action cache
   * on disk, which might not be safe.
   */
  private Path getCacheDirectory() {
    return getOutputBase().getChild("action_cache");
  }

  /**
   * Returns a provider for project file objects. Can be null if no such provider was set by any of
   * the modules.
   */
  @Nullable
  public ProjectFile.Provider getProjectFileProvider() {
    return projectFileProvider;
  }

  /**
   * Hook method called by the BlazeCommandDispatcher prior to the dispatch of
   * each command.
   *
   * @param options The CommonCommandOptions used by every command.
   * @throws AbruptExitException if this command is unsuitable to be run as specified
   */
  void beforeCommand(Command command, OptionsParser optionsParser,
      CommonCommandOptions options, long execStartTimeNanos)
      throws AbruptExitException {
    commandStartTime -= options.startupTime;

    eventBus.post(new GotOptionsEvent(startupOptionsProvider,
        optionsParser));
    throwPendingException();

    outputService = null;
    BlazeModule outputModule = null;
    for (BlazeModule module : blazeModules) {
      OutputService moduleService = module.getOutputService();
      if (moduleService != null) {
        if (outputService != null) {
          throw new IllegalStateException(String.format(
              "More than one module (%s and %s) returns an output service",
              module.getClass(), outputModule.getClass()));
        }
        outputService = moduleService;
        outputModule = module;
      }
    }

    skyframeExecutor.setBatchStatter(outputService == null
        ? null
        : outputService.getBatchStatter());

    outputFileSystem = determineOutputFileSystem();

    // Ensure that the working directory will be under the workspace directory.
    Path workspace = getWorkspace();
    if (inWorkspace()) {
      workingDirectory = workspace.getRelative(options.clientCwd);
    } else {
      workspace = FileSystemUtils.getWorkingDirectory(directories.getFileSystem());
      workingDirectory = workspace;
    }
    updateClientEnv(options.clientEnv, options.ignoreClientEnv);
    loadingPhaseRunner.updatePatternEvaluator(workingDirectory.relativeTo(workspace));

    // Fail fast in the case where a Blaze command forgets to install the package path correctly.
    skyframeExecutor.setActive(false);
    // Let skyframe figure out if it needs to store graph edges for this build.
    skyframeExecutor.decideKeepIncrementalState(
        startupOptionsProvider.getOptions(BlazeServerStartupOptions.class).batch,
        optionsParser.getOptions(BuildView.Options.class));

    // Conditionally enable profiling
    // We need to compensate for launchTimeNanos (measurements taken outside of the jvm).
    long startupTimeNanos = options.startupTime * 1000000L;
    if (initProfiler(options, this.getCommandId(), execStartTimeNanos - startupTimeNanos)) {
      Profiler profiler = Profiler.instance();

      // Instead of logEvent() we're calling the low level function to pass the timings we took in
      // the launcher. We're setting the INIT phase marker so that it follows immediately the LAUNCH
      // phase.
      profiler.logSimpleTaskDuration(execStartTimeNanos - startupTimeNanos, 0, ProfilerTask.PHASE,
          ProfilePhase.LAUNCH.description);
      profiler.logSimpleTaskDuration(execStartTimeNanos, 0, ProfilerTask.PHASE,
          ProfilePhase.INIT.description);
    }

    if (options.memoryProfilePath != null) {
      Path memoryProfilePath = getWorkingDirectory().getRelative(options.memoryProfilePath);
      try {
        MemoryProfiler.instance().start(memoryProfilePath.getOutputStream());
      } catch (IOException e) {
        getReporter().handle(
            Event.error("Error while creating memory profile file: " + e.getMessage()));
      }
    }

    if (command.builds()) {
      Map<String, String> testEnv = new TreeMap<>();
      for (Map.Entry<String, String> entry :
          optionsParser.getOptions(BuildConfiguration.Options.class).testEnvironment) {
        testEnv.put(entry.getKey(), entry.getValue());
      }

      try {
        for (Map.Entry<String, String> entry : testEnv.entrySet()) {
          if (entry.getValue() == null) {
            String clientValue = clientEnv.get(entry.getKey());
            if (clientValue != null) {
              optionsParser.parse(OptionPriority.SOFTWARE_REQUIREMENT,
                  "test environment variable from client environment",
                  ImmutableList.of(
                      "--test_env=" + entry.getKey() + "=" + clientEnv.get(entry.getKey())));
            }
          }
        }
      } catch (OptionsParsingException e) {
        throw new IllegalStateException(e);
      }
    }
    for (BlazeModule module : blazeModules) {
      module.handleOptions(optionsParser);
    }

    eventBus.post(new CommandStartEvent(command.name(), commandId, clientEnv, workingDirectory));
    // Initialize exit code to dummy value for afterCommand.
    storedExitCode.set(ExitCode.RESERVED.getNumericExitCode());
  }

  /**
   * Hook method called by the BlazeCommandDispatcher right before the dispatch
   * of each command ends (while its outcome can still be modified).
   */
  ExitCode precompleteCommand(ExitCode originalExit) {
    eventBus.post(new CommandPrecompleteEvent(originalExit));
    // If Blaze did not suffer an infrastructure failure, check for errors in modules.
    ExitCode exitCode = originalExit;
    if (!originalExit.isInfrastructureFailure()) {
      if (pendingException != null) {
        exitCode = pendingException.getExitCode();
      }
    }
    pendingException = null;
    return exitCode;
  }

  /**
   * Posts the {@link CommandCompleteEvent}, so that listeners can tidy up. Called by {@link
   * #afterCommand}, and by BugReport when crashing from an exception in an async thread.
   */
  public void notifyCommandComplete(int exitCode) {
    if (!storedExitCode.compareAndSet(ExitCode.RESERVED.getNumericExitCode(), exitCode)) {
      // This command has already been called, presumably because there is a race between the main
      // thread and a worker thread that crashed. Don't try to arbitrate the dispute. If the main
      // thread won the race (unlikely, but possible), this may be incorrectly logged as a success.
      return;
    }
    eventBus.post(new CommandCompleteEvent(exitCode));
  }

  /**
   * Hook method called by the BlazeCommandDispatcher after the dispatch of each
   * command.
   */
  @VisibleForTesting
  public void afterCommand(int exitCode) {
    // Remove any filters that the command might have added to the reporter.
    getReporter().setOutputFilter(OutputFilter.OUTPUT_EVERYTHING);

    notifyCommandComplete(exitCode);

    for (BlazeModule module : blazeModules) {
      module.afterCommand();
    }

    clearEventBus();

    try {
      Profiler.instance().stop();
      MemoryProfiler.instance().stop();
    } catch (IOException e) {
      getReporter().handle(Event.error("Error while writing profile file: " + e.getMessage()));
    }
  }

  // Make sure we keep a strong reference to this logger, so that the
  // configuration isn't lost when the gc kicks in.
  private static Logger templateLogger = Logger.getLogger("com.google.devtools.build");

  /**
   * Configures "com.google.devtools.build.*" loggers to the given
   *  {@code level}. Note: This code relies on static state.
   */
  public static void setupLogging(Level level) {
    templateLogger.setLevel(level);
    templateLogger.info("Log level: " + templateLogger.getLevel());
  }

  /**
   * Return an unmodifiable view of the blaze client's environment when it
   * invoked the most recent command. Updates from future requests will be
   * accessible from this view.
   */
  public Map<String, String> getClientEnv() {
    return Collections.unmodifiableMap(clientEnv);
  }

  @VisibleForTesting
  void updateClientEnv(List<Map.Entry<String, String>> clientEnvList, boolean ignoreClientEnv) {
    clientEnv.clear();

    Collection<Map.Entry<String, String>> env =
        ignoreClientEnv ? System.getenv().entrySet() : clientEnvList;
    for (Map.Entry<String, String> entry : env) {
      clientEnv.put(entry.getKey(), entry.getValue());
    }
  }

  /**
   * Returns the Clock-instance used for the entire build. Before,
   * individual classes (such as Profiler) used to specify the type
   * of clock (e.g. EpochClock) they wanted to use. This made it
   * difficult to get Blaze working on Windows as some of the clocks
   * available for Linux aren't (directly) available on Windows.
   * Setting the Blaze-wide clock upon construction of BlazeRuntime
   * allows injecting whatever Clock instance should be used from
   * BlazeMain.
   *
   * @return The Blaze-wide clock
   */
  public Clock getClock() {
    return clock;
  }

  public OptionsProvider getStartupOptionsProvider() {
    return startupOptionsProvider;
  }

  /**
   * An array of String values useful if Blaze crashes.
   * For now, just returns the size of the action cache and the build id.
   */
  public String[] getCrashData() {
    return new String[]{
        getFileSizeString(CompactPersistentActionCache.cacheFile(getCacheDirectory()),
                          "action cache"),
        commandIdString(),
    };
  }

  private String commandIdString() {
    UUID uuid = getCommandId();
    return (uuid == null)
        ? "no build id"
        : uuid + " (build id)";
  }

  /**
   * @return the OutputService in use, or null if none.
   */
  public OutputService getOutputService() {
    return outputService;
  }

  private String getFileSizeString(Path path, String type) {
    try {
      return String.format("%d bytes (%s)", path.getFileSize(), type);
    } catch (IOException e) {
      return String.format("unknown file size (%s)", type);
    }
  }

  /**
   * Returns the UUID that Blaze uses to identify everything
   * logged from the current build command.
   */
  public UUID getCommandId() {
    return commandId;
  }

  void setCommandMap(Map<String, BlazeCommand> commandMap) {
    this.commandMap = ImmutableMap.copyOf(commandMap);
  }

  public Map<String, BlazeCommand> getCommandMap() {
    return commandMap;
  }

  /**
   * Sets the UUID that Blaze uses to identify everything
   * logged from the current build command.
   */
  @VisibleForTesting
  public void setCommandId(UUID runId) {
    commandId = runId;
  }

  /**
   * This method only exists for the benefit of InfoCommand, which needs to construct a {@link
   * BuildConfigurationCollection} without running a full loading phase. Don't add any more clients;
   * instead, we should change info so that it doesn't need the configuration.
   */
  public BuildConfigurationCollection getConfigurations(OptionsProvider optionsProvider)
      throws InvalidConfigurationException, InterruptedException {
    BuildOptions buildOptions = createBuildOptions(optionsProvider);
    boolean keepGoing = optionsProvider.getOptions(BuildView.Options.class).keepGoing;
    LoadedPackageProvider loadedPackageProvider =
        loadingPhaseRunner.loadForConfigurations(reporter,
            ImmutableSet.copyOf(buildOptions.getAllLabels().values()),
            keepGoing);
    if (loadedPackageProvider == null) {
      throw new InvalidConfigurationException("Configuration creation failed");
    }
    return skyframeExecutor.createConfigurations(configurationFactory,
        buildOptions, directories, ImmutableSet.<String>of(), keepGoing);
  }

  /**
   * Initializes the package cache using the given options, and syncs the package cache. Also
   * injects a defaults package using the options for the {@link BuildConfiguration}.
   *
   * @see DefaultsPackage
   */
  public void setupPackageCache(PackageCacheOptions packageCacheOptions,
      String defaultsPackageContents) throws InterruptedException, AbruptExitException {
    if (!skyframeExecutor.hasIncrementalState()) {
      clearSkyframeRelevantCaches();
    }
    skyframeExecutor.sync(packageCacheOptions, getOutputBase(), getWorkingDirectory(),
        defaultsPackageContents, getCommandId());
  }

  public void shutdown() {
    for (BlazeModule module : blazeModules) {
      module.blazeShutdown();
    }
  }

  /**
   * Throws the exception currently queued by a Blaze module.
   *
   * <p>This should be called as often as is practical so that errors are reported as soon as
   * possible. Ideally, we'd not need this, but the event bus swallows exceptions so we raise
   * the exception this way.
   */
  public void throwPendingException() throws AbruptExitException {
    if (pendingException != null) {
      AbruptExitException exception = pendingException;
      pendingException = null;
      throw exception;
    }
  }

  /**
   * Returns the defaults package for the default settings. Should only be called by commands that
   * do <i>not</i> process {@link BuildOptions}, since build options can alter the contents of the
   * defaults package, which will not be reflected here.
   */
  public String getDefaultsPackageContent() {
    return ruleClassProvider.getDefaultsPackageContent();
  }

  /**
   * Returns the defaults package for the given options taken from an optionsProvider.
   */
  public String getDefaultsPackageContent(OptionsClassProvider optionsProvider) {
    return ruleClassProvider.getDefaultsPackageContent(optionsProvider);
  }

  /**
   * Creates a BuildOptions class for the given options taken from an optionsProvider.
   */
  public BuildOptions createBuildOptions(OptionsClassProvider optionsProvider) {
    return ruleClassProvider.createBuildOptions(optionsProvider);
  }

  /**
   * An EventBus exception handler that will report the exception to a remote server, if a
   * handler is registered.
   */
  public static final class RemoteExceptionHandler implements SubscriberExceptionHandler {
    @Override
    public void handleException(Throwable exception, SubscriberExceptionContext context) {
      LOG.log(Level.SEVERE, "Failure in EventBus subscriber", exception);
      LoggingUtil.logToRemote(Level.SEVERE, "Failure in EventBus subscriber.", exception);
    }
  }

  /**
   * An EventBus exception handler that will call BugReport.handleCrash exiting
   * the current thread.
   */
  public static final class BugReportingExceptionHandler implements SubscriberExceptionHandler {
    @Override
    public void handleException(Throwable exception, SubscriberExceptionContext context) {
      BugReport.handleCrash(exception);
    }
  }

  /**
   * Main method for the Blaze server startup. Note: This method logs
   * exceptions to remote servers. Do not add this to a unittest.
   */
  public static void main(Iterable<Class<? extends BlazeModule>> moduleClasses, String[] args) {
    setupUncaughtHandler(args);
    List<BlazeModule> modules = createModules(moduleClasses);
    // blaze.cc will put --batch first if the user set it.
    if (args.length >= 1 && args[0].equals("--batch")) {
      // Run Blaze in batch mode.
      System.exit(batchMain(modules, args));
    }
    LOG.info("Starting Blaze server with args " + Arrays.toString(args));
    try {
      // Run Blaze in server mode.
      System.exit(serverMain(modules, OutErr.SYSTEM_OUT_ERR, args));
    } catch (RuntimeException | Error e) { // A definite bug...
      BugReport.printBug(OutErr.SYSTEM_OUT_ERR, e);
      BugReport.sendBugReport(e, Arrays.asList(args));
      System.exit(ExitCode.BLAZE_INTERNAL_ERROR.getNumericExitCode());
      throw e; // Shouldn't get here.
    }
  }

  @VisibleForTesting
  public static List<BlazeModule> createModules(
      Iterable<Class<? extends BlazeModule>> moduleClasses) {
    ImmutableList.Builder<BlazeModule> result = ImmutableList.builder();
    for (Class<? extends BlazeModule> moduleClass : moduleClasses) {
      try {
        BlazeModule module = moduleClass.newInstance();
        result.add(module);
      } catch (Throwable e) {
        throw new IllegalStateException("Cannot instantiate module " + moduleClass.getName(), e);
      }
    }

    return result.build();
  }

  /**
   * Generates a string form of a request to be written to the logs,
   * filtering the user environment to remove anything that looks private.
   * The current filter criteria removes any variable whose name includes
   * "auth", "pass", or "cookie".
   *
   * @param requestStrings
   * @return the filtered request to write to the log.
   */
  @VisibleForTesting
  public static String getRequestLogString(List<String> requestStrings) {
    StringBuilder buf = new StringBuilder();
    buf.append('[');
    String sep = "";
    for (String s : requestStrings) {
      buf.append(sep);
      if (s.startsWith("--client_env")) {
        int varStart = "--client_env=".length();
        int varEnd = s.indexOf('=', varStart);
        String varName = s.substring(varStart, varEnd);
        if (suppressFromLog.matcher(varName).matches()) {
          buf.append("--client_env=");
          buf.append(varName);
          buf.append("=__private_value_removed__");
        } else {
          buf.append(s);
        }
      } else {
        buf.append(s);
      }
      sep = ", ";
    }
    buf.append(']');
    return buf.toString();
  }

  /**
   * Command line options split in to two parts: startup options and everything else.
   */
  @VisibleForTesting
  static class CommandLineOptions {
    private final List<String> startupArgs;
    private final List<String> otherArgs;

    CommandLineOptions(List<String> startupArgs, List<String> otherArgs) {
      this.startupArgs = ImmutableList.copyOf(startupArgs);
      this.otherArgs = ImmutableList.copyOf(otherArgs);
    }

    public List<String> getStartupArgs() {
      return startupArgs;
    }

    public List<String> getOtherArgs() {
      return otherArgs;
    }
  }

  /**
   * Splits given arguments into two lists - arguments matching options defined in this class
   * and everything else, while preserving order in each list.
   */
  static CommandLineOptions splitStartupOptions(
      Iterable<BlazeModule> modules, String... args) {
    List<String> prefixes = new ArrayList<>();
    List<Field> startupFields = Lists.newArrayList();
    for (Class<? extends OptionsBase> defaultOptions
      : BlazeCommandUtils.getStartupOptions(modules)) {
      startupFields.addAll(ImmutableList.copyOf(defaultOptions.getFields()));
    }

    for (Field field : startupFields) {
      if (field.isAnnotationPresent(Option.class)) {
        prefixes.add("--" + field.getAnnotation(Option.class).name());
        if (field.getType() == boolean.class || field.getType() == TriState.class) {
          prefixes.add("--no" + field.getAnnotation(Option.class).name());
        }
      }
    }

    List<String> startupArgs = new ArrayList<>();
    List<String> otherArgs = Lists.newArrayList(args);

    for (Iterator<String> argi = otherArgs.iterator(); argi.hasNext(); ) {
      String arg = argi.next();
      if (!arg.startsWith("--")) {
        break;  // stop at command - all startup options would be specified before it.
      }
      for (String prefix : prefixes) {
        if (arg.startsWith(prefix)) {
          startupArgs.add(arg);
          argi.remove();
          break;
        }
      }
    }
    return new CommandLineOptions(startupArgs, otherArgs);
  }

  private static void captureSigint() {
    final Thread mainThread = Thread.currentThread();
    final AtomicInteger numInterrupts = new AtomicInteger();

    final Runnable interruptWatcher = new Runnable() {
      @Override
      public void run() {
        int count = 0;
        // Not an actual infinite loop because it's run in a daemon thread.
        while (true) {
          count++;
          Uninterruptibles.sleepUninterruptibly(10, TimeUnit.SECONDS);
          LOG.warning("Slow interrupt number " + count + " in batch mode");
          ThreadUtils.warnAboutSlowInterrupt();
        }
      }
    };

    new InterruptSignalHandler() {
      @Override
      public void run() {
        LOG.info("User interrupt");
        OutErr.SYSTEM_OUT_ERR.printErrLn("Blaze received an interrupt");
        mainThread.interrupt();

        int curNumInterrupts = numInterrupts.incrementAndGet();
        if (curNumInterrupts == 1) {
          Thread interruptWatcherThread = new Thread(interruptWatcher, "interrupt-watcher");
          interruptWatcherThread.setDaemon(true);
          interruptWatcherThread.start();
        } else if (curNumInterrupts == 2) {
          LOG.warning("Second --batch interrupt: Reverting to JVM SIGINT handler");
          uninstall();
        }
      }
    };
  }

  /**
   * A main method that runs blaze commands in batch mode. The return value indicates the desired
   * exit status of the program.
   */
  private static int batchMain(Iterable<BlazeModule> modules, String[] args) {
    captureSigint();
    CommandLineOptions commandLineOptions = splitStartupOptions(modules, args);
    LOG.info("Running Blaze in batch mode with startup args "
        + commandLineOptions.getStartupArgs());

    String memoryWarning = validateJvmMemorySettings();
    if (memoryWarning != null) {
      OutErr.SYSTEM_OUT_ERR.printErrLn(memoryWarning);
    }

    BlazeRuntime runtime;
    try {
      runtime = newRuntime(modules, parseOptions(modules, commandLineOptions.getStartupArgs()));
    } catch (OptionsParsingException e) {
      OutErr.SYSTEM_OUT_ERR.printErr(e.getMessage());
      return ExitCode.COMMAND_LINE_ERROR.getNumericExitCode();
    } catch (AbruptExitException e) {
      OutErr.SYSTEM_OUT_ERR.printErr(e.getMessage());
      return e.getExitCode().getNumericExitCode();
    }

    BlazeCommandDispatcher dispatcher =
        new BlazeCommandDispatcher(runtime, getBuiltinCommandList());

    try {
      LOG.info(getRequestLogString(commandLineOptions.getOtherArgs()));
      return dispatcher.exec(commandLineOptions.getOtherArgs(), OutErr.SYSTEM_OUT_ERR,
          runtime.getClock().currentTimeMillis());
    } catch (BlazeCommandDispatcher.ShutdownBlazeServerException e) {
      return e.getExitStatus();
    } finally {
      runtime.shutdown();
      dispatcher.shutdown();
    }
  }

  /**
   * A main method that does not send email. The return value indicates the desired exit status of
   * the program.
   */
  private static int serverMain(Iterable<BlazeModule> modules, OutErr outErr, String[] args) {
    try {
      createBlazeRPCServer(modules, Arrays.asList(args)).serve();
      return ExitCode.SUCCESS.getNumericExitCode();
    } catch (OptionsParsingException e) {
      outErr.printErr(e.getMessage());
      return ExitCode.COMMAND_LINE_ERROR.getNumericExitCode();
    } catch (IOException e) {
      outErr.printErr("I/O Error: " + e.getMessage());
      return ExitCode.BUILD_FAILURE.getNumericExitCode();
    } catch (AbruptExitException e) {
      outErr.printErr(e.getMessage());
      return e.getExitCode().getNumericExitCode();
    }
  }

  private static FileSystem fileSystemImplementation() {
    // The JNI-based UnixFileSystem is faster, but on Windows it is not available.
    return OS.getCurrent() == OS.WINDOWS ? new JavaIoFileSystem() : new UnixFileSystem();
  }

  /**
   * Creates and returns a new Blaze RPCServer. Call {@link RPCServer#serve()} to start the server.
   */
  private static RPCServer createBlazeRPCServer(Iterable<BlazeModule> modules, List<String> args)
      throws IOException, OptionsParsingException, AbruptExitException {
    OptionsProvider options = parseOptions(modules, args);
    BlazeServerStartupOptions startupOptions = options.getOptions(BlazeServerStartupOptions.class);

    final BlazeRuntime runtime = newRuntime(modules, options);
    final BlazeCommandDispatcher dispatcher =
        new BlazeCommandDispatcher(runtime, getBuiltinCommandList());
    final String memoryWarning = validateJvmMemorySettings();

    final ServerCommand blazeCommand;

    // Adaptor from RPC mechanism to BlazeCommandDispatcher:
    blazeCommand = new ServerCommand() {
      private boolean shutdown = false;

      @Override
      public int exec(List<String> args, OutErr outErr, long firstContactTime) {
        LOG.info(getRequestLogString(args));
        if (memoryWarning != null) {
          outErr.printErrLn(memoryWarning);
        }

        try {
          return dispatcher.exec(args, outErr, firstContactTime);
        } catch (BlazeCommandDispatcher.ShutdownBlazeServerException e) {
          if (e.getCause() != null) {
            StringWriter message = new StringWriter();
            message.write("Shutting down due to exception:\n");
            PrintWriter writer = new PrintWriter(message, true);
            e.printStackTrace(writer);
            writer.flush();
            LOG.severe(message.toString());
          }
          shutdown = true;
          runtime.shutdown();
          dispatcher.shutdown();
          return e.getExitStatus();
        }
      }

      @Override
      public boolean shutdown() {
        return shutdown;
      }
    };

    RPCServer server = RPCServer.newServerWith(runtime.getClock(), blazeCommand,
        runtime.getServerDirectory(), runtime.getWorkspace(), startupOptions.maxIdleSeconds);
    return server;
  }

  private static Function<String, String> sourceFunctionForMap(final Map<String, String> map) {
    return new Function<String, String>() {
      @Override
      public String apply(String input) {
        if (!map.containsKey(input)) {
          return "default";
        }

        if (map.get(input).isEmpty()) {
          return "command line";
        }

        return map.get(input);
      }
    };
  }

  /**
   * Parses the command line arguments into a {@link OptionsParser} object.
   *
   *  <p>This function needs to parse the --option_sources option manually so that the real option
   * parser can set the source for every option correctly. If that cannot be parsed or is missing,
   * we just report an unknown source for every startup option.
   */
  private static OptionsProvider parseOptions(
      Iterable<BlazeModule> modules, List<String> args) throws OptionsParsingException {
    Set<Class<? extends OptionsBase>> optionClasses = Sets.newHashSet();
    optionClasses.addAll(BlazeCommandUtils.getStartupOptions(modules));
    // First parse the command line so that we get the option_sources argument
    OptionsParser parser = OptionsParser.newOptionsParser(optionClasses);
    parser.setAllowResidue(false);
    parser.parse(OptionPriority.COMMAND_LINE, null, args);
    Function<? super String, String> sourceFunction =
        sourceFunctionForMap(parser.getOptions(BlazeServerStartupOptions.class).optionSources);

    // Then parse the command line again, this time with the correct option sources
    parser = OptionsParser.newOptionsParser(optionClasses);
    parser.setAllowResidue(false);
    parser.parseWithSourceFunction(OptionPriority.COMMAND_LINE, sourceFunction, args);
    return parser;
  }

  /**
   * Creates a new blaze runtime, given the install and output base directories.
   *
   * <p>Note: This method can and should only be called once per startup, as it also creates the
   * filesystem object that will be used for the runtime. So it should only ever be called from the
   * main method of the Blaze program.
   *
   * @param options Blaze startup options.
   *
   * @return a new BlazeRuntime instance initialized with the given filesystem and directories, and
   *         an error string that, if not null, describes a fatal initialization failure that makes
   *         this runtime unsuitable for real commands
   */
  private static BlazeRuntime newRuntime(
      Iterable<BlazeModule> blazeModules, OptionsProvider options) throws AbruptExitException {
    for (BlazeModule module : blazeModules) {
      module.globalInit(options);
    }

    BlazeServerStartupOptions startupOptions = options.getOptions(BlazeServerStartupOptions.class);
    PathFragment workspaceDirectory = startupOptions.workspaceDirectory;
    PathFragment installBase = startupOptions.installBase;
    PathFragment outputBase = startupOptions.outputBase;

    OsUtils.maybeForceJNI(installBase);  // Must be before first use of JNI.

    // From the point of view of the Java program --install_base and --output_base
    // are mandatory options, despite the comment in their declarations.
    if (installBase == null || !installBase.isAbsolute()) { // (includes "" default case)
      throw new IllegalArgumentException(
          "Bad --install_base option specified: '" + installBase + "'");
    }
    if (outputBase != null && !outputBase.isAbsolute()) { // (includes "" default case)
      throw new IllegalArgumentException(
          "Bad --output_base option specified: '" + outputBase + "'");
    }

    PathFragment outputPathFragment = BlazeDirectories.outputPathFromOutputBase(
        outputBase, workspaceDirectory);
    FileSystem fs = null;
    for (BlazeModule module : blazeModules) {
      FileSystem moduleFs = module.getFileSystem(options, outputPathFragment);
      if (moduleFs != null) {
        Preconditions.checkState(fs == null, "more than one module returns a file system");
        fs = moduleFs;
      }
    }

    if (fs == null) {
      fs = fileSystemImplementation();
    }
    Path.setFileSystemForSerialization(fs);

    Path installBasePath = fs.getPath(installBase);
    Path outputBasePath = fs.getPath(outputBase);
    Path workspaceDirectoryPath = null;
    if (!workspaceDirectory.equals(PathFragment.EMPTY_FRAGMENT)) {
      workspaceDirectoryPath = fs.getPath(workspaceDirectory);
    }

    BlazeDirectories directories =
        new BlazeDirectories(installBasePath, outputBasePath, workspaceDirectoryPath);

    Clock clock = BlazeClock.instance();

    BinTools binTools;
    try {
      binTools = BinTools.forProduction(directories);
    } catch (IOException e) {
      throw new AbruptExitException(
          "Cannot enumerate embedded binaries: " + e.getMessage(),
          ExitCode.LOCAL_ENVIRONMENTAL_ERROR);
    }

    BlazeRuntime.Builder runtimeBuilder = new BlazeRuntime.Builder().setDirectories(directories)
        .setStartupOptionsProvider(options)
        .setBinTools(binTools)
        .setClock(clock)
        // TODO(bazel-team): Make BugReportingExceptionHandler the default.
        // See bug "Make exceptions in EventBus subscribers fatal"
        .setEventBusExceptionHandler(
            startupOptions.fatalEventBusExceptions || !BlazeVersionInfo.instance().isReleasedBlaze()
                ? new BlazeRuntime.BugReportingExceptionHandler()
                : new BlazeRuntime.RemoteExceptionHandler());

    for (BlazeModule blazeModule : blazeModules) {
      runtimeBuilder.addBlazeModule(blazeModule);
    }

    BlazeRuntime runtime = runtimeBuilder.build();
    BugReport.setRuntime(runtime);
    return runtime;
  }

  /**
   * Returns null if JVM memory settings are considered safe, and an error string otherwise.
   */
  private static String validateJvmMemorySettings() {
    boolean is64BitVM = "64".equals(System.getProperty("sun.arch.data.model"));
    if (is64BitVM) {
      return null;
    }
    MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
    long heapSize = mem.getHeapMemoryUsage().getMax();
    long nonHeapSize = mem.getNonHeapMemoryUsage().getMax();
    if (heapSize == -1 || nonHeapSize == -1) {
      return null;
    }

    if (heapSize + nonHeapSize > MAX_BLAZE32_RESERVED_MEMORY) {
      return String.format(
          "WARNING: JVM reserved %d MB of virtual memory (above threshold of %d MB). "
          + "This may result in OOMs at runtime. Use lower values of MaxPermSize "
          + "or switch to blaze64.",
          (heapSize + nonHeapSize) >> 20, MAX_BLAZE32_RESERVED_MEMORY >> 20);
    } else if (heapSize < MIN_BLAZE32_HEAP_SIZE) {
      return String.format(
          "WARNING: JVM heap size is %d MB. You probably have a custom -Xmx setting in your "
          + "local Blaze configuration. This may result in OOMs. Removing overrides of -Xmx "
          + "settings is advised.",
          heapSize >> 20);
    } else {
      return null;
    }
  }

  /**
   * Make sure async threads cannot be orphaned. This method makes sure bugs are reported to
   * telemetry and the proper exit code is reported.
   */
  private static void setupUncaughtHandler(final String[] args) {
    Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
      @Override
      public void uncaughtException(Thread thread, Throwable throwable) {
        BugReport.handleCrash(throwable, args);
      }
    });
  }


  /**
   * Returns an immutable list containing new instances of each Blaze command.
   */
  @VisibleForTesting
  public static List<BlazeCommand> getBuiltinCommandList() {
    return ImmutableList.of(
        new BuildCommand(),
        new CanonicalizeCommand(),
        new CleanCommand(),
        new DumpCommand(),
        new HelpCommand(),
        new InfoCommand(),
        new MobileInstallCommand(),
        new ProfileCommand(),
        new QueryCommand(),
        new RunCommand(),
        new ShutdownCommand(),
        new TestCommand(),
        new VersionCommand());
  }

  /**
   * A builder for {@link BlazeRuntime} objects. The only required fields are the {@link
   * BlazeDirectories}, and the {@link RuleClassProvider} (except for testing). All other fields
   * have safe default values.
   *
   * <p>If a {@link ConfigurationFactory} is set, then the builder ignores the host system flag.
   * <p>The default behavior of the BlazeRuntime's EventBus is to exit when a subscriber throws
   * an exception. Please plan appropriately.
   */
  public static class Builder {

    private BlazeDirectories directories;
    private Reporter reporter;
    private ConfigurationFactory configurationFactory;
    private Clock clock;
    private OptionsProvider startupOptionsProvider;
    private final List<BlazeModule> blazeModules = Lists.newArrayList();
    private SubscriberExceptionHandler eventBusExceptionHandler =
        new RemoteExceptionHandler();
    private BinTools binTools;
    private UUID instanceId;

    public BlazeRuntime build() throws AbruptExitException {
      Preconditions.checkNotNull(directories);
      Preconditions.checkNotNull(startupOptionsProvider);
      Reporter reporter = (this.reporter == null) ? new Reporter() : this.reporter;

      Clock clock = (this.clock == null) ? BlazeClock.instance() : this.clock;
      UUID instanceId =  (this.instanceId == null) ? UUID.randomUUID() : this.instanceId;

      Preconditions.checkNotNull(clock);
      Map<String, String> clientEnv = new HashMap<>();
      TimestampGranularityMonitor timestampMonitor = new TimestampGranularityMonitor(clock);

      Preprocessor.Factory.Supplier preprocessorFactorySupplier = null;
      SkyframeExecutorFactory skyframeExecutorFactory = null;
      for (BlazeModule module : blazeModules) {
        module.blazeStartup(startupOptionsProvider,
            BlazeVersionInfo.instance(), instanceId, directories, clock);
        Preprocessor.Factory.Supplier modulePreprocessorFactorySupplier =
            module.getPreprocessorFactorySupplier();
        if (modulePreprocessorFactorySupplier != null) {
          Preconditions.checkState(preprocessorFactorySupplier == null,
              "more than one module defines a preprocessor factory supplier");
          preprocessorFactorySupplier = modulePreprocessorFactorySupplier;
        }
        SkyframeExecutorFactory skyFactory = module.getSkyframeExecutorFactory();
        if (skyFactory != null) {
          Preconditions.checkState(skyframeExecutorFactory == null,
              "At most one skyframe factory supported. But found two: %s and %s", skyFactory,
              skyframeExecutorFactory);
          skyframeExecutorFactory = skyFactory;
        }
      }
      if (skyframeExecutorFactory == null) {
        skyframeExecutorFactory = new SequencedSkyframeExecutorFactory();
      }
      if (preprocessorFactorySupplier == null) {
        preprocessorFactorySupplier = Preprocessor.Factory.Supplier.NullSupplier.INSTANCE;
      }

      ConfiguredRuleClassProvider.Builder ruleClassBuilder =
          new ConfiguredRuleClassProvider.Builder();
      for (BlazeModule module : blazeModules) {
        module.initializeRuleClasses(ruleClassBuilder);
      }

      Map<String, String> platformRegexps = null;
      {
        ImmutableMap.Builder<String, String> builder = new ImmutableMap.Builder<>();
        for (BlazeModule module : blazeModules) {
          builder.putAll(module.getPlatformSetRegexps());
        }
        platformRegexps = builder.build();
        if (platformRegexps.isEmpty()) {
          platformRegexps = null; // Use the default.
        }
      }

      Set<Path> immutableDirectories = null;
      {
        ImmutableSet.Builder<Path> builder = new ImmutableSet.Builder<>();
        for (BlazeModule module : blazeModules) {
          builder.addAll(module.getImmutableDirectories());
        }
        immutableDirectories = builder.build();
      }

      Iterable<DiffAwareness.Factory> diffAwarenessFactories = null;
      {
        ImmutableList.Builder<DiffAwareness.Factory> builder = new ImmutableList.Builder<>();
        boolean watchFS = startupOptionsProvider != null
            && startupOptionsProvider.getOptions(BlazeServerStartupOptions.class).watchFS;
        for (BlazeModule module : blazeModules) {
          builder.addAll(module.getDiffAwarenessFactories(watchFS));
        }
        diffAwarenessFactories = builder.build();
      }

      // Merge filters from Blaze modules that allow some action inputs to be missing.
      Predicate<PathFragment> allowedMissingInputs = null;
      for (BlazeModule module : blazeModules) {
        Predicate<PathFragment> modulePredicate = module.getAllowedMissingInputs();
        if (modulePredicate != null) {
          Preconditions.checkArgument(allowedMissingInputs == null,
              "More than one Blaze module allows missing inputs.");
          allowedMissingInputs = modulePredicate;
        }
      }
      if (allowedMissingInputs == null) {
        allowedMissingInputs = Predicates.alwaysFalse();
      }

      ConfiguredRuleClassProvider ruleClassProvider = ruleClassBuilder.build();
      WorkspaceStatusAction.Factory workspaceStatusActionFactory = null;
      for (BlazeModule module : blazeModules) {
        WorkspaceStatusAction.Factory candidate = module.getWorkspaceStatusActionFactory();
        if (candidate != null) {
          Preconditions.checkState(workspaceStatusActionFactory == null,
              "more than one module defines a workspace status action factory");
          workspaceStatusActionFactory = candidate;
        }
      }

      List<PackageFactory.EnvironmentExtension> extensions = new ArrayList<>();
      for (BlazeModule module : blazeModules) {
        extensions.add(module.getPackageEnvironmentExtension());
      }

      // We use an immutable map builder for the nice side effect that it throws if a duplicate key
      // is inserted.
      ImmutableMap.Builder<SkyFunctionName, SkyFunction> skyFunctions = ImmutableMap.builder();
      for (BlazeModule module : blazeModules) {
        skyFunctions.putAll(module.getSkyFunctions(directories));
      }

      ImmutableList.Builder<PrecomputedValue.Injected> precomputedValues = ImmutableList.builder();
      for (BlazeModule module : blazeModules) {
        precomputedValues.addAll(module.getPrecomputedSkyframeValues());
      }

      final PackageFactory pkgFactory =
          new PackageFactory(ruleClassProvider, platformRegexps, extensions);
      SkyframeExecutor skyframeExecutor = skyframeExecutorFactory.create(reporter, pkgFactory,
          timestampMonitor, directories, workspaceStatusActionFactory,
          ruleClassProvider.getBuildInfoFactories(), immutableDirectories, diffAwarenessFactories,
          allowedMissingInputs, preprocessorFactorySupplier, skyFunctions.build(),
          precomputedValues.build());

      if (configurationFactory == null) {
        configurationFactory = new ConfigurationFactory(
            ruleClassProvider.getConfigurationCollectionFactory(),
            ruleClassProvider.getConfigurationFragments());
      }

      ProjectFile.Provider projectFileProvider = null;
      for (BlazeModule module : blazeModules) {
        ProjectFile.Provider candidate = module.createProjectFileProvider();
        if (candidate != null) {
          Preconditions.checkState(projectFileProvider == null,
              "more than one module defines a project file provider");
          projectFileProvider = candidate;
        }
      }

      return new BlazeRuntime(directories, reporter, workspaceStatusActionFactory, skyframeExecutor,
          pkgFactory, ruleClassProvider, configurationFactory,
          clock, startupOptionsProvider, ImmutableList.copyOf(blazeModules),
          clientEnv, timestampMonitor,
          eventBusExceptionHandler, binTools, projectFileProvider);
    }

    public Builder setBinTools(BinTools binTools) {
      this.binTools = binTools;
      return this;
    }

    public Builder setDirectories(BlazeDirectories directories) {
      this.directories = directories;
      return this;
    }

    /**
     * Creates and sets a new {@link BlazeDirectories} instance with the given
     * parameters.
     */
    public Builder setDirectories(Path installBase, Path outputBase,
        Path workspace) {
      this.directories = new BlazeDirectories(installBase, outputBase, workspace);
      return this;
    }

    public Builder setReporter(Reporter reporter) {
      this.reporter = reporter;
      return this;
    }

    public Builder setConfigurationFactory(ConfigurationFactory configurationFactory) {
      this.configurationFactory = configurationFactory;
      return this;
    }

    public Builder setClock(Clock clock) {
      this.clock = clock;
      return this;
    }

    public Builder setStartupOptionsProvider(OptionsProvider startupOptionsProvider) {
      this.startupOptionsProvider = startupOptionsProvider;
      return this;
    }

    public Builder addBlazeModule(BlazeModule blazeModule) {
      blazeModules.add(blazeModule);
      return this;
    }

    public Builder setInstanceId(UUID id) {
      instanceId = id;
      return this;
    }

    @VisibleForTesting
    public Builder setEventBusExceptionHandler(
        SubscriberExceptionHandler eventBusExceptionHandler) {
      this.eventBusExceptionHandler = eventBusExceptionHandler;
      return this;
    }
  }
}
