// Copyright 2014 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.skyframe;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalFunction.FileType;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;

import javax.annotation.Nullable;

/**
 * Collection of files found while recursively traversing a path.
 *
 * <p>The path may refer to files, symlinks or directories that may or may not exist.
 *
 * <p>Traversing a file or a symlink results in a single {@link ResolvedFile} corresponding to the
 * file or symlink.
 *
 * <p>Traversing a directory results in a collection of {@link ResolvedFile}s for all files and
 * symlinks under it, and in all of its subdirectories. The {@link TraversalRequest} can specify
 * whether to traverse source subdirectories that are packages (have BUILD files in them).
 *
 * <p>Traversing a symlink that points to a directory is the same as traversing a normal directory
 * but the resulting {@link ResolvedFile}s are stored for internal use only (for correct
 * invalidation of this {@link SkyValue}).
 *
 * <p>Editing a file that is part of this traversal, or adding or removing a file in a directory
 * that is part of this traversal, will invalidate this {@link SkyValue}. This also applies to
 * directories that are symlinked to.
 */
public final class RecursiveFilesystemTraversalValue implements SkyValue {
  static final RecursiveFilesystemTraversalValue EMPTY = new RecursiveFilesystemTraversalValue(
      NestedSetBuilder.<ResolvedFile>emptySet(Order.STABLE_ORDER),
      NestedSetBuilder.<ResolvedFile>emptySet(Order.STABLE_ORDER));

  /** The transitive closure of {@link ResolvedFile}s. */
  private final NestedSet<ResolvedFile> resolvedPaths;

  /** The transitive closure of {@link ResolvedFile}s in symlinked directories. */
  private final NestedSet<ResolvedFile> resolvedPathsBehindSymlink;

  RecursiveFilesystemTraversalValue(NestedSet<ResolvedFile> resolvedPaths,
      NestedSet<ResolvedFile> resolvedPathsBehindSymlink) {
    this.resolvedPaths = Preconditions.checkNotNull(resolvedPaths);
    this.resolvedPathsBehindSymlink = Preconditions.checkNotNull(resolvedPathsBehindSymlink);
  }

  static RecursiveFilesystemTraversalValue of(ResolvedFile singleMember) {
    return new RecursiveFilesystemTraversalValue(
        NestedSetBuilder.<ResolvedFile>create(Order.STABLE_ORDER, singleMember),
        NestedSetBuilder.<ResolvedFile>emptySet(Order.STABLE_ORDER));
  }

  /**
   * Retrieves the set of {@link ResolvedFile}s that were found by this traversal.
   *
   * <p>The returned set may be empty if no files were found, or the ones found were to be
   * considered non-existent.
   *
   * <p>The returned set contains symlinks, but not what they point to. If a symlink points to a
   * directory, its contents are *not* included in this set.
   * See {@link #getTransitiveFilesBehindSymlinks()}.
   */
  public NestedSet<ResolvedFile> getTransitiveFiles() {
    return resolvedPaths;
  }

  /** Retrieves the set of {@link ResolvedFile}s that were found in symlinked directories. */
  NestedSet<ResolvedFile> getTransitiveFilesBehindSymlinks() {
    return resolvedPathsBehindSymlink;
  }

  public static SkyKey key(TraversalRequest traversal) {
    return new SkyKey(SkyFunctions.RECURSIVE_FILESYSTEM_TRAVERSAL, traversal);
  }

  /** The parameters that define how to traverse a package or a directory. */
  public static final class TraversalRequest {
    /** The path to traverse; may refer to a file, to a directory or to a symlink. */
    final RootedPath path;

    /**
     * Whether the path is in the output tree.
     *
     * <p>Such paths and all their subdirectories are assumed not to define packages, so package
     * lookup for them is skipped.
     */
    final boolean isGenerated;

    /** Whether traversal should descend into directories that are roots of subpackages. */
    final boolean crossPkgBoundaries;

    /**
     * Whether this is a top-level request.
     *
     * <p>A top-level request is one that was directly requested by a caller of {@link #forFile} or
     * {@link #forPackage}.
     *
     * <p>A non-top-level request is one that was generated during traversal of a subdirectory.
     */
    final boolean isTopLevelRequest;

    @Nullable final String errorInfo;

