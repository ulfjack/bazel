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

package com.google.devtools.build.lib.analysis.config;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.devtools.build.lib.actions.ArtifactFactory;
import com.google.devtools.build.lib.actions.PackageRootResolver;
import com.google.devtools.build.lib.actions.Root;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.ViewCreationFailedException;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationCollection.Transitions;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.Attribute.Configurator;
import com.google.devtools.build.lib.packages.Attribute.SplitTransition;
import com.google.devtools.build.lib.packages.Attribute.Transition;
import com.google.devtools.build.lib.packages.InputFile;
import com.google.devtools.build.lib.packages.PackageGroup;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.rules.test.TestActionBuilder;
import com.google.devtools.build.lib.syntax.Label;
import com.google.devtools.build.lib.syntax.Label.SyntaxException;
import com.google.devtools.build.lib.syntax.SkylarkCallable;
import com.google.devtools.build.lib.syntax.SkylarkModule;
import com.google.devtools.build.lib.util.CPU;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.util.RegexFilter;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.common.options.Converter;
import com.google.devtools.common.options.Converters;
import com.google.devtools.common.options.EnumConverter;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.devtools.common.options.TriState;

import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;

import javax.annotation.Nullable;

/**
 * Instances of BuildConfiguration represent a collection of context
 * information which may affect a build (for example: the target platform for
 * compilation, or whether or not debug tables are required).  In fact, all
 * "environmental" information (e.g. from the tool's command-line, as opposed
 * to the BUILD file) that can affect the output of any build tool should be
 * explicitly represented in the BuildConfiguration instance.
 *
 * <p>A single build may require building tools to run on a variety of
 * platforms: when compiling a server application for production, we must build
 * the build tools (like compilers) to run on the host platform, but cross-compile
 * the application for the production environment.
 *
 * <p>There is always at least one BuildConfiguration instance in any build:
 * the one representing the host platform. Additional instances may be created,
 * in a cross-compilation build, for example.
 *
 * <p>Instances of BuildConfiguration are canonical:
 * <pre>c1.equals(c2) <=> c1==c2.</pre>
 */
@SkylarkModule(name = "configuration",
    doc = "Data required for the analysis of a target that comes from targets that "
        + "depend on it and not targets that it depends on.")
public final class BuildConfiguration {

  /**
   * An interface for language-specific configurations.
   */
  public abstract static class Fragment {
    /**
     * Validates the options for this Fragment. Issues warnings for the
     * use of deprecated options, and warnings or errors for any option settings
     * that conflict.
     */
    @SuppressWarnings("unused")
    public void reportInvalidOptions(EventHandler reporter, BuildOptions buildOptions) {
    }

    /**
     * Adds mapping of names to values of "Make" variables defined by this configuration.
     */
    @SuppressWarnings("unused")
    public void addGlobalMakeVariables(ImmutableMap.Builder<String, String> globalMakeEnvBuilder) {
    }

    /**
     * Collects all labels that should be implicitly loaded from labels that were specified as
     * options, keyed by the name to be displayed to the user if something goes wrong.
     * The resulting set only contains labels that were derived from command-line options; the
     * intention is that it can be used to sanity-check that the command-line options actually
     * contain these in their transitive closure.
     */
    @SuppressWarnings("unused")
    public void addImplicitLabels(Multimap<String, Label> implicitLabels) {
    }

    /**
     * The fragment may use this hook to perform I/O and read data into memory that is used during
     * analysis. During the analysis phase disk I/O operations are disallowed.
     *
     * <p>This hook is called for all configurations after the loading phase is complete.
     */
    @SuppressWarnings("unused")
    public void prepareHook(Path execPath, ArtifactFactory artifactFactory,
        PathFragment genfilesPath, PackageRootResolver resolver)
        throws ViewCreationFailedException {
    }

    /**
     * Adds all the roots from this fragment.
     */
    @SuppressWarnings("unused")
    public void addRoots(List<Root> roots) {
    }

    /**
     * Returns a (key, value) mapping to insert into the subcommand environment for coverage.
     */
    public Map<String, String> getCoverageEnvironment() {
      return ImmutableMap.<String, String>of();
    }

    /*
     * Returns the command-line "Make" variable overrides.
     */
    public ImmutableMap<String, String> getCommandLineDefines() {
      return ImmutableMap.of();
    }

    /**
     * Returns all the coverage labels for the fragment.
     */
    public ImmutableList<Label> getCoverageLabels() {
      return ImmutableList.of();
    }

    /**
     * Returns the coverage report generator tool labels.
     */
    public ImmutableList<Label> getCoverageReportGeneratorLabels() {
      return ImmutableList.of();
    }

    /**
     * Returns a fragment of the output directory name for this configuration. The output
     * directory for the whole configuration contains all the short names by all fragments.
     */
    @Nullable
    public String getOutputDirectoryName() {
      return null;
    }

    /**
     * The platform name is a concatenation of fragment platform names.
     */
    public String getPlatformName() {
      return "";
    }

    /**
     * Return false if incremental build is not possible for some reason.
     */
    public boolean supportsIncrementalBuild() {
      return true;
    }

    /**
     * Return true if the fragment performs static linking. This information is needed for
     * lincence checking.
     */
    public boolean performsStaticLink() {
      return false;
    }

    /**
     * Fragments should delete temporary directories they create for their inner mechanisms.
     * This is only called for target configuration.
     */
    @SuppressWarnings("unused")
    public void prepareForExecutionPhase() throws IOException {
    }

    /**
     * Add items to the shell environment.
     */
    @SuppressWarnings("unused")
    public void setupShellEnvironment(ImmutableMap.Builder<String, String> builder) {
    }

    /**
     * Add mappings from generally available tool names (like "sh") to their paths
     * that actions can access.
     */
    @SuppressWarnings("unused")
    public void defineExecutables(ImmutableMap.Builder<String, PathFragment> builder) {
    }

    /**
     * Returns { 'option name': 'alternative default' } entries for options where the
     * "real default" should be something besides the default specified in the {@link Option}
     * declaration.
     */
    public Map<String, Object> lateBoundOptionDefaults() {
      return ImmutableMap.of();
    }

    /**
     * Declares dependencies on any relevant Skyframe values (for example, relevant FileValues).
     *
     * @param env the skyframe environment
     */
    public void declareSkyframeDependencies(Environment env) {
    }
  }

  /**
   * A converter from strings to Labels.
   */
  public static class LabelConverter implements Converter<Label> {
    @Override
    public Label convert(String input) throws OptionsParsingException {
      try {
        // Check if the input starts with '/'. We don't check for "//" so that
        // we get a better error message if the user accidentally tries to use
        // an absolute path (starting with '/') for a label.
        if (!input.startsWith("/") && !input.startsWith("@")) {
          input = "//" + input;
        }
        return Label.parseAbsolute(input);
      } catch (SyntaxException e) {
        throw new OptionsParsingException(e.getMessage());
      }
    }

    @Override
    public String getTypeDescription() {
      return "a build target label";
    }
  }

  /** TODO(bazel-team): document this */
  public static class PluginOptionConverter implements Converter<Map.Entry<String, String>> {
    @Override
    public Map.Entry<String, String> convert(String input) throws OptionsParsingException {
      int index = input.indexOf('=');
      if (index == -1) {
        throw new OptionsParsingException("Plugin option not in the plugin=option format");
      }
      String option = input.substring(0, index);
      String value = input.substring(index + 1);
      return Maps.immutableEntry(option, value);
    }

    @Override
    public String getTypeDescription() {
      return "An option for a plugin";
    }
  }

