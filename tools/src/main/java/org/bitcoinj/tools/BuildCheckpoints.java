/*
 * Copyright 2013 Google Inc.
 * Copyright 2014 Andreas Schildbach
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.litecoinj.tools;

import org.litecoinj.core.listeners.NewBestBlockListener;
import org.litecoinj.core.*;
import org.litecoinj.net.discovery.DnsDiscovery;
import org.litecoinj.params.MainNetParams;
import org.litecoinj.params.RegTestParams;
import org.litecoinj.params.TestNet3Params;
import org.litecoinj.store.BlockStore;
import org.litecoinj.store.MemoryBlockStore;
import org.litecoinj.utils.BriefLogFormatter;
import org.litecoinj.utils.Threading;
import com.google.common.io.Resources;
import picocli.CommandLine;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Downloads and verifies a full chain from your local peer, emitting checkpoints at each difficulty transition period
 * to a file which is then signed with your key.
 */
@CommandLine.Command(name = "build-checkpoints", usageHelpAutoWidth = true, sortOptions = false, description = "Create checkpoint files to use with CheckpointManager.")
public class BuildCheckpoints implements Callable<Integer> {
    @CommandLine.Option(names = "--net", description = "Which network to connect to. Valid values: ${COMPLETION-CANDIDATES}. Default: ${DEFAULT-VALUE}")
    private NetworkEnum net = NetworkEnum.MAIN;
    @CommandLine.Option(names = "--peer", description = "IP address/domain name for connection instead of localhost.")
    private String peer = null;
    @CommandLine.Option(names = "--days", description = "How many days to keep as a safety margin. Checkpointing will be done up to this many days ago.")
    private int days = 7;
    @CommandLine.Option(names = "--help", usageHelp = true, description = "Displays program options.")
    private boolean help;

    private static NetworkParameters params;