    private TraversalRequest(RootedPath path, boolean isRootGenerated,
        boolean traverseSubpackages, boolean isTopLevelRequest, @Nullable String errorInfo) {
      this.path = path;
      this.isGenerated = isRootGenerated;
      this.crossPkgBoundaries = traverseSubpackages;
      this.isTopLevelRequest = isTopLevelRequest;
      this.errorInfo = errorInfo;
    }

    private TraversalRequest(Artifact root, boolean isPkg, boolean traverseSubpackages,
        boolean isTopLevelRequest, @Nullable String errorInfo) {
      this(RootedPath.toRootedPath(root.getRoot().getPath(), isPkg
              ? root.getRootRelativePath().getParentDirectory()
              : root.getRootRelativePath()),
          !root.isSourceArtifact(), traverseSubpackages, isTopLevelRequest, errorInfo);
    }

    /**
     * Creates a "top-level" request to traverse a path that the artifact points to.
     *
     * <p>See {@link #isTopLevelRequest}.
     *
     * <p>The artifact may point to a regular file, a directory or to a symlink. These may be
     * source artifacts or generated ones.
     *
     * @param root the file/directory/symlink to traverse. See comments on {@link #path}.
     * @param traverseSubpackages dictates whether a subdirectory that is also a package root
     *     should be traversed or not
     */
    public static TraversalRequest forFile(Artifact root, boolean traverseSubpackages,
        @Nullable String errorInfo) {
      return new TraversalRequest(root, false, traverseSubpackages, true, errorInfo);
    }

    /**
     * Creates a "top-level" request to traverse a package.
     *
     * <p>See {@link #isTopLevelRequest}.
     *
     * @param buildFile the BUILD file of the package
     * @param traverseSubpackages dictates whether a subdirectory that is also a package root
     *     should be traversed or not
     */
    public static TraversalRequest forPackage(Artifact buildFile, boolean traverseSubpackages,
        @Nullable String errorInfo) {
      Preconditions.checkArgument(buildFile.isSourceArtifact(), buildFile);
      return new TraversalRequest(buildFile, true, traverseSubpackages, true, errorInfo);
    }

    private TraversalRequest duplicate(RootedPath newRoot, boolean isTopLevelRequest) {
      return new TraversalRequest(newRoot, isGenerated, crossPkgBoundaries,
          isTopLevelRequest, errorInfo);
    }

    /** Creates a new, non-top-level request to traverse a subdirectory. */
    TraversalRequest forChildEntry(RootedPath newPath) {
      return duplicate(newPath, false);
    }

    /**
     * Creates a new, non-top-level request for a changed root.
     *
     * <p>This method can be used when a package is found out to be under a different root path than
     * originally assumed.
     */
    TraversalRequest forChangedRootPath(Path newRoot) {
      return duplicate(RootedPath.toRootedPath(newRoot, path.getRelativePath()),
          isTopLevelRequest);
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (!(obj instanceof TraversalRequest)) {
        return false;
      }
      TraversalRequest o = (TraversalRequest) obj;
      return path.equals(o.path) && isGenerated == o.isGenerated
          && crossPkgBoundaries == o.crossPkgBoundaries
          && isTopLevelRequest == o.isTopLevelRequest;
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(path, isGenerated, crossPkgBoundaries, isTopLevelRequest);
    }

    @Override
    public String toString() {
      return String.format("TraversalParams(root=%s, is_generated=%d, is_top_level=%d,"
          + " traverse_subpkgs=%d)",
          path, isGenerated ? 1 : 0, isTopLevelRequest ? 1 : 0,
          crossPkgBoundaries ? 1 : 0);
    }
  }

  /**
   * Path and type information about a single file or symlink.
   *
   * <p>The object stores things such as the absolute path of the file or symlink, its exact type
   * and, if it's a symlink, the resolved and unresolved link target paths.
   */
  public abstract static class ResolvedFile {
    private static final class Symlink {
      private final RootedPath linkName;
      private final PathFragment unresolvedLinkTarget;
      // The resolved link target is stored in ResolvedFile.path

      private Symlink(RootedPath linkName, PathFragment unresolvedLinkTarget) {
        this.linkName = Preconditions.checkNotNull(linkName);
        this.unresolvedLinkTarget = Preconditions.checkNotNull(unresolvedLinkTarget);
      }

