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
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Helper class for downloading a file from a URL.
 */
public class HttpDownloader {
  private static final int BUFFER_SIZE = 8192;

  private final String urlString;
  private final String sha256;
  private final String type;
  private final Path outputDirectory;
  private final Reporter reporter;
  private final ScheduledExecutorService scheduler;

  HttpDownloader(
      Reporter reporter, String urlString, String sha256, Path outputDirectory, String type) {
    this.urlString = urlString;
    this.sha256 = sha256;
    this.outputDirectory = outputDirectory;
    this.reporter = reporter;
    this.scheduler = Executors.newScheduledThreadPool(1);
    this.type = type;
  }

  /**
   * Attempt to download a file from the repository's URL. Returns the path to the file downloaded.
   */
  public Path download() throws IOException {
    URL url = new URL(urlString);
    String filename = new PathFragment(url.getPath()).getBaseName();
    if (filename.isEmpty()) {
      filename = "temp";
    }
    if (!type.isEmpty()) {
      filename += "." + type;
    }
    Path destination = outputDirectory.getRelative(filename);

    int currentBytes;
    final AtomicInteger totalBytes = new AtomicInteger(0);
    final ScheduledFuture<?> loggerHandle = getLoggerHandle(totalBytes);

    try (OutputStream outputStream = destination.getOutputStream()) {
      ReadableByteChannel rbc = getChannel(url);
      WritableByteChannel obc = Channels.newChannel(outputStream);
      ByteBuffer byteBuffer = ByteBuffer.allocate(BUFFER_SIZE);
      while ((currentBytes = rbc.read(byteBuffer)) > 0) {
        totalBytes.addAndGet(currentBytes);
        byteBuffer.flip();
        while (byteBuffer.hasRemaining()) {
          obc.write(byteBuffer);
        }
        byteBuffer.flip();
      }
    } catch (IOException e) {
      throw new IOException(
          "Error downloading " + url + " to " + destination + ": " + e.getMessage());
    } finally {
      scheduler.schedule(new Runnable() {
        public void run() { loggerHandle.cancel(true); }
      }, 0, TimeUnit.SECONDS);
    }

    String downloadedSha256;
    try {
      downloadedSha256 = getHash(Hashing.sha256().newHasher(), destination);
    } catch (IOException e) {
      throw new IOException(
          "Could not hash file " + destination + ": " + e.getMessage() + ", expected SHA-256 of "
              + sha256 + ")");
    }
    if (!downloadedSha256.equals(sha256)) {
      throw new IOException(
          "Downloaded file at " + destination + " has SHA-256 of " + downloadedSha256
              + ", does not match expected SHA-256 (" + sha256 + ")");
    }
    return destination;
  }

  private ScheduledFuture<?> getLoggerHandle(final AtomicInteger totalBytes) {
    final Runnable logger = new Runnable() {
      private static final int KB = 1024;
      private static final String UNITS = " KMGTPEY";
      private final double logOfKb = Math.log(1024);

      public void run() {
        try {
          reporter.handle(Event.progress(
              "Downloading from " + urlString + ": " + formatSize(totalBytes.get())));
        } catch (Exception e) {
          reporter.handle(Event.error(
              "Error generating download progress: " + e.getMessage()));
        }
      }

      private String formatSize(int bytes) {
        if (bytes < KB) {
          return bytes + "B";
        }
        int logBaseUnitOfBytes = (int) (Math.log(bytes) / logOfKb);
        if (logBaseUnitOfBytes < 0 || logBaseUnitOfBytes >= UNITS.length()) {
          return bytes + "B";
        }
        return (int) (bytes / Math.pow(KB, logBaseUnitOfBytes))
            + (UNITS.charAt(logBaseUnitOfBytes) + "B");
      }
    };
    return scheduler.scheduleAtFixedRate(logger, 0, 1, TimeUnit.SECONDS);
  }

  @VisibleForTesting
  protected ReadableByteChannel getChannel(URL url) throws IOException {
    return Channels.newChannel(url.openStream());
  }

  public static String getHash(Hasher hasher, Path path) throws IOException {
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
