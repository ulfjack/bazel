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
package com.google.devtools.build.lib.rules.android;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Root;
import com.google.devtools.build.lib.analysis.TransitiveInfoProvider;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.vfs.PathFragment;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * An Android target provider to provide Android-specific info to IDEs.
 */
@Immutable
public final class AndroidIdeInfoProvider implements TransitiveInfoProvider {
  /** Represents a directory that contains sources, generated or otherwise, for an IDE.*/
  @Immutable
  public static class SourceDirectory {
    final PathFragment relativePath;
    final PathFragment rootPath;
    final boolean isSource;

    public SourceDirectory(PathFragment rootPath, PathFragment relativePath, boolean isSource) {
      this.rootPath = rootPath;
      this.relativePath = relativePath;
      this.isSource = isSource;
    }

   /**
    * The root relative path, {@link Artifact#getRootRelativePath()}.
    */
    public PathFragment getRelativePath() {
      return relativePath;
    }

    /**
     * The absolute path of the root that contains this directory, {@link Root#getPath()}.
     */
    public PathFragment getRootPath() {
      return rootPath;
    }

    /** Indicates if the directory is in the gen files tree. */
    public boolean isSource() {
      return isSource;
    }

    @Override
    public int hashCode() {
      return Objects.hash(relativePath, rootPath, isSource);
    }

    @Override
    public boolean equals(Object other) {
      if (other instanceof SourceDirectory) {
        SourceDirectory otherDir = (SourceDirectory) other;
        return Objects.equals(rootPath, otherDir.rootPath)
            && Objects.equals(relativePath, otherDir.relativePath)
            && Objects.equals(isSource, otherDir.isSource);
      }
      return false;
    }

    @Override
    public String toString() {
      return "SourceDirectory [relativePath=" + relativePath + ", rootPath=" + rootPath
          + ", isSource=" + isSource + "]";
    }
  }

  /**
   * Builder for {@link AndroidIdeInfoProvider}
   */
  public static class Builder {
    private Artifact manifest = null;
    private Artifact generatedManifest = null;
    private Artifact apk = null;
    private Artifact idlClassJar = null;
    private Artifact idlSourceJar = null;
    private final Set<SourceDirectory> resourceDirs = new LinkedHashSet<>();
    private final Set<SourceDirectory> assetDirs = new LinkedHashSet<>();
    private final Set<SourceDirectory> idlDirs = new LinkedHashSet<>();
    private final Set<Artifact> idlSrcs = new LinkedHashSet<>();
    private final Set<Artifact> idlGeneratedJavaFiles = new LinkedHashSet<>();
    private final Set<Artifact> apksUnderTest = new LinkedHashSet<>();

    public AndroidIdeInfoProvider build() {
      return new AndroidIdeInfoProvider(
          manifest,
          generatedManifest,
          apk,
          idlClassJar,
          idlSourceJar,
          ImmutableList.copyOf(assetDirs),
          ImmutableList.copyOf(resourceDirs),
          ImmutableList.copyOf(idlDirs),
          ImmutableList.copyOf(idlSrcs),
          ImmutableList.copyOf(idlGeneratedJavaFiles),
          ImmutableList.copyOf(apksUnderTest));
    }

    public Builder setApk(Artifact apk) {
      Preconditions.checkState(this.apk == null);
      this.apk = apk;
      return this;
    }

    public Builder setManifest(Artifact manifest) {
      Preconditions.checkState(this.manifest == null);
      this.manifest = manifest;
      return this;
    }

    public Builder setGeneratedManifest(Artifact manifest) {
      Preconditions.checkState(this.generatedManifest == null);
      this.generatedManifest = manifest;
      return this;
    }

    public Builder setIdlClassJar(@Nullable Artifact idlClassJar) {
      Preconditions.checkState(this.idlClassJar == null);
      this.idlClassJar = idlClassJar;
      return this;
    }

    public Builder setIdlSourceJar(@Nullable Artifact idlSourceJar) {
      Preconditions.checkState(this.idlSourceJar == null);
      this.idlSourceJar = idlSourceJar;
      return this;
    }

