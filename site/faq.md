---
layout: default
title: FAQ
nav: faq
---

What is Bazel?
--------------

Bazel is a tool that automates software builds and tests. Supported build tasks
include running compilers and linkers to produce executable programs and
libraries, and assembling deployable packages for Android, iOS and other target
environments. Bazel is similar to other tools like Make, Ant, Gradle, Buck,
Pants and Maven.

What is special about Bazel?
----------------------------

Bazel was designed to fit the way software is developed at Google. It
has the following features:

* Multi-language support: Bazel supports Java, Objective-C and C++ out
  of the box, and can be extended to support arbitrary programming
  languages.

* High-level build language: Projects are described in the BUILD
  language, a concise text format that describes a project as sets of
  small interconnected libraries, binaries and tests. In contrast, with
  tools like Make, you have to describe individual files and compiler
  invocations.

* Multi-platform support: The same tool and the same BUILD files can
  be used to build software for different architectures, and even
  different platforms.  At Google, we use Bazel to build everything from
  server applications running on systems in our data centers to client apps
  running on mobile phones.

* Reproducibility: In BUILD files, each library, test and binary must
  specify its direct dependencies completely.  Bazel uses this
  dependency information to know what must be rebuilt when you make
  changes to a source file, and which tasks can run in parallel.  This
  means that all builds are incremental and will always produce the
  same result.

* Scalable: Bazel can handle large builds; at Google, it is common for
  a server binary to have 100k source files, and builds where no files
  were changed take about ~200ms.


Why doesn't Google use ...?
---------------------------

* Make, Ninja: These tools give very exact control over what commands
  get invoked to build files, but it's up to the user to write rules
  that are correct.

  Users interact with Bazel on a higher level. For example, Bazel has
  built-in rules for "Java test", "C++ binary", and notions such as
  "target platform" and "host platform". These rules have been battle
  tested to be foolproof.

* Ant and Maven: Ant and Maven are primarily geared toward Java, while
  Bazel handles multiple languages.  Bazel encourages subdividing
  codebases in smaller reusable units, and can rebuild only ones that
  need rebuilding. This speeds up development when working with larger
  codebases.

* Gradle: Bazel configuration files are much more structured than
  Gradle's, letting Bazel understand exactly what each action does.
  This allows for more parallelism and better reproducibility.

* Pants, Buck: Both tools were created and developed by ex-Googlers at
  Twitter and Foursquare, and Facebook respectively. They have been modeled
  after Bazel, but their feature sets are different, so they aren't viable
  alternatives for us.


Where did Bazel come from?
--------------------------

Bazel is a flavor of the tool that Google uses to build its server
software internally. It has expanded to build other software as well,
like mobile apps (iOS, Android) that connect to our servers.

Did you rewrite your internal tool as open-source? Is it a fork?
----------------------------------------------------------------

Bazel shares most of its code with the internal tool and its rules
are used for millions of builds every day.

Why did Google build Bazel?
---------------------------

A long time ago, Google built its software using large, generated
Makefiles. These led to slow and unreliable builds, which began to
interfere with our developers' productivity and the company's
agility. Bazel was a way to solve these problems.

Does Bazel require a build cluster?
-----------------------------------

