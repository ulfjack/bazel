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

package com.google.devtools.build.lib.rules.proto;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.TransitiveInfoProvider;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.syntax.SkylarkCallable;
import com.google.devtools.build.lib.syntax.SkylarkModule;

/**
 * Configured target classes that implement this class can contribute .proto files to the
 * compilation of proto_library rules.
 */
@Immutable
@SkylarkModule(name = "ProtoSourcesProvider", doc = "")
public final class ProtoSourcesProvider implements TransitiveInfoProvider {
  /** The name of the field in Skylark used to access this class. */
  public static final String SKYLARK_NAME = "proto";

  private final NestedSet<Artifact> transitiveImports;
  private final NestedSet<Artifact> transitiveProtoSources;
  // TODO(bazel-team): Both of these should be NestedSets.
  private final ImmutableList<Artifact> protoSources;
  private final ImmutableList<Artifact> checkDepsProtoSources;

  public ProtoSourcesProvider(
      NestedSet<Artifact> transitiveImports,
      NestedSet<Artifact> transitiveProtoSources,
      ImmutableList<Artifact> protoSources,
      ImmutableList<Artifact> checkDepsProtoSources) {
    this.transitiveImports = transitiveImports;
    this.transitiveProtoSources = transitiveProtoSources;
    this.protoSources = protoSources;
    this.checkDepsProtoSources = checkDepsProtoSources;
  }

  /**
   * Transitive imports including weak dependencies
   * This determines the order of "-I" arguments to the protocol compiler, and
   * that is probably important
   */
  @SkylarkCallable(
      name = "transitive_imports",
      doc = "Transitive imports including weak dependencies",
      structField = true)
  public NestedSet<Artifact> getTransitiveImports() {
    return transitiveImports;
  }

  /**
   * Returns the proto sources for this rule and all its dependent protocol
   * buffer rules.
   */
  @SkylarkCallable(
      name = "transitive_sources",
      doc = "Proto sources for this rule and all its dependent protocol buffer rules.",
      structField = true)
  // TODO(bazel-team): The difference between transitive imports and transitive proto sources
  // should never be used by Skylark or by an Aspect. One of these two should be removed,
  // preferably soon, before Skylark users start depending on them.
  public NestedSet<Artifact> getTransitiveProtoSources() {
    return transitiveProtoSources;
  }

  /**
   * Returns the proto sources from the 'srcs' attribute.
   */
  @SkylarkCallable(
      name = "direct_sources",
      doc = "Proto sources from the 'srcs' attribute.",
      structField = true)
  public ImmutableList<Artifact> getDirectProtoSources() {
    return protoSources;
  }

  /**
   * Returns the proto sources from the 'srcs' attribute. If the library is a proxy library
   * that has no sources, return the sources from the direct deps.
   */
  @SkylarkCallable(
      name = "check_deps_sources",
      doc = "Proto sources from the 'srcs' attribute. If the library is a proxy library "
          + "that has no sources, it contains the check_deps_sources"
          + "from this library's direct deps.",
      structField = true)
  public ImmutableList<Artifact> getCheckDepsProtoSources() {
    return checkDepsProtoSources;
  }
}
