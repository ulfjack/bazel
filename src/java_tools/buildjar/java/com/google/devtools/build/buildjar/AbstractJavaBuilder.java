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

package com.google.devtools.build.buildjar;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.io.Files;
import com.google.devtools.build.buildjar.javac.JavacRunner;
import com.google.devtools.build.buildjar.javac.JavacRunnerImpl;

import com.sun.tools.javac.main.Main.Result;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.List;
import java.util.zip.ZipEntry;

/**
 * A command line interface to compile a java_library rule using in-process
 * javac. This allows us to spawn multiple java_library compilations on a
 * single machine or distribute Java compilations to multiple machines.
 */
public abstract class AbstractJavaBuilder extends AbstractLibraryBuilder {

  /** The name of the protobuf meta file. */
  private static final String PROTOBUF_META_NAME = "protobuf.meta";

  /** Enables more verbose output from the compiler. */
  protected boolean debug = false;

  /**
   * Flush the buffers of this JavaBuilder
   */
  @SuppressWarnings("unused")  // IOException
  public synchronized void flush(OutputStream err) throws IOException {
  }

  /**
   * Shut this JavaBuilder down
   */
  @SuppressWarnings("unused")  // IOException
  public synchronized void shutdown(OutputStream err) throws IOException {
  }

  /**
   * Prepares a compilation run and sets everything up so that the source files
   * in the build request can be compiled. Invokes compileSources to do the
   * actual compilation.
   *
   * @param build A JavaLibraryBuildRequest request object describing what to
   *              compile
   * @param err PrintWriter for logging any diagnostic output
   */
  public void compileJavaLibrary(final JavaLibraryBuildRequest build, final OutputStream err)
      throws Exception {
    prepareSourceCompilation(build);

    final Exception[] exception = {null};
    final JavacRunner javacRunner = new JavacRunnerImpl(build.getPlugins());
    runWithLargeStack(
        new Runnable() {
          @Override
          public void run() {
            try {
              internalCompileJavaLibrary(build, javacRunner, err);
            } catch (Exception e) {
              exception[0] = e;
            }
          }
        },
        4L * 1024 * 1024); // 4MB stack

    if (exception[0] != null) {
      throw exception[0];
    }
  }

  /**
   * Compiles the java files of the java library specified in the build request.<p>
   * The compilation consists of two parts:<p>
   * First, javac is invoked directly to compile the java files in the build request.<p>
   * Second, additional processing is done to the .class files that came out of the compile.<p>
   *
   * @param build A JavaLibraryBuildRequest request object describing what to compile
   * @param err OutputStream for logging any diagnostic output
   */
  private void internalCompileJavaLibrary(JavaLibraryBuildRequest build, JavacRunner javacRunner,
      OutputStream err) throws IOException, JavacException {
    // result may not be null, in case somebody changes the set of source files
    // to the empty set
    Result result = Result.OK;
    if (!build.getSourceFiles().isEmpty()) {
      PrintWriter javacErrorOutputWriter = new PrintWriter(err);
      try {
        result = compileSources(build, javacRunner, javacErrorOutputWriter);
      } finally {
        javacErrorOutputWriter.flush();
      }
    }

    if (!result.isOK()) {
      throw new JavacException(result);
    }
    runClassPostProcessing(build);
  }

  /**
   * Build a jar file containing source files that were generated by an annotation processor.
   */
  public abstract void buildGensrcJar(JavaLibraryBuildRequest build, OutputStream err)
      throws IOException;

  @VisibleForTesting
  protected void runClassPostProcessing(JavaLibraryBuildRequest build)
      throws IOException {
    for (AbstractPostProcessor postProcessor : build.getPostProcessors()) {
      postProcessor.initialize(build);
      postProcessor.processRequest();
    }
  }

  /**
   * Compiles the java files specified in 'JavaLibraryBuildRequest'.
   * Implementations can try to avoid recompiling the java files. Whenever
   * this function is invoked, it is guaranteed that the build request
   * contains files to compile.
   *
   * @param build A JavaLibraryBuildRequest request object describing what to
   *              compile
   * @param err PrintWriter for logging any diagnostic output
   * @return the exit status of the java compiler.
   */
  abstract Result compileSources(JavaLibraryBuildRequest build, JavacRunner javacRunner,
      PrintWriter err) throws IOException;

  /**
   * Perform the build.
   */
  public void run(JavaLibraryBuildRequest build, PrintStream err) throws Exception {
    boolean successful = false;
    try {
      compileJavaLibrary(build, err);
      buildJar(build);
      if (!build.getProcessors().isEmpty()) {
        if (build.getGeneratedSourcesOutputJar() != null) {
          buildGensrcJar(build, err);
        }
      }
      successful = true;
    } finally {
      build.getDependencyModule().emitUsedClasspath(build.getClassPath());
      build.getDependencyModule().emitDependencyInformation(build.getClassPath(), successful);
      build.getProcessingModule().emitManifestProto();
      shutdown(err);
    }
  }

  // Utility functions

  /**
   * Runs "run" in another thread (whose lifetime is contained within the
   * activation of this function call) using a stack size of 'stackSize' bytes.
   * Unchecked exceptions thrown by the Runnable will be re-thrown in the main
   * thread.
   */
  private static void runWithLargeStack(final Runnable run, long stackSize) {
    final Throwable[] unchecked = { null };
    Thread t = new Thread(null, run, "runWithLargeStack", stackSize);
    t.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          unchecked[0] = e;
        }
      });
    t.start();
    boolean wasInterrupted = false;
    for (;;) {
      try {
        t.join(0);
        break;
      } catch (InterruptedException e) {
        wasInterrupted = true;
      }
    }
    if (wasInterrupted) {
      Thread.currentThread().interrupt();
    }
    if (unchecked[0] instanceof Error) {
      throw (Error) unchecked[0];
    } else if (unchecked[0] instanceof RuntimeException) {
      throw (RuntimeException) unchecked[0];
    }
  }

  /**
   * A SourceJarEntryListener that collects protobuf meta data files from the
   * source jar files.
   */
  private static class ProtoMetaFileCollector implements SourceJarEntryListener {

    private final String sourceDir;
    private final String outputDir;
    private final ByteArrayOutputStream buffer;

    public ProtoMetaFileCollector(String sourceDir, String outputDir) {
      this.sourceDir = sourceDir;
      this.outputDir = outputDir;
      this.buffer = new ByteArrayOutputStream();
    }

    @Override
    public void onEntry(ZipEntry entry) throws IOException {
      String entryName = entry.getName();
      if (!entryName.equals(PROTOBUF_META_NAME)) {
        return;
      }
      Files.copy(new File(sourceDir, PROTOBUF_META_NAME), buffer);
    }

    /**
     * Writes the combined the meta files into the output directory. Delete the
     * stalling meta file if no meta file is collected.
     */
    @Override
    public void finish() throws IOException {
      File outputFile = new File(outputDir, PROTOBUF_META_NAME);
      if (buffer.size() > 0) {
        try (OutputStream outputStream = new FileOutputStream(outputFile)) {
          buffer.writeTo(outputStream);
        }
      } else if (outputFile.exists()) {
        // Delete stalled meta file.
        outputFile.delete();
      }
    }
  }

  @Override
  protected List<SourceJarEntryListener> getSourceJarEntryListeners(
      JavaLibraryBuildRequest build) {
    List<SourceJarEntryListener> result = super.getSourceJarEntryListeners(build);
    result.add(new ProtoMetaFileCollector(
        build.getTempDir(), build.getClassDir()));
    return result;
  }
}
