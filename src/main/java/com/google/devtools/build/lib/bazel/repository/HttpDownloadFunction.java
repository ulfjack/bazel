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

package com.google.devtools.build.lib.bazel.repository;

import com.google.devtools.build.lib.bazel.repository.RepositoryFunction.RepositoryFunctionException;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.packages.AggregatingAttributeMapper;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;

import java.io.IOException;
import java.util.Objects;

import javax.annotation.Nullable;

/**
 * Downloads an archive file over HTTP.
 */
public class HttpDownloadFunction implements SkyFunction {
  public static final String NAME = "HTTP_DOWNLOAD";
  private Reporter reporter;

  public void setReporter(Reporter reporter) {
    this.reporter = reporter;
  }

  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env) throws RepositoryFunctionException {
    HttpDescriptor descriptor = (HttpDescriptor) skyKey.argument();
    try {
      // The downloaded file is not added to Skyframe, as changes to it cannot affect a build
      // (it's essentially a temporary file). The downloaded file is always an archive and its
      // contents, once decompressed, _can_ be dependencies of the build and _are_ added to
      // Skyframe (through the normal package mechanism).
      return new HttpDownloadValue(
          new HttpDownloader(
                  reporter,
                  descriptor.url,
                  descriptor.sha256,
                  descriptor.outputDirectory,
                  descriptor.type)
              .download());
    } catch (IOException e) {
      throw new RepositoryFunctionException(new IOException("Error downloading from "
          + descriptor.url + " to " + descriptor.outputDirectory + ": " + e.getMessage()),
          SkyFunctionException.Transience.TRANSIENT);
    }
  }

  @Nullable
  @Override
  public String extractTag(SkyKey skyKey) {
    return null;
  }

  public static SkyKey key(Rule rule, Path outputDirectory)
      throws RepositoryFunction.RepositoryFunctionException {
    AggregatingAttributeMapper mapper = AggregatingAttributeMapper.of(rule);
    String url = mapper.get("url", Type.STRING);
    String sha256 = mapper.get("sha256", Type.STRING);
    String type = mapper.has("type", Type.STRING) ? mapper.get("type", Type.STRING) : "";
    return new SkyKey(
        SkyFunctionName.create(NAME),
        new HttpDownloadFunction.HttpDescriptor(url, sha256, outputDirectory, type));
  }

  static final class HttpDescriptor {
    private String url;
    private String sha256;
    private Path outputDirectory;
    private String type;

    public HttpDescriptor(String url, String sha256, Path outputDirectory, String type) {
      this.url = url;
      this.sha256 = sha256;
      this.outputDirectory = outputDirectory;
      this.type = type;
    }

    @Override
    public String toString() {
      return url + " -> " + outputDirectory + " (" + sha256 + ")";
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == this) {
        return true;
      }
      if (obj == null || !(obj instanceof HttpDescriptor)) {
        return false;
      }
      HttpDescriptor other = (HttpDescriptor) obj;
      return Objects.equals(url, other.url)
          && Objects.equals(sha256, other.sha256)
          && Objects.equals(outputDirectory, other.outputDirectory)
          && Objects.equals(type, other.type);
    }

    @Override
    public int hashCode() {
      return Objects.hash(url, sha256, outputDirectory, type);
    }
  }
}
