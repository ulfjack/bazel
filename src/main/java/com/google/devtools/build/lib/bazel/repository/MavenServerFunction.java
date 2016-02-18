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

package com.google.devtools.build.lib.bazel.repository;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.bazel.rules.workspace.MavenServerRule;
import com.google.devtools.build.lib.packages.AggregatingAttributeMapper;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.rules.repository.RepositoryFunction;
import com.google.devtools.build.lib.skyframe.FileValue;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;

import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.building.DefaultSettingsBuilder;
import org.apache.maven.settings.building.DefaultSettingsBuilderFactory;
import org.apache.maven.settings.building.DefaultSettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuildingException;
import org.apache.maven.settings.building.SettingsBuildingResult;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * Implementation of maven_repository.
 */
public class MavenServerFunction extends RepositoryFunction {
  public static final SkyFunctionName NAME = SkyFunctionName.create("MAVEN_SERVER_FUNCTION");

  private static final String USER_KEY = "user";
  private static final String SYSTEM_KEY = "system";

  public MavenServerFunction(BlazeDirectories directories) {
    setDirectories(directories);
  }

  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env) throws RepositoryFunctionException {
    String repository = skyKey.argument().toString();
    Package externalPackage = RepositoryFunction.getExternalPackage(env);
    if (externalPackage == null) {
      return null;
    }
    Rule repositoryRule = externalPackage.getRule(repository);

    String serverName;
    String url;
    Map<String, FileValue> settingsFiles;
    boolean foundRepoRule = repositoryRule != null
        && repositoryRule.getRuleClass().equals(MavenServerRule.NAME);
    if (!foundRepoRule) {
      if (repository.equals(MavenServerValue.DEFAULT_ID)) {
        settingsFiles = getDefaultSettingsFile(env);
        serverName = MavenServerValue.DEFAULT_ID;
        url = MavenConnector.getMavenCentralRemote().getUrl();
      } else {
        throw new RepositoryFunctionException(
            new IOException("Could not find maven repository " + repository),
            Transience.TRANSIENT);
      }
    } else {
      AggregatingAttributeMapper mapper = AggregatingAttributeMapper.of(repositoryRule);
      serverName = repositoryRule.getName();
      url = mapper.get("url", Type.STRING);
      if (!mapper.has("settings_file", Type.STRING)
          || mapper.get("settings_file", Type.STRING).isEmpty()) {
        settingsFiles = getDefaultSettingsFile(env);
      } else {
        PathFragment settingsFilePath = new PathFragment(mapper.get("settings_file", Type.STRING));
        RootedPath settingsPath = RootedPath.toRootedPath(
            getWorkspace().getRelative(settingsFilePath), PathFragment.EMPTY_FRAGMENT);
        FileValue fileValue = (FileValue) env.getValue(FileValue.key(settingsPath));
        if (fileValue == null) {
          return null;
        }

        if (!fileValue.exists()) {
          throw new RepositoryFunctionException(
              new IOException("Could not find settings file " + settingsPath),
              Transience.TRANSIENT);
        }
        settingsFiles = ImmutableMap.<String, FileValue>builder().put(
            USER_KEY, fileValue).build();
      }
    }
    if (settingsFiles == null) {
      return null;
    }

    if (settingsFiles.isEmpty()) {
      return new MavenServerValue(serverName, url, new Server());
    }

    DefaultSettingsBuildingRequest request = new DefaultSettingsBuildingRequest();
    if (settingsFiles.containsKey(SYSTEM_KEY)) {
      request.setGlobalSettingsFile(
          settingsFiles.get(SYSTEM_KEY).realRootedPath().asPath().getPathFile());
    }
    if (settingsFiles.containsKey(USER_KEY)) {
      request.setUserSettingsFile(
          settingsFiles.get(USER_KEY).realRootedPath().asPath().getPathFile());
    }
    DefaultSettingsBuilder builder = (new DefaultSettingsBuilderFactory()).newInstance();
    SettingsBuildingResult result;
    try {
      result = builder.build(request);
    } catch (SettingsBuildingException e) {
      throw new RepositoryFunctionException(
          new IOException("Error parsing settings files: " + e.getMessage()),
          Transience.TRANSIENT);
    }
    if (!result.getProblems().isEmpty()) {
      throw new RepositoryFunctionException(
          new IOException("Errors interpreting settings file: "
              + Arrays.toString(result.getProblems().toArray())), Transience.PERSISTENT);
    }
    Settings settings = result.getEffectiveSettings();
    Server server = settings.getServer(serverName);
    server = server == null ? new Server() : server;
    return new MavenServerValue(serverName, url, server);
  }

  private Map<String, FileValue> getDefaultSettingsFile(Environment env) {
    // The system settings file is at $M2_HOME/conf/settings.xml.
    String m2Home = System.getenv("M2_HOME");
    ImmutableList.Builder<SkyKey> settingsFilesBuilder = ImmutableList.builder();
    SkyKey systemKey = null;
    if (m2Home != null) {
      PathFragment mavenInstallSettings = new PathFragment(m2Home).getRelative("conf/settings.xml");
      systemKey = FileValue.key(
          RootedPath.toRootedPath(getWorkspace().getRelative(mavenInstallSettings),
              PathFragment.EMPTY_FRAGMENT));
      settingsFilesBuilder.add(systemKey);
    }

    // The user settings file is at $HOME/.m2/settings.xml.
    String userHome = System.getenv("HOME");
    SkyKey userKey = null;
    if (userHome != null) {
      PathFragment userSettings = new PathFragment(userHome).getRelative(".m2/settings.xml");
      userKey = FileValue.key(RootedPath.toRootedPath(getWorkspace().getRelative(userSettings),
          PathFragment.EMPTY_FRAGMENT));
      settingsFilesBuilder.add(userKey);
    }

    ImmutableList settingsFiles = settingsFilesBuilder.build();
    if (settingsFiles.isEmpty()) {
      return ImmutableMap.of();
    }
    Map<SkyKey, SkyValue> values = env.getValues(settingsFilesBuilder.build());
    ImmutableMap.Builder<String, FileValue> settingsBuilder = ImmutableMap.builder();
    for (Map.Entry<SkyKey, SkyValue> entry : values.entrySet()) {
      if (entry.getValue() == null) {
        return null;
      }
      if (systemKey != null && systemKey.equals(entry.getKey())) {
        settingsBuilder.put(SYSTEM_KEY, (FileValue) entry.getValue());
      } else if (userKey != null && userKey.equals(entry.getKey())) {
        settingsBuilder.put(USER_KEY, (FileValue) entry.getValue());
      }
    }
    return settingsBuilder.build();
  }

  @Override
  public SkyFunctionName getSkyFunctionName() {
    return NAME;
  }

  @Override
  public Class<? extends RuleDefinition> getRuleDefinition() {
    return MavenServerRule.class;
  }
}
