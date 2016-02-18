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

package com.google.devtools.build.lib.skylark;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.actions.FileWriteAction;
import com.google.devtools.build.lib.analysis.actions.SpawnAction;
import com.google.devtools.build.lib.analysis.actions.TemplateExpansionAction;
import com.google.devtools.build.lib.analysis.actions.TemplateExpansionAction.Substitution;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.rules.SkylarkRuleContext;
import com.google.devtools.build.lib.skylark.util.SkylarkTestCase;
import com.google.devtools.build.lib.syntax.BuiltinFunction;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.EvalUtils;
import com.google.devtools.build.lib.syntax.Printer;
import com.google.devtools.build.lib.syntax.Runtime;
import com.google.devtools.build.lib.syntax.SkylarkList.MutableList;
import com.google.devtools.build.lib.syntax.SkylarkNestedSet;
import com.google.devtools.build.lib.syntax.SkylarkSignature;
import com.google.devtools.build.lib.syntax.SkylarkSignature.Param;
import com.google.devtools.build.lib.testutil.MoreAsserts;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Tests for SkylarkRuleImplementationFunctions.
 */
public class SkylarkRuleImplementationFunctionsTest extends SkylarkTestCase {

  @SkylarkSignature(
    name = "mock",
    documented = false,
    mandatoryPositionals = {@Param(name = "mandatory", doc = "")},
    optionalPositionals = {@Param(name = "optional", doc = "")},
    mandatoryNamedOnly = {@Param(name = "mandatory_key", doc = "")},
    optionalNamedOnly = {@Param(name = "optional_key", doc = "", defaultValue = "'x'")}
  )
  private BuiltinFunction mockFunc;

  /**
   * Used for {@link #testStackTraceWithoutOriginalMessage()} and {@link
   * #testNoStackTraceOnInterrupt}.
   */
  @SkylarkSignature(name = "throw", documented = false)
  BuiltinFunction throwFunction;

  @Override
  public void setUp() throws Exception {
    super.setUp();
    scratch.file(
        "foo/BUILD",
        "genrule(name = 'foo',",
        "  cmd = 'dummy_cmd',",
        "  srcs = ['a.txt', 'b.img'],",
        "  tools = ['t.exe'],",
        "  outs = ['c.txt'])",
        "genrule(name = 'bar',",
        "  cmd = 'dummy_cmd',",
        "  srcs = [':jl', ':gl'],",
        "  outs = ['d.txt'])",
        "genrule(name = 'baz',",
        "  cmd = 'dummy_cmd',",
        "  outs = ['e.txt'])",
        "java_library(name = 'jl',",
        "  srcs = ['a.java'])",
        "genrule(name = 'gl',",
        "  cmd = 'touch $(OUTS)',",
        "  srcs = ['a.go'],",
        "  outs = [ 'gl.a', 'gl.gcgox', ],",
        "  output_to_bindir = 1,",
        ")",
        // The two below are used by testResolveCommand
        "sh_binary(name = 'mytool',",
        "  srcs = ['mytool.sh'],",
        "  data = ['file1.dat', 'file2.dat'],",
        ")",
        "genrule(name = 'resolve_me',",
        "  cmd = 'aa',",
        "  tools = [':mytool', 't.exe'],",
        "  srcs = ['file3.dat', 'file4.dat'],",
        "  outs = ['r1.txt', 'r2.txt'],",
        ")");
  }

  private void setupSkylarkFunction(String line) throws Exception {
    mockFunc =
        new BuiltinFunction("mock") {
          @SuppressWarnings("unused")
          public Object invoke(
              Object mandatory, Object optional, Object mandatoryKey, Object optionalKey) {
            return EvalUtils.optionMap(
                "mandatory",
                mandatory,
                "optional",
                optional,
                "mandatory_key",
                mandatoryKey,
                "optional_key",
                optionalKey);
          }
        };
    assertFalse(mockFunc.isConfigured());
    mockFunc.configure(
        SkylarkRuleImplementationFunctionsTest.class
            .getDeclaredField("mockFunc")
            .getAnnotation(SkylarkSignature.class));
    update("mock", mockFunc);
    eval(line);
  }

  private void checkSkylarkFunctionError(String errorMsg, String line) throws Exception {
    try {
      setupSkylarkFunction(line);
      fail();
    } catch (EvalException e) {
      assertThat(e).hasMessage(errorMsg);
    }
  }

