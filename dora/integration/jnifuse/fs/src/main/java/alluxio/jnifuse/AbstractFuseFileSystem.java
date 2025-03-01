/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.jnifuse;

import alluxio.jnifuse.struct.FileStat;
import alluxio.jnifuse.struct.FuseContext;
import alluxio.jnifuse.struct.FuseFileInfo;
import alluxio.jnifuse.struct.Statvfs;
import alluxio.jnifuse.utils.SecurityUtils;

import org.apache.commons.lang3.SystemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import io.vertx.core.dns.AddressResolverOptions;

/**
 * Abstract class for other File System to extend and integrate with Fuse.
 */
public abstract class AbstractFuseFileSystem implements FuseFileSystem {

  static {
    // Preload dependencies for jnr-runtime to avoid exceptions during class loading
    // when launching a large number of pods in kubernetes. (to resolve issues/15679)
    jnr.ffi.Runtime.getSystemRuntime();
  }

  private static final Logger LOG = LoggerFactory.getLogger(AbstractFuseFileSystem.class);

  // timeout to mount a JNI fuse file system in ms
  private static final int MOUNT_TIMEOUT_MS = 2000;

  private final LibFuse mLibFuse = new LibFuse();
  private final AtomicBoolean mMounted = new AtomicBoolean();
  private final Path mMountPoint;

  /**
   * Constructs an {@link AbstractFuseFileSystem}.
   *
   * @param mountPoint
   */
  public AbstractFuseFileSystem(Path mountPoint) {
    mMountPoint = mountPoint.toAbsolutePath();
  }

