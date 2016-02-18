// Copyright 2006 The Bazel Authors. All Rights Reserved.
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

package com.google.devtools.build.lib.syntax;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.syntax.SkylarkList.MutableList;
import com.google.devtools.build.lib.syntax.util.EvaluationTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for MethodLibrary.
 */
@RunWith(JUnit4.class)
public class MethodLibraryTest extends EvaluationTestCase {

  @Before
  @Override
  public void setUp() throws Exception {
    super.setUp();
    setFailFast(true);
  }

  @Test
  public void testStackTraceLocation() throws Exception {
    new SkylarkTest().testIfErrorContains(
        "Traceback (most recent call last):\n\t"
        + "File \"<unknown>\", line 8\n\t\t"
        + "foo()\n\t"
        + "File \"<unknown>\", line 2, in foo\n\t\t"
        + "bar(1)\n\t"
        + "File \"<unknown>\", line 7, in bar\n\t\t"
        + "'test'.index(x)",
        "def foo():",
        "  bar(1)",
        "def bar(x):",
        "  if x == 1:",
        "    a = x",
        "    b = 2",
        "    'test'.index(x)",
        "foo()");
  }

  @Test
  public void testStackTraceWithIf() throws Exception {
    new SkylarkTest().testIfErrorContains(
        "File \"<unknown>\", line 5\n\t\t"
        + "foo()\n\t"
        + "File \"<unknown>\", line 3, in foo\n\t\ts[0]",
        "def foo():",
        "  s = set()",
        "  if s[0] == 1:",
        "    x = 1",
        "foo()");
  }

  @Test
  public void testStackTraceSkipBuiltInOnly() throws Exception {
    // The error message should not include the stack trace when there is
    // only one built-in function.
    new SkylarkTest()
        .testIfExactError(
            "Method string.index(sub: string, start: int, end: int or NoneType) is not applicable "
                + "for arguments (int, int, NoneType): 'sub' is int, but should be string",
            "'test'.index(1)");
  }

  @Test
  public void testStackTrace() throws Exception {
    // Unlike SkylarintegrationTests#testStackTraceErrorInFunction(), this test
    // has neither a BUILD nor a bzl file.
    new SkylarkTest()
        .testIfExactError(
            "Traceback (most recent call last):\n"
                + "\tFile \"<unknown>\", line 6\n"
                + "\t\tfoo()\n"
                + "\tFile \"<unknown>\", line 2, in foo\n"
                + "\t\tbar(1)\n"
                + "\tFile \"<unknown>\", line 5, in bar\n"
                + "\t\t'test'.index(x)\n"
                + "Method string.index(sub: string, start: int, end: int or NoneType) "
                + "is not applicable "
                + "for arguments (int, int, NoneType): 'sub' is int, but should be string",
            "def foo():",
            "  bar(1)",
            "def bar(x):",
            "  if 1 == 1:",
            "    'test'.index(x)",
            "foo()");
  }

  @Test
  public void testBuiltinFunctionErrorMessage() throws Exception {
    new SkylarkTest()
        .testIfErrorContains(
            "Method set.union(newElements: Iterable) is not applicable for arguments (string): "
            + "'newElements' is string, but should be Iterable",
            "set([]).union('a')")
        .testIfErrorContains(
            "Method string.startswith(sub: string, start: int, end: int or NoneType) is not "
            + "applicable for arguments (int, int, NoneType): 'sub' is int, "
            + "but should be string",
            "'test'.startswith(1)")
        .testIfErrorContains(
            "expected value of type 'list(object)' for parameter args in dict(), "
            + "but got \"a\" (string)",
            "dict('a')");
  }

  @Test
  public void testHasAttr() throws Exception {
    new SkylarkTest()
        .testStatement("hasattr(set(), 'union')", Boolean.TRUE)
        .testStatement("hasattr('test', 'count')", Boolean.TRUE)
        .testStatement("hasattr(dict(a = 1, b = 2), 'items')", Boolean.TRUE)
        .testStatement("hasattr({}, 'items')", Boolean.TRUE);
  }

  @Test
  public void testDir() throws Exception {
    new SkylarkTest().testStatement(
        "str(dir({}))", "[\"$index\", \"get\", \"items\", \"keys\", \"values\"]");
  }

  @Test
  public void testBoolean() throws Exception {
    new BothModesTest().testStatement("False", Boolean.FALSE).testStatement("True", Boolean.TRUE);
  }

  @Test
  public void testBooleanUnsupportedOperationFails() throws Exception {
    new BothModesTest().testIfErrorContains(
        "unsupported operand type(s) for +: 'bool' and 'bool'", "True + True");
  }

  @Test
  public void testPyStringJoin() throws Exception {
    new BothModesTest().testStatement("'-'.join([ 'a', 'b', 'c' ])", "a-b-c");
  }

  @Test
  public void testPyStringGlobalJoin() throws Exception {
    // TODO(bazel-team): BUILD and Skylark should use the same code path (and same error message).
    new BuildTest().testIfErrorContains(
        "name 'join' is not defined", "join(' ', [ 'a', 'b', 'c' ])");

    new SkylarkTest().testIfErrorContains("ERROR 1:1: function 'join' does not exist",
        "join(' ', [ 'a', 'b', 'c' ])");

    new BothModesTest().testStatement("' '.join([ 'a', 'b', 'c' ])", "a b c");
  }

  @Test
  public void testPyStringJoinCompr() throws Exception {
    new BothModesTest().testStatement("''.join([(x + '*') for x in ['a', 'b', 'c']])", "a*b*c*")
        .testStatement(
            "''.join([(y + '*' + z + '|') " + "for y in ['a', 'b', 'c'] for z in ['d', 'e']])",
            "a*d|a*e|b*d|b*e|c*d|c*e|");
  }

  @Test
  public void testPyStringLower() throws Exception {
    new BothModesTest().testStatement("'Blah Blah'.lower()", "blah blah");
  }

  @Test
  public void testPyStringUpper() throws Exception {
    new BothModesTest()
        .testStatement("'ein bier'.upper()", "EIN BIER")
        .testStatement("''.upper()", "");
  }

