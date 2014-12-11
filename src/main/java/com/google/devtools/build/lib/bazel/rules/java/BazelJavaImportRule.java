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

package com.google.devtools.build.lib.bazel.rules.java;

import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.Type.BOOLEAN;
import static com.google.devtools.build.lib.packages.Type.LABEL;
import static com.google.devtools.build.lib.packages.Type.LABEL_LIST;

import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.bazel.rules.java.BazelJavaRuleClasses.IjarBaseRule;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClass.Builder;
import com.google.devtools.build.lib.rules.java.JavaSemantics;
import com.google.devtools.build.lib.view.BaseRuleClasses;
import com.google.devtools.build.lib.view.BlazeRule;
import com.google.devtools.build.lib.view.RuleDefinition;
import com.google.devtools.build.lib.view.RuleDefinitionEnvironment;

import java.util.Set;

/**
 * Rule definition for the java_import rule.
 */
@BlazeRule(name = "java_import",
             ancestors = { BaseRuleClasses.RuleBase.class, IjarBaseRule.class },
             factoryClass = BazelJavaImport.class)
public final class BazelJavaImportRule implements RuleDefinition {
  @Override
  public RuleClass build(Builder builder, RuleDefinitionEnvironment env) {
    final Set<String> allowedDeps = ImmutableSet.of(
        "java_library",
        "java_import",
        "cc_library",
        "cc_binary"  // NB: linkshared=1
    );

    return builder
        .add(attr("jars", LABEL_LIST)
            .mandatory()
            .nonEmpty()
            .allowedFileTypes(JavaSemantics.JAR))
        .add(attr("srcjar", LABEL)
            .allowedFileTypes(JavaSemantics.SOURCE_JAR, JavaSemantics.JAR)
            .direct_compile_time_input())
        .removeAttribute("deps")  // only exports are allowed; nothing is compiled
        .add(attr("exports", LABEL_LIST)
            .allowedRuleClasses(allowedDeps)
            .allowedFileTypes()  // none allowed
            )
        .add(attr("neverlink", BOOLEAN).value(false))
        .build();
  }
}
