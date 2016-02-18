// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.actions;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Ordering;
import com.google.devtools.build.lib.actions.FilesetTraversalParams.DirectTraversal;
import com.google.devtools.build.lib.actions.FilesetTraversalParams.DirectTraversalRoot;
import com.google.devtools.build.lib.actions.FilesetTraversalParams.PackageBoundaryMode;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.FilesetEntry.SymlinkBehavior;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.RootedPath;

import java.util.Set;

import javax.annotation.Nullable;

/** Factory of {@link FilesetTraversalParams}. */
public final class FilesetTraversalParamsFactory {

  /**
   * Creates parameters for a recursive traversal request in a package.
   *
   * <p>"Recursive" means that a directory is traversed along with all of its subdirectories. Such
   * a traversal is created when FilesetEntry.files is unspecified.
   *
   * @param ownerLabel the rule that created this object
   * @param buildFile path of the BUILD file of the package to traverse
   * @param destPath path in the Fileset's output directory that will be the root of files found
   *     in this directory
   * @param excludes optional; set of files directly under this package's directory to exclude;
   *     files in subdirectories cannot be excluded
   * @param symlinkBehaviorMode what to do with symlinks
   * @param pkgBoundaryMode what to do when the traversal hits a subdirectory that is also a
   *     subpackage (contains a BUILD file)
   */
  public static FilesetTraversalParams recursiveTraversalOfPackage(Label ownerLabel,
      Artifact buildFile, PathFragment destPath, @Nullable Set<String> excludes,
      SymlinkBehavior symlinkBehaviorMode, PackageBoundaryMode pkgBoundaryMode) {
    Preconditions.checkState(buildFile.isSourceArtifact(), "%s", buildFile);
    return new DirectoryTraversalParams(ownerLabel, DirectTraversalRootImpl.forPackage(buildFile),
        true, destPath, excludes, symlinkBehaviorMode, pkgBoundaryMode, true, false);
  }

  /**
   * Creates parameters for a recursive traversal request in a directory.
   *
   * <p>"Recursive" means that a directory is traversed along with all of its subdirectories. Such
   * a traversal is created when FilesetEntry.files is unspecified.
   *
   * @param ownerLabel the rule that created this object
   * @param directoryToTraverse path of the directory to traverse
   * @param destPath path in the Fileset's output directory that will be the root of files found
   *     in this directory
   * @param excludes optional; set of files directly below this directory to exclude; files in
   *     subdirectories cannot be excluded
   * @param symlinkBehaviorMode what to do with symlinks
   * @param pkgBoundaryMode what to do when the traversal hits a subdirectory that is also a
   *     subpackage (contains a BUILD file)
   */
  public static FilesetTraversalParams recursiveTraversalOfDirectory(Label ownerLabel,
      Artifact directoryToTraverse, PathFragment destPath, @Nullable Set<String> excludes,
      SymlinkBehavior symlinkBehaviorMode, PackageBoundaryMode pkgBoundaryMode) {
    return new DirectoryTraversalParams(ownerLabel,
        DirectTraversalRootImpl.forFileOrDirectory(directoryToTraverse), false, destPath,
        excludes, symlinkBehaviorMode, pkgBoundaryMode, true,
        !directoryToTraverse.isSourceArtifact());
  }

  /**
   * Creates parameters for a file traversal request.
   *
   * <p>Such a traversal is created for every entry in FilesetEntry.files, when it is specified.
   *
   * @param ownerLabel the rule that created this object
   * @param fileToTraverse the file to traverse; "traversal" means that if this file is actually a
   *     directory or a symlink to one then it'll be traversed as one
   * @param destPath path in the Fileset's output directory that will be the name of this file's
   *     respective symlink there, or the root of files found (in case this is a directory)
   * @param symlinkBehaviorMode what to do with symlinks
   * @param pkgBoundaryMode what to do when the traversal hits a subdirectory that is also a
   *     subpackage (contains a BUILD file)
   */
  public static FilesetTraversalParams fileTraversal(Label ownerLabel, Artifact fileToTraverse,
      PathFragment destPath, SymlinkBehavior symlinkBehaviorMode,
      PackageBoundaryMode pkgBoundaryMode) {
    return new DirectoryTraversalParams(ownerLabel,
        DirectTraversalRootImpl.forFileOrDirectory(fileToTraverse), false, destPath, null,
        symlinkBehaviorMode, pkgBoundaryMode, false, !fileToTraverse.isSourceArtifact());
  }

  /**
   * Creates traversal request parameters for a FilesetEntry wrapping another Fileset.
   *
   * @param ownerLabel the rule that created this object
   * @param nested the traversal params that were used for the nested (inner) Fileset
   * @param destDir path in the Fileset's output directory that will be the root of files coming
   *     from the nested Fileset
   * @param excludes optional; set of files directly below (not in a subdirectory of) the nested
   *     Fileset that should be excluded from the outer Fileset
   */
  public static FilesetTraversalParams nestedTraversal(Label ownerLabel,
      FilesetTraversalParams nested, PathFragment destDir, @Nullable Set<String> excludes) {
    // When srcdir is another Fileset, then files must be null so strip_prefix must also be null.
    return new NestedTraversalParams(ownerLabel, nested, destDir, excludes);
  }

  private abstract static class ParamsCommon implements FilesetTraversalParams {
    private final Label ownerLabel;
    private final PathFragment destDir;
    private final ImmutableSet<String> excludes;

