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
package com.google.devtools.build.lib.ideinfo;

import static com.google.common.collect.Iterables.transform;
import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.lib.ideinfo.androidstudio.AndroidStudioIdeInfo.ArtifactLocation;
import com.google.devtools.build.lib.ideinfo.androidstudio.AndroidStudioIdeInfo.JavaRuleIdeInfo;
import com.google.devtools.build.lib.ideinfo.androidstudio.AndroidStudioIdeInfo.RuleIdeInfo;
import com.google.devtools.build.lib.ideinfo.androidstudio.AndroidStudioIdeInfo.RuleIdeInfo.Kind;
import com.google.devtools.build.lib.vfs.Path;

import java.util.Map;

/**
 * Tests for {@link AndroidStudioInfoAspect} validating proto's contents.
 */
public class AndroidStudioInfoAspectTest extends AndroidStudioInfoAspectTestBase {

  public void testSimpleJavaLibrary() throws Exception {
    Path buildFilePath =
        scratch.file(
            "com/google/example/BUILD",
            "java_library(",
            "    name = 'simple',",
            "    srcs = ['simple/Simple.java']",
            ")");
    Map<String, RuleIdeInfo> ruleIdeInfos = buildRuleIdeInfo("//com/google/example:simple");
    assertThat(ruleIdeInfos.size()).isEqualTo(1);
    RuleIdeInfo ruleIdeInfo = getRuleInfoAndVerifyLabel(
        "//com/google/example:simple", ruleIdeInfos);
    assertThat(ruleIdeInfo.getBuildFile()).isEqualTo(buildFilePath.toString());
    assertThat(ruleIdeInfo.getKind()).isEqualTo(Kind.JAVA_LIBRARY);
    assertThat(ruleIdeInfo.getDependenciesCount()).isEqualTo(0);
    assertThat(relativePathsForSourcesOf(ruleIdeInfo))
        .containsExactly("com/google/example/simple/Simple.java");
    assertThat(
            transform(ruleIdeInfo.getJavaRuleIdeInfo().getJarsList(), LIBRARY_ARTIFACT_TO_STRING))
        .containsExactly(jarString("com/google/example",
                "libsimple.jar", "libsimple-ijar.jar", "libsimple-src.jar"));

    assertThat(getIdeResolveFiles()).containsExactly(
        "com/google/example/libsimple.jar",
        "com/google/example/libsimple-ijar.jar",
        "com/google/example/libsimple-src.jar"
    );
  }

  public void testJavaLibraryProtoWithDependencies() throws Exception {
    scratch.file(
        "com/google/example/BUILD",
        "java_library(",
        "    name = 'simple',",
        "    srcs = ['simple/Simple.java']",
        ")",
        "java_library(",
        "    name = 'complex',",
        "    srcs = ['complex/Complex.java'],",
        "    deps = [':simple']",
        ")");

    Map<String, RuleIdeInfo> ruleIdeInfos = buildRuleIdeInfo("//com/google/example:complex");
    assertThat(ruleIdeInfos.size()).isEqualTo(2);

    getRuleInfoAndVerifyLabel("//com/google/example:simple", ruleIdeInfos);
    RuleIdeInfo complexRuleIdeInfo = getRuleInfoAndVerifyLabel(
        "//com/google/example:complex", ruleIdeInfos);

    assertThat(relativePathsForSourcesOf(complexRuleIdeInfo))
        .containsExactly("com/google/example/complex/Complex.java");
    assertThat(complexRuleIdeInfo.getDependenciesList())
        .containsExactly("//com/google/example:simple");
  }