  @Test
  public void testPyStringReplace() throws Exception {
    new BothModesTest()
      .testStatement("'banana'.replace('a', 'e')", "benene")
      .testStatement("'banana'.replace('a', '$()')", "b$()n$()n$()")
      .testStatement("'banana'.replace('a', '$')", "b$n$n$")
      .testStatement("'banana'.replace('a', '\\\\')", "b\\n\\n\\")
      .testStatement("'b$()n$()n$()'.replace('$()', '$($())')", "b$($())n$($())n$($())")
      .testStatement("'b\\\\n\\\\n\\\\'.replace('\\\\', '$()')", "b$()n$()n$()");
  }

  @Test
  public void testPyStringReplace2() throws Exception {
    new BothModesTest()
      .testStatement("'banana'.replace('a', 'e', 2)", "benena")
;
  }

  @Test
  public void testPyStringSplit() throws Exception {
    new BothModesTest().testEval("'h i'.split(' ')", "['h', 'i']");
  }

  @Test
  public void testPyStringSplit2() throws Exception {
    new BothModesTest().testEval("'h i p'.split(' ')", "['h', 'i', 'p']");
  }

  @Test
  public void testPyStringSplit3() throws Exception {
    new BothModesTest().testEval("'a,e,i,o,u'.split(',', 2)", "['a', 'e', 'i,o,u']");
  }

  @Test
  public void testPyStringSplitNoSep() throws Exception {
    new BothModesTest().testEval(
        "'  1  2  3  '.split(' ')", "['', '', '1', '', '2', '', '3', '', '']");
  }

  @Test
  public void testPyStringRSplitRegex() throws Exception {
    new BothModesTest()
        .testEval("'foo/bar.lisp'.rsplit('.')", "['foo/bar', 'lisp']")
        .testEval("'foo/bar.?lisp'.rsplit('.?')", "['foo/bar', 'lisp']")
        .testEval("'fwe$foo'.rsplit('$')", "['fwe', 'foo']")
        .testEval("'windows'.rsplit('\\w')", "['windows']");
  }

  @Test
  public void testPyStringRSplitNoMatch() throws Exception {
    new BothModesTest()
        .testEval("''.rsplit('o')", "['']")
        .testEval("'google'.rsplit('x')", "['google']");
  }

  @Test
  public void testPyStringRSplitSeparator() throws Exception {
    new BothModesTest()
        .testEval("'xxxxxx'.rsplit('x')", "['', '', '', '', '', '', '']")
        .testEval("'xxxxxx'.rsplit('x', 1)", "['xxxxx', '']")
        .testEval("'xxxxxx'.rsplit('x', 2)", "['xxxx', '', '']")
        .testEval("'xxxxxx'.rsplit('x', 3)", "['xxx', '', '', '']")
        .testEval("'xxxxxx'.rsplit('x', 4)", "['xx', '', '', '', '']")
        .testEval("'xxxxxx'.rsplit('x', 5)", "['x', '', '', '', '', '']")
        .testEval("'xxxxxx'.rsplit('x', 6)", "['', '', '', '', '', '', '']")
        .testEval("'xxxxxx'.rsplit('x', 7)", "['', '', '', '', '', '', '']");

  }

  @Test
  public void testPyStringRSplitLongerSep() throws Exception {
    new BothModesTest().testEval("'abcdabef'.rsplit('ab')", "['', 'cd', 'ef']").testEval(
        "'google_or_gogol'.rsplit('go')", "['', 'ogle_or_', '', 'l']");
  }

  @Test
  public void testPyStringRSplitMaxSplit() throws Exception {
    new BothModesTest()
        .testEval("'google'.rsplit('o')", "['g', '', 'gle']")
        .testEval("'google'.rsplit('o')", "['g', '', 'gle']")
        .testEval("'google'.rsplit('o', 1)", "['go', 'gle']")
        .testEval("'google'.rsplit('o', 2)", "['g', '', 'gle']")
        .testEval("'google'.rsplit('o', 3)", "['g', '', 'gle']")
        .testEval("'ogooglo'.rsplit('o')", "['', 'g', '', 'gl', '']")
        .testEval("'ogooglo'.rsplit('o', 1)", "['ogoogl', '']")
        .testEval("'ogooglo'.rsplit('o', 2)", "['ogo', 'gl', '']")
        .testEval("'ogooglo'.rsplit('o', 3)", "['og', '', 'gl', '']")
        .testEval("'ogooglo'.rsplit('o', 4)", "['', 'g', '', 'gl', '']")
        .testEval("'ogooglo'.rsplit('o', 5)", "['', 'g', '', 'gl', '']")
        .testEval("'google'.rsplit('google')", "['', '']")
        .testEval("'google'.rsplit('google', 1)", "['', '']")
        .testEval("'google'.rsplit('google', 2)", "['', '']");
  }

  @Test
  public void testPyStringPartitionEasy() throws Exception {
    new BothModesTest().testEval("'lawl'.partition('a')", "['l', 'a', 'wl']").testEval(
        "'lawl'.rpartition('a')", "['l', 'a', 'wl']");
  }

  @Test
  public void testPyStringPartitionMultipleSep() throws Exception {
    new BothModesTest()
        .testEval("'google'.partition('o')", "['g', 'o', 'ogle']")
        .testEval("'google'.rpartition('o')", "['go', 'o', 'gle']")
        .testEval("'xxx'.partition('x')", "['', 'x', 'xx']")
        .testEval("'xxx'.rpartition('x')", "['xx', 'x', '']");
  }

  @Test
  public void testPyStringPartitionEmptyInput() throws Exception {
    new BothModesTest()
        .testEval("''.partition('a')", "['', '', '']")
        .testEval("''.rpartition('a')", "['', '', '']");
  }

  @Test
  public void testPyStringPartitionEmptySeparator() throws Exception {
    new BothModesTest()
        .testIfErrorContains("Empty separator", "'google'.partition('')")
        .testIfErrorContains("Empty separator", "'google'.rpartition('')");
  }

  @Test
  public void testPyStringPartitionDefaultSep() throws Exception {
    new BothModesTest()
        .testEval("'hi this is a test'.partition()", "['hi', ' ', 'this is a test']")
        .testEval("'hi this is a test'.rpartition()", "['hi this is a', ' ', 'test']")
        .testEval("'google'.partition()", "['google', '', '']")
        .testEval("'google'.rpartition()", "['', '', 'google']");
  }

