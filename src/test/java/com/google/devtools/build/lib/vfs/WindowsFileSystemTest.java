// Copyright 2016 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.vfs;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.testutil.TestSpec;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.windows.util.WindowsTestUtil;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link WindowsFileSystem}. */
@RunWith(JUnit4.class)
@TestSpec(localOnly = true, supportedOs = OS.WINDOWS)
public class WindowsFileSystemTest {

  private String scratchRoot;
  private WindowsTestUtil testUtil;
  private WindowsFileSystem fs;

  @Before
  public void loadJni() throws Exception {
    WindowsTestUtil.loadJni();
    scratchRoot = new File(System.getenv("TEST_TMPDIR")).getAbsolutePath() + "/x";
    testUtil = new WindowsTestUtil(scratchRoot);
    fs = new WindowsFileSystem();
    cleanupScratchDir();
  }

  @After
  public void cleanupScratchDir() throws Exception {
    testUtil.deleteAllUnder("");
  }

  @Test
  public void testCanWorkWithJunctionSymlinks() throws Exception {
    testUtil.scratchFile("dir\\hello.txt", "hello");
    testUtil.scratchDir("non_existent");
    testUtil.createJunctions(ImmutableMap.of("junc", "dir", "junc_bad", "non_existent"));

    Path juncPath = testUtil.createVfsPath(fs, "junc");
    Path dirPath = testUtil.createVfsPath(fs, "dir");
    Path juncBadPath = testUtil.createVfsPath(fs, "junc_bad");
    Path nonExistentPath = testUtil.createVfsPath(fs, "non_existent");

    // Test junction creation.
    assertThat(fs.exists(juncPath, /* followSymlinks */ false)).isTrue();
    assertThat(fs.exists(dirPath, /* followSymlinks */ false)).isTrue();
    assertThat(fs.exists(juncBadPath, /* followSymlinks */ false)).isTrue();
    assertThat(fs.exists(nonExistentPath, /* followSymlinks */ false)).isTrue();

    // Test recognizing and dereferencing a directory junction.
    assertThat(fs.isSymbolicLink(juncPath)).isTrue();
    assertThat(fs.isDirectory(juncPath, /* followSymlinks */ true)).isTrue();
    assertThat(fs.isDirectory(juncPath, /* followSymlinks */ false)).isFalse();
    assertThat(fs.getDirectoryEntries(juncPath))
        .containsExactly(testUtil.createVfsPath(fs, "junc\\hello.txt"));

    // Test deleting a directory junction.
    assertThat(fs.delete(juncPath)).isTrue();
    assertThat(fs.exists(juncPath, /* followSymlinks */ false)).isFalse();

    // Test recognizing a dangling directory junction.
    assertThat(fs.delete(nonExistentPath)).isTrue();
    assertThat(fs.exists(nonExistentPath, /* followSymlinks */ false)).isFalse();
    assertThat(fs.exists(juncBadPath, /* followSymlinks */ false)).isTrue();
    // TODO(bazel-team): fix https://github.com/bazelbuild/bazel/issues/1690 and uncomment the
    // assertion below.
    //assertThat(fs.isSymbolicLink(juncBadPath)).isTrue();
    assertThat(fs.isDirectory(juncBadPath, /* followSymlinks */ true)).isFalse();
    assertThat(fs.isDirectory(juncBadPath, /* followSymlinks */ false)).isFalse();

    // Test deleting a dangling junction.
    assertThat(fs.delete(juncBadPath)).isTrue();
    assertThat(fs.exists(juncBadPath, /* followSymlinks */ false)).isFalse();
  }

  @Test
  public void testMockJunctionCreation() throws Exception {
    String root = testUtil.scratchDir("dir").getParent().toString();
    testUtil.scratchFile("dir/file.txt", "hello");
    testUtil.createJunctions(ImmutableMap.of("junc", "dir"));
    String[] children = new File(root + "/junc").list();
    assertThat(children).isNotNull();
    assertThat(children).hasLength(1);
    assertThat(Arrays.asList(children)).containsExactly("file.txt");
  }

