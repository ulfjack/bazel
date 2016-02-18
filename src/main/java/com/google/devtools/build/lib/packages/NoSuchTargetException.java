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

package com.google.devtools.build.lib.packages;

import com.google.devtools.build.lib.cmdline.Label;

import java.util.Objects;

import javax.annotation.Nullable;

/**
 * Exception indicating an attempt to access a target which is not found or does
 * not exist.
 */
public class NoSuchTargetException extends NoSuchThingException {

  @Nullable private final Label label;
  private final boolean hasTarget;

  public NoSuchTargetException(String message) {
    this(null, message);
  }

  public NoSuchTargetException(@Nullable Label label, String message) {
    this((label != null ? "no such target '" + label + "': " : "") + message, label, null, null);
  }

  public NoSuchTargetException(Target targetInError, NoSuchPackageException nspe) {
    this(String.format("Target '%s' contains an error and its package is in error",
        targetInError.getLabel()), targetInError.getLabel(), targetInError, nspe);
  }

  private NoSuchTargetException(String message, @Nullable Label label, @Nullable Target target,
      @Nullable NoSuchPackageException nspe) {
    super(message, nspe);
    this.label = label;
    this.hasTarget = (target != null);
  }

  @Nullable
  public Label getLabel() {
    return label;
  }

  /**
   * Return whether parsing completed enough to construct the target.
   */
  public boolean hasTarget() {
    return hasTarget;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof NoSuchTargetException)) {
      return false;
    }
    NoSuchTargetException that = (NoSuchTargetException) o;
    return Objects.equals(this.label, that.label) && Objects.equals(this.hasTarget, that.hasTarget);
  }

  @Override
  public int hashCode() {
    return Objects.hash(label, hasTarget);
  }
}