  @Test
  public void testPyStringPartitionNoMatch() throws Exception {
    new BothModesTest()
        .testEval("'google'.partition('x')", "['google', '', '']")
        .testEval("'google'.rpartition('x')", "['', '', 'google']");
  }

  @Test
  public void testPyStringPartitionWordBoundaries() throws Exception {
    new BothModesTest()
        .testEval("'goog'.partition('g')", "['', 'g', 'oog']")
        .testEval("'goog'.rpartition('g')", "['goo', 'g', '']")
        .testEval("'plex'.partition('p')", "['', 'p', 'lex']")
        .testEval("'plex'.rpartition('p')", "['', 'p', 'lex']")
        .testEval("'plex'.partition('x')", "['ple', 'x', '']")
        .testEval("'plex'.rpartition('x')", "['ple', 'x', '']");
  }

  @Test
  public void testPyStringPartitionLongSep() throws Exception {
    new BothModesTest()
        .testEval("'google'.partition('oog')", "['g', 'oog', 'le']")
        .testEval("'google'.rpartition('oog')", "['g', 'oog', 'le']")
        .testEval(
            "'lolgooglolgooglolgooglol'.partition('goog')", "['lol', 'goog', 'lolgooglolgooglol']")
        .testEval(
            "'lolgooglolgooglolgooglol'.rpartition('goog')",
            "['lolgooglolgooglol', 'goog', 'lol']");
  }

  @Test
  public void testPyStringPartitionCompleteString() throws Exception {
    new BothModesTest()
        .testEval("'google'.partition('google')", "['', 'google', '']")
        .testEval("'google'.rpartition('google')", "['', 'google', '']");
  }

  @Test
  public void testPyStringTitle() throws Exception {
    new BothModesTest().testStatement(
        "'this is a very simple test'.title()", "This Is A Very Simple Test");
    new BothModesTest().testStatement(
        "'Do We Keep Capital Letters?'.title()", "Do We Keep Capital Letters?");
    new BothModesTest().testStatement(
        "'this isn\\'t just an ol\\' apostrophe test'.title()",
        "This Isn'T Just An Ol' Apostrophe Test");
    new BothModesTest().testStatement(
        "'Let us test crazy characters: _bla.exe//foo:bla(test$class)'.title()",
        "Let Us Test Crazy Characters: _Bla.Exe//Foo:Bla(Test$Class)");
    new BothModesTest().testStatement(
        "'any germans here? äöü'.title()",
        "Any Germans Here? Äöü");
    new BothModesTest().testStatement(
        "'WE HAve tO lOWERCASE soMEthING heRE, AI?'.title()",
        "We Have To Lowercase Something Here, Ai?");
    new BothModesTest().testStatement(
        "'wh4t ab0ut s0me numb3rs'.title()", "Wh4T Ab0Ut S0Me Numb3Rs");
  }

  @Test
  public void testCapitalize() throws Exception {
    new BothModesTest()
        .testStatement("'hello world'.capitalize()", "Hello world")
        .testStatement("'HELLO WORLD'.capitalize()", "Hello world")
        .testStatement("''.capitalize()", "")
        .testStatement("'12 lower UPPER 34'.capitalize()", "12 lower upper 34");
  }

  @Test
  public void testPyStringRfind() throws Exception {
    new BothModesTest()
        .testStatement("'banana'.rfind('na')", 4)
        .testStatement("'banana'.rfind('na', 3, 1)", -1)
        .testStatement("'aaaa'.rfind('a', 1, 1)", -1)
        .testStatement("'aaaa'.rfind('a', 1, 50)", 3)
        .testStatement("'aaaa'.rfind('aaaaa')", -1)
        .testStatement("'abababa'.rfind('ab', 1)", 4)
        .testStatement("'abababa'.rfind('ab', 0)", 4)
        .testStatement("'abababa'.rfind('ab', -1)", -1)
        .testStatement("'abababa'.rfind('ab', -2)", -1)
        .testStatement("'abababa'.rfind('ab', -3)", 4)
        .testStatement("'abababa'.rfind('ab', 0, 1)", -1)
        .testStatement("'abababa'.rfind('ab', 0, 2)", 0)
        .testStatement("'abababa'.rfind('ab', -1000)", 4)
        .testStatement("'abababa'.rfind('ab', 1000)", -1)
        .testStatement("''.rfind('a', 1)", -1);
  }

  @Test
  public void testPyStringFind() throws Exception {
    new BothModesTest()
        .testStatement("'banana'.find('na')", 2)
        .testStatement("'banana'.find('na', 3, 1)", -1)
        .testStatement("'aaaa'.find('a', 1, 1)", -1)
        .testStatement("'aaaa'.find('a', 1, 50)", 1)
        .testStatement("'aaaa'.find('aaaaa')", -1)
        .testStatement("'abababa'.find('ab', 1)", 2)
        .testStatement("'abababa'.find('ab', 0)", 0)
        .testStatement("'abababa'.find('ab', -1)", -1)
        .testStatement("'abababa'.find('ab', -2)", -1)
        .testStatement("'abababa'.find('ab', -3)", 4)
        .testStatement("'abababa'.find('ab', 0, 1)", -1)
        .testStatement("'abababa'.find('ab', 0, 2)", 0)
        .testStatement("'abababa'.find('ab', -1000)", 0)
        .testStatement("'abababa'.find('ab', 1000)", -1)
        .testStatement("''.find('a', 1)", -1);
  }

  @Test
  public void testPyStringIndex() throws Exception {
    new BothModesTest()
        .testStatement("'banana'.index('na')", 2)
        .testStatement("'abababa'.index('ab', 1)", 2)
        .testIfErrorContains("substring \"foo\" not found in \"banana\"", "'banana'.index('foo')");
  }

  @Test
  public void testPyStringRIndex() throws Exception {
    new BothModesTest()
        .testStatement("'banana'.rindex('na')", 4)
        .testStatement("'abababa'.rindex('ab', 1)", 4)
        .testIfErrorContains("substring \"foo\" not found in \"banana\"", "'banana'.rindex('foo')");
  }

