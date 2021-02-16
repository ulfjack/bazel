// Copyright 2019 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.analysis.platform;

import build.bazel.remote.execution.v2.Platform;
import build.bazel.remote.execution.v2.Platform.Property;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Ordering;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.UserExecException;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.Spawn.Code;
import com.google.protobuf.TextFormat;
import com.google.protobuf.TextFormat.ParseException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import javax.annotation.Nullable;

/** Utilities for accessing platform properties. */
public final class PlatformUtils {

  private static void sortPlatformProperties(Platform.Builder builder) {
    List<Platform.Property> properties =
        Ordering.from(Comparator.comparing(Platform.Property::getName))
            .sortedCopy(builder.getPropertiesList());
    builder.clearProperties();
    builder.addAllProperties(properties);
  }

  @Nullable
  public static Platform buildPlatformProto(Map<String, String> executionProperties) {
    if (executionProperties.isEmpty()) {
      return null;
    }
    Platform.Builder builder = Platform.newBuilder();
    for (Map.Entry<String, String> keyValue : executionProperties.entrySet()) {
      Property property =
          Property.newBuilder().setName(keyValue.getKey()).setValue(keyValue.getValue()).build();
      builder.addProperties(property);
    }

    sortPlatformProperties(builder);
    return builder.build();
  }

  @Nullable
  public static Platform getPlatformProto(Spawn spawn, @Nullable RemoteOptions remoteOptions)
      throws UserExecException {
    return getPlatformProto(spawn, remoteOptions, ImmutableMap.of());
  }

  @Nullable
  public static Platform getPlatformProto(
      Spawn spawn,
      @Nullable RemoteOptions remoteOptions,
      Map<String, String> additionalProperties)
      throws UserExecException {
    SortedMap<String, String> defaultExecProperties =
        remoteOptions != null
            ? remoteOptions.getRemoteDefaultExecProperties()
            : ImmutableSortedMap.of();

    if (spawn.getExecutionPlatform() == null
        && spawn.getCombinedExecProperties().isEmpty()
        && defaultExecProperties.isEmpty()
        && additionalProperties.isEmpty()) {
      return null;
    }

    Map<String, String> properties;
    if (!spawn.getCombinedExecProperties().isEmpty()) {
      properties = spawn.getCombinedExecProperties();
    } else if (spawn.getExecutionPlatform() != null
        && !Strings.isNullOrEmpty(spawn.getExecutionPlatform().remoteExecutionProperties())) {
      properties = new HashMap<>();
      // Try and get the platform info from the execution properties. This is pretty inefficient; it
      // would be better to store the parsed properties instead of the String text proto.
      try {
        Platform.Builder platformBuilder = Platform.newBuilder();
        TextFormat.getParser()
            .merge(spawn.getExecutionPlatform().remoteExecutionProperties(), platformBuilder);
        for (Property property : platformBuilder.getPropertiesList()) {
          properties.put(property.getName(), property.getValue());
        }
      } catch (ParseException e) {
        String message =
            String.format(
                "Failed to parse remote_execution_properties from platform %s",
                spawn.getExecutionPlatform().label());
        throw new UserExecException(
            e, createFailureDetail(message, Code.INVALID_REMOTE_EXECUTION_PROPERTIES));
      }
    } else {
      properties = defaultExecProperties;
    }

    if (!additionalProperties.isEmpty()) {
      if (properties.isEmpty()) {
        properties = additionalProperties;
      } else {
        // Merge the two maps.
        properties = new HashMap<>(properties);
        properties.putAll(additionalProperties);
      }
    }

    Platform.Builder platformBuilder = Platform.newBuilder();
    for (Map.Entry<String, String> entry : properties.entrySet()) {
      platformBuilder.addPropertiesBuilder().setName(entry.getKey()).setValue(entry.getValue());
    }
    sortPlatformProperties(platformBuilder);
    return platformBuilder.build();
  }

  private static FailureDetail createFailureDetail(String message, Code detailedCode) {
    return FailureDetail.newBuilder()
        .setMessage(message)
        .setSpawn(FailureDetails.Spawn.newBuilder().setCode(detailedCode))
        .build();
  }
}
