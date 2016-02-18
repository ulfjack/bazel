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
package com.google.devtools.build.lib.collect.nestedset;

/**
 * Helps reduce a sequence of potentially duplicated elements to a sequence of unique elements.
 */
interface Uniqueifier {

  /**
   * Returns true if-and-only-if this is the first time that this {@link Uniqueifier}'s method has
   * been called with this Object.  This uses Object.equals-type equality for the comparison.
   */
  public boolean isUnique(Object o);
}
