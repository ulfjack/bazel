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

package com.google.devtools.build.lib.rules.objc;

import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.TransitiveInfoProvider;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;

/**
 * Provides information contained in a {@code objc_options} target.
 */
@Immutable
final class OptionsProvider
    extends Value<OptionsProvider>
    implements TransitiveInfoProvider {

  private final Iterable<String> copts;
  private final Iterable<Artifact> infoplists;

  public OptionsProvider(Iterable<String> copts, Iterable<Artifact> infoplists) {
    super(copts, infoplists);
    this.copts = Preconditions.checkNotNull(copts);
    this.infoplists = Preconditions.checkNotNull(infoplists);
  }

  public Iterable<String> getCopts() {
    return copts;
  }

  public Iterable<Artifact> getInfoplists() {
    return infoplists;
  }
}