  public void testJavaLibraryWithTransitiveDependencies() throws Exception {
    scratch.file(
        "com/google/example/BUILD",
        "java_library(",
        "    name = 'simple',",
        "    srcs = ['simple/Simple.java']",
        ")",
        "java_library(",
        "    name = 'complex',",
        "    srcs = ['complex/Complex.java'],",
        "    deps = [':simple']",
        ")",
        "java_library(",
        "    name = 'extracomplex',",
        "    srcs = ['extracomplex/ExtraComplex.java'],",
        "    deps = [':complex']",
        ")");
    Map<String, RuleIdeInfo> ruleIdeInfos = buildRuleIdeInfo("//com/google/example:extracomplex");
    assertThat(ruleIdeInfos.size()).isEqualTo(3);

    getRuleInfoAndVerifyLabel("//com/google/example:simple", ruleIdeInfos);
    getRuleInfoAndVerifyLabel("//com/google/example:complex", ruleIdeInfos);

    RuleIdeInfo extraComplexRuleIdeInfo = getRuleInfoAndVerifyLabel(
        "//com/google/example:extracomplex", ruleIdeInfos);

    assertThat(relativePathsForSourcesOf(extraComplexRuleIdeInfo))
        .containsExactly("com/google/example/extracomplex/ExtraComplex.java");
    assertThat(extraComplexRuleIdeInfo.getDependenciesList())
        .containsExactly("//com/google/example:complex");

    assertThat(getIdeResolveFiles()).containsExactly(
        "com/google/example/libextracomplex.jar",
        "com/google/example/libextracomplex-ijar.jar",
        "com/google/example/libextracomplex-src.jar",
        "com/google/example/libcomplex.jar",
        "com/google/example/libcomplex-ijar.jar",
        "com/google/example/libcomplex-src.jar",
        "com/google/example/libsimple.jar",
        "com/google/example/libsimple-ijar.jar",
        "com/google/example/libsimple-src.jar"
    );
  }

  public void testJavaLibraryWithDiamondDependencies() throws Exception {
    scratch.file(
        "com/google/example/BUILD",
        "java_library(",
        "    name = 'simple',",
        "    srcs = ['simple/Simple.java']",
        ")",
        "java_library(",
        "    name = 'complex',",
        "    srcs = ['complex/Complex.java'],",
        "    deps = [':simple']",
        ")",
        "java_library(",
        "    name = 'complex1',",
        "    srcs = ['complex1/Complex.java'],",
        "    deps = [':simple']",
        ")",
        "java_library(",
        "    name = 'extracomplex',",
        "    srcs = ['extracomplex/ExtraComplex.java'],",
        "    deps = [':complex', ':complex1']",
        ")");
    Map<String, RuleIdeInfo> ruleIdeInfos = buildRuleIdeInfo("//com/google/example:extracomplex");
    assertThat(ruleIdeInfos.size()).isEqualTo(4);

    getRuleInfoAndVerifyLabel("//com/google/example:simple", ruleIdeInfos);
    getRuleInfoAndVerifyLabel("//com/google/example:complex", ruleIdeInfos);
    getRuleInfoAndVerifyLabel("//com/google/example:complex1", ruleIdeInfos);

    RuleIdeInfo extraComplexRuleIdeInfo = getRuleInfoAndVerifyLabel(
        "//com/google/example:extracomplex", ruleIdeInfos);

    assertThat(relativePathsForSourcesOf(extraComplexRuleIdeInfo))
        .containsExactly("com/google/example/extracomplex/ExtraComplex.java");
    assertThat(extraComplexRuleIdeInfo.getDependenciesList())
        .containsExactly("//com/google/example:complex", "//com/google/example:complex1");
  }

  public void testJavaLibraryWithExports() throws Exception {
    scratch.file(
        "com/google/example/BUILD",
        "java_library(",
        "    name = 'simple',",
        "    srcs = ['simple/Simple.java']",
        ")",
        "java_library(",
        "    name = 'complex',",
        "    srcs = ['complex/Complex.java'],",
        "    exports = [':simple'],",
        ")",
        "java_library(",
        "    name = 'extracomplex',",
        "    srcs = ['extracomplex/ExtraComplex.java'],",
        "    deps = [':complex']",
        ")");
    Map<String, RuleIdeInfo> ruleIdeInfos = buildRuleIdeInfo("//com/google/example:extracomplex");
    assertThat(ruleIdeInfos.size()).isEqualTo(3);

    getRuleInfoAndVerifyLabel("//com/google/example:simple", ruleIdeInfos);
    getRuleInfoAndVerifyLabel("//com/google/example:complex", ruleIdeInfos);

    RuleIdeInfo complexRuleIdeInfo = getRuleInfoAndVerifyLabel("//com/google/example:complex",
        ruleIdeInfos);
    RuleIdeInfo extraComplexRuleIdeInfo = getRuleInfoAndVerifyLabel(
        "//com/google/example:extracomplex", ruleIdeInfos);

    assertThat(complexRuleIdeInfo.getDependenciesList())
        .containsExactly("//com/google/example:simple");

    assertThat(extraComplexRuleIdeInfo.getDependenciesList())
        .containsExactly("//com/google/example:simple", "//com/google/example:complex")
        .inOrder();
    assertThat(getIdeResolveFiles()).containsExactly(
        "com/google/example/libextracomplex.jar",
        "com/google/example/libextracomplex-ijar.jar",
        "com/google/example/libextracomplex-src.jar",
        "com/google/example/libcomplex.jar",
        "com/google/example/libcomplex-ijar.jar",
        "com/google/example/libcomplex-src.jar",
        "com/google/example/libsimple.jar",
        "com/google/example/libsimple-ijar.jar",
        "com/google/example/libsimple-src.jar"
    );
  }

