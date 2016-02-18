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
package com.google.devtools.build.lib.query2;

import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSet.Builder;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.TargetParsingException;
import com.google.devtools.build.lib.cmdline.TargetPattern;
import com.google.devtools.build.lib.collect.CompactHashSet;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.graph.Digraph;
import com.google.devtools.build.lib.packages.BuildFileContainsErrorsException;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.NoSuchThingException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.pkgcache.TargetPatternEvaluator;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.query2.engine.AllRdepsFunction;
import com.google.devtools.build.lib.query2.engine.Callback;
import com.google.devtools.build.lib.query2.engine.QueryEvalResult;
import com.google.devtools.build.lib.query2.engine.QueryException;
import com.google.devtools.build.lib.query2.engine.QueryExpression;
import com.google.devtools.build.lib.query2.engine.QueryUtil.AbstractUniquifier;
import com.google.devtools.build.lib.query2.engine.QueryUtil.AggregateAllCallback;
import com.google.devtools.build.lib.query2.engine.Uniquifier;
import com.google.devtools.build.lib.skyframe.FileValue;
import com.google.devtools.build.lib.skyframe.GraphBackedRecursivePackageProvider;
import com.google.devtools.build.lib.skyframe.PackageLookupValue;
import com.google.devtools.build.lib.skyframe.PackageValue;
import com.google.devtools.build.lib.skyframe.PrepareDepsOfPatternsValue;
import com.google.devtools.build.lib.skyframe.RecursivePackageProviderBackedTargetPatternResolver;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.TargetPatternValue;
import com.google.devtools.build.lib.skyframe.TargetPatternValue.TargetPatternKey;
import com.google.devtools.build.lib.skyframe.TransitiveTraversalValue;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.EvaluationResult;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.WalkableGraph;
import com.google.devtools.build.skyframe.WalkableGraph.WalkableGraphFactory;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.logging.Logger;

import javax.annotation.Nullable;

/**
 * {@link AbstractBlazeQueryEnvironment} that introspects the Skyframe graph to find forward and
 * reverse edges. Results obtained by calling {@link #evaluateQuery} are not guaranteed to be in
 * any particular order. As well, this class eagerly loads the full transitive closure of targets,
 * even if the full closure isn't needed.
 */
public class SkyQueryEnvironment extends AbstractBlazeQueryEnvironment<Target> {

  private WalkableGraph graph;

  private ImmutableList<TargetPatternKey> universeTargetPatternKeys;

  private final BlazeTargetAccessor accessor = new BlazeTargetAccessor(this);
  private final int loadingPhaseThreads;
  private final WalkableGraphFactory graphFactory;
  private final List<String> universeScope;
  private final String parserPrefix;
  private final PathPackageLocator pkgPath;

  private static final Logger LOG = Logger.getLogger(SkyQueryEnvironment.class.getName());

  private static final Function<Target, Label> TARGET_LABEL_FUNCTION =
      new Function<Target, Label>() {
    
    @Override
    public Label apply(Target target) {
      return target.getLabel();
    }
  };

  public SkyQueryEnvironment(boolean keepGoing, boolean strictScope, int loadingPhaseThreads,
      Predicate<Label> labelFilter,
      EventHandler eventHandler,
      Set<Setting> settings,
      Iterable<QueryFunction> extraFunctions, String parserPrefix,
      WalkableGraphFactory graphFactory,
      List<String> universeScope, PathPackageLocator pkgPath) {
    super(keepGoing, strictScope, labelFilter,
        eventHandler,
        settings,
        extraFunctions);
    this.loadingPhaseThreads = loadingPhaseThreads;
    this.graphFactory = graphFactory;
    this.pkgPath = pkgPath;
    this.universeScope = Preconditions.checkNotNull(universeScope);
    this.parserPrefix = parserPrefix;
    Preconditions.checkState(!universeScope.isEmpty(),
        "No queries can be performed with an empty universe");
  }

