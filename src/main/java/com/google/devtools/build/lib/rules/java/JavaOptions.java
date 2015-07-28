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
package com.google.devtools.build.lib.rules.java;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration.LabelConverter;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration.StrictDepsConverter;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration.StrictDepsMode;
import com.google.devtools.build.lib.analysis.config.DefaultsPackage;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.rules.java.JavaConfiguration.JavaClasspathMode;
import com.google.devtools.build.lib.syntax.Label;
import com.google.devtools.build.lib.syntax.Label.SyntaxException;
import com.google.devtools.common.options.Converters.StringSetConverter;
import com.google.devtools.common.options.EnumConverter;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.TriState;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Command-line options for building Java targets
 */
public class JavaOptions extends FragmentOptions {
  // Defaults value for options
  public static final String DEFAULT_LANGTOOLS = "//tools/jdk:langtools";
  static final String DEFAULT_LANGTOOLS_BOOTCLASSPATH = "//tools/jdk:bootclasspath";
  static final String DEFAULT_LANGTOOLS_EXTDIR = "//tools/jdk:extdir";
  static final String DEFAULT_JAVABUILDER = "//tools/jdk:JavaBuilder_deploy.jar";
  static final String DEFAULT_SINGLEJAR = "//tools/jdk:SingleJar_deploy.jar";
  static final String DEFAULT_GENCLASS = "//tools/jdk:GenClass_deploy.jar";
  static final String DEFAULT_JAVABASE = "//tools/jdk:jdk";
  static final String DEFAULT_IJAR = "//tools/jdk:ijar";
  static final String DEFAULT_TOOLCHAIN = "//tools/jdk:toolchain";

  /**
   * Converter for the --javawarn option.
   */
  public static class JavacWarnConverter extends StringSetConverter {
    public JavacWarnConverter() {
      super("all",
            "cast",
            "-cast",
            "deprecation",
            "-deprecation",
            "divzero",
            "-divzero",
            "empty",
            "-empty",
            "fallthrough",
            "-fallthrough",
            "finally",
            "-finally",
            "none",
            "options",
            "-options",
            "overrides",
            "-overrides",
            "path",
            "-path",
            "processing",
            "-processing",
            "rawtypes",
            "-rawtypes",
            "serial",
            "-serial",
            "unchecked",
            "-unchecked"
            );
    }
  }

  /**
   * Converter for the --experimental_java_classpath option.
   */
  public static class JavaClasspathModeConverter extends EnumConverter<JavaClasspathMode> {
    public JavaClasspathModeConverter() {
      super(JavaClasspathMode.class, "Java classpath reduction strategy");
    }
  }

  @Option(name = "javabase",
      defaultValue = DEFAULT_JAVABASE,
      category = "version",
      help = "JAVABASE used for the JDK invoked by Blaze. This is the "
          + "JAVABASE which will be used to execute external Java "
          + "commands.")
  public String javaBase;

  @Option(name = "java_toolchain",
      defaultValue = DEFAULT_TOOLCHAIN,
      category = "version",
      converter = LabelConverter.class,
      help = "The name of the toolchain rule for Java. Default is " + DEFAULT_TOOLCHAIN)
  public Label javaToolchain;

  @Option(name = "host_javabase",
    defaultValue = DEFAULT_JAVABASE,
    category = "version",
    help = "JAVABASE used for the host JDK. This is the JAVABASE which is used to execute "
         + " tools during a build.")
  public String hostJavaBase;

  @Option(name = "javacopt",
      allowMultiple = true,
      defaultValue = "",
      category = "flags",
      help = "Additional options to pass to javac.")
  public List<String> javacOpts;

  @Option(name = "jvmopt",
      allowMultiple = true,
      defaultValue = "",
      category = "flags",
      help = "Additional options to pass to the Java VM. These options will get added to the "
          + "VM startup options of each java_binary target.")
  public List<String> jvmOpts;

  @Option(name = "javawarn",
      converter = JavacWarnConverter.class,
      defaultValue = "",
      category = "flags",
      allowMultiple = true,
      help = "Additional javac warnings to enable when compiling Java source files.")
  public List<String> javaWarns;