  @Test
  public void testIsJunction() throws Exception {
    final Map<String, String> junctions = new HashMap<>();
    junctions.put("shrtpath/a", "shrttrgt");
    junctions.put("shrtpath/b", "longtargetpath");
    junctions.put("shrtpath/c", "longta~1");
    junctions.put("longlinkpath/a", "shrttrgt");
    junctions.put("longlinkpath/b", "longtargetpath");
    junctions.put("longlinkpath/c", "longta~1");
    junctions.put("abbrev~1/a", "shrttrgt");
    junctions.put("abbrev~1/b", "longtargetpath");
    junctions.put("abbrev~1/c", "longta~1");

    String root = testUtil.scratchDir("shrtpath").getParent().toAbsolutePath().toString();
    testUtil.scratchDir("longlinkpath");
    testUtil.scratchDir("abbreviated");
    testUtil.scratchDir("control/a");
    testUtil.scratchDir("control/b");
    testUtil.scratchDir("control/c");

    testUtil.scratchFile("shrttrgt/file1.txt", "hello");
    testUtil.scratchFile("longtargetpath/file2.txt", "hello");

    testUtil.createJunctions(junctions);

    assertThat(WindowsFileSystem.isJunction(new File(root, "shrtpath/a"))).isTrue();
    assertThat(WindowsFileSystem.isJunction(new File(root, "shrtpath/b"))).isTrue();
    assertThat(WindowsFileSystem.isJunction(new File(root, "shrtpath/c"))).isTrue();
    assertThat(WindowsFileSystem.isJunction(new File(root, "longlinkpath/a"))).isTrue();
    assertThat(WindowsFileSystem.isJunction(new File(root, "longlinkpath/b"))).isTrue();
    assertThat(WindowsFileSystem.isJunction(new File(root, "longlinkpath/c"))).isTrue();
    assertThat(WindowsFileSystem.isJunction(new File(root, "longli~1/a"))).isTrue();
    assertThat(WindowsFileSystem.isJunction(new File(root, "longli~1/b"))).isTrue();
    assertThat(WindowsFileSystem.isJunction(new File(root, "longli~1/c"))).isTrue();
    assertThat(WindowsFileSystem.isJunction(new File(root, "abbreviated/a"))).isTrue();
    assertThat(WindowsFileSystem.isJunction(new File(root, "abbreviated/b"))).isTrue();
    assertThat(WindowsFileSystem.isJunction(new File(root, "abbreviated/c"))).isTrue();
    assertThat(WindowsFileSystem.isJunction(new File(root, "abbrev~1/a"))).isTrue();
    assertThat(WindowsFileSystem.isJunction(new File(root, "abbrev~1/b"))).isTrue();
    assertThat(WindowsFileSystem.isJunction(new File(root, "abbrev~1/c"))).isTrue();
    assertThat(WindowsFileSystem.isJunction(new File(root, "control/a"))).isFalse();
    assertThat(WindowsFileSystem.isJunction(new File(root, "control/b"))).isFalse();
    assertThat(WindowsFileSystem.isJunction(new File(root, "control/c"))).isFalse();
    assertThat(WindowsFileSystem.isJunction(new File(root, "shrttrgt/file1.txt")))
        .isFalse();
    assertThat(WindowsFileSystem.isJunction(new File(root, "longtargetpath/file2.txt")))
        .isFalse();
    assertThat(WindowsFileSystem.isJunction(new File(root, "longta~1/file2.txt")))
        .isFalse();
    try {
      WindowsFileSystem.isJunction(new File(root, "non-existent"));
      Assert.fail("expected failure");
    } catch (IOException e) {
      assertThat(e.getMessage()).contains("cannot find");
    }

    assertThat(Arrays.asList(new File(root + "/shrtpath/a").list())).containsExactly("file1.txt");
    assertThat(Arrays.asList(new File(root + "/shrtpath/b").list())).containsExactly("file2.txt");
    assertThat(Arrays.asList(new File(root + "/shrtpath/c").list())).containsExactly("file2.txt");
    assertThat(Arrays.asList(new File(root + "/longlinkpath/a").list()))
        .containsExactly("file1.txt");
    assertThat(Arrays.asList(new File(root + "/longlinkpath/b").list()))
        .containsExactly("file2.txt");
    assertThat(Arrays.asList(new File(root + "/longlinkpath/c").list()))
        .containsExactly("file2.txt");
    assertThat(Arrays.asList(new File(root + "/abbreviated/a").list()))
        .containsExactly("file1.txt");
    assertThat(Arrays.asList(new File(root + "/abbreviated/b").list()))
        .containsExactly("file2.txt");
    assertThat(Arrays.asList(new File(root + "/abbreviated/c").list()))
        .containsExactly("file2.txt");
  }

  @Test
  public void testIsJunctionIsTrueForDanglingJunction() throws Exception {
    java.nio.file.Path helloPath = testUtil.scratchFile("target\\hello.txt", "hello");
    testUtil.createJunctions(ImmutableMap.of("link", "target"));

    File linkPath = new File(helloPath.getParent().getParent().toFile(), "link");
    assertThat(Arrays.asList(linkPath.list())).containsExactly("hello.txt");
    assertThat(WindowsFileSystem.isJunction(linkPath)).isTrue();

    assertThat(helloPath.toFile().delete()).isTrue();
    assertThat(helloPath.getParent().toFile().delete()).isTrue();
    assertThat(helloPath.getParent().toFile().exists()).isFalse();
    assertThat(Arrays.asList(linkPath.getParentFile().list())).containsExactly("link");

    assertThat(WindowsFileSystem.isJunction(linkPath)).isTrue();
    assertThat(
            Files.exists(
                linkPath.toPath(), WindowsFileSystem.symlinkOpts(/* followSymlinks */ false)))
        .isTrue();
    assertThat(
            Files.exists(
                linkPath.toPath(), WindowsFileSystem.symlinkOpts(/* followSymlinks */ true)))
        .isFalse();
  }

  @Test
  public void testIsJunctionHandlesFilesystemChangesCorrectly() throws Exception {
    File longPath =
        testUtil.scratchFile("target\\helloworld.txt", "hello").toAbsolutePath().toFile();
    File shortPath = new File(longPath.getParentFile(), "hellow~1.txt");
    assertThat(WindowsFileSystem.isJunction(longPath)).isFalse();
    assertThat(WindowsFileSystem.isJunction(shortPath)).isFalse();

    assertThat(longPath.delete()).isTrue();
    testUtil.createJunctions(ImmutableMap.of("target\\helloworld.txt", "target"));
    assertThat(WindowsFileSystem.isJunction(longPath)).isTrue();
    assertThat(WindowsFileSystem.isJunction(shortPath)).isTrue();

    assertThat(longPath.delete()).isTrue();
    assertThat(longPath.mkdir()).isTrue();
    assertThat(WindowsFileSystem.isJunction(longPath)).isFalse();
    assertThat(WindowsFileSystem.isJunction(shortPath)).isFalse();
  }
}
