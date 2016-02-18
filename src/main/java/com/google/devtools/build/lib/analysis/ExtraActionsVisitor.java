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

package com.google.devtools.build.lib.analysis;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionGraph;
import com.google.devtools.build.lib.actions.ActionGraphVisitor;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.rules.extra.ExtraActionSpec;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * A bipartite graph visitor which accumulates extra actions for a target.
 */
final class ExtraActionsVisitor extends ActionGraphVisitor {
  private final RuleContext ruleContext;
  private final Multimap<String, ExtraActionSpec> mnemonicToExtraActionMap;
  private final List<Artifact> extraArtifacts;
  public final Set<Action> actions = Sets.newHashSet();

  /** Creates a new visitor for the extra actions associated with the given target. */
  public ExtraActionsVisitor(RuleContext ruleContext,
      Multimap<String, ExtraActionSpec> mnemonicToExtraActionMap) {
    super(getActionGraph(ruleContext));
    this.ruleContext = ruleContext;
    this.mnemonicToExtraActionMap = mnemonicToExtraActionMap;
    extraArtifacts = Lists.newArrayList();
  }

  public void addExtraAction(Action original) {
    Collection<ExtraActionSpec> extraActions = mnemonicToExtraActionMap.get(
        original.getMnemonic());
    if (extraActions != null) {
      for (ExtraActionSpec extraAction : extraActions) {
        extraArtifacts.addAll(extraAction.addExtraAction(ruleContext, original));
      }
    }
  }

  @Override
  protected void visitAction(Action action) {
    actions.add(action);
    addExtraAction(action);
  }

  /** Retrieves the collected artifacts since this method was last called and clears the list. */
  public ImmutableList<Artifact> getAndResetExtraArtifacts() {
    ImmutableList<Artifact> collected = ImmutableList.copyOf(extraArtifacts);
    extraArtifacts.clear();
    return collected;
  }

  /** Gets an action graph wrapper for the given target through its analysis environment. */
  private static ActionGraph getActionGraph(final RuleContext ruleContext) {
    return new ActionGraph() {
      @Override
      @Nullable
      public Action getGeneratingAction(Artifact artifact) {
        return ruleContext.getAnalysisEnvironment().getLocalGeneratingAction(artifact);
      }
    };
  }
}
