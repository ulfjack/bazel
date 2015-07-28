// Copyright 2015 Google Inc. All rights reserved.
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
import com.google.devtools.build.lib.collect.nestedset.Order;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for SkylarkNestedSet.
 */
@RunWith(JUnit4.class)
public class SkylarkNestedSetTest extends EvaluationTestCase {

  @Test
  public void testNsetBuilder() throws Exception {
    eval("n = set(order='stable')");
    assertThat(lookup("n")).isInstanceOf(SkylarkNestedSet.class);
  }

  @Test
  public void testNsetOrder() throws Exception {
    eval("n = set(['a', 'b'], order='compile')");
    assertEquals(Order.COMPILE_ORDER, get("n").getSet(String.class).getOrder());
  }

  @Test
  public void testEmptyNsetGenericType() throws Exception {
    eval("n = set()");
    assertEquals(SkylarkType.TOP, get("n").getContentType());
  }

  @Test
  public void testFunctionReturnsNset() throws Exception {
    eval("def func():",
         "  n = set()",
         "  n += ['a']",
         "  return n",
         "s = func()");
    assertEquals(ImmutableList.of("a"), get("s").toCollection());
  }

  @Test
  public void testNsetTwoReferences() throws Exception {
    eval("def func():",
         "  n1 = set()",
         "  n1 += ['a']",
         "  n2 = n1",
         "  n2 += ['b']",
         "  return n1",
         "n = func()");
    assertEquals(ImmutableList.of("a"), get("n").toCollection());
  }

  @Test
  public void testNsetNestedItem() throws Exception {
    eval("def func():",
        "  n1 = set()",
        "  n2 = set()",
        "  n1 += ['a']",
        "  n2 += ['b']",
        "  n1 += n2",
        "  return n1",
        "n = func()");
    assertEquals(ImmutableList.of("b", "a"), get("n").toCollection());
  }

  @Test
  public void testNsetNestedItemBadOrder() throws Exception {
    checkEvalError("LINK_ORDER != COMPILE_ORDER",
        "set(['a', 'b'], order='compile') + set(['c', 'd'], order='link')");
  }

  @Test
  public void testNsetItemList() throws Exception {
    eval("def func():",
        "  n = set()",
        "  n += ['a', 'b']",
        "  return n",
        "n = func()");
    assertEquals(ImmutableList.of("a", "b"), get("n").toCollection());
  }

  @Test
  public void testNsetFuncParamNoSideEffects() throws Exception {
    eval("def func1(n):",
        "  n += ['b']",
        "def func2():",
        "  n = set()",
        "  n += ['a']",
        "  func1(n)",
        "  return n",
        "n = func2()");
    assertEquals(ImmutableList.of("a"), get("n").toCollection());
  }

  @Test
  public void testNsetTransitiveOrdering() throws Exception {
    eval("def func():",
        "  na = set(['a'], order='compile')",
        "  nb = set(['b'], order='compile')",
        "  nc = set(['c'], order='compile') + na",
        "  return set() + nb + nc",
        "n = func()");
    // The iterator lists the Transitive sets first
    assertEquals(ImmutableList.of("b", "a", "c"), get("n").toCollection());
  }

  @Test
  public void testNsetOrdering() throws Exception {
    eval("def func():",
        "  na = set()",
        "  na += [4]",
        "  na += [2, 4]",
        "  na += [3, 4, 5]",
        "  return na",
        "n = func()");
    // The iterator lists the Transitive sets first
    assertEquals(ImmutableList.of(4, 2, 3, 5), get("n").toCollection());
  }

  @Test
  public void testNsetBadOrder() throws Exception {
    checkEvalError("Invalid order: non_existing", "set(order='non_existing')");
  }

  @Test
  public void testNsetBadRightOperand() throws Exception {
    checkEvalError("cannot add 'string'-s to nested sets", "l = ['a']\n" + "set() + l[0]");
  }

  @Test
  public void testNsetBadCompositeItem() throws Exception {
    checkEvalError("nested set item is composite (type of struct)", "set([struct(a='a')])");
  }

  @Test
  public void testNsetToString() throws Exception {
    eval("s = set() + [2, 4, 6] + [3, 4, 5]",
        "x = str(s)");
    assertEquals("set([2, 4, 6, 3, 5])", lookup("x"));
  }

  @Test
  public void testNsetToStringWithOrder() throws Exception {
    eval("s = set(order = 'link') + [2, 4, 6] + [3, 4, 5]",
        "x = str(s)");
    assertEquals("set([2, 4, 6, 3, 5], order = \"link\")", lookup("x"));
  }

  @SuppressWarnings("unchecked")
  private SkylarkNestedSet get(String varname) throws Exception {
    return (SkylarkNestedSet) lookup(varname);
  }
}