      @Override
      public boolean equals(Object obj) {
        if (this == obj) {
          return true;
        }
        if (!(obj instanceof Symlink)) {
          return false;
        }
        Symlink o = (Symlink) obj;
        return linkName.equals(o.linkName) && unresolvedLinkTarget.equals(o.unresolvedLinkTarget);
      }

      @Override
      public int hashCode() {
        return Objects.hashCode(linkName, unresolvedLinkTarget);
      }

      @Override
      public String toString() {
        return String.format("Symlink(link_name=%s, unresolved_target=%s)",
            linkName, unresolvedLinkTarget);
      }
    }

    private static final class RegularFile extends ResolvedFile {
      private RegularFile(RootedPath path) {
        super(FileType.FILE, Optional.of(path), Optional.<FileStateValue>absent());
      }

      RegularFile(RootedPath path, FileStateValue metadata) {
        super(FileType.FILE, Optional.of(path), Optional.of(metadata));
      }

      @Override
      public boolean equals(Object obj) {
        if (this == obj) {
          return true;
        }
        if (!(obj instanceof RegularFile)) {
          return false;
        }
        return super.isEqualTo((RegularFile) obj);
      }

      @Override
      public String toString() {
        return String.format("RegularFile(%s)", super.toString());
      }

      @Override
      ResolvedFile stripMetadataForTesting() {
        return new RegularFile(path.get());
      }
    }

    private static final class DanglingSymlink extends ResolvedFile {
      private final Symlink symlink;

      private DanglingSymlink(Symlink symlink) {
        super(FileType.DANGLING_SYMLINK, Optional.<RootedPath>absent(),
            Optional.<FileStateValue>absent());
        this.symlink = symlink;
      }

      DanglingSymlink(RootedPath linkNamePath, PathFragment linkTargetPath,
          FileStateValue metadata) {
        super(FileType.DANGLING_SYMLINK, Optional.<RootedPath>absent(), Optional.of(metadata));
        this.symlink = new Symlink(linkNamePath, linkTargetPath);
      }

      @Override
      public boolean equals(Object obj) {
        if (this == obj) {
          return true;
        }
        if (!(obj instanceof DanglingSymlink)) {
          return false;
        }
        DanglingSymlink o = (DanglingSymlink) obj;
        return super.isEqualTo(o) && symlink.equals(o.symlink);
      }

      @Override
      public int hashCode() {
        return Objects.hashCode(super.hashCode(), symlink);
      }

      @Override
      public String toString() {
        return String.format("DanglingSymlink(%s, %s)", super.toString(), symlink);
      }

      @Override
      ResolvedFile stripMetadataForTesting() {
        return new DanglingSymlink(symlink);
      }
    }

    private static final class SymlinkToFile extends ResolvedFile {
      private final Symlink symlink;

      private SymlinkToFile(RootedPath targetPath, Symlink symlink) {
        super(FileType.SYMLINK_TO_FILE, Optional.of(targetPath), Optional.<FileStateValue>absent());
        this.symlink = symlink;
      }

      SymlinkToFile(RootedPath targetPath, RootedPath linkNamePath,
          PathFragment linkTargetPath, FileStateValue metadata) {
        super(FileType.SYMLINK_TO_FILE, Optional.of(targetPath), Optional.of(metadata));
        this.symlink = new Symlink(linkNamePath, linkTargetPath);
      }

      @Override
      public boolean equals(Object obj) {
        if (this == obj) {
          return true;
        }
        if (!(obj instanceof SymlinkToFile)) {
          return false;
        }
        SymlinkToFile o = (SymlinkToFile) obj;
        return super.isEqualTo(o) && symlink.equals(o.symlink);
      }

      @Override
      public int hashCode() {
        return Objects.hashCode(super.hashCode(), symlink);
      }

      @Override
      public String toString() {
        return String.format("SymlinkToFile(%s, %s)", super.toString(), symlink);
      }

      @Override
      ResolvedFile stripMetadataForTesting() {
        return new SymlinkToFile(path.get(), symlink);
      }
    }

    private static final class SymlinkToDirectory extends ResolvedFile {
      private final Symlink symlink;

      private SymlinkToDirectory(RootedPath targetPath, Symlink symlink) {
        super(FileType.SYMLINK_TO_DIRECTORY, Optional.of(targetPath),
            Optional.<FileStateValue>absent());
        this.symlink = symlink;
      }

