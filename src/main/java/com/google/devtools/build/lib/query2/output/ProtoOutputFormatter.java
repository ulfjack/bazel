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
package com.google.devtools.build.lib.query2.output;

import static com.google.devtools.build.lib.query2.proto.proto2api.Build.Target.Discriminator.ENVIRONMENT_GROUP;
import static com.google.devtools.build.lib.query2.proto.proto2api.Build.Target.Discriminator.GENERATED_FILE;
import static com.google.devtools.build.lib.query2.proto.proto2api.Build.Target.Discriminator.PACKAGE_GROUP;
import static com.google.devtools.build.lib.query2.proto.proto2api.Build.Target.Discriminator.RULE;
import static com.google.devtools.build.lib.query2.proto.proto2api.Build.Target.Discriminator.SOURCE_FILE;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.graph.Digraph;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.EnvironmentGroup;
import com.google.devtools.build.lib.packages.InputFile;
import com.google.devtools.build.lib.packages.OutputFile;
import com.google.devtools.build.lib.packages.PackageGroup;
import com.google.devtools.build.lib.packages.PackageSerializer;
import com.google.devtools.build.lib.packages.ProtoUtils;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.query2.FakeSubincludeTarget;
import com.google.devtools.build.lib.query2.output.AspectResolver.BuildFileDependencyMode;
import com.google.devtools.build.lib.query2.output.OutputFormatter.UnorderedFormatter;
import com.google.devtools.build.lib.query2.proto.proto2api.Build;
import com.google.devtools.build.lib.syntax.Label;
import com.google.devtools.build.lib.syntax.SkylarkEnvironment;
import com.google.devtools.build.lib.util.BinaryPredicate;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map.Entry;
import java.util.Set;

/**
 * An output formatter that outputs a protocol buffer representation
 * of a query result and outputs the proto bytes to the output print stream.
 * By taking the bytes and calling {@code mergeFrom()} on a
 * {@code Build.QueryResult} object the full result can be reconstructed.
 */
public class ProtoOutputFormatter extends OutputFormatter implements UnorderedFormatter {

  /**
   * A special attribute name for the rule implementation hash code.
   */
  public static final String RULE_IMPLEMENTATION_HASH_ATTR_NAME = "$rule_implementation_hash";

  private BinaryPredicate<Rule, Attribute> dependencyFilter;
  private boolean relativeLocations = false;
  protected boolean includeDefaultValues = true;
  protected AspectResolver aspectResolver;

  protected void setDependencyFilter(QueryOptions options) {
    this.dependencyFilter = OutputFormatter.getDependencyFilter(options);
  }

  @Override
  public String getName() {
    return "proto";
  }

  @Override
  public void outputUnordered(QueryOptions options, Iterable<Target> result, PrintStream out,
      AspectResolver aspectResolver) throws IOException, InterruptedException {
    relativeLocations = options.relativeLocations;
    this.aspectResolver = aspectResolver;
    this.includeDefaultValues = options.protoIncludeDefaultValues;
    setDependencyFilter(options);

    Build.QueryResult.Builder queryResult = Build.QueryResult.newBuilder();
    for (Target target : result) {
      addTarget(queryResult, target);
    }

    queryResult.build().writeTo(out);
  }

  @Override
  public void output(QueryOptions options, Digraph<Target> result, PrintStream out,
      AspectResolver aspectResolver) throws IOException, InterruptedException {
    outputUnordered(options, result.getLabels(), out, aspectResolver);
  }

  /**
   * Add the target to the query result.
   * @param queryResult The query result that contains all rule, input and
   *   output targets.
   * @param target The query target being converted to a protocol buffer.
   */
  private void addTarget(Build.QueryResult.Builder queryResult, Target target)
      throws InterruptedException {
    queryResult.addTarget(toTargetProtoBuffer(target));
  }