  @Test
  public void testPyStringEndswith() throws Exception {
    new BothModesTest()
        .testStatement("'Apricot'.endswith('cot')", true)
        .testStatement("'a'.endswith('')", true)
        .testStatement("''.endswith('')", true)
        .testStatement("'Apricot'.endswith('co')", false)
        .testStatement("'Apricot'.endswith('co', -1)", false)
        .testStatement("'abcd'.endswith('c', -2, -1)", true)
        .testStatement("'abcd'.endswith('c', 1, 8)", false)
        .testStatement("'abcd'.endswith('d', 1, 8)", true);
  }

  @Test
  public void testPyStringStartswith() throws Exception {
    new BothModesTest()
        .testStatement("'Apricot'.startswith('Apr')", true)
        .testStatement("'Apricot'.startswith('A')", true)
        .testStatement("'Apricot'.startswith('')", true)
        .testStatement("'Apricot'.startswith('z')", false)
        .testStatement("''.startswith('')", true)
        .testStatement("''.startswith('a')", false);
  }

  @Test
  public void testPySubstring() throws Exception {
    new BothModesTest()
        .testStatement("'012345678'[0:-1]", "01234567")
        .testStatement("'012345678'[2:4]", "23")
        .testStatement("'012345678'[-5:-3]", "45")
        .testStatement("'012345678'[2:2]", "")
        .testStatement("'012345678'[2:]", "2345678")
        .testStatement("'012345678'[:3]", "012")
        .testStatement("'012345678'[-1:]", "8")
        .testStatement("'012345678'[:]", "012345678")
        .testStatement("'012345678'[-1:2]", "")
        .testStatement("'012345678'[4:2]", "");
  }

  @Test
  public void testPyStringFormatEscaping() throws Exception {
    new BothModesTest()
        .testStatement("'{{}}'.format()", "{}")
        .testStatement("'{{}}'.format(42)", "{}")
        .testStatement("'{{ }}'.format()", "{ }")
        .testStatement("'{{ }}'.format(42)", "{ }")
        .testStatement("'{{{{}}}}'.format()", "{{}}")
        .testStatement("'{{{{}}}}'.format(42)", "{{}}")

        .testStatement("'{{0}}'.format(42)", "{0}")

        .testStatement("'{{}}'.format(42)", "{}")
        .testStatement("'{{{}}}'.format(42)", "{42}")
        .testStatement("'{{ '.format(42)", "{ ")
        .testStatement("' }}'.format(42)", " }")
        .testStatement("'{{ {}'.format(42)", "{ 42")
        .testStatement("'{} }}'.format(42)", "42 }")

        .testStatement("'{{0}}'.format(42)", "{0}")
        .testStatement("'{{{0}}}'.format(42)", "{42}")
        .testStatement("'{{ 0'.format(42)", "{ 0")
        .testStatement("'0 }}'.format(42)", "0 }")
        .testStatement("'{{ {0}'.format(42)", "{ 42")
        .testStatement("'{0} }}'.format(42)", "42 }")

        .testStatement("'{{test}}'.format(test = 42)", "{test}")
        .testStatement("'{{{test}}}'.format(test = 42)", "{42}")
        .testStatement("'{{ test'.format(test = 42)", "{ test")
        .testStatement("'test }}'.format(test = 42)", "test }")
        .testStatement("'{{ {test}'.format(test = 42)", "{ 42")
        .testStatement("'{test} }}'.format(test = 42)", "42 }")

        .testIfErrorContains("Found '}' without matching '{'", "'{{}'.format(1)")
        .testIfErrorContains("Found '}' without matching '{'", "'{}}'.format(1)");
  }

  @Test
  public void testPyStringFormatManualPositionals() throws Exception {
    new BothModesTest()
        .testStatement(
            "'{0}, {1} {2} {3} test'.format('hi', 'this', 'is', 'a')", "hi, this is a test")
        .testStatement(
            "'{3}, {2} {1} {0} test'.format('a', 'is', 'this', 'hi')", "hi, this is a test")
        .testStatement(
            "'skip some {0}'.format('arguments', 'obsolete', 'deprecated')", "skip some arguments")
        .testStatement(
            "'{0} can be reused: {0}'.format('this', 'obsolete')", "this can be reused: this");
  }

  @Test
  public void testPyStringFormatManualPositionalsErrors() throws Exception {
    new BothModesTest()
        .testIfErrorContains("No replacement found for index 0", "'{0}'.format()")
        .testIfErrorContains("No replacement found for index 1", "'{0} and {1}'.format('this')")
        .testIfErrorContains(
            "No replacement found for index 2", "'{0} and {2}'.format('this', 'that')")
        .testIfErrorContains(
            "No replacement found for index -1", "'{-0} and {-1}'.format('this', 'that')")
        .testIfErrorContains(
            "Invalid character ',' inside replacement field",
            "'{0,1} and {1}'.format('this', 'that')")
        .testIfErrorContains(
            "Invalid character '.' inside replacement field",
            "'{0.1} and {1}'.format('this', 'that')");
  }

  @Test
  public void testPyStringFormatAutomaticPositionals() throws Exception {
    new BothModesTest()
        .testStatement("'{}, {} {} {} test'.format('hi', 'this', 'is', 'a')", "hi, this is a test")
        .testStatement(
            "'skip some {}'.format('arguments', 'obsolete', 'deprecated')", "skip some arguments");
  }

  @Test
  public void testPyStringFormatAutomaticPositionalsError() throws Exception {
    new BothModesTest()
        .testIfErrorContains("No replacement found for index 0", "'{}'.format()")
        .testIfErrorContains("No replacement found for index 1", "'{} and {}'.format('this')");
  }

