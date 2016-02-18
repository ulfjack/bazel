// Copyright 2014 The Bazel Authors. All rights reserved.
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
import com.google.devtools.build.lib.actions.FilesetTraversalParams.PackageBoundaryMode;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalFunction.DanglingSymlinkException;
import com.google.devtools.build.lib.skyframe.RecursiveFilesystemTraversalFunction.FileType;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;

import java.util.regex.Pattern;

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
 * <p>Traversing a symlink that points to a directory is the same as traversing a normal directory.
 * The paths in the result will not be resolved; the files will be listed under the symlink, as if
 * it was the actual directory they reside in.
 *
 * <p>Editing a file that is part of this traversal, or adding or removing a file in a directory
 * that is part of this traversal, will invalidate this {@link SkyValue}. This also applies to
 * directories that are symlinked to.
 */
public final class RecursiveFilesystemTraversalValue implements SkyValue {
  static final RecursiveFilesystemTraversalValue EMPTY = new RecursiveFilesystemTraversalValue(
      Optional.<ResolvedFile>absent(),
      NestedSetBuilder.<ResolvedFile>emptySet(Order.STABLE_ORDER));

  /** The root of the traversal. May only be absent for the {@link #EMPTY} instance. */
  private final Optional<ResolvedFile> resolvedRoot;

  /** The transitive closure of {@link ResolvedFile}s. */
  private final NestedSet<ResolvedFile> resolvedPaths;

  private RecursiveFilesystemTraversalValue(Optional<ResolvedFile> resolvedRoot,
      NestedSet<ResolvedFile> resolvedPaths) {
    this.resolvedRoot = Preconditions.checkNotNull(resolvedRoot);
    this.resolvedPaths = Preconditions.checkNotNull(resolvedPaths);
  }

  static RecursiveFilesystemTraversalValue of(ResolvedFile resolvedRoot,
      NestedSet<ResolvedFile> resolvedPaths) {
    if (resolvedPaths.isEmpty()) {
      return EMPTY;
    } else {
      return new RecursiveFilesystemTraversalValue(Optional.of(resolvedRoot), resolvedPaths);
    }
  }

  static RecursiveFilesystemTraversalValue of(ResolvedFile singleMember) {
    return new RecursiveFilesystemTraversalValue(Optional.of(singleMember),
        NestedSetBuilder.<ResolvedFile>create(Order.STABLE_ORDER, singleMember));
  }

  /** Returns the root of the traversal; absent only for the {@link #EMPTY} instance. */
  public Optional<ResolvedFile> getResolvedRoot() {
    return resolvedRoot;
  }

  /**
   * Retrieves the set of {@link ResolvedFile}s that were found by this traversal.
   *
   * <p>The returned set may be empty if no files were found, or the ones found were to be
   * considered non-existent. Unless it's empty, the returned set always includes the
   * {@link #getResolvedRoot() resolved root}.
   *
   * <p>The returned set also includes symlinks. If a symlink points to a directory, its contents
   * are also included in this set, and their path will start with the symlink's path, just like on
   * a usual Unix file system.
   */
  public NestedSet<ResolvedFile> getTransitiveFiles() {
    return resolvedPaths;
  }

  public static SkyKey key(TraversalRequest traversal) {
    return new SkyKey(SkyFunctions.RECURSIVE_FILESYSTEM_TRAVERSAL, traversal);
  }

  /** The parameters of a file or directory traversal. */
  public static final class TraversalRequest {

    /** The path to start the traversal from; may be a file, a directory or a symlink. */
    final RootedPath path;

    /**
     * Whether the path is in the output tree.
     *
     * <p>Such paths and all their subdirectories are assumed not to define packages, so package
     * lookup for them is skipped.
     */
    final boolean isGenerated;

    /** Whether traversal should descend into directories that are roots of subpackages. */
    final PackageBoundaryMode crossPkgBoundaries;

    /**
     * Whether to skip checking if the root (if it's a directory) contains a BUILD file.
     *
     * <p>Such directories are not considered to be packages when this flag is true. This needs to
     * be true in order to traverse directories of packages, but should be false for <i>their</i>
     * subdirectories.
     */
    final boolean skipTestingForSubpackage;

    /** A pattern that files must match to be included in this traversal (may be null.) */
    @Nullable
    final Pattern pattern;

    /** Information to be attached to any error messages that may be reported. */
    @Nullable final String errorInfo;

    public TraversalRequest(RootedPath path, boolean isRootGenerated,
        PackageBoundaryMode crossPkgBoundaries, boolean skipTestingForSubpackage,
        @Nullable String errorInfo, @Nullable Pattern pattern) {
      this.path = path;
      this.isGenerated = isRootGenerated;
      this.crossPkgBoundaries = crossPkgBoundaries;
      this.skipTestingForSubpackage = skipTestingForSubpackage;
      this.errorInfo = errorInfo;
      this.pattern = pattern;
    }

    private TraversalRequest duplicate(RootedPath newRoot, boolean newSkipTestingForSubpackage) {
      return new TraversalRequest(newRoot, isGenerated, crossPkgBoundaries,
          newSkipTestingForSubpackage, errorInfo, pattern);
    }

    /** Creates a new request to traverse a child element in the current directory (the root). */
    TraversalRequest forChildEntry(RootedPath newPath) {
      return duplicate(newPath, false);
    }

