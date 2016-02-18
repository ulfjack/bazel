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

package com.google.devtools.build.lib.cmdline;

import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.Interner;
import com.google.common.collect.Interners;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.util.StringCanonicalizer;
import com.google.devtools.build.lib.util.StringUtilities;
import com.google.devtools.build.lib.vfs.Canonicalizer;
import com.google.devtools.build.lib.vfs.PathFragment;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamException;
import java.io.Serializable;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

import javax.annotation.concurrent.Immutable;

/**
 * Uniquely identifies a package, given a repository name and a package's path fragment.
 *
 * <p>The repository the build is happening in is the <i>default workspace</i>, and is identified
 * by the workspace name "". Other repositories can be named in the WORKSPACE file.  These
 * workspaces are prefixed by {@literal @}.</p>
 */
@Immutable
public final class PackageIdentifier implements Comparable<PackageIdentifier>, Serializable {
  public static final String EXTERNAL_PREFIX = "external";

  private static final Interner<PackageIdentifier> INTERNER = Interners.newWeakInterner();

  public static PackageIdentifier create(String repository, PathFragment pkgName)
      throws LabelSyntaxException {
    return create(RepositoryName.create(repository), pkgName);
  }

  public static PackageIdentifier create(RepositoryName repository, PathFragment pkgName) {
    return INTERNER.intern(new PackageIdentifier(repository, pkgName));
  }

  /**
   * A human-readable name for the repository.
   */
  public static final class RepositoryName implements Serializable {
    private static final Pattern VALID_REPO_NAME = Pattern.compile("@[\\w\\-.]*");

    /** Helper for serializing {@link RepositoryName}. */
    private static final class SerializationProxy implements Serializable {
      private RepositoryName repositoryName;

      private SerializationProxy(RepositoryName repositoryName) {
        this.repositoryName = repositoryName;
      }

      private void writeObject(ObjectOutputStream out) throws IOException {
        out.writeObject(repositoryName.toString());
      }

      private void readObject(ObjectInputStream in)
          throws IOException, ClassNotFoundException {
        try {
          repositoryName = RepositoryName.create((String) in.readObject());
        } catch (LabelSyntaxException e) {
          throw new IOException("Error serializing repository name: " + e.getMessage());
        }
      }

      @SuppressWarnings("unused")
      private void readObjectNoData() throws ObjectStreamException {
      }

      private Object readResolve() {
        return repositoryName;
      }
    }

    private void readObject(@SuppressWarnings("unused") ObjectInputStream in) throws IOException {
      throw new IOException("Serialization is allowed only by proxy");
    }

    private Object writeReplace() {
      return new SerializationProxy(this);
    }

    private static final LoadingCache<String, RepositoryName> repositoryNameCache =
        CacheBuilder.newBuilder()
          .weakValues()
          .build(
              new CacheLoader<String, RepositoryName> () {
                @Override
                public RepositoryName load(String name) throws LabelSyntaxException {
                  String errorMessage = validate(name);
                  if (errorMessage != null) {
                    errorMessage = "invalid repository name '"
                        + StringUtilities.sanitizeControlChars(name) + "': " + errorMessage;
                    throw new LabelSyntaxException(errorMessage);
                  }
                  return new RepositoryName(StringCanonicalizer.intern(name));
                }
              });

    /**
     * Makes sure that name is a valid repository name and creates a new RepositoryName using it.
     *
     * @throws LabelSyntaxException if the name is invalid
     */
    public static RepositoryName create(String name) throws LabelSyntaxException {
      try {
        return repositoryNameCache.get(name);
      } catch (ExecutionException e) {
        Throwables.propagateIfInstanceOf(e.getCause(), LabelSyntaxException.class);
        throw new IllegalStateException("Failed to create RepositoryName from " + name, e);
      }
    }

    /**
     * Extracts the repository name from a PathFragment that was created with
     * {@code PackageIdentifier.getPathFragment}.
     *
     * @return a {@code Pair} of the extracted repository name and the path fragment with stripped
     * of "external/"-prefix and repository name, or null if none was found or the repository name
     * was invalid.
     */
    public static Pair<RepositoryName, PathFragment> fromPathFragment(PathFragment path) {
      if (path.segmentCount() < 2 || !path.getSegment(0).equals(EXTERNAL_PREFIX)) {
        return null;
      }
      try {
        RepositoryName repoName = RepositoryName.create("@" + path.getSegment(1));
        PathFragment subPath = path.subFragment(2, path.segmentCount());
        return Pair.of(repoName, subPath);
      } catch (LabelSyntaxException e) {
        return null;
      }
    }

    private final String name;

    private RepositoryName(String name) {
      this.name = name;
    }

    /**
     * Performs validity checking.  Returns null on success, an error message otherwise.
     */
    private static String validate(String name) {
      if (name.isEmpty()) {
        return null;
      }

      // Some special cases for more user-friendly error messages.
      if (!name.startsWith("@")) {
        return "workspace names must start with '@'";
      }
      if (name.equals("@.")) {
        return "workspace names are not allowed to be '@.'";
      }
      if (name.equals("@..")) {
        return "workspace names are not allowed to be '@..'";
      }

      if (!VALID_REPO_NAME.matcher(name).matches()) {
        return "workspace names may contain only A-Z, a-z, 0-9, '-', '_' and '.'";
      }

      return null;
    }