  /** TODO(bazel-team): document this */
  public static class RunsPerTestConverter extends PerLabelOptions.PerLabelOptionsConverter {
    @Override
    public PerLabelOptions convert(String input) throws OptionsParsingException {
      try {
        return parseAsInteger(input);
      } catch (NumberFormatException ignored) {
        return parseAsRegex(input);
      }
    }

    private PerLabelOptions parseAsInteger(String input)
        throws NumberFormatException, OptionsParsingException {
      int numericValue = Integer.parseInt(input);
      if (numericValue <= 0) {
        throw new OptionsParsingException("'" + input + "' should be >= 1");
      } else {
        RegexFilter catchAll = new RegexFilter(Collections.singletonList(".*"),
            Collections.<String>emptyList());
        return new PerLabelOptions(catchAll, Collections.singletonList(input));
      }
    }

    private PerLabelOptions parseAsRegex(String input) throws OptionsParsingException {
      PerLabelOptions testRegexps = super.convert(input);
      if (testRegexps.getOptions().size() != 1) {
        throw new OptionsParsingException(
            "'" + input + "' has multiple runs for a single pattern");
      }
      String runsPerTest = Iterables.getOnlyElement(testRegexps.getOptions());
      try {
        int numericRunsPerTest = Integer.parseInt(runsPerTest);
        if (numericRunsPerTest <= 0) {
          throw new OptionsParsingException("'" + input + "' has a value < 1");
        }
      } catch (NumberFormatException e) {
        throw new OptionsParsingException("'" + input + "' has a non-numeric value", e);
      }
      return testRegexps;
    }

    @Override
    public String getTypeDescription() {
      return "a positive integer or test_regex@runs. This flag may be passed more than once";
    }
  }

  /**
   * Values for the --strict_*_deps option
   */
  public static enum StrictDepsMode {
    /** Silently allow referencing transitive dependencies. */
    OFF,
    /** Warn about transitive dependencies being used directly. */
    WARN,
    /** Fail the build when transitive dependencies are used directly. */
    ERROR,
    /** Transition to strict by default. */
    STRICT,
    /** When no flag value is specified on the command line. */
    DEFAULT
  }

  /**
   * Converter for the --strict_*_deps option.
   */
  public static class StrictDepsConverter extends EnumConverter<StrictDepsMode> {
    public StrictDepsConverter() {
      super(StrictDepsMode.class, "strict dependency checking level");
    }
  }

  /**
   * Converter for default --host_cpu to the auto-detected host cpu.
   *
   * <p>This detects the host cpu of the Blaze's server but if the compilation happens in a
   * compilation cluster then the host cpu of the compilation cluster might be different than
   * the auto-detected one and the --host_cpu option must then be set explicitly.
   */
  public static class HostCpuConverter implements Converter<String> {
    @Override
    public String convert(String input) throws OptionsParsingException {
      if (input.isEmpty()) {
        // TODO(philwo) - replace these deprecated names with more logical ones (e.g. k8 becomes
        // linux-x86_64, darwin includes the CPU architecture, ...).
        switch (OS.getCurrent()) {
          case DARWIN:
            return "darwin";
          case LINUX:
            switch (CPU.getCurrent()) {
              case X86_32:
                return "piii";
              case X86_64:
                return "k8";
            }
        }
        return "unknown";
      }
      return input;
    }

    @Override
    public String getTypeDescription() {
      return "a string";
    }
  }

  /**
   * Options that affect the value of a BuildConfiguration instance.
   *
   * <p>(Note: any client that creates a view will also need to declare
   * BuildView.Options, which affect the <i>mechanism</i> of view construction,
   * even if they don't affect the value of the BuildConfiguration instances.)
   *
   * <p>IMPORTANT: when adding new options, be sure to consider whether those
   * values should be propagated to the host configuration or not (see
   * {@link ConfigurationFactory#getConfiguration}.
   *
   * <p>ALSO IMPORTANT: all option types MUST define a toString method that
   * gives identical results for semantically identical option values. The
   * simplest way to ensure that is to return the input string.
   */
  public static class Options extends FragmentOptions implements Cloneable {
    public String getCpu() {
      return cpu;
    }

    @Option(name = "cpu",
        defaultValue = "null",
        category = "semantics",
        help = "The target CPU.")
    public String cpu;

    @Option(name = "min_param_file_size",
        defaultValue = "32768",
        category = "undocumented",
        help = "Minimum command line length before creating a parameter file.")
    public int minParamFileSize;

    @Option(name = "experimental_extended_sanity_checks",
        defaultValue = "false",
        category = "undocumented",
        help  = "Enables internal validation checks to make sure that configured target "
            + "implementations only access things they should. Causes a performance hit.")
    public boolean extendedSanityChecks;

    @Option(name = "experimental_allow_runtime_deps_on_neverlink",
        defaultValue = "true",
        category = "undocumented",
        help = "Flag to help transition from allowing to disallowing runtime_deps on neverlink"
            + " Java archives. The depot needs to be cleaned up to roll this out by default.")
    public boolean allowRuntimeDepsOnNeverLink;

    @Option(name = "strict_filesets",
        defaultValue = "false",
        category = "semantics",
        help = "If this option is enabled, filesets crossing package boundaries are reported "
            + "as errors. It does not work when check_fileset_dependencies_recursively is "
            + "disabled.")
    public boolean strictFilesets;

    // Plugins are build using the host config. To avoid cycles we just don't propagate
    // this option to the host config. If one day we decide to use plugins when building
    // host tools, we can improve this by (for example) creating a compiler configuration that is
    // used only for building plugins.
    @Option(name = "plugin",
        converter = LabelConverter.class,
        allowMultiple = true,
        defaultValue = "",
        category = "flags",
        help = "Plugins to use in the build. Currently works with java_plugin.")
    public List<Label> pluginList;

    @Option(name = "plugin_copt",
        converter = PluginOptionConverter.class,
        allowMultiple = true,
        category = "flags",
        defaultValue = ":",
        help = "Plugin options")
    public List<Map.Entry<String, String>> pluginCoptList;

    @Option(name = "stamp",
        defaultValue = "true",
        category = "semantics",
        help = "Stamp binaries with the date, username, hostname, workspace information, etc.")
    public boolean stampBinaries;

    // TODO(bazel-team): delete from OSS tree
    // This value is always overwritten in the case of "blaze coverage" by :
    // CoverageCommand.setDefaultInstrumentationFilter()
    @Option(name = "instrumentation_filter",
        converter = RegexFilter.RegexFilterConverter.class,
        defaultValue = "-javatests,-_test$,-Tests$",
        category = "semantics",
        help = "When coverage is enabled, only rules with names included by the "
            + "specified regex-based filter will be instrumented. Rules prefixed "
            + "with '-' are excluded instead. By default, rules containing "
            + "'javatests' or ending with '_test' will not be instrumented.")
    public RegexFilter instrumentationFilter;

    @Option(name = "show_cached_analysis_results",
        defaultValue = "true",
        category = "undocumented",
        help = "Bazel reruns a static analysis only if it detects changes in the analysis "
            + "or its dependencies. If this option is enabled, Bazel will show the analysis' "
            + "results, even if it did not rerun the analysis.  If this option is disabled, "
            + "Bazel will show analysis results only if it reran the analysis.")
    public boolean showCachedAnalysisResults;

    @Option(name = "host_cpu",
        defaultValue = "",
        category = "semantics",
        converter = HostCpuConverter.class,
        help = "The host CPU.")
    public String hostCpu;

    @Option(name = "compilation_mode",
        abbrev = 'c',
        converter = CompilationMode.Converter.class,
        defaultValue = "fastbuild",
        category = "semantics", // Should this be "flags"?
        help = "Specify the mode the binary will be built in. "
               + "Values: 'fastbuild', 'dbg', 'opt'.")
    public CompilationMode compilationMode;

