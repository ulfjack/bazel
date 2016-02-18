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

package com.google.devtools.build.workspace.maven;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.bazel.repository.MavenConnector;

import org.apache.maven.model.Parent;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.ModelSource;
import org.apache.maven.model.building.UrlModelSource;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.UnresolvableModelException;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolver to find the repository a given Maven artifact should be fetched
 * from.
 */
public class DefaultModelResolver implements ModelResolver {

  private final Set<Repository> repositories;
  private final Map<String, ModelSource> artifactToUrl;

  public DefaultModelResolver() {
    repositories = Sets.newHashSet();
    repositories.add(MavenConnector.getMavenCentral());
    artifactToUrl = Maps.newHashMap();
  }

  private DefaultModelResolver(
      Set<Repository> repositories, Map<String, ModelSource> artifactToRepository) {
    this.repositories = repositories;
    this.artifactToUrl = artifactToRepository;
  }

  @Override
  public ModelSource resolveModel(String groupId, String artifactId, String version)
      throws UnresolvableModelException {
    String artifact = Rule.name(groupId, artifactId);
    if (artifactToUrl.containsKey(artifact)) {
      return artifactToUrl.get(artifact);
    }
    for (Repository repository : repositories) {
      UrlModelSource modelSource = getModelSource(
          repository.getUrl(), groupId, artifactId, version);
      if (modelSource != null) {
        return modelSource;
      }
    }

    // TODO(kchodorow): use Java 8 features to make this a one-liner.
    List<String> urls = Lists.newArrayList();
    for (Repository repository : repositories) {
      urls.add(repository.getUrl());
    }
    throw new UnresolvableModelException("Could not find any repositories that knew how to "
        + "resolve " + groupId + ":" + artifactId + ":" + version + " (checked "
        + Joiner.on(", ").join(urls) + ")", groupId, artifactId, version);
  }

  // TODO(kchodorow): make this work with local repositories.
  private UrlModelSource getModelSource(
      String url, String groupId, String artifactId, String version)
      throws UnresolvableModelException {
    try {
      if (!url.endsWith("/")) {
        url += "/";
      }
      URL urlUrl = new URL(url
          + groupId.replaceAll("\\.", "/") + "/" + artifactId + "/" + version + "/" + artifactId
          + "-" + version + ".pom");
      if (pomFileExists(urlUrl)) {
        UrlModelSource urlModelSource = new UrlModelSource(urlUrl);
        artifactToUrl.put(Rule.name(groupId, artifactId), urlModelSource);
        return urlModelSource;
      }
    } catch (MalformedURLException e) {
      throw new UnresolvableModelException("Bad URL " + url + ": " + e.getMessage(), groupId,
          artifactId, version, e);
    }
    return null;
  }

  private boolean pomFileExists(URL url) {
    try {
      HttpURLConnection connection = (HttpURLConnection) url.openConnection();
      connection.setRequestMethod("HEAD");
      connection.setInstanceFollowRedirects(true);
      connection.connect();

      int code = connection.getResponseCode();
      if (code == 200) {
        return true;
      }
    } catch (IOException e) {
      // Something went wrong, fall through.
    }
    return false;
  }

  public ModelSource resolveModel(Parent parent) throws UnresolvableModelException {
    return resolveModel(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
  }

  @Override
  public void addRepository(Repository repository) {
    repositories.add(repository);
  }

  public void addRepository(Repository repository, boolean replace) {
    addRepository(repository);
  }

  @Override
  public ModelResolver newCopy() {
    return new DefaultModelResolver(repositories, artifactToUrl);
  }

  /**
   * Adds a user-specified repository to the list.
   */
  public void addUserRepository(String url) {
    Repository repository = new Repository();
    repository.setUrl(url);
    repository.setId("user-defined repository");
    repository.setName("default");
    addRepository(repository);
  }

  public boolean putModelSource(String groupId, String artifactId, ModelSource modelSource) {
    String key = Rule.name(groupId, artifactId);
    if (!artifactToUrl.containsKey(key)) {
      artifactToUrl.put(key, modelSource);
      return true;
    }
    return false;
  }
}
