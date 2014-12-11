// Copyright 2007-2014 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.buildjar;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * The JavaBuilder main called by bazel.
 */
public abstract class BazelJavaBuilder {

  private static final String CMDNAME = "BazelJavaBuilder";

  /**
   * The main method of the BazelJavaBuilder.
   */
  public static void main(String[] args) {
    try {
      JavaLibraryBuildRequest build = parse(Arrays.asList(args));
      AbstractJavaBuilder builder = build.getDependencyModule().reduceClasspath()
          ? new ReducedClasspathJavaLibraryBuilder()
          : new SimpleJavaLibraryBuilder();
      builder.run(build, System.err);
    } catch (IOException | InvalidCommandLineException e) {
      System.err.println(CMDNAME + " threw exception : " + e.getMessage());
      System.exit(1);
    }
  }

  /**
   * Parses the list of arguments into a {@link JavaLibraryBuildRequest}. The returned
   * {@link JavaLibraryBuildRequest} object can be then used to configure the compilation itself.
   *
   * @throws IOException if the argument list contains file (with the @ prefix) and reading that
   *         file failed.
   * @throws InvalidCommandLineException on any command line error
   */
  public static JavaLibraryBuildRequest parse(List<String> args) throws IOException,
      InvalidCommandLineException {
    List<String> arguments = JavaLibraryBuildRequest.expandArguments(args);
    return new JavaLibraryBuildRequest(arguments);
  }
}
