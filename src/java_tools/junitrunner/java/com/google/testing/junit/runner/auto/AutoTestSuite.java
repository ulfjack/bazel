// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.testing.junit.runner.auto;

import org.junit.runners.Suite;
import org.junit.runners.model.RunnerBuilder;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;

@RunWith(AutoTestSuite.class)
public class AutoTestSuite extends Suite {
  /**
   * Only called reflectively. Do not use programmatically.
   */
  public AutoTestSuite(Class<?> klass, RunnerBuilder builder) throws Throwable {
    super(builder, klass, getClasses(klass));
  }

  private static Class<?>[] getClasses(Class<?> klass) throws Throwable {
    List<String> allTests = getAllTestClasses();
    List<Class<?>> allClasses = new ArrayList<>();
    for (String testName : allTests) {
      allClasses.add(Class.forName(testName));
    }
    return allClasses.toArray(new Class<?>[0]);
  }

  private static List<String> getAllTestClasses() throws IOException {
    List<String> result = new ArrayList<>();
    ClassLoader loader = AutoTestSuite.class.getClassLoader();
    Enumeration<URL> configs = loader.getResources(TestAnnotationProcessor.SERVICES_FILENAME);
    while (configs.hasMoreElements()) {
      URL u = configs.nextElement();
      BufferedReader reader = null;
      try {
        reader = new BufferedReader(new InputStreamReader(u.openStream(), StandardCharsets.UTF_8));
        String s;
        while ((s = reader.readLine()) != null) {
          if (s.isEmpty()) {
            continue;
          }
          result.add(s);
        }
      } finally {
        try {
          if (reader != null) reader.close();
        } catch (IOException ignored) {
          // Closing silently.
        }
      }
    }
    return result;
  }
}