  /**
   * Executes mount command.
   *
   * @param blocking whether this command is blocking
   * @param debug whether to show debug information
   * @param fuseOpts the fuse mount options
   */
  public void mount(boolean blocking, boolean debug, Set<String> fuseOpts) {
    if (!mMounted.compareAndSet(false, true)) {
      throw new FuseException("Fuse File System already mounted!");
    }
    LOG.info("Mounting {}: blocking={}, debug={}, fuseOpts=\"{}\"",
        mMountPoint, blocking, debug, String.join(",", fuseOpts));
    List<String> args = new ArrayList<>();
    args.add(getFileSystemName());
    args.add("-f");
    if (debug) {
      args.add("-d");
    }
    String mountPointStr = mMountPoint.toString();
    if (mountPointStr.endsWith("\\")) {
      mountPointStr = mountPointStr.substring(0, mountPointStr.length() - 1);
    }
    args.add(mountPointStr);
    if (fuseOpts.size() != 0) {
      fuseOpts.stream().map(opt -> "-o" + opt).forEach(args::add);
    }
    final String[] argsArray = args.toArray(new String[0]);
    try {
      if (SecurityUtils.canHandleShutdownHooks()) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
          LOG.info("Unmounting Fuse through shutdown hook");
          umount(true);
        }));
      }
      int res;
      if (blocking) {
        res = execMount(argsArray);
      } else {
        try {
          res = CompletableFuture.supplyAsync(() -> execMount(argsArray)).get(MOUNT_TIMEOUT_MS,
              TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
          // ok
          res = 0;
        }
      }
      if (res != 0) {
        throw new FuseException("Unable to mount FS, return code = " + res);
      }
    } catch (Exception e) {
      mMounted.set(false);
      throw new FuseException("Unable to mount FS", e);
    }
  }

  private int execMount(String[] arg) {
    loadNecessaryClasses();
    return mLibFuse.fuse_main_real(this, arg.length, arg);
  }

  private void loadNecessaryClasses() {
    LOG.info("Loading necessary classes...");
    try {
      String[] classesToLoad = {
          "io.vertx.core.dns.AddressResolverOptions"
      };
      for (String classToLoad : classesToLoad) {
        Class<io.vertx.core.dns.AddressResolverOptions> cls =
            (Class<AddressResolverOptions>)
                ClassLoader.getSystemClassLoader().loadClass(classToLoad);
        cls.newInstance();
      }
    } catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Umount the mount point of this Fuse Filesystem.
   *
   * @param force     whether to do a force umount
   */
  public void umount(boolean force) {
    if (!mMounted.get()) {
      return;
    }
    LOG.info("Umounting {}", mMountPoint);
    try {
      umountInternal();
    } catch (FuseException e) {
      LOG.error("Failed to umount {}", mMountPoint, e);
      throw e;
    }
    mMounted.set(false);
  }

  private void umountInternal() {
    int exitCode;
    String mountPath = mMountPoint.toString();
    if (SystemUtils.IS_OS_WINDOWS) {
      throw new FuseException("Unable to umount FS in a windows system.");
    } else if (SystemUtils.IS_OS_MAC_OSX) {
      try {
        exitCode = new ProcessBuilder("umount", "-f", mountPath).start().waitFor();
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        throw new FuseException("Unable to umount FS", ie);
      } catch (IOException ioe) {
        throw new FuseException("Unable to umount FS", ioe);
      }
    } else {
      try {
        exitCode = new ProcessBuilder("fusermount", "-u", "-z", mountPath).start().waitFor();
        if (exitCode != 0) {
          throw new Exception(String.format("fusermount returns %d", exitCode));
        }
      } catch (Exception e) {
        if (e instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        try {
          exitCode = new ProcessBuilder("umount", mountPath).start().waitFor();
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw new FuseException("Unable to umount FS", e);
        } catch (IOException ioe) {
          ioe.addSuppressed(e);
          throw new FuseException("Unable to umount FS", ioe);
        }
      }
    }
    if (exitCode != 0) {
      throw new FuseException("Unable to umount FS with exit code " + exitCode);
    }
  }

  public int openCallback(String path, ByteBuffer buf) {
    try {
      return open(path, FuseFileInfo.of(buf));
    } catch (Exception e) {
      LOG.error("Failed to open {}: ", path, e);
      return -ErrorCodes.EIO();
    }
  }

  public int readCallback(String path, ByteBuffer buf, long size, long offset, ByteBuffer fibuf) {
    try {
      return read(path, buf, size, offset, FuseFileInfo.of(fibuf));
    } catch (Exception e) {
      LOG.error("Failed to read {}, size {}, offset {}: ", path, size, offset, e);
      return -ErrorCodes.EIO();
    }
  }

  public int getattrCallback(String path, ByteBuffer buf) {
    try {
      return getattr(path, FileStat.of(buf));
    } catch (Exception e) {
      LOG.error("Failed to getattr {}: ", path, e);
      return -ErrorCodes.EIO();
    }
  }

  public int readdirCallback(String path, long bufaddr, long filter, long offset,
      ByteBuffer fi) {
    try {
      return readdir(path, bufaddr, filter, offset, FuseFileInfo.of(fi));
    } catch (Exception e) {
      LOG.error("Failed to readdir {}, offset {}: ", path, offset, e);
      return -ErrorCodes.EIO();
    }
  }

  public int unlinkCallback(String path) {
    try {
      return unlink(path);
    } catch (Exception e) {
      LOG.error("Failed to unlink {}: ", path, e);
      return -ErrorCodes.EIO();
    }
  }

  public int flushCallback(String path, ByteBuffer fi) {
    try {
      return flush(path, FuseFileInfo.of(fi));
    } catch (Exception e) {
      LOG.error("Failed to flush {}: ", path, e);
      return -ErrorCodes.EIO();
    }
  }

  public int releaseCallback(String path, ByteBuffer fi) {
    try {
      return release(path, FuseFileInfo.of(fi));
    } catch (Exception e) {
      LOG.error("Failed to release {}: ", path, e);
      return -ErrorCodes.EIO();
    }
  }

  public int chmodCallback(String path, long mode) {
    try {
      return chmod(path, mode);
    } catch (Exception e) {
      LOG.error("Failed to chmod {}, mode {}: ", path, mode, e);
      return -ErrorCodes.EIO();
    }
  }

  public int chownCallback(String path, long uid, long gid) {
    try {
      return chown(path, uid, gid);
    } catch (Exception e) {
      LOG.error("Failed to chown {}, uid {}, gid {}: ", path, uid, gid, e);
      return -ErrorCodes.EIO();
    }
  }

  public int createCallback(String path, long mode, ByteBuffer fi) {
    try {
      return create(path, mode, FuseFileInfo.of(fi));
    } catch (Exception e) {
      LOG.error("Failed to create {}, mode {}: ", path, mode, e);
      return -ErrorCodes.EIO();
    }
  }

  public int mkdirCallback(String path, long mode) {
    try {
      return mkdir(path, mode);
    } catch (Exception e) {
      LOG.error("Failed to mkdir {}, mode {}: ", path, mode, e);
      return -ErrorCodes.EIO();
    }
  }

  public int renameCallback(String oldPath, String newPath) {
    return renameCallback(oldPath, newPath, 0);
  }

  public int renameCallback(String oldPath, String newPath, int flags) {
    try {
      return rename(oldPath, newPath, flags);
    } catch (Exception e) {
      LOG.error("Failed to rename {}, newPath {}: ", oldPath, newPath, e);
      return -ErrorCodes.EIO();
    }
  }

  public int rmdirCallback(String path) {
    try {
      return rmdir(path);
    } catch (Exception e) {
      LOG.error("Failed to rmdir {}: ", path, e);
      return -ErrorCodes.EIO();
    }
  }

  public int statfsCallback(String path, ByteBuffer stbuf) {
    try {
      return statfs(path, Statvfs.of(stbuf));
    } catch (Exception e) {
      LOG.error("Failed to statfs {}: ", path, e);
      return -ErrorCodes.EIO();
    }
  }

  public int symlinkCallback(String linkname, String path) {
    try {
      return symlink(linkname, path);
    } catch (Exception e) {
      LOG.error("Failed to symlink linkname {}, path {}", linkname, path, e);
      return -ErrorCodes.EIO();
    }
  }

  public int truncateCallback(String path, long size) {
    try {
      return truncate(path, size);
    } catch (Exception e) {
      LOG.error("Failed to truncate {}, size {}: ", path, size, e);
      return -ErrorCodes.EIO();
    }
  }

  public int utimensCallback(String path, long aSec, long aNsec, long mSec, long mNsec) {
    try {
      return utimens(path, aSec, aNsec, mSec, mNsec);
    } catch (Exception e) {
      LOG.error("Failed to utimens {}, aSec {}, aNsec {}, mSec {}, mNsec {}: ",
          path, aSec, aNsec, mSec, mNsec, e);
      return -ErrorCodes.EIO();
    }
  }

  public int writeCallback(String path, ByteBuffer buf, long size, long offset, ByteBuffer fi) {
    try {
      return write(path, buf, size, offset, FuseFileInfo.of(fi));
    } catch (Exception e) {
      LOG.error("Failed to write {}, size {}, offset {}: ", path, size, offset, e);
      return -ErrorCodes.EIO();
    }
  }

  public int setxattrCallback(String path, String name, ByteBuffer value, long size, int flags) {
    return 0;
  }

  public int getxattrCallback(String path, String name, ByteBuffer value) {
    return 0;
  }

  public int listxattrCallback(String path, ByteBuffer list) {
    return 0;
  }

  public int removexattrCallback(String path, String name) {
    return 0;
  }

  @Override
  public FuseContext getContext() {
    ByteBuffer buffer = mLibFuse.fuse_get_context();
    return FuseContext.of(buffer);
  }
}