    /**
     * Add "idl_srcs" contents.
     */
    public Builder addIdlSrcs(Collection<Artifact> idlSrcs) {
      this.idlSrcs.addAll(idlSrcs);
      addIdlDirs(idlSrcs);
      return this;
    }

    /**
     * Add the java files generated from "idl_srcs".
     */
    public Builder addIdlGeneratedJavaFiles(Collection<Artifact> idlGeneratedJavaFiles) {
      this.idlGeneratedJavaFiles.addAll(idlGeneratedJavaFiles);
      return this;
    }

    /**
     * Add "idl_parcelables" contents.
     */
    public Builder addIdlParcelables(Collection<Artifact> idlParcelables) {
      addIdlDirs(idlParcelables);
      return this;
    }

    private void addIdlDirs(Collection<Artifact> idlArtifacts) {
      for (Artifact idl : idlArtifacts) {
        this.idlDirs.add(
            new SourceDirectory(
                idl.getRoot().getPath().asFragment(),
                idl.getRootRelativePath().getParentDirectory(),
                idl.isSourceArtifact()));
      }
    }

    public Builder addAllResources(Collection<SourceDirectory> resources) {
      resourceDirs.addAll(resources);
      return this;
    }

    public Builder addAllAssets(Collection<SourceDirectory> assets) {
      assetDirs.addAll(assets);
      return this;
    }

    public Builder addResourceSource(Artifact resource) {
      resourceDirs.add(
          new SourceDirectory(
              resource.getRoot().getPath().asFragment(),
              trimTo(
                  resource.getRootRelativePath(),
                  LocalResourceContainer.Builder.findResourceDir(resource)),
              resource.isSourceArtifact()));
      return this;
    }

    public Builder addResourceSources(Collection<Artifact> resources) {
      for (Artifact resource : resources) {
        addResourceSource(resource);
      }
      return this;
    }

    public Builder addAssetSources(Collection<Artifact> assets, PathFragment assetDir) {
      for (Artifact asset : assets) {
        addAssetSource(asset, assetDir);
      }
      return this;
    }

    public Builder addAssetSource(Artifact asset, PathFragment assetDir) {
      assetDirs.add(
          new SourceDirectory(
              asset.getRoot().getPath().asFragment(),
              trimTo(asset.getRootRelativePath(), assetDir),
              asset.isSourceArtifact()));
      return this;
    }

    public Builder addAllApksUnderTest(Iterable<Artifact> apks) {
      Iterables.addAll(apksUnderTest, apks);
      return this;
    }

    /**
     * Finds the rightmost occurrence of the needle and returns subfragment of the haystack from
     * left to the end of the occurrence inclusive of the needle.
     *
     * <pre>
     * `Example:
     *   Given the haystack:
     *     res/research/handwriting/res/values/strings.xml
     *   And the needle:
     *     res
     *   Returns:
     *     res/research/handwriting/res
     * </pre>
     */
    private static PathFragment trimTo(PathFragment haystack, PathFragment needle) {
      if (needle.equals(PathFragment.EMPTY_FRAGMENT)) {
        return haystack;
      }
      // Compute the overlap offset for duplicated parts of the needle.
      int[] overlap = new int[needle.segmentCount() + 1];
      // Start overlap at -1, as it will cancel out the increment in the search.
      // See http://en.wikipedia.org/wiki/Knuth%E2%80%93Morris%E2%80%93Pratt_algorithm for the
      // details.
      overlap[0] = -1;
      for (int i = 0, j = -1; i < needle.segmentCount(); j++, i++, overlap[i] = j) {
        while (j >= 0 && !needle.getSegment(i).equals(needle.getSegment(j))) {
          // Walk the overlap until the bound is found.
          j = overlap[j];
        }
      }
      // TODO(corysmith): reverse the search algorithm.
      // Keep the index of the found so that the rightmost index is taken.
      int found = -1;
      for (int i = 0, j = 0; i < haystack.segmentCount(); i++) {

        while (j >= 0 && !haystack.getSegment(i).equals(needle.getSegment(j))) {
          // Not matching, walk the needle index to attempt another match.
          j = overlap[j];
        }
        j++;
        // Needle index is exhausted, so the needle must match.
        if (j == needle.segmentCount()) {
          // Record the found index + 1 to be inclusive of the end index.
          found = i + 1;
          // Subtract one from the needle index to restart the search process
          j = j - 1;
        }
      }
      if (found != -1) {
        // Return the subsection of the haystack.
        return haystack.subFragment(0, found);
      }
      throw new IllegalArgumentException(String.format("%s was not found in %s", needle, haystack));
    }
  }

