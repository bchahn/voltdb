/* This file is part of VoltDB.
 * Copyright (C) 2008-2020 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.utils;

import static org.voltdb.RestoreAgent.checkSnapshotIsComplete;
import static org.voltdb.utils.SnapshotComparer.CONSOLE_LOG;
import static org.voltdb.utils.SnapshotComparer.SNAPSHOT_LOG;
import static org.voltdb.utils.SnapshotComparer.STATUS_INCONSISTENCY;
import static org.voltdb.utils.SnapshotComparer.STATUS_INVALID_INPUT;
import static org.voltdb.utils.SnapshotComparer.STATUS_OK;
import static org.voltdb.utils.SnapshotComparer.remoteSnapshotFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Vector;

import org.apache.commons.io.FileUtils;
import org.voltcore.logging.VoltLogger;
import org.voltcore.utils.DBBPool;
import org.voltdb.ElasticHashinator;
import org.voltdb.PrivateVoltTableFactory;
import org.voltdb.RestoreAgent;
import org.voltdb.SnapshotTableInfo;
import org.voltdb.VoltTable;
import org.voltdb.sysprocs.saverestore.HashinatorSnapshotData;
import org.voltdb.sysprocs.saverestore.SnapshotPathType;
import org.voltdb.sysprocs.saverestore.SnapshotUtil;
import org.voltdb.sysprocs.saverestore.SnapshotUtil.Snapshot;
import org.voltdb.sysprocs.saverestore.TableSaveFile;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;


/**
 * A command line utility for scanning and compare snapshots
 */

public class SnapshotComparer {
    public static int STATUS_OK = 0;
    public static int STATUS_INVALID_INPUT = -1;
    public static int STATUS_INCONSISTENCY = -2;

    public static final VoltLogger CONSOLE_LOG = new VoltLogger("CONSOLE");
    public static final VoltLogger SNAPSHOT_LOG = new VoltLogger("SNAPSHOT");
    // A string builder to hold all snapshot validation errors, gets printed when no viable snapshot is found
    public static final StringBuilder m_ErrLogStr =
            new StringBuilder("The comparing process can not find a viable snapshot. "
                    + "Restore requires a complete, uncorrupted snapshot.");
    public static final String remoteSnapshotFolder = "./remoteSnapshot/";

    public static void main(String[] args) {
        if (args.length == 0 || args[0].equals("--help")) {
            printHelpAndQuit(0);
        }

        Config config = new Config(args);
        if (!config.valid) {
            System.exit(STATUS_INVALID_INPUT);
        }

        SnapshotLoader source = new SnapshotLoader(config.local, config.username, config.sourceNonce, config.sourceDirs, config.sourceHosts);
        if (config.selfCompare) {
            source.selfCompare(config.orderLevel);
        } else {
            SnapshotLoader target = new SnapshotLoader(config.local, config.username, config.targetNonce, config.targetDirs, config.targetHosts);
            source.compareWith(target);
        }
    }

    private static void printHelpAndQuit(int code) {
        System.out.println("Usage: snapshotComparer --help");
        System.out.println("Self Comparision, verify data consistency among replicas of single snapshot: snapshotComparer --self nonce");
        System.out.println("for local snapshots, use --dirs for specify directories: snapshotComparer --self --nonce nonce1 --dirs dir1,dir2,dir3");
        System.out.println("for remote snapshots, use --paths and --hosts for specify remote directories: snapshotComparer --self --nonce nonce1 --paths path1,path2 --hosts host1,host2 --user username");
        System.out.println();
        System.out.println("Peer Comparision, verify data consistency among snapshots: snapshotComparer nonce1 nonce2");
        System.out.println("for local snapshots, use --dirs for specify directories: snapshotComparer  --nonce1 nonce1 --dir1 dir1-1,dir1-2,dir1-3 nonce2 --dir2 dir2-1,dir2-2,dir2-3");
        System.out.println("for remote snapshots, use --paths and --hosts for specify remote directories: snapshotComparer --nonce1 nonce1 --paths1 path1,path2 --hosts1 host1,host2 --nonce2 nonce2 --paths2 path1,path2 --hosts2 host1,host2 --user username");
        System.out.println();
        System.out.println("For integrity check only without row order, use --ignoreOrder");
        System.out.println("For integrity check only with only chunk level row order, use --ignoreChunkOrder");
        System.exit(code);
    }