  @Option(name = "use_ijars",
      defaultValue = "true",
      category = "strategy",
      help = "If enabled, this option causes Java compilation to use interface jars. "
          + "This will result in faster incremental compilation, "
          + "but error messages can be different.")
  public boolean useIjars;

  @Deprecated
  @Option(name = "use_src_ijars",
      defaultValue = "false",
      category = "undocumented",
      help = "No-op. Kept here for backwards compatibility.")
  public boolean useSourceIjars;

  @Deprecated
  @Option(name = "experimental_incremental_ijars",
      defaultValue = "false",
      category = "undocumented",
      help = "No-op. Kept here for backwards compatibility.")
  public boolean incrementalIjars;

  @Option(name = "java_deps",
      defaultValue = "true",
      category = "strategy",
      help = "Generate dependency information (for now, compile-time classpath) per Java target.")
  public boolean javaDeps;

  @Option(name = "experimental_java_deps",
      defaultValue = "false",
      category = "experimental",
      expansion = "--java_deps",
      deprecationWarning = "Use --java_deps instead")
  public boolean experimentalJavaDeps;

  @Option(name = "experimental_java_classpath",
      allowMultiple = false,
      defaultValue = "javabuilder",
      converter = JavaClasspathModeConverter.class,
      category = "semantics",
      help = "Enables reduced classpaths for Java compilations.")
  public JavaClasspathMode experimentalJavaClasspath;

  @Option(name = "java_debug",
      defaultValue = "null",
      category = "testing",
      expansion = {"--test_arg=--wrapper_script_flag=--debug", "--test_output=streamed",
                   "--test_strategy=exclusive", "--test_timeout=9999", "--nocache_test_results"},
      help = "Causes the Java virtual machine of a java test to wait for a connection from a "
      + "JDWP-compliant debugger (such as jdb) before starting the test. Implies "
      + "-test_output=streamed."
      )
  public Void javaTestDebug;

  @Option(name = "strict_java_deps",
      allowMultiple = false,
      defaultValue = "default",
      converter = StrictDepsConverter.class,
      category = "semantics",
      help = "If true, checks that a Java target explicitly declares all directly used "
          + "targets as dependencies.")
  public StrictDepsMode strictJavaDeps;

  @Option(name = "javabuilder_top",
      defaultValue = DEFAULT_JAVABUILDER,
      category = "version",
      converter = LabelConverter.class,
      help = "Label of the filegroup that contains the JavaBuilder jar.")
  public Label javaBuilderTop;

  @Option(name = "javabuilder_jvmopt",
      allowMultiple = true,
      defaultValue = "",
      category = "undocumented",
      help = "Additional options to pass to the JVM when invoking JavaBuilder.")
  public List<String> javaBuilderJvmOpts;

  @Option(name = "singlejar_top",
      defaultValue = DEFAULT_SINGLEJAR,
      category = "version",
      converter = LabelConverter.class,
      help = "Label of the filegroup that contains the SingleJar jar.")
  public Label singleJarTop;

  @Option(name = "genclass_top",
      defaultValue = DEFAULT_GENCLASS,
      category = "version",
      converter = LabelConverter.class,
      help = "Label of the filegroup that contains the GenClass jar.")
  public Label genClassTop;

  @Option(name = "ijar_top",
      defaultValue = DEFAULT_IJAR,
      category = "version",
      converter = LabelConverter.class,
      help = "Label of the filegroup that contains the ijar binary.")
  public Label iJarTop;

  @Option(name = "java_langtools",
      defaultValue = DEFAULT_LANGTOOLS,
      category = "version",
      converter = LabelConverter.class,
      help = "Label of the rule that produces the Java langtools jar.")
  public Label javaLangtoolsJar;

  @Option(name = "javac_bootclasspath",
      defaultValue = DEFAULT_LANGTOOLS_BOOTCLASSPATH,
      category = "version",
      converter = LabelConverter.class,
      help = "Label of the rule that produces the bootclasspath jars for javac to use.")
  public Label javacBootclasspath;

