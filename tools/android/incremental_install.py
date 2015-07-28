# Copyright 2015 Google Inc. All rights reserved.
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

"""Installs an Android application, possibly in an incremental way."""

import collections
import hashlib
import logging
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import zipfile

from third_party.py import gflags
from third_party.py.concurrent import futures


gflags.DEFINE_string("split_main_apk", None, "The main APK for split install")
gflags.DEFINE_multistring("split_apk", [], "Split APKs to install")
gflags.DEFINE_string("dexmanifest", None, "The .dex manifest")
gflags.DEFINE_string("resource_apk", None, "The resource .apk")
gflags.DEFINE_string("apk", None, "The app .apk. If not specified, "
                     "do incremental deployment")
gflags.DEFINE_string("adb", None, "ADB to use")
gflags.DEFINE_string("stub_datafile", None, "The stub data file")
gflags.DEFINE_string("output_marker", None, "The output marker file")
gflags.DEFINE_multistring("extra_adb_arg", [], "Extra arguments to adb")
gflags.DEFINE_string("execroot", ".", "The exec root")
gflags.DEFINE_integer("adb_jobs", 2,
                      "The number of instances of adb to use in parallel to "
                      "update files on the device",
                      lower_bound=1)
gflags.DEFINE_boolean("start_app", False, "Whether to start the app after "
                      "installing it.")
gflags.DEFINE_string("user_home_dir", None, "Path to the user's home directory")
gflags.DEFINE_string("flagfile", None,
                     "Path to a file to read additional flags from")
gflags.DEFINE_string("verbosity", None, "Logging verbosity")

FLAGS = gflags.FLAGS

DEVICE_DIRECTORY = "/data/local/tmp/incrementaldeployment"


class AdbError(Exception):
  """An exception class signaling an error in an adb invocation."""

  def __init__(self, args, returncode, stdout, stderr):
    self.args = args
    self.returncode = returncode
    self.stdout = stdout
    self.stderr = stderr
    details = "\n".join([
        "adb command: %s" % args,
        "return code: %s" % returncode,
        "stdout: %s" % stdout,
        "stderr: %s" % stderr,
    ])
    super(AdbError, self).__init__(details)


class DeviceNotFoundError(Exception):
  """Raised when the device could not be found."""


class MultipleDevicesError(Exception):
  """Raised when > 1 device is attached and no device serial was given."""

  @staticmethod
  def CheckError(s):
    return re.search("more than one (device and emulator|device|emulator)", s)


class DeviceUnauthorizedError(Exception):
  """Raised when the local machine is not authorized to the device."""


class TimestampException(Exception):
  """Raised when there is a problem with timestamp reading/writing."""