  public void testSkylarkFunctionPosArgs() throws Exception {
    setupSkylarkFunction("a = mock('a', 'b', mandatory_key='c')");
    Map<?, ?> params = (Map<?, ?>) lookup("a");
    assertEquals("a", params.get("mandatory"));
    assertEquals("b", params.get("optional"));
    assertEquals("c", params.get("mandatory_key"));
    assertEquals("x", params.get("optional_key"));
  }

  public void testSkylarkFunctionKwArgs() throws Exception {
    setupSkylarkFunction("a = mock(optional='b', mandatory='a', mandatory_key='c')");
    Map<?, ?> params = (Map<?, ?>) lookup("a");
    assertEquals("a", params.get("mandatory"));
    assertEquals("b", params.get("optional"));
    assertEquals("c", params.get("mandatory_key"));
    assertEquals("x", params.get("optional_key"));
  }

  public void testSkylarkFunctionTooFewArguments() throws Exception {
    checkSkylarkFunctionError(
        "insufficient arguments received by mock("
            + "mandatory, optional = None, *, mandatory_key, optional_key = \"x\") "
            + "(got 0, expected at least 1)",
        "mock()");
  }

  public void testSkylarkFunctionTooManyArguments() throws Exception {
    checkSkylarkFunctionError(
        "too many (3) positional arguments in call to "
            + "mock(mandatory, optional = None, *, mandatory_key, optional_key = \"x\")",
        "mock('a', 'b', 'c')");
  }

  public void testSkylarkFunctionAmbiguousArguments() throws Exception {
    checkSkylarkFunctionError(
        "argument 'mandatory' passed both by position and by name "
            + "in call to mock(mandatory, optional = None, *, mandatory_key, optional_key = \"x\")",
        "mock('by position', mandatory='by_key', mandatory_key='c')");
  }

  @SuppressWarnings("unchecked")
  public void testListComprehensionsWithNestedSet() throws Exception {
    Object result = eval("[x + x for x in set([1, 2, 3])]");
    assertThat((Iterable<Object>) result).containsExactly(2, 4, 6).inOrder();
  }

  public void testNestedSetGetsConvertedToSkylarkNestedSet() throws Exception {
    SkylarkRuleContext ruleContext = createRuleContext("//foo:foo");
    Object result =
        evalRuleContextCode(
            ruleContext,
            "dep = ruleContext.attr.tools[0]",
            "provider(dep, 'analysis.FileProvider').files_to_build");
    SkylarkNestedSet nset = (SkylarkNestedSet) result;
    assertEquals(Artifact.class, nset.getContentType().getType());
  }

  public void testCreateSpawnActionCreatesSpawnAction() throws Exception {
    SkylarkRuleContext ruleContext = createRuleContext("//foo:foo");
    createTestSpawnAction(ruleContext);
    Action action =
        Iterables.getOnlyElement(
            ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions());
    assertThat(action).isInstanceOf(SpawnAction.class);
  }

  public void testCreateSpawnActionArgumentsWithCommand() throws Exception {
    SkylarkRuleContext ruleContext = createRuleContext("//foo:foo");
    createTestSpawnAction(ruleContext);
    SpawnAction action =
        (SpawnAction)
            Iterables.getOnlyElement(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions());
    assertArtifactFilenames(action.getInputs(), "a.txt", "b.img");
    assertArtifactFilenames(action.getOutputs(), "a.txt", "b.img");
    MoreAsserts.assertContainsSublist(action.getArguments(), "-c", "dummy_command", "", "--a", "--b");
    assertEquals("DummyMnemonic", action.getMnemonic());
    assertEquals("dummy_message", action.getProgressMessage());
    assertEquals(targetConfig.getDefaultShellEnvironment(), action.getEnvironment());
  }

  public void testCreateSpawnActionArgumentsWithExecutable() throws Exception {
    SkylarkRuleContext ruleContext = createRuleContext("//foo:foo");
    evalRuleContextCode(
        ruleContext,
        "ruleContext.action(",
        "  inputs = ruleContext.files.srcs,",
        "  outputs = ruleContext.files.srcs,",
        "  arguments = ['--a','--b'],",
        "  executable = ruleContext.files.tools[0])");
    SpawnAction action =
        (SpawnAction)
            Iterables.getOnlyElement(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions());
    assertArtifactFilenames(action.getInputs(), "a.txt", "b.img", "t.exe");
    assertArtifactFilenames(action.getOutputs(), "a.txt", "b.img");
    MoreAsserts.assertContainsSublist(action.getArguments(), "foo/t.exe", "--a", "--b");
  }

