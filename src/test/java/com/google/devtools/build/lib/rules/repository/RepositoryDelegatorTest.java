// Copyright 2017 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.rules.repository;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.repository.RepositoryDelegatorFunction;
import com.google.devtools.build.lib.repository.RepositoryDirectoryValue;
import com.google.devtools.build.lib.repository.RepositoryFunction;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper;
import com.google.devtools.build.lib.skyframe.ExternalFilesHelper.ExternalFileAction;
import com.google.devtools.build.lib.skyframe.ExternalPackageFunction;
import com.google.devtools.build.lib.skyframe.FileFunction;
import com.google.devtools.build.lib.skyframe.FileStateFunction;
import com.google.devtools.build.lib.skyframe.LocalRepositoryLookupFunction;
import com.google.devtools.build.lib.skyframe.PackageFunction;
import com.google.devtools.build.lib.skyframe.PackageLookupFunction;
import com.google.devtools.build.lib.skyframe.PackageLookupFunction.CrossRepositoryLabelViolationStrategy;
import com.google.devtools.build.lib.skyframe.PackageLookupValue.BuildFileName;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.WorkspaceASTFunction;
import com.google.devtools.build.lib.skyframe.WorkspaceFileFunction;
import com.google.devtools.build.lib.testutil.FoundationTestCase;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.testutil.TestRuleClassProvider;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.InMemoryMemoizingEvaluator;
import com.google.devtools.build.skyframe.MemoizingEvaluator;
import com.google.devtools.build.skyframe.RecordingDifferencer;
import com.google.devtools.build.skyframe.SequentialBuildDriver;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link RepositoryDelegatorFunction}
 */
@RunWith(JUnit4.class)
public class RepositoryDelegatorTest extends FoundationTestCase {
  private RepositoryDelegatorFunction delegatorFunction;
  private Path overrideDirectory;
  private SequentialBuildDriver driver;

  @Before
  public void setupDelegator() throws Exception {
    Path root = scratch.dir("/outputbase");
    delegatorFunction = new RepositoryDelegatorFunction(
        ImmutableMap.<String, RepositoryFunction>of(), null, new AtomicBoolean(true));
    AtomicReference<PathPackageLocator> pkgLocator = new AtomicReference<>(
        new PathPackageLocator(root, ImmutableList.of(root)));
    BlazeDirectories directories = new BlazeDirectories(root, root, root,
        TestConstants.PRODUCT_NAME);
    ExternalFilesHelper externalFilesHelper = new ExternalFilesHelper(
        pkgLocator,
        ExternalFileAction.DEPEND_ON_EXTERNAL_PKG_FOR_EXTERNAL_REPO_PATHS,
        directories);
    RecordingDifferencer differencer = new RecordingDifferencer();
    MemoizingEvaluator evaluator =
        new InMemoryMemoizingEvaluator(
            ImmutableMap.<SkyFunctionName, SkyFunction>builder()
                .put(
                    SkyFunctions.FILE_STATE,
                    new FileStateFunction(
                        new AtomicReference<TimestampGranularityMonitor>(), externalFilesHelper))
                .put(SkyFunctions.FILE, new FileFunction(pkgLocator))
                .put(SkyFunctions.REPOSITORY_DIRECTORY, delegatorFunction)
                .put(
                    SkyFunctions.PACKAGE,
                    new PackageFunction(null, null, null, null, null, null, null))
                .put(
                    SkyFunctions.PACKAGE_LOOKUP,
                    new PackageLookupFunction(
                        null,
                        CrossRepositoryLabelViolationStrategy.ERROR,
                        ImmutableList.of(BuildFileName.BUILD_DOT_BAZEL, BuildFileName.BUILD)))
                .put(
                    SkyFunctions.WORKSPACE_AST,
                    new WorkspaceASTFunction(TestRuleClassProvider.getRuleClassProvider()))
                .put(
                    SkyFunctions.WORKSPACE_FILE,
                    new WorkspaceFileFunction(
                        TestRuleClassProvider.getRuleClassProvider(),
                        TestConstants.PACKAGE_FACTORY_BUILDER_FACTORY_FOR_TESTING.builder().build(
                            TestRuleClassProvider.getRuleClassProvider(), root.getFileSystem()),
                        directories))
                .put(SkyFunctions.LOCAL_REPOSITORY_LOOKUP, new LocalRepositoryLookupFunction())
                .put(SkyFunctions.EXTERNAL_PACKAGE, new ExternalPackageFunction())
                .build(),
            differencer);
    driver = new SequentialBuildDriver(evaluator);
    overrideDirectory = scratch.dir("/foo");
    scratch.file("/foo/WORKSPACE");
    RepositoryDelegatorFunction.REPOSITORY_OVERRIDES.set(
        differencer,
        ImmutableMap.<RepositoryName, PathFragment>builder()
            .put(RepositoryName.createFromValidStrippedName("foo"), overrideDirectory.asFragment())
            .build());
    PrecomputedValue.BLAZE_DIRECTORIES.set(differencer, directories);
    PrecomputedValue.PATH_PACKAGE_LOCATOR.set(differencer, pkgLocator.get());
  }

  @Test
  public void testOverride() throws Exception {
    StoredEventHandler eventHandler = new StoredEventHandler();
    SkyKey key = RepositoryDirectoryValue.key(RepositoryName.createFromValidStrippedName("foo"));
    EvaluationResult<SkyValue> result =
        driver.evaluate(ImmutableList.of(key), false, 8, eventHandler);
    assertThat(result.hasError()).isFalse();
    RepositoryDirectoryValue repositoryDirectoryValue = (RepositoryDirectoryValue) result.get(key);
    Path expectedPath = scratch.dir("/outputbase/external/foo");
    Path actualPath = repositoryDirectoryValue.getPath();
    assertThat(actualPath).isEqualTo(expectedPath);
    assertThat(actualPath.isSymbolicLink()).isTrue();
    assertThat(actualPath.readSymbolicLink()).isEqualTo(overrideDirectory.asFragment());
  }

}