    static class Config {
        boolean selfCompare;
        // level of row order consistency
        // 0 for total order
        // 1 for chunk level order (there could be out of order within chunk, but not across chunk)
        // 2 for no order
        byte orderLevel = 0;
        Boolean local = null;
        String username = "";
        String password = "";

        String sourceNonce;
        String[] sourceDirs;
        String[] sourceHosts;

        String targetNonce;
        String[] targetDirs;
        String[] targetHosts;
        boolean cleanup = false;
        boolean valid = false;

        public Config(String[] args) {
            selfCompare = args[0].equalsIgnoreCase("--self");
            if (selfCompare) {
                for (int i = 1; i < args.length; i++) {
                    String arg = args[i];
                    if (arg.equalsIgnoreCase("--nonce")) {
                        if (i + 1 >= args.length) {
                            System.err.println("Error: Not enough args following --nonce");
                            printHelpAndQuit(STATUS_INVALID_INPUT);
                        }
                        i++;
                        sourceNonce = args[i];
                    } else if (arg.equalsIgnoreCase("--ignoreChunkOrder")) {
                        orderLevel = 1;
                    } else if (arg.equalsIgnoreCase("--ignoreOrder")) {
                        orderLevel = 2;
                    } else if (arg.equalsIgnoreCase("--dirs")) {
                        if (local != null && !local) {
                            System.err.println("Error: already specify snapshot from remote");
                            printHelpAndQuit(STATUS_INVALID_INPUT);
                        }
                        local = true;
                        if (i + 1 >= args.length) {
                            System.err.println("Error: Not enough args following --dirs");
                            printHelpAndQuit(STATUS_INVALID_INPUT);
                        }
                        i++;
                        sourceDirs = args[i].split(",");
                    } else if (arg.equalsIgnoreCase("--paths")) {
                        if (local != null && local) {
                            System.err.println("Error: already specify snapshot from local");
                            printHelpAndQuit(STATUS_INVALID_INPUT);
                        }
                        local = false;
                        if (i + 1 >= args.length) {
                            System.err.println("Error: Not enough args following --paths");
                            printHelpAndQuit(STATUS_INVALID_INPUT);
                        }
                        i++;
                        sourceDirs = args[i].split(",");
                    } else if (arg.equalsIgnoreCase("--hosts")) {
                        if (local != null && local) {
                            System.err.println("Error: already specify snapshot from local");
                            printHelpAndQuit(STATUS_INVALID_INPUT);
                        }
                        local = false;
                        if (i + 1 >= args.length) {
                            System.err.println("Error: Not enough args following --hosts");
                            printHelpAndQuit(STATUS_INVALID_INPUT);
                        }
                        i++;
                        sourceHosts = args[i].split(",");
                    } else if (arg.equalsIgnoreCase("--user")) {
                        if (i + 1 >= args.length) {
                            System.err.println("Error: Not enough args following --user");
                            printHelpAndQuit(STATUS_INVALID_INPUT);
                        }
                        i++;
                        username = args[i];
                    } else if (arg.equalsIgnoreCase("--cleanup")) {
                        cleanup = true;
                    }
                }
                if (sourceNonce == null || sourceNonce.isEmpty()) {
                    System.err.println("Error: Does not specify snapshot nonce.");
                    printHelpAndQuit(STATUS_INVALID_INPUT);
                }
                if (local == null) {
                    System.err.println("Error: Does not specify location of snapshot, either using --dirs for local or --paths for remote.");
                    printHelpAndQuit(STATUS_INVALID_INPUT);
                }
                if (!local && (
                        (sourceDirs == null) || (sourceHosts == null) || (sourceDirs.length == 0)
                                || (sourceDirs.length != sourceHosts.length))) {
                    System.err.println("Error: Directories and Host number does not match.");
                    printHelpAndQuit(STATUS_INVALID_INPUT);
                }
                if (!local && username.isEmpty()) {
                    System.err.println("Error: Does not specify username.");
                    printHelpAndQuit(STATUS_INVALID_INPUT);
                }
            } else {
                // TODO: better UI for specify target snapshot
                for (int i = 0; i < args.length; i++) {
                    String arg = args[i];
                    if (arg.equalsIgnoreCase("--nonce1")) {
                        if (i + 1 >= args.length) {
                            System.err.println("Error: Not enough args following --nonce");
                            printHelpAndQuit(STATUS_INVALID_INPUT);
                        }
                        i++;
                        sourceNonce = args[i];
                    } else if (arg.equalsIgnoreCase("--nonce2")) {
                        if (i + 1 >= args.length) {
                            System.err.println("Error: Not enough args following --nonce");
                            printHelpAndQuit(STATUS_INVALID_INPUT);
                        }
                        i++;
                        targetNonce = args[i];
                    } else if (arg.equalsIgnoreCase("--dirs1")) {
                        if (local != null && !local) {
                            System.err.println("Error: already specify snapshot from remote");
                            printHelpAndQuit(STATUS_INVALID_INPUT);
                        }
                        local = true;
                        if (i + 1 >= args.length) {
                            System.err.println("Error: Not enough args following --dirs");
                            printHelpAndQuit(STATUS_INVALID_INPUT);
                        }
                        i++;
                        sourceDirs = args[i].split(",");
                    } else if (arg.equalsIgnoreCase("--dirs2")) {
                        if (local != null && !local) {
                            System.err.println("Error: already specify snapshot from remote");
                            printHelpAndQuit(STATUS_INVALID_INPUT);
                        }
                        local = true;
                        if (i + 1 >= args.length) {
                            System.err.println("Error: Not enough args following --dirs");
                            printHelpAndQuit(STATUS_INVALID_INPUT);
                        }
                        i++;
                        targetDirs = args[i].split(",");
                    } else if (arg.equalsIgnoreCase("--paths1")) {
                        if (local != null && local) {
                            System.err.println("Error: already specify snapshot from local");
                            printHelpAndQuit(STATUS_INVALID_INPUT);
                        }
                        local = false;
                        if (i + 1 >= args.length) {
                            System.err.println("Error: Not enough args following --paths");
                            printHelpAndQuit(STATUS_INVALID_INPUT);
                        }
                        i++;
                        sourceDirs = args[i].split(",");
                    } else if (arg.equalsIgnoreCase("--paths2")) {
                        if (local != null && local) {
                            System.err.println("Error: already specify snapshot from local");
                            printHelpAndQuit(STATUS_INVALID_INPUT);
                        }
                        local = false;
                        if (i + 1 >= args.length) {
                            System.err.println("Error: Not enough args following --paths");
                            printHelpAndQuit(STATUS_INVALID_INPUT);
                        }
                        i++;
                        targetDirs = args[i].split(",");
                    } else if (arg.equalsIgnoreCase("--hosts1")) {
                        if (local != null && local) {
                            System.err.println("Error: already specify snapshot from local");
                            printHelpAndQuit(STATUS_INVALID_INPUT);
                        }
                        local = false;
                        if (i + 1 >= args.length) {
                            System.err.println("Error: Not enough args following --hosts");
                            printHelpAndQuit(STATUS_INVALID_INPUT);
                        }
                        i++;
                        sourceHosts = args[i].split(",");
                    } else if (arg.equalsIgnoreCase("--hosts2")) {
                        if (local != null && local) {
                            System.err.println("Error: already specify snapshot from local");
                            printHelpAndQuit(STATUS_INVALID_INPUT);
                        }
                        local = false;
                        if (i + 1 >= args.length) {
                            System.err.println("Error: Not enough args following --hosts");
                            printHelpAndQuit(STATUS_INVALID_INPUT);
                        }
                        i++;
                        targetHosts = args[i].split(",");
                    } else if (arg.equalsIgnoreCase("--user")) {
                        if (i + 1 >= args.length) {
                            System.err.println("Error: Not enough args following --user");
                            printHelpAndQuit(STATUS_INVALID_INPUT);
                        }
                        i++;
                        username = args[i];
                    } else if (arg.equalsIgnoreCase("--cleanup")) {
                        cleanup = true;
                    }
                }
                if (sourceNonce == null || sourceNonce.isEmpty()) {
                    System.err.println("Error: Does not specify source snapshot nonce.");
                    printHelpAndQuit(STATUS_INVALID_INPUT);
                }
                if (targetNonce == null || targetNonce.isEmpty()) {
                    System.err.println("Error: Does not specify comparing snapshot nonce.");
                    printHelpAndQuit(STATUS_INVALID_INPUT);
                }
                if (local == null) {
                    System.err.println("Error: Does not specify location of snapshot, either using --dirs for local or --paths for remote.");
                    printHelpAndQuit(STATUS_INVALID_INPUT);
                }
                if (!local && (
                        (sourceDirs == null) || (sourceHosts == null) || (sourceDirs.length == 0)
                                || (sourceDirs.length != sourceHosts.length)
                                || (targetDirs == null) || (targetHosts == null) || (targetDirs.length == 0)
                                || (targetDirs.length != targetHosts.length))) {
                    System.err.println("Error: Directories and Host number does not match.");
                    printHelpAndQuit(STATUS_INVALID_INPUT);
                }
                if (!local && username.isEmpty()) {
                    System.err.println("Error: Does not specify username.");
                    printHelpAndQuit(STATUS_INVALID_INPUT);
                }
            }
            if (!local && cleanup) {
                try {
                    FileUtils.deleteDirectory(new File(remoteSnapshotFolder));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            valid = true;
        }
    }
}

class SnapshotLoader {
    final String nonce;
    final String[] dirs;
    final String[] hosts;
    InMemoryJarfile jar;
    ElasticHashinator hashinator;
    final Snapshot snapshot;
    final Map<String, Boolean> tables;
    int partitionCount, totalHost;