  public void testJavaLibraryWithTransitiveExports() throws Exception {
    scratch.file(
        "com/google/example/BUILD",
        "java_library(",
        "    name = 'simple',",
        "    srcs = ['simple/Simple.java']",
        ")",
        "java_library(",
        "    name = 'complex',",
        "    srcs = ['complex/Complex.java'],",
        "    exports = [':simple'],",
        ")",
        "java_library(",
        "    name = 'extracomplex',",
        "    srcs = ['extracomplex/ExtraComplex.java'],",
        "    exports = [':complex'],",
        ")",
        "java_library(",
        "    name = 'megacomplex',",
        "    srcs = ['megacomplex/MegaComplex.java'],",
        "    deps = [':extracomplex'],",
        ")"
    );
    Map<String, RuleIdeInfo> ruleIdeInfos = buildRuleIdeInfo("//com/google/example:megacomplex");
    assertThat(ruleIdeInfos.size()).isEqualTo(4);

    getRuleInfoAndVerifyLabel("//com/google/example:simple", ruleIdeInfos);
    getRuleInfoAndVerifyLabel("//com/google/example:complex", ruleIdeInfos);
    getRuleInfoAndVerifyLabel("//com/google/example:extracomplex", ruleIdeInfos);

    RuleIdeInfo megaComplexRuleIdeInfo = getRuleInfoAndVerifyLabel(
        "//com/google/example:megacomplex", ruleIdeInfos);

    assertThat(relativePathsForSourcesOf(megaComplexRuleIdeInfo))
        .containsExactly("com/google/example/megacomplex/MegaComplex.java");
    assertThat(megaComplexRuleIdeInfo.getDependenciesList())
        .containsExactly(
            "//com/google/example:simple",
            "//com/google/example:complex",
            "//com/google/example:extracomplex")
        .inOrder();
  }

  public void testJavaImport() throws Exception {
    scratch.file(
        "com/google/example/BUILD",
        "java_import(",
        "   name = 'imp',",
        "   jars = ['a.jar', 'b.jar'],",
        "   srcjar = 'impsrc.jar',",
        ")",
        "java_library(",
        "   name = 'lib',",
        "   srcs = ['Lib.java'],",
        "   deps = [':imp'],",
        ")");

    Map<String, RuleIdeInfo> ruleIdeInfos = buildRuleIdeInfo("//com/google/example:lib");
    final RuleIdeInfo libInfo = getRuleInfoAndVerifyLabel("//com/google/example:lib", ruleIdeInfos);
    RuleIdeInfo impInfo = getRuleInfoAndVerifyLabel("//com/google/example:imp", ruleIdeInfos);
    assertThat(impInfo.getKind()).isEqualTo(Kind.JAVA_IMPORT);
    assertThat(libInfo.getDependenciesList()).containsExactly("//com/google/example:imp");

    JavaRuleIdeInfo javaRuleIdeInfo = impInfo.getJavaRuleIdeInfo();
    assertThat(javaRuleIdeInfo).isNotNull();
    assertThat(transform(javaRuleIdeInfo.getJarsList(), LIBRARY_ARTIFACT_TO_STRING))
        .containsExactly(
            jarString("com/google/example", "a.jar", null, "impsrc.jar"),
            jarString("com/google/example", "b.jar", null, "impsrc.jar"))
        .inOrder();

    assertThat(getIdeResolveFiles()).containsExactly(
        "com/google/example/liblib.jar",
        "com/google/example/liblib-ijar.jar",
        "com/google/example/liblib-src.jar"
    );
  }

