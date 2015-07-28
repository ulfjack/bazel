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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileStatusWithDigest;
import com.google.devtools.build.lib.vfs.FileStatusWithDigestAdapter;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

import javax.annotation.Nullable;

/**
 * Encapsulates the filesystem operations needed to get state for a path. This is at least a
 * 'lstat' to determine what type of file the path is.
 * <ul>
 *   <li> For a non-existent file, the non existence is noted.
 *   <li> For a symlink, the symlink target is noted.
 *   <li> For a directory, the existence is noted.
 *   <li> For a file, the existence is noted, along with metadata about the file (e.g.
 *        file digest). See {@link FileFileStateValue}.
 * <ul>
 *
 * <p>This class is an implementation detail of {@link FileValue} and should not be used outside of
 * {@link FileFunction}. Instead, {@link FileValue} should be used by consumers that care about
 * files.
 *
 * <p>All subclasses must implement {@link #equals} and {@link #hashCode} properly.
 */
@VisibleForTesting
public abstract class FileStateValue implements SkyValue {

  static final FileStateValue DIRECTORY_FILE_STATE_NODE = DirectoryFileStateValue.INSTANCE;
  static final FileStateValue NONEXISTENT_FILE_STATE_NODE = NonexistentFileStateValue.INSTANCE;

  enum Type {
    FILE,
    DIRECTORY,
    SYMLINK,
    NONEXISTENT,
  }

  protected FileStateValue() {
  }

  static FileStateValue create(RootedPath rootedPath,
      @Nullable TimestampGranularityMonitor tsgm) throws InconsistentFilesystemException,
      IOException {
    Path path = rootedPath.asPath();
    // Stat, but don't throw an exception for the common case of a nonexistent file. This still
    // throws an IOException in case any other IO error is encountered.
    FileStatus stat = path.statIfFound(Symlinks.NOFOLLOW);
    if (stat == null) {
      return NONEXISTENT_FILE_STATE_NODE;
    }
    return createWithStatNoFollow(rootedPath, FileStatusWithDigestAdapter.adapt(stat), tsgm);
  }

  static FileStateValue createWithStatNoFollow(RootedPath rootedPath,
      FileStatusWithDigest statNoFollow, @Nullable TimestampGranularityMonitor tsgm)
          throws InconsistentFilesystemException, IOException {
    Path path = rootedPath.asPath();
    if (statNoFollow.isFile()) {
      return FileFileStateValue.fromPath(path, statNoFollow, tsgm);
    } else if (statNoFollow.isDirectory()) {
      return DIRECTORY_FILE_STATE_NODE;
    } else if (statNoFollow.isSymbolicLink()) {
      return new SymlinkFileStateValue(path.readSymbolicLink());
    }
    throw new InconsistentFilesystemException("according to stat, existing path " + path + " is "
        + "neither a file nor directory nor symlink.");
  }

  @VisibleForTesting
  @ThreadSafe
  public static SkyKey key(RootedPath rootedPath) {
    return new SkyKey(SkyFunctions.FILE_STATE, rootedPath);
  }

  abstract Type getType();

  PathFragment getSymlinkTarget() {
    throw new IllegalStateException();
  }

  long getSize() {
    throw new IllegalStateException();
  }

  @Nullable
  byte[] getDigest() {
    throw new IllegalStateException();
  }

  /**
   * Implementation of {@link FileStateValue} for files that exist.
   *
   * <p>A union of (digest, mtime). We use digests only if a fast digest lookup is available from
   * the filesystem. If not, we fall back to mtime-based digests. This avoids the case where Blaze
   * must read all files involved in the build in order to check for modifications in the case
   * where fast digest lookups are not available.
   */
  @ThreadSafe
  private static final class FileFileStateValue extends FileStateValue {
    private final long size;
    // Only needed for empty-file equality-checking. Otherwise is always -1.
    // TODO(bazel-team): Consider getting rid of this special case for empty files.
    private final long mtime;
    @Nullable private final byte[] digest;
    @Nullable private final FileContentsProxy contentsProxy;

    private FileFileStateValue(long size, long mtime, byte[] digest,
        FileContentsProxy contentsProxy) {
      Preconditions.checkState((digest == null) != (contentsProxy == null));
      this.size = size;
      // mtime is forced to be -1 so that we do not accidentally depend on it for non-empty files,
      // which should only be compared using digests.
      this.mtime = size == 0 ? mtime : -1;
      this.digest = digest;
      this.contentsProxy = contentsProxy;
    }