Google's in-house flavor of Bazel does use [build
clusters](http://google-engtools.blogspot.com/2011/09/build-in-cloud-distributing-build-steps.html),
so Bazel does have hooks in the code base to plug in a remote build
cache or a remote execution system.

The open source Bazel code runs build operations locally. We believe
that this is fast enough for most of our users.

How does the Google development process work?
----------------------------------------------

For our server code base, we use the following development workflow:

* All our server code is in a single, gigantic version control
  system.

* Everybody builds their software with Bazel.

* Different teams own different parts of the source tree, and make
  their components available as BUILD targets.

* Branching is primarily used for managing releases, so everybody
  develops their software at the head revision.

Bazel is a cornerstone of this philosophy: since Bazel requires all
dependencies to be fully specified, we can predict which programs and
tests are affected by a change, and vet them before submission.

More background on the development process at Google can be found on
the [eng tools blog](http://google-engtools.blogspot.com/).

Why are you opening up Bazel?
-----------------------------

Building software should be fun and easy. Slow and unpredictable
builds take the fun out of programming.

Why would I want to use Bazel?
------------------------------

* Bazel may give you faster build times because it can recompile only
  the files that need to be recompiled. Similarly, it can skip
  re-running tests that it knows haven't changed.

* Bazel produces deterministic results. This eliminates skew
  between incremental and clean builds, laptop and CI system, etc.

* Bazel can build different client and server apps with the same tool
  from the same workspace. For example, you can change a client/server
  protocol in a single commit, and test that the updated mobile app
  works with the updated server, building both with the same tool,
  reaping all the aforementioned benefits of Bazel.

Can I see examples?
-------------------

Yes. For a simple example, see:

  <https://github.com/bazelbuild/bazel/blob/master/examples/cpp/BUILD>

The Bazel source code itself provides more complex examples:

  <https://github.com/bazelbuild/bazel/blob/master/src/main/java/BUILD>\\
  <https://github.com/bazelbuild/bazel/blob/master/src/test/java/BUILD>

What is Bazel best at?
----------------------

Bazel shines at building and testing projects with the following properties:

* Projects with a large codebase
* Projects written in (multiple) compiled languages
* Projects that deploy on multiple platforms
* Projects that have extensive tests

Where can I run Bazel?
---------------------------------

Currently, Linux and Mac OS X. Porting to other UNIX platforms should be
straightforward, as long as a JDK is available for the platform.

What about Windows?
-------------------

Due to its UNIX heritage, porting Bazel to Windows is significant work. For
example, Bazel uses symlinks extensively, which has varying levels of support
across Windows versions.

We are currently actively working on improving Windows support, but it's still
ways from being usable.

What should I not use Bazel for?
--------------------------------

* Bazel tries to be smart about caching. This means that it is not good
  for running build operations whose outputs should not be cached. For example,
  the following steps should not be run from Bazel:

  * A compilation step that fetches data from the internet.
  * A test step that connects to the QA instance of your site.
  * A deployment step that changes your site's cloud configuration.

* Bazel tries to minimize expensive compilation steps. If you are only
  using interpreted languages directly, such as JavaScript or Python,
  Bazel will likely not interest you.

How stable is Bazel's feature set?
----------------------------------

The core features (C++, Java, and shell rules) have extensive use
inside Google, so they are thoroughly tested and have very little
churn.  Similarly, we test new versions of Bazel across hundreds of
thousands of targets every day to find regressions, and we release new
versions multiple times every month.

In short, except for features marked as experimental, Bazel should Just Work.
Changes to non-experimental rules will be backward compatible. A more detailed
list of feature support statuses can be found in our
[support document](support.html).

How stable is Bazel as a binary?
--------------------------------

Inside Google, we make sure that Bazel crashes are very rare. This
should also hold for our open source codebase.

How can I start using Bazel?
----------------------------

See our [getting started document](docs/getting-started.html).

Why do I need to have a tools/ directory in my package path?
------------------------------------------------------------

Your project never works in isolation. Typically, it builds with a
certain version of the JDK/C++ compiler, with a certain test driver
framework, on a certain version of your operating system.

To guarantee that builds are reproducible even when we upgrade our
workstations, we at Google check most of these tools into version
control, including the toolchains and Bazel itself. By convention, we
do this in a directory called "tools".

Bazel allows tools such as the JDK to live outside your workspace, but
the configuration data for this (where is the JDK, where is the C++
compiler?) still needs to be somewhere, and that place is also the
`tools/` directory.

Bazel's `compile.sh` script builds a minimal set of configuration files,
suitable for running toolchains from standard system directories, e.g.,
`/usr/bin/`.

Doesn't Docker solve the reproducibility problems?
--------------------------------------------------

With Docker you can easily create sandboxes with fixed OS releases,
for example, Ubuntu 12.04, Fedora 21. This solves the problem of
reproducibility for the system environment -- that is, "which version of
/usr/bin/c++ do I need?"

Docker does not address reproducibility with regard to changes in the
source code.  Running Make with an imperfectly written Makefile inside a
Docker container can still yield unpredictable results.

Inside Google, we check tools into source control for reproducibility.
In this way, we can vet changes to tools ("upgrade GCC to 4.6.1") with
the same mechanism as changes to base libraries ("fix bounds check in
OpenSSL").

Can I build binaries for deployment on Docker?
----------------------------------------------

With Bazel, you can build standalone, statically linked binaries in
C/C++, and self-contained jar files for Java. These run with few
dependencies on normal UNIX systems, and as such should be simple to
install inside a Docker container.

Bazel has conventions for structuring more complex programs, for example, a
Java program that consumes a set of data files, or runs another
program as subprocess. It is possible to package up such environments
as standalone archives, so they can be deployed on different systems,
including Docker images.

Can I build Docker images with Bazel?
-------------------------------------

Yes, you can use our
[Docker rules](https://github.com/bazelbuild/bazel/blob/master/tools/build_defs/docker/README.md)
to build reproducible Docker images.

Will Bazel make my builds reproducible automatically?
-----------------------------------------------------

For Java and C++ binaries, yes, assuming you do not change the
toolchain. If you have build steps that involve custom recipes
(for example, executing binaries through a shell script inside a rule), you
will need to take some extra care:

  * Do not use dependencies that were not declared. Sandboxed
    execution (--spawn_strategy=sandboxed, only on Linux) can
    help find undeclared dependencies.

  * Avoid storing timestamps and user-IDs in generated files. ZIP files and
    other archives are especially prone to this.

  * Avoid connecting to the network. Sandboxed execution can help here
    too.

  * Avoid processes that use random numbers, in particular, dictionary
    traversal is randomized in many programming languages.

Do you have binary releases?
----------------------------

Yes, you can find the latest release binaries
[here](https://github.com/bazelbuild/bazel/releases/latest). Our release
policy is documented [here](http://bazel.io/support.html).

I use Eclipse/IntelliJ. How does Bazel interoperate with IDEs?
--------------------------------------------------------------

We currently have no IDE integration API as such but the iOS rules generate
Xcode projects based on Bazel BUILD targets (see below).

How does Bazel interact with Xcode?
-----------------------------------

Bazel generates Xcode projects that you can use to work with any inputs and
dependencies for the target, to build apps from Xcode directly and to deploy to
an iOS simulator and devices. Simply open the project file whose path is printed
by Bazel after building any iOS target. There is no support to invoke Bazel from
Xcode (for example to re-generate generated sources such as Objective-C files
based on protos), nor to open Xcode from Bazel directly.

I use Jenkins/CircleCI/TravisCI. How does Bazel interoperate with CI systems?
-----------------------------------------------------------------------------

Bazel returns a non-zero exit code if the build or test invocation
fails, and this should be enough for basic CI integration.  Since
Bazel does not need clean builds for correctness, the CI system should not
be configured to clean before starting a build/test run.

Further details on exit codes are in the [User Manual](docs/bazel-user-manual.html).

What future features can we expect in Bazel?
--------------------------------------------

Our initial goal is to work on Google's internal use-cases. This
includes Google's principal languages (C++, Java, Go) and major
platforms (Linux, Android, iOS).  For practical reasons, not all of
these are currently open-sourced. For more details see our
[roadmap](roadmap.html).

What about Python?
------------------

It is possible to write Python rules as extensions (see below). See
the following files for an example of generating self-contained zip
files for python:

  <https://github.com/bazelbuild/bazel/blob/master/tools/build_rules/py_rules.bzl>\\
  <https://github.com/bazelbuild/bazel/tree/master/examples/py>

We have opened up a subset of our internal Python rules, so they
can be used as helper scripts as part of a build.

Simplistic support for PEX-style binaries is at
[here](https://github.com/bazelbuild/bazel/blob/master/tools/build_rules/py_rules.bzl).


What about Go?
--------------

If your codebase is 100% Go, the `go` tool has excellent support for
building and testing, and Bazel will not bring you much benefit.

The server code written in Go at Google is built with Bazel. However, the rules
that accomplish this are rather complex due to their interactions with our C++
libraries, and are incompatible with the conventions of the `go` tool.  We are
working on improving this situation.

Can I use Bazel for my [INSERT LANGUAGE HERE] project?
------------------------------------------------------

We have an extension mechanism called Skylark that allows you to add new rules
without recompiling Bazel.

For documentation: see [here](/docs/skylark/index.html). We have support for
several languages that use that extension mechanism, see our
[build encyclopedia](/docs/be/overview.html) for the full
list of supported languages.

I need more functionality. Can I add rules that are compiled into Bazel?
------------------------------------------------------------------------

If our extension mechanism is insufficient for your use case, email
the mailing list for advice: <bazel-discuss@googlegroups.com>.

Can I contribute to the Bazel code base?
----------------------------------------

See our [contribution guidelines](contributing.html).

Why isn't all development done in the open?
-------------------------------------------

We still have to refactor the interfaces between the public code in
Bazel and our internal extensions frequently. This makes it hard to do
much development in the open. See our [governance plan](governance.html)
for more details.

How do I contact the team?
--------------------------

We are reachable at <bazel-discuss@googlegroups.com>.

Where do I report bugs?
-----------------------

Send an e-mail to <bazel-discuss@googlegroups.com> or file a bug
[on GitHub](https://github.com/bazelbuild/bazel/issues).



What's up with the word "Blaze" in the codebase?
------------------------------------------------

This is an internal name for the tool. Please refer to Bazel as
Bazel.


Why do other Google projects (Android, Chrome) use other build tools?
---------------------------------------------------------------------

Until now, Bazel was not available externally, so open source projects
such as Chromium, Android, etc. could not use it. In addition, lack of
Windows support is a problem for building Windows applications, such
as Chrome.


How do you pronounce "Bazel"?
-----------------------------

The same way as "basil" (the herb) in US English: "BAY-zel". It rhymes with
"hazel". IPA: /ˈbeɪzˌəl/
