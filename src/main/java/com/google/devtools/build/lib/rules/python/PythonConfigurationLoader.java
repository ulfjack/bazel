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
package com.google.devtools.build.lib.rules.python;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration.Fragment;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.ConfigurationEnvironment;
import com.google.devtools.build.lib.analysis.config.ConfigurationFragmentFactory;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.analysis.config.InvalidConfigurationException;
import com.google.devtools.build.lib.rules.cpp.CppConfiguration;
import com.google.devtools.build.lib.rules.cpp.CppOptions;
import com.google.devtools.build.lib.rules.cpp.CrosstoolConfigurationLoader;
import com.google.devtools.build.lib.syntax.Label;
import com.google.devtools.build.lib.view.config.crosstool.CrosstoolConfig;

import javax.annotation.Nullable;

/**
 * A factory implementation for {@link PythonConfiguration} objects.
 */
public class PythonConfigurationLoader implements ConfigurationFragmentFactory {
  private final Function<String, String> cpuTransformer;

  public PythonConfigurationLoader(Function<String, String> cpuTransformer) {
    this.cpuTransformer = cpuTransformer;
  }

  @Override
  public ImmutableSet<Class<? extends FragmentOptions>> requiredOptions() {
    return ImmutableSet.of(PythonOptions.class, CppOptions.class);
  }

  @Nullable
  private CrosstoolConfig.CToolchain getToolchain(
      ConfigurationEnvironment env, BuildOptions buildOptions, Label crosstoolTop)
      throws InvalidConfigurationException {
    CrosstoolConfigurationLoader.CrosstoolFile file =
        CrosstoolConfigurationLoader.readCrosstool(env, crosstoolTop);
    if (file == null) {
      return null;
    }
    return CrosstoolConfigurationLoader.selectToolchain(
        file.getProto(), buildOptions, cpuTransformer);
  }

  @Override
  public PythonConfiguration create(ConfigurationEnvironment env, BuildOptions buildOptions)
      throws InvalidConfigurationException {
    PythonOptions pythonOptions = buildOptions.get(PythonOptions.class);
    CppConfiguration cppConfiguration = env.getFragment(buildOptions, CppConfiguration.class);
    if (cppConfiguration == null) {
      return null;
    }

    CrosstoolConfig.CToolchain toolchain = getToolchain(
        env, buildOptions, buildOptions.get(CppOptions.class).crosstoolTop);
    if (toolchain == null) {
      return null;
    }

    boolean ignorePythonVersionAttribute = pythonOptions.forcePython != null;
    PythonVersion pythonVersion = pythonOptions.getPythonVersion();

    return new PythonConfiguration(pythonVersion, ignorePythonVersionAttribute);
  }

  @Override
  public Class<? extends Fragment> creates() {
    return PythonConfiguration.class;
  }
}