  private void init() throws InterruptedException {
    long startTime = Profiler.nanoTimeMaybe();
    EvaluationResult<SkyValue> result =
        graphFactory.prepareAndGet(universeScope, parserPrefix, loadingPhaseThreads, eventHandler);
    graph = result.getWalkableGraph();
    long duration = Profiler.nanoTimeMaybe() - startTime;
    if (duration > 0) {
      LOG.info("Spent " + (duration / 1000 / 1000) + " ms on evaluation and walkable graph");
    }

    // The prepareAndGet call above evaluates a single PrepareDepsOfPatterns SkyKey.
    // We expect to see either a single successfully evaluated value or a cycle in the result.
    Collection<SkyValue> values = result.values();
    if (!values.isEmpty()) {
      Preconditions.checkState(values.size() == 1, "Universe query \"%s\" returned multiple"
              + " values unexpectedly (%s values in result)", universeScope, values.size());
      PrepareDepsOfPatternsValue prepareDepsOfPatternsValue =
          (PrepareDepsOfPatternsValue) Iterables.getOnlyElement(values);
      universeTargetPatternKeys = prepareDepsOfPatternsValue.getTargetPatternKeys();
    } else {
      // No values in the result, so there must be an error. We expect the error to be a cycle.
      boolean foundCycle = !Iterables.isEmpty(result.getError().getCycleInfo());
      Preconditions.checkState(foundCycle, "Universe query \"%s\" failed with non-cycle error: %s",
          universeScope, result.getError());
      universeTargetPatternKeys = ImmutableList.of();
    }
  }

  @Override
  public QueryEvalResult<Target> evaluateQuery(QueryExpression expr)
      throws QueryException, InterruptedException {
    // Some errors are reported as QueryExceptions and others as ERROR events (if --keep_going). The
    // result is set to have an error iff there were errors emitted during the query, so we reset
    // errors here.
    eventHandler.resetErrors();
    init();
    return super.evaluateQuery(expr);
  }

  private Map<Target, Collection<Target>> makeTargetsMap(Map<SkyKey, Iterable<SkyKey>> input) {
    ImmutableMap.Builder<Target, Collection<Target>> result = ImmutableMap.builder();
    
    Map<SkyKey, Target> allTargets = makeTargetsWithAssociations(
        Sets.newHashSet(Iterables.concat(input.values())));

    for (Map.Entry<SkyKey, Target> entry : makeTargetsWithAssociations(input.keySet()).entrySet()) {
      Iterable<SkyKey> skyKeys = input.get(entry.getKey());
      Set<Target> targets = CompactHashSet.createWithExpectedSize(Iterables.size(skyKeys));
      for (SkyKey key : skyKeys) {
        Target target = allTargets.get(key);
        if (target != null) {
          targets.add(target);
        }
      }
      result.put(entry.getValue(), targets);
    }
    return result.build();
  }

  private Map<Target, Collection<Target>> getRawFwdDeps(Iterable<Target> targets) {
    return makeTargetsMap(graph.getDirectDeps(makeTransitiveTraversalKeys(targets)));
  }

  private Map<Target, Collection<Target>> getRawReverseDeps(Iterable<Target> targets) {
    return makeTargetsMap(graph.getReverseDeps(makeTransitiveTraversalKeys(targets)));
  }

  private Set<Label> getAllowedDeps(Rule rule) {
    Set<Label> allowedLabels = new HashSet<>(rule.getTransitions(dependencyFilter).values());
    allowedLabels.addAll(rule.getVisibility().getDependencyLabels());
    // We should add deps from aspects, otherwise they are going to be filtered out.
    allowedLabels.addAll(rule.getAspectLabelsSuperset(dependencyFilter));
    return allowedLabels;
  }

