// Copyright 2014 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.rules.objc;

import static com.google.devtools.build.lib.rules.objc.ObjcProvider.ASSET_CATALOG;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.BUNDLE_FILE;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.Flag.USES_SWIFT;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.IMPORTED_LIBRARY;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.LIBRARY;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.MERGE_ZIP;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.NESTED_BUNDLE;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.STORYBOARD;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.STRINGS;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.XCDATAMODEL;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.XIB;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.vfs.PathFragment;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Contains information regarding the creation of an iOS bundle.
 */
@Immutable
final class Bundling {
  static final class Builder {
    private String name;
    private String bundleDirFormat;
    private ImmutableList.Builder<BundleableFile> bundleFilesBuilder = ImmutableList.builder();
    private ObjcProvider objcProvider;
    private NestedSetBuilder<Artifact> infoplists = NestedSetBuilder.stableOrder();
    private IntermediateArtifacts intermediateArtifacts;
    private String primaryBundleId;
    private String fallbackBundleId;
    private String architecture;
    private String minimumOsVersion;

    public Builder setName(String name) {
      this.name = name;
      return this;
    }

    /**
     * Sets the CPU architecture this bundling was constructed for. Legal value are any that may be
     * set on {@link ObjcConfiguration#getIosCpu()}.
     */
    public Builder setArchitecture(String architecture) {
      this.architecture = architecture;
      return this;
    }

    public Builder setBundleDirFormat(String bundleDirFormat) {
      this.bundleDirFormat = bundleDirFormat;
      return this;
    }

    public Builder addExtraBundleFiles(ImmutableList<BundleableFile> extraBundleFiles) {
      this.bundleFilesBuilder.addAll(extraBundleFiles);
      return this;
    }

    public Builder setObjcProvider(ObjcProvider objcProvider) {
      this.objcProvider = objcProvider;
      return this;
    }

    /**
     * Adds an artifact representing an {@code Info.plist} as an input to this bundle's
     * {@code Info.plist} (which is merged from any such added plists plus some additional
     * information).
     */
    public Builder addInfoplistInput(Artifact infoplist) {
      this.infoplists.add(infoplist);
      return this;
    }

    /**
     * Adds any info plists specified in the given rule's {@code infoplist} attribute as well as
     * from its {@code options} as inputs to this bundle's {@code Info.plist} (which is merged from
     * any such added plists plus some additional information).
     */
    public Builder addInfoplistInputFromRule(RuleContext ruleContext) {
      if (ruleContext.attributes().has("options", BuildType.LABEL)) {
        OptionsProvider optionsProvider = ruleContext
            .getPrerequisite("options", Mode.TARGET, OptionsProvider.class);
        if (optionsProvider != null) {
          infoplists.addAll(optionsProvider.getInfoplists());
        }
      }
      Artifact infoplist = ruleContext.getPrerequisiteArtifact("infoplist", Mode.TARGET);
      if (infoplist != null) {
        infoplists.add(infoplist);
      }
      return this;
    }

    public Builder setIntermediateArtifacts(IntermediateArtifacts intermediateArtifacts) {
      this.intermediateArtifacts = intermediateArtifacts;
      return this;
    }
    
    public Builder setPrimaryBundleId(String primaryId) {
      this.primaryBundleId = primaryId;
      return this;
    }
    
    public Builder setFallbackBundleId(String fallbackId) {
      this.fallbackBundleId = fallbackId;
      return this;
    }

    /**
     * Sets the minimum OS version for this bundle which will be used when constructing the bundle's
     * plist.
     */
    public Builder setMinimumOsVersion(String minimumOsVersion) {
      this.minimumOsVersion = minimumOsVersion;
      return this;
    }

    private static NestedSet<Artifact> nestedBundleContentArtifacts(Iterable<Bundling> bundles) {
      NestedSetBuilder<Artifact> artifacts = NestedSetBuilder.stableOrder();
      for (Bundling bundle : bundles) {
        artifacts.addTransitive(bundle.getBundleContentArtifacts());
      }
      return artifacts.build();
    }