  @Test
  public void testPyStringFormatMixedFields() throws Exception {
    new BothModesTest()
        .testStatement("'{test} and {}'.format(2, test = 1)", "1 and 2")
        .testStatement("'{test} and {0}'.format(2, test = 1)", "1 and 2")

        .testIfErrorContains(
            "non-keyword arg after keyword arg", "'{test} and {}'.format(test = 1, 2)")
        .testIfErrorContains(
            "non-keyword arg after keyword arg", "'{test} and {0}'.format(test = 1, 2)")

        .testIfErrorContains(
            "Cannot mix manual and automatic numbering of positional fields",
            "'{} and {1}'.format(1, 2)")
        .testIfErrorContains(
            "Cannot mix manual and automatic numbering of positional fields",
            "'{1} and {}'.format(1, 2)");
  }

  @Test
  public void testPyStringFormatInvalidFields() throws Exception {
    for (char unsupported : new char[] {'.', '[', ']', ','}) {
      new BothModesTest().testIfErrorContains(
          String.format("Invalid character '%c' inside replacement field", unsupported),
          String.format("'{test%ctest}'.format(test = 1)", unsupported));
    }

    new BothModesTest().testIfErrorContains(
        "Nested replacement fields are not supported", "'{ {} }'.format(42)");
  }

  @Test
  public void testPyStringFormat() throws Exception {
    new BothModesTest()
        .testStatement("'abc'.format()", "abc")
        .testStatement("'x{key}x'.format(key = 2)", "x2x")
        .testStatement("'x{key}x'.format(key = 'abc')", "xabcx")
        .testStatement("'{a}{b}{a}{b}'.format(a = 3, b = True)", "3True3True")
        .testStatement("'{a}{b}{a}{b}'.format(a = 3, b = True)", "3True3True")
        .testStatement("'{s1}{s2}'.format(s1 = ['a'], s2 = 'a')", "[\"a\"]a")

        .testIfErrorContains("Missing argument 'b'", "'{a}{b}'.format(a = 5)")

        .testStatement("'{a}'.format(a = '$')", "$")
        .testStatement("'{a}'.format(a = '$a')", "$a")
        .testStatement("'{a}$'.format(a = '$a')", "$a$");

    // The test below is using **kwargs, which is not available in BUILD mode.
    new SkylarkTest().testStatement("'{(}'.format(**{'(': 2})", "2");
  }

  @Test
  public void testListSlice() throws Exception {
    new BothModesTest()
        .testEval("[0,1,2,3][0:-1]", "[0, 1, 2]")
        .testEval("[0,1,2,3,4,5][2:4]", "[2, 3]")
        .testEval("[0,1,2,3,4,5][-2:-1]", "[4]")
        .testEval("[][1:2]", "[]")
        .testEval("[1,2,3][1:0]", "[]")
        .testEval("[0,1,2,3][-10:10]", "[0, 1, 2, 3]");
  }

  @Test
  public void testListSort() throws Exception {
    new BothModesTest()
        .testEval("sorted([0,1,2,3])", "[0, 1, 2, 3]")
        .testEval("sorted([])", "[]")
        .testEval("sorted([3, 2, 1, 0])", "[0, 1, 2, 3]")
        .testEval("sorted([[1], [], [2], [1, 2]])", "[[], [1], [1, 2], [2]]")
        .testEval("sorted([True, False, True])", "[False, True, True]")
        .testEval("sorted(['a','x','b','z'])", "[\"a\", \"b\", \"x\", \"z\"]")
        .testEval("sorted([sorted, sorted])", "[sorted, sorted]")
        .testEval("sorted({1: True, 5: True, 4: False})", "[1, 4, 5]");

    new SkylarkTest()  // set is available only in Skylark mode.
        .testEval("sorted(set([1, 5, 4]))", "[1, 4, 5]");
  }

  @Test
  public void testDictionaryCopy() throws Exception {
    new SkylarkTest()
        .setUp("x = {1 : 2}", "y = dict(x)")
        .testEval("x[1] == 2 and y[1] == 2", "True");
  }

  @Test
  public void testDictionaryCopyKeyCollision() throws Exception {
    new SkylarkTest()
        .setUp("x = {'test' : 2}", "y = dict(x, test = 3)")
        .testEval("y['test']", "3");
  }

  @Test
  public void testDictionaryWithMultipleKeys() throws Exception {
    new BothModesTest().testStatement("{0: 'a', 1: 'b', 0: 'c'}[0]", "c");
  }

  @Test
  public void testDictionaryKeyNotFound() throws Exception {
    new BothModesTest()
        .testIfErrorContains("Key \"0\" not found in dictionary", "{}['0']")
        .testIfErrorContains("Key 0 not found in dictionary", "{'0': 1, 2: 3, 4: 5}[0]");
  }

  @Test
  public void testListAccessBadIndex() throws Exception {
    new BothModesTest().testIfErrorContains(
        "expected value of type 'int' for index operand, but got \"a\" (string)",
        "[[1], [2]]['a']");
  }

  @Test
  public void testDictionaryAccess() throws Exception {
    new BothModesTest().testEval("{1: ['foo']}[1]", "['foo']")
      .testStatement("{'4': 8}['4']", 8)
      .testStatement("{'a': 'aa', 'b': 'bb', 'c': 'cc'}['b']", "bb");
  }

  @Test
  public void testDictionaryVariableAccess() throws Exception {
    new BothModesTest().setUp("d = {'a' : 1}", "a = d['a']\n").testLookup("a", 1);
  }

  @Test
  public void testStringIndexing() throws Exception {
    new BothModesTest()
        .testStatement("'somestring'[0]", "s")
        .testStatement("'somestring'[1]", "o")
        .testStatement("'somestring'[4]", "s")
        .testStatement("'somestring'[9]", "g")
        .testStatement("'somestring'[-1]", "g")
        .testStatement("'somestring'[-2]", "n")
        .testStatement("'somestring'[-10]", "s");
  }

  @Test
  public void testStringIndexingOutOfRange() throws Exception {
    new BothModesTest()
        .testIfErrorContains("List index out of range", "'abcdef'[10]")
        .testIfErrorContains("List index out of range", "'abcdef'[-11]")
        .testIfErrorContains("List index out of range", "'abcdef'[42]");
  }

  @Test
  public void testDictionaryCreation() throws Exception {
    String expected = "{'a': 1, 'b': 2, 'c': 3}";

    new BothModesTest()
        .testEval("dict([('a', 1), ('b', 2), ('c', 3)])", expected)
        .testEval("dict(a = 1, b = 2, c = 3)", expected)
        .testEval("dict([('a', 1)], b = 2, c = 3)", expected);
  }