  public void testCreateSpawnActionArgumentsBadExecutable() throws Exception {
    checkErrorContains(
        createRuleContext("//foo:foo"),
        "expected file or PathFragment for executable but got string instead",
        "ruleContext.action(",
        "  inputs = ruleContext.files.srcs,",
        "  outputs = ruleContext.files.srcs,",
        "  arguments = ['--a','--b'],",
        "  executable = 'xyz.exe')");
  }

  public void testCreateSpawnActionShellCommandList() throws Exception {
    SkylarkRuleContext ruleContext = createRuleContext("//foo:foo");
    evalRuleContextCode(
        ruleContext,
        "ruleContext.action(",
        "  inputs = ruleContext.files.srcs,",
        "  outputs = ruleContext.files.srcs,",
        "  mnemonic = 'DummyMnemonic',",
        "  command = ['dummy_command', '--arg1', '--arg2'],",
        "  progress_message = 'dummy_message')");
    SpawnAction action =
        (SpawnAction)
            Iterables.getOnlyElement(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions());
    assertThat(action.getArguments())
        .containsExactly("dummy_command", "--arg1", "--arg2")
        .inOrder();
  }

  public void testCreateSpawnActionEnvAndExecInfo() throws Exception {
    SkylarkRuleContext ruleContext = createRuleContext("//foo:foo");
    evalRuleContextCode(
        ruleContext,
        "env = {'a' : 'b'}",
        "ruleContext.action(",
        "  inputs = ruleContext.files.srcs,",
        "  outputs = ruleContext.files.srcs,",
        "  env = env,",
        "  execution_requirements = env,",
        "  mnemonic = 'DummyMnemonic',",
        "  command = 'dummy_command',",
        "  progress_message = 'dummy_message')");
    SpawnAction action =
        (SpawnAction)
            Iterables.getOnlyElement(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions());
    assertEquals(ImmutableMap.of("a", "b"), action.getEnvironment());
    assertEquals(ImmutableMap.of("a", "b"), action.getExecutionInfo());
  }

  public void testCreateSpawnActionUnknownParam() throws Exception {
    SkylarkRuleContext ruleContext = createRuleContext("//foo:foo");
    checkErrorContains(
        ruleContext,
        "unexpected keyword 'bad_param' in call to action(self: ctx, *, ",
        "ruleContext.action(outputs=[], bad_param = 'some text')");
  }

  public void testCreateSpawnActionNoExecutable() throws Exception {
    SkylarkRuleContext ruleContext = createRuleContext("//foo:foo");
    checkErrorContains(
        ruleContext,
        "You must specify either 'command' or 'executable' argument",
        "ruleContext.action(outputs=[])");
  }

  private Object createTestSpawnAction(SkylarkRuleContext ruleContext) throws Exception {
    return evalRuleContextCode(
        ruleContext,
        "ruleContext.action(",
        "  inputs = ruleContext.files.srcs,",
        "  outputs = ruleContext.files.srcs,",
        "  arguments = ['--a','--b'],",
        "  mnemonic = 'DummyMnemonic',",
        "  command = 'dummy_command',",
        "  progress_message = 'dummy_message',",
        "  use_default_shell_env = True)");
  }

  public void testCreateSpawnActionBadGenericArg() throws Exception {
    checkErrorContains(
        createRuleContext("//foo:foo"),
        "Illegal argument: expected type File for 'outputs' element but got type string instead",
        "l = ['a', 'b']",
        "ruleContext.action(",
        "  outputs = l,",
        "  command = 'dummy_command')");
  }

  public void testCreateSpawnActionCommandsListTooShort() throws Exception {
    checkErrorContains(
        createRuleContext("//foo:foo"),
        "'command' list has to be of size at least 3",
        "ruleContext.action(",
        "  outputs = ruleContext.files.srcs,",
        "  command = ['dummy_command', '--arg'])");
  }

  public void testCreateFileAction() throws Exception {
    SkylarkRuleContext ruleContext = createRuleContext("//foo:foo");
    FileWriteAction action =
        (FileWriteAction)
            evalRuleContextCode(
                ruleContext,
                "ruleContext.file_action(",
                "  output = ruleContext.files.srcs[0],",
                "  content = 'hello world',",
                "  executable = False)");
    assertEquals("foo/a.txt", Iterables.getOnlyElement(action.getOutputs()).getExecPathString());
    assertEquals("hello world", action.getFileContents());
    assertFalse(action.makeExecutable());
  }