  public void testJavaImportWithExports() throws Exception {
    scratch.file(
        "com/google/example/BUILD",
        "java_library(",
        "   name = 'foobar',",
        "   srcs = ['FooBar.java'],",
        ")",
        "java_import(",
        "   name = 'imp',",
        "   jars = ['a.jar', 'b.jar'],",
        "   deps = [':foobar'],",
        "   exports = [':foobar'],",
        ")",
        "java_library(",
        "   name = 'lib',",
        "   srcs = ['Lib.java'],",
        "   deps = [':imp'],",
        ")");

    Map<String, RuleIdeInfo> ruleIdeInfos = buildRuleIdeInfo("//com/google/example:lib");
    RuleIdeInfo libInfo = getRuleInfoAndVerifyLabel("//com/google/example:lib", ruleIdeInfos);
    RuleIdeInfo impInfo = getRuleInfoAndVerifyLabel("//com/google/example:imp", ruleIdeInfos);

    assertThat(impInfo.getKind()).isEqualTo(Kind.JAVA_IMPORT);
    assertThat(impInfo.getDependenciesList()).containsExactly("//com/google/example:foobar");
    assertThat(libInfo.getDependenciesList())
        .containsExactly("//com/google/example:foobar", "//com/google/example:imp")
        .inOrder();
  }

  public void testGeneratedJavaImportFilesAreAddedToOutputGroup() throws Exception {
    scratch.file(
        "com/google/example/BUILD",
        "java_import(",
        "   name = 'imp',",
        "   jars = [':gen_jar'],",
        "   srcjar = ':gen_srcjar',",
        ")",
        "genrule(",
        "   name = 'gen_jar',",
        "   outs = ['gen_jar.jar'],",
        "   cmd = '',",
        ")",
        "genrule(",
        "   name = 'gen_srcjar',",
        "   outs = ['gen_srcjar.jar'],",
        "   cmd = '',",
        ")");
    buildTarget("//com/google/example:imp");
    assertThat(getIdeResolveFiles()).containsExactly(
        "com/google/example/gen_jar.jar",
        "com/google/example/gen_srcjar.jar"
    );
  }

  public void testAspectIsPropagatedAcrossExports() throws Exception {
    scratch.file(
        "com/google/example/BUILD",
        "java_library(",
        "   name = 'foobar',",
        "   srcs = ['FooBar.java'],",
        ")",
        "java_library(",
        "   name = 'lib',",
        "   srcs = ['Lib.java'],",
        "   exports = [':foobar'],",
        ")");

    Map<String, RuleIdeInfo> ruleIdeInfos = buildRuleIdeInfo("//com/google/example:lib");
    getRuleInfoAndVerifyLabel("//com/google/example:foobar", ruleIdeInfos);
  }

  public void testJavaTest() throws Exception {
    scratch.file(
        "java/com/google/example/BUILD",
        "java_library(",
        "   name = 'foobar',",
        "   srcs = ['FooBar.java'],",
        ")",
        "java_test(",
        "   name = 'FooBarTest',",
        "   srcs = ['FooBarTest.java'],",
        "   deps = [':foobar'],",
        ")");
    Map<String, RuleIdeInfo> ruleIdeInfos = buildRuleIdeInfo(
        "//java/com/google/example:FooBarTest");
    RuleIdeInfo testInfo = getRuleInfoAndVerifyLabel(
        "//java/com/google/example:FooBarTest", ruleIdeInfos);
    assertThat(testInfo.getKind()).isEqualTo(Kind.JAVA_TEST);
    assertThat(relativePathsForSourcesOf(testInfo))
        .containsExactly("java/com/google/example/FooBarTest.java");
    assertThat(testInfo.getDependenciesList())
        .containsExactly("//java/com/google/example:foobar");
    assertThat(transform(testInfo.getJavaRuleIdeInfo().getJarsList(), LIBRARY_ARTIFACT_TO_STRING))
        .containsExactly(jarString("java/com/google/example",
            "FooBarTest.jar", null, "FooBarTest-src.jar"));

    assertThat(getIdeResolveFiles()).containsExactly(
        "java/com/google/example/libfoobar.jar",
        "java/com/google/example/libfoobar-ijar.jar",
        "java/com/google/example/libfoobar-src.jar",
        "java/com/google/example/FooBarTest.jar",
        "java/com/google/example/FooBarTest-src.jar"
    );
  }