  private Collection<Target> filterFwdDeps(Target target, Collection<Target> rawFwdDeps) {
    if (!(target instanceof Rule)) {
      return rawFwdDeps;
    }
    final Set<Label> allowedLabels = getAllowedDeps((Rule) target);
    return Collections2.filter(rawFwdDeps,
        new Predicate<Target>() {
          @Override
          public boolean apply(Target target) {
            return allowedLabels.contains(target.getLabel());
          }
        });
  }

  @Override
  public Collection<Target> getFwdDeps(Iterable<Target> targets) {
    Set<Target> result = new HashSet<>();
    for (Map.Entry<Target, Collection<Target>> entry : getRawFwdDeps(targets).entrySet()) {
      result.addAll(filterFwdDeps(entry.getKey(), entry.getValue()));
    }
    return result;
  }

  @Override
  public Collection<Target> getReverseDeps(Iterable<Target> targets) {
    Set<Target> result = CompactHashSet.create();
    Map<Target, Collection<Target>> rawReverseDeps = getRawReverseDeps(targets);

    CompactHashSet<Target> visited = CompactHashSet.create();

    Set<Label> keys = CompactHashSet.create(Collections2.transform(rawReverseDeps.keySet(),
        TARGET_LABEL_FUNCTION));
    for (Collection<Target> parentCollection : rawReverseDeps.values()) {
      for (Target parent : parentCollection) {
        if (visited.add(parent)) {
          if (parent instanceof Rule) {
            for (Label label : getAllowedDeps((Rule) parent)) {
              if (keys.contains(label)) {
                result.add(parent);
              }
            }
          } else {
            result.add(parent);
          }
        }
      }
    }
    return result;
  }

  @Override
  public Set<Target> getTransitiveClosure(Set<Target> targets) {
    Set<Target> visited = new HashSet<>();
    Collection<Target> current = targets;
    while (!current.isEmpty()) {
      Collection<Target> toVisit = Collections2.filter(current,
          Predicates.not(Predicates.in(visited)));
      current = getFwdDeps(toVisit);
      visited.addAll(toVisit);
    }
    return ImmutableSet.copyOf(visited);
  }

  // Implemented with a breadth-first search.
  @Override
  public Set<Target> getNodesOnPath(Target from, Target to) {
    // Tree of nodes visited so far.
    Map<Target, Target> nodeToParent = new HashMap<>();
    // Contains all nodes left to visit in a (LIFO) stack.
    Deque<Target> toVisit = new ArrayDeque<>();
    toVisit.add(from);
    nodeToParent.put(from, null);
    while (!toVisit.isEmpty()) {
      Target current = toVisit.removeFirst();
      if (to.equals(current)) {
        return ImmutableSet.copyOf(Digraph.getPathToTreeNode(nodeToParent, to));
      }
      for (Target dep : getFwdDeps(ImmutableList.of(current))) {
        if (!nodeToParent.containsKey(dep)) {
          nodeToParent.put(dep, current);
          toVisit.addFirst(dep);
        }
      }
    }
    // Note that the only current caller of this method checks first to see if there is a path
    // before calling this method. It is not clear what the return value should be here.
    return null;
  }

  @Override
  public void eval(QueryExpression expr, Callback<Target> callback)
      throws QueryException, InterruptedException {
    AggregateAllCallback<Target> aggregator = new AggregateAllCallback<>();
    expr.eval(this, aggregator);
    callback.process(aggregator.getResult());
  }

  @Override
  public Uniquifier<Target> createUniquifier() {
    return new AbstractUniquifier<Target, Label>() {
      @Override
      protected Label extractKey(Target target) {
        return target.getLabel();
      }
    };
  }

  @Override
  public Set<Target> getTargetsMatchingPattern(QueryExpression owner, String pattern)
      throws QueryException {
    Set<Target> targets = new LinkedHashSet<>(resolvedTargetPatterns.get(pattern));

    // Sets.filter would be more convenient here, but can't deal with exceptions.
    Iterator<Target> targetIterator = targets.iterator();
    while (targetIterator.hasNext()) {
      Target target = targetIterator.next();
      if (!validateScope(target.getLabel(), strictScope)) {
        targetIterator.remove();
      }
    }
    return targets;
  }

