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
package com.google.devtools.build.lib.skyframe;

import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.analysis.buildinfo.BuildInfoCollection;
import com.google.devtools.build.lib.analysis.buildinfo.BuildInfoFactory;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.skyframe.SkyFunctionName;

import java.util.Objects;

/**
 * Value that stores {@link BuildInfoCollection}s generated by {@link BuildInfoFactory} instances.
 * These collections are used during analysis (see {@code CachingAnalysisEnvironment}).
 */
public class BuildInfoCollectionValue extends ActionLookupValue {
  private final BuildInfoCollection collection;

  BuildInfoCollectionValue(BuildInfoCollection collection) {
    super(collection.getActions());
    this.collection = collection;
  }

  public BuildInfoCollection getCollection() {
    return collection;
  }

  @Override
  public String toString() {
    return com.google.common.base.MoreObjects.toStringHelper(getClass())
        .add("collection", collection)
        .add("generatingActionMap", generatingActionMap).toString();
  }

  /** Key for BuildInfoCollectionValues. */
  public static class BuildInfoKeyAndConfig extends ActionLookupKey {
    private final BuildInfoFactory.BuildInfoKey infoKey;
    private final BuildConfiguration config;

    public BuildInfoKeyAndConfig(BuildInfoFactory.BuildInfoKey key, BuildConfiguration config) {
      this.infoKey = Preconditions.checkNotNull(key, config);
      this.config = Preconditions.checkNotNull(config, key);
    }

    @Override
    SkyFunctionName getType() {
      return SkyFunctions.BUILD_INFO_COLLECTION;
    }

    BuildInfoFactory.BuildInfoKey getInfoKey() {
      return infoKey;
    }

    BuildConfiguration getConfig() {
      return config;
    }

    @Override
    public Label getLabel() {
      return null;
    }

    @Override
    public int hashCode() {
      return Objects.hash(infoKey, config);
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (other == null) {
        return false;
      }
      if (this.getClass() != other.getClass()) {
        return false;
      }
      BuildInfoKeyAndConfig that = (BuildInfoKeyAndConfig) other;
      return Objects.equals(this.infoKey, that.infoKey) && Objects.equals(this.config, that.config);
    }
  }
}
