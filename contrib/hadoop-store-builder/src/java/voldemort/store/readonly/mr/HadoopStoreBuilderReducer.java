/*
 * Copyright 2008-2009 LinkedIn, Inc
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package voldemort.store.readonly.mr;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Iterator;

import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reducer;
import org.apache.hadoop.mapred.Reporter;
import org.apache.log4j.Logger;

import voldemort.VoldemortException;
import voldemort.store.readonly.ReadOnlyUtils;
import voldemort.store.readonly.checksum.CheckSum;
import voldemort.store.readonly.checksum.CheckSum.CheckSumType;
import voldemort.utils.ByteUtils;

/**
 * Take key md5s and value bytes and build a read-only store from these values
 */
@SuppressWarnings("deprecation")
public class HadoopStoreBuilderReducer extends AbstractStoreBuilderConfigurable implements
        Reducer<BytesWritable, BytesWritable, Text, Text> {

    private static final Logger logger = Logger.getLogger(HadoopStoreBuilderReducer.class);

    private DataOutputStream indexFileStream = null;
    private DataOutputStream valueFileStream = null;
    private int position;
    private String taskId = null;

    private int nodeId = -1;
    private int partitionId = -1;
    private int chunkId = -1;
    private int replicaType = -1;

    private Path taskIndexFileName;
    private Path taskValueFileName;

    private JobConf conf;
    private CheckSumType checkSumType;
    private CheckSum checkSumDigestIndex;
    private CheckSum checkSumDigestValue;

    private String outputDir;

    private FileSystem fs;

    protected static enum CollisionCounter {
        NUM_COLLISIONS,
        MAX_COLLISIONS;
    }

    /**
     * Reduce should get sorted MD5 of Voldemort key ( either 16 bytes if saving
     * keys is disabled, else 8 bytes ) as key and for value (a) node-id,
     * partition-id, value - if saving keys is disabled (b) node-id,
     * partition-id, replica-type, [key-size, value-size, key, value]* if saving
     * keys is enabled
     */
    public void reduce(BytesWritable key,
                       Iterator<BytesWritable> iterator,
                       OutputCollector<Text, Text> output,
                       Reporter reporter) throws IOException {

        // Write key and position
        this.indexFileStream.write(key.get(), 0, key.getSize());
        this.indexFileStream.writeInt(this.position);

        // Run key through checksum digest
        if(this.checkSumDigestIndex != null) {
            this.checkSumDigestIndex.update(key.get(), 0, key.getSize());
            this.checkSumDigestIndex.update(this.position);
        }

        short numTuples = 0;
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        DataOutputStream valueStream = new DataOutputStream(stream);

        while(iterator.hasNext()) {
            BytesWritable writable = iterator.next();
            byte[] valueBytes = writable.get();
            int offsetTillNow = 0;

            // Read node Id
            if(this.nodeId == -1)
                this.nodeId = ByteUtils.readInt(valueBytes, offsetTillNow);
            offsetTillNow += ByteUtils.SIZE_OF_INT;

            // Read partition id
            if(this.partitionId == -1)
                this.partitionId = ByteUtils.readInt(valueBytes, offsetTillNow);
            offsetTillNow += ByteUtils.SIZE_OF_INT;

            // Read chunk id
            if(this.chunkId == -1)
                this.chunkId = ReadOnlyUtils.chunk(key.get(), getNumChunks());

            // Read replica type
            if(getSaveKeys()) {
                if(this.replicaType == -1)
                    this.replicaType = (int) ByteUtils.readBytes(valueBytes,
                                                                 offsetTillNow,
                                                                 ByteUtils.SIZE_OF_BYTE);
                offsetTillNow += ByteUtils.SIZE_OF_BYTE;
            }

            int valueLength = writable.getSize() - offsetTillNow;
            if(getSaveKeys()) {
                // Write ( key_length, value_length, key,
                // value )
                valueStream.write(valueBytes, offsetTillNow, valueLength);
            } else {
                // Write (value_length + value)
                valueStream.writeInt(valueLength);
                valueStream.write(valueBytes, offsetTillNow, valueLength);
            }

            numTuples++;

            // If we have multiple values for this md5 that is a collision,
            // throw an exception--either the data itself has duplicates, there
            // are trillions of keys, or someone is attempting something
            // malicious ( We obviously expect collisions when we save keys )
            if(!getSaveKeys() && numTuples > 1)
                throw new VoldemortException("Duplicate keys detected for md5 sum "
                                             + ByteUtils.toHexString(ByteUtils.copy(key.get(),
                                                                                    0,
                                                                                    key.getSize())));

        }

        if(numTuples < 0) {
            // Overflow
            throw new VoldemortException("Found too many collisions: chunk " + chunkId
                                         + " has exceeded " + Short.MAX_VALUE + " collisions.");
        } else if(numTuples > 1) {
            // Update number of collisions + max keys per collision
            reporter.incrCounter(CollisionCounter.NUM_COLLISIONS, 1);

            long numCollisions = reporter.getCounter(CollisionCounter.MAX_COLLISIONS).getCounter();
            if(numTuples > numCollisions) {
                reporter.incrCounter(CollisionCounter.MAX_COLLISIONS, numTuples - numCollisions);
            }
        }

        // Flush the value
        valueStream.flush();
        byte[] value = stream.toByteArray();

        // Start writing to file now
        // First, if save keys flag set the number of keys
        if(getSaveKeys()) {

            this.valueFileStream.writeShort(numTuples);
            this.position += ByteUtils.SIZE_OF_SHORT;

            if(this.checkSumDigestValue != null) {
                this.checkSumDigestValue.update(numTuples);
            }
        }

        this.valueFileStream.write(value);
        this.position += value.length;

        if(this.checkSumDigestValue != null) {
            this.checkSumDigestValue.update(value);
        }

        if(this.position < 0)
            throw new VoldemortException("Chunk overflow exception: chunk " + chunkId
                                         + " has exceeded " + Integer.MAX_VALUE + " bytes.");

    }

    @Override
    public void configure(JobConf job) {
        super.configure(job);
        try {
            this.conf = job;
            this.position = 0;
            this.outputDir = job.get("final.output.dir");
            this.taskId = job.get("mapred.task.id");
            this.checkSumType = CheckSum.fromString(job.get("checksum.type"));
            this.checkSumDigestIndex = CheckSum.getInstance(checkSumType);
            this.checkSumDigestValue = CheckSum.getInstance(checkSumType);

            this.taskIndexFileName = new Path(FileOutputFormat.getOutputPath(job), getStoreName()
                                                                                   + "."
                                                                                   + this.taskId
                                                                                   + ".index");
            this.taskValueFileName = new Path(FileOutputFormat.getOutputPath(job), getStoreName()
                                                                                   + "."
                                                                                   + this.taskId
                                                                                   + ".data");

            if(this.fs == null)
                this.fs = this.taskIndexFileName.getFileSystem(job);

            this.indexFileStream = fs.create(this.taskIndexFileName);
            this.valueFileStream = fs.create(this.taskValueFileName);

            logger.info("Opening " + this.taskIndexFileName + " and " + this.taskValueFileName
                        + " for writing.");

        } catch(IOException e) {
            throw new RuntimeException("Failed to open Input/OutputStream", e);
        }
    }

    @Override
    public void close() throws IOException {

        this.indexFileStream.close();
        this.valueFileStream.close();

        if(this.nodeId == -1 || this.chunkId == -1 || this.partitionId == -1) {
            // Issue 258 - No data was read in the reduce phase, do not create
            // any output
            return;
        }

        // If the replica type read was not valid, shout out
        if(getSaveKeys() && this.replicaType == -1) {
            throw new RuntimeException("Could not read the replica type correctly for node "
                                       + nodeId + " ( partition - " + this.partitionId + " )");
        }

        String fileNamePrefix = null;
        if(getSaveKeys()) {
            fileNamePrefix = new String(Integer.toString(this.partitionId) + "_"
                                        + Integer.toString(this.replicaType) + "_"
                                        + Integer.toString(this.chunkId));
        } else {
            fileNamePrefix = new String(Integer.toString(this.partitionId) + "_"
                                        + Integer.toString(this.chunkId));
        }

        // Initialize the node directory
        Path nodeDir = new Path(this.outputDir, "node-" + this.nodeId);

        // Create output directory, if it doesn't exist
        FileSystem outputFs = nodeDir.getFileSystem(this.conf);
        outputFs.mkdirs(nodeDir);

        // Write the checksum and output files
        if(this.checkSumType != CheckSumType.NONE) {

            if(this.checkSumDigestIndex != null && this.checkSumDigestValue != null) {
                Path checkSumIndexFile = new Path(nodeDir, fileNamePrefix + ".index.checksum");
                Path checkSumValueFile = new Path(nodeDir, fileNamePrefix + ".data.checksum");

                FSDataOutputStream output = outputFs.create(checkSumIndexFile);
                output.write(this.checkSumDigestIndex.getCheckSum());
                output.close();

                output = outputFs.create(checkSumValueFile);
                output.write(this.checkSumDigestValue.getCheckSum());
                output.close();
            } else {
                throw new RuntimeException("Failed to open checksum digest for node " + nodeId
                                           + " ( partition - " + this.partitionId + ", chunk - "
                                           + chunkId + " )");
            }
        }

        // Generate the final chunk files
        Path indexFile = new Path(nodeDir, fileNamePrefix + ".index");
        Path valueFile = new Path(nodeDir, fileNamePrefix + ".data");

        logger.info("Moving " + this.taskIndexFileName + " to " + indexFile);
        outputFs.rename(taskIndexFileName, indexFile);
        logger.info("Moving " + this.taskValueFileName + " to " + valueFile);
        outputFs.rename(this.taskValueFileName, valueFile);

    }
}