  /**
   * Converts a logical Target object into a Target protobuffer.
   */
  protected Build.Target toTargetProtoBuffer(Target target)
      throws InterruptedException {
    Build.Target.Builder targetPb = Build.Target.newBuilder();

    String location = getLocation(target, relativeLocations);
    if (target instanceof Rule) {
      Rule rule = (Rule) target;
      Build.Rule.Builder rulePb = Build.Rule.newBuilder()
          .setName(rule.getLabel().toString())
          .setRuleClass(rule.getRuleClass())
          .setLocation(location);

      for (Attribute attr : rule.getAttributes()) {
        if (!includeDefaultValues && !rule.isAttributeValueExplicitlySpecified(attr)) {
          continue;
        }
        PackageSerializer.addAttributeToProto(rulePb, attr,
            PackageSerializer.getAttributeValues(rule, attr), null,
            rule.isAttributeValueExplicitlySpecified(attr), false);
      }

      SkylarkEnvironment env = rule.getRuleClassObject().getRuleDefinitionEnvironment();
      if (env != null) {
        // The RuleDefinitionEnvironment is always defined for Skylark rules and
        // always null for non Skylark rules.
        rulePb.addAttribute(
            Build.Attribute.newBuilder()
                .setName(RULE_IMPLEMENTATION_HASH_ATTR_NAME)
                .setType(ProtoUtils.getDiscriminatorFromType(
                    com.google.devtools.build.lib.packages.Type.STRING))
                .setStringValue(env.getTransitiveFileContentHashCode()));
      }

      ImmutableMultimap<Attribute, Label> aspectsDependencies =
          aspectResolver.computeAspectDependencies(target);
      // Add information about additional attributes from aspects.
      for (Entry<Attribute, Collection<Label>> entry : aspectsDependencies.asMap().entrySet()) {
        PackageSerializer.addAttributeToProto(rulePb, entry.getKey(),
            Lists.<Object>newArrayList(entry.getValue()), null, false, false);
      }
      // Add all deps from aspects as rule inputs of current target.
      for (Label label : aspectsDependencies.values()) {
        rulePb.addRuleInput(label.toString());
      }

      // Include explicit elements for all direct inputs and outputs of a rule;
      // this goes beyond what is available from the attributes above, since it
      // may also (depending on options) include implicit outputs,
      // host-configuration outputs, and default values.
      for (Label label : rule.getLabels(dependencyFilter)) {
        rulePb.addRuleInput(label.toString());
      }
      for (OutputFile outputFile : rule.getOutputFiles()) {
        Label fileLabel = outputFile.getLabel();
        rulePb.addRuleOutput(fileLabel.toString());
      }
      for (String feature : rule.getFeatures()) {
        rulePb.addDefaultSetting(feature);
      }

      targetPb.setType(RULE);
      targetPb.setRule(rulePb);
    } else if (target instanceof OutputFile) {
      OutputFile outputFile = (OutputFile) target;
      Label label = outputFile.getLabel();

      Rule generatingRule = outputFile.getGeneratingRule();
      Build.GeneratedFile output = Build.GeneratedFile.newBuilder()
          .setLocation(location)
          .setGeneratingRule(generatingRule.getLabel().toString())
          .setName(label.toString())
          .build();

      targetPb.setType(GENERATED_FILE);
      targetPb.setGeneratedFile(output);
    } else if (target instanceof InputFile) {
      InputFile inputFile = (InputFile) target;
      Label label = inputFile.getLabel();

      Build.SourceFile.Builder input = Build.SourceFile.newBuilder()
          .setLocation(location)
          .setName(label.toString());

      if (inputFile.getName().equals("BUILD")) {
        Set<Label> subincludeLabels = new LinkedHashSet<>();
        subincludeLabels.addAll(aspectResolver == null
            ? inputFile.getPackage().getSubincludeLabels()
            : aspectResolver.computeBuildFileDependencies(
                inputFile.getPackage(), BuildFileDependencyMode.SUBINCLUDE));
        subincludeLabels.addAll(aspectResolver == null
            ? inputFile.getPackage().getSkylarkFileDependencies()
            : aspectResolver.computeBuildFileDependencies(
                inputFile.getPackage(), BuildFileDependencyMode.SKYLARK));

        for (Label skylarkFileDep : subincludeLabels) {
          input.addSubinclude(skylarkFileDep.toString());
        }

        for (String feature : inputFile.getPackage().getFeatures()) {
          input.addFeature(feature);
        }
      }

      for (Label visibilityDependency : target.getVisibility().getDependencyLabels()) {
        input.addPackageGroup(visibilityDependency.toString());
      }

      for (Label visibilityDeclaration : target.getVisibility().getDeclaredLabels()) {
        input.addVisibilityLabel(visibilityDeclaration.toString());
      }

      targetPb.setType(SOURCE_FILE);
      targetPb.setSourceFile(input);
    } else if (target instanceof FakeSubincludeTarget) {
      Label label = target.getLabel();
      Build.SourceFile input = Build.SourceFile.newBuilder()
          .setLocation(location)
          .setName(label.toString())
          .build();

      targetPb.setType(SOURCE_FILE);
      targetPb.setSourceFile(input);
    } else if (target instanceof PackageGroup) {
      PackageGroup packageGroup = (PackageGroup) target;
      Build.PackageGroup.Builder packageGroupPb = Build.PackageGroup.newBuilder()
          .setName(packageGroup.getLabel().toString());
      for (String containedPackage : packageGroup.getContainedPackages()) {
        packageGroupPb.addContainedPackage(containedPackage);
      }
      for (Label include : packageGroup.getIncludes()) {
        packageGroupPb.addIncludedPackageGroup(include.toString());
      }

      targetPb.setType(PACKAGE_GROUP);
      targetPb.setPackageGroup(packageGroupPb);
    } else if (target instanceof EnvironmentGroup) {
      EnvironmentGroup envGroup = (EnvironmentGroup) target;
      Build.EnvironmentGroup.Builder envGroupPb =
          Build.EnvironmentGroup
              .newBuilder()
              .setName(envGroup.getLabel().toString());
      for (Label env : envGroup.getEnvironments()) {
        envGroupPb.addEnvironment(env.toString());
      }
      for (Label defaultEnv : envGroup.getDefaults()) {
        envGroupPb.addDefault(defaultEnv.toString());
      }
      targetPb.setType(ENVIRONMENT_GROUP);
      targetPb.setEnvironmentGroup(envGroupPb);
    } else {
      throw new IllegalArgumentException(target.toString());
    }

    return targetPb.build();
  }
}