    /**
     * This option is used internally to set output directory name of the <i>host</i> configuration
     * to a constant, so that the output files for the host are completely independent of those for
     * the target, no matter what options are in force (k8/piii, opt/dbg, etc).
     */
    @Option(name = "output directory name", // (Spaces => can't be specified on command line.)
        defaultValue = "null",
        category = "undocumented")
    public String outputDirectoryName;

    @Option(name = "platform_suffix",
        defaultValue = "null",
        category = "misc",
        help = "Specifies a suffix to be added to the configuration directory.")
    public String platformSuffix;

    // TODO(bazel-team): The test environment is actually computed in BlazeRuntime and this option
    // is not read anywhere else. Thus, it should be in a different options class, preferably one
    // specific to the "test" command or maybe in its own configuration fragment.
    // BlazeRuntime, though.
    @Option(name = "test_env",
        converter = Converters.OptionalAssignmentConverter.class,
        allowMultiple = true,
        defaultValue = "",
        category = "testing",
        help = "Specifies additional environment variables to be injected into the test runner "
            + "environment. Variables can be either specified by name, in which case its value "
            + "will be read from the Bazel client environment, or by the name=value pair. "
            + "This option can be used multiple times to specify several variables. "
            + "Used only by the 'bazel test' command."
        )
    public List<Map.Entry<String, String>> testEnvironment;

    @Option(name = "collect_code_coverage",
        defaultValue = "false",
        category = "testing",
        help = "If specified, Bazel will instrument code (using offline instrumentation where "
            + "possible) and will collect coverage information during tests. Only targets that "
            + " match --instrumentation_filter will be affected. Usually this option should "
            + " not be specified directly - 'bazel coverage' command should be used instead."
        )
    public boolean collectCodeCoverage;

    @Option(name = "microcoverage",
        defaultValue = "false",
        category = "testing",
        help = "If specified with coverage, Blaze will collect microcoverage (per test method "
            + "coverage) information during tests. Only targets that match "
            + "--instrumentation_filter will be affected. Usually this option should not be "
            + "specified directly - 'blaze coverage --microcoverage' command should be used "
            + "instead."
        )
    public boolean collectMicroCoverage;

    @Option(name = "cache_test_results",
        defaultValue = "auto",
        category = "testing",
        abbrev = 't', // it's useful to toggle this on/off quickly
        help = "If 'auto', Bazel will only rerun a test if any of the following conditions apply: "
            + "(1) Bazel detects changes in the test or its dependencies "
            + "(2) the test is marked as external "
            + "(3) multiple test runs were requested with --runs_per_test"
            + "(4) the test failed"
            + "If 'yes', the caching behavior will be the same as 'auto' except that "
            + "it may cache test failures and test runs with --runs_per_test."
            + "If 'no', all tests will be always executed.")
    public TriState cacheTestResults;

    @Deprecated
    @Option(name = "test_result_expiration",
        defaultValue = "-1", // No expiration by defualt.
        category = "testing",
        help = "This option is deprecated and has no effect.")
    public int testResultExpiration;

    @Option(name = "test_sharding_strategy",
        defaultValue = "explicit",
        category = "testing",
        converter = TestActionBuilder.ShardingStrategyConverter.class,
        help = "Specify strategy for test sharding: "
            + "'explicit' to only use sharding if the 'shard_count' BUILD attribute is present. "
            + "'disabled' to never use test sharding. "
            + "'experimental_heuristic' to enable sharding on remotely executed tests without an "
            + "explicit  'shard_count' attribute which link in a supported framework. Considered "
            + "experimental.")
    public TestActionBuilder.TestShardingStrategy testShardingStrategy;

    @Option(name = "runs_per_test",
        allowMultiple = true,
        defaultValue = "1",
        category = "testing",
        converter = RunsPerTestConverter.class,
        help = "Specifies number of times to run each test. If any of those attempts "
            + "fail for any reason, the whole test would be considered failed. "
            + "Normally the value specified is just an integer. Example: --runs_per_test=3 "
            + "will run all tests 3 times. "
            + "Alternate syntax: regex_filter@runs_per_test. Where runs_per_test stands for "
            + "an integer value and regex_filter stands "
            + "for a list of include and exclude regular expression patterns (Also see "
            + "--instrumentation_filter). Example: "
            + "--runs_per_test=//foo/.*,-//foo/bar/.*@3 runs all tests in //foo/ "
            + "except those under foo/bar three times. "
            + "This option can be passed multiple times. ")
    public List<PerLabelOptions> runsPerTest;

    @Option(name = "build_runfile_links",
        defaultValue = "true",
        category = "strategy",
        help = "If true, build runfiles symlink forests for all targets.  "
            + "If false, write only manifests when possible.")
    public boolean buildRunfiles;

    @Option(name = "test_arg",
        allowMultiple = true,
        defaultValue = "",
        category = "testing",
        help = "Specifies additional options and arguments that should be passed to the test "
            + "executable. Can be used multiple times to specify several arguments. "
            + "If multiple tests are executed, each of them will receive identical arguments. "
            + "Used only by the 'bazel test' command."
        )
    public List<String> testArguments;

    @Option(name = "test_filter",
        allowMultiple = false,
        defaultValue = "null",
        category = "testing",
        help = "Specifies a filter to forward to the test framework.  Used to limit "
            + "the tests run. Note that this does not affect which targets are built.")
    public String testFilter;

    @Option(name = "check_fileset_dependencies_recursively",
        defaultValue = "true",
        category = "semantics",
        help = "If false, fileset targets will, whenever possible, create "
            + "symlinks to directories instead of creating one symlink for each "
            + "file inside the directory. Disabling this will significantly "
            + "speed up fileset builds, but targets that depend on filesets will "
            + "not be rebuilt if files are added, removed or modified in a "
            + "subdirectory which has not been traversed.")
    public boolean checkFilesetDependenciesRecursively;

    @Option(name = "run_under",
        category = "run",
        defaultValue = "null",
        converter = RunUnderConverter.class,
        help = "Prefix to insert in front of command before running. "
            + "Examples:\n"
            + "\t--run_under=valgrind\n"
            + "\t--run_under=strace\n"
            + "\t--run_under='strace -c'\n"
            + "\t--run_under='valgrind --quiet --num-callers=20'\n"
            + "\t--run_under=//package:target\n"
            + "\t--run_under='//package:target --options'\n")
    public RunUnder runUnder;

    @Option(name = "distinct_host_configuration",
        defaultValue = "true",
        category = "strategy",
        help = "Build all the tools used during the build for a distinct configuration from "
            + "that used for the target program.  By default, the same configuration is used "
            + "for host and target programs, but this may cause undesirable rebuilds of tool "
            + "such as the protocol compiler (and then everything downstream) whenever a minor "
            + "change is made to the target configuration, such as setting the linker options.  "
            + "When this flag is specified, a distinct configuration will be used to build the "
            + "tools, preventing undesired rebuilds.  However, certain libraries will then "
            + "need to be compiled twice, once for each configuration, which may cause some "
            + "builds to be slower.  As a rule of thumb, this option is likely to benefit "
            + "users that make frequent changes in configuration (e.g. opt/dbg).  "
            + "Please read the user manual for the full explanation.")
    public boolean useDistinctHostConfiguration;

    @Option(name = "check_visibility",
        defaultValue = "true",
        category = "checking",
        help = "If disabled, visibility errors are demoted to warnings.")
    public boolean checkVisibility;