  public void testEmptyAction() throws Exception {
    SkylarkRuleContext ruleContext = createRuleContext("//foo:foo");

    checkEmptyAction(ruleContext, "mnemonic = 'test'");
    checkEmptyAction(ruleContext, "mnemonic = 'test', inputs = ruleContext.files.srcs");

    checkErrorContains(
        ruleContext,
        "missing mandatory named-only argument 'mnemonic' while calling empty_action",
        "ruleContext.empty_action(inputs = ruleContext.files.srcs)");
  }

  private void checkEmptyAction(SkylarkRuleContext ruleContext, String namedArgs) throws Exception {
    assertThat(
            evalRuleContextCode(
                ruleContext, String.format("ruleContext.empty_action(%s)", namedArgs)))
        .isEqualTo(Runtime.NONE);
  }

  public void testEmptyActionWithExtraAction() throws Exception {
    scratch.file(
        "test/empty.bzl",
        "def _impl(ctx):",
        "  ctx.empty_action(",
        "      inputs = ctx.files.srcs,",
        "      mnemonic = 'EA',",
        "  )",

        "empty_action_rule = rule(",
        "    implementation = _impl,",
        "    attrs = {",
        "       \"srcs\": attr.label_list(allow_files=True),",
        "    }",
        ")");

    scratch.file(
        "test/BUILD",
        "load('/test/empty', 'empty_action_rule')",
        "empty_action_rule(name = 'my_empty_action',",
        "                srcs = ['foo.in', 'other_foo.in'])",

        "action_listener(name = 'listener',",
        "                mnemonics = ['EA'],",
        "                extra_actions = [':extra'])",

        "extra_action(name = 'extra',",
        "             cmd='')");

    getPseudoActionViaExtraAction("//test:my_empty_action", "//test:listener");
  }

  public void testExpandLocation() throws Exception {
    SkylarkRuleContext ruleContext = createRuleContext("//foo:bar");

    // If there is only a single target, both "location" and "locations" should work
    runExpansion(ruleContext, "location :jl", "[blaze]*-out/.*/bin/foo/libjl.jar");
    runExpansion(ruleContext, "locations :jl", "[blaze]*-out/.*/bin/foo/libjl.jar");

    runExpansion(ruleContext, "location //foo:jl", "[blaze]*-out/.*/bin/foo/libjl.jar");

    // Multiple targets and "location" should result in an error
    checkReportedErrorStartsWith(
        ruleContext,
        "in genrule rule //foo:bar: label '//foo:gl' "
            + "in $(location) expression expands to more than one file, please use $(locations "
            + "//foo:gl) instead.",
        "ruleContext.expand_location('$(location :gl)')");

    // We have to use "locations" for multiple targets
    runExpansion(
        ruleContext,
        "locations :gl",
        "[blaze]*-out/.*/bin/foo/gl.a [blaze]*-out/.*/bin/foo/gl.gcgox");

    // LocationExpander just returns the input string if there is no label
    runExpansion(ruleContext, "location", "\\$\\(location\\)");

    checkReportedErrorStartsWith(
        ruleContext,
        "in genrule rule //foo:bar: label '//foo:abc' in $(locations) expression "
            + "is not a declared prerequisite of this rule",
        "ruleContext.expand_location('$(locations :abc)')");
  }

  /**
   * Invokes ctx.expand_location() with the given parameters and checks whether this led to the
   * expected result
   * @param ruleContext The rule context
   * @param command Either "location" or "locations". This only matters when the label has multiple
   * targets
   * @param expectedPattern Regex pattern that matches the expected result
   */
  private void runExpansion(SkylarkRuleContext ruleContext, String command, String expectedPattern)
      throws Exception {
    assertMatches(
        "Expanded string",
        expectedPattern,
        (String) evalRuleContextCode(
            ruleContext, String.format("ruleContext.expand_location('$(%s)')", command)));
  }

  private void assertMatches(String description, String expectedPattern, String computedValue)
      throws Exception {
    assertTrue(
        Printer.format("%s %r did not match pattern '%s'",
            description, computedValue, expectedPattern),
        Pattern.matches(expectedPattern, computedValue));
  }

  public void testResolveCommandMakeVariables() throws Exception {
    evalRuleContextCode(
        createRuleContext("//foo:resolve_me"),
        "inputs, argv, manifests = ruleContext.resolve_command(",
        "  command='I got the $(HELLO) on a $(DAVE)', ",
        "  make_variables={'HELLO': 'World', 'DAVE': type('')})");
    @SuppressWarnings("unchecked")
    List<String> argv = (List<String>) (List<?>) ((MutableList) lookup("argv")).getList();
    assertThat(argv).hasSize(3);
    assertMatches("argv[0]", "^.*/bash$", argv.get(0));
    assertThat(argv.get(1)).isEqualTo("-c");
    assertThat(argv.get(2)).isEqualTo("I got the World on a string");
  }

