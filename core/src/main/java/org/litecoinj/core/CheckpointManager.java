/*
 * Copyright 2013 Google Inc.
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

package org.litecoinj.core;

import org.litecoinj.store.BlockStore;
import org.litecoinj.store.BlockStoreException;
import org.litecoinj.store.FullPrunedBlockStore;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;

import org.litecoinj.store.SPVBlockStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.*;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.*;

import static com.google.common.base.Preconditions.*;

/**
 * <p>Vends hard-coded {@link StoredBlock}s for blocks throughout the chain. Checkpoints serve two purposes:</p>
 * <ol>
 *    <li>They act as a safety mechanism against huge re-orgs that could rewrite large chunks of history, thus
 *    constraining the block chain to be a consensus mechanism only for recent parts of the timeline.</li>
 *    <li>They allow synchronization to the head of the chain for new wallets/users much faster than syncing all
 *    headers from the genesis block.</li>
 * </ol>
 *
 * <p>Checkpoints are used by the SPV {@link BlockChain} to initialize fresh
 * {@link SPVBlockStore}s. They are not used by fully validating mode, which instead has a
 * different concept of checkpoints that are used to hard-code the validity of blocks that violate BIP30 (duplicate
 * coinbase transactions). Those "checkpoints" can be found in NetworkParameters.</p>
 *
 * <p>The file format consists of the string "CHECKPOINTS 1", followed by a uint32 containing the number of signatures
 * to read. The value may not be larger than 256 (so it could have been a byte but isn't for historical reasons).
 * If the number of signatures is larger than zero, each 65 byte ECDSA secp256k1 signature then follows. The signatures
 * sign the hash of all bytes that follow the last signature.</p>
 *
 * <p>After the signatures come an int32 containing the number of checkpoints in the file. Then each checkpoint follows
 * one after the other. A checkpoint is 12 bytes for the total work done field, 4 bytes for the height, 80 bytes
 * for the block header and then 1 zero byte at the end (i.e. number of transactions in the block: always zero).</p>
 */
public class CheckpointManager {
    private static final Logger log = LoggerFactory.getLogger(CheckpointManager.class);

    private static final String BINARY_MAGIC = "CHECKPOINTS 1";
    private static final String TEXTUAL_MAGIC = "TXT CHECKPOINTS 1";
    private static final int MAX_SIGNATURES = 256;

    // Map of block header time (in seconds) to data.
    protected final TreeMap<Long, StoredBlock> checkpoints = new TreeMap<>();

    protected final NetworkParameters params;
    protected final Sha256Hash dataHash;

    public static final BaseEncoding BASE64 = BaseEncoding.base64().omitPadding();

    /** Loads the default checkpoints bundled with litecoinj */
    public CheckpointManager(Context context) throws IOException {
        this(context.getParams(), null);
    }

    /** Loads the checkpoints from the given stream */
    public CheckpointManager(NetworkParameters params, @Nullable InputStream inputStream) throws IOException {
        this.params = checkNotNull(params);
        if (inputStream == null)
            inputStream = openStream(params);
        checkNotNull(inputStream);
        inputStream = new BufferedInputStream(inputStream);
        inputStream.mark(1);
        int first = inputStream.read();
        inputStream.reset();
        if (first == BINARY_MAGIC.charAt(0))
            dataHash = readBinary(inputStream);
        else if (first == TEXTUAL_MAGIC.charAt(0))
            dataHash = readTextual(inputStream);
        else
            throw new IOException("Unsupported format.");
    }

    /** Returns a checkpoints stream pointing to inside the litecoinj JAR */
    public static InputStream openStream(NetworkParameters params) {
        return CheckpointManager.class.getResourceAsStream("/" + params.getId() + ".checkpoints.txt");
    }