    private NestedSet<Artifact> mergeZips(Optional<Artifact> actoolzipOutput) {
      NestedSetBuilder<Artifact> mergeZipBuilder =
          NestedSetBuilder.<Artifact>stableOrder()
              .addAll(actoolzipOutput.asSet())
              .addAll(
                  Xcdatamodel.outputZips(
                      Xcdatamodels.xcdatamodels(
                          intermediateArtifacts, objcProvider.get(XCDATAMODEL))))
              .addTransitive(objcProvider.get(MERGE_ZIP));
      for (Artifact xibFile : objcProvider.get(XIB)) {
        mergeZipBuilder.add(intermediateArtifacts.compiledXibFileZip(xibFile));
      }
      for (Artifact storyboard : objcProvider.get(STORYBOARD)) {
        mergeZipBuilder.add(intermediateArtifacts.compiledStoryboardZip(storyboard));
      }

      if (objcProvider.is(USES_SWIFT)) {
        mergeZipBuilder.add(intermediateArtifacts.swiftFrameworksFileZip());
      }

      return mergeZipBuilder.build();
    }

    private NestedSet<Artifact> bundleInfoplistInputs() {
      if (objcProvider.hasAssetCatalogs()) {
        infoplists.add(intermediateArtifacts.actoolPartialInfoplist());
      }
      return infoplists.build();
    }

    private Optional<Artifact> bundleInfoplist(NestedSet<Artifact> bundleInfoplistInputs) {
      if (bundleInfoplistInputs.isEmpty()) {
        return Optional.absent();
      }
      if (needsToMerge(bundleInfoplistInputs, primaryBundleId, fallbackBundleId)) {
        return Optional.of(intermediateArtifacts.mergedInfoplist());
      }
      return Optional.of(Iterables.getOnlyElement(bundleInfoplistInputs));
    }

    private Optional<Artifact> combinedArchitectureBinary() {
      Optional<Artifact> combinedArchitectureBinary = Optional.absent();
      if (!Iterables.isEmpty(objcProvider.get(LIBRARY))
          || !Iterables.isEmpty(objcProvider.get(IMPORTED_LIBRARY))) {
        combinedArchitectureBinary =
            Optional.of(intermediateArtifacts.combinedArchitectureBinary());
      }
      return combinedArchitectureBinary;
    }

    private Optional<Artifact> actoolzipOutput() {
      Optional<Artifact> actoolzipOutput = Optional.absent();
      if (!Iterables.isEmpty(objcProvider.get(ASSET_CATALOG))) {
        actoolzipOutput = Optional.of(intermediateArtifacts.actoolzipOutput());
      }
      return actoolzipOutput;
    }

    private NestedSet<BundleableFile> binaryStringsFiles() {
      NestedSetBuilder<BundleableFile> binaryStringsBuilder = NestedSetBuilder.stableOrder();
      for (Artifact stringsFile : objcProvider.get(STRINGS)) {
        BundleableFile bundleFile =
            new BundleableFile(
                intermediateArtifacts.convertedStringsFile(stringsFile),
                BundleableFile.flatBundlePath(stringsFile.getExecPath()));
        binaryStringsBuilder.add(bundleFile);
      }
      return binaryStringsBuilder.build();
    }

    /**
     * Filters files that would map to the same location in the bundle, adding only one copy to the
     * set of files returned.
     *
     * <p>Files can have the same bundle path for various illegal reasons and errors are raised for
     * that separately (see {@link BundleSupport#validateResources}). There are situations though
     * where the same file exists multiple times (for example in multi-architecture builds) and
     * would conflict when creating the bundle. In all these cases it shouldn't matter which one is
     * included and this class will select the first one.
     */
    ImmutableList<BundleableFile> deduplicateByBundlePaths(
        ImmutableList<BundleableFile> bundleFiles) {
      ImmutableList.Builder<BundleableFile> deduplicated = ImmutableList.builder();
      Set<String> bundlePaths = new HashSet<>();
      for (BundleableFile bundleFile : bundleFiles) {
        if (bundlePaths.add(bundleFile.getBundlePath())) {
          deduplicated.add(bundleFile);
        }
      }
      return deduplicated.build();
    }

