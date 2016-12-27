// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.rules.android;

import com.android.repository.Revision;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.packages.AttributeMap;
import com.google.devtools.build.lib.packages.NonconfigurableAttributeMapper;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.rules.repository.RepositoryDirectoryValue;
import com.google.devtools.build.lib.rules.repository.RepositoryFunction;
import com.google.devtools.build.lib.skyframe.DirectoryListingValue;
import com.google.devtools.build.lib.skyframe.Dirents;
import com.google.devtools.build.lib.skyframe.FileSymlinkException;
import com.google.devtools.build.lib.skyframe.FileValue;
import com.google.devtools.build.lib.skyframe.InconsistentFilesystemException;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.util.ResourceFileLoader;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.io.IOException;
import java.util.Properties;
import javax.annotation.Nullable;

/**
 * Implementation of the {@code android_sdk_repository} rule.
 */
public class AndroidSdkRepositoryFunction extends RepositoryFunction {
  private static final String BUILD_TOOLS_DIR_NAME = "build-tools";
  private static final Revision MIN_BUILD_TOOLS_REVISION = new Revision(24, 0, 3);

  @Override
  public boolean isLocal(Rule rule) {
    return true;
  }

  @Override
  public SkyValue fetch(
      Rule rule, Path outputDirectory, BlazeDirectories directories, Environment env)
      throws SkyFunctionException, InterruptedException {

    prepareLocalRepositorySymlinkTree(rule, outputDirectory);
    PathFragment pathFragment = getTargetPath(rule, directories.getWorkspace());

    if (!symlinkLocalRepositoryContents(
        outputDirectory, directories.getOutputBase().getFileSystem().getPath(pathFragment))) {
      return null;
    }

    AttributeMap attributes = NonconfigurableAttributeMapper.of(rule);
    Integer apiLevel = attributes.get("api_level", Type.INTEGER);
    String buildToolsDirectory;
    if (attributes.isAttributeValueExplicitlySpecified("build_tools_version")) {
      buildToolsDirectory = attributes.get("build_tools_version", Type.STRING);
    } else {
      // If the build_tools_version attribute is not explicitly set, we select the highest version
      // installed in the SDK.
      DirectoryListingValue directoryValue =
          getBuildToolsDirectoryListing(
              directories.getOutputBase().getFileSystem(), pathFragment, env);
      if (directoryValue == null) {
        return null;
      }
      buildToolsDirectory = getNewestBuildToolsDirectory(rule, directoryValue.getDirents());
    }

    // android_sdk_repository.build_tools_version is technically actually the name of the
    // directory in $sdk/build-tools. Most of the time this is just the actual build tools
    // version, but for preview build tools, the directory is something like 24.0.0-preview, and
    // the actual version is something like "24 rc3". The android_sdk rule in the template needs
    // the real version.
    String buildToolsVersion;
    if (buildToolsDirectory.contains("-preview")) {

      Properties sourceProperties =
          getBuildToolsSourceProperties(outputDirectory, buildToolsDirectory, env);
      if (env.valuesMissing()) {
        return null;
      }

      buildToolsVersion = sourceProperties.getProperty("Pkg.Revision");

    } else {
      buildToolsVersion = buildToolsDirectory;
    }

    try {
      assertValidBuildToolsVersion(rule, buildToolsVersion);
    } catch (EvalException e) {
      throw new RepositoryFunctionException(e, Transience.PERSISTENT);
    }

    String template = getStringResource("android_sdk_repository_template.txt");

    String buildFile = template
        .replaceAll("%repository_name%", rule.getName())
        .replaceAll("%build_tools_version%", buildToolsVersion)
        .replaceAll("%build_tools_directory%", buildToolsDirectory)
        .replaceAll("%api_level%", apiLevel.toString());

    // All local maven repositories that are shipped in the Android SDK.
    // TODO(ajmichael): Create SkyKeys so that if the SDK changes, this function will get rerun.
    Iterable<Path> localMavenRepositories = ImmutableList.of(
        outputDirectory.getRelative("extras/android/m2repository"),
        outputDirectory.getRelative("extras/google/m2repository"));
    try {
      SdkMavenRepository sdkExtrasRepository =
          SdkMavenRepository.create(Iterables.filter(localMavenRepositories, new Predicate<Path>() {
            @Override
            public boolean apply(@Nullable Path path) {
              return path.isDirectory();
            }
          }));
      sdkExtrasRepository.writeBuildFiles(outputDirectory);
      buildFile = buildFile.replaceAll(
          "%exported_files%", sdkExtrasRepository.getExportsFiles(outputDirectory));
    } catch (IOException e) {
      throw new RepositoryFunctionException(e, Transience.TRANSIENT);
    }

    writeBuildFile(outputDirectory, buildFile);
    return RepositoryDirectoryValue.create(outputDirectory);
  }