class Adb(object):
  """A class to handle interaction with adb."""

  def __init__(self, adb_path, temp_dir, adb_jobs, user_home_dir):
    self._adb_path = adb_path
    self._temp_dir = temp_dir
    self._user_home_dir = user_home_dir
    self._file_counter = 1
    self._executor = futures.ThreadPoolExecutor(max_workers=adb_jobs)

  def _Exec(self, adb_args):
    """Executes the given adb command + args."""
    args = [self._adb_path] + FLAGS.extra_adb_arg + adb_args
    # TODO(ahumesky): Because multiple instances of adb are executed in
    # parallel, these debug logging lines will get interleaved.
    logging.debug("Executing: %s", " ".join(args))

    # adb sometimes requires the user's home directory to access things in
    # $HOME/.android (e.g. keys to authorize with the device). To avoid any
    # potential problems with python picking up things in the user's home
    # directory, HOME is not set in the environment around python and is instead
    # passed explicitly as a flag.
    env = {}
    if self._user_home_dir:
      env["HOME"] = self._user_home_dir

    adb = subprocess.Popen(
        args,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env)
    stdout, stderr = adb.communicate()
    stdout = stdout.strip()
    stderr = stderr.strip()
    logging.debug("adb ret: %s", adb.returncode)
    logging.debug("adb out: %s", stdout)
    logging.debug("adb err: %s", stderr)

    # Check these first so that the more specific error gets raised instead of
    # the more generic AdbError.
    if "device not found" in stderr:
      raise DeviceNotFoundError()
    elif "device unauthorized" in stderr:
      raise DeviceUnauthorizedError()
    elif MultipleDevicesError.CheckError(stderr):
      # The error messages are from adb's transport.c, but something adds
      # "error: " to the beginning, so take it off so that we don't end up
      # printing "Error: error: ..."
      raise MultipleDevicesError(re.sub("^error: ", "", stderr))

    if adb.returncode != 0:
      raise AdbError(args, adb.returncode, stdout, stderr)

    return adb.returncode, stdout, stderr, args

  def _ExecParallel(self, adb_args):
    return self._executor.submit(self._Exec, adb_args)

  def _CreateLocalFile(self):
    """Returns a path to a temporary local file in the temp directory."""
    local = os.path.join(self._temp_dir, "adbfile_%d" % self._file_counter)
    self._file_counter += 1
    return local

  def GetInstallTime(self, package):
    """Get the installation time of a package."""
    _, stdout, _, _ = self._Shell("dumpsys package %s" % package)
    match = re.search("lastUpdateTime=(.*)$", stdout, re.MULTILINE)
    if match:
      return match.group(1)
    else:
      raise TimestampException(
          "Package '%s' is not installed on the device. At least one "
          "non-incremental 'mobile-install' must precede incremental "
          "installs." % package)

  def Push(self, local, remote):
    """Invoke 'adb push' in parallel."""
    return self._ExecParallel(["push", local, remote])

  def PushString(self, contents, remote):
    """Push a given string to a given path on the device in parallel."""
    local = self._CreateLocalFile()
    with file(local, "w") as f:
      f.write(contents)
    return self.Push(local, remote)

  def Pull(self, remote):
    """Invoke 'adb pull'.

    Args:
      remote: The path to the remote file to pull.

    Returns:
      The contents of a file or None if the file didn't exist.
    """
    local = self._CreateLocalFile()
    try:
      self._Exec(["pull", remote, local])
      with file(local) as f:
        return f.read()
    except (AdbError, IOError):
      return None

  def InstallMultiple(self, apk, pkg=None):
    """Invoke 'adb install-multiple'."""

    pkg_args = ["-p", pkg] if pkg else []
    ret, stdout, stderr, args = self._Exec(
        ["install-multiple", "-r"] + pkg_args + [apk])
    if "Success" not in stderr and "Success" not in stdout:
      raise AdbError(args, ret, stdout, stderr)

  def Install(self, apk):
    """Invoke 'adb install'."""
    ret, stdout, stderr, args = self._Exec(["install", "-r", apk])

    # adb install could fail with a message on stdout like this:
    #
    #   pkg: /data/local/tmp/Gmail_dev_sharded_incremental.apk
    #   Failure [INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES]
    #
    # and yet it will still have a return code of 0. At least for the install
    # command, it will print "Success" if it succeeded, so check for that in
    # standard out instead of relying on the return code.
    if "Success" not in stderr and "Success" not in stdout:
      raise AdbError(args, ret, stdout, stderr)

  def Delete(self, remote):
    """Delete the given file (or directory) on the device."""
    self.DeleteMultiple([remote])

  def DeleteMultiple(self, remote_files):
    """Delete the given files (or directories) on the device."""
    files_str = " ".join(remote_files)
    if files_str:
      self._Shell("rm -fr %s" % files_str)

  def Mkdir(self, d):
    """Invokes mkdir with the specified directory on the device."""
    self._Shell("mkdir -p %s" % d)

  def StopApp(self, package):
    """Force stops the app with the given package."""
    self._Shell("am force-stop %s" % package)

  def StartApp(self, package):
    """Starts the app with the given package."""
    self._Shell("monkey -p %s -c android.intent.category.LAUNCHER 1" % package)

  def _Shell(self, cmd):
    """Invoke 'adb shell'."""
    return self._Exec(["shell", cmd])