    /**
     * Creates a new request for a changed root.
     *
     * <p>This method can be used when a package is found out to be under a different root path than
     * originally assumed.
     */
    TraversalRequest forChangedRootPath(Path newRoot) {
      return duplicate(RootedPath.toRootedPath(newRoot, path.getRelativePath()),
          skipTestingForSubpackage);
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
          && skipTestingForSubpackage == o.skipTestingForSubpackage
          && Objects.equal(pattern, o.pattern);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(path, isGenerated, crossPkgBoundaries, skipTestingForSubpackage,
          pattern);
    }

    @Override
    public String toString() {
      return String.format(
          "TraversalParams(root=%s, is_generated=%d, skip_testing_for_subpkg=%d,"
          + " pkg_boundaries=%s, pattern=%s)", path, isGenerated ? 1 : 0,
          skipTestingForSubpackage ? 1 : 0, crossPkgBoundaries,
          pattern == null ? "null" : pattern.pattern());
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

      PathFragment getNameInSymlinkTree() {
        return linkName.getRelativePath();
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

      @Override
      public PathFragment getNameInSymlinkTree() {
        return path.get().getRelativePath();
      }

      @Override
      public PathFragment getTargetInSymlinkTree(boolean followSymlinks) {
        return path.get().asPath().asFragment();
      }
    }

    private static final class Directory extends ResolvedFile {
      Directory(RootedPath path) {
        super(FileType.DIRECTORY, Optional.of(path), Optional.<FileStateValue>of(
            FileStateValue.DIRECTORY_FILE_STATE_NODE));
      }

      @Override
      public boolean equals(Object obj) {
        if (this == obj) {
          return true;
        }
        if (!(obj instanceof Directory)) {
          return false;
        }
        return super.isEqualTo((Directory) obj);
      }

      @Override
      public String toString() {
        return String.format("Directory(%s)", super.toString());
      }

      @Override
      ResolvedFile stripMetadataForTesting() {
        return this;
      }

      @Override
      public PathFragment getNameInSymlinkTree() {
        return path.get().getRelativePath();
      }

      @Override
      public PathFragment getTargetInSymlinkTree(boolean followSymlinks) {
        return path.get().asPath().asFragment();
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

      @Override
      public PathFragment getNameInSymlinkTree() {
        return symlink.getNameInSymlinkTree();
      }

      @Override
      public PathFragment getTargetInSymlinkTree(boolean followSymlinks)
          throws DanglingSymlinkException {
        if (followSymlinks) {
          throw new DanglingSymlinkException(symlink.linkName.asPath().getPathString(),
              symlink.unresolvedLinkTarget.getPathString());
        } else {
          return symlink.unresolvedLinkTarget;
        }
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

      @Override
      public PathFragment getNameInSymlinkTree() {
        return symlink.getNameInSymlinkTree();
      }

      @Override
      public PathFragment getTargetInSymlinkTree(boolean followSymlinks) {
        return followSymlinks ? path.get().asPath().asFragment() : symlink.unresolvedLinkTarget;
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

      @Override
      public PathFragment getNameInSymlinkTree() {
        return symlink.getNameInSymlinkTree();
      }

      @Override
      public PathFragment getTargetInSymlinkTree(boolean followSymlinks) {
        return followSymlinks ? path.get().asPath().asFragment() : symlink.unresolvedLinkTarget;
      }
    }

    /** Type of the entity under {@link #path}. */
    final FileType type;

    /**
     * Path of the file, directory or resolved target of the symlink.
     *
     * <p>May only be absent for dangling symlinks.
     */
    protected final Optional<RootedPath> path;

    /**
     * Associated metadata.
     *
     * <p>This field must be stored so that this {@link ResolvedFile} is (also) the function of the
     * stat() of the file, but otherwise it is likely not something the consumer of the
     * {@link ResolvedFile} is directly interested in.
     *
     * <p>May only be absent if stripped for tests.
     */
    final Optional<FileStateValue> metadata;

    private ResolvedFile(FileType type, Optional<RootedPath> path,
        Optional<FileStateValue> metadata) {
      this.type = Preconditions.checkNotNull(type);
      this.path = Preconditions.checkNotNull(path);
      this.metadata = Preconditions.checkNotNull(metadata);
    }

    static ResolvedFile regularFile(RootedPath path, FileStateValue metadata) {
      return new RegularFile(path, metadata);
    }

    static ResolvedFile directory(RootedPath path) {
      return new Directory(path);
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
      return String.format("type=%s, path=%s, metadata=%s", type, path,
          metadata.isPresent() ? Integer.toHexString(metadata.get().hashCode()) : "(stripped)");
    }

    /**
     * Returns the path of the Fileset-output symlink relative to the output directory.
     *
     * <p>The path should contain the FilesetEntry-specific destination directory (if any) and
     * should have necessary prefixes stripped (if any).
     */
    public abstract PathFragment getNameInSymlinkTree();

    /**
     * Returns the path of the symlink target.
     *
     * @throws DanglingSymlinkException if the target cannot be resolved because the symlink is
     *     dangling
     */
    public abstract PathFragment getTargetInSymlinkTree(boolean followSymlinks)
        throws DanglingSymlinkException;

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
    return resolvedRoot.equals(o.resolvedRoot) && resolvedPaths.equals(o.resolvedPaths);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(resolvedRoot, resolvedPaths);
  }
}