  @Override
  public Set<Target> getBuildFiles(QueryExpression caller, Set<Target> nodes)
      throws QueryException {
    Set<Target> dependentFiles = new LinkedHashSet<>();
    Set<Package> seenPackages = new HashSet<>();
    // Keep track of seen labels, to avoid adding a fake subinclude label that also exists as a
    // real target.
    Set<Label> seenLabels = new HashSet<>();

    // Adds all the package definition files (BUILD files and build
    // extensions) for package "pkg", to "buildfiles".
    for (Target x : nodes) {
      Package pkg = x.getPackage();
      if (seenPackages.add(pkg)) {
        addIfUniqueLabel(pkg.getBuildFile(), seenLabels, dependentFiles);
        for (Label subinclude
            : Iterables.concat(pkg.getSubincludeLabels(), pkg.getSkylarkFileDependencies())) {
          addIfUniqueLabel(getSubincludeTarget(subinclude, pkg), seenLabels, dependentFiles);

          // Also add the BUILD file of the subinclude.
          try {
            addIfUniqueLabel(getSubincludeTarget(
                subinclude.getLocalTargetLabel("BUILD"), pkg), seenLabels, dependentFiles);
          } catch (LabelSyntaxException e) {
            throw new AssertionError("BUILD should always parse as a target name", e);
          }
        }
      }
    }
    return dependentFiles;
  }

  private static void addIfUniqueLabel(Target node, Set<Label> labels, Set<Target> nodes) {
    if (labels.add(node.getLabel())) {
      nodes.add(node);
    }
  }

  private static Target getSubincludeTarget(final Label label, Package pkg) {
    return new FakeSubincludeTarget(label, pkg);
  }

  @Override
  public TargetAccessor<Target> getAccessor() {
    return accessor;
  }

  private SkyKey getPackageKeyAndValidateLabel(Label label) throws QueryException {
    // Can't use strictScope here because we are expecting a target back.
    validateScope(label, true);
    return PackageValue.key(label.getPackageIdentifier());
  }

  @Override
  public Target getTarget(Label label) throws TargetNotFoundException, QueryException {
    SkyKey packageKey = getPackageKeyAndValidateLabel(label);
    if (!graph.exists(packageKey)) {
      throw new QueryException(packageKey + " does not exist in graph");
    }
    try {
      PackageValue packageValue = (PackageValue) graph.getValue(packageKey);
      if (packageValue != null) {
        Package pkg = packageValue.getPackage();
        if (pkg.containsErrors()) {
          throw new BuildFileContainsErrorsException(label.getPackageIdentifier());
        }
        return packageValue.getPackage().getTarget(label.getName());
      } else {
        throw (NoSuchThingException) Preconditions.checkNotNull(
            graph.getException(packageKey), label);
      }
    } catch (NoSuchThingException e) {
      throw new TargetNotFoundException(e);
    }
  }