ManifestEntry = collections.namedtuple(
    "ManifestEntry", ["input_file", "zippath", "installpath", "sha256"])


def ParseManifest(contents):
  """Parses a dexmanifest file.

  Args:
    contents: the contents of the manifest file to be parsed.

  Returns:
    A dict of install path -> ManifestEntry.
  """
  result = {}

  for l in contents.split("\n"):
    entry = ManifestEntry(*(l.strip().split(" ")))
    result[entry.installpath] = entry

  return result


def GetAppPackage(stub_datafile):
  """Returns the app package specified in a stub data file."""
  with file(stub_datafile) as f:
    return f.readlines()[1].strip()


def UploadDexes(adb, execroot, app_dir, temp_dir, dexmanifest, full_install):
  """Uploads dexes to the device so that the state.

  Does the minimum amount of work necessary to make the state of the device
  consistent with what was built.

  Args:
    adb: the Adb instance representing the device to install to
    execroot: the execroot
    app_dir: the directory things should be installed under on the device
    temp_dir: a local temporary directory
    dexmanifest: contents of the dex manifest
    full_install: whether to do a full install

  Returns:
    None.
  """

  # Fetch the manifest on the device
  dex_dir = os.path.join(app_dir, "dex")
  adb.Mkdir(dex_dir)

  old_manifest = None

  if not full_install:
    logging.info("Fetching dex manifest from device...")
    old_manifest_contents = adb.Pull("%s/manifest" % dex_dir)
    if old_manifest_contents:
      old_manifest = ParseManifest(old_manifest_contents)
    else:
      logging.info("Dex manifest not found on device")

  if old_manifest is None:
    # If the manifest is not found, maybe a previous installation attempt
    # was interrupted. Wipe the slate clean. Do this also in case we do a full
    # installation.
    old_manifest = {}
    adb.Delete("%s/*" % dex_dir)

  new_manifest = ParseManifest(dexmanifest)
  dexes_to_delete = set(old_manifest) - set(new_manifest)

  # Figure out which dexes to upload: those that are present in the new manifest
  # but not in the old one and those whose checksum was changed
  common_dexes = set(new_manifest).intersection(old_manifest)
  dexes_to_upload = set(d for d in common_dexes
                        if new_manifest[d].sha256 != old_manifest[d].sha256)
  dexes_to_upload.update(set(new_manifest) - set(old_manifest))

  if not dexes_to_delete and not dexes_to_upload:
    # If we have nothing to do, don't bother removing and rewriting the manifest
    logging.info("Application dexes up-to-date")
    return

  # Delete the manifest so that we know how to get back to a consistent state
  # if we are interrupted.
  adb.Delete("%s/manifest" % dex_dir)

  # Tuple of (local, remote) files to push to the device.
  files_to_push = []

  # Sort dexes to be uploaded by the zip file they are in so that we only need
  # to open each zip only once.
  dexzips_in_upload = set(new_manifest[d].input_file for d in dexes_to_upload
                          if new_manifest[d].zippath != "-")
  for i, dexzip_name in enumerate(dexzips_in_upload):
    zip_dexes = [
        d for d in dexes_to_upload if new_manifest[d].input_file == dexzip_name]
    dexzip_tempdir = os.path.join(temp_dir, "dex", str(i))
    with zipfile.ZipFile(os.path.join(execroot, dexzip_name)) as dexzip:
      for dex in zip_dexes:
        zippath = new_manifest[dex].zippath
        dexzip.extract(zippath, dexzip_tempdir)
        files_to_push.append(
            (os.path.join(dexzip_tempdir, zippath), "%s/%s" % (dex_dir, dex)))

  # Now gather all the dexes that are not within a .zip file.
  dexes_to_upload = set(
      d for d in dexes_to_upload if new_manifest[d].zippath == "-")
  for dex in dexes_to_upload:
    files_to_push.append(
        (new_manifest[dex].input_file, "%s/%s" % (dex_dir, dex)))

  num_files = len(dexes_to_delete) + len(files_to_push)
  logging.info("Updating %d dex%s...", num_files, "es" if num_files > 1 else "")

  # Delete the dexes that are not in the new manifest
  adb.DeleteMultiple(os.path.join(dex_dir, dex) for dex in dexes_to_delete)

  # Upload all the files.
  upload_walltime_start = time.time()
  fs = [adb.Push(local, remote) for local, remote in files_to_push]
  done, not_done = futures.wait(fs, return_when=futures.FIRST_EXCEPTION)
  upload_walltime = time.time() - upload_walltime_start
  logging.debug("Dex upload walltime: %s seconds", upload_walltime)

  # If there is anything in not_done, then some adb call failed and we
  # can cancel the rest.
  if not_done:
    for f in not_done:
      f.cancel()

  # If any adb call resulted in an exception, re-raise it.
  for f in done:
    f.result()

  # If no dex upload failed, upload the manifest. If any upload failed, the
  # exception should have been re-raised above.
  # Call result() to raise the exception if there was one.
  adb.PushString(dexmanifest, "%s/manifest" % dex_dir).result()


