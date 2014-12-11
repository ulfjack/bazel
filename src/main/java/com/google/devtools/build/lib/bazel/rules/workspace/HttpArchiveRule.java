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

package com.google.devtools.build.lib.bazel.rules.workspace;

import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.Type.STRING;

import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClass.Builder;
import com.google.devtools.build.lib.view.BaseRuleClasses;
import com.google.devtools.build.lib.view.BlazeRule;
import com.google.devtools.build.lib.view.RuleDefinition;
import com.google.devtools.build.lib.view.RuleDefinitionEnvironment;

/**
 * Rule definition for the http_archive rule.
 */
@BlazeRule(name = HttpArchiveRule.NAME,
  ancestors = { BaseRuleClasses.RuleBase.class },
  factoryClass = WorkspaceConfiguredTargetFactory.class)
public class HttpArchiveRule implements RuleDefinition {

  public static final String NAME = "http_archive";

  @Override
  public RuleClass build(Builder builder, RuleDefinitionEnvironment environment) {
    return builder.add(attr("url", STRING).mandatory())
        .add(attr("sha1", STRING).mandatory())
        .setWorkspaceOnly()
        .build();
  }

}