  public void testJavaBinary() throws Exception {
    scratch.file(
        "com/google/example/BUILD",
        "java_library(",
        "   name = 'foobar',",
        "   srcs = ['FooBar.java'],",
        ")",
        "java_binary(",
        "   name = 'foobar-exe',",
        "   main_class = 'MyMainClass',",
        "   srcs = ['FooBarMain.java'],",
        "   deps = [':foobar'],",
        ")");
    Map<String, RuleIdeInfo> ruleIdeInfos = buildRuleIdeInfo("//com/google/example:foobar-exe");
    RuleIdeInfo binaryInfo = getRuleInfoAndVerifyLabel(
        "//com/google/example:foobar-exe", ruleIdeInfos);
    assertThat(binaryInfo.getKind()).isEqualTo(Kind.JAVA_BINARY);
    assertThat(relativePathsForSourcesOf(binaryInfo))
        .containsExactly("com/google/example/FooBarMain.java");
    assertThat(binaryInfo.getDependenciesList()).containsExactly("//com/google/example:foobar");
    assertThat(transform(binaryInfo.getJavaRuleIdeInfo().getJarsList(), LIBRARY_ARTIFACT_TO_STRING))
        .containsExactly(jarString("com/google/example",
            "foobar-exe.jar", null, "foobar-exe-src.jar"));

    assertThat(getIdeResolveFiles()).containsExactly(
        "com/google/example/libfoobar.jar",
        "com/google/example/libfoobar-ijar.jar",
        "com/google/example/libfoobar-src.jar",
        "com/google/example/foobar-exe.jar",
        "com/google/example/foobar-exe-src.jar"
    );
  }

  public void testAndroidLibrary() throws Exception {
    scratch.file(
        "com/google/example/BUILD",
        "android_library(",
        "  name = 'l1',",
        "  manifest = 'Manifesto.xml',",
        "  custom_package = 'com.google.example',",
        "  resource_files = ['r1/values/a.xml'],",
        ")",
        "android_library(",
        "  name = 'l',",
        "  srcs = ['Main.java'],",
        "  deps = [':l1'],",
        "  manifest = 'Abracadabra.xml',",
        "  custom_package = 'com.google.example',",
        "  resource_files = ['res/drawable/a.png', 'res/drawable/b.png'],",
        ")");
    Map<String, RuleIdeInfo> ruleIdeInfos = buildRuleIdeInfo("//com/google/example:l");
    RuleIdeInfo ruleInfo = getRuleInfoAndVerifyLabel("//com/google/example:l", ruleIdeInfos);
    assertThat(ruleInfo.getKind()).isEqualTo(Kind.ANDROID_LIBRARY);
    assertThat(relativePathsForSourcesOf(ruleInfo)).containsExactly("com/google/example/Main.java");
    assertThat(transform(ruleInfo.getJavaRuleIdeInfo().getJarsList(), LIBRARY_ARTIFACT_TO_STRING))
        .containsExactly(
            jarString("com/google/example",
                "libl.jar", "libl-ijar.jar", "libl-src.jar"),
            jarString("com/google/example",
                "l_resources.jar", "l_resources-ijar.jar", "l_resources-src.jar"));
    assertThat(
            transform(
                ruleInfo.getAndroidRuleIdeInfo().getResourcesList(), ARTIFACT_TO_RELATIVE_PATH))
        .containsExactly("com/google/example/res");
    assertThat(ruleInfo.getAndroidRuleIdeInfo().getManifest().getRelativePath())
        .isEqualTo("com/google/example/Abracadabra.xml");
    assertThat(ruleInfo.getAndroidRuleIdeInfo().getJavaPackage()).isEqualTo("com.google.example");

    assertThat(ruleInfo.getDependenciesList()).containsExactly("//com/google/example:l1");
    assertThat(getIdeResolveFiles()).containsExactly(
        "com/google/example/libl.jar",
        "com/google/example/libl-ijar.jar",
        "com/google/example/libl-src.jar",
        "com/google/example/l_resources.jar",
        "com/google/example/l_resources-ijar.jar",
        "com/google/example/l_resources-src.jar",
        "com/google/example/libl1.jar",
        "com/google/example/libl1-src.jar",
        "com/google/example/l1_resources.jar",
        "com/google/example/l1_resources-ijar.jar",
        "com/google/example/l1_resources-src.jar"
    );
  }