  @Test
  public void testDictionaryCreationInnerLists() throws Exception {
    new BothModesTest().testEval("dict([[1, 2], [3, 4]], a = 5)", "{1: 2, 3: 4, 'a': 5}");
  }

  @Test
  public void testDictionaryCreationEmpty() throws Exception {
    new BothModesTest()
    .testEval("dict()", "{}")
    .testEval("dict([])", "{}");
  }

  @Test
  public void testDictionaryCreationDifferentKeyTypes() throws Exception {
    String expected = "{'a': 1, 2: 3}";

    new BothModesTest()
        .testEval("dict([('a', 1), (2, 3)])", expected)
        .testEval("dict([(2, 3)], a = 1)", expected);
  }

  @Test
  public void testDictionaryCreationKeyCollision() throws Exception {
    String expected = "{'a': 1, 'b': 2, 'c': 3}";

    new BothModesTest()
        .testEval("dict([('a', 42), ('b', 2), ('a', 1), ('c', 3)])", expected)
        .testEval("dict([('a', 42)], a = 1, b = 2, c = 3)", expected);
    new SkylarkTest()
        .testEval("dict([('a', 42)], **{'a': 1, 'b': 2, 'c': 3})", expected);
  }

  @Test
  public void testDictionaryCreationInvalidPositional() throws Exception {
    new BothModesTest()
        .testIfErrorContains(
            "expected value of type 'list(object)' for parameter args in dict(), "
            + "but got \"a\" (string)",
            "dict('a')")
        .testIfErrorContains(
            "Cannot convert dictionary update sequence element #0 to a sequence", "dict(['a'])")
        .testIfErrorContains(
            "Cannot convert dictionary update sequence element #0 to a sequence", "dict([('a')])")
        .testIfErrorContains("too many (3) positional arguments", "dict((3,4), (3,2), (1,2))")
        .testIfErrorContains(
            "Sequence #0 has length 3, but exactly two elements are required",
            "dict([('a', 'b', 'c')])");
  }

  @Test
  public void testDictionaryValues() throws Exception {
    new BothModesTest()
        .testEval("{1: 'foo'}.values()", "['foo']")
        .testEval("{}.values()", "[]")
        .testEval("{True: 3, False: 5}.values()", "[5, 3]")
        .testEval("{'a': 5, 'c': 2, 'b': 4, 'd': 3}.values()", "[5, 4, 2, 3]");
    // sorted by keys
  }

  @Test
  public void testDictionaryKeys() throws Exception {
    new BothModesTest()
        .testEval("{1: 'foo'}.keys()", "[1]")
        .testEval("{}.keys()", "[]")
        .testEval("{True: 3, False: 5}.keys()", "[False, True]")
        .testEval(
            "{1:'a', 2:'b', 6:'c', 0:'d', 5:'e', 4:'f', 3:'g'}.keys()", "[0, 1, 2, 3, 4, 5, 6]");
  }

  @Test
  public void testDictionaryGet() throws Exception {
    new BuildTest()
        .testStatement("{1: 'foo'}.get(1)", "foo")
        .testStatement("{1: 'foo'}.get(2)", Runtime.NONE)
        .testStatement("{1: 'foo'}.get(2, 'a')", "a")
        .testStatement("{1: 'foo'}.get(2, default='a')", "a")
        .testStatement("{1: 'foo'}.get(2, default=None)", Runtime.NONE);
  }

  @Test
  public void testDictionaryItems() throws Exception {
    new BothModesTest()
        .testEval("{'a': 'foo'}.items()", "[('a', 'foo')]")
        .testEval("{}.items()", "[]")
        .testEval("{1: 3, 2: 5}.items()", "[(1, 3), (2, 5)]")
        .testEval("{'a': 5, 'c': 2, 'b': 4}.items()", "[('a', 5), ('b', 4), ('c', 2)]");
  }

  @Test
  public void testSetUnionWithList() throws Exception {
    evaluateSet("set([]).union(['a', 'b', 'c'])", "a", "b", "c");
    evaluateSet("set(['a']).union(['b', 'c'])", "a", "b", "c");
    evaluateSet("set(['a', 'b']).union(['c'])", "a", "b", "c");
    evaluateSet("set(['a', 'b', 'c']).union([])", "a", "b", "c");
  }

  @Test
  public void testSetUnionWithSet() throws Exception {
    evaluateSet("set([]).union(set(['a', 'b', 'c']))", "a", "b", "c");
    evaluateSet("set(['a']).union(set(['b', 'c']))", "a", "b", "c");
    evaluateSet("set(['a', 'b']).union(set(['c']))", "a", "b", "c");
    evaluateSet("set(['a', 'b', 'c']).union(set([]))", "a", "b", "c");
  }

  @Test
  public void testSetUnionDuplicates() throws Exception {
    evaluateSet("set(['a', 'b', 'c']).union(['a', 'b', 'c'])", "a", "b", "c");
    evaluateSet("set(['a', 'a', 'a']).union(['a', 'a'])", "a");

    evaluateSet("set(['a', 'b', 'c']).union(set(['a', 'b', 'c']))", "a", "b", "c");
    evaluateSet("set(['a', 'a', 'a']).union(set(['a', 'a']))", "a");
  }

  @Test
  public void testSetUnionError() throws Exception {
    new SkylarkTest()
        .testIfErrorContains("insufficient arguments received by union", "set(['a']).union()")
        .testIfErrorContains(
            "Method set.union(newElements: Iterable) is not applicable for arguments (string): "
            + "'newElements' is string, but should be Iterable",
            "set(['a']).union('b')");
  }

  @Test
  public void testSetUnionSideEffects() throws Exception {
    eval("def func():",
        "  n1 = set(['a'])",
        "  n2 = n1.union(['b'])",
        "  return n1",
        "n = func()");
    assertEquals(ImmutableList.of("a"), ((SkylarkNestedSet) lookup("n")).toCollection());
  }

  private void evaluateSet(String statement, Object... expectedElements) throws Exception {
    new SkylarkTest().testCollection(statement, expectedElements);
  }