      SymlinkToDirectory(RootedPath targetPath, RootedPath linkNamePath,
          PathFragment linkValue, FileStateValue metadata) {
        super(FileType.SYMLINK_TO_DIRECTORY, Optional.of(targetPath), Optional.of(metadata));
        this.symlink = new Symlink(linkNamePath, linkValue);
      }

      @Override
      public boolean equals(Object obj) {
        if (this == obj) {
          return true;
        }
        if (!(obj instanceof SymlinkToDirectory)) {
          return false;
        }
        SymlinkToDirectory o = (SymlinkToDirectory) obj;
        return super.isEqualTo(o) && symlink.equals(o.symlink);
      }

      @Override
      public int hashCode() {
        return Objects.hashCode(super.hashCode(), symlink);
      }

      @Override
      public String toString() {
        return String.format("SymlinkToDirectory(%s, %s)", super.toString(), symlink);
      }

      @Override
      ResolvedFile stripMetadataForTesting() {
        return new SymlinkToDirectory(path.get(), symlink);
      }
    }

    /** Type of the object: file, symlink to a file, or symlink to a directory. */
    private final FileType type;

    /**
     * Path of the file or the resolved target of a symlink.
     *
     * <p>May only be absent for dangling symlinks.
     */
    protected final Optional<RootedPath> path;

    /**
     * Associated metadata.
     *
     * <p>This field must be stored so that this {@link ResolvedFile} is (also) the function of the
     * stat() of the file , but otherwise it is likely not something the consumer of the
     * {@link ResolvedFile} is directly interested in.
     *
     * <p>May only be absent if stripped for tests.
     */
    // TODO(bazel-team): we may need to expose this field when Fileset starts using
    // RecursiveFilesystemTraversalValue to build its output, because the output manifest currently
    // stores mtimes of every file so changing a file's contents, hence its mtime, will result in
    // a different manifest, triggering a rerun of downstream actions.
    // Storing this data will always be required in order to make ResolvedFile dependent on the file
    // contents.
    public final Optional<FileStateValue> metadata;

    private ResolvedFile(FileType type, Optional<RootedPath> path,
        Optional<FileStateValue> metadata) {
      this.type = Preconditions.checkNotNull(type);
      this.path = Preconditions.checkNotNull(path);
      this.metadata = Preconditions.checkNotNull(metadata);
    }

    static ResolvedFile regularFile(RootedPath path, FileStateValue metadata) {
      return new RegularFile(path, metadata);
    }

    static ResolvedFile symlinkToFile(RootedPath targetPath, RootedPath linkNamePath,
        PathFragment linkTargetPath, FileStateValue metadata) {
      return new SymlinkToFile(targetPath, linkNamePath, linkTargetPath, metadata);
    }

    static ResolvedFile symlinkToDirectory(RootedPath targetPath,
        RootedPath linkNamePath, PathFragment linkValue, FileStateValue metadata) {
      return new SymlinkToDirectory(targetPath, linkNamePath, linkValue, metadata);
    }

    static ResolvedFile danglingSymlink(RootedPath linkNamePath, PathFragment linkValue,
        FileStateValue metadata) {
      return new DanglingSymlink(linkNamePath, linkValue, metadata);
    }

    private boolean isEqualTo(ResolvedFile o) {
      return type.equals(o.type) && path.equals(o.path) && metadata.equals(o.metadata);
    }

    @Override
    public abstract boolean equals(Object obj);

    @Override
    public int hashCode() {
      return Objects.hashCode(type, path, metadata);
    }

    @Override
    public String toString() {
      return String.format("path=%s, metadata=%s", path,
          metadata.isPresent() ? Integer.toHexString(metadata.get().hashCode()) : "(stripped)");
    }

    /**
     * Returns a copy of this object with the metadata stripped away.
     *
     * <p>This method should only be used by tests that wish to assert that this
     * {@link ResolvedFile} refers to the expected absolute path and has the expected type, without
     * asserting its actual contents (which the metadata is a function of).
     */
    @VisibleForTesting
    abstract ResolvedFile stripMetadataForTesting();
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof RecursiveFilesystemTraversalValue)) {
      return false;
    }
    RecursiveFilesystemTraversalValue o = (RecursiveFilesystemTraversalValue) obj;
    return resolvedPaths.equals(o.resolvedPaths)
        && resolvedPathsBehindSymlink.equals(o.resolvedPathsBehindSymlink);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(resolvedPaths, resolvedPathsBehindSymlink);
  }
}