  @Override
  public Class<? extends RuleDefinition> getRuleDefinition() {
    return AndroidSdkRepositoryRule.class;
  }

  private static String getStringResource(String name) {
    try {
      return ResourceFileLoader.loadResource(
          AndroidSdkRepositoryFunction.class, name);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Gets a DirectoryListingValue for the build-tools directory under the sdkRepoPathFragment
   * or returns null.
   */
  private static DirectoryListingValue getBuildToolsDirectoryListing(
      FileSystem fs, PathFragment sdkRepoPathFragment, Environment env)
      throws RepositoryFunctionException, InterruptedException {
    try {
      return (DirectoryListingValue)
          env.getValueOrThrow(
              DirectoryListingValue.key(
                  RootedPath.toRootedPath(
                      fs.getRootDirectory(),
                      fs.getPath(sdkRepoPathFragment).getChild(BUILD_TOOLS_DIR_NAME))),
              InconsistentFilesystemException.class);
    } catch (InconsistentFilesystemException e) {
      throw new RepositoryFunctionException(new IOException(e), Transience.PERSISTENT);
    }
  }

  /**
   * Gets the newest build tools directory according to {@link Revision}.
   *
   * @throws RepositoryFunctionException if none of the buildToolsDirectories are directories and
   *     have names that are parsable as build tools version.
   */
  private static String getNewestBuildToolsDirectory(Rule rule, Dirents buildToolsDirectories)
      throws RepositoryFunctionException {
    String newestBuildToolsDirectory = null;
    Revision newestBuildToolsRevision = null;
    for (Dirent buildToolsDirectory : buildToolsDirectories) {
      if (buildToolsDirectory.getType() != Dirent.Type.DIRECTORY) {
        continue;
      }
      try {
        Revision buildToolsRevision = Revision.parseRevision(buildToolsDirectory.getName());
        if (newestBuildToolsRevision == null
            || buildToolsRevision.compareTo(newestBuildToolsRevision) > 0) {
          newestBuildToolsDirectory = buildToolsDirectory.getName();
          newestBuildToolsRevision = buildToolsRevision;
        }
      } catch (NumberFormatException e) {
        // Ignore unparsable build tools directories.
      }
    }
    if (newestBuildToolsDirectory == null) {
      throw new RepositoryFunctionException(
          new EvalException(
              rule.getLocation(),
              String.format(
                  "Bazel requires Android build tools version %s or newer but none are installed. "
                      + "Please install a recent version through the Android SDK manager.",
                  MIN_BUILD_TOOLS_REVISION)),
          Transience.PERSISTENT);
    }
    return newestBuildToolsDirectory;
  }

  private static Properties getBuildToolsSourceProperties(
      Path directory, String buildToolsDirectory, Environment env)
      throws RepositoryFunctionException, InterruptedException {

    Path sourcePropertiesFilePath = directory.getRelative(
        "build-tools/" + buildToolsDirectory + "/source.properties");

    SkyKey releaseFileKey = FileValue.key(
        RootedPath.toRootedPath(directory, sourcePropertiesFilePath));

    try {
      env.getValueOrThrow(releaseFileKey,
          IOException.class,
          FileSymlinkException.class,
          InconsistentFilesystemException.class);

      Properties properties = new Properties();
      properties.load(sourcePropertiesFilePath.getInputStream());
      return properties;

    } catch (IOException | FileSymlinkException | InconsistentFilesystemException e) {
      String error = String.format(
          "Could not read %s in Android SDK: %s", sourcePropertiesFilePath, e.getMessage());
      throw new RepositoryFunctionException(new IOException(error), Transience.PERSISTENT);
    }
  }

  private static void assertValidBuildToolsVersion(Rule rule, String buildToolsVersion)
      throws EvalException {
    try {
      Revision buildToolsRevision = Revision.parseRevision(buildToolsVersion);
      if (buildToolsRevision.compareTo(MIN_BUILD_TOOLS_REVISION) < 0) {
        throw new EvalException(
            rule.getAttributeLocation("build_tools_version"),
            String.format(
                "Bazel requires Android build tools version %s or newer, %s was provided",
                MIN_BUILD_TOOLS_REVISION,
                buildToolsRevision));
      }
    } catch (NumberFormatException e) {
      throw new EvalException(
          rule.getAttributeLocation("build_tools_version"),
          String.format(
              "Bazel does not recognize Android build tools version %s",
              buildToolsVersion),
          e);
    }
  }
}
