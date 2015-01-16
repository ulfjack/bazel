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
package com.google.devtools.build.lib.standalone;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.actions.Actions;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ExecutionStrategy;
import com.google.devtools.build.lib.actions.Executor;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnActionContext;
import com.google.devtools.build.lib.actions.UserExecException;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.rules.cpp.CppCompileAction;
import com.google.devtools.build.lib.shell.Command;
import com.google.devtools.build.lib.shell.CommandException;
import com.google.devtools.build.lib.syntax.Label;
import com.google.devtools.build.lib.util.CommandFailureUtils;
import com.google.devtools.build.lib.util.DependencySet;
import com.google.devtools.build.lib.util.io.FileOutErr;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;

/**
 * Strategy that uses sandboxing to execute a process.
 */
@ExecutionStrategy(name = {"sandboxed"}, 
                   contextType = SpawnActionContext.class)
public class LinuxSandboxedStrategy implements SpawnActionContext {
  private final boolean verboseFailures;
  private final BlazeDirectories directories;
  
  public LinuxSandboxedStrategy(BlazeDirectories blazeDirectories, boolean verboseFailures) {
    this.directories = blazeDirectories;
    this.verboseFailures = verboseFailures;
  }

  /**
   * Executes the given {@code spawn}.
   */
  @Override
  public void exec(Spawn spawn, ActionExecutionContext actionExecutionContext)
      throws ExecException {
    Executor executor = actionExecutionContext.getExecutor();
    if (executor.reportsSubcommands()) {
      executor.reportSubcommand(Label.print(spawn.getOwner().getLabel()),
          spawn.asShellCommand(executor.getExecRoot()));
    }
    List<String> args = new ArrayList<>();

    boolean processHeaders = spawn.getResourceOwner() instanceof CppCompileAction;
    
    Path execPath = this.directories.getExecRoot();
    Path sandboxWrapper = execPath.getRelative("_bin/namespace-sandbox-prototype-wrapper.py");
    Path sandboxBinary = execPath.getRelative("_bin/namespace-sandbox");
    Path inputDir = execPath.getRelative(".");
    List<String> spawnArguments = new ArrayList<>();

    for (String arg : spawn.getArguments()) {
      if (arg.startsWith(execPath.getPathString())) {
        // make all paths relative for the sandbox
        spawnArguments.add(arg.substring(execPath.getPathString().length()));
      } else {
        spawnArguments.add(arg);
      }
    }

    args.add("python");
    args.add(sandboxWrapper.getPathString());
    args.add("-S");
    args.add(sandboxBinary.getPathString());
    args.add("-I");
    args.add(inputDir.getPathString());
    args.add("-H");
    args.add(execPath.toString());
    addManifests(args, spawn.getRunfilesManifests());
    
    if (processHeaders) {
      // headers are mount in the sandbox in a separate include dir, so their names are mangled
      // when running the compilation and will have to be unmangled after it's done in the *.pic.d
      extractIncludeDirs(execPath, args, (CppCompileAction) spawn.getResourceOwner(),
          spawnArguments);  
    }
    
    List<? extends ActionInput> expandedInputs =
        ActionInputHelper.expandMiddlemen(spawn.getInputFiles(),
            actionExecutionContext.getMiddlemanExpander());
    addInputs(args, expandedInputs);
    addOutputs(args, spawn.getOutputFiles());
    args.add("-C");
    args.addAll(spawnArguments);
    
    String cwd = executor.getExecRoot().getPathString();
    Command cmd = new Command(args.toArray(new String[]{}), spawn.getEnvironment(), new File(cwd));

    FileOutErr outErr = actionExecutionContext.getFileOutErr();
    try {
      cmd.execute(
          /* stdin */ new byte[]{},
          Command.NO_OBSERVER,
          outErr.getOutputStream(),
          outErr.getErrorStream(),
          /*killSubprocessOnInterrupt*/ true);
      if (processHeaders) {
        unmangleHeaderFiles((CppCompileAction) spawn.getResourceOwner());
      }
    } catch (CommandException e) {
      String message = CommandFailureUtils.describeCommandFailure(verboseFailures,
          spawn.getArguments(), spawn.getEnvironment(), cwd);
      throw new UserExecException(String.format("%s: %s", message, e));
    } catch (IOException e) {
      String message = "Failed to post-process *.pic.d file when compiling: ";
      throw new UserExecException(String.format("%s %s: %s", message, spawn.getArguments(), e));
    }
  }