    /**
     * Returns the repository name without the leading "{@literal @}".  For the default repository,
     * returns "".
     */
    public String strippedName() {
      if (name.isEmpty()) {
        return name;
      }
      return name.substring(1);
    }

    /**
     * Returns if this is the default repository, that is, {@link #name} is "".
     */
    public boolean isDefault() {
      return name.isEmpty();
    }

    /**
     * Returns the repository name, with leading "{@literal @}" (or "" for the default repository).
     */
    // TODO(bazel-team): Use this over toString()- easier to track its usage.
    public String getName() {
      return name;
    }

    /**
     * Returns the path at which this repository is mapped within the exec root.
     */
    public PathFragment getPathFragment() {
      return isDefault()
          ? PathFragment.EMPTY_FRAGMENT
          : new PathFragment(EXTERNAL_PREFIX).getRelative(strippedName());
    }

    /**
     * Returns the repository name, with leading "{@literal @}" (or "" for the default repository).
     */
    @Override
    public String toString() {
      return name;
    }

    @Override
    public boolean equals(Object object) {
      if (this == object) {
        return true;
      }
      if (!(object instanceof RepositoryName)) {
        return false;
      }
      return name.equals(((RepositoryName) object).name);
    }

    @Override
    public int hashCode() {
      return name.hashCode();
    }
  }

  public static final String DEFAULT_REPOSITORY = "";
  public static final RepositoryName DEFAULT_REPOSITORY_NAME;
  public static final RepositoryName MAIN_REPOSITORY_NAME;

  static {
    try {
      DEFAULT_REPOSITORY_NAME = RepositoryName.create(DEFAULT_REPOSITORY);
      MAIN_REPOSITORY_NAME = RepositoryName.create("@");
    } catch (LabelSyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  // Temporary factory for identifiers without explicit repositories.
  // TODO(bazel-team): remove all usages of this.
  public static PackageIdentifier createInDefaultRepo(String name) {
    return createInDefaultRepo(new PathFragment(name));
  }

  public static PackageIdentifier createInDefaultRepo(PathFragment name) {
    try {
      return create(DEFAULT_REPOSITORY, name);
    } catch (LabelSyntaxException e) {
      throw new IllegalArgumentException("could not create package identifier for " + name
          + ": " + e.getMessage());
    }
  }

  /**
   * The identifier for this repository. This is either "" or prefixed with an "@",
   * e.g., "@myrepo".
   */
  private final RepositoryName repository;

  /** The name of the package. Canonical (i.e. x.equals(y) <=> x==y). */
  private final PathFragment pkgName;

  private PackageIdentifier(RepositoryName repository, PathFragment pkgName) {
    Preconditions.checkNotNull(repository);
    Preconditions.checkNotNull(pkgName);
    this.repository = repository;
    this.pkgName = Canonicalizer.fragments().intern(pkgName.normalize());
  }

  public static PackageIdentifier parse(String input) throws LabelSyntaxException {
    String repo;
    String packageName;
    int packageStartPos = input.indexOf("//");
    if (input.startsWith("@") && packageStartPos > 0) {
      repo = input.substring(0, packageStartPos);
      packageName = input.substring(packageStartPos + 2);
    } else if (input.startsWith("@")) {
      throw new LabelSyntaxException("invalid package name '" + input + "'");
    } else if (packageStartPos == 0) {
      repo = PackageIdentifier.DEFAULT_REPOSITORY;
      packageName = input.substring(2);
    } else {
      repo = PackageIdentifier.DEFAULT_REPOSITORY;
      packageName = input;
    }

    String error = RepositoryName.validate(repo);
    if (error != null) {
      throw new LabelSyntaxException(error);
    }

    error = LabelValidator.validatePackageName(packageName);
    if (error != null) {
      throw new LabelSyntaxException(error);
    }

    return create(repo, new PathFragment(packageName));
  }

  public RepositoryName getRepository() {
    return repository;
  }

  public PathFragment getPackageFragment() {
    return pkgName;
  }

  /**
   * Returns a relative path that should be unique across all remote and packages, based on the
   * repository and package names.
   */
  public PathFragment getPathFragment() {
    return repository.getPathFragment().getRelative(pkgName);
  }

  /**
   * Returns the name of this package.
   *
   * <p>There are certain places that expect the path fragment as the package name ('foo/bar') as a
   * package identifier. This isn't specific enough for packages in other repositories, so their
   * stringified version is '@baz//foo/bar'.</p>
   */
  @Override
  public String toString() {
    return (repository.isDefault() ? "" : repository + "//") + pkgName;
  }

  @Override
  public boolean equals(Object object) {
    if (this == object) {
      return true;
    }
    if (!(object instanceof PackageIdentifier)) {
      return false;
    }
    PackageIdentifier that = (PackageIdentifier) object;
    return pkgName.equals(that.pkgName) && repository.equals(that.repository);
  }

  @Override
  public int hashCode() {
    return Objects.hash(repository, pkgName);
  }

  @Override
  public int compareTo(PackageIdentifier that) {
    return ComparisonChain.start()
        .compare(repository.toString(), that.repository.toString())
        .compare(pkgName, that.pkgName)
        .result();
  }
}
