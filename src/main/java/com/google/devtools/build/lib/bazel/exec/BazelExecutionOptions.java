package com.google.devtools.build.lib.bazel.exec;

import java.util.List;
import java.util.Map;

import com.google.devtools.common.options.Converters.AssignmentConverter;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionsBase;

/**
 * Execution options affecting how we execute the build actions (but not their semantics).
 */
public class BazelExecutionOptions extends OptionsBase {
  @Option(
    name = "spawn_strategy",
    defaultValue = "",
    category = "strategy",
    help =
        "Specify how spawn actions are executed by default."
            + "'standalone' means run all of them locally."
            + "'sandboxed' means run them in namespaces based sandbox (available only on Linux)"
  )
  public String spawnStrategy;

  @Option(
    name = "genrule_strategy",
    defaultValue = "",
    category = "strategy",
    help =
        "Specify how to execute genrules."
            + "'standalone' means run all of them locally."
            + "'sandboxed' means run them in namespaces based sandbox (available only on Linux)"
  )
  public String genruleStrategy;

  @Option(name = "strategy",
      allowMultiple = true,
      converter = AssignmentConverter.class,
      defaultValue = "",
      category = "strategy",
      help = "Specify how to distribute compilation of other spawn actions. "
          + "Example: 'Javac=local' means to spawn Java compilation locally. "
          + "'JavaIjar=sandboxed' means to spawn Java Ijar actions in a sandbox. ")
  public List<Map.Entry<String, String>> strategy;
}