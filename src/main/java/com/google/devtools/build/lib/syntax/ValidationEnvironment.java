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

package com.google.devtools.build.lib.syntax;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.events.Location;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

/**
 * An Environment for the semantic checking of Skylark files.
 *
 * @see Statement#validate
 * @see Expression#validate
 */
public class ValidationEnvironment {

  private final ValidationEnvironment parent;

  private Set<String> variables = new HashSet<>();

  private Map<String, Location> variableLocations = new HashMap<>();

  private Set<String> readOnlyVariables = new HashSet<>();

  // A stack of variable-sets which are read only but can be assigned in different
  // branches of if-else statements.
  private Stack<Set<String>> futureReadOnlyVariables = new Stack<>();

  // Whether this validation environment is not modified therefore clonable or not.
  private boolean clonable;
  
  /**
   * Tracks the number of nested for loops that contain the statement that is currently being
   * validated
   */
  private int loopCount = 0;

  public ValidationEnvironment(Set<String> builtinVariables) {
    parent = null;
    variables.addAll(builtinVariables);
    readOnlyVariables.addAll(builtinVariables);
    clonable = true;
  }

  private ValidationEnvironment(Set<String> builtinVariables, Set<String> readOnlyVariables) {
    parent = null;
    this.variables = new HashSet<>(builtinVariables);
    this.readOnlyVariables = new HashSet<>(readOnlyVariables);
    clonable = false;
  }

  // ValidationEnvironment for a new Environment()
  private static ImmutableSet<String> globalTypes = ImmutableSet.of("False", "True", "None");

  public ValidationEnvironment() {
    this(globalTypes);
  }

  @Override
  public ValidationEnvironment clone() {
    Preconditions.checkState(clonable);
    return new ValidationEnvironment(variables, readOnlyVariables);
  }

  /**
   * Creates a local ValidationEnvironment to validate user defined function bodies.
   */
  public ValidationEnvironment(ValidationEnvironment parent) {
    // Don't copy readOnlyVariables: Variables may shadow global values.
    this.parent = parent;
    this.clonable = false;
  }

  /**
   * Returns true if this ValidationEnvironment is top level i.e. has no parent.
   */
  public boolean isTopLevel() {
    return parent == null;
  }

  /**
   * Declare a variable and add it to the environment.
   */
  public void declare(String varname, Location location)
      throws EvalException {
    checkReadonly(varname, location);
    if (parent == null) {  // top-level values are immutable
      readOnlyVariables.add(varname);
      if (!futureReadOnlyVariables.isEmpty()) {
        // Currently validating an if-else statement
        futureReadOnlyVariables.peek().add(varname);
      }
    }
    variables.add(varname);
    variableLocations.put(varname, location);
    clonable = false;
  }

  private void checkReadonly(String varname, Location location) throws EvalException {
    if (readOnlyVariables.contains(varname)) {
      throw new EvalException(location, String.format("Variable %s is read only", varname));
    }
  }

  /**
   * Returns true if the symbol exists in the validation environment.
   */
  public boolean hasSymbolInEnvironment(String varname) {
    return variables.contains(varname) || topLevel().variables.contains(varname);
  }

  private ValidationEnvironment topLevel() {
    return Preconditions.checkNotNull(parent == null ? this : parent);
  }

  /**
   * Starts a session with temporarily disabled readonly checking for variables between branches.
   * This is useful to validate control flows like if-else when we know that certain parts of the
   * code cannot both be executed. 
   */
  public void startTemporarilyDisableReadonlyCheckSession() {
    futureReadOnlyVariables.add(new HashSet<String>());
    clonable = false;
  }

  /**
   * Finishes the session with temporarily disabled readonly checking.
   */
  public void finishTemporarilyDisableReadonlyCheckSession() {
    Set<String> variables = futureReadOnlyVariables.pop();
    readOnlyVariables.addAll(variables);
    if (!futureReadOnlyVariables.isEmpty()) {
      futureReadOnlyVariables.peek().addAll(variables);
    }
    clonable = false;
  }

  /**
   * Finishes a branch of temporarily disabled readonly checking.
   */
  public void finishTemporarilyDisableReadonlyCheckBranch() {
    readOnlyVariables.removeAll(futureReadOnlyVariables.peek());
    clonable = false;
  }

  /**
   * Validates the AST and runs static checks.
   */
  public void validateAst(List<Statement> statements) throws EvalException {
    // Add every function in the environment before validating. This is
    // necessary because functions may call other functions defined
    // later in the file.
    for (Statement statement : statements) {
      if (statement instanceof FunctionDefStatement) {
        FunctionDefStatement fct = (FunctionDefStatement) statement;
        declare(fct.getIdent().getName(), fct.getLocation());
      }
    }

    for (Statement statement : statements) {
      statement.validate(this);
    }
  }

  /**
   * Returns whether the current statement is inside a for loop (either in this environment or one
   * of its parents)
   *
   * @return True if the current statement is inside a for loop
   */
  public boolean isInsideLoop() {
    return (loopCount > 0);
  }
  
  /**
   * Signals that the block of a for loop was entered
   */
  public void enterLoop()   {
    ++loopCount;
  }
  
  /**
   * Signals that the block of a for loop was left
   *
   * @param location The current location
   * @throws EvalException If there was no corresponding call to
   *         {@code ValidationEnvironment#enterLoop}
   */
  public void exitLoop(Location location) throws EvalException {
    Preconditions.checkState(loopCount > 0);
    --loopCount;
  }
}
