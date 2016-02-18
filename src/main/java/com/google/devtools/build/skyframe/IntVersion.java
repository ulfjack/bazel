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

import java.io.Serializable;

/**
 * Versioning scheme based on integers.
 */
public final class IntVersion implements Version, Serializable {

  private final long val;

  public IntVersion(long val) {
    this.val = val;
  }

  public long getVal() {
    return val;
  }

  public IntVersion next() {
    return new IntVersion(val + 1);
  }

  @Override
  public boolean atMost(Version other) {
    if (!(other instanceof IntVersion)) {
      return false;
    }
    return val <= ((IntVersion) other).val;
  }

  @Override
  public int hashCode() {
    return Long.valueOf(val).hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    if (obj instanceof IntVersion) {
      IntVersion other = (IntVersion) obj;
      return other.val == val;
    }
    return false;
  }

  @Override
  public String toString() {
    return "IntVersion: " + val;
  }
}
