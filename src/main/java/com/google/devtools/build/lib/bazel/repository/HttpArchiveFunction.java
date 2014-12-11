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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.devtools.build.lib.bazel.repository.DecompressorFactory.DecompressorException;
import com.google.devtools.build.lib.bazel.rules.workspace.HttpArchiveRule;
import com.google.devtools.build.lib.packages.AggregatingAttributeMapper;
import com.google.devtools.build.lib.packages.PackageIdentifier.RepositoryName;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.skyframe.FileValue;
import com.google.devtools.build.lib.skyframe.RepositoryValue;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.view.RuleDefinition;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;

/**
 * Downloads a file over HTTP.
 */
public class HttpArchiveFunction extends RepositoryFunction {

  private static final int BUFFER_SIZE = 2048;

  @Override
  public SkyValue compute(SkyKey skyKey, Environment env) throws SkyFunctionException {
    RepositoryName repositoryName = (RepositoryName) skyKey.argument();
    Rule rule = RepositoryFunction.getRule(repositoryName, HttpArchiveRule.NAME, env);
    if (rule == null) {
      return null;
    }

    // The output directory is always under .external-repository (to stay out of the way of
    // artifacts from this repository) and uses the rule's name to avoid conflicts with other
    // remote repository rules. For example, suppose you had the following WORKSPACE file:
    //
    // http_archive(name = "png", url = "http://example.com/downloads/png.tar.gz", sha1 = "...")
    //
    // This would download png.tar.gz to .external-repository/png/png.tar.gz.
    Path outputDirectory = getOutputBase().getRelative(".external-repository")
        .getRelative(rule.getName());
    try {
      FileSystemUtils.createDirectoryAndParents(outputDirectory);
    } catch (IOException e) {
      throw new RepositoryFunctionException(e, Transience.TRANSIENT);
    }
    FileValue directoryValue = getRepositoryDirectory(outputDirectory, env);
    if (directoryValue == null) {
      return null;
    }
    AggregatingAttributeMapper mapper = AggregatingAttributeMapper.of(rule);
    URL url = null;
    try {
      url = new URL(mapper.get("url", Type.STRING));
    } catch (MalformedURLException e) {
      throw new RepositoryFunctionException(
          new EvalException(rule.getLocation(), "Error parsing URL: " + e.getMessage()),
              Transience.PERSISTENT);
    }
    String sha1 = mapper.get("sha1", Type.STRING);
    // TODO(bazel-team): check if there's an archive file that's already been downloaded that
    // matches this sha1 and, if so, use that.
    Path archiveFile = downloadHttp(outputDirectory, url, sha1);
    try {
      outputDirectory = DecompressorFactory.create(archiveFile).decompress();
    } catch (DecompressorException e) {
      throw new RepositoryFunctionException(new IOException(e.getMessage()), Transience.TRANSIENT);
    }
    return new RepositoryValue(outputDirectory, directoryValue);
  }

  @Override
  public String extractTag(SkyKey skyKey) {
    return null;
  }

  @Override
  public SkyFunctionName getSkyFunctionName() {
    return SkyFunctionName.computed(HttpArchiveRule.NAME.toUpperCase());
  }

  @Override
  public Class<? extends RuleDefinition> getRuleDefinition() {
    return HttpArchiveRule.class;
  }

  /**
   * Attempt to download a file from the repository's URL. Returns the path to the file downloaded.
   */
  private Path downloadHttp(Path outputDirectory, URL url, String sha1)
      throws RepositoryFunctionException {
    String filename = new PathFragment(url.getPath()).getBaseName();
    if (filename.isEmpty()) {
      filename = "temp";
    }
    Path outputFile = outputDirectory.getRelative(filename);

    try {
      FileSystemUtils.createDirectoryAndParents(outputDirectory);
    } catch (IOException e) {
      throw new RepositoryFunctionException(
          new IOException("Error creating directory " + outputDirectory + ": " + e.getMessage()),
          Transience.TRANSIENT);
    }

    try (OutputStream outputStream = outputFile.getOutputStream()) {
      ReadableByteChannel rbc = getChannel(url);
      ByteBuffer byteBuffer = ByteBuffer.allocate(BUFFER_SIZE);
      while (rbc.read(byteBuffer) > 0) {
        byteBuffer.flip();
        while (byteBuffer.hasRemaining()) {
          outputStream.write(byteBuffer.get());
        }
      }
    } catch (IOException e) {
      throw new RepositoryFunctionException(
          new IOException(
              "Error downloading " + url + " to " + outputDirectory + ": " + e.getMessage()),
          Transience.TRANSIENT);
    }

    try {
      String downloadedSha1 = getSha1(outputFile);
      if (!downloadedSha1.equals(sha1)) {
        throw new RepositoryFunctionException(
            new IOException(
                "Downloaded file at " + outputFile + " has sha1 of " + downloadedSha1
                    + ", does not match expected sha1 (" + sha1 + ")"),
            Transience.TRANSIENT);
      }
    } catch (IOException e) {
      throw new RepositoryFunctionException(
          new IOException(
              "Could not hash file " + outputFile + ": " + e.getMessage() + ", expected sha1 of "
                  + sha1 + ")"),
          Transience.TRANSIENT);
    }

    return outputFile;
  }

  @VisibleForTesting
  protected ReadableByteChannel getChannel(URL url) throws IOException {
    return Channels.newChannel(url.openStream());
  }

  private String getSha1(Path path) throws IOException {
    Hasher hasher = Hashing.sha1().newHasher();

    byte byteBuffer[] = new byte[BUFFER_SIZE];
    try (InputStream stream = path.getInputStream()) {
      int numBytesRead = stream.read(byteBuffer);
      while (numBytesRead != -1) {
        if (numBytesRead != 0) {
          // If more than 0 bytes were read, add them to the hash.
          hasher.putBytes(byteBuffer, 0, numBytesRead);
        }
        numBytesRead = stream.read(byteBuffer);
      }
    }
    return hasher.hash().toString();
  }
}