  @Override
  public void buildTransitiveClosure(QueryExpression caller, Set<Target> targets, int maxDepth)
      throws QueryException {
    // Everything has already been loaded, so here we just check for errors so that we can
    // pre-emptively throw/report if needed.
    Iterable<SkyKey> transitiveTraversalKeys = makeTransitiveTraversalKeys(targets);
    ImmutableList.Builder<String> errorMessagesBuilder = ImmutableList.builder();

    // First, look for errors in the successfully evaluated TransitiveTraversalValues. They may
    // have encountered errors that they were able to recover from.
    Set<Entry<SkyKey, SkyValue>> successfulEntries =
        graph.getSuccessfulValues(transitiveTraversalKeys).entrySet();
    Builder<SkyKey> successfulKeysBuilder = ImmutableSet.builder();
    for (Entry<SkyKey, SkyValue> successfulEntry : successfulEntries) {
      successfulKeysBuilder.add(successfulEntry.getKey());
      TransitiveTraversalValue value = (TransitiveTraversalValue) successfulEntry.getValue();
      String firstErrorMessage = value.getFirstErrorMessage();
      if (firstErrorMessage != null) {
        errorMessagesBuilder.add(firstErrorMessage);
      }
    }
    ImmutableSet<SkyKey> successfulKeys = successfulKeysBuilder.build();

    // Next, look for errors from the unsuccessfully evaluated TransitiveTraversal skyfunctions.
    Iterable<SkyKey> unsuccessfulKeys =
        Iterables.filter(transitiveTraversalKeys, Predicates.not(Predicates.in(successfulKeys)));
    Set<Entry<SkyKey, Exception>> errorEntries =
        graph.getMissingAndExceptions(unsuccessfulKeys).entrySet();
    for (Map.Entry<SkyKey, Exception> entry : errorEntries) {
      if (entry.getValue() == null) {
        throw new QueryException(entry.getKey().argument() + " does not exist in graph");
      }
      errorMessagesBuilder.add(entry.getValue().getMessage());
    }

    // Lastly, report all found errors.
    ImmutableList<String> errorMessages = errorMessagesBuilder.build();
    for (String errorMessage : errorMessages) {
      reportBuildFileError(caller, errorMessage);
    }
  }

  private Set<Target> filterTargetsNotInGraph(Set<Target> targets) {
    Map<Target, SkyKey> map = Maps.toMap(targets, TARGET_TO_SKY_KEY);
    Set<SkyKey> present = graph.getSuccessfulValues(map.values()).keySet();
    if (present.size() == targets.size()) {
      // Optimize for case of all targets being in graph.
      return targets;
    }
    return Maps.filterValues(map, Predicates.in(present)).keySet();
  }

  @Override
  protected Map<String, Set<Target>> preloadOrThrow(
      QueryExpression caller, Collection<String> patterns)
      throws QueryException, TargetParsingException {
    GraphBackedRecursivePackageProvider provider =
        new GraphBackedRecursivePackageProvider(graph, universeTargetPatternKeys, pkgPath);
    Map<String, Set<Target>> result = Maps.newHashMapWithExpectedSize(patterns.size());

    Map<String, SkyKey> keys = new HashMap<>(patterns.size());

    for (String pattern : patterns) {
      keys.put(pattern, TargetPatternValue.key(pattern,
          TargetPatternEvaluator.DEFAULT_FILTERING_POLICY, parserPrefix));
    }
    // Get all the patterns in one batch call
    Map<SkyKey, SkyValue> existingPatterns = graph.getSuccessfulValues(keys.values());

    Map<String, Set<Target>> patternsWithTargetsToFilter = new HashMap<>();
    for (String pattern : patterns) {
      SkyKey patternKey = keys.get(pattern);
      TargetParsingException targetParsingException = null;
      if (existingPatterns.containsKey(patternKey)) {
        // The graph already contains a value or exception for this target pattern, so we use it.
        TargetPatternValue value = (TargetPatternValue) existingPatterns.get(patternKey);
        if (value != null) {
          result.put(
              pattern, ImmutableSet.copyOf(makeTargetsFromLabels(value.getTargets().getTargets())));
        } else {
          // Because the graph was always initialized via a keep_going build, we know that the
          // exception stored here must be a TargetParsingException. Thus the comment in
          // SkyframeTargetPatternEvaluator#parseTargetPatternKeys describing the situation in which
          // the exception acceptance must be looser does not apply here.
          targetParsingException =
              (TargetParsingException)
                  Preconditions.checkNotNull(graph.getException(patternKey), pattern);
        }
      } else {
        // If the graph doesn't contain a value for this target pattern, try to directly evaluate
        // it, by making use of packages already present in the graph.
        TargetPatternValue.TargetPatternKey targetPatternKey =
            ((TargetPatternValue.TargetPatternKey) patternKey.argument());

        RecursivePackageProviderBackedTargetPatternResolver resolver =
            new RecursivePackageProviderBackedTargetPatternResolver(provider, eventHandler,
                targetPatternKey.getPolicy());
        TargetPattern parsedPattern = targetPatternKey.getParsedPattern();
        try {
          patternsWithTargetsToFilter.put(pattern, parsedPattern.eval(resolver).getTargets());
        } catch (TargetParsingException e) {
          targetParsingException = e;
        } catch (InterruptedException e) {
          throw new QueryException(e.getMessage());
        }
      }

      if (targetParsingException != null) {
        if (!keepGoing) {
          throw targetParsingException;
        } else {
          eventHandler.handle(Event.error("Evaluation of query \"" + caller + "\" failed: "
              + targetParsingException.getMessage()));
          result.put(pattern, ImmutableSet.<Target>of());
        }
      }
    }
    // filterTargetsNotInGraph does graph lookups. So we batch all the queries in one call.
    Set<Target> targetsInGraph = filterTargetsNotInGraph(
        ImmutableSet.copyOf(Iterables.concat(patternsWithTargetsToFilter.values())));

    for (Entry<String, Set<Target>> pattern : patternsWithTargetsToFilter.entrySet()) {
      result.put(pattern.getKey(), Sets.intersection(pattern.getValue(), targetsInGraph));
    }

    return result;
  }

