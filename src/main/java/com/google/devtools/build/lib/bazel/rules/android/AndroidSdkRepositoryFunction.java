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
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Maps.EntryTransformer;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.repository.RepositoryDirectoryValue;
import com.google.devtools.build.lib.repository.RepositoryFunction;
import com.google.devtools.build.lib.repository.WorkspaceAttributeMapper;
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
import com.google.devtools.build.skyframe.ValueOrException;
import java.io.IOException;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * Implementation of the {@code android_sdk_repository} rule.
 */
public class AndroidSdkRepositoryFunction extends RepositoryFunction {
  private static final PathFragment BUILD_TOOLS_DIR = new PathFragment("build-tools");
  private static final PathFragment PLATFORMS_DIR = new PathFragment("platforms");
  private static final PathFragment SYSTEM_IMAGES_DIR = new PathFragment("system-images");
  private static final Pattern PLATFORMS_API_LEVEL_PATTERN = Pattern.compile("android-(\\d+)");
  private static final Revision MIN_BUILD_TOOLS_REVISION = new Revision(24, 0, 3);
  private static final String PATH_ENV_VAR = "ANDROID_HOME";
  private static final ImmutableList<String> PATH_ENV_VAR_AS_LIST = ImmutableList.of(PATH_ENV_VAR);

  @Override
  public boolean isLocal(Rule rule) {
    return true;
  }

  @Override
  public boolean verifyMarkerData(Rule rule, Map<String, String> markerData, Environment env)
      throws InterruptedException {
    WorkspaceAttributeMapper attributes = WorkspaceAttributeMapper.of(rule);
    if (attributes.isAttributeValueExplicitlySpecified("path")) {
      return true;
    }
    return super.verifyEnvironMarkerData(markerData, env, PATH_ENV_VAR_AS_LIST);
  }