    // Moved from viewOptions to here because license information is very expensive to serialize.
    // Having it here allows us to skip computation of transitive license information completely
    // when the setting is disabled.
    @Option(name = "check_licenses",
        defaultValue = "false",
        category = "checking",
        help = "Check that licensing constraints imposed by dependent packages "
            + "do not conflict with distribution modes of the targets being built. "
            + "By default, licenses are not checked.")
    public boolean checkLicenses;

    @Option(name = "experimental_enforce_constraints",
        defaultValue = "true",
        category = "undocumented",
        help = "Checks the environments each target is compatible with and reports errors if any "
            + "target has dependencies that don't support the same environments")
    public boolean enforceConstraints;

    @Option(name = "experimental_action_listener",
        allowMultiple = true,
        defaultValue = "",
        category = "experimental",
        converter = LabelConverter.class,
        help = "Use action_listener to attach an extra_action to existing build actions.")
    public List<Label> actionListeners;

    @Option(name = "is host configuration",
        defaultValue = "false",
        category = "undocumented",
        help = "Shows whether these options are set for host configuration.")
    public boolean isHost;

    @Option(name = "experimental_proto_header_modules",
        defaultValue = "false",
        category = "undocumented",
        help  = "Enables compilation of C++ header modules for proto libraries.")
    public boolean protoHeaderModules;

    @Option(name = "features",
        allowMultiple = true,
        defaultValue = "",
        category = "flags",
        help = "The given features will be enabled or disabled by default for all packages. "
            + "Specifying -<feature> will disable the feature globally. "
            + "Negative features always override positive ones. "
            + "This flag is used to enable rolling out default feature changes without a "
            + "Blaze release.")
    public List<String> defaultFeatures;

    @Option(name = "target_environment",
        converter = LabelConverter.class,
        allowMultiple = true,
        defaultValue = "",
        category = "flags",
        help = "Declares this build's target environment. Must be a label reference to an "
            + "\"environment\" rule. If specified, all top-level targets must be "
            + "compatible with this environment."
    )
    public List<Label> targetEnvironments;

    @Option(name = "objc_gcov_binary",
        converter = LabelConverter.class,
        defaultValue = "//third_party/gcov:gcov_for_xcode",
        category = "undocumented")
    public Label objcGcovBinary;

    @Override
    public FragmentOptions getHost(boolean fallback) {
      Options host = (Options) getDefault();

      host.outputDirectoryName = "host";
      host.compilationMode = CompilationMode.OPT;
      host.isHost = true;

      if (fallback) {
        // In the fallback case, we have already tried the target options and they didn't work, so
        // now we try the default options; the hostCpu field has the default value, because we use
        // getDefault() above.
        host.cpu = host.hostCpu;
      } else {
        host.cpu = hostCpu;
      }

      // === Runfiles ===
      // Ideally we could force this the other way, and skip runfiles construction
      // for host tools which are never run locally, but that's probably a very
      // small optimization.
      host.buildRunfiles = true;

      // === Linkstamping ===
      // Disable all link stamping for the host configuration, to improve action
      // cache hit rates for tools.
      host.stampBinaries = false;

      // === Visibility ===
      host.checkVisibility = checkVisibility;

      // === Licenses ===
      host.checkLicenses = checkLicenses;

      // === Allow runtime_deps to depend on neverlink Java libraries.
      host.allowRuntimeDepsOnNeverLink = allowRuntimeDepsOnNeverLink;

      // === Pass on C++ compiler features.
      host.defaultFeatures = ImmutableList.copyOf(defaultFeatures);

      return host;
    }

    @Override
    public void addAllLabels(Multimap<String, Label> labelMap) {
      labelMap.putAll("action_listener", actionListeners);
      labelMap.putAll("plugins", pluginList);
      if ((runUnder != null) && (runUnder.getLabel() != null)) {
        labelMap.put("RunUnder", runUnder.getLabel());
      }
      if (collectCodeCoverage) {
        labelMap.put("objc_gcov", objcGcovBinary);
      }
    }
  }

  /**
   * All the output directories pertinent to a configuration.
   */
  private static final class OutputRoots implements Serializable {
    private final Root outputDirectory; // the configuration-specific output directory.
    private final Root binDirectory;
    private final Root genfilesDirectory;
    private final Root coverageMetadataDirectory; // for coverage-related metadata, artifacts, etc.
    private final Root testLogsDirectory;
    private final Root includeDirectory;
    private final Root middlemanDirectory;

    private OutputRoots(BlazeDirectories directories, String outputDirName) {
      Path execRoot = directories.getExecRoot();
      // configuration-specific output tree
      Path outputDir = directories.getOutputPath().getRelative(outputDirName);
      this.outputDirectory = Root.asDerivedRoot(execRoot, outputDir);

      // specific subdirs under outputDirectory
      this.binDirectory = Root.asDerivedRoot(execRoot, outputDir.getRelative("bin"));
      this.genfilesDirectory = Root.asDerivedRoot(execRoot, outputDir.getRelative("genfiles"));
      this.coverageMetadataDirectory = Root.asDerivedRoot(execRoot,
          outputDir.getRelative("coverage-metadata"));
      this.testLogsDirectory = Root.asDerivedRoot(execRoot, outputDir.getRelative("testlogs"));
      this.includeDirectory = Root.asDerivedRoot(execRoot,
          outputDir.getRelative(BlazeDirectories.RELATIVE_INCLUDE_DIR));
      this.middlemanDirectory = Root.middlemanRoot(execRoot, outputDir);
    }
  }

  /** A list of build configurations that only contains the null element. */
  private static final List<BuildConfiguration> NULL_LIST =
      Collections.unmodifiableList(Arrays.asList(new BuildConfiguration[] { null }));

  private final String checksum;

  private Transitions transitions;
  private Set<BuildConfiguration> allReachableConfigurations;

  private final ImmutableMap<Class<? extends Fragment>, Fragment> fragments;

  /**
   * Directories in the output tree.
   * 
   * <p>The computation of the output directory should be a non-injective mapping from
   * BuildConfiguration instances to strings. The result should identify the aspects of the
   * configuration that should be reflected in the output file names.  Furthermore the
   * returned string must not contain shell metacharacters.
   *
   * <p>For configuration settings which are NOT part of the output directory name,
   * rebuilding with a different value of such a setting will build in
   * the same output directory.  This means that any actions whose
   * keys (see Action.getKey()) have changed will be rerun.  That
   * may result in a lot of recompilation.
   *
   * <p>For configuration settings which ARE part of the output directory name,
   * rebuilding with a different value of such a setting will rebuild
   * in a different output directory; this will result in higher disk
   * usage and more work the <i>first</i> time you rebuild with a different
   * setting, but will result in less work if you regularly switch
   * back and forth between different settings.
   *
   * <p>With one important exception, it's sound to choose any subset of the
   * config's components for this string, it just alters the dimensionality
   * of the cache.  In other words, it's a trade-off on the "injectiveness"
   * scale: at one extreme (output directory name contains all data in the config, and is
   * thus injective) you get extremely precise caching (no competition for the
   * same output-file locations) but you have to rebuild for even the
   * slightest change in configuration.  At the other extreme (the output
   * (directory name is a constant) you have very high competition for
   * output-file locations, but if a slight change in configuration doesn't
   * affect a particular build step, you're guaranteed not to have to
   * rebuild it. The important exception has to do with multiple configurations: every
   * configuration in the build must have a different output directory name so that
   * their artifacts do not conflict.
   *
   * <p>The host configuration is special-cased: in order to guarantee that its output directory
   * is always separate from that of the target configuration, we simply pin it to "host". We do
   * this so that the build works even if the two configurations are too close (which is common)
   * and so that the path of artifacts in the host configuration is a bit more readable.
   */
  private final OutputRoots outputRoots;