  private static final Function<SkyKey, Label> SKYKEY_TO_LABEL = new Function<SkyKey, Label>() {
    @Nullable
    @Override
    public Label apply(SkyKey skyKey) {
      SkyFunctionName functionName = skyKey.functionName();
      if (!functionName.equals(SkyFunctions.TRANSITIVE_TRAVERSAL)) {
        // Skip non-targets.
        return null;
      }
      return (Label) skyKey.argument();
    }
  };

  private Map<SkyKey, Target> makeTargetsWithAssociations(Iterable<SkyKey> keys) {
    return makeTargetsWithAssociations(keys, SKYKEY_TO_LABEL);
  }

  private Collection<Target> makeTargetsFromLabels(Iterable<Label> labels) {
    return makeTargetsWithAssociations(labels, Functions.<Label>identity()).values();
  }

  private <E> Map<E, Target> makeTargetsWithAssociations(Iterable<E> keys,
      Function<E, Label> toLabel) {
    Multimap<SkyKey, E> packageKeyToTargetKeyMap = ArrayListMultimap.create();
    for (E key : keys) {
      Label label = toLabel.apply(key);
      if (label == null) {
        continue;
      }
      try {
        packageKeyToTargetKeyMap.put(getPackageKeyAndValidateLabel(label), key);
      } catch (QueryException e) {
        // Skip disallowed labels.
      }
    }
    ImmutableMap.Builder<E, Target> result = ImmutableMap.builder();
    Map<SkyKey, SkyValue> packageMap = graph.getSuccessfulValues(packageKeyToTargetKeyMap.keySet());
    for (Map.Entry<SkyKey, SkyValue> entry : packageMap.entrySet()) {
      for (E targetKey : packageKeyToTargetKeyMap.get(entry.getKey())) {
        try {
          result.put(targetKey, ((PackageValue) entry.getValue()).getPackage()
              .getTarget((toLabel.apply(targetKey)).getName()));
        } catch (NoSuchTargetException e) {
          // Skip missing target.
        }
      }
    }
    return result.build();
  }

  private static final Function<Target, SkyKey> TARGET_TO_SKY_KEY =
      new Function<Target, SkyKey>() {
        @Override
        public SkyKey apply(Target target) {
          return TransitiveTraversalValue.key(target.getLabel());
        }
      };

  private static Iterable<SkyKey> makeTransitiveTraversalKeys(Iterable<Target> targets) {
    return Iterables.transform(targets, TARGET_TO_SKY_KEY);
  }

  @Override
  public Target getOrCreate(Target target) {
    return target;
  }

