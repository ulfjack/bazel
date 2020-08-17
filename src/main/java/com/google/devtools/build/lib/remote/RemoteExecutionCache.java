// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote;

import static com.google.devtools.build.lib.remote.util.Utils.getFromFuture;
import static java.lang.String.format;

import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.remote.common.RemoteCacheClient;
import com.google.devtools.build.lib.remote.merkletree.MerkleTree;
import com.google.devtools.build.lib.remote.merkletree.MerkleTree.PathOrBytes;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.protobuf.Message;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A {@link RemoteCache} with additional functionality needed for remote execution. */
public class RemoteExecutionCache extends RemoteCache {

  public RemoteExecutionCache(
      RemoteCacheClient protocolImpl, RemoteOptions options, DigestUtil digestUtil) {
    super(protocolImpl, options, digestUtil);
  }

  /**
   * Ensures that the tree structure of the inputs, the input files themselves, and the command are
   * available in the remote cache, such that the tree can be reassembled and executed on another
   * machine given the root digest.
   *
   * <p>The cache may check whether files or parts of the tree structure are already present, and do
   * not need to be uploaded again.
   *
   * <p>Note that this method is only required for remote execution, not for caching itself.
   * However, remote execution uses a cache to store input files, and that may be a separate
   * end-point from the executor itself, so the functionality lives here.
   */
  public void ensureInputsPresent(MerkleTree merkleTree, Map<Digest, Message> additionalInputs, boolean checkAll)
      throws IOException, InterruptedException {
    Iterable<Digest> allDigests = Iterables.concat(merkleTree.getAllDigests(), additionalInputs.keySet());
    if (checkAll || !options.experimentalRemoteDeduplicateUploads) {
      ImmutableSet<Digest> missingDigests = getFromFuture(cacheProtocol.findMissingDigests(allDigests));

      List<ListenableFuture<Void>> futures = new ArrayList<>();
      for (Digest missingDigest : missingDigests) {
        futures.add(uploadBlob(missingDigest, merkleTree, additionalInputs));
      }

      waitForBulkTransfer(futures, /* cancelRemainingOnInterrupt=*/ false);
    } else {
      Map<Digest, SettableFuture<Void>> ownedUnknownDigests = new HashMap<>();
      Map<Digest, ListenableFuture<Void>> unownedUnknownDigests = new HashMap<>();
      ImmutableSet<Digest> missingDigests;
      for (Digest d : ImmutableSet.copyOf(allDigests)) {
        ListenableFuture<Void> future = uploadFutures.get(d);
        if (future == null) {
          SettableFuture<Void> settableFuture = SettableFuture.create();
          ListenableFuture<Void> previous = uploadFutures.putIfAbsent(d, settableFuture);
          if (previous == null) {
            ownedUnknownDigests.put(d, settableFuture);
          } else {
            unownedUnknownDigests.put(d, previous);
          }
        } else {
          unownedUnknownDigests.put(d, future);
        }
      }
      try {
        missingDigests = getFromFuture(cacheProtocol.findMissingDigests(ownedUnknownDigests.keySet()));
      } catch (IOException | InterruptedException e) {
        for (SettableFuture<Void> future : ownedUnknownDigests.values()) {
          future.cancel(false);
        }
        throw e;
      }

      for (Map.Entry<Digest, SettableFuture<Void>> e : ownedUnknownDigests.entrySet()) {
        Digest missingDigest = e.getKey();
        SettableFuture<Void> future = e.getValue();
        if (!missingDigests.contains(missingDigest)) {
          future.set(null);
          continue;
        }

        future.setFuture(uploadBlob(missingDigest, merkleTree, additionalInputs));
      }

      waitForBulkTransfer(ownedUnknownDigests.values(), /* cancelRemainingOnInterrupt=*/ false);
      waitForBulkTransfer(unownedUnknownDigests.values(), /* cancelRemainingOnInterrupt=*/ false);
    }
  }

  private ListenableFuture<Void> uploadBlob(
      Digest digest, MerkleTree merkleTree, Map<Digest, Message> additionalInputs) {
    Directory node = merkleTree.getDirectoryByDigest(digest);
    if (node != null) {
      return cacheProtocol.uploadBlob(digest, node.toByteString());
    }

    PathOrBytes file = merkleTree.getFileByDigest(digest);
    if (file != null) {
      if (file.getBytes() != null) {
        return cacheProtocol.uploadBlob(digest, file.getBytes());
      }
      return cacheProtocol.uploadFile(digest, file.getPath());
    }

    Message message = additionalInputs.get(digest);
    if (message != null) {
      return cacheProtocol.uploadBlob(digest, message.toByteString());
    }

    return Futures.immediateFailedFuture(
        new IOException(
            format(
                "findMissingDigests returned a missing digest that has not been requested: %s",
                digest)));
  }
}