  @Option(name = "javac_extdir",
      defaultValue = DEFAULT_LANGTOOLS_EXTDIR,
      category = "version",
      converter = LabelConverter.class,
      help = "Label of the rule that produces the extdir for javac to use.")
  public Label javacExtdir;

  @Option(name = "java_launcher",
      defaultValue = "null",
      converter = LabelConverter.class,
      category = "semantics",
      help = "If enabled, a specific Java launcher is used. "
          + "The \"launcher\" attribute overrides this flag. ")
  public Label javaLauncher;

  @Option(name = "translations",
      defaultValue = "auto",
      category = "semantics",
      help = "Translate Java messages; bundle all translations into the jar "
          + "for each affected rule.")
  public TriState bundleTranslations;

  @Option(name = "message_translations",
      defaultValue = "",
      category = "semantics",
      allowMultiple = true,
      help = "The message translations used for translating messages in Java targets.")
  public List<String> translationTargets;

  @Option(name = "check_constraint",
      allowMultiple = true,
      defaultValue = "",
      category = "checking",
      help = "Check the listed constraint.")
  public List<String> checkedConstraints;

  @Override
  public FragmentOptions getHost(boolean fallback) {
    JavaOptions host = (JavaOptions) getDefault();

    host.javaBase = hostJavaBase;
    host.jvmOpts = ImmutableList.of("-client", "-XX:ErrorFile=/dev/stderr");

    host.javacOpts = javacOpts;
    host.javaLangtoolsJar = javaLangtoolsJar;
    host.javacExtdir = javacExtdir;
    host.javaBuilderTop = javaBuilderTop;
    host.javaToolchain = javaToolchain;
    host.singleJarTop = singleJarTop;
    host.genClassTop = genClassTop;
    host.iJarTop = iJarTop;

    // Java builds often contain complicated code generators for which
    // incremental build performance is important.
    host.useIjars = useIjars;

    host.javaDeps = javaDeps;
    host.experimentalJavaClasspath = experimentalJavaClasspath;

    return host;
  }

  @Override
  public void addAllLabels(Multimap<String, Label> labelMap) {
    addOptionalLabel(labelMap, "jdk", javaBase);
    addOptionalLabel(labelMap, "jdk", hostJavaBase);
    if (javaLauncher != null) {
      labelMap.put("java_launcher", javaLauncher);
    }
    labelMap.put("javabuilder", javaBuilderTop);
    labelMap.put("singlejar", singleJarTop);
    labelMap.put("genclass", genClassTop);
    labelMap.put("ijar", iJarTop);
    labelMap.put("java_toolchain", javaToolchain);
    labelMap.putAll("translation", getTranslationLabels());
  }

  @Override
  public Map<String, Set<Label>> getDefaultsLabels(BuildConfiguration.Options commonOptions) {
    Set<Label> jdkLabels = new LinkedHashSet<>();
    DefaultsPackage.parseAndAdd(jdkLabels, javaBase);
    DefaultsPackage.parseAndAdd(jdkLabels, hostJavaBase);
    Map<String, Set<Label>> result = new HashMap<>();
    result.put("JDK", jdkLabels);
    result.put("JAVA_LANGTOOLS", ImmutableSet.of(javaLangtoolsJar));
    result.put("JAVAC_BOOTCLASSPATH", ImmutableSet.of(javacBootclasspath));
    result.put("JAVAC_EXTDIR", ImmutableSet.of(javacExtdir));
    result.put("JAVABUILDER", ImmutableSet.of(javaBuilderTop));
    result.put("SINGLEJAR", ImmutableSet.of(singleJarTop));
    result.put("GENCLASS", ImmutableSet.of(genClassTop));
    result.put("IJAR", ImmutableSet.of(iJarTop));
    result.put("JAVA_TOOLCHAIN", ImmutableSet.of(javaToolchain));

    return result;
  }

  private Set<Label> getTranslationLabels() {
    Set<Label> result = new LinkedHashSet<>();
    for (String s : translationTargets) {
      try {
        Label label = Label.parseAbsolute(s);
        result.add(label);
      } catch (SyntaxException e) {
        // We ignore this exception here - it will cause an error message at a later time.
      }
    }
    return result;
  }
}