  /** If false, AnalysisEnviroment doesn't register any actions created by the ConfiguredTarget. */
  private final boolean actionsEnabled;

  private final ImmutableSet<Label> coverageLabels;
  private final ImmutableSet<Label> coverageReportGeneratorLabels;

  // TODO(bazel-team): Move this to a configuration fragment.
  private final PathFragment shExecutable;

  /**
   * The global "make variables" such as "$(TARGET_CPU)"; these get applied to all rules analyzed in
   * this configuration.
   */
  private final ImmutableMap<String, String> globalMakeEnv;

  private final ImmutableMap<String, String> defaultShellEnvironment;
  private final BuildOptions buildOptions;
  private final Options options;

  private final String mnemonic;
  private final String platformName;

  private final ImmutableMap<String, String> testEnvironment;

  /**
   * Helper container for {@link #transitiveOptionsMap} below.
   */
  private static class OptionDetails implements Serializable {
    private OptionDetails(Class<? extends OptionsBase> optionsClass, Object value,
        boolean allowsMultiple) {
      this.optionsClass = optionsClass;
      this.value = value;
      this.allowsMultiple = allowsMultiple;
    }

    /** The {@link FragmentOptions} class that defines this option. */
    private final Class<? extends OptionsBase> optionsClass;

    /**
     * The value of the given option (either explicitly defined or default). May be null.
     */
    private final Object value;

    /** Whether or not this option supports multiple values. */
    private final boolean allowsMultiple;
  }

  /**
   * Maps option names to the {@link OptionDetails} the option takes for this configuration.
   *
   * <p>This can be used to:
   * <ol>
   *   <li>Find an option's (parsed) value given its command-line name</li>
   *   <li>Parse alternative values for the option.</li>
   * </ol>
   *
   * <p>This map is "transitive" in that it includes *all* options recognizable by this
   * configuration, including those defined in child fragments.
   */
  private final Map<String, OptionDetails> transitiveOptionsMap;


  /**
   * Validates the options for this BuildConfiguration. Issues warnings for the
   * use of deprecated options, and warnings or errors for any option settings
   * that conflict.
   */
  public void reportInvalidOptions(EventHandler reporter) {
    for (Fragment fragment : fragments.values()) {
      fragment.reportInvalidOptions(reporter, this.buildOptions);
    }

    Set<String> plugins = new HashSet<>();
    for (Label plugin : options.pluginList) {
      String name = plugin.getName();
      if (plugins.contains(name)) {
        reporter.handle(Event.error("A build cannot have two plugins with the same name"));
      }
      plugins.add(name);
    }
    for (Map.Entry<String, String> opt : options.pluginCoptList) {
      if (!plugins.contains(opt.getKey())) {
        reporter.handle(Event.error("A plugin_copt must refer to an existing plugin"));
      }
    }

    if (options.outputDirectoryName != null) {
      reporter.handle(Event.error(
          "The internal '--output directory name' option cannot be used on the command line"));
    }

    if (options.testShardingStrategy
        == TestActionBuilder.TestShardingStrategy.EXPERIMENTAL_HEURISTIC) {
      reporter.handle(Event.warn(
          "Heuristic sharding is intended as a one-off experimentation tool for determing the "
          + "benefit from sharding certain tests. Please don't keep this option in your "
          + ".blazerc or continuous build"));
    }
  }

  private ImmutableMap<String, String> setupShellEnvironment() {
    ImmutableMap.Builder<String, String> builder = new ImmutableMap.Builder<>();
    for (Fragment fragment : fragments.values()) {
      fragment.setupShellEnvironment(builder);
    }
    return builder.build();
  }

  public BuildConfiguration(BlazeDirectories directories,
                     Map<Class<? extends Fragment>, Fragment> fragmentsMap,
                     BuildOptions buildOptions,
                     boolean actionsDisabled) {
    this.actionsEnabled = !actionsDisabled;
    this.fragments = ImmutableMap.copyOf(fragmentsMap);

    this.buildOptions = buildOptions;
    this.options = buildOptions.get(Options.class);

    Map<String, String> testEnv = new TreeMap<>();
    for (Map.Entry<String, String> entry : this.options.testEnvironment) {
      if (entry.getValue() != null) {
        testEnv.put(entry.getKey(), entry.getValue());
      }
    }

    this.testEnvironment = ImmutableMap.copyOf(testEnv);

    this.mnemonic = buildMnemonic();
    String outputDirName = (options.outputDirectoryName != null)
        ? options.outputDirectoryName : mnemonic;
    this.platformName = buildPlatformName();

    this.shExecutable = collectExecutables().get("sh");

    this.outputRoots = new OutputRoots(directories, outputDirName);

    ImmutableSet.Builder<Label> coverageLabelsBuilder = ImmutableSet.builder();
    ImmutableSet.Builder<Label> coverageReportGeneratorLabelsBuilder = ImmutableSet.builder();
    for (Fragment fragment : fragments.values()) {
      coverageLabelsBuilder.addAll(fragment.getCoverageLabels());
      coverageReportGeneratorLabelsBuilder.addAll(fragment.getCoverageReportGeneratorLabels());
    }
    this.coverageLabels = coverageLabelsBuilder.build();
    this.coverageReportGeneratorLabels = coverageReportGeneratorLabelsBuilder.build();

    this.defaultShellEnvironment = setupShellEnvironment();

    this.transitiveOptionsMap = computeOptionsMap(buildOptions, fragments.values());

    ImmutableMap.Builder<String, String> globalMakeEnvBuilder = ImmutableMap.builder();
    for (Fragment fragment : fragments.values()) {
      fragment.addGlobalMakeVariables(globalMakeEnvBuilder);
    }

    // Lots of packages in third_party assume that BINMODE expands to either "-dbg", or "-opt". So
    // for backwards compatibility we preserve that invariant, setting BINMODE to "-dbg" rather than
    // "-fastbuild" if the compilation mode is "fastbuild".
    // We put the real compilation mode in a new variable COMPILATION_MODE.
    globalMakeEnvBuilder.put("COMPILATION_MODE", options.compilationMode.toString());
    globalMakeEnvBuilder.put("BINMODE", "-"
        + ((options.compilationMode == CompilationMode.FASTBUILD)
            ? "dbg"
            : options.compilationMode.toString()));
    /*
     * Attention! Document these in the build-encyclopedia
     */
    // the bin directory and the genfiles directory
    // These variables will be used on Windows as well, so we need to make sure
    // that paths use the correct system file-separator.
    globalMakeEnvBuilder.put("BINDIR", getBinDirectory().getExecPath().getPathString());
    globalMakeEnvBuilder.put("GENDIR", getGenfilesDirectory().getExecPath().getPathString());
    globalMakeEnv = globalMakeEnvBuilder.build();

    checksum = Fingerprint.md5Digest(buildOptions.computeCacheKey());
  }


  /**
   * Computes and returns the transitive optionName -> "option info" map for
   * this configuration.
   */
  private static Map<String, OptionDetails> computeOptionsMap(BuildOptions buildOptions,
      Iterable<Fragment> fragments) {
    // Collect from our fragments "alternative defaults" for options where the default
    // should be something other than what's specified in Option.defaultValue.
    Map<String, Object> lateBoundDefaults = Maps.newHashMap();
    for (Fragment fragment : fragments) {
      lateBoundDefaults.putAll(fragment.lateBoundOptionDefaults());
    }

    ImmutableMap.Builder<String, OptionDetails> map = ImmutableMap.builder();
    try {
      for (FragmentOptions options : buildOptions.getOptions()) {
        for (Field field : options.getClass().getFields()) {
          if (field.isAnnotationPresent(Option.class)) {
            Option option = field.getAnnotation(Option.class);
            Object value = field.get(options);
            if (value == null) {
              if (lateBoundDefaults.containsKey(option.name())) {
                value = lateBoundDefaults.get(option.name());
              } else if (!option.defaultValue().equals("null")) {
                 // See {@link Option#defaultValue} for an explanation of default "null" strings.
                value = option.defaultValue();
              }
            }
            map.put(option.name(),
                new OptionDetails(options.getClass(), value, option.allowMultiple()));
          }
        }
      }
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(
          "Unexpected illegal access trying to create this configuration's options map: ", e);
    }
    return map.build();
  }

