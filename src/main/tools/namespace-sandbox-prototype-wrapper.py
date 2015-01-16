# Copyright 2014 Google Inc. All Rights Reserved.
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

"""Wrapper for bazel sandbox.

This is a temporary wrapper for bazel sandbox, which will be rewritten into
java/c.
"""

import os
import os.path
import re
import shutil
import subprocess
import sys
import tempfile

BAZEL_BIN = os.path.dirname(os.path.realpath(__file__))
DEBUG = False


def setup_blaze_utils(sandbox):
  os.makedirs(os.path.join(sandbox, '_bin'))
  shutil.copy(os.path.join(BAZEL_BIN, 'build-runfiles'),
              os.path.join(sandbox, '_bin'))


def debug(*args):
  if not DEBUG:
    return
  def str_(x):
    if type(x) == str:
      return x
    else:
      return str(x)
  sys.stderr.write('wrapper: ' + ' '.join(map(str_, args)) + '\n')
  sys.stderr.flush()


def copy_inputs(sandbox, inputs, input_dir):
  for i in inputs:
    target_dir = os.path.join(sandbox, os.path.dirname(i))
    if not os.path.isdir(target_dir):
      os.makedirs(target_dir)
    shutil.copy(os.path.join(input_dir, i), target_dir)


def copy_outputs(sandbox, outputs, host_output):
  for o in outputs:
    target_dir = os.path.dirname(o)
    try:
      os.makedirs(target_dir)
    except OSError:
      # unfortunately, we can't do something like
      #
      # if not os.path.isdir(target_dir):
      #   os.makedirs(target_dir)
      #
      # because some other spawn with the same output dir may create this
      # directory in the meantime
      pass
    try:
      copy(os.path.join(sandbox, o),
           os.path.join(host_output, target_dir))
    except IOError:
      # TODO(bazel-team): decide if this failure should terminate execution?
      debug('Failed to copy: ', o)


def copy(source, target):
  debug('copy: {} -> {}'.format(source, target))
  shutil.copy(source, target)


def run(command, sandbox_binary, sandbox_root, mounts, include_directories,
        include_prefix):
  """Run command in the sandbox.

  Runs given command in the sandbox.

  Args:
    command: command to run
    sandbox_binary: actual sandbox programme
    sandbox_root: root directory for sandbox
    mounts: readonly directories to mount into the sandbox
    include_directories: host directories with header files
    include_prefix: directory inside sandbox where include_directories will
      be mounted

  Returns:
    return code of the command
  """
  def add_opt(opt, l):
    res = []
    for elt in l:
      res.append(opt)
      res.append(elt)
    return res
  args = [sandbox_binary, '-S', sandbox_root]
  #  debug(os.path.join(sandbox_root, 'tmp', 'mounts'))
  #  os.makedirs(os.path.join(sandbox_root, 'tmp', 'mounts'))
  #  mounts += ['/tmp/mounts']

  args += add_opt('-m', mounts)

  if include_prefix:
    args += add_opt('-N', [include_prefix])
    args += add_opt('-n', include_directories)

  if DEBUG:
    args.append('-D')

  try:
    args.append('-C')
    args += command
    debug('Sandbox will run now:', ' '.join(args))
    subprocess.check_call(args)
    code = 0
  except subprocess.CalledProcessError as e:
    code = e.returncode
  return code


def expand_include_directories(dirs, execute_path):
  excluded = set(['_bin', 'bazel-out'])
  if execute_path in dirs:  # this means including '.', which we don't want
    dirs.remove(execute_path)
    for subdir in os.listdir(execute_path):
      if subdir not in excluded:
        dirs.append(os.path.join(execute_path, subdir))


def setup_env(sandbox, command, output_dirs, runfiles_manifest,
              include_directories, include_prefix):
  """Setup various things inside sandbox that will be needed for it to work.

  Args:
    sandbox: sandbox root directory
    command: command (binary) to run inside sandbox
    output_dirs: list of output directories to create inside sandbox
    runfiles_manifest: list of runfiles manifest files
    include_directories: host directories with header files
    include_prefix: directory inside sandbox where include_directories will
      be mounted

  Returns:
    list of directories which sandbox will mount inside itself
  """

  # ultimately all of this goes into the container
  for d in ['bin', 'dev', 'proc', 'etc', os.path.join('usr', 'bin'),
            os.path.join('usr', 'include'),
            os.path.join('usr', 'local', 'include')]:
    os.makedirs(os.path.join(sandbox, d))
  for f in ['dev/random', 'dev/zero', 'dev/null', 'dev/urandom']:
    open(os.path.join(sandbox, f), 'w+').close()

  # command to run
  if (os.path.isfile(command[0]) and not
      os.path.isfile(os.path.join(sandbox, command[0]))):
    shutil.copy(command[0], os.path.join(sandbox, command[0]))

  # output directory
  for o in output_dirs:
    if not os.path.exists(os.path.join(sandbox, o)):
      os.makedirs(os.path.join(sandbox, o))

  setup_blaze_utils(sandbox)

  # header directories
  if include_prefix:
    os.makedirs(os.path.join(sandbox, include_prefix))
    include_directories.sort()
    for d in include_directories:
      debug('headers: ', d)
      # omit leading '/'
      os.makedirs(os.path.join(sandbox, include_prefix, d[1:]))

  # manifests
  for m in runfiles_manifest:
    debug('Reading manifest:', m)
    with open(m) as manifest_handler:
      manifest_lines = manifest_handler.readlines()
      for line in manifest_lines:
        [target, source] = line.strip().split(' ')
        debug(source)
        debug(target)
        target_dir = os.path.dirname(os.path.join(sandbox, target))
        debug(target_dir)
        if not os.path.isdir(target_dir):
          os.makedirs(target_dir)
        shutil.copy(source, target_dir)

  # shared libs and elf interpreter
  shared_libs = ['/lib/', '/lib32/', '/lib64/', '/usr/lib/',
                 '/usr/lib32/', '/usr/libx32/', '/libx32/']
  for directory in shared_libs:
    os.makedirs(os.path.join(sandbox, directory[1:]))

  try:
    # make sure we didn't omit anything
    for directory in get_shared_libs_directiories():
      included = False
      for l in shared_libs:
        included |= directory.startswith(l)
      if not included:
        raise ValueError(directory)
  except IOError as e:
    debug('some files for shared libs not found')
    debug(e)
    sys.exit(1)

  # At the moment places mounted for shared libs are hardcoded. Is is possible
  # that this will break things in the future. That's why the check below.
  #
  # Also, default ELF interpreter is usually in lib, lib32 or lib64. However,
  # if it is located somwehere else a binary may fail. This can be diagnosed
  # by running 'readelf -a binary | grep interpreter' and adding outputed file
  # to sandbox.
  #

  return shared_libs


