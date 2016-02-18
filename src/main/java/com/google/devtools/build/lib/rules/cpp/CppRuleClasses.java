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

package com.google.devtools.build.lib.rules.cpp;

import static com.google.devtools.build.lib.packages.ImplicitOutputsFunction.fromTemplates;
import static com.google.devtools.build.lib.rules.cpp.CppFileTypes.ALWAYS_LINK_LIBRARY;
import static com.google.devtools.build.lib.rules.cpp.CppFileTypes.ALWAYS_LINK_PIC_LIBRARY;
import static com.google.devtools.build.lib.rules.cpp.CppFileTypes.ARCHIVE;
import static com.google.devtools.build.lib.rules.cpp.CppFileTypes.ASSEMBLER;
import static com.google.devtools.build.lib.rules.cpp.CppFileTypes.ASSEMBLER_WITH_C_PREPROCESSOR;
import static com.google.devtools.build.lib.rules.cpp.CppFileTypes.CPP_HEADER;
import static com.google.devtools.build.lib.rules.cpp.CppFileTypes.CPP_SOURCE;
import static com.google.devtools.build.lib.rules.cpp.CppFileTypes.C_SOURCE;
import static com.google.devtools.build.lib.rules.cpp.CppFileTypes.OBJECT_FILE;
import static com.google.devtools.build.lib.rules.cpp.CppFileTypes.PIC_ARCHIVE;
import static com.google.devtools.build.lib.rules.cpp.CppFileTypes.PIC_OBJECT_FILE;
import static com.google.devtools.build.lib.rules.cpp.CppFileTypes.SHARED_LIBRARY;
import static com.google.devtools.build.lib.rules.cpp.CppFileTypes.VERSIONED_SHARED_LIBRARY;

import com.google.devtools.build.lib.analysis.LanguageDependentFragment.LibraryLanguage;
import com.google.devtools.build.lib.packages.ImplicitOutputsFunction.SafeImplicitOutputsFunction;
import com.google.devtools.build.lib.rules.test.InstrumentedFilesCollector.InstrumentationSpec;
import com.google.devtools.build.lib.util.FileTypeSet;

/**
 * Rule class definitions for C++ rules.
 */
public class CppRuleClasses {
  // Artifacts of these types are discarded from the 'hdrs' attribute in cc rules
  static final FileTypeSet DISALLOWED_HDRS_FILES = FileTypeSet.of(
      ARCHIVE,
      PIC_ARCHIVE,
      ALWAYS_LINK_LIBRARY,
      ALWAYS_LINK_PIC_LIBRARY,
      SHARED_LIBRARY,
      VERSIONED_SHARED_LIBRARY,
      OBJECT_FILE,
      PIC_OBJECT_FILE);

  /**
   * The set of instrumented source file types; keep this in sync with the list above. Note that
   * extension-less header files cannot currently be declared, so we cannot collect coverage for
   * those.
   */
  static final InstrumentationSpec INSTRUMENTATION_SPEC = new InstrumentationSpec(
      FileTypeSet.of(CPP_SOURCE, C_SOURCE, CPP_HEADER, ASSEMBLER_WITH_C_PREPROCESSOR,
          ASSEMBLER))
      .withSourceAttributes("srcs", "hdrs")
      .withDependencyAttributes("deps", "data");

  public static final LibraryLanguage LANGUAGE = new LibraryLanguage("C++");

  /**
   * Implicit outputs for cc_binary rules.
   */
  public static final SafeImplicitOutputsFunction CC_BINARY_STRIPPED =
      fromTemplates("%{name}.stripped");


  // Used for requesting dwp "debug packages".
  public static final SafeImplicitOutputsFunction CC_BINARY_DEBUG_PACKAGE =
      fromTemplates("%{name}.dwp");


  /**
   * Path of the build_interface_so script in the Blaze binary.
   */
  public static final String BUILD_INTERFACE_SO = "build_interface_so";

  /**
   * A string constant for the parse_headers feature.
   */
  public static final String PARSE_HEADERS = "parse_headers";

  /**
   * A string constant for the preprocess_headers feature.
   */
  public static final String PREPROCESS_HEADERS = "preprocess_headers";

  /**
   * A string constant for the module_maps feature; this is a precondition to the layering_check and
   * header_modules features.
   */
  public static final String MODULE_MAPS = "module_maps";

  /**
   * A string constant for the module_map_home_cwd feature.
   */
  public static final String MODULE_MAP_HOME_CWD = "module_map_home_cwd";

  /**
   * A string constant for the module_map_without_extern_module feature.
   *
   * <p>This features is a transitional feature; enabling it means that generated module maps
   * will not have "extern module" declarations inside them; instead, the module maps need
   * to be passed via the dependent_module_map_files build variable.
   *
   * <p>This variable is phrased negatively to aid the roll-out: currently, the default is that
   * "extern module" declarations are generated.
   */
  public static final String MODULE_MAP_WITHOUT_EXTERN_MODULE = "module_map_without_extern_module";

  /**
   * A string constant for the layering_check feature.
   */
  public static final String LAYERING_CHECK = "layering_check";

  /**
   * A string constant for the header_modules feature.
   */
  public static final String HEADER_MODULES = "header_modules";

  /**
   * A string constant for the use_header_modules feature.
   *
   * <p>This feature is only used during rollout; we expect to default enable this once we
   * have verified that module-enabled compilation is stable enough.
   */
  public static final String USE_HEADER_MODULES = "use_header_modules";

  /**
   * A string constant for the generate_submodules feature.
   *
   * <p>This feature is only used temporarily to make the switch to using submodules easier. With
   * submodules, each header of a cc_library is placed into a submodule of the module generated for
   * the appropriate target. As this influences the layering_check semantics and needs to be synced
   * with a clang release, we want to be able to switch back and forth easily.
   */
  public static final String GENERATE_SUBMODULES = "generate_submodules";

  /**
   * A string constant for the transitive_module_maps feature.
   *
   * <p>This feature is used temporarily to switch between adding transitive module maps to a
   * modules enabled build or only adding module maps from direct dependencies.
   */
  public static final String TRANSITIVE_MODULE_MAPS = "transitive_module_maps";

  /**
   * A string constant for switching on that a header module file includes information about
   * all its dependencies, so we do not need to pass all transitive dependent header modules on
   * the command line.
   */
  public static final String HEADER_MODULE_INCLUDES_DEPENDENCIES =
      "header_module_includes_dependencies";

  /**
   * A string constant for the no_legacy_features feature.
   *
   * <p>If this feature is enabled, Bazel will not extend the crosstool configuration with the
   * default legacy feature set.
   */
  public static final String NO_LEGACY_FEATURES = "no_legacy_features";

  /**
   * A string constant for the include_paths feature.
   */
  public static final String INCLUDE_PATHS = "include_paths";

  /**
   * A string constant for the ThinLTO feature.
   */
  public static final String THIN_LTO = "thin_lto";

  /*
   * A string constant for the fdo_instrument feature.
   */
  public static final String FDO_INSTRUMENT = "fdo_instrument";

  /**
   * A string constant for the fdo_optimize feature.
   */
  public static final String FDO_OPTIMIZE = "fdo_optimize";

  /**
   * A string constant for the autofdo feature.
   */
  public static final String AUTOFDO = "autofdo";

  /**
   * A string constant for the lipo feature.
   */
  public static final String LIPO = "lipo";

  /**
   * A string constant for the coverage feature.
   */
  public static final String COVERAGE = "coverage";
}
