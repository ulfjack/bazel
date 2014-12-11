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

import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.vfs.PathFragment;

/**
 * An object that captures information of a single java_library target for j2objc transpilation.
 */
public class J2ObjcSource {
  private final Artifact sourceJar;
  private final Iterable<Artifact> objcSrcs;
  private final Iterable<Artifact> objcHdrs;
  private final PathFragment objcFilePath;

  /**
   * Constructs a J2ObjcSource containing java_library target information for j2objc transpilation.
   *
   * @param sourceJar the source jar @{code Artifact} associated with the java_library target.
   * @param objcSrcs the {@code Iterable} containing objc source files transpiled from Java sources
   *     in the {@code sourceJar}.
   * @param objcHdrs the {@code Iterable} containing objc header files transpiled from Java sources
   *     in the {@code sourceJar}.
   * @param objcFilePath the {@code PathFragment} under which all the transpiled objc files are. It
   *     can be used as header search path for objc compilations.
   */
  public J2ObjcSource(Artifact sourceJar, Iterable<Artifact> objcSrcs, Iterable<Artifact> objcHdrs,
      PathFragment objcFilePath) {
    this.sourceJar = sourceJar;
    this.objcSrcs = objcSrcs;
    this.objcHdrs = objcHdrs;
    this.objcFilePath = objcFilePath;
  }

  /**
   * Returns the source jar {@code Artifact} of the associated java_library target.
   */
  public Artifact getSourceJar() {
    return sourceJar;
  }

  /**
   * Returns the {@code Iterable} of objc source files transpiled from the Java files in
   * {@code sourceJar}.
   */
  public Iterable<Artifact> getObjcSrcs() {
    return objcSrcs;
  }

  /*
   * Returns the {@code Iterable} of objc header files transpiled from the Java files in
   * {@code sourceJar}.
   */
  public Iterable<Artifact> getObjcHdrs() {
    return objcHdrs;
  }

  /**
   * Returns the {@code PathFragment} which represents a directory where the transpiled objc files
   * reside and which can also be used as header search path in ObjC compilation.
   */
  public PathFragment getObjcFilePath() {
    return objcFilePath;
  }
}