    private Sha256Hash readBinary(InputStream inputStream) throws IOException {
        DataInputStream dis = null;
        try {
            MessageDigest digest = Sha256Hash.newDigest();
            DigestInputStream digestInputStream = new DigestInputStream(inputStream, digest);
            dis = new DataInputStream(digestInputStream);
            digestInputStream.on(false);
            byte[] header = new byte[BINARY_MAGIC.length()];
            dis.readFully(header);
            if (!Arrays.equals(header, BINARY_MAGIC.getBytes(StandardCharsets.US_ASCII)))
                throw new IOException("Header bytes did not match expected version");
            int numSignatures = checkPositionIndex(dis.readInt(), MAX_SIGNATURES, "Num signatures out of range");
            for (int i = 0; i < numSignatures; i++) {
                byte[] sig = new byte[65];
                dis.readFully(sig);
                // TODO: Do something with the signature here.
            }
            digestInputStream.on(true);
            int numCheckpoints = dis.readInt();
            checkState(numCheckpoints > 0);
            final int size = StoredBlock.COMPACT_SERIALIZED_SIZE;
            ByteBuffer buffer = ByteBuffer.allocate(size);
            for (int i = 0; i < numCheckpoints; i++) {
                if (dis.read(buffer.array(), 0, size) < size)
                    throw new IOException("Incomplete read whilst loading checkpoints.");
                StoredBlock block = StoredBlock.deserializeCompact(params, buffer);
                ((Buffer) buffer).position(0);
                checkpoints.put(block.getHeader().getTimeSeconds(), block);
            }
            Sha256Hash dataHash = Sha256Hash.wrap(digest.digest());
            log.info("Read {} checkpoints up to time {}, hash is {}", checkpoints.size(),
                    Utils.dateTimeFormat(checkpoints.lastEntry().getKey() * 1000), dataHash);
            return dataHash;
        } catch (ProtocolException e) {
            throw new IOException(e);
        } finally {
            if (dis != null) dis.close();
            inputStream.close();
        }
    }