  public void testResolveCommandInputs() throws Exception {
    evalRuleContextCode(
        createRuleContext("//foo:resolve_me"),
        "inputs, argv, manifests = ruleContext.resolve_command(",
        "   tools=ruleContext.attr.tools)");
    @SuppressWarnings("unchecked")
    List<Artifact> inputs = (List<Artifact>) (List<?>) ((MutableList) lookup("inputs")).getList();
    assertArtifactFilenames(inputs, "mytool.sh", "mytool", "foo_Smytool-runfiles", "t.exe");
    Map<?, ?> manifests = (Map<?, ?>) lookup("manifests");
    assertThat(manifests).hasSize(1);
  }

  public void testResolveCommandExpandLocations() throws Exception {
    evalRuleContextCode(
        createRuleContext("//foo:resolve_me"),
        "def foo():", // no for loops at top-level
        "  label_dict = {}",
        "  all = []",
        "  for dep in ruleContext.attr.srcs + ruleContext.attr.tools:",
        "    all.extend(list(dep.files))",
        "    label_dict[dep.label] = list(dep.files)",
        "  return ruleContext.resolve_command(",
        "    command='A$(locations //foo:mytool) B$(location //foo:file3.dat)',",
        "    attribute='cmd', expand_locations=True, label_dict=label_dict)",
        "inputs, argv, manifests = foo()");
    @SuppressWarnings("unchecked")
    List<String> argv = (List<String>) (List<?>) ((MutableList) lookup("argv")).getList();
    assertThat(argv).hasSize(3);
    assertMatches("argv[0]", "^.*/bash$", argv.get(0));
    assertThat(argv.get(1)).isEqualTo("-c");
    assertMatches("argv[2]", "A.*/mytool .*/mytool.sh B.*file3.dat", argv.get(2));
  }

  public void testResolveCommandScript() throws Exception {
    evalRuleContextCode(
        createRuleContext("//foo:resolve_me"),
        "def foo():", // no for loops at top-level
        "  s = 'a'",
        "  for i in range(1,17): s = s + s", // 2**17 > CommandHelper.maxCommandLength (=64000)
        "  return ruleContext.resolve_command(",
        "    command=s)",
        "argv = foo()[1]");
    @SuppressWarnings("unchecked")
    List<String> argv = (List<String>) (List<?>) ((MutableList) lookup("argv")).getList();
    assertThat(argv).hasSize(2);
    assertMatches("argv[0]", "^.*/bash$", argv.get(0));
    assertMatches("argv[1]", "^.*/resolve_me[.]script[.]sh$", argv.get(1));
  }

  public void testBadParamTypeErrorMessage() throws Exception {
    SkylarkRuleContext ruleContext = createRuleContext("//foo:foo");
    checkErrorContains(
        ruleContext,
        "Method ctx.file_action(output: File, content: string, executable: bool) is not "
            + "applicable for arguments (File, int, bool): 'content' is int, but should be string",
        "ruleContext.file_action(",
        "  output = ruleContext.files.srcs[0],",
        "  content = 1,",
        "  executable = False)");
  }

  public void testCreateTemplateAction() throws Exception {
    SkylarkRuleContext ruleContext = createRuleContext("//foo:foo");
    TemplateExpansionAction action =
        (TemplateExpansionAction)
            evalRuleContextCode(
                ruleContext,
                "ruleContext.template_action(",
                "  template = ruleContext.files.srcs[0],",
                "  output = ruleContext.files.srcs[1],",
                "  substitutions = {'a': 'b'},",
                "  executable = False)");
    assertEquals("foo/a.txt", Iterables.getOnlyElement(action.getInputs()).getExecPathString());
    assertEquals("foo/b.img", Iterables.getOnlyElement(action.getOutputs()).getExecPathString());
    assertEquals("a", Iterables.getOnlyElement(action.getSubstitutions()).getKey());
    assertEquals("b", Iterables.getOnlyElement(action.getSubstitutions()).getValue());
    assertFalse(action.makeExecutable());
  }