  private String buildMnemonic() {
    // See explanation at getShortName().
    String platformSuffix = (options.platformSuffix != null) ? options.platformSuffix : "";
    ArrayList<String> nameParts = new ArrayList<>();
    for (Fragment fragment : fragments.values()) {
      nameParts.add(fragment.getOutputDirectoryName());
    }
    nameParts.add(getCompilationMode() + platformSuffix);
    return Joiner.on('-').skipNulls().join(nameParts);
  }

  private String buildPlatformName() {
    StringBuilder platformNameBuilder = new StringBuilder();
    for (Fragment fragment : fragments.values()) {
      platformNameBuilder.append(fragment.getPlatformName());
    }
    return platformNameBuilder.toString();
  }

  /**
   * Set the outgoing configuration transitions. During the lifetime of a given build configuration,
   * this must happen exactly once, shortly after the configuration is created.
   * TODO(bazel-team): this makes the object mutable, get rid of it.
   */
  public void setConfigurationTransitions(Transitions transitions) {
    Preconditions.checkNotNull(transitions);
    Preconditions.checkState(this.transitions == null);
    this.transitions = transitions;
  }

  public Transitions getTransitions() {
    return transitions;
  }

  /**
   * Returns all configurations that can be reached from this configuration through any kind of
   * configuration transition.
   */
  public synchronized Collection<BuildConfiguration> getAllReachableConfigurations() {
    if (allReachableConfigurations == null) {
      // This is needed for every configured target in skyframe m2, so we cache it.
      // We could alternatively make the corresponding dependencies into a skyframe node.
      this.allReachableConfigurations = computeAllReachableConfigurations();
    }
    return allReachableConfigurations;
  }

  /**
   * Returns all configurations that can be reached from this configuration through any kind of
   * configuration transition.
   */
  private Set<BuildConfiguration> computeAllReachableConfigurations() {
    Set<BuildConfiguration> result = new LinkedHashSet<>();
    Queue<BuildConfiguration> queue = new LinkedList<>();
    queue.add(this);
    while (!queue.isEmpty()) {
      BuildConfiguration config = queue.remove();
      if (!result.add(config)) {
        continue;
      }
      config.getTransitions().addDirectlyReachableConfigurations(queue);
    }
    return result;
  }

  /**
   * Returns the new configuration after traversing a dependency edge with a given configuration
   * transition.
   *
   * @param transition the configuration transition
   * @return the new configuration
   * @throws IllegalArgumentException if the transition is a {@link SplitTransition}
   */
  public BuildConfiguration getConfiguration(Transition transition) {
    Preconditions.checkArgument(!(transition instanceof SplitTransition));
    return transitions.getConfiguration(transition);
  }

  /**
   * Returns the new configurations after traversing a dependency edge with a given split
   * transition.
   *
   * @param transition the split configuration transition
   * @return the new configurations
   */
  public List<BuildConfiguration> getSplitConfigurations(SplitTransition<?> transition) {
    return transitions.getSplitConfigurations(transition);
  }

  /**
   * Calculates the configurations of a direct dependency. If a rule in some BUILD file refers
   * to a target (like another rule or a source file) using a label attribute, that target needs
   * to have a configuration, too. This method figures out the proper configuration for the
   * dependency.
   *
   * @param fromRule the rule that's depending on some target
   * @param attribute the attribute using which the rule depends on that target (eg. "srcs")
   * @param toTarget the target that's dependeded on
   * @return the configuration that should be associated to {@code toTarget}
   */
  public Iterable<BuildConfiguration> evaluateTransition(final Rule fromRule,
      final Attribute attribute, final Target toTarget) {
    // Fantastic configurations and where to find them:

    // I. Input files and package groups have no configurations. We don't want to duplicate them.
    if (toTarget instanceof InputFile || toTarget instanceof PackageGroup) {
      return NULL_LIST;
    }

    // II. Host configurations never switch to another. All prerequisites of host targets have the
    // same host configuration.
    if (isHostConfiguration()) {
      return ImmutableList.of(this);
    }

    // Make sure config_setting dependencies are resolved in the referencing rule's configuration,
    // unconditionally. For example, given:
    //
    // genrule(
    //     name = 'myrule',
    //     tools = select({ '//a:condition': [':sometool'] })
    //
    // all labels in "tools" get resolved in the host configuration (since the "tools" attribute
    // declares a host configuration transition). We want to explicitly exclude configuration labels
    // from these transitions, since their *purpose* is to do computation on the owning
    // rule's configuration.
    // TODO(bazel-team): implement this more elegantly. This is far too hackish. Specifically:
    // don't reference the rule name explicitly and don't require special-casing here.
    if (toTarget instanceof Rule && ((Rule) toTarget).getRuleClass().equals("config_setting")) {
      return ImmutableList.of(this);
    }

    List<BuildConfiguration> toConfigurations;
    if (attribute.getConfigurationTransition() instanceof SplitTransition) {
      Preconditions.checkState(attribute.getConfigurator() == null);
      toConfigurations = getSplitConfigurations(
          (SplitTransition<?>) attribute.getConfigurationTransition());
    } else {
      // III. Attributes determine configurations. The configuration of a prerequisite is determined
      // by the attribute.
      @SuppressWarnings("unchecked")
      Configurator<BuildConfiguration, Rule> configurator =
          (Configurator<BuildConfiguration, Rule>) attribute.getConfigurator();
      toConfigurations = ImmutableList.of((configurator != null)
          ? configurator.apply(fromRule, this, attribute, toTarget)
          : getConfiguration(attribute.getConfigurationTransition()));
    }

    return Iterables.transform(toConfigurations,
        new Function<BuildConfiguration, BuildConfiguration>() {
      @Override
      public BuildConfiguration apply(BuildConfiguration input) {
        // IV. Allow the transition object to perform an arbitrary switch. Blaze modules can inject
        // configuration transition logic by extending the Transitions class.
        BuildConfiguration actual = getTransitions().configurationHook(
            fromRule, attribute, toTarget, input);

        // V. Allow rule classes to override their own configurations.
        Rule associatedRule = toTarget.getAssociatedRule();
        if (associatedRule != null) {
          @SuppressWarnings("unchecked")
          RuleClass.Configurator<BuildConfiguration, Rule> func =
              associatedRule.getRuleClassObject().<BuildConfiguration, Rule>getConfigurator();
          actual = func.apply(associatedRule, actual);
        }

        return actual;
      }
    });
  }

  /**
   * Returns a multimap of all labels that should be implicitly loaded from labels that were
   * specified as options, keyed by the name to be displayed to the user if something goes wrong.
   * The returned set only contains labels that were derived from command-line options; the
   * intention is that it can be used to sanity-check that the command-line options actually contain
   * these in their transitive closure.
   */
  public ListMultimap<String, Label> getImplicitLabels() {
    ListMultimap<String, Label> implicitLabels = ArrayListMultimap.create();
    for (Fragment fragment : fragments.values()) {
      fragment.addImplicitLabels(implicitLabels);
    }
    return implicitLabels;
  }

