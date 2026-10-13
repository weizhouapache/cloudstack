// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.hypervisor.kvm.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.utils.Pair;
import com.cloud.utils.script.Script;

/**
 * Drives the {@code rbd} CLI for the incremental backup paths that back Ceph volumes up as
 * {@code export-diff} streams.
 *
 * <p>RBD images are raw: they cannot hold a backing-file reference, so a diff carries no pointer
 * to the one before it and a chain cannot be flattened with {@code qemu-img} the way a qcow2 chain
 * can. Ceph applies the deltas natively instead — each diff ends at the snapshot the backup was
 * taken from, {@code import-diff} recreates that snapshot on the target, and the next diff is
 * validated against it, so a truncated or out-of-order diff is rejected rather than silently
 * producing a half-restored image.</p>
 *
 * <p>An instance owns a temporary cephx key file and must be closed; the secret is handed to the
 * CLI through a 0600 file rather than an argument so it never appears in the host's process
 * list.</p>
 */
public class RbdDiffHelper implements AutoCloseable {

    protected static Logger LOGGER = LogManager.getLogger(RbdDiffHelper.class);

    /**
     * Prefix every chain anchor is named with. A snapshot carrying it belongs to a backup chain
     * and to nothing else, which is what makes sweeping stale anchors safe.
     */
    public static final String SNAPSHOT_PREFIX = "csbackup-";

    /** Marks a backup whose per-volume artefacts are {@code rbd export-diff} streams. */
    public static final String FORMAT_RBD_DIFF = "rbddiff";

    private static final Pattern INFO_SIZE = Pattern.compile("\"size\"\\s*:\\s*(\\d+)");

    private static final Pattern SNAP_NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");

    // Matches the features CloudStack itself creates RBD volumes with
    // (LibvirtStorageAdaptor.RBD_FEATURES), so a restored image behaves like any other. object-map
    // and fast-diff in particular are what make a later `rbd export-diff` cheap.
    private static final List<String> IMAGE_FEATURES =
            List.of("layering", "exclusive-lock", "object-map", "fast-diff", "deep-flatten");

    private final List<String> connection;
    private final Path keyFile;

    private RbdDiffHelper(List<String> connection, Path keyFile) {
        this.connection = connection;
        this.keyFile = keyFile;
    }

    /** Open a helper against {@code pool}, writing its cephx secret to a temporary key file. */
    public static RbdDiffHelper forPool(KVMStoragePool pool) throws IOException {
        Path keyFile = writeKeyFile(pool.getAuthSecret());
        return new RbdDiffHelper(connectionArgs(pool, keyFile), keyFile);
    }

    /** The {@code rbd_default_data_pool} of {@code pool}, or {@code null} when it has none. */
    public static String dataPoolOf(KVMStoragePool pool) {
        Map<String, String> details = pool.getDetails();
        return details == null ? null : details.get(KVMPhysicalDisk.RBD_DEFAULT_DATA_POOL);
    }

    /**
     * The pool-qualified {@code <ceph pool>/<image>} name the CLI needs. The helper drives
     * {@code rbd} without a {@code -p} argument, so a bare volume path would silently resolve
     * against the default {@code rbd} pool instead of the volume's own.
     */
    public static String qualifiedImage(KVMStoragePool pool, String path) {
        return path == null || path.contains("/") ? path : pool.getSourceDir() + "/" + path;
    }

    /** A chain anchor name that is unique for this cycle and recognisable as ours. */
    public static String newSnapshotName() {
        return SNAPSHOT_PREFIX + System.currentTimeMillis() / 1000L;
    }

    /**
     * Stream {@code image} out to {@code outputFile}. With {@code fromSnapshot} the stream holds
     * only the extents that changed since that snapshot; without it, every extent allocated at
     * {@code snapshot}.
     */
    public boolean exportDiff(String image, String snapshot, String fromSnapshot, String outputFile) {
        List<String> arguments = new ArrayList<>();
        arguments.add("export-diff");
        if (StringUtils.isNotBlank(fromSnapshot)) {
            arguments.add("--from-snap");
            arguments.add(fromSnapshot);
        }
        arguments.add(image + "@" + snapshot);
        arguments.add(outputFile);
        if (Script.executeCommandForExitValue(command(arguments.toArray(new String[0]))) != 0) {
            LOGGER.error("Failed to export RBD diff of image {}@{} (from-snap {}) to {}", image, snapshot, fromSnapshot, outputFile);
            return false;
        }
        return true;
    }

    /**
     * Recreate {@code image} and replay {@code diffFiles} into it in order.
     *
     * <p>The image is recreated first: a full diff only carries the extents that were allocated
     * when it was taken, so any block still allocated in the current image would otherwise show
     * through the restore as stale data. That also drops the RBD snapshots the image carried,
     * which is inherent to rewinding a volume wholesale.</p>
     */
    public boolean importDiffChain(String image, long sizeBytes, String dataPool, List<String> diffFiles) {
        if (!recreateImage(image, sizeBytes, dataPool)) {
            return false;
        }
        for (String diff : diffFiles) {
            LOGGER.debug("Applying backup diff {} to RBD image {}", diff, image);
            if (Script.executeCommandForExitValue(command("import-diff", diff, image)) != 0) {
                LOGGER.error("Failed to apply backup diff {} to RBD image {}", diff, image);
                return false;
            }
        }
        // The snapshots import-diff recreated only existed to chain the diffs together; the
        // restored volume should look like any other, so drop them.
        if (Script.executeCommandForExitValue(command("snap", "purge", image)) != 0) {
            LOGGER.warn("Failed to purge the snapshots left behind by the restore of RBD image {}", image);
        }
        return true;
    }