  /**
   * Simulates the fact that the Parser currently uses Latin1 to read BUILD files, while users
   * usually write those files using UTF-8 encoding.
   * Once {@link
   * com.google.devtools.build.lib.syntax.ParserInputSource#create(com.google.devtools.build.lib.vfs.Path)} parses files using UTF-8, this test will fail.
   */
  public void testCreateTemplateActionWithWrongEncoding() throws Exception {
    String value = "Š©±½";
    SkylarkRuleContext ruleContext = createRuleContext("//foo:foo");
    TemplateExpansionAction action =
        (TemplateExpansionAction)
            evalRuleContextCode(
                ruleContext,
                "ruleContext.template_action(",
                "  template = ruleContext.files.srcs[0],",
                "  output = ruleContext.files.srcs[1],",
                "  substitutions = {'a': '" + convertUtf8ToLatin1(value) + "'},",
                "  executable = False)");

    List<Substitution> substitutions = action.getSubstitutions();
    assertThat(substitutions).hasSize(1);
    assertThat(substitutions.get(0).getValue()).isEqualTo(value);
  }

  /**
   * Turns the given UTF-8 input into an "unreadable" Latin1 string
   */
  private String convertUtf8ToLatin1(String input) {
    return new String(input.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
  }

  public void testGetProviderNotTransitiveInfoCollection() throws Exception {
    checkErrorContains(
        createRuleContext("//foo:foo"),
        "Method provider(target: Target, type: string) is not applicable for arguments "
            + "(string, string): 'target' is string, but should be Target",
        "provider('some string', 'FileProvider')");
  }

  public void testGetProviderNonExistingClassType() throws Exception {
    checkErrorContains(
        createRuleContext("//foo:foo"),
        "Unknown class type bad.Bad",
        "def func():", // we need a func to hold the for loop
        "  for tic in ruleContext.attr.srcs:",
        "    provider(tic, 'bad.Bad')",
        "func()");
  }

  public void testGetProviderNotTransitiveInfoProviderClassType() throws Exception {
    checkErrorContains(
        createRuleContext("//foo:foo"),
        "Not a TransitiveInfoProvider rules.java.JavaBinary",
        "def func():", // we need a func to hold the for loop
        "  for tic in ruleContext.attr.srcs:",
        "    provider(tic, 'rules.java.JavaBinary')",
        "func()");
  }

  public void testRunfilesAddFromDependencies() throws Exception {
    SkylarkRuleContext ruleContext = createRuleContext("//foo:bar");
    Object result =
        evalRuleContextCode(ruleContext, "ruleContext.runfiles(collect_default = True)");
    assertThat(ActionsTestUtil.baseArtifactNames(getRunfileArtifacts(result)))
        .contains("libjl.jar");
  }

  public void testRunfilesStatelessWorksAsOnlyPosArg() throws Exception {
    SkylarkRuleContext ruleContext = createRuleContext("//foo:bar");
    Object result =
        evalRuleContextCode(ruleContext, "ruleContext.runfiles(collect_default = True)");
    assertThat(ActionsTestUtil.baseArtifactNames(getRunfileArtifacts(result)))
        .contains("libjl.jar");
  }

  public void testRunfilesBadListGenericType() throws Exception {
    checkErrorContains(
        "Illegal argument: expected type File for 'files' element but got type string instead",
        "ruleContext.runfiles(files = ['some string'])");
  }

  public void testRunfilesBadSetGenericType() throws Exception {
    checkErrorContains(
        "expected set of Files or NoneType for 'transitive_files' while calling runfiles "
            + "but got set of ints instead: set([1, 2, 3])",
        "ruleContext.runfiles(transitive_files=set([1, 2, 3]))");
  }

  public void testRunfilesArtifactsFromArtifact() throws Exception {
    SkylarkRuleContext ruleContext = createRuleContext("//foo:foo");
    Object result =
        evalRuleContextCode(
            ruleContext,
            "artifacts = ruleContext.files.tools",
            "ruleContext.runfiles(files = artifacts)");
    assertThat(ActionsTestUtil.baseArtifactNames(getRunfileArtifacts(result))).contains("t.exe");
  }

  public void testRunfilesArtifactsFromIterableArtifacts() throws Exception {
    SkylarkRuleContext ruleContext = createRuleContext("//foo:foo");
    Object result =
        evalRuleContextCode(
            ruleContext,
            "artifacts = ruleContext.files.srcs",
            "ruleContext.runfiles(files = artifacts)");
    assertEquals(
        ActionsTestUtil.baseArtifactNames(getRunfileArtifacts(result)),
        ImmutableList.of("a.txt", "b.img"));
  }

  public void testRunfilesArtifactsFromNestedSetArtifacts() throws Exception {
    SkylarkRuleContext ruleContext = createRuleContext("//foo:foo");
    Object result =
        evalRuleContextCode(
            ruleContext,
            "ftb = set() + ruleContext.files.srcs",
            "ruleContext.runfiles(transitive_files = ftb)");
    assertEquals(
        ActionsTestUtil.baseArtifactNames(getRunfileArtifacts(result)),
        ImmutableList.of("a.txt", "b.img"));
  }

  public void testRunfilesArtifactsFromDefaultAndFiles() throws Exception {
    SkylarkRuleContext ruleContext = createRuleContext("//foo:bar");
    Object result =
        evalRuleContextCode(
            ruleContext,
            "artifacts = ruleContext.files.srcs",
            // It would be nice to write [DEFAULT] + artifacts, but artifacts
            // is an ImmutableList and Skylark interprets it as a tuple.
            "ruleContext.runfiles(collect_default = True, files = artifacts)");
    // From DEFAULT only libjl.jar comes, see testRunfilesAddFromDependencies().
    assertEquals(
        ActionsTestUtil.baseArtifactNames(getRunfileArtifacts(result)),
        ImmutableList.of("libjl.jar", "gl.a", "gl.gcgox"));
  }

  private Iterable<Artifact> getRunfileArtifacts(Object runfiles) {
    return ((Runfiles) runfiles).getAllArtifacts();
  }

  public void testRunfilesBadKeywordArguments() throws Exception {
    SkylarkRuleContext ruleContext = createRuleContext("//foo:foo");
    checkErrorContains(
        ruleContext,
        "unexpected keyword 'bad_keyword' in call to runfiles(self: ctx, ",
        "ruleContext.runfiles(bad_keyword = '')");
  }

  public void testNsetContainsList() throws Exception {
    checkErrorContains(
        "sets cannot contain items of type 'list'", "set() + [ruleContext.files.srcs]");
  }

  public void testCmdJoinPaths() throws Exception {
    SkylarkRuleContext ruleContext = createRuleContext("//foo:foo");
    Object result =
        evalRuleContextCode(
            ruleContext, "f = set(ruleContext.files.srcs)", "cmd_helper.join_paths(':', f)");
    assertEquals("foo/a.txt:foo/b.img", result);
  }

  public void testStructPlusArtifactErrorMessage() throws Exception {
    SkylarkRuleContext ruleContext = createRuleContext("//foo:foo");
    checkErrorContains(
        ruleContext,
        "unsupported operand type(s) for +: 'File' and 'struct'",
        "ruleContext.files.tools[0] + struct(a = 1)");
  }

  public void testNoSuchProviderErrorMessage() throws Exception {
    checkErrorContains(
        createRuleContext("//foo:bar"),
        "target (rule class of 'java_library') " + "doesn't have provider 'my_provider'.",
        "ruleContext.attr.srcs[0].my_provider");
  }

  public void testFilesForRuleConfiguredTarget() throws Exception {
    Object result =
        evalRuleContextCode(createRuleContext("//foo:foo"), "ruleContext.attr.srcs[0].files");
    assertEquals(
        "a.txt", ActionsTestUtil.baseNamesOf(((SkylarkNestedSet) result).getSet(Artifact.class)));
  }

  public void testFilesForFileConfiguredTarget() throws Exception {
    Object result =
        evalRuleContextCode(createRuleContext("//foo:bar"), "ruleContext.attr.srcs[0].files");
    assertEquals(
        "libjl.jar",
        ActionsTestUtil.baseNamesOf(((SkylarkNestedSet) result).getSet(Artifact.class)));
  }

  public void testCtxStructFieldsCustomErrorMessages() throws Exception {
    checkErrorContains("No attribute 'foo' in attr.", "ruleContext.attr.foo");
    checkErrorContains("No attribute 'foo' in outputs.", "ruleContext.outputs.foo");
    checkErrorContains("No attribute 'foo' in files.", "ruleContext.files.foo");
    checkErrorContains("No attribute 'foo' in file.", "ruleContext.file.foo");
    checkErrorContains("No attribute 'foo' in executable.", "ruleContext.executable.foo");
  }

  public void testBinDirPath() throws Exception {
    SkylarkRuleContext ctx = createRuleContext("//foo:bar");
    Object result = evalRuleContextCode(ctx, "ruleContext.configuration.bin_dir.path");
    assertEquals(ctx.getConfiguration().getBinFragment().getPathString(), result);
  }

  public void testEmptyLabelListTypeAttrInCtx() throws Exception {
    SkylarkRuleContext ctx = createRuleContext("//foo:baz");
    Object result = evalRuleContextCode(ctx, "ruleContext.attr.srcs");
    assertEquals(MutableList.EMPTY, result);
  }

  public void testDefinedMakeVariable() throws Exception {
    SkylarkRuleContext ctx = createRuleContext("//foo:baz");
    String javac = (String) evalRuleContextCode(ctx, "ruleContext.var['JAVAC']");
    // Get the last path segment
    javac = javac.substring(javac.lastIndexOf('/'));
    assertEquals("/javac", javac);
  }

  public void testCodeCoverageConfigurationAccess() throws Exception {
    SkylarkRuleContext ctx = createRuleContext("//foo:baz");
    boolean coverage =
        (Boolean) evalRuleContextCode(ctx, "ruleContext.configuration.coverage_enabled");
    assertEquals(coverage, ctx.getRuleContext().getConfiguration().isCodeCoverageEnabled());
  }

  @Override
  protected void checkErrorContains(String errorMsg, String... lines) throws Exception {
    super.checkErrorContains(createRuleContext("//foo:foo"), errorMsg, lines);
  }

  /**
   * Checks whether the given (invalid) statement leads to the expected error
   */
  private void checkReportedErrorStartsWith(
      SkylarkRuleContext ruleContext, String errorMsg, String... statements) throws Exception {
    // If the component under test relies on Reporter and EventCollector for error handling, any
    // error would lead to an asynchronous AssertionFailedError thanks to failFastHandler in
    // FoundationTestCase.
    //
    // Consequently, we disable failFastHandler and check all events for the expected error message
    reporter.removeHandler(failFastHandler);

    Object result = evalRuleContextCode(ruleContext, statements);

    String first = null;
    int count = 0;

    try {
      for (Event evt : eventCollector) {
        if (evt.getMessage().startsWith(errorMsg)) {
          return;
        }

        ++count;
        first = evt.getMessage();
      }

      if (count == 0) {
        fail(
            String.format(
                "checkReportedErrorStartsWith(): There was no error; the result is '%s'", result));
      } else {
        fail(
            String.format(
                "Found %d error(s), but none with the expected message '%s'. First error: '%s'",
                count,
                errorMsg,
                first));
      }
    } finally {
      eventCollector.clear();
    }
  }

  public void testStackTraceWithoutOriginalMessage() throws Exception {
    setupThrowFunction(
        new BuiltinFunction("throw") {
          @SuppressWarnings("unused")
          public Object invoke() throws Exception {
            throw new ThereIsNoMessageException();
          }
        });

    checkEvalErrorContains(
        "There Is No Message: SkylarkRuleImplementationFunctionsTest$2.invoke() in "
            + "SkylarkRuleImplementationFunctionsTest.java:",
        // This test skips the line number since it was not consistent across local tests and TAP.
        "throw()");
  }

  public void testNoStackTraceOnInterrupt() throws Exception {
    setupThrowFunction(
        new BuiltinFunction("throw") {
          @SuppressWarnings("unused")
          public Object invoke() throws Exception {
            throw new InterruptedException();
          }
        });
    try {
      eval("throw()");
      fail("Expected an InterruptedException");
    } catch (InterruptedException ex) {
      // Expected.
    }
  }

  public void testGlobInImplicitOutputs() throws Exception {
    scratch.file("test/glob.bzl",
        "def _impl(ctx):",
        "  ctx.empty_action(",
        "    inputs = [],",
        "  )",
        "def _foo(attr_map):",
        "  return native.glob(['*'])",
        "glob_rule = rule(",
        "  implementation = _impl,",
        "  outputs = _foo,",
        ")");
    scratch.file("test/BUILD",
        "load('/test/glob', 'glob_rule')",
        "glob_rule(name = 'my_glob',",
        "  srcs = ['foo.bar', 'other_foo.bar'])");
    reporter.removeHandler(failFastHandler);
    getConfiguredTarget("//test:my_glob");
    assertContainsEvent("native.glob() can only be called during the loading phase");
  }

  private void setupThrowFunction(BuiltinFunction func) throws Exception {
    throwFunction = func;
    throwFunction.configure(
        getClass().getDeclaredField("throwFunction").getAnnotation(SkylarkSignature.class));
    update("throw", throwFunction);
  }

  private static class ThereIsNoMessageException extends EvalException {
    public ThereIsNoMessageException() {
      super(null, "This is not the message you are looking for."); // Unused dummy message
    }

    @Override
    public String getMessage() {
      return "";
    }
  }
}