  @Test
  public void testListIndexMethod() throws Exception {
    new BothModesTest()
        .testStatement("['a', 'b', 'c'].index('a')", 0)
        .testStatement("['a', 'b', 'c'].index('b')", 1)
        .testStatement("['a', 'b', 'c'].index('c')", 2)
        .testStatement("[2, 4, 6].index(4)", 1)
        .testStatement("[2, 4, 6].index(4)", 1)
        .testStatement("[0, 1, [1]].index([1])", 2)
        .testIfErrorContains("Item \"a\" not found in list", "[1, 2].index('a')")
        .testIfErrorContains("Item 0 not found in list", "[].index(0)");
  }

  @Test
  public void testListIndex() throws Exception {
    new BothModesTest()
        .testStatement("['a', 'b', 'c', 'd'][0]", "a")
        .testStatement("['a', 'b', 'c', 'd'][1]", "b")
        .testStatement("['a', 'b', 'c', 'd'][-1]", "d")
        .testStatement("['a', 'b', 'c', 'd'][-2]", "c")
        .testStatement("[0, 1, 2][-3]", 0)
        .testStatement("[0, 1, 2][-2]", 1)
        .testStatement("[0, 1, 2][-1]", 2)
        .testStatement("[0, 1, 2][0]", 0);
  }

  @Test
  public void testListIndexOutOfRange() throws Exception {
    new BothModesTest()
        .testIfErrorContains("List index out of range", "[0, 1, 2][3]")
        .testIfErrorContains("List index out of range", "[0, 1, 2][-4]")
        .testIfErrorContains("List is empty", "[][0]")
        .testIfErrorContains("List index out of range", "[0][-2]")
        .testIfErrorContains("List index out of range", "[0][1]");
  }

  @Test
  public void testRange() throws Exception {
    new BothModesTest()
        .testStatement("str(range(5))", "[0, 1, 2, 3, 4]")
        .testStatement("str(range(0))", "[]")
        .testStatement("str(range(1))", "[0]")
        .testStatement("str(range(-2))", "[]")

        .testStatement("str(range(-3, 2))", "[-3, -2, -1, 0, 1]")
        .testStatement("str(range(3, 2))", "[]")
        .testStatement("str(range(3, 3))", "[]")
        .testStatement("str(range(3, 4))", "[3]")
        .testStatement("str(range(3, 5))", "[3, 4]")

        .testStatement("str(range(-3, 5, 2))", "[-3, -1, 1, 3]")
        .testStatement("str(range(-3, 6, 2))", "[-3, -1, 1, 3, 5]")
        .testStatement("str(range(5, 0, -1))", "[5, 4, 3, 2, 1]")
        .testStatement("str(range(5, 0, -10))", "[5]")
        .testStatement("str(range(0, -3, -2))", "[0, -2]")
        .testIfErrorContains("step cannot be 0", "range(2, 3, 0)");
  }

  @Test
  public void testEnumerate() throws Exception {
    new BothModesTest()
        .testStatement("str(enumerate([]))", "[]")
        .testStatement("str(enumerate([5]))", "[(0, 5)]")
        .testStatement("str(enumerate([5, 3]))", "[(0, 5), (1, 3)]")
        .testStatement("str(enumerate(['a', 'b', 'c']))", "[(0, \"a\"), (1, \"b\"), (2, \"c\")]")
        .testStatement("str(enumerate(['a']) + [(1, 'b')])", "[(0, \"a\"), (1, \"b\")]");
  }

  @Test
  public void testEnumerateBadArg() throws Exception {
    new BothModesTest().testIfErrorContains(
        "Method enumerate(list: sequence) is not applicable for arguments (string): "
        + "'list' is string, but should be sequence",
        "enumerate('a')");
  }

  @Test
  public void testPyListAppend() throws Exception {
    new BuildTest()
        .setUp("FOO = ['a', 'b']", "FOO.append('c')")
        .testLookup("FOO", MutableList.of(env, "a", "b", "c"))
        .testIfErrorContains(
            "function append is not defined on object of type 'tuple'", "(1, 2).append(3)");
  }

  @Test
  public void testPyListExtend() throws Exception {
    new BuildTest()
        .setUp("FOO = ['a', 'b']", "FOO.extend(['c', 'd'])")
        .testLookup("FOO", MutableList.of(env, "a", "b", "c", "d"))
        .testIfErrorContains(
            "function extend is not defined on object of type 'tuple'", "(1, 2).extend([3, 4])");
  }

  @Test
  public void testReassignmentOfPrimitivesNotForbiddenByCoreLanguage() throws Exception {
    new BuildTest()
        .setUp("cc_binary = (['hello.cc'])")
        .testIfErrorContains(
            "'list' object is not callable",
            "cc_binary(name = 'hello', srcs=['hello.cc'], malloc = '//base:system_malloc')");
  }

  @Test
  public void testLenOnString() throws Exception {
    new BothModesTest().testStatement("len('abc')", 3);
  }

  @Test
  public void testLenOnList() throws Exception {
    new BothModesTest().testStatement("len([1,2,3])", 3);
  }

  @Test
  public void testLenOnDict() throws Exception {
    new BothModesTest().testStatement("len({'a' : 1, 'b' : 2})", 2);
  }

  @Test
  public void testLenOnBadType() throws Exception {
    new BothModesTest().testIfErrorContains("int is not iterable", "len(1)");
  }

  @Test
  public void testIndexOnFunction() throws Exception {
    new BuildTest()
        .testIfErrorContains("operator [] is not defined on object of type 'function'", "len[1]")
        .testIfErrorContains(
            "operator [:] is not defined on object of type 'function'", "len[1:4]");
  }

  @Test
  public void testBool() throws Exception {
    new BothModesTest()
        .testStatement("bool(1)", Boolean.TRUE)
        .testStatement("bool(0)", Boolean.FALSE)
        .testStatement("bool([1, 2])", Boolean.TRUE)
        .testStatement("bool([])", Boolean.FALSE)
        .testStatement("bool(None)", Boolean.FALSE);
  }