    public SnapshotLoader(boolean local, String username, String nonce, String[] dirs, String[] hosts) {
        this.dirs = dirs;
        this.nonce = nonce;
        this.hosts = hosts;
        ArrayList<File> directories = new ArrayList<>();
        if (local) {
            boolean invalidDir = false;
            for (String path : dirs) {
                File f = new File(path);
                if (!f.exists()) {
                    System.err.println("Error: " + path + " does not exist");
                    invalidDir = true;
                }
                if (!f.canRead()) {
                    System.err.println("Error: " + path + " does not have read permission set");
                    invalidDir = true;
                }
                if (!f.canExecute()) {
                    System.err.println("Error: " + path + " does not have execute permission set");
                    invalidDir = true;
                }
                directories.add(f);
                if (invalidDir) {
                    System.exit(STATUS_INVALID_INPUT);
                }
                if (directories.isEmpty()) {
                    directories.add(new File("."));
                }
            }
        } else {
            // if from remote, first fetch to local
            File localRootDir = new File(remoteSnapshotFolder + nonce);
            localRootDir.mkdirs();
            for (int i = 0; i < hosts.length; i++) {
                File localDir = new File(localRootDir.getPath() + PATHSEPARATOR + hosts[i]);
                localDir.mkdirs();
                if (!downloadFiles(username, hosts[i], dirs[i], localDir.getPath())) {
                    System.exit(STATUS_INVALID_INPUT);
                }
                directories.add(localDir);
            }
        }
        // Check snapshot completeness
        Map<String, SnapshotUtil.Snapshot> snapshots = new TreeMap<>();
        HashSet<String> snapshotNames = new HashSet<>();
        snapshotNames.add(nonce);
        SnapshotUtil.SpecificSnapshotFilter filter = new SnapshotUtil.SpecificSnapshotFilter(snapshotNames);
        for (File directory : directories) {
            SnapshotUtil.retrieveSnapshotFiles(directory, snapshots, filter, false, SnapshotPathType.SNAP_PATH, SNAPSHOT_LOG);
        }

        if (snapshots.size() > 1) {
            System.err.println("Error: Found " + snapshots.size() + " snapshots with specified name");
            int ii = 0;
            for (SnapshotUtil.Snapshot entry : snapshots.values()) {
                System.err.println("Snapshot " + ii + " taken " + new Date(entry.getInstanceId().getTimestamp()));
                System.err.println("Files: ");
                for (File digest : entry.m_digests) {
                    System.err.println("\t" + digest.getPath());
                }
                for (Map.Entry<String, SnapshotUtil.TableFiles> e2 : entry.m_tableFiles.entrySet()) {
                    System.err.println("\t" + e2.getKey());
                    for (File tableFile : e2.getValue().m_files) {
                        System.err.println("\t\t" + tableFile.getPath());
                    }
                }
                ii++;
            }
            System.exit(STATUS_INVALID_INPUT);
        }

        if (snapshots.size() < 1) {
            System.err.println("Error: Did not find any snapshots with the specified name");
            System.exit(STATUS_INVALID_INPUT);
        }
        snapshot = snapshots.values().iterator().next();
        RestoreAgent.SnapshotInfo info = checkSnapshotIsComplete(snapshot.getTxnId(), snapshot, SnapshotComparer.m_ErrLogStr, 0);
        if (info == null) {
            System.exit(STATUS_INVALID_INPUT);
        }
        partitionCount = info.partitionCount;

        // load the in memory jar
        try {
            jar = getInMemoryJarFileBySnapShotName(directories.get(0).getPath(), nonce);
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(STATUS_INVALID_INPUT);
        }

        // Deserialize hashinator from .hash file
        // Use HASH_EXTENSION
        // ElasticHashinator hashinator = getHashinatorFromFile(dirs[0], nonce, Integer.parseInt(hosts.get(0)));
        // Validate SnapshotName
        // Collect tables
        tables = new HashMap<>();
            // check all tables, get from catalog
        for (SnapshotTableInfo sti : SnapshotUtil.getTablesToSave(CatalogUtil.getDatabaseFrom(jar))) {
            tables.put(sti.getName(), sti.isReplicated());
        }
    }