def Checksum(filename):
  """Compute the SHA-256 checksum of a file."""
  h = hashlib.sha256()
  with file(filename, "r") as f:
    while True:
      data = f.read(65536)
      if not data:
        break

      h.update(data)

  return h.hexdigest()


def UploadResources(adb, resource_apk, app_dir):
  """Uploads resources to the device.

  Args:
    adb: The Adb instance representing the device to install to.
    resource_apk: Path to the resource apk.
    app_dir: The directory things should be installed under on the device.

  Returns:
    None.
  """

  # Compute the checksum of the new resources file
  new_checksum = Checksum(resource_apk)

  # Fetch the checksum of the resources file on the device, if it exists
  device_checksum_file = "%s/%s" % (app_dir, "resources_checksum")
  old_checksum = adb.Pull(device_checksum_file)
  if old_checksum == new_checksum:
    logging.info("Application resources up-to-date")
    return
  logging.info("Updating application resources...")

  # Remove the checksum file on the device so that if the transfer is
  # interrupted, we know how to get the device back to a consistent state.
  adb.Delete(device_checksum_file)
  adb.Push(resource_apk, "%s/%s" % (app_dir, "resources.ap_")).result()

  # Write the new checksum to the device.
  adb.PushString(new_checksum, device_checksum_file).result()


def VerifyInstallTimestamp(adb, app_package):
  """Verifies that the app is unchanged since the last mobile-install."""
  expected_timestamp = adb.Pull("%s/%s/install_timestamp" % (
      DEVICE_DIRECTORY, app_package))
  if not expected_timestamp:
    raise TimestampException(
        "Cannot verify last mobile install. At least one non-incremental "
        "'mobile-install' must precede incremental installs")

  actual_timestamp = adb.GetInstallTime(app_package)
  if actual_timestamp != expected_timestamp:
    raise TimestampException("Installed app '%s' has an unexpected timestamp. "
                             "Did you last install the app in a way other than "
                             "'mobile-install'?" % app_package)