    public static void main(String[] args) throws Exception {
        BriefLogFormatter.initWithSilentBitcoinJ();
        int exitCode = new CommandLine(new BuildCheckpoints()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        final String suffix;
        switch (net) {
            case MAIN:
            case PROD:
                params = MainNetParams.get();
                suffix = "";
                break;
            case TEST:
                params = TestNet3Params.get();
                suffix = "-testnet";
                break;
            case REGTEST:
                params = RegTestParams.get();
                suffix = "-regtest";
                break;
            default:
                throw new RuntimeException("Unreachable.");
        }

        // Configure litecoinj to fetch only headers, not save them to disk, connect to a local fully synced/validated
        // node and to save block headers that are on interval boundaries, as long as they are <1 month old.
        final BlockStore store = new MemoryBlockStore(params);
        final BlockChain chain = new BlockChain(params, store);
        final PeerGroup peerGroup = new PeerGroup(params, chain);

        final InetAddress ipAddress;

        // DNS discovery can be used for some networks
        boolean networkHasDnsSeeds = params.getDnsSeeds() != null;
        if (peer != null) {
            // use peer provided in argument
            try {
                ipAddress = InetAddress.getByName(peer);
                startPeerGroup(peerGroup, ipAddress);
            } catch (UnknownHostException e) {
                System.err.println("Could not understand peer domain name/IP address: " + peer + ": " + e.getMessage());
                return 1;
            }
        } else if (networkHasDnsSeeds) {
            // for PROD and TEST use a peer group discovered with dns
            peerGroup.setUserAgent("PeerMonitor", "1.0");
            peerGroup.setMaxConnections(4);
            peerGroup.addPeerDiscovery(new DnsDiscovery(params));
            peerGroup.start();

            // Connect to at least 4 peers because some may not support download
            Future<List<Peer>> future = peerGroup.waitForPeers(2);
            System.out.println("Connecting to " + params.getId() + ", timeout 20 seconds...");
            // throw timeout exception if we can't get peers
            future.get(60, SECONDS);
        } else {
            // try localhost
            ipAddress = InetAddress.getLocalHost();
            startPeerGroup(peerGroup, ipAddress);
        }

        // Sorted map of block height to StoredBlock object.
        final TreeMap<Integer, StoredBlock> checkpoints = new TreeMap<>();

        long now = new Date().getTime() / 1000;
        peerGroup.setFastCatchupTimeSecs(now);

        final long timeAgo = now - (86400 * days);
        System.out.println("Checkpointing up to " + Utils.dateTimeFormat(timeAgo * 1000));

        chain.addNewBestBlockListener(Threading.SAME_THREAD, block -> {
            int height = block.getHeight();
            if (height % params.getInterval() == 0 && block.getHeader().getTimeSeconds() <= timeAgo) {
                System.out.println(String.format("Checkpointing block %s at height %d, time %s",
                        block.getHeader().getHash(), block.getHeight(), Utils.dateTimeFormat(block.getHeader().getTime())));
                checkpoints.put(height, block);
            } else if ((height+1) % params.getInterval() == 0 && block.getHeader().getTimeSeconds() <= timeAgo) {
                System.out.println(String.format("Checkpointing block %s at height %d, time %s",
                        block.getHeader().getHash(), block.getHeight(), Utils.dateTimeFormat(block.getHeader().getTime())));
                checkpoints.put(height, block);
            }
        });

        peerGroup.downloadBlockChain();

        checkState(checkpoints.size() > 0);

        final File plainFile = new File("checkpoints" + suffix);
        final File textFile = new File("checkpoints" + suffix + ".txt");

        // Write checkpoint data out.
        writeBinaryCheckpoints(checkpoints, plainFile);
        writeTextualCheckpoints(checkpoints, textFile);

        peerGroup.stop();
        store.close();

        return 0;
    }

    private static void writeBinaryCheckpoints(TreeMap<Integer, StoredBlock> checkpoints, File file) throws Exception {
        MessageDigest digest = Sha256Hash.newDigest();
        try (FileOutputStream fileOutputStream = new FileOutputStream(file, false);
             DigestOutputStream digestOutputStream = new DigestOutputStream(fileOutputStream, digest);
             DataOutputStream dataOutputStream = new DataOutputStream(digestOutputStream)) {
            digestOutputStream.on(false);
            dataOutputStream.writeBytes("CHECKPOINTS 1");
            dataOutputStream.writeInt(0); // Number of signatures to read. Do this later.
            digestOutputStream.on(true);
            dataOutputStream.writeInt(checkpoints.size());
            ByteBuffer buffer = ByteBuffer.allocate(StoredBlock.COMPACT_SERIALIZED_SIZE);
            for (StoredBlock block : checkpoints.values()) {
                block.serializeCompact(buffer);
                dataOutputStream.write(buffer.array());
                ((Buffer) buffer).position(0);
            }
            Sha256Hash checkpointsHash = Sha256Hash.wrap(digest.digest());
            System.out.println("Hash of checkpoints data is " + checkpointsHash);
            System.out.println("Checkpoints written to '" + file.getCanonicalPath() + "'.");
        }
    }

    private static final BigInteger MAX_WORK_V1 = new BigInteger(/* 12 bytes */ "ffffffffffffffffffffffff", 16);

    private static void writeTextualCheckpoints(TreeMap<Integer, StoredBlock> checkpoints, File file)
            throws IOException {
        try (PrintWriter writer = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.US_ASCII))) {
            writer.println("TXT CHECKPOINTS 1");
            writer.println("0"); // Number of signatures to read. Do this later.
            writer.println(checkpoints.size());
            ByteBuffer bufferV1 = ByteBuffer.allocate(StoredBlock.COMPACT_SERIALIZED_SIZE);
            ByteBuffer bufferV2 = ByteBuffer.allocate(StoredBlock.COMPACT_SERIALIZED_SIZE_V2);
            for (StoredBlock block : checkpoints.values()) {
                if (block.getChainWork().compareTo(MAX_WORK_V1) <= 0) {
                    ((Buffer) bufferV1).rewind();
                    block.serializeCompact(bufferV1);
                    writer.println(CheckpointManager.BASE64.encode(bufferV1.array()));
                } else {
                    ((Buffer) bufferV2).rewind();
                    block.serializeCompactV2(bufferV2);
                    writer.println(CheckpointManager.BASE64.encode(bufferV2.array()));
                }
            }
            System.out.println("Checkpoints written to '" + file.getCanonicalPath() + "'.");
        }
    }

    private static void startPeerGroup(PeerGroup peerGroup, InetAddress ipAddress) {
        final PeerAddress peerAddress = new PeerAddress(params, ipAddress);
        System.out.println("Connecting to " + peerAddress + "...");
        peerGroup.addAddress(peerAddress);
        peerGroup.start();
    }
}