    private Sha256Hash readTextual(InputStream inputStream) throws IOException {
        Hasher hasher = Hashing.sha256().newHasher();
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.US_ASCII))) {
            String magic = reader.readLine();
            if (!TEXTUAL_MAGIC.equals(magic))
                throw new IOException("unexpected magic: " + magic);
            int numSigs = Integer.parseInt(reader.readLine());
            for (int i = 0; i < numSigs; i++)
                reader.readLine(); // Skip sigs for now.
            int numCheckpoints = Integer.parseInt(reader.readLine());
            checkState(numCheckpoints > 0);
            // Hash numCheckpoints in a way compatible to the binary format.
            hasher.putBytes(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(numCheckpoints).array());
            for (int i = 0; i < numCheckpoints; i++) {
                byte[] bytes = BASE64.decode(reader.readLine());
                hasher.putBytes(bytes);
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                StoredBlock block;
                if (bytes.length == StoredBlock.COMPACT_SERIALIZED_SIZE)
                    block = StoredBlock.deserializeCompact(params, buffer);
                else if (bytes.length == StoredBlock.COMPACT_SERIALIZED_SIZE_V2)
                    block = StoredBlock.deserializeCompactV2(params, buffer);
                else
                    throw new IllegalStateException("unexpected length of checkpoint: " + bytes.length);
                checkpoints.put(block.getHeader().getTimeSeconds(), block);
            }
            HashCode hash = hasher.hash();
            log.info("Read {} checkpoints up to time {}, hash is {}", checkpoints.size(),
                    Utils.dateTimeFormat(checkpoints.lastEntry().getKey() * 1000), hash);
            return Sha256Hash.wrap(hash.asBytes());
        }
    }

    /**
     * Returns a {@link StoredBlock} representing the last checkpoint before the given time, for example, normally
     * you would want to know the checkpoint before the earliest wallet birthday.
     */
    public StoredBlock getCheckpointBefore(long timeSecs) {
        try {
            checkArgument(timeSecs > params.getGenesisBlock().getTimeSeconds());
            // This is thread safe because the map never changes after creation.
            Map.Entry<Long, StoredBlock> entry = checkpoints.floorEntry(timeSecs);
            if (entry != null) return entry.getValue();
            Block genesis = params.getGenesisBlock().cloneAsHeader();
            return new StoredBlock(genesis, genesis.getWork(), 0);
        } catch (VerificationException e) {
            throw new RuntimeException(e);  // Cannot happen.
        }
    }

    public ArrayList<StoredBlock> getCheckpointsBefore(long timeSecs) {
        try {
            ArrayList<StoredBlock> checkpointsBefore = new ArrayList<>();
            checkArgument(timeSecs > params.getGenesisBlock().getTimeSeconds());
            // This is thread safe because the map never changes after creation.
            Map.Entry<Long, StoredBlock> entry = checkpoints.floorEntry(timeSecs);
            if (entry != null) {
                StoredBlock mostRecentCheckpointBlock = entry.getValue();
                StoredBlock blockBefore = getBlockBefore(mostRecentCheckpointBlock, checkpoints);
                checkpointsBefore.add(blockBefore);
                checkpointsBefore.add(mostRecentCheckpointBlock);
                return checkpointsBefore;
            }
            Block genesis = params.getGenesisBlock().cloneAsHeader();
            checkpointsBefore.add(new StoredBlock(genesis, genesis.getWork(), 0));
            return checkpointsBefore;
        } catch (VerificationException e) {
            throw new RuntimeException(e);  // Cannot happen.
        }
    }

    public StoredBlock getBlockBefore(StoredBlock block, TreeMap<Long, StoredBlock> checkpoints) {
        /*
        Litecoin does this weird thing with the difficulty adjustment algorithm.
        In Bitcoin, upon time for the difficulty to adjust (every 2016 blocks), it actually goes back 2015 blocks to calculate.
        In Litecoin, it goes a full 2016 blocks, so for the checkpoint manager to fully work in litecoinj, we need both the actual
        block where the difficulty changes, and the block before it.
         */
        int heightToLookFor = block.getHeight()-1;
        for(StoredBlock checkpoint : checkpoints.values()) {
            if(checkpoint.getHeight() == heightToLookFor) {
                return checkpoint;
            }
        }
        return null;
    }

    /** Returns the number of checkpoints that were loaded. */
    public int numCheckpoints() {
        return checkpoints.size();
    }

    /** Returns a hash of the concatenated checkpoint data. */
    public Sha256Hash getDataHash() {
        return dataHash;
    }

    /**
     * <p>Convenience method that creates a CheckpointManager, loads the given data, gets the checkpoint for the given
     * time, then inserts it into the store and sets that to be the chain head. Useful when you have just created
     * a new store from scratch and want to use configure it all in one go.</p>
     *
     * <p>Note that timeSecs is adjusted backwards by a week to account for possible clock drift in the block headers.</p>
     */
    public static void checkpoint(NetworkParameters params, InputStream checkpoints, BlockStore store, long timeSecs)
            throws IOException, BlockStoreException {
        checkNotNull(params);
        checkNotNull(store);
        checkArgument(!(store instanceof FullPrunedBlockStore), "You cannot use checkpointing with a full store.");

        timeSecs -= 60 * 60 * 24 * 7; // one week in seconds

        checkArgument(timeSecs > 0);
        log.info("Attempting to initialize a new block store with a checkpoint for time {} ({})", timeSecs,
                Utils.dateTimeFormat(timeSecs * 1000));

        BufferedInputStream stream = new BufferedInputStream(checkpoints);
        CheckpointManager manager = new CheckpointManager(params, stream);
        ArrayList<StoredBlock> checkpointsBefore = manager.getCheckpointsBefore(timeSecs);
        for(int i = 0; i < checkpointsBefore.size(); i++) {
            store.put(checkpointsBefore.get(i));
            if(i == checkpointsBefore.size()-1) {
                store.setChainHead(checkpointsBefore.get(i));
            }
        }
    }
}