    public Bundling build() {
      Preconditions.checkNotNull(intermediateArtifacts, "intermediateArtifacts");

      NestedSet<Artifact> bundleInfoplistInputs = bundleInfoplistInputs();
      Optional<Artifact> bundleInfoplist = bundleInfoplist(bundleInfoplistInputs);
      Optional<Artifact> actoolzipOutput = actoolzipOutput();
      Optional<Artifact> combinedArchitectureBinary = combinedArchitectureBinary();
      NestedSet<BundleableFile> binaryStringsFiles = binaryStringsFiles();
      NestedSet<Artifact> mergeZips = mergeZips(actoolzipOutput);

      bundleFilesBuilder.addAll(binaryStringsFiles).addAll(objcProvider.get(BUNDLE_FILE));
      ImmutableList<BundleableFile> bundleFiles =
          deduplicateByBundlePaths(bundleFilesBuilder.build());

      NestedSetBuilder<Artifact> bundleContentArtifactsBuilder =
          NestedSetBuilder.<Artifact>stableOrder()
              .addTransitive(nestedBundleContentArtifacts(objcProvider.get(NESTED_BUNDLE)))
              .addAll(combinedArchitectureBinary.asSet())
              .addAll(bundleInfoplist.asSet())
              .addTransitive(mergeZips)
              .addAll(BundleableFile.toArtifacts(binaryStringsFiles))
              .addAll(BundleableFile.toArtifacts(bundleFiles));

      return new Bundling(
          name,
          bundleDirFormat,
          combinedArchitectureBinary,
          bundleFiles,
          bundleInfoplist,
          actoolzipOutput,
          bundleContentArtifactsBuilder.build(),
          mergeZips,
          primaryBundleId,
          fallbackBundleId,
          architecture,
          minimumOsVersion,
          bundleInfoplistInputs,
          objcProvider.get(NESTED_BUNDLE));
    }
  }

  private static boolean needsToMerge(
      NestedSet<Artifact> bundleInfoplistInputs, String primaryBundleId, String fallbackBundleId) {
    return primaryBundleId != null || fallbackBundleId != null
        || Iterables.size(bundleInfoplistInputs) > 1;
  }

  private final String name;
  private final String architecture;
  private final String bundleDirFormat;
  private final Optional<Artifact> combinedArchitectureBinary;
  private final ImmutableList<BundleableFile> bundleFiles;
  private final Optional<Artifact> bundleInfoplist;
  private final Optional<Artifact> actoolzipOutput;
  private final NestedSet<Artifact> bundleContentArtifacts;
  private final NestedSet<Artifact> mergeZips;
  private final String primaryBundleId;
  private final String fallbackBundleId;
  private final String minimumOsVersion;
  private final NestedSet<Artifact> bundleInfoplistInputs;
  private final NestedSet<Bundling> nestedBundlings;

  private Bundling(
      String name,
      String bundleDirFormat,
      Optional<Artifact> combinedArchitectureBinary,
      ImmutableList<BundleableFile> bundleFiles,
      Optional<Artifact> bundleInfoplist,
      Optional<Artifact> actoolzipOutput,
      NestedSet<Artifact> bundleContentArtifacts,
      NestedSet<Artifact> mergeZips,
      String primaryBundleId,
      String fallbackBundleId,
      String architecture,
      String minimumOsVersion,
      NestedSet<Artifact> bundleInfoplistInputs,
      NestedSet<Bundling> nestedBundlings) {
    this.nestedBundlings = Preconditions.checkNotNull(nestedBundlings);
    this.name = Preconditions.checkNotNull(name);
    this.bundleDirFormat = Preconditions.checkNotNull(bundleDirFormat);
    this.combinedArchitectureBinary = Preconditions.checkNotNull(combinedArchitectureBinary);
    this.bundleFiles = Preconditions.checkNotNull(bundleFiles);
    this.bundleInfoplist = Preconditions.checkNotNull(bundleInfoplist);
    this.actoolzipOutput = Preconditions.checkNotNull(actoolzipOutput);
    this.bundleContentArtifacts = Preconditions.checkNotNull(bundleContentArtifacts);
    this.mergeZips = Preconditions.checkNotNull(mergeZips);
    this.fallbackBundleId = fallbackBundleId;
    this.primaryBundleId = primaryBundleId;
    this.architecture = Preconditions.checkNotNull(architecture);
    this.minimumOsVersion = Preconditions.checkNotNull(minimumOsVersion);
    this.bundleInfoplistInputs = Preconditions.checkNotNull(bundleInfoplistInputs);
  }