  private void unmangleHeaderFiles(CppCompileAction cppCompileAction) throws IOException {
    Path execPath = this.directories.getExecRoot();
    CppCompileAction.DotdFile dotdfile = cppCompileAction.getDotdFile();
    DependencySet depset = new DependencySet(execPath).read(dotdfile.getPath());
    DependencySet unmangled = new DependencySet(execPath);
    PathFragment sandboxIncludeDir = getSandboxIncludeDir(cppCompileAction);
    PathFragment prefix = sandboxIncludeDir.getRelative(execPath.asFragment().relativeTo("/"));
    for (PathFragment dep : depset.getDependencies()) {
      if (dep.startsWith(prefix)) {
        dep = dep.relativeTo(prefix);
      }
      unmangled.addDependency(dep);
    }
    unmangled.write(execPath.getRelative(depset.getOutputFileName()), ".d");
  }

  private PathFragment getSandboxIncludeDir(CppCompileAction cppCompileAction) {
    return new PathFragment(
        "include-" + Actions.escapedPath(cppCompileAction.getPrimaryOutput().toString()));
  }

  private void extractIncludeDirs(Path execPath, List<String> args,
      CppCompileAction cppCompileAction, List<String> spawnArguments) {
    List<PathFragment> includes = new ArrayList<>();
    includes.addAll(cppCompileAction.getQuoteIncludeDirs());
    includes.addAll(cppCompileAction.getIncludeDirs());
    includes.addAll(cppCompileAction.getSystemIncludeDirs());
    
    // gcc implicitly includes headers in the same dir as .cc file
    PathFragment sourceDirectory =
        cppCompileAction.getSourceFile().getPath().getParentDirectory().asFragment();
    includes.add(sourceDirectory);
    spawnArguments.add("-iquote");
    spawnArguments.add(sourceDirectory.toString());
    
    for (int i = 0; i < includes.size(); i++) {
      if (!includes.get(i).isAbsolute()) {
        includes.set(i, execPath.getRelative(includes.get(i)).asFragment());
      }
    }

    // the sandbox will mount these directories in the same order as they are passed and omit what
    // is already mounted. This means that if we give [/x/y/, /x/y/z, /x/y/t] it will mount only
    // /x/y/ and omit /x/y/t/ and /x/y/z (because they are the children of /x/y)
    Collections.sort(includes);
    
    for (PathFragment include : includes) {
      args.add("-n");
      args.add(include.toString());
    }
    
    // pseudo random name for include directory inside sandbox, so it won't be accessed by accident
    String prefix = getSandboxIncludeDir(cppCompileAction).toString();
    args.add("-N");
    args.add(prefix);
    
    // change names in the invocation
    for (int i = 0; i < spawnArguments.size(); i++) {
      if (spawnArguments.get(i).startsWith("-I")) {
        String argument = spawnArguments.get(i).substring(2);
        spawnArguments.set(i, setIncludeDirSandboxPath(execPath, argument, "-I" + prefix));
      }
      if (spawnArguments.get(i).equals("-iquote") || spawnArguments.get(i).equals("-isystem")) {
        spawnArguments.set(i + 1, setIncludeDirSandboxPath(execPath, 
            spawnArguments.get(i + 1), prefix));  
      }
    }
  }

  private String setIncludeDirSandboxPath(Path execPath, String argument, String prefix) {
    StringBuilder builder = new StringBuilder(prefix);
    if (argument.charAt(0) != '/') {
      // relative path
      builder.append(execPath);
      builder.append('/');
    }
    builder.append(argument);
    
    return builder.toString();
  }

  private void addManifests(List<String> args,
      ImmutableMap<PathFragment, Artifact> runfilesManifests) {
    for (Entry<PathFragment, Artifact> manifest : runfilesManifests.entrySet()) {
      args.add("-M");
      args.add(manifest.getValue().getPath().toString());
    }
  }

  private void addInputs(List<String> args, Iterable<? extends ActionInput> inputFiles) {
    for (ActionInput input : inputFiles) {
      if (input.getExecPathString().contains("internal/_middlemen/")) {
        continue;
      }
      args.add("-i");
      args.add(input.getExecPathString());
    }
  }

  private void addOutputs(List<String> args, Collection<? extends ActionInput> outputFiles) {
    HashSet<PathFragment> dirs = new HashSet<>();
    for (ActionInput output : outputFiles) {
      args.add("-o");
      args.add(output.getExecPathString());
      dirs.add(new PathFragment(output.getExecPathString()).getParentDirectory());
    }

    // dirs are directories that will be created inside sandbox so the action can put its output
    // inside them.
    for (PathFragment path : dirs) {
      if (path.toString().isEmpty()) {
        // some of the output files will be in sandbox root, we don't want to "mkdir "."
        continue;
      }
      args.add("-O");
      args.add(path.toString());
    }
  }

  @Override
  public String strategyLocality(String mnemonic, boolean remotable) {
    return "linux-sandboxing";
  }

  @Override
  public boolean isRemotable(String mnemonic, boolean remotable) {
    return false;
  }
}
