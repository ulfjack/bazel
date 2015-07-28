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

package com.google.devtools.build.lib.packages;

/**
 * Exception indicating that the same package (i.e. BUILD file) can be loaded
 * via different package paths.
 */
// TODO(bazel-team): (2009) Change exception hierarchy so that DuplicatePackageException is no
// longer a child of NoSuchPackageException.
public class DuplicatePackageException extends NoSuchPackageException {

  public DuplicatePackageException(PackageIdentifier packageIdentifier, String message) {
    super(packageIdentifier, message);
  }

  public DuplicatePackageException(PackageIdentifier packageIdentifier, String message,
                                   Throwable cause) {
    super(packageIdentifier, message, cause);
  }
}