  /**
   * The bundle directory. For apps, this would be {@code "Payload/TARGET_NAME.app"}, which is where
   * in the bundle zip archive every file is found, including the linked binary, nested bundles, and
   * everything returned by {@link #getBundleFiles()}.
   */
  public String getBundleDir() {
    return String.format(bundleDirFormat, name);
  }

  /**
   * The name of the bundle, from which the bundle root and the path of the linked binary in the
   * bundle archive are derived.
   */
  public String getName() {
    return name;
  }

  /**
   * An {@link Optional} with the linked binary artifact, or {@link Optional#absent()} if it is
   * empty and should not be included in the bundle.
   */
  public Optional<Artifact> getCombinedArchitectureBinary() {
    return combinedArchitectureBinary;
  }

  /**
   * Bundle files to include in the bundle. These files are placed under the bundle root (possibly
   * nested, of course, depending on the bundle path of the files).
   */
  public ImmutableList<BundleableFile> getBundleFiles() {
    return bundleFiles;
  }

  /**
   * Returns any bundles nested in this one.
   */
  public NestedSet<Bundling> getNestedBundlings() {
    return nestedBundlings;
  }

  /**
   * Returns an artifact representing this bundle's {@code Info.plist} or {@link Optional#absent()}
   * if this bundle has no info plist inputs.
   */
  public Optional<Artifact> getBundleInfoplist() {
    return bundleInfoplist;
  }

  /**
   * Returns all info plists that need to be merged into this bundle's {@link #getBundleInfoplist()
   * info plist}.
   */
  public NestedSet<Artifact> getBundleInfoplistInputs() {
    return bundleInfoplistInputs;
  }

  /**
   * Returns {@code true} if this bundle requires merging of its {@link #getBundleInfoplist() info
   * plist}.
   */
  public boolean needsToMergeInfoplist() {
    return needsToMerge(bundleInfoplistInputs, primaryBundleId, fallbackBundleId);
  }

  /**
   * The location of the actoolzip output for this bundle. This is non-absent only included in the
   * bundle if there is at least one asset catalog artifact supplied by
   * {@link ObjcProvider#ASSET_CATALOG}.
   */
  public Optional<Artifact> getActoolzipOutput() {
    return actoolzipOutput;
  }

  /**
   * Returns all zip files whose contents should be merged into this bundle under the main bundle
   * directory. For instance, if a merge zip contains files a/b and c/d, then the resulting bundling
   * would have additional files at:
   * <ul>
   *   <li>{bundleDir}/a/b
   *   <li>{bundleDir}/c/d
   * </ul>
   */
  public NestedSet<Artifact> getMergeZips() {
    return mergeZips;
  }

  /**
   * Returns the variable substitutions that should be used when merging the plist info file of
   * this bundle.
   */
  public Map<String, String> variableSubstitutions() {
    return ImmutableMap.of(
        "EXECUTABLE_NAME", name,
        "BUNDLE_NAME", new PathFragment(getBundleDir()).getBaseName(),
        "PRODUCT_NAME", name);
  }

  /**
   * Returns the artifacts that are required to generate this bundle.
   */
  public NestedSet<Artifact> getBundleContentArtifacts() {
    return bundleContentArtifacts;
  }

  /**
   * Returns primary bundle ID to use, can be null.
   */
  public String getPrimaryBundleId() {
    return primaryBundleId;
  }
  
  /**
   * Returns fallback bundle ID to use when primary isn't set.
   */
  public String getFallbackBundleId() {
    return fallbackBundleId;
  }

  /**
   * Returns the iOS CPU architecture this bundle was constructed for.
   */
  public String getArchitecture() {
    return architecture;
  }

  /**
   * Returns the minimum iOS version this bundle's plist and resources should be generated for
   * (does <b>not</b> affect the minimum OS version its binary is compiled with).
   */
  public String getMinimumOsVersion() {
    return minimumOsVersion;
  }
}
