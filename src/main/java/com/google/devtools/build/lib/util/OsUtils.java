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

package com.google.devtools.build.lib.util;

import com.google.devtools.build.lib.util.resources.ResourceSet;

/**
 * Operating system-specific utilities.
 */
public final class OsUtils {
  public interface Helper {
    int getProcessId();
    ResourceSet getLocalResources();
  }

  /* If /proc/* information is not available, guess based on what the JVM thinks.  Anecdotally,
   * the JVM picks 0.22 the total available memory as maxMemory (tested on a standard Mac), so
   * multiply by 3, and divide by 2^20 because we want megabytes.
   */
  public static final ResourceSet DEFAULT_LOCAL_RESOURCES =
      ResourceSet.create(
          3.0 * (Runtime.getRuntime().maxMemory() >> 20),
          Runtime.getRuntime().availableProcessors(),
          1.0,
          Integer.MAX_VALUE);

  private static final String EXECUTABLE_EXTENSION = OS.getCurrent() == OS.WINDOWS ? ".exe" : "";

  private static Helper helper;

  // Utility class.
  private OsUtils() {
  }

  /**
   * Returns the extension used for executables on the current platform (.exe
   * for Windows, empty string for others).
   */
  public static String executableExtension() {
    return EXECUTABLE_EXTENSION;
  }

  /**
   * Loads JNI libraries, if necessary under the current platform.
   */
  public static void maybeForceJNI(String installBase) {
    if (jniLibsAvailable()) {
      forceJNI(installBase);
    }
  }

  private static boolean jniLibsAvailable() {
    return !"0".equals(System.getProperty("io.bazel.EnableJni"));
  }

  // Force JNI linking at a moment when we have 'installBase' handy, and print
  // an informative error if it fails.
  private static void forceJNI(String installBase) {
    try {
      String implementationName;
      if (OS.getCurrent() == OS.WINDOWS) {
        implementationName = "com.google.devtools.build.lib.windows.WindowsProcesses";
      } else {
        implementationName = "com.google.devtools.build.lib.unix.ProcessUtils";
      }
      helper = Class.forName(implementationName)
          .asSubclass(Helper.class)
          .getConstructor()
          .newInstance();
    } catch (UnsatisfiedLinkError e) {
      System.err.println("JNI initialization failed: " + e.getMessage() + ".  "
          + "Possibly your installation has been corrupted; "
          + "if this problem persists, try 'rm -fr " + installBase + "'.");
      throw e;
    } catch (ReflectiveOperationException e) {
      System.err.println("JNI initialization failed: " + e.getMessage() + ".  "
          + "Possibly your installation has been corrupted; "
          + "if this problem persists, try 'rm -fr " + installBase + "'.");
      throw new RuntimeException(e);
    }
  }

  /**
   * Returns the PID of the current process, or -1 if not available.
   */
  public static int getpid() {
    if (helper != null) {
      return helper.getProcessId();
    }
    return -1;
  }

  public static ResourceSet getLocalResources() {
    if (helper != null) {
      return helper.getLocalResources();
    }
    return DEFAULT_LOCAL_RESOURCES;
  }
}