  @Override
  public RepositoryDirectoryValue.Builder fetch(Rule rule, Path outputDirectory,
      BlazeDirectories directories, Environment env, Map<String, String> markerData)
      throws SkyFunctionException, InterruptedException {
    declareEnvironmentDependencies(markerData, env, PATH_ENV_VAR_AS_LIST);
    prepareLocalRepositorySymlinkTree(rule, outputDirectory);
    WorkspaceAttributeMapper attributes = WorkspaceAttributeMapper.of(rule);
    FileSystem fs = directories.getOutputBase().getFileSystem();
    Path androidSdkPath;
    if (attributes.isAttributeValueExplicitlySpecified("path")) {
      androidSdkPath = fs.getPath(getTargetPath(rule, directories.getWorkspace()));
    } else if (clientEnvironment.containsKey(PATH_ENV_VAR)){
      androidSdkPath =
          fs.getPath(getAndroidHomeEnvironmentVar(directories.getWorkspace(), clientEnvironment));
    } else {
      throw new RepositoryFunctionException(
          new EvalException(
              rule.getLocation(),
              "Either the path attribute of android_sdk_repository or the ANDROID_HOME environment "
                  + " variable must be set."),
          Transience.PERSISTENT);
    }

    if (!symlinkLocalRepositoryContents(outputDirectory, androidSdkPath)) {
      return null;
    }

    DirectoryListingValue platformsDirectoryValue =
        getDirectoryListing(androidSdkPath, PLATFORMS_DIR, env);
    if (platformsDirectoryValue == null) {
      return null;
    }

    ImmutableSortedSet<Integer> apiLevels = getApiLevels(platformsDirectoryValue.getDirents());
    if (apiLevels.isEmpty()) {
      throw new RepositoryFunctionException(
          new EvalException(
              rule.getLocation(),
              "android_sdk_repository requires that at least one Android SDK Platform is installed "
                  + "in the Android SDK. Please install an Android SDK Platform through the "
                  + "Android SDK manager."),
          Transience.PERSISTENT);
    }

    String defaultApiLevel;
    if (attributes.isAttributeValueExplicitlySpecified("api_level")) {
      try {
        defaultApiLevel = attributes.get("api_level", Type.INTEGER).toString();
      } catch (EvalException e) {
        throw new RepositoryFunctionException(e, Transience.PERSISTENT);
      }
    } else {
      // If the api_level attribute is not explicitly set, we select the highest api level that is
      // available in the SDK.
      defaultApiLevel = String.valueOf(apiLevels.first());
    }

    String buildToolsDirectory;
    if (attributes.isAttributeValueExplicitlySpecified("build_tools_version")) {
      try {
        buildToolsDirectory = attributes.get("build_tools_version", Type.STRING);
      } catch (EvalException e) {
        throw new RepositoryFunctionException(e, Transience.PERSISTENT);
      }
    } else {
      // If the build_tools_version attribute is not explicitly set, we select the highest version
      // installed in the SDK.
      DirectoryListingValue directoryValue =
          getDirectoryListing(androidSdkPath, BUILD_TOOLS_DIR, env);
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

    ImmutableSortedSet<PathFragment> androidDeviceSystemImageDirs =
        getAndroidDeviceSystemImageDirs(androidSdkPath, env);
    if (androidDeviceSystemImageDirs == null) {
      return null;
    }

    StringBuilder systemImageDirsList = new StringBuilder();
    for (PathFragment systemImageDir : androidDeviceSystemImageDirs) {
      systemImageDirsList.append(String.format("        \"%s\",\n", systemImageDir));
    }

    String template = getStringResource("android_sdk_repository_template.txt");

    String buildFile = template
        .replace("%repository_name%", rule.getName())
        .replace("%build_tools_version%", buildToolsVersion)
        .replace("%build_tools_directory%", buildToolsDirectory)
        .replace("%api_levels%", Iterables.toString(apiLevels))
        .replace("%default_api_level%", defaultApiLevel)
        .replace("%system_image_dirs%", systemImageDirsList);

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
      buildFile = buildFile.replace(
          "%exported_files%", sdkExtrasRepository.getExportsFiles(outputDirectory));
    } catch (IOException e) {
      throw new RepositoryFunctionException(e, Transience.TRANSIENT);
    }

    writeBuildFile(outputDirectory, buildFile);
    return RepositoryDirectoryValue.builder().setPath(outputDirectory);
  }

  @Override
  public Class<? extends RuleDefinition> getRuleDefinition() {
    return AndroidSdkRepositoryRule.class;
  }

  private static PathFragment getAndroidHomeEnvironmentVar(
      Path workspace, Map<String, String> env) {
    return workspace.getRelative(new PathFragment(env.get(PATH_ENV_VAR))).asFragment();
  }