    ParamsCommon(Label ownerLabel, PathFragment destDir, @Nullable Set<String> excludes) {
      this.ownerLabel = ownerLabel;
      this.destDir = destDir;
      if (excludes == null) {
        this.excludes = ImmutableSet.<String>of();
      } else {
        // Order the set for the sake of deterministic fingerprinting.
        this.excludes = ImmutableSet.copyOf(Ordering.natural().immutableSortedCopy(excludes));
      }
    }

    @Override
    public Label getOwnerLabel() {
      return ownerLabel;
    }

    @Override
    public Set<String> getExcludedFiles() {
      return excludes;
    }

    @Override
    public PathFragment getDestPath() {
      return destDir;
    }

    protected final void commonFingerprint(Fingerprint fp) {
      fp.addPath(destDir);
      if (!excludes.isEmpty()) {
        fp.addStrings(excludes);
      }
    }
  }

  private static final class DirectTraversalImpl implements DirectTraversal {
    private final DirectTraversalRoot root;
    private final boolean isPackage;
    private final boolean followSymlinks;
    private final PackageBoundaryMode pkgBoundaryMode;
    private final boolean isRecursive;
    private final boolean isGenerated;

    DirectTraversalImpl(DirectTraversalRoot root, boolean isPackage, boolean followSymlinks,
        PackageBoundaryMode pkgBoundaryMode, boolean isRecursive, boolean isGenerated) {
      this.root = root;
      this.isPackage = isPackage;
      this.followSymlinks = followSymlinks;
      this.pkgBoundaryMode = pkgBoundaryMode;
      this.isRecursive = isRecursive;
      this.isGenerated = isGenerated;
    }

    @Override
    public DirectTraversalRoot getRoot() {
      return root;
    }

    @Override
    public boolean isPackage() {
      return isPackage;
    }

    @Override
    public boolean isRecursive() {
      return isRecursive;
    }

    @Override
    public boolean isGenerated() {
      return isGenerated;
    }

    @Override
    public boolean isFollowingSymlinks() {
      return followSymlinks;
    }

    @Override
    public PackageBoundaryMode getPackageBoundaryMode() {
      return pkgBoundaryMode;
    }

    void fingerprint(Fingerprint fp) {
      fp.addPath(root.asRootedPath().asPath());
      fp.addBoolean(isPackage);
      fp.addBoolean(followSymlinks);
      fp.addBoolean(isRecursive);
      fp.addBoolean(isGenerated);
      pkgBoundaryMode.fingerprint(fp);
    }
  }

  private static final class DirectoryTraversalParams extends ParamsCommon {
    private final DirectTraversalImpl traversal;

    DirectoryTraversalParams(Label ownerLabel,
        DirectTraversalRoot root,
        boolean isPackage,
        PathFragment destPath,
        @Nullable Set<String> excludes,
        SymlinkBehavior symlinkBehaviorMode,
        PackageBoundaryMode pkgBoundaryMode,
        boolean isRecursive,
        boolean isGenerated) {
      super(ownerLabel, destPath, excludes);
      traversal = new DirectTraversalImpl(root, isPackage,
          symlinkBehaviorMode == SymlinkBehavior.DEREFERENCE, pkgBoundaryMode, isRecursive,
          isGenerated);
    }

    @Override
    public Optional<DirectTraversal> getDirectTraversal() {
      return Optional.<DirectTraversal>of(traversal);
    }

    @Override
    public Optional<FilesetTraversalParams> getNestedTraversal() {
      return Optional.absent();
    }

    @Override
    public void fingerprint(Fingerprint fp) {
      commonFingerprint(fp);
      traversal.fingerprint(fp);
    }
  }

  private static final class NestedTraversalParams extends ParamsCommon {
    private final FilesetTraversalParams nested;

    public NestedTraversalParams(Label ownerLabel, FilesetTraversalParams nested,
        PathFragment destDir, @Nullable Set<String> excludes) {
      super(ownerLabel, destDir, excludes);
      this.nested = nested;
    }

    @Override
    public Optional<DirectTraversal> getDirectTraversal() {
      return Optional.absent();
    }

    @Override
    public Optional<FilesetTraversalParams> getNestedTraversal() {
      return Optional.of(nested);
    }

    @Override
    public void fingerprint(Fingerprint fp) {
      commonFingerprint(fp);
      nested.fingerprint(fp);
    }
  }

  private static final class DirectTraversalRootImpl implements DirectTraversalRoot {
    private final Path rootDir;
    private final PathFragment relativeDir;

    static DirectTraversalRoot forPackage(Artifact buildFile) {
      return new DirectTraversalRootImpl(buildFile.getRoot().getPath(),
          buildFile.getRootRelativePath().getParentDirectory());
    }

    static DirectTraversalRoot forFileOrDirectory(Artifact fileOrDirectory) {
      return new DirectTraversalRootImpl(fileOrDirectory.getRoot().getPath(),
          fileOrDirectory.getRootRelativePath());
    }

    private DirectTraversalRootImpl(Path rootDir, PathFragment relativeDir) {
      this.rootDir = rootDir;
      this.relativeDir = relativeDir;
    }

    @Override
    public Path getRootPart() {
      return rootDir;
    }

    @Override
    public PathFragment getRelativePart() {
      return relativeDir;
    }

    @Override
    public RootedPath asRootedPath() {
      return RootedPath.toRootedPath(rootDir, relativeDir);
    }
  }
}