  /**
   * Get SkyKeys for the FileValues for the given {@code pathFragments}. To do this, we look for a
   * package lookup node for each path fragment, since package lookup nodes contain the "root" of a
   * package. The returned SkyKeys correspond to FileValues that may not exist in the graph.
   */
  private Collection<SkyKey> getSkyKeysForFileFragments(Iterable<PathFragment> pathFragments) {
    Set<SkyKey> result = new HashSet<>();
    Multimap<PathFragment, PathFragment> currentToOriginal = ArrayListMultimap.create();
    for (PathFragment pathFragment : pathFragments) {
      currentToOriginal.put(pathFragment, pathFragment);
    }
    while (!currentToOriginal.isEmpty()) {
      Map<SkyKey, PathFragment> keys = new HashMap<>();
      for (PathFragment pathFragment : currentToOriginal.keySet()) {
        keys.put(
            PackageLookupValue.key(PackageIdentifier.createInDefaultRepo(pathFragment)),
            pathFragment);
      }
      Map<SkyKey, SkyValue> lookupValues = graph.getSuccessfulValues(keys.keySet());
      for (Map.Entry<SkyKey, SkyValue> entry : lookupValues.entrySet()) {
        PackageLookupValue packageLookupValue = (PackageLookupValue) entry.getValue();
        if (packageLookupValue.packageExists()) {
          PathFragment dir = keys.get(entry.getKey());
          Collection<PathFragment> originalFiles = currentToOriginal.get(dir);
          Preconditions.checkState(!originalFiles.isEmpty(), entry);
          for (PathFragment fileName : originalFiles) {
            result.add(
                FileValue.key(RootedPath.toRootedPath(packageLookupValue.getRoot(), fileName)));
          }
          currentToOriginal.removeAll(dir);
        }
      }
      Multimap<PathFragment, PathFragment> newCurrentToOriginal = ArrayListMultimap.create();
      for (PathFragment pathFragment : currentToOriginal.keySet()) {
        PathFragment parent = pathFragment.getParentDirectory();
        if (parent != null) {
          newCurrentToOriginal.putAll(parent, currentToOriginal.get(pathFragment));
        }
      }
      currentToOriginal = newCurrentToOriginal;
    }
    return result;
  }

  /**
   * Calculates the set of {@link Package} objects, represented as source file targets, that depend
   * on the given list of BUILD files and subincludes (other files are filtered out).
   */
  @Nullable
  Set<Target> getRBuildFiles(Collection<PathFragment> fileIdentifiers) {
    Collection<SkyKey> files = getSkyKeysForFileFragments(fileIdentifiers);
    Collection<SkyKey> current = graph.getSuccessfulValues(files).keySet();
    Set<SkyKey> resultKeys = CompactHashSet.create();
    while (!current.isEmpty()) {
      Collection<Iterable<SkyKey>> reverseDeps = graph.getReverseDeps(current).values();
      current = new HashSet<>();
      for (SkyKey rdep : Iterables.concat(reverseDeps)) {
        if (rdep.functionName().equals(SkyFunctions.PACKAGE)) {
          resultKeys.add(rdep);
        } else if (!rdep.functionName().equals(SkyFunctions.PACKAGE_LOOKUP)) {
          // Packages may depend on subpackages for existence, but we don't report them as rdeps.
          current.add(rdep);
        }
      }
    }
    Map<SkyKey, SkyValue> packageValues = graph.getSuccessfulValues(resultKeys);
    ImmutableSet.Builder<Target> result = ImmutableSet.builder();
    for (SkyValue value : packageValues.values()) {
      Package pkg = ((PackageValue) value).getPackage();
      if (!pkg.containsErrors()) {
        result.add(pkg.getBuildFile());
      }
    }
    return result.build();
  }

  @Override
  public Iterable<QueryFunction> getFunctions() {
    return ImmutableList.<QueryFunction>builder()
        .addAll(super.getFunctions())
        .add(new AllRdepsFunction())
        .add(new RBuildFilesFunction())
        .build();
  }
}
