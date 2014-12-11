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
package com.google.devtools.build.lib.skyframe;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.packages.PackageFactory;
import com.google.devtools.build.lib.packages.RuleClassProvider;
import com.google.devtools.build.lib.skyframe.ASTFileLookupValue.ASTLookupInputException;
import com.google.devtools.build.lib.syntax.BuildFileAST;
import com.google.devtools.build.lib.syntax.Function;
import com.google.devtools.build.lib.syntax.Label;
import com.google.devtools.build.lib.syntax.Label.SyntaxException;
import com.google.devtools.build.lib.syntax.SkylarkEnvironment;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;

import java.util.HashMap;
import java.util.Map;

/**
 * A Skyframe function to look up and import a single Skylark extension.
 */
public class SkylarkImportLookupFunction implements SkyFunction {

  private final RuleClassProvider ruleClassProvider;
  private final ImmutableList<Function> nativeRuleFunctions;

  public SkylarkImportLookupFunction(
      RuleClassProvider ruleClassProvider, PackageFactory packageFactory) {
    this.ruleClassProvider = ruleClassProvider;
    this.nativeRuleFunctions = packageFactory.collectNativeRuleFunctions();
  }

  @Override
  public SkyValue compute(SkyKey skyKey, Environment env) throws SkyFunctionException,
      InterruptedException {
    PathFragment file = (PathFragment) skyKey.argument();
    ASTFileLookupValue astLookupValue = null;
    try {
      SkyKey astLookupKey = ASTFileLookupValue.key(file);
      astLookupValue = (ASTFileLookupValue) env.getValueOrThrow(astLookupKey,
          ErrorReadingSkylarkExtensionException.class, InconsistentFilesystemException.class);
    } catch (ErrorReadingSkylarkExtensionException e) {
      throw new SkylarkImportLookupFunctionException(SkylarkImportFailedException.errorReadingFile(
          file, e.getMessage()));
    } catch (InconsistentFilesystemException e) {
      throw new SkylarkImportLookupFunctionException(e, Transience.PERSISTENT);
    } catch (ASTLookupInputException e) {
      throw new SkylarkImportLookupFunctionException(e, Transience.PERSISTENT);
    }
    if (astLookupValue == null) {
      return null;
    }
    if (astLookupValue == ASTFileLookupValue.NO_FILE) {
      // Skylark import files have to exist.
      throw new SkylarkImportLookupFunctionException(SkylarkImportFailedException.noFile(file));
    }

    Map<PathFragment, SkylarkEnvironment> importMap = new HashMap<>();
    ImmutableList.Builder<SkylarkFileDependency> fileDependencies = ImmutableList.builder();
    BuildFileAST ast = astLookupValue.getAST();
    // TODO(bazel-team): Refactor this code and PackageFunction to reduce code duplications.
    for (PathFragment importFile : ast.getImports()) {
      try {
        SkyKey importsLookupKey = SkylarkImportLookupValue.key(importFile);
        SkylarkImportLookupValue importsLookupValue;
        importsLookupValue = (SkylarkImportLookupValue) env.getValueOrThrow(
            importsLookupKey, ASTLookupInputException.class);
        if (importsLookupValue != null) {
          importMap.put(importFile, importsLookupValue.getImportedEnvironment());
          fileDependencies.add(importsLookupValue.getDependency());
        }
      } catch (ASTLookupInputException e) {
        throw new SkylarkImportLookupFunctionException(e, Transience.PERSISTENT);
      }
    }
    if (env.valuesMissing()) {
      // This means some imports are unavailable.
      return null;
    }

    if (ast.containsErrors()) {
      throw new SkylarkImportLookupFunctionException(SkylarkImportFailedException.skylarkErrors(
          file));
    }

    SkylarkEnvironment extensionEnv = createEnv(ast, importMap, env);
    // Skylark UserDefinedFunctions are sharing function definition Environments, so it's extremely
    // important not to modify them from this point. Ideally they should be only used to import
    // symbols and serve as global Environments of UserDefinedFunctions.
    return new SkylarkImportLookupValue(extensionEnv,
        new SkylarkFileDependency(pathFragmentToLabel(file), fileDependencies.build()));
  }

  private Label pathFragmentToLabel(PathFragment file) {
    try {
      // Create an absolute Label from the PathFragment: foo/bar/baz.ext --> //foo/bar:baz.ext.
      // These are not real Labels. We only use the Labels to have a unified format for reporting.
      return Label.parseAbsolute(
            "//" + file.subFragment(0, file.segmentCount() - 1).getPathString()
          + ":" + file.getBaseName());
    } catch (SyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Creates the SkylarkEnvironment to be imported. After it's returned, the Environment
   * must not be modified.
   */
  private SkylarkEnvironment createEnv(BuildFileAST ast,
      Map<PathFragment, SkylarkEnvironment> importMap, Environment env)
          throws InterruptedException {
    StoredEventHandler eventHandler = new StoredEventHandler();
    SkylarkEnvironment extensionEnv =
        ruleClassProvider.createSkylarkRuleClassEnvironment(eventHandler);
    // Adding native rules module for build extensions.
    // TODO(bazel-team): this might not be the best place to do this.
    extensionEnv.update("native", ruleClassProvider.getNativeModule());
    for (Function function : nativeRuleFunctions) {
        extensionEnv.registerFunction(
            ruleClassProvider.getNativeModule().getClass(), function.getName(), function);
    }
    extensionEnv.setImportedExtensions(importMap);
    ast.exec(extensionEnv, eventHandler);
    // Don't fail just replay the events so the original package lookup can fail.
    Event.replayEventsOn(env.getListener(), eventHandler.getEvents());
    return extensionEnv;
  }

  @Override
  public String extractTag(SkyKey skyKey) {
    return null;
  }

  static final class SkylarkImportFailedException extends Exception {
    private SkylarkImportFailedException(String errorMessage) {
      super(errorMessage);
    }

    public static SkylarkImportFailedException errorReadingFile(PathFragment file, String error) {
      return new SkylarkImportFailedException(
          String.format("Encountered error while reading extension file '%s': %s", file, error));
    }

    public static SkylarkImportFailedException noFile(PathFragment file) {
      return new SkylarkImportFailedException(
          String.format("Extension file not found: '%s'", file));
    }

    public static SkylarkImportFailedException skylarkErrors(PathFragment file) {
      return new SkylarkImportFailedException(String.format("Extension '%s' has errors", file));
    }
  }

  private static final class SkylarkImportLookupFunctionException extends SkyFunctionException {
    private SkylarkImportLookupFunctionException(SkylarkImportFailedException cause) {
      super(cause, Transience.PERSISTENT);
    }

    private SkylarkImportLookupFunctionException(InconsistentFilesystemException e,
        Transience transience) {
      super(e, transience);
    }

    private SkylarkImportLookupFunctionException(ASTLookupInputException e,
        Transience transience) {
      super(e, transience);
    }
  }
}