  public void testAndroidBinary() throws Exception {
    scratch.file(
        "com/google/example/BUILD",
        "android_library(",
        "  name = 'l1',",
        "  manifest = 'Manifesto.xml',",
        "  custom_package = 'com.google.example',",
        "  resource_files = ['r1/values/a.xml'],",
        ")",
        "android_binary(",
        "  name = 'b',",
        "  srcs = ['Main.java'],",
        "  deps = [':l1'],",
        "  manifest = 'Abracadabra.xml',",
        "  custom_package = 'com.google.example',",
        "  resource_files = ['res/drawable/a.png', 'res/drawable/b.png'],",
        ")");
    Map<String, RuleIdeInfo> ruleIdeInfos = buildRuleIdeInfo("//com/google/example:b");
    RuleIdeInfo ruleInfo = getRuleInfoAndVerifyLabel("//com/google/example:b", ruleIdeInfos);
    assertThat(ruleInfo.getKind()).isEqualTo(Kind.ANDROID_BINARY);
    assertThat(relativePathsForSourcesOf(ruleInfo)).containsExactly("com/google/example/Main.java");
    assertThat(transform(ruleInfo.getJavaRuleIdeInfo().getJarsList(), LIBRARY_ARTIFACT_TO_STRING))
        .containsExactly(
            jarString("com/google/example",
                "libb.jar", "libb-ijar.jar", "libb-src.jar"),
            jarString("com/google/example",
                "b_resources.jar", "b_resources-ijar.jar", "b_resources-src.jar"));
    assertThat(
            transform(
                ruleInfo.getAndroidRuleIdeInfo().getResourcesList(), ARTIFACT_TO_RELATIVE_PATH))
        .containsExactly("com/google/example/res");
    assertThat(ruleInfo.getAndroidRuleIdeInfo().getManifest().getRelativePath())
        .isEqualTo("com/google/example/Abracadabra.xml");
    assertThat(ruleInfo.getAndroidRuleIdeInfo().getJavaPackage()).isEqualTo("com.google.example");
    assertThat(ruleInfo.getAndroidRuleIdeInfo().getApk().getRelativePath())
        .isEqualTo("com/google/example/b.apk");

    assertThat(ruleInfo.getDependenciesList()).containsExactly("//com/google/example:l1");

    assertThat(getIdeResolveFiles()).containsExactly(
        "com/google/example/libb.jar",
        "com/google/example/libb-ijar.jar",
        "com/google/example/libb-src.jar",
        "com/google/example/b_resources.jar",
        "com/google/example/b_resources-ijar.jar",
        "com/google/example/b_resources-src.jar",
        "com/google/example/libl1.jar",
        "com/google/example/libl1-src.jar",
        "com/google/example/l1_resources.jar",
        "com/google/example/l1_resources-ijar.jar",
        "com/google/example/l1_resources-src.jar"
    );
  }

  public void testAndroidInferredPackage() throws Exception {
    scratch.file(
        "java/com/google/example/BUILD",
        "android_library(",
        "  name = 'l',",
        "  manifest = 'Manifesto.xml',",
        ")",
        "android_binary(",
        "  name = 'b',",
        "  srcs = ['Main.java'],",
        "  deps = [':l'],",
        "  manifest = 'Abracadabra.xml',",
        ")");
    Map<String, RuleIdeInfo> ruleIdeInfos = buildRuleIdeInfo("//java/com/google/example:b");
    RuleIdeInfo lRuleInfo = getRuleInfoAndVerifyLabel("//java/com/google/example:l", ruleIdeInfos);
    RuleIdeInfo bRuleInfo = getRuleInfoAndVerifyLabel("//java/com/google/example:b", ruleIdeInfos);

    assertThat(bRuleInfo.getAndroidRuleIdeInfo().getJavaPackage()).isEqualTo("com.google.example");
    assertThat(lRuleInfo.getAndroidRuleIdeInfo().getJavaPackage()).isEqualTo("com.google.example");
  }

  public void testAndroidLibraryWithoutAidlHasNoIdlJars() throws Exception {
    scratch.file(
        "java/com/google/example/BUILD",
        "android_library(",
        "  name = 'no_idl',",
        "  srcs = ['Test.java'],",
        ")"
    );
    String noIdlTarget = "//java/com/google/example:no_idl";
    Map<String, RuleIdeInfo> ruleIdeInfos = buildRuleIdeInfo(noIdlTarget);
    RuleIdeInfo noIdlRuleInfo = getRuleInfoAndVerifyLabel(noIdlTarget, ruleIdeInfos);

    assertThat(noIdlRuleInfo.getAndroidRuleIdeInfo().getHasIdlSources()).isFalse();
  }