  private static String getStringResource(String name) {
    try {
      return ResourceFileLoader.loadResource(
          AndroidSdkRepositoryFunction.class, name);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Gets a DirectoryListingValue for {@code dirPath} or returns null. */
  private static DirectoryListingValue getDirectoryListing(
      Path root, PathFragment dirPath, Environment env)
      throws RepositoryFunctionException, InterruptedException {
    try {
      return (DirectoryListingValue)
          env.getValueOrThrow(
              DirectoryListingValue.key(RootedPath.toRootedPath(root, dirPath)),
              InconsistentFilesystemException.class);
    } catch (InconsistentFilesystemException e) {
      throw new RepositoryFunctionException(new IOException(e), Transience.PERSISTENT);
    }
  }

  /**
   * Gets the numeric api levels from the contents of the platforms directory in descending order.
   */
  private static ImmutableSortedSet<Integer> getApiLevels(Dirents platformsDirectories) {
    ImmutableSortedSet.Builder<Integer> apiLevels = ImmutableSortedSet.reverseOrder();
    for (Dirent platformDirectory : platformsDirectories) {
      if (platformDirectory.getType() != Dirent.Type.DIRECTORY) {
        continue;
      }
      Matcher matcher = PLATFORMS_API_LEVEL_PATTERN.matcher(platformDirectory.getName());
      if (matcher.matches()) {
        apiLevels.add(Integer.parseInt(matcher.group(1)));
      }
    }
    return apiLevels.build();
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

  /**
   * Gets PathFragments for /sdk/system-images/*&#47;*&#47;*, which are the directories in the
   * SDK that contain system images needed for android_device.
   *
   * If the sdk/system-images directory does not exist, an empty set is returned.
   */
  private static ImmutableSortedSet<PathFragment> getAndroidDeviceSystemImageDirs(
      Path androidSdkPath, Environment env)
      throws RepositoryFunctionException, InterruptedException {
    if (!androidSdkPath.getRelative(SYSTEM_IMAGES_DIR).exists()) {
      return ImmutableSortedSet.of();
    }
    DirectoryListingValue systemImagesDirectoryValue =
        getDirectoryListing(androidSdkPath, SYSTEM_IMAGES_DIR, env);
    if (systemImagesDirectoryValue == null) {
      return null;
    }
    ImmutableMap<PathFragment, DirectoryListingValue> apiLevelSystemImageDirs =
        getSubdirectoryListingValues(
            androidSdkPath, SYSTEM_IMAGES_DIR, systemImagesDirectoryValue, env);
    if (apiLevelSystemImageDirs == null) {
      return null;
    }

    ImmutableSortedSet.Builder<PathFragment> pathFragments = ImmutableSortedSet.naturalOrder();
    for (PathFragment apiLevelDir : apiLevelSystemImageDirs.keySet()) {
      ImmutableMap<PathFragment, DirectoryListingValue> apiTypeSystemImageDirs =
          getSubdirectoryListingValues(
              androidSdkPath, apiLevelDir, apiLevelSystemImageDirs.get(apiLevelDir), env);
      if (apiTypeSystemImageDirs == null) {
        return null;
      }
      for (PathFragment apiTypeDir : apiTypeSystemImageDirs.keySet()) {
        for (Dirent architectureSystemImageDir :
            apiTypeSystemImageDirs.get(apiTypeDir).getDirents()) {
          pathFragments.add(apiTypeDir.getRelative(architectureSystemImageDir.getName()));
        }
      }
    }
    return pathFragments.build();
  }

  /** Gets DirectoryListingValues for subdirectories of the directory or returns null. */
  private static ImmutableMap<PathFragment, DirectoryListingValue> getSubdirectoryListingValues(
      final Path root, final PathFragment path, DirectoryListingValue directory, Environment env)
      throws RepositoryFunctionException, InterruptedException {
    Map<PathFragment, SkyKey> skyKeysForSubdirectoryLookups =
        Maps.transformEntries(
            Maps.uniqueIndex(
                directory.getDirents(),
                new Function<Dirent, PathFragment>() {
                  @Override
                  public PathFragment apply(Dirent input) {
                    return path.getRelative(input.getName());
                  }
                }),
            new EntryTransformer<PathFragment, Dirent, SkyKey>() {
              @Override
              public SkyKey transformEntry(PathFragment key, Dirent value) {
                return DirectoryListingValue.key(
                    RootedPath.toRootedPath(root, root.getRelative(key)));
              }
            });

    Map<SkyKey, ValueOrException<InconsistentFilesystemException>> values =
        env.getValuesOrThrow(
            skyKeysForSubdirectoryLookups.values(), InconsistentFilesystemException.class);

    ImmutableMap.Builder<PathFragment, DirectoryListingValue> directoryListingValues =
        new ImmutableMap.Builder<>();
    for (PathFragment pathFragment : skyKeysForSubdirectoryLookups.keySet()) {
      try {
        SkyValue skyValue = values.get(skyKeysForSubdirectoryLookups.get(pathFragment)).get();
        if (skyValue == null) {
          return null;
        }
        directoryListingValues.put(pathFragment, (DirectoryListingValue) skyValue);
      } catch (InconsistentFilesystemException e) {
        throw new RepositoryFunctionException(new IOException(e), Transience.PERSISTENT);
      }
    }
    return directoryListingValues.build();
  }
}