  private final Artifact manifest;
  private final Artifact generatedManifest;
  private final Artifact signedApk;
  @Nullable private final Artifact idlClassJar;
  @Nullable private final Artifact idlSourceJar;
  private final ImmutableCollection<SourceDirectory> resourceDirs;
  private final ImmutableCollection<SourceDirectory> assetDirs;
  private final ImmutableCollection<SourceDirectory> idlImports;
  private final ImmutableCollection<Artifact> idlSrcs;
  private final ImmutableCollection<Artifact> idlGeneratedJavaFiles;
  private final ImmutableCollection<Artifact> apksUnderTest;

  AndroidIdeInfoProvider(@Nullable Artifact manifest,
      @Nullable Artifact generatedManifest,
      @Nullable Artifact signedApk,
      @Nullable Artifact idlClassJar,
      @Nullable Artifact idlSourceJar,
      ImmutableCollection<SourceDirectory> assetDirs,
      ImmutableCollection<SourceDirectory> resourceDirs,
      ImmutableCollection<SourceDirectory> idlImports,
      ImmutableCollection<Artifact> idlSrcs,
      ImmutableCollection<Artifact> idlGeneratedJavaFiles,
      ImmutableCollection<Artifact> apksUnderTest) {
    this.manifest = manifest;
    this.generatedManifest = generatedManifest;
    this.signedApk = signedApk;
    this.idlClassJar = idlClassJar;
    this.idlSourceJar = idlSourceJar;
    this.assetDirs = assetDirs;
    this.resourceDirs = resourceDirs;
    this.idlImports = idlImports;
    this.idlSrcs = idlSrcs;
    this.idlGeneratedJavaFiles = idlGeneratedJavaFiles;
    this.apksUnderTest = apksUnderTest;
  }

  /** Returns the direct AndroidManifest. */
  @Nullable
  public Artifact getManifest() {
    return manifest;
  }

  /** Returns the direct generated AndroidManifest. */
  @Nullable
  public Artifact getGeneratedManifest() {
    return generatedManifest;
  }


  /** Returns the direct debug key signed apk, if there is one. */
  @Nullable
  public Artifact getSignedApk() {
    return signedApk;
  }

  @Nullable
  public Artifact getIdlClassJar() {
    return idlClassJar;
  }

  @Nullable
  public Artifact getIdlSourceJar() {
    return idlSourceJar;
  }

  /** A list of the direct Resource directories. */
  public ImmutableCollection<SourceDirectory> getResourceDirs() {
    return resourceDirs;
  }

  /** A list of the direct Asset directories. */
  public ImmutableCollection<SourceDirectory> getAssetDirs() {
    return assetDirs;
  }

  /** A list of direct idl directories. */
  public ImmutableCollection<SourceDirectory> getIdlImports() {
    return idlImports;
  }

  /** A list of sources from the "idl_srcs" attribute. */
  public ImmutableCollection<Artifact> getIdlSrcs() {
    return idlSrcs;
  }

  /** A list of java files generated from the "idl_srcs" attribute. */
  public ImmutableCollection<Artifact> getIdlGeneratedJavaFiles() {
    return idlGeneratedJavaFiles;
  }

  /** A list of the APKs related to the app under test, if any. */
  public ImmutableCollection<Artifact> getApksUnderTest() {
    return apksUnderTest;
  }
}