  public void testAndroidLibraryWithAidlHasIdlJars() throws Exception {
    scratch.file(
        "java/com/google/example/BUILD",
        "android_library(",
        "  name = 'has_idl',",
        "  idl_srcs = ['a.aidl'],",
        ")"
    );
    String idlTarget = "//java/com/google/example:has_idl";
    Map<String, RuleIdeInfo> ruleIdeInfos = buildRuleIdeInfo(idlTarget);
    RuleIdeInfo idlRuleInfo = getRuleInfoAndVerifyLabel(idlTarget, ruleIdeInfos);

    assertThat(idlRuleInfo.getAndroidRuleIdeInfo().getHasIdlSources()).isTrue();
    assertThat(LIBRARY_ARTIFACT_TO_STRING.apply(idlRuleInfo.getAndroidRuleIdeInfo().getIdlJar()))
        .isEqualTo(jarString("java/com/google/example",
            "libhas_idl-idl.jar", null, "libhas_idl-idl.srcjar"));
    assertThat(relativePathsForSourcesOf(idlRuleInfo))
        .isEmpty();
    assertThat(getIdeResolveFiles()).containsExactly(
        "java/com/google/example/libhas_idl.jar",
        "java/com/google/example/libhas_idl-ijar.jar",
        "java/com/google/example/libhas_idl-src.jar",
        "java/com/google/example/libhas_idl-idl.jar",
        "java/com/google/example/libhas_idl-idl.srcjar"
    );
  }

  public void testAndroidLibraryGeneratedManifestIsAddedToOutputGroup() throws Exception {
    scratch.file(
        "com/google/example/BUILD",
        "android_library(",
        "  name = 'lib',",
        "  manifest = ':manifest',",
        "  custom_package = 'com.google.example',",
        ")",
        "genrule(",
        "  name = 'manifest',",
        "  outs = ['AndroidManifest.xml'],",
        "  cmd = '',",
        ")");
    buildTarget("//com/google/example:lib");
    assertThat(getIdeResolveFiles()).containsExactly(
        "com/google/example/liblib.jar",
        "com/google/example/liblib-src.jar",
        "com/google/example/lib_resources.jar",
        "com/google/example/lib_resources-ijar.jar",
        "com/google/example/lib_resources-src.jar",
        "com/google/example/AndroidManifest.xml"
    );
  }

  public void testJavaLibraryWithoutGeneratedSourcesHasNoGenJars() throws Exception {
    scratch.file(
        "java/com/google/example/BUILD",
        "java_library(",
        "  name = 'no_plugin',",
        "  srcs = ['Test.java'],",
        ")"
    );
    Map<String, RuleIdeInfo> ruleIdeInfos = buildRuleIdeInfo("//java/com/google/example:no_plugin");
    RuleIdeInfo ruleIdeInfo = getRuleInfoAndVerifyLabel(
        "//java/com/google/example:no_plugin", ruleIdeInfos);

    assertThat(ruleIdeInfo.getJavaRuleIdeInfo().getGeneratedJarsList())
        .isEmpty();
  }

  public void testJavaLibraryWithGeneratedSourcesHasGenJars() throws Exception {
    scratch.file(
        "java/com/google/example/BUILD",
        "java_library(",
        "  name = 'test',",
        "  srcs = ['Test.java'],",
        "  plugins = [':plugin']",
        ")",
        "java_plugin(",
        "  name = 'plugin',",
        "  processor_class = 'com.google.example.Plugin',",
        "  deps = ['plugin_lib'],",
        ")",
        "java_library(",
        "  name = 'plugin_lib',",
        "  srcs = ['Plugin.java'],",
        ")"
    );
    Map<String, RuleIdeInfo> ruleIdeInfos = buildRuleIdeInfo("//java/com/google/example:test");
    RuleIdeInfo ruleIdeInfo = getRuleInfoAndVerifyLabel(
        "//java/com/google/example:test", ruleIdeInfos);

    assertThat(transform(
        ruleIdeInfo.getJavaRuleIdeInfo().getGeneratedJarsList(),
        LIBRARY_ARTIFACT_TO_STRING))
        .containsExactly(jarString("java/com/google/example",
            "libtest-gen.jar", null, "libtest-gensrc.jar"));
    assertThat(getIdeResolveFiles()).containsExactly(
        "java/com/google/example/libtest.jar",
        "java/com/google/example/libtest-ijar.jar",
        "java/com/google/example/libtest-src.jar",
        "java/com/google/example/libtest-gen.jar",
        "java/com/google/example/libtest-gensrc.jar"
    );
  }
  
