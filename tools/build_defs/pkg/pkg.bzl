# Copyright 2015 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Rules for manipulation of various packaging."""

# Filetype to restrict inputs
tar_filetype = FileType([".tar", ".tar.gz", ".tgz", ".tar.xz"])
deb_filetype = FileType([".deb"])

def _short_path_dirname(path):
  """Returns the directory's name of the short path of an artifact."""
  sp = path.short_path
  return sp[:sp.rfind("/")]

def _dest_path(f, strip_prefix):
  """Returns the short path of f, stripped of strip_prefix."""
  if strip_prefix == None:
    # If no strip_prefix was specified, use the package of the
    # given input as the strip_prefix.
    strip_prefix = _short_path_dirname(f)
  if not strip_prefix:
    return f.short_path
  if f.short_path.startswith(strip_prefix):
    return f.short_path[len(strip_prefix):]
  return f.short_path

def _compute_data_path(out, data_path):
  """Compute the relative data path prefix from the data_path attribute."""
  if data_path:
    # Strip ./ from the beginning if specified.
    # There is no way to handle .// correctly (no function that would make
    # that possible and Skylark is not turing complete) so just consider it
    # as an absolute path.
    if len(data_path) >= 2 and data_path[0:2] == "./":
      data_path = data_path[2:]
    if not data_path or data_path == ".":  # Relative to current package
      return _short_path_dirname(out)
    elif data_path[0] == "/":  # Absolute path
      return data_path[1:]
    else:  # Relative to a sub-directory
      return _short_path_dirname(out) + "/" + data_path
  else:
    return None

def _pkg_tar_impl(ctx):
  """Implementation of the pkg_tar rule."""
  # Compute the relative path
  data_path = _compute_data_path(ctx.outputs.out, ctx.attr.strip_prefix)

  build_tar = ctx.executable._build_tar
  args = [
      "--output=" + ctx.outputs.out.path,
      "--directory=" + ctx.attr.package_dir,
      "--mode=" + ctx.attr.mode,
      ]
  args += ["--file=%s=%s" % (f.path, _dest_path(f, data_path))
           for f in ctx.files.files]
  if ctx.attr.modes:
    args += ["--modes=%s=%s" % (key, ctx.attr.modes[key]) for key in ctx.attr.modes]
  if ctx.attr.extension:
    dotPos = ctx.attr.extension.find('.')
    if dotPos > 0:
      dotPos += 1
      args += ["--compression=%s" % ctx.attr.extension[dotPos:]]
  args += ["--tar=" + f.path for f in ctx.files.deps]
  args += ["--link=%s:%s" % (k, ctx.attr.symlinks[k])
           for k in ctx.attr.symlinks]

  ctx.action(
      executable = build_tar,
      arguments = args,
      inputs = ctx.files.files + ctx.files.deps,
      outputs = [ctx.outputs.out],
      mnemonic="PackageTar"
      )


