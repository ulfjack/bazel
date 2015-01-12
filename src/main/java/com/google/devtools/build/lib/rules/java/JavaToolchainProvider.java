// Copyright 2014 Google Inc. All rights reserved.
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
package com.google.devtools.build.lib.rules.java;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.devtools.build.lib.analysis.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.TransitiveInfoProvider;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;

import java.util.List;

/**
 * Information about the JDK used by the <code>java_*</code> rules.
 */
@Immutable
public final class JavaToolchainProvider implements TransitiveInfoProvider {
  private final String source; // javac -source flag value.
  private final String target; // javac -target flag value.
  private final String encoding; // javac -encoding flag value.
  private final ImmutableList<String> xlint; // suboptions of the javac -Xlint: flag.
  private final ImmutableList<String> misc; // additional miscellaneous javac flag.

  public JavaToolchainProvider(String source, String target, String encoding,
      ImmutableList<String> xlint, ImmutableList<String> misc) {
    super();
    this.source = source;
    this.target = target;
    this.encoding = encoding;
    this.xlint = xlint;
    this.misc = misc;
  }

  /**
   * @return the list of default options for the java compiler
   */
  public ImmutableList<String> getJavacOptions() {
    Builder<String> builder = ImmutableList.<String>builder();
    if (!source.isEmpty()) {
      builder.add("-source", source);
    }
    if (!target.isEmpty()) {
      builder.add("-target", target);
    }
    if (!encoding.isEmpty()) {
      builder.add("-encoding", encoding);
    }
    if (!xlint.isEmpty()) {
      builder.add("-Xlint:" + Joiner.on(",").join(xlint));
    }
    return builder.addAll(misc).build();
  }

  /**
   * An helper method to construct the list of javac options. It merges the defaultJavacFlags from
   * {@link JavaConfiguration} with the flags from the {@code java_toolchain} rule.
   *
   * @param ruleContext The rule context of the current rule.
   * @return the list of flags provided by the {@code java_toolchain} rule merged with the one
   *         provided by the {@link JavaConfiguration} fragment.
   */
  public static List<String> getDefaultJavacOptions(RuleContext ruleContext) {
    JavaConfiguration configuration = ruleContext.getFragment(JavaConfiguration.class);
    JavaToolchainProvider provider =  ruleContext.getPrerequisite(":java_toolchain", Mode.TARGET,
        JavaToolchainProvider.class);
    if (provider == null) {
      // support for :java_toolchain not being an actual java_toolchain rule for transition between
      // the old world and the new world.
      // TODO(bazel-team): Get rid of JAVABUILDER file and the getJavacOptionsFromXXX() functions.
      return configuration.getDefaultJavacFlags();
    } else {
      return  ImmutableList.<String>builder()
          .addAll(provider.getJavacOptions())
          .addAll(configuration.getJavacOptionsFromCommandLine()).build();
    }
  }
}
