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
package com.google.devtools.build.skyframe;

import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;

import java.util.Map;

/**
 * Interface between a single version of the graph and the evaluator. Supports mutation of that
 * single version of the graph.
 */
@ThreadSafe
interface EvaluableGraph extends QueryableGraph {
  /**
   * Like {@link QueryableGraph#getBatch}, except it creates a new node for each key not already
   * present in the graph. Thus, the returned map will have an entry for each key in {@code keys}.
   */
  Map<SkyKey, NodeEntry> createIfAbsentBatch(Iterable<SkyKey> keys);
}
