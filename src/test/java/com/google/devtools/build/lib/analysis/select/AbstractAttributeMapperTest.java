// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.select;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.util.EventCollectionApparatus;
import com.google.devtools.build.lib.packages.AbstractAttributeMapper;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.AttributeContainer;
import com.google.devtools.build.lib.packages.AttributeMap;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.util.PackageFactoryApparatus;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.testutil.FoundationTestCase;
import com.google.devtools.build.lib.testutil.Scratch;
import com.google.devtools.build.lib.vfs.Path;

import java.util.List;

/**
 * Unit tests for {@link AbstractAttributeMapper}.
 */
public class AbstractAttributeMapperTest extends FoundationTestCase {

  private Package pkg;
  protected Rule rule;
  protected AbstractAttributeMapper mapper;

  private static class TestMapper extends AbstractAttributeMapper {
    public TestMapper(Package pkg, RuleClass ruleClass, Label ruleLabel,
        AttributeContainer attributes) {
      super(pkg, ruleClass, ruleLabel, attributes);
    }
  }

  protected Rule createRule(String pkgPath, String ruleName, String... ruleDef) throws Exception  {
    Scratch scratch = new Scratch();
    EventCollectionApparatus events = new EventCollectionApparatus();
    PackageFactoryApparatus packages = new PackageFactoryApparatus(events.reporter());

    Path buildFile = scratch.file(pkgPath + "/BUILD", ruleDef);
    pkg = packages.createPackage(pkgPath, buildFile);
    return pkg.getRule(ruleName);
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    rule = createRule("x", "myrule",
        "cc_binary(name = 'myrule',",
        "          srcs = ['a', 'b', 'c'])");
    RuleClass ruleClass = rule.getRuleClassObject();
    mapper = new TestMapper(pkg, ruleClass, rule.getLabel(), rule.getAttributeContainer());
  }

  public void testRuleProperties() throws Exception {
    assertEquals(rule.getName(), mapper.getName());
    assertEquals(rule.getLabel(), mapper.getLabel());
  }

  public void testPackageDefaultProperties() throws Exception {
    assertEquals(pkg.getDefaultHdrsCheck(), mapper.getPackageDefaultHdrsCheck());
    assertEquals(pkg.getDefaultTestOnly(), mapper.getPackageDefaultTestOnly());
    assertEquals(pkg.getDefaultDeprecation(), mapper.getPackageDefaultDeprecation());
  }

  public void testAttributeTypeChecking() throws Exception {
    // Good typing:
    mapper.get("srcs", BuildType.LABEL_LIST);

    // Bad typing:
    try {
      mapper.get("srcs", Type.BOOLEAN);
      fail("Expected type mismatch to trigger an exception");
    } catch (IllegalArgumentException e) {
      // Expected.
    }

    // Unknown attribute:
    try {
      mapper.get("nonsense", Type.BOOLEAN);
      fail("Expected non-existent type to trigger an exception");
    } catch (IllegalArgumentException e) {
      // Expected.
    }
  }

  public void testGetAttributeType() throws Exception {
    assertEquals(BuildType.LABEL_LIST, mapper.getAttributeType("srcs"));
    assertNull(mapper.getAttributeType("nonsense"));
  }

  public void testGetAttributeDefinition() {
    assertEquals("srcs", mapper.getAttributeDefinition("srcs").getName());
    assertNull(mapper.getAttributeDefinition("nonsense"));
  }

  public void testIsAttributeExplicitlySpecified() throws Exception {
    assertTrue(mapper.isAttributeValueExplicitlySpecified("srcs"));
    assertFalse(mapper.isAttributeValueExplicitlySpecified("deps"));
    assertFalse(mapper.isAttributeValueExplicitlySpecified("nonsense"));
  }

  protected static class VisitationRecorder implements AttributeMap.AcceptsLabelAttribute {
    public List<String> labelsVisited = Lists.newArrayList();
    @Override
    public void acceptLabelAttribute(Label label, Attribute attribute) {
      if (attribute.getName().equals("srcs")) {
        labelsVisited.add(label.toString());
      }
    }
  }

  public void testVisitation() throws Exception {
    VisitationRecorder recorder = new VisitationRecorder();
    mapper.visitLabels(recorder);
    assertThat(recorder.labelsVisited)
        .containsExactlyElementsIn(ImmutableList.of("//x:a", "//x:b", "//x:c"));
  }

  public void testComputedDefault() throws Exception {
    // Should return a valid ComputedDefault instance since this is a computed default:
    assertThat(mapper.getComputedDefault("$stl", BuildType.LABEL))
        .isInstanceOf(Attribute.ComputedDefault.class);
    // Should return null since this *isn't* a computed default:
    assertNull(mapper.getComputedDefault("srcs", BuildType.LABEL_LIST));
  }
}