def IncrementalInstall(adb_path, execroot, stub_datafile, output_marker,
                       adb_jobs, start_app, dexmanifest=None, apk=None,
                       resource_apk=None, split_main_apk=None, split_apks=None,
                       user_home_dir=None):
  """Performs an incremental install.

  Args:
    adb_path: Path to the adb executable.
    execroot: Exec root.
    stub_datafile: The stub datafile containing the app's package name.
    output_marker: Path to the output marker file.
    adb_jobs: The number of instances of adb to use in parallel.
    start_app: If True, starts the app after updating.
    dexmanifest: Path to the .dex manifest file.
    apk: Path to the .apk file. May be None to perform an incremental install.
    resource_apk: Path to the apk containing the app's resources.
    split_main_apk: the split main .apk if split installation is desired.
    split_apks: the list of split .apks to be installed.
    user_home_dir: Path to the user's home directory.
  """
  temp_dir = tempfile.mkdtemp()
  try:
    adb = Adb(adb_path, temp_dir, adb_jobs, user_home_dir)
    app_package = GetAppPackage(os.path.join(execroot, stub_datafile))
    app_dir = os.path.join(DEVICE_DIRECTORY, app_package)
    if split_main_apk:
      adb.InstallMultiple(os.path.join(execroot, split_main_apk))
      for split_apk in split_apks:
        adb.InstallMultiple(os.path.join(execroot, split_apk), app_package)
    else:
      if not apk:
        VerifyInstallTimestamp(adb, app_package)

      with file(os.path.join(execroot, dexmanifest)) as f:
        dexmanifest = f.read()
      UploadDexes(adb, execroot, app_dir, temp_dir, dexmanifest, bool(apk))
      # TODO(ahumesky): UploadDexes waits for all the dexes to be uploaded, and
      # then UploadResources is called. We could instead enqueue everything
      # onto the threadpool so that uploading resources happens sooner.
      UploadResources(adb, os.path.join(execroot, resource_apk), app_dir)
      if apk:
        apk_path = os.path.join(execroot, apk)
        adb.Install(apk_path)
        future = adb.PushString(
            adb.GetInstallTime(app_package),
            "%s/%s/install_timestamp" % (DEVICE_DIRECTORY, app_package))
        future.result()

      else:
        adb.StopApp(app_package)

    if start_app:
      logging.info("Starting application %s", app_package)
      adb.StartApp(app_package)

    with file(output_marker, "w") as _:
      pass
  except DeviceNotFoundError:
    sys.exit("Error: Device not found")
  except DeviceUnauthorizedError:
    sys.exit("Error: Device unauthorized. Please check the confirmation "
             "dialog on your device.")
  except MultipleDevicesError as e:
    sys.exit(
        "Error: " + e.message + "\nTry specifying a device serial with " +
        "\"blaze mobile-install --adb_arg=-s --adb_arg=$ANDROID_SERIAL\"")
  except TimestampException as e:
    sys.exit("Error:\n%s" % e.message)
  except AdbError as e:
    sys.exit("Error:\n%s" % e.message)
  finally:
    shutil.rmtree(temp_dir, True)


def main():
  if FLAGS.verbosity == "1":
    level = logging.DEBUG
    fmt = "%(levelname)-5s %(asctime)s %(module)s:%(lineno)3d] %(message)s"
  else:
    level = logging.INFO
    fmt = "%(message)s"
  logging.basicConfig(stream=sys.stdout, level=level, format=fmt)

  IncrementalInstall(
      adb_path=FLAGS.adb,
      adb_jobs=FLAGS.adb_jobs,
      execroot=FLAGS.execroot,
      stub_datafile=FLAGS.stub_datafile,
      output_marker=FLAGS.output_marker,
      start_app=FLAGS.start_app,
      split_main_apk=FLAGS.split_main_apk,
      split_apks=FLAGS.split_apk,
      dexmanifest=FLAGS.dexmanifest,
      apk=FLAGS.apk,
      resource_apk=FLAGS.resource_apk,
      user_home_dir=FLAGS.user_home_dir)


if __name__ == "__main__":
  FLAGS(sys.argv)
  # process any additional flags in --flagfile
  if FLAGS.flagfile:
    with open(FLAGS.flagfile) as flagsfile:
      FLAGS.Reset()
      FLAGS(sys.argv + [line.strip() for line in flagsfile.readlines()])

  main()