    /**
     * Validate the data consistency within the snapshot
     */
    // Todo: now is 1-1 comparing, implement m-way comparing
    public void selfCompare(byte orderLevel) {
        boolean fail = false;
        // Build a plan for which save file as the baseline for each partition
        Map<String, List<List<File>>> tableToCopies = new HashMap<>();
        for (String tableName : tables.keySet()) {
            if (!snapshot.m_tableFiles.containsKey(tableName)) {
                System.err.println("Error: Snapshot does not contain table " + tableName);
                System.exit(STATUS_INVALID_INPUT);
            }
            SnapshotUtil.TableFiles tableFiles = snapshot.m_tableFiles.get(tableName);
            if (!tableFiles.m_isReplicated) {
                TreeSet<Integer> partitionsIds = new TreeSet<>();
                List<List<File>> partitionToFiles = new ArrayList<>();
                for (int i = 0; i < partitionCount; i++) {
                    partitionToFiles.add(new ArrayList<>());
                }
                for (int ii = 0; ii < tableFiles.m_files.size(); ii++) {
                    Set<Integer> validPartitions = tableFiles.m_validPartitionIds.get(ii);
                    partitionsIds.addAll(validPartitions);
                    for (int p : validPartitions) {
                        partitionToFiles.get(p).add(tableFiles.m_files.get(ii));
                    }
                }
                int totalPartitionCount = tableFiles.m_totalPartitionCounts.get(0);
                if (!((partitionsIds.size() == totalPartitionCount) &&
                        (partitionsIds.first() == 0) &&
                        (partitionsIds.last() == totalPartitionCount - 1))) {
                    System.err.println("Error: Not all partitions present for table " + tableName);
                    fail = true;
                } else {
                    tableToCopies.put(tableName, partitionToFiles);
                }
            } else {
                List<List<File>> partitionToFiles = new ArrayList<>();
                partitionToFiles.add(new ArrayList<>());
                partitionToFiles.get(0).addAll(tableFiles.m_files);
                tableToCopies.put(tableName, partitionToFiles);
            }
        }
        if (fail) {
            System.exit(STATUS_INVALID_INPUT);
        }

        // based on the plan, retrieve the file and compare
        Set<String> inconsistentTable = new HashSet<>();
        for (String tableName : tables.keySet()) {
            List<List<File>> partitionToFiles = tableToCopies.get(tableName);
            boolean isReplicated = tables.get(tableName);
            for (int p = 0; p < partitionToFiles.size(); p++) {
                Integer[] relevantPartition = isReplicated ? null : new Integer[]{p};
                int partitionid = isReplicated ? 16383 : p;
                for (int target = 1; target < partitionToFiles.get(p).size(); target++) {
                    boolean isConsistent = true;

                    long refCheckSum = 0l, compCheckSum = 0l;
                    try (TableSaveFile referenceSaveFile = new TableSaveFile(
                            new FileInputStream(partitionToFiles.get(p).get(0)), 1, relevantPartition);
                            TableSaveFile compareSaveFile = new TableSaveFile(
                                    new FileInputStream(partitionToFiles.get(p).get(target)), 1, relevantPartition)) {
                        DBBPool.BBContainer cr = null, cc = null;

                        while (referenceSaveFile.hasMoreChunks() && compareSaveFile.hasMoreChunks()) {
                            // skip chunk for irrelevant partition
                            cr = referenceSaveFile.getNextChunk();
                            cc = compareSaveFile.getNextChunk();

                            if (cr == null && cc == null) { // both reached EOF
                                break;
                            }
                            try {
                                // TODO: chunk not aligned?
                                if (cr != null && cc == null) {
                                    System.err.println("Reference file still contain chunks while comparing file does not");
                                    break;
                                }
                                if (cr == null && cc != null) {
                                    System.err.println("Comparing file still contain chunks while reference file does not");
                                    break;
                                }

                                final VoltTable tr = PrivateVoltTableFactory.createVoltTableFromBuffer(cr.b(), true);
                                final VoltTable tc = PrivateVoltTableFactory.createVoltTableFromBuffer(cc.b(), true);
                                if (orderLevel == 2) {
                                    refCheckSum = tr.updateCheckSum(refCheckSum);
                                    compCheckSum = tc.updateCheckSum(compCheckSum);
                                } else if (!tr.hasSameContents(tc, orderLevel == 1)) {
                                    // seek to find where discrepancy happened
                                    if (SNAPSHOT_LOG.isDebugEnabled()) {
                                        SNAPSHOT_LOG.debug(
                                                "table from file: " + partitionToFiles.get(p).get(0) + " : " + tr);
                                        SNAPSHOT_LOG.debug(
                                                "table from file: " + partitionToFiles.get(p).get(target) + " : " + tc);
                                    }

                                    int trSize = tr.getRowCount(), tcSize = tc.getRowCount();
                                    int[][] lookup = new int[trSize + 1][tcSize + 1];
                                    // fill lookup table
                                    lcsLength(tr, tc, trSize, tcSize, lookup);
                                    // find difference
                                    StringBuilder output = new StringBuilder().append("Diffs between file ")
                                            .append(partitionToFiles.get(p).get(0)).append(" and file ")
                                            .append(partitionToFiles.get(p).get(target)).append(" \n");
                                    diff(tr, tc, trSize, tcSize, lookup, output);
                                    CONSOLE_LOG.info(output.toString());
                                    isConsistent = false;
                                    break;
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            } finally {
                                if (cr != null) {
                                    cr.discard();
                                }
                                if (cc != null) {
                                    cc.discard();
                                }
                            }
                        }

                        if (orderLevel == 2) {
                            isConsistent = isConsistent && (refCheckSum == compCheckSum);
                        }
                        if (isConsistent) {
                            SNAPSHOT_LOG.info((isReplicated ? "Replicated" : "Partitioned") + " Table " + tableName
                                    + " is consistent between host0 with host" + target + " on partition "
                                    + partitionid);
                        } else {
                            inconsistentTable.add(tableName);
                            SNAPSHOT_LOG.warn((isReplicated ? "Replicated" : "Partitioned") + " Table " + tableName
                                    + " is inconsistent between host0 with host" + target + " on partition "
                                    + partitionid);
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
            CONSOLE_LOG.info("Finished comparing table " + tableName + ".\n");
        }
        CONSOLE_LOG.info("Finished comparing all tables.");
        if (!inconsistentTable.isEmpty()) {
            CONSOLE_LOG.info("The inconsistent tables are: " + inconsistentTable);
            System.exit(STATUS_INCONSISTENCY);
        } else {
            System.exit(STATUS_OK);
        }
    }

    // Function to display the differences between two voltTables
    public static void diff(VoltTable X, VoltTable Y, int m, int n, int[][] lookup, StringBuilder sb) {
        // if last character of X and Y matches
        // TODO: optimize by useing bottom up dp, need traverse volttable backward
        X.resetRowPosition();
        X.advanceRow();
        X.advanceToRow(m-1);
        Y.resetRowPosition();
        Y.advanceRow();
        Y.advanceToRow(n-1);
        if (m > 0 && n > 0 && X.getRawRow().equals(Y.getRawRow())) {
            String curRow = "  " + X.getRow();
            diff(X, Y, m - 1, n - 1, lookup, sb);
            sb.append(curRow);
        }
        // current row of Y is not present in X
        else if (n > 0 && (m == 0 || lookup[m][n - 1] >= lookup[m - 1][n])) {
            String curRow = " +" + Y.getRow();
            diff(X, Y, m, n - 1, lookup, sb);
            sb.append(curRow);
        }

        // current row of X is not present in Y
        else if (m > 0 && (n == 0 || lookup[m][n - 1] < lookup[m - 1][n])) {
            String curRow = " -" + X.getRow();
            diff(X, Y, m - 1, n, lookup, sb);
            sb.append(curRow);
        }
    }

    // Function to fill lookup table by finding the length of LCS
    private static void lcsLength(VoltTable X, VoltTable Y, int m, int n,
                                 int[][] lookup) {
        // first column of the lookup table will be all 0
        for (int i = 0; i <= m; i++) {
            lookup[i][0] = 0;
        }

        // first row of the lookup table will be all 0
        for (int j = 0; j <= n; j++) {
            lookup[0][j] = 0;
        }

        // fill the lookup table in bottom-up manner
        for (int i = 1; i <= m; i++) {
            X.advanceRow();
            Y.resetRowPosition();
            for (int j = 1; j <= n; j++) {
                Y.advanceRow();
                // if current row of X and Y matches
                if (X.getRawRow().equals(Y.getRawRow())) {
                    lookup[i][j] = lookup[i - 1][j - 1] + 1;
                } // else if current row of X and Y don't match
                else {
                    lookup[i][j] = Integer.max(lookup[i - 1][j],
                            lookup[i][j - 1]);
                }
            }
        }
    }

    public void compareWith(SnapshotLoader another) {
        boolean fail = false;
        // Build a plan for which save file as the baseline for each partition
        Map<String, List<List<File>>> tableToCopies = new HashMap<>();
        for (String tableName : tables.keySet()) {
            if (!snapshot.m_tableFiles.containsKey(tableName)) {
                System.err.println("Error: Snapshot does not contain table " + tableName);
                System.exit(STATUS_INVALID_INPUT);
            }
            SnapshotUtil.TableFiles tableFiles = snapshot.m_tableFiles.get(tableName);
            if (!tableFiles.m_isReplicated) {
                TreeSet<Integer> partitionsIds = new TreeSet<>();
                List<List<File>> partitionToFiles = new ArrayList<>();
                for (int i = 0; i < partitionCount; i++) {
                    partitionToFiles.add(new ArrayList<>());
                }
                for (int ii = 0; ii < tableFiles.m_files.size(); ii++) {
                    Set<Integer> validPartitions = tableFiles.m_validPartitionIds.get(ii);
                    partitionsIds.addAll(validPartitions);
                    for (int p : validPartitions) {
                        partitionToFiles.get(p).add(tableFiles.m_files.get(ii));
                    }
                }
                int totalPartitionCount = tableFiles.m_totalPartitionCounts.get(0);
                if (!((partitionsIds.size() == totalPartitionCount) &&
                        (partitionsIds.first() == 0) &&
                        (partitionsIds.last() == totalPartitionCount - 1))) {
                    System.err.println("Error: Not all partitions present for table " + tableName);
                    fail = true;
                } else {
                    tableToCopies.put(tableName, partitionToFiles);
                }
            } else {
                List<List<File>> partitionToFiles = new ArrayList<>();
                partitionToFiles.add(new ArrayList<>());
                partitionToFiles.get(0).addAll(tableFiles.m_files);
                tableToCopies.put(tableName, partitionToFiles);
            }
        }
        if (fail) {
            System.exit(STATUS_INVALID_INPUT);
        }
        // based on the plan, retrieve the file and compare
        Set<String> inconsistentTable = new HashSet<>();

        CONSOLE_LOG.info("Finished comparing all tables.");
        if (!inconsistentTable.isEmpty()) {
            CONSOLE_LOG.info("The inconsistent tables are: " + inconsistentTable);
            System.exit(STATUS_INCONSISTENCY);
        } else {
            System.exit(STATUS_OK);
        }
    }

    /**
     * return InMemoryJarfile of a snapshot jar file
     */
    private InMemoryJarfile getInMemoryJarFileBySnapShotName(String location, String nonce) throws IOException {
        final File file = new VoltFile(location, nonce + ".jar");
        byte[] bytes = MiscUtils.fileToBytes(file);
        return CatalogUtil.loadInMemoryJarFile(bytes);
    }

    private static ElasticHashinator getHashinatorFromFile(String location, String nonce, int hostId) throws IOException {
        File hashFile = new File(location + '/' + nonce + "-host_" + hostId + ".hash");
        HashinatorSnapshotData hashinatorSSData = new HashinatorSnapshotData();
        hashinatorSSData.restoreFromFile(hashFile);
        return new ElasticHashinator(hashinatorSSData.m_serData, true);
    }

    /**
     * using publickey for auth
     */
    private ChannelSftp setupJsch(String username, String remoteHost) throws JSchException {
        JSch jsch = new JSch();
        jsch.setKnownHosts("~/.ssh/known_hosts");
        jsch.addIdentity("~/.ssh/id_rsa");

        Session session = jsch.getSession(username, remoteHost);
        session.setConfig("PreferredAuthentications", "publickey");
        session.setConfig("StrictHostKeyChecking", "no");

        // jschSession.setPassword(password);
        session.connect(30000);
        return (ChannelSftp) session.openChannel("sftp");
    }

    /**
     * download remote files through ssh
     */
    static String PATHSEPARATOR = "/";

    private boolean downloadFiles(String username, String remoteHost, String sourcePath, String destinationPath) {
        ChannelSftp channelSftp = null;
        try {
            channelSftp = setupJsch(username, remoteHost);
            channelSftp.connect();
            channelSftp.cd(sourcePath);
            // list of folder content
            Vector<ChannelSftp.LsEntry> fileAndFolderList = channelSftp.ls(sourcePath);
            //Iterate through list of folder content
            for (ChannelSftp.LsEntry item : fileAndFolderList) {
                if (!item.getAttrs().isDir()) { // Check if it is a file (not a directory).
                    if (!(new File(destinationPath + PATHSEPARATOR + item.getFilename())).exists()
                            || (item.getAttrs().getMTime() > Long
                            .valueOf(new File(destinationPath + PATHSEPARATOR + item.getFilename()).lastModified()
                                    / 1000)
                            .intValue())) { // Download only if changed later.
                        new File(destinationPath + PATHSEPARATOR + item.getFilename());
                        channelSftp.get(sourcePath + PATHSEPARATOR + item.getFilename(),
                                destinationPath + PATHSEPARATOR + item.getFilename()); // Download file from source (source filename, destination filename).
                    }
                }
            }
        } catch (JSchException | SftpException ex) {
            ex.printStackTrace();
            return false;
        } finally {
            if (channelSftp != null) {
                channelSftp.disconnect();
            }
        }
        return true;
    }
}