    public boolean createSnapshot(String image, String snapshot) {
        if (Script.executeCommandForExitValue(command("snap", "create", image + "@" + snapshot)) != 0) {
            LOGGER.error("Failed to create RBD snapshot {}@{}", image, snapshot);
            return false;
        }
        return true;
    }

    public boolean removeSnapshot(String image, String snapshot) {
        if (Script.executeCommandForExitValue(command("snap", "rm", image + "@" + snapshot)) != 0) {
            LOGGER.warn("Failed to remove RBD snapshot {}@{}", image, snapshot);
            return false;
        }
        return true;
    }

    /** Whether {@code image} currently carries {@code snapshot}. */
    public boolean hasSnapshot(String image, String snapshot) {
        return listSnapshots(image).contains(snapshot);
    }

    /** Every snapshot of {@code image}, in the order Ceph lists them. */
    public List<String> listSnapshots(String image) {
        List<String> names = new ArrayList<>();
        Pair<Integer, String> result = Script.executePipedCommands(
                Collections.singletonList(command("snap", "ls", image, "--format", "json")), 0);
        if (result.first() == null || result.first() != 0 || result.second() == null) {
            // Returning an empty list for a failed call would make callers believe the image
            // carries no snapshots, so say what happened.
            LOGGER.warn("Could not list the snapshots of RBD image {}: {}", image, result.second());
            return names;
        }
        Matcher matcher = SNAP_NAME.matcher(result.second());
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /**
     * Drop every chain anchor of {@code image} except {@code keep}. Only snapshots carrying
     * {@link #SNAPSHOT_PREFIX} are touched, so volume snapshots taken by anything else survive.
     */
    public void sweepStaleSnapshots(String image, String keep) {
        for (String snapshot : listSnapshots(image)) {
            if (snapshot.startsWith(SNAPSHOT_PREFIX) && !snapshot.equals(keep)) {
                LOGGER.debug("Removing stale backup anchor {}@{}", image, snapshot);
                removeSnapshot(image, snapshot);
            }
        }
    }

    /** Size of {@code image} in bytes, or {@code null} when the image does not exist. */
    public Long imageSize(String image) {
        Pair<Integer, String> result = Script.executePipedCommands(
                Collections.singletonList(command("info", image, "--format", "json")), 0);
        if (result.first() == null || result.first() != 0 || result.second() == null) {
            return null;
        }
        Matcher matcher = INFO_SIZE.matcher(result.second());
        return matcher.find() ? Long.valueOf(matcher.group(1)) : null;
    }

    public boolean recreateImage(String image, long sizeBytes, String dataPool) {
        // `rbd rm` refuses to delete an image that still has snapshots, and a restore target
        // normally carries the anchor snapshot of the backup chain it belongs to.
        Script.executeCommandForExitValue(command("snap", "purge", image));
        if (Script.executeCommandForExitValue(command("rm", image)) != 0 && imageSize(image) != null) {
            LOGGER.error("Failed to remove RBD image {} before restoring it", image);
            return false;
        }
        List<String> create = new ArrayList<>(Arrays.asList("create", image, "--size", sizeBytes + "B"));
        for (String feature : IMAGE_FEATURES) {
            create.add("--image-feature");
            create.add(feature);
        }
        if (StringUtils.isNotBlank(dataPool)) {
            create.add("--data-pool");
            create.add(dataPool);
        }
        if (Script.executeCommandForExitValue(command(create.toArray(new String[0]))) != 0) {
            LOGGER.error("Failed to recreate RBD image {} with size {} bytes", image, sizeBytes);
            return false;
        }
        return true;
    }

    protected String[] command(String... arguments) {
        List<String> command = new ArrayList<>();
        command.add(Script.getExecutableAbsolutePath("rbd"));
        command.addAll(connection);
        command.addAll(Arrays.asList(arguments));
        return command.toArray(new String[0]);
    }

    protected static List<String> connectionArgs(KVMStoragePool pool, Path keyFile) {
        List<String> args = new ArrayList<>();
        if (StringUtils.isNotBlank(pool.getSourceHost())) {
            List<String> monitors = new ArrayList<>();
            for (String host : pool.getSourceHost().split(",")) {
                host = host.trim().replace("[", "").replace("]", "");
                monitors.add(pool.getSourcePort() > 0 ? host + ":" + pool.getSourcePort() : host);
            }
            args.add("-m");
            args.add(String.join(",", monitors));
        }
        if (StringUtils.isNotBlank(pool.getAuthUserName())) {
            args.add("--id");
            args.add(pool.getAuthUserName());
            if (keyFile != null) {
                args.add("--keyfile");
                args.add(keyFile.toString());
            }
        }
        return args;
    }

    private static Path writeKeyFile(String authSecret) throws IOException {
        if (StringUtils.isBlank(authSecret)) {
            return null;
        }
        Path keyFile = Files.createTempFile("csbackup-ceph-", ".key",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.write(keyFile, authSecret.getBytes(StandardCharsets.UTF_8));
        return keyFile;
    }

    @Override
    public void close() {
        if (keyFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(keyFile);
        } catch (IOException e) {
            LOGGER.warn("Failed to delete the temporary Ceph key file [{}].", keyFile, e);
        }
    }
}