    /**
     * Create a FileFileStateValue instance corresponding to the given existing file.
     * @param stat must be of type "File". (Not a symlink).
     */
    private static FileFileStateValue fromPath(Path path, FileStatusWithDigest stat,
                                        @Nullable TimestampGranularityMonitor tsgm)
        throws InconsistentFilesystemException {
      Preconditions.checkState(stat.isFile(), path);
      try {
        byte[] digest = stat.getDigest();
        if (digest == null) {
          digest = path.getFastDigest();
        }
        if (digest == null) {
          long mtime = stat.getLastModifiedTime();
          // Note that TimestampGranularityMonitor#notifyDependenceOnFileTime is a thread-safe
          // method.
          if (tsgm != null) {
            tsgm.notifyDependenceOnFileTime(path.getPathString(), mtime);
          }
          return new FileFileStateValue(stat.getSize(), stat.getLastModifiedTime(), null,
              FileContentsProxy.create(mtime, stat.getNodeId()));
        } else {
          // We are careful here to avoid putting the value ID into FileMetadata if we already have
          // a digest. Arbitrary filesystems may do weird things with the value ID; a digest is more
          // robust.
          return new FileFileStateValue(stat.getSize(), stat.getLastModifiedTime(), digest, null);
        }
      } catch (IOException e) {
        String errorMessage = e.getMessage() != null
            ? "error '" + e.getMessage() + "'" : "an error";
        throw new InconsistentFilesystemException("'stat' said " + path + " is a file but then we "
            + "later encountered " + errorMessage + " which indicates that " + path + " no longer "
            + "exists. Did you delete it during the build?");
      }
    }

    @Override
    Type getType() {
      return Type.FILE;
    }

    @Override
    long getSize() {
      return size;
    }

    @Override
    @Nullable
    byte[] getDigest() {
      return digest;
    }

    @Override
    public boolean equals(Object obj) {
      if (obj instanceof FileFileStateValue) {
        FileFileStateValue other = (FileFileStateValue) obj;
        return size == other.size && mtime == other.mtime && Arrays.equals(digest, other.digest)
            && Objects.equals(contentsProxy, other.contentsProxy);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return Objects.hash(size, mtime, Arrays.hashCode(digest), contentsProxy);
    }

    @Override
    public String toString() {
      return "[size: " + size + " " + (mtime != -1 ? "mtime: " + mtime : "")
          + (digest != null ? "digest: " + Arrays.toString(digest) : contentsProxy) + "]";
    }
  }

  /** Implementation of {@link FileStateValue} for directories that exist. */
  private static final class DirectoryFileStateValue extends FileStateValue {

    static final DirectoryFileStateValue INSTANCE = new DirectoryFileStateValue();

    private DirectoryFileStateValue() {
    }

    @Override
    Type getType() {
      return Type.DIRECTORY;
    }

    @Override
    public String toString() {
      return "directory";
    }

    // This object is normally a singleton, but deserialization produces copies.
    @Override
    public boolean equals(Object obj) {
      return obj instanceof DirectoryFileStateValue;
    }

    @Override
    public int hashCode() {
      return 7654321;
    }
  }

  /** Implementation of {@link FileStateValue} for symlinks. */
  private static final class SymlinkFileStateValue extends FileStateValue {

    private final PathFragment symlinkTarget;

    private SymlinkFileStateValue(PathFragment symlinkTarget) {
      this.symlinkTarget = symlinkTarget;
    }

    @Override
    Type getType() {
      return Type.SYMLINK;
    }

    @Override
    PathFragment getSymlinkTarget() {
      return symlinkTarget;
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof SymlinkFileStateValue)) {
        return false;
      }
      SymlinkFileStateValue other = (SymlinkFileStateValue) obj;
      return symlinkTarget.equals(other.symlinkTarget);
    }

    @Override
    public int hashCode() {
      return symlinkTarget.hashCode();
    }

    @Override
    public String toString() {
      return "symlink to " + symlinkTarget;
    }
  }

  /** Implementation of {@link FileStateValue} for nonexistent files. */
  private static final class NonexistentFileStateValue extends FileStateValue {

    static final NonexistentFileStateValue INSTANCE = new NonexistentFileStateValue();

    private NonexistentFileStateValue() {
    }

    @Override
    Type getType() {
      return Type.NONEXISTENT;
    }

    @Override
    public String toString() {
      return "nonexistent";
    }

    // This object is normally a singleton, but deserialization produces copies.
    @Override
    public boolean equals(Object obj) {
      return obj instanceof NonexistentFileStateValue;
    }

    @Override
    public int hashCode() {
      return 8765432;
    }
  }
}
