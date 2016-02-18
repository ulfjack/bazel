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

package com.google.devtools.build.buildjar.genclass;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.buildjar.jarhelper.JarCreator;
import com.google.devtools.build.buildjar.proto.JavaCompilation.CompilationUnit;
import com.google.devtools.build.buildjar.proto.JavaCompilation.Manifest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * GenClass post-processes the output of a Java compilation, and produces
 * a jar containing only the class files for sources that were generated
 * by annotation processors.
 */
public class GenClass {

  public static void main(String[] args) throws IOException {
    GenClassOptions options = GenClassOptionsParser.parse(Arrays.asList(args));
    Manifest manifest = readManifest(options.manifest());
    extractGeneratedClasses(options.classJar(), manifest, options.tempDir());
    writeOutputJar(options);
  }

  /**
   * Reads the compilation manifest.
   */
  private static Manifest readManifest(Path path) throws IOException {
    Manifest manifest;
    try (InputStream inputStream = Files.newInputStream(path)) {
      manifest = Manifest.parseFrom(inputStream);
    }
    return manifest;
  }

  /**
   * For each top-level class in the compilation, determine the path prefix
   * of classes corresponding to that compilation unit.
   *
   * <p>Prefixes are used to correctly handle inner classes, e.g. the top-level
   * class "c.g.Foo" may correspond to "c/g/Foo.class" and also
   * "c/g/Foo$Inner.class" or "c/g/Foo$0.class".
   */
  @VisibleForTesting
  static ImmutableSet<String> getGeneratedPrefixes(Manifest manifest) {
    ImmutableSet.Builder<String> prefixes = ImmutableSet.builder();
    for (CompilationUnit unit : manifest.getCompilationUnitList()) {
      if (!unit.getGeneratedByAnnotationProcessor()) {
        continue;
      }
      String pkg;
      if (unit.hasPkg()) {
        pkg = unit.getPkg().replace('.', '/') + "/";
      } else {
        pkg = "";
      }
      for (String toplevel : unit.getTopLevelList()) {
        prefixes.add(pkg + toplevel);
      }
    }
    return prefixes.build();
  }

  /**
   * Unzip all the class files that correspond to annotation processor-
   * generated sources into the temporary directory.
   */
  private static void extractGeneratedClasses(Path classJar, Manifest manifest, Path tempDir)
      throws IOException {
    ImmutableSet<String> generatedPrefixes = getGeneratedPrefixes(manifest);
    try (JarFile jar = new JarFile(classJar.toFile())) {
      Enumeration<JarEntry> entries = jar.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        String name = entry.getName();
        if (!name.endsWith(".class")) {
          continue;
        }
        String prefix = name.substring(0, name.length() - ".class".length());
        int idx = prefix.indexOf('$');
        if (idx > 0) {
          prefix = prefix.substring(0, idx);
        }
        if (generatedPrefixes.contains(prefix)) {
          Files.createDirectories(tempDir.resolve(name).getParent());
          Files.copy(jar.getInputStream(entry), tempDir.resolve(name));
        }
      }
    }
  }

  /** Writes the generated class files to the output jar. */
  private static void writeOutputJar(GenClassOptions options) throws IOException {
    JarCreator output = new JarCreator(options.outputJar().toString());
    output.setCompression(true);
    output.setNormalize(true);
    output.addDirectory(options.tempDir().toString());
    output.execute();
  }
}