  /**
   * For an given environment, returns a subset containing all
   * variables in the given list if they are defined in the given
   * environment.
   */
  @VisibleForTesting
  static Map<String, String> getMapping(List<String> variables,
                                        Map<String, String> environment) {
    Map<String, String> result = new HashMap<>();
    for (String var : variables) {
      if (environment.containsKey(var)) {
        result.put(var, environment.get(var));
      }
    }
    return result;
  }

  /**
   * Returns the {@link Option} class the defines the given option, null if the
   * option isn't recognized.
   *
   * <p>optionName is the name of the option as it appears on the command line
   * e.g. {@link Option#name}).
   */
  Class<? extends OptionsBase> getOptionClass(String optionName) {
    OptionDetails optionData = transitiveOptionsMap.get(optionName);
    return optionData == null ? null : optionData.optionsClass;
  }

  /**
   * Returns the value of the specified option for this configuration or null if the
   * option isn't recognized. Since an option's legitimate value could be null, use
   * {@link #getOptionClass} to distinguish between that and an unknown option.
   *
   * <p>optionName is the name of the option as it appears on the command line
   * e.g. {@link Option#name}).
   */
  Object getOptionValue(String optionName) {
    OptionDetails optionData = transitiveOptionsMap.get(optionName);
    return (optionData == null) ? null : optionData.value;
  }

  /**
   * Returns whether or not the given option supports multiple values at the command line (e.g.
   * "--myoption value1 --myOption value2 ..."). Returns false for unrecognized options. Use
   * {@link #getOptionClass} to distinguish between those and legitimate single-value options.
   *
   * <p>As declared in {@link Option#allowMultiple}, multi-value options are expected to be
   * of type {@code List<T>}.
   */
  boolean allowsMultipleValues(String optionName) {
    OptionDetails optionData = transitiveOptionsMap.get(optionName);
    return (optionData == null) ? false : optionData.allowsMultiple;
  }

  /**
   * The platform string, suitable for use as a key into a MakeEnvironment.
   */
  public String getPlatformName() {
    return platformName;
  }

  /**
   * Returns the output directory for this build configuration.
   */
  public Root getOutputDirectory() {
    return outputRoots.outputDirectory;
  }

  /**
   * Returns the bin directory for this build configuration.
   */
  @SkylarkCallable(name = "bin_dir", structField = true,
      doc = "The root corresponding to bin directory.")
  public Root getBinDirectory() {
    return outputRoots.binDirectory;
  }

  /**
   * Returns a relative path to the bin directory at execution time.
   */
  public PathFragment getBinFragment() {
    return getBinDirectory().getExecPath();
  }

  /**
   * Returns the include directory for this build configuration.
   */
  public Root getIncludeDirectory() {
    return outputRoots.includeDirectory;
  }

  /**
   * Returns the genfiles directory for this build configuration.
   */
  @SkylarkCallable(name = "genfiles_dir", structField = true,
      doc = "The root corresponding to genfiles directory.")
  public Root getGenfilesDirectory() {
    return outputRoots.genfilesDirectory;
  }

  /**
   * Returns the directory where coverage-related artifacts and metadata files
   * should be stored. This includes for example uninstrumented class files
   * needed for Jacoco's coverage reporting tools.
   */
  public Root getCoverageMetadataDirectory() {
    return outputRoots.coverageMetadataDirectory;
  }

  /**
   * Returns the testlogs directory for this build configuration.
   */
  public Root getTestLogsDirectory() {
    return outputRoots.testLogsDirectory;
  }

  /**
   * Returns a relative path to the genfiles directory at execution time.
   */
  public PathFragment getGenfilesFragment() {
    return getGenfilesDirectory().getExecPath();
  }

  /**
   * Returns the path separator for the host platform. This is basically the same as {@link
   * java.io.File#pathSeparator}, except that that returns the value for this JVM, which may or may
   * not match the host platform. You should only use this when invoking tools that are known to use
   * the native path separator, i.e., the path separator for the machine that they run on.
   */
  @SkylarkCallable(name = "host_path_separator", structField = true,
      doc = "Returns the separator for PATH environment variable, which is ':' on Unix.")
  public String getHostPathSeparator() {
    // TODO(bazel-team): This needs to change when we support Windows.
    return ":";
  }

  /**
   * Returns the internal directory (used for middlemen) for this build configuration.
   */
  public Root getMiddlemanDirectory() {
    return outputRoots.middlemanDirectory;
  }

  public boolean getAllowRuntimeDepsOnNeverLink() {
    return options.allowRuntimeDepsOnNeverLink;
  }

  public boolean isStrictFilesets() {
    return options.strictFilesets;
  }

  public List<Label> getPlugins() {
    return options.pluginList;
  }

  public List<Map.Entry<String, String>> getPluginCopts() {
    return options.pluginCoptList;
  }

  /**
   * Like getShortName(), but always returns a configuration-dependent string even for
   * the host configuration.
   */
  public String getMnemonic() {
    return mnemonic;
  }

  @Override
  public String toString() {
    return checksum();
  }

  /**
   * Returns the default shell environment
   */
  @SkylarkCallable(name = "default_shell_env", structField = true,
      doc = "A dictionary representing the default environment. It maps variables "
      + "to their values (strings).")
  public ImmutableMap<String, String> getDefaultShellEnvironment() {
    return defaultShellEnvironment;
  }

  /**
   * Returns the path to sh.
   */
  public PathFragment getShExecutable() {
    return shExecutable;
  }

  /**
   * Returns a regex-based instrumentation filter instance that used to match label
   * names to identify targets to be instrumented in the coverage mode.
   */
  public RegexFilter getInstrumentationFilter() {
    return options.instrumentationFilter;
  }

  /**
   * Returns the set of labels for coverage.
   */
  public Set<Label> getCoverageLabels() {
    return coverageLabels;
  }

  /**
   * Returns the set of labels for the coverage report generator.
   */
  public Set<Label> getCoverageReportGeneratorLabels() {
    return coverageReportGeneratorLabels;
  }

  /**
   * Returns true if bazel should show analyses results, even if it did not
   * re-run the analysis.
   */
  public boolean showCachedAnalysisResults() {
    return options.showCachedAnalysisResults;
  }

  /**
   * Returns a new, unordered mapping of names to values of "Make" variables defined by this
   * configuration.
   *
   * <p>This does *not* include package-defined overrides (e.g. vardef)
   * and so should not be used by the build logic.  This is used only for
   * the 'info' command.
   *
   * <p>Command-line definitions of make enviroments override variables defined by
   * {@code Fragment.addGlobalMakeVariables()}.
   */
  public Map<String, String> getMakeEnvironment() {
    Map<String, String> makeEnvironment = new HashMap<>();
    makeEnvironment.putAll(globalMakeEnv);
    for (Fragment fragment : fragments.values()) {
      makeEnvironment.putAll(fragment.getCommandLineDefines());
    }
    return ImmutableMap.copyOf(makeEnvironment);
  }

  /**
   * Returns a new, unordered mapping of names that are set through the command lines.
   * (Fragments, in particular the Google C++ support, can set variables through the
   * command line.)
   */
  public Map<String, String> getCommandLineDefines() {
    ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
    for (Fragment fragment : fragments.values()) {
      builder.putAll(fragment.getCommandLineDefines());
    }
    return builder.build();
  }

  /**
   * Returns the global defaults for this configuration for the Make environment.
   */
  public Map<String, String> getGlobalMakeEnvironment() {
    return globalMakeEnv;
  }

