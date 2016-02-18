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

package com.google.devtools.build.lib.rules.objc;

import com.google.devtools.build.lib.analysis.BaseRuleClasses;
import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.packages.RuleClass.Builder.RuleClassType;

/**
 * Rule definition for {@code experimental_ios_test} rule in Bazel.
 *
 * <p>experimental_ios_test is equivalent to ios_test.
 */
public final class ExperimentalIosTestRule extends AbstractIosTestRule {
  @Override
  public Metadata getMetadata() {
    return RuleDefinition.Metadata.builder()
        .name("experimental_ios_test")
        .type(RuleClassType.TEST)
        .ancestors(
            BaseRuleClasses.BaseRule.class,
            BaseRuleClasses.TestBaseRule.class,
            ObjcRuleClasses.IosTestBaseRule.class,
            ObjcRuleClasses.SimulatorRule.class)
        .factoryClass(IosTest.class)
        .build();
  }
}

/*<!-- #BLAZE_RULE (NAME = experimental_ios_test, TYPE = TEST, FAMILY = Objective-C) -->
${ATTRIBUTE_SIGNATURE}

<p>Deprecated. Use ios_test instead.</p>

<p>This rule provides a way to build iOS unit tests written in GTM and XCTest test frameworks
on both iOS simulator and real devices.
</p>

${ATTRIBUTE_DEFINITION}

<!-- #END_BLAZE_RULE -->*/