def _pkg_deb_impl(ctx):
  """The implementation for the pkg_deb rule."""
  files = [ctx.file.data]
  args = [
      "--output=" + ctx.outputs.out.path,
      "--data=" + ctx.file.data.path,
      "--package=" + ctx.attr.package,
      "--architecture=" + ctx.attr.architecture,
      "--maintainer=" + ctx.attr.maintainer,
      ]
  if ctx.attr.preinst:
    args += ["--preinst=@" + ctx.file.preinst.path]
    files += [ctx.file.preinst]
  if ctx.attr.postinst:
    args += ["--postinst=@" + ctx.file.postinst.path]
    files += [ctx.file.postinst]
  if ctx.attr.prerm:
    args += ["--prerm=@" + ctx.file.prerm.path]
    files += [ctx.file.prerm]
  if ctx.attr.postrm:
    args += ["--postrm=@" + ctx.file.postrm.path]
    files += [ctx.file.postrm]

  # Version and description can be specified by a file or inlined
  if ctx.attr.version_file:
    if ctx.attr.version:
      fail("Both version and version_file attributes were specified")
    args += ["--version=@" + ctx.file.version_file.path]
    files += [ctx.file.version_file]
  elif ctx.attr.version:
    args += ["--version=" + ctx.attr.version]
  else:
    fail("Neither version_file nor version attribute was specified")

  if ctx.attr.description_file:
    if ctx.attr.description:
      fail("Both description and description_file attributes were specified")
    args += ["--description=@" + ctx.file.description_file.path]
    files += [ctx.file.description_file]
  elif ctx.attr.description:
    args += ["--description=" + ctx.attr.description]
  else:
    fail("Neither description_file nor description attribute was specified")

  # Built using can also be specified by a file or inlined (but is not mandatory)
  if ctx.attr.built_using_file:
    if ctx.attr.built_using:
      fail("Both build_using and built_using_file attributes were specified")
    args += ["--built_using=@" + ctx.file.built_using_file.path]
    files += [ctx.file.built_using_file]
  elif ctx.attr.built_using:
    args += ["--built_using=" + ctx.attr.built_using]

  if ctx.attr.priority:
    args += ["--priority=" + ctx.attr.priority]
  if ctx.attr.section:
    args += ["--section=" + ctx.attr.section]
  if ctx.attr.homepage:
    args += ["--homepage=" + ctx.attr.homepage]

  args += ["--depends=" + d for d in ctx.attr.depends]
  args += ["--suggests=" + d for d in ctx.attr.suggests]
  args += ["--enhances=" + d for d in ctx.attr.enhances]
  args += ["--pre_depends=" + d for d in ctx.attr.predepends]
  args += ["--recommends=" + d for d in ctx.attr.recommends]

  ctx.action(
      executable = ctx.executable._make_deb,
      arguments = args,
      inputs = files,
      outputs = [ctx.outputs.out],
      mnemonic="MakeDeb"
      )

# A rule for creating a tar file, see README.md
pkg_tar = rule(
    implementation = _pkg_tar_impl,
    attrs = {
        "strip_prefix": attr.string(),
        "package_dir": attr.string(default="/"),
        "deps": attr.label_list(allow_files=tar_filetype),
        "files": attr.label_list(allow_files=True),
        "mode": attr.string(default="0555"),
        "modes": attr.string_dict(),
        "extension": attr.string(default="tar"),
        "symlinks": attr.string_dict(),
        # Implicit dependencies.
        "_build_tar": attr.label(
            default=Label("//tools/build_defs/pkg:build_tar"),
            cfg=HOST_CFG,
            executable=True,
            allow_files=True)
    },
    outputs = {
        "out": "%{name}.%{extension}",
    },
    executable = False)


# A rule for creating a deb file, see README.md
pkg_deb = rule(
    implementation = _pkg_deb_impl,
    attrs = {
        "data": attr.label(mandatory=True, allow_files=tar_filetype, single_file=True),
        "package": attr.string(mandatory=True),
        "architecture": attr.string(default="all"),
        "maintainer": attr.string(mandatory=True),
        "preinst": attr.label(allow_files=True, single_file=True),
        "postinst": attr.label(allow_files=True, single_file=True),
        "prerm": attr.label(allow_files=True, single_file=True),
        "postrm": attr.label(allow_files=True, single_file=True),
        "version_file": attr.label(allow_files=True, single_file=True),
        "version": attr.string(),
        "description_file": attr.label(allow_files=True, single_file=True),
        "description": attr.string(),
        "built_using_file": attr.label(allow_files=True, single_file=True),
        "built_using": attr.string(),
        "priority": attr.string(),
        "section": attr.string(),
        "homepage": attr.string(),
        "depends": attr.string_list(default=[]),
        "suggests": attr.string_list(default=[]),
        "enhances": attr.string_list(default=[]),
        "predepends": attr.string_list(default=[]),
        "recommends": attr.string_list(default=[]),
        # Implicit dependencies.
        "_make_deb": attr.label(
            default=Label("//tools/build_defs/pkg:make_deb"),
            cfg=HOST_CFG,
            executable=True,
            allow_files=True)
    },
    outputs = {
        "out": "%{name}.deb",
    },
    executable = False)