  /**
   * Returns a (key, value) mapping to insert into the subcommand environment for coverage
   * actions.
   */
  public Map<String, String> getCoverageEnvironment() {
    Map<String, String> env = new HashMap<>();
    for (Fragment fragment : fragments.values()) {
      env.putAll(fragment.getCoverageEnvironment());
    }
    return env;
  }

  /**
   * Returns the default value for the specified "Make" variable for this
   * configuration.  Returns null if no value was found.
   */
  public String getMakeVariableDefault(String var) {
    return globalMakeEnv.get(var);
  }

  /**
   * Returns a configuration fragment instances of the given class.
   */
  @SkylarkCallable(name = "fragment", documented = false,
      doc = "Returns a configuration fragment using the key.")
  public <T extends Fragment> T getFragment(Class<T> clazz) {
    return clazz.cast(fragments.get(clazz));
  }

  /**
   * Returns true if the requested configuration fragment is present.
   */
  public <T extends Fragment> boolean hasFragment(Class<T> clazz) {
    return getFragment(clazz) != null;
  }

  /**
   * Returns true if all requested configuration fragment are present (this may be slow).
   */
  public boolean hasAllFragments(Set<Class<?>> fragmentClasses) {
    for (Class<?> fragmentClass : fragmentClasses) {
      if (!hasFragment(fragmentClass.asSubclass(Fragment.class))) {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns true if non-functional build stamps are enabled.
   */
  public boolean stampBinaries() {
    return options.stampBinaries;
  }

  /**
   * Returns true if extended sanity checks should be enabled.
   */
  public boolean extendedSanityChecks() {
    return options.extendedSanityChecks;
  }

  /**
   * Returns true if we are building runfiles symlinks for this configuration.
   */
  public boolean buildRunfiles() {
    return options.buildRunfiles;
  }

  public boolean getCheckFilesetDependenciesRecursively() {
    return options.checkFilesetDependenciesRecursively;
  }

  public List<String> getTestArguments() {
    return options.testArguments;
  }

  public String getTestFilter() {
    return options.testFilter;
  }

  /**
   * Returns user-specified test environment variables and their values, as
   * set by the --test_env options.
   */
  public Map<String, String> getTestEnv() {
    return testEnvironment;
  }

  public TriState cacheTestResults() {
    return options.cacheTestResults;
  }

  public int getMinParamFileSize() {
    return options.minParamFileSize;
  }

  @SkylarkCallable(name = "coverage_enabled", structField = true,
      doc = "A boolean that tells whether code coverage is enabled.")
  public boolean isCodeCoverageEnabled() {
    return options.collectCodeCoverage;
  }

  public boolean isMicroCoverageEnabled() {
    return options.collectMicroCoverage;
  }

  public boolean isActionsEnabled() {
    return actionsEnabled;
  }

  public TestActionBuilder.TestShardingStrategy testShardingStrategy() {
    return options.testShardingStrategy;
  }

  /**
   * @return number of times the given test should run.
   * If the test doesn't match any of the filters, runs it once.
   */
  public int getRunsPerTestForLabel(Label label) {
    for (PerLabelOptions perLabelRuns : options.runsPerTest) {
      if (perLabelRuns.isIncluded(label)) {
        return Integer.parseInt(Iterables.getOnlyElement(perLabelRuns.getOptions()));
      }
    }
    return 1;
  }

  public RunUnder getRunUnder() {
    return options.runUnder;
  }

  /**
   * Returns true if this is a host configuration.
   */
  public boolean isHostConfiguration() {
    return options.isHost;
  }

  public boolean checkVisibility() {
    return options.checkVisibility;
  }

  public boolean checkLicenses() {
    return options.checkLicenses;
  }

  public boolean enforceConstraints() {
    return options.enforceConstraints;
  }

  public List<Label> getActionListeners() {
    return actionsEnabled ? options.actionListeners : ImmutableList.<Label>of();
  }

  /**
   * Returns compilation mode.
   */
  public CompilationMode getCompilationMode() {
    return options.compilationMode;
  }

  /** Returns the cache key of the build options used to create this configuration. */
  public final String checksum() {
    return checksum;
  }

  /** Returns a copy of the build configuration options for this configuration. */
  public BuildOptions cloneOptions() {
    return buildOptions.clone();
  }

  /**
   * Prepare the fdo support. It reads data into memory that is used during analysis. The analysis
   * phase is generally not allowed to perform disk I/O. This code is here because it is
   * conceptually part of the analysis phase, and it needs to happen when the loading phase is
   * complete.
   *
   * <p>C++ also requires this to resolve artifacts that are unconditionally included in every
   * compilation.</p>
   */
  public void prepareToBuild(Path execRoot, ArtifactFactory artifactFactory,
      PackageRootResolver resolver) throws ViewCreationFailedException {
    for (Fragment fragment : fragments.values()) {
      fragment.prepareHook(execRoot, artifactFactory, getGenfilesFragment(), resolver);
    }
  }

  /**
   * Declares dependencies on any relevant Skyframe values (for example, relevant FileValues).
   */
  public void declareSkyframeDependencies(SkyFunction.Environment env) {
    for (Fragment fragment : fragments.values()) {
      fragment.declareSkyframeDependencies(env);
    }
  }

  /**
   * Returns all the roots for this configuration.
   */
  public List<Root> getRoots() {
    List<Root> roots = new ArrayList<>();

    // Configuration-specific roots.
    roots.add(getBinDirectory());
    roots.add(getGenfilesDirectory());
    roots.add(getIncludeDirectory());
    roots.add(getMiddlemanDirectory());
    roots.add(getTestLogsDirectory());

    // Fragment-defined roots
    for (Fragment fragment : fragments.values()) {
      fragment.addRoots(roots);
    }

    return ImmutableList.copyOf(roots);
  }

  public ListMultimap<String, Label> getAllLabels() {
    return buildOptions.getAllLabels();
  }

  public String getCpu() {
    return options.cpu;
  }

  /**
   * Returns true is incremental builds are supported with this configuration.
   */
  public boolean supportsIncrementalBuild() {
    for (Fragment fragment : fragments.values()) {
      if (!fragment.supportsIncrementalBuild()) {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns true if the configuration performs static linking.
   */
  public boolean performsStaticLink() {
    for (Fragment fragment : fragments.values()) {
      if (fragment.performsStaticLink()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Deletes temporary directories before execution phase. This is only called for
   * target configuration.
   */
  public void prepareForExecutionPhase() throws IOException {
    for (Fragment fragment : fragments.values()) {
      fragment.prepareForExecutionPhase();
    }
  }

  /**
   * Collects executables defined by fragments.
   */
  private ImmutableMap<String, PathFragment> collectExecutables() {
    ImmutableMap.Builder<String, PathFragment> builder = new ImmutableMap.Builder<>();
    for (Fragment fragment : fragments.values()) {
      fragment.defineExecutables(builder);
    }
    return builder.build();
  }

  /**
   * See {@code BuildConfigurationCollection.Transitions.getArtifactOwnerConfiguration()}.
   */
  public BuildConfiguration getArtifactOwnerConfiguration() {
    return transitions.getArtifactOwnerConfiguration();
  }

  /**
   * @return whether proto header modules should be built.
   */
  public boolean getProtoHeaderModules() {
    return options.protoHeaderModules;
  }

  /**
   * @return the list of default features used for all packages.
   */
  public List<String> getDefaultFeatures() {
    return options.defaultFeatures;
  }

  /**
   * Returns the "top-level" environment space, i.e. the set of environments all top-level
   * targets must be compatible with. An empty value implies no restrictions.
   */
  public List<Label> getTargetEnvironments() {
    return options.targetEnvironments;
  }
}
