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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 *  Test properties of the evaluator's datatypes and utility functions
 *  without actually creating any parse trees.
 */
@RunWith(JUnit4.class)
public class EvalUtilsTest {

  private static List<?> makeList(Object... args) {
    return EvalUtils.makeSequence(Arrays.<Object>asList(args), false);
  }

  private static List<?> makeTuple(Object... args) {
    return EvalUtils.makeSequence(Arrays.<Object>asList(args), true);
  }

  private static Map<Object, Object> makeDict() {
    return new LinkedHashMap<>();
  }

  @Test
  public void testDataTypeNames() throws Exception {
    assertEquals("string", EvalUtils.getDataTypeName("foo"));
    assertEquals("int", EvalUtils.getDataTypeName(3));
    assertEquals("Tuple", EvalUtils.getDataTypeName(makeTuple(1, 2, 3)));
    assertEquals("List",  EvalUtils.getDataTypeName(makeList(1, 2, 3)));
    assertEquals("dict",  EvalUtils.getDataTypeName(makeDict()));
    assertEquals("NoneType", EvalUtils.getDataTypeName(Runtime.NONE));
  }

  @Test
  public void testDatatypeMutability() throws Exception {
    assertTrue(EvalUtils.isImmutable("foo"));
    assertTrue(EvalUtils.isImmutable(3));
    assertTrue(EvalUtils.isImmutable(makeTuple(1, 2, 3)));
    assertFalse(EvalUtils.isImmutable(makeList(1, 2, 3)));
    assertFalse(EvalUtils.isImmutable(makeDict()));
  }

  @Test
  public void testComparatorWithDifferentTypes() throws Exception {
    TreeMap<Object, Object> map = new TreeMap<>(EvalUtils.SKYLARK_COMPARATOR);
    map.put(2, 3);
    map.put("1", 5);
    map.put(42, 4);
    map.put("test", 7);
    map.put(-1, 2);
    map.put("4", 6);
    map.put(2.0, 1);
    map.put(Runtime.NONE, 0);

    int expected = 0;
    // Expected order of keys is NoneType -> Double -> Integers -> Strings
    for (Object obj : map.values()) {
      assertThat(obj).isEqualTo(expected);
      ++expected;
    }
  }
}