  @Test
  public void testStr() throws Exception {
    new BothModesTest()
        .testStatement("str(1)", "1")
        .testStatement("str(-2)", "-2")
        .testStatement("str([1, 2])", "[1, 2]")
        .testStatement("str(True)", "True")
        .testStatement("str(False)", "False")
        .testStatement("str(None)", "None")
        .testStatement("str(str)", "<function str>");

    new SkylarkTest()
        .testStatement("str(struct(x = 2, y = 3, z = 4))", "struct(x = 2, y = 3, z = 4)");
  }

  @Test
  public void testInt() throws Exception {
    new BothModesTest()
        .testStatement("int('1')", 1)
        .testStatement("int('-1234')", -1234)
        .testIfErrorContains("invalid literal for int(): \"1.5\"", "int('1.5')")
        .testIfErrorContains("invalid literal for int(): \"ab\"", "int('ab')")
        .testStatement("int(42)", 42)
        .testStatement("int(-1)", -1)
        .testStatement("int(True)", 1)
        .testStatement("int(False)", 0)
        .testIfErrorContains("None is not of type string or int or bool", "int(None)");
  }

  @Test
  public void testStrFunction() throws Exception {
    new SkylarkTest().testStatement("def foo(x): return x\nstr(foo)", "<function foo>");
  }

  @Test
  public void testType() throws Exception {
    new SkylarkTest()
        .testStatement("type(1)", "int")
        .testStatement("type('a')", "string")
        .testStatement("type([1, 2])", "list")
        .testStatement("type((1, 2))", "tuple")
        .testStatement("type(True)", "bool")
        .testStatement("type(None)", "NoneType")
        .testStatement("type(str)", "function");
  }

  @Test
  public void testSelectFunction() throws Exception {
    enableSkylarkMode();
    eval("a = select({'a': 1})");
    SelectorList result = (SelectorList) lookup("a");
    assertThat(((SelectorValue) Iterables.getOnlyElement(result.getElements())).getDictionary())
        .isEqualTo(ImmutableMap.of("a", 1));
  }

  @Test
  public void testCountFunction() throws Exception {
    new BothModesTest()
        .testStatement("'abc'.count('')", 4)
        .testStatement("'abc'.count('a')", 1)
        .testStatement("'abc'.count('b')", 1)
        .testStatement("'abc'.count('c')", 1)
        .testStatement("'abbc'.count('b')", 2)
        .testStatement("'aba'.count('a')", 2)
        .testStatement("'aaa'.count('aa')", 1)
        .testStatement("'aaaa'.count('aa')", 2)
        .testStatement("'abc'.count('a', 0)", 1)
        .testStatement("'abc'.count('a', 1)", 0)
        .testStatement("'abc'.count('c', 0, 3)", 1)
        .testStatement("'abc'.count('c', 0, 2)", 0)
        .testStatement("'abc'.count('a', -1)", 0)
        .testStatement("'abc'.count('c', -1)", 1)
        .testStatement("'abc'.count('c', 0, 5)", 1)
        .testStatement("'abc'.count('c', 0, -1)", 0)
        .testStatement("'abc'.count('a', 0, -1)", 1);
  }

  @Test
  public void testZipFunction() throws Exception {
    new BothModesTest()
        .testStatement("str(zip())", "[]")
        .testStatement("str(zip([1, 2]))", "[(1,), (2,)]")
        .testStatement("str(zip([1, 2], ['a', 'b']))", "[(1, \"a\"), (2, \"b\")]")
        .testStatement("str(zip([1, 2, 3], ['a', 'b']))", "[(1, \"a\"), (2, \"b\")]")
        .testStatement("str(zip([1], [2], [3]))", "[(1, 2, 3)]")
        .testStatement("str(zip([1], {2: 'a'}))", "[(1, 2)]")
        .testStatement("str(zip([1], []))", "[]")
        .testIfErrorContains("type 'int' is not iterable", "zip(123)")
        .testIfErrorContains("type 'int' is not iterable", "zip([1], 1)");

    new SkylarkTest()  // because of set
        .testStatement("str(zip([1], set([2])))", "[(1, 2)]");
  }

  @Test
  public void testIsAlphaFunction() throws Exception {
    new BothModesTest()
        .testStatement("''.isalpha()", false)
        .testStatement("'abz'.isalpha()", true)
        .testStatement("'a1'.isalpha()", false)
        .testStatement("'a '.isalpha()", false)
        .testStatement("'A'.isalpha()", true)
        .testStatement("'AbZ'.isalpha()", true);
  }

  @Test
  public void testLStrip() throws Exception {
    new BothModesTest()
        .testStatement("'a b c'.lstrip('')", "a b c")
        .testStatement("'abcba'.lstrip('ba')", "cba")
        .testStatement("'abc'.lstrip('xyz')", "abc")
        .testStatement("'  a b c  '.lstrip()", "a b c  ")
        // the "\\"s are because Java absorbs one level of "\"s
        .testStatement("' \\t\\na b c '.lstrip()", "a b c ")
        .testStatement("' a b c '.lstrip('')", " a b c ");
  }

  @Test
  public void testRStrip() throws Exception {
    new BothModesTest()
        .testStatement("'a b c'.rstrip('')", "a b c")
        .testStatement("'abcba'.rstrip('ba')", "abc")
        .testStatement("'abc'.rstrip('xyz')", "abc")
        .testStatement("'  a b c  '.rstrip()", "  a b c")
        // the "\\"s are because Java absorbs one level of "\"s
        .testStatement("' a b c \\t \\n'.rstrip()", " a b c")
        .testStatement("' a b c '.rstrip('')", " a b c ");
  }

  @Test
  public void testStrip() throws Exception {
    new BothModesTest()
        .testStatement("'a b c'.strip('')", "a b c")
        .testStatement("'abcba'.strip('ba')", "c")
        .testStatement("'abc'.strip('xyz')", "abc")
        .testStatement("'  a b c  '.strip()", "a b c")
        .testStatement("' a b c\\t'.strip()", "a b c")
        .testStatement("'a b c'.strip('.')", "a b c")
        // the "\\"s are because Java absorbs one level of "\"s
        .testStatement("' \\t\\n\\ra b c \\t\\n\\r'.strip()", "a b c")
        .testStatement("' a b c '.strip('')", " a b c ");
  }
}