  public void testNonConformingPackageName() throws Exception {
    scratch.file(
        "bad/package/google/example/BUILD",
        "android_library(",
        "  name = 'test',",
        "  srcs = ['Test.java'],",
        ")"
    );
    Map<String, RuleIdeInfo> ruleIdeInfos = buildRuleIdeInfo("//bad/package/google/example:test");
    RuleIdeInfo ruleInfo = getRuleInfoAndVerifyLabel(
        "//bad/package/google/example:test", ruleIdeInfos);

    assertThat(ruleInfo.getAndroidRuleIdeInfo().getJavaPackage())
        .isEqualTo("bad.package.google.example");
  }

  public void testTags() throws Exception {
    scratch.file(
        "com/google/example/BUILD",
        "java_library(",
        "    name = 'lib',",
        "    srcs = ['Test.java'],",
        "    tags = ['d', 'b', 'c', 'a'],",
        ")");
    Map<String, RuleIdeInfo> ruleIdeInfos = buildRuleIdeInfo("//com/google/example:lib");
    RuleIdeInfo ruleIdeInfo = getRuleInfoAndVerifyLabel("//com/google/example:lib", ruleIdeInfos);
    assertThat(ruleIdeInfo.getTagsList())
        .containsExactly("a", "b", "c", "d");
  }

  public void testAndroidLibraryWithoutSourcesExportsDependencies() throws Exception {
    scratch.file(
        "java/com/google/example/BUILD",
        "android_library(",
        "  name = 'lib',",
        "  srcs = ['Test.java']",
        ")",
        "android_library(",
        "  name = 'forward',",
        "  deps = [':lib'],",
        ")",
        "android_library(",
        "  name = 'super',",
        "  deps = [':forward'],",
        ")");
    Map<String, RuleIdeInfo> ruleIdeInfos = buildRuleIdeInfo("//java/com/google/example:super");
    RuleIdeInfo ruleInfo = getRuleInfoAndVerifyLabel(
        "//java/com/google/example:super", ruleIdeInfos);

    assertThat(ruleInfo.getDependenciesList()).containsExactly(
        "//java/com/google/example:forward",
        "//java/com/google/example:lib");
  }

  public void testSourceFilesAreCorrectlyMarkedAsSourceOrGenerated() throws Exception {
    scratch.file(
        "com/google/example/BUILD",
        "genrule(",
        "   name = 'gen',",
        "   outs = ['gen.java'],",
        "   cmd = '',",
        ")",
        "java_library(",
        "    name = 'lib',",
        "    srcs = ['Test.java', ':gen'],",
        ")");
    Map<String, RuleIdeInfo> ruleIdeInfos = buildRuleIdeInfo("//com/google/example:lib");
    RuleIdeInfo ruleIdeInfo = getRuleInfoAndVerifyLabel("//com/google/example:lib", ruleIdeInfos);
    assertThat(ruleIdeInfo.getJavaRuleIdeInfo().getSourcesList()).containsExactly(
        ArtifactLocation.newBuilder()
            .setRootPath(targetConfig.getGenfilesDirectory().getPath().getPathString())
            .setRelativePath("com/google/example/gen.java")
            .setIsSource(false)
            .build(),
        ArtifactLocation.newBuilder()
            .setRootPath(directories.getWorkspace().getPathString())
            .setRelativePath("com/google/example/Test.java")
            .setIsSource(true)
            .build());
  }

  public void testAspectIsPropagatedAcrossRuntimeDeps() throws Exception {
    scratch.file(
        "com/google/example/BUILD",
        "java_library(",
        "   name = 'foobar',",
        "   srcs = ['FooBar.java'],",
        ")",
        "java_library(",
        "   name = 'lib',",
        "   srcs = ['Lib.java'],",
        "   runtime_deps = [':foobar'],",
        ")");

    Map<String, RuleIdeInfo> ruleIdeInfos = buildRuleIdeInfo("//com/google/example:lib");
    // Fails if aspect was not propagated
    getRuleInfoAndVerifyLabel("//com/google/example:foobar", ruleIdeInfos);

    RuleIdeInfo libInfo = getRuleInfoAndVerifyLabel("//com/google/example:foobar", ruleIdeInfos);
    assertThat(libInfo.getDependenciesList()).isEmpty();
  }
}