def get_shared_libs_directiories():
  """Get directories for shared libs.

  Used mostly for sanity check to make sure everything necessary is mounted

  Returns:
    list of shared libs directories
  """
  cache_file = '/etc/ld.so.cache'
  ldconfig_args = ['/sbin/ldconfig', '-p']
  out = subprocess.check_output(ldconfig_args).strip().split('\n')

  info = re.escape(' libs found in cache `{}\''.format(cache_file))
  pattern = re.compile('^([0-9]*)' + info + '$')
  match = pattern.match(out[0])
  if match:
    lines = int(match.group(1))
  else:
    debug('First line of cache dump {} doesn\'t match'
          'pattern {}:\n{}'.format(cache_file, pattern.pattern, out[0]))
    exit(1)

  if len(out) - 1 != int(lines):
    debug('Cache file has {} lines, but first line'
          'says:\n {}'.format(len(out), out[0]))
    exit(1)

  libs = []
  # pylint: disable=anomalous-backslash-in-string
  pattern = re.compile('(.*\.so(?:\..*){0,1})\s*\(.*\)\s*\\=\\>\s*(.*)')
  for o in out[1:]:
    match = pattern.match(o.strip())
    if match:
      libs.append(match.group(2))
    else:
      debug('Unexpected line in ldconfing output:\n' + o)

  return libs


def usage():
  debug('Usage: sandbox.py command [-i inputs] [-o outputs] [-a args]'
        '[-H host_output_directory] [-m runfiles_manifests]'
        '[-N tmp_include_directory_name] [-n include_directories]'
        '[-I input_files_directory] [-O output_files_directories]')
  sys.exit(1)


def main():
  if len(sys.argv) < 2:
    usage()

  debug('argv', ' '.join(sys.argv))
  args = iter(sys.argv[1:])
  inputs, outputs, output_dirs = [], [], []
  runfiles_manifest, include_directories = [], []
  include_prefix = None

  try:
    for arg in args:
      if arg == '-i':
        inputs.append(next(args))
      elif arg == '-o':
        outputs.append(next(args))
      elif arg == '-C':
        command = list(args)
        debug(command)
        break
      elif arg == '-I':
        input_dir = next(args)
      elif arg == '-O':
        output_dirs.append(next(args))
      elif arg == '-H':
        host_output = next(args)
      elif arg == '-S':
        sandbox_binary = next(args)
      elif arg == '-M':
        runfiles_manifest.append(next(args))
      elif arg == '-N':
        include_prefix = next(args)
      elif arg == '-n':
        include_directories.append(next(args))
      else:
        debug('Unexpected arg: ' + arg)
        usage()
  except StopIteration:
    usage()
  execute_path = host_output
  include_directories = filter(os.path.isdir, include_directories)
  expand_include_directories(include_directories, execute_path)
  os.chdir(os.path.dirname(sandbox_binary))
  sandbox_root = tempfile.mkdtemp(prefix='sandbox-root-', dir=os.getcwd())
  debug('root', sandbox_root)
  mounts = setup_env(sandbox_root, command, output_dirs, runfiles_manifest,
                     include_directories, include_prefix)
  mounts += ['/bin', '/usr/bin', '/usr/include', '/usr/local/include',
             '/usr/lib32', '/usr/libx32', '/usr/lib']
  mounts += ['/etc']
  copy_inputs(sandbox_root, inputs, input_dir)
  # TODO(bazel-team): mounts instead of copying. (This will require changes to
  # namespace-sandbox.c, since absolute path outside sandbox is different to the
  # one inside)
  if os.path.isdir(os.path.join(execute_path, sandbox_root, 'tools')):
    shutil.rmtree(os.path.join(execute_path, sandbox_root, 'tools'))
  shutil.copytree(os.path.join(execute_path, 'tools'),
                  os.path.join(execute_path, sandbox_root, 'tools'))

  return_code = run(command, sandbox_binary, sandbox_root, mounts,
                    include_directories, include_prefix)
  if return_code == 0:
    copy_outputs(sandbox_root, outputs, host_output)
  if not DEBUG:
    # tools in bazel doesn't have w bit, so we have to set it
    if os.path.isdir(os.path.join(execute_path, sandbox_root, 'tools')):
      # os.chmod doesn't have -R option
      subprocess.check_call(['chmod', '-R', '0777',
                             os.path.join(execute_path, sandbox_root, 'tools')])
    shutil.rmtree(sandbox_root)
  debug('finished: {}'.format(return_code))
  sys.exit(return_code)


if __name__ == '__main__':
  main()
