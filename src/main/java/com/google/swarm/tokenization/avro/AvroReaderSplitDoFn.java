/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.swarm.tokenization.avro;

import java.io.IOException;
import java.util.List;
import java.util.Random;

import static org.apache.avro.file.DataFileConstants.SYNC_SIZE;
import com.google.privacy.dlp.v2.Table;
import com.google.privacy.dlp.v2.Value;
import org.apache.avro.file.DataFileReader;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.beam.sdk.io.FileIO.ReadableFile;
import org.apache.beam.sdk.io.range.OffsetRange;
import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.splittabledofn.OffsetRangeTracker;
import org.apache.beam.sdk.transforms.splittabledofn.RestrictionTracker;
import org.apache.beam.sdk.values.KV;
import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A SplitDoFn that splits the given Avro file into chunks. Then reads chunks in parallel and outputs a DLP row
 * for each Avro record ingested.
 */
public class AvroReaderSplitDoFn extends DoFn<KV<String, ReadableFile>, KV<String, Table.Row>> {

    public static final Logger LOG = LoggerFactory.getLogger(AvroReaderSplitDoFn.class);
    private final Counter numberOfAvroRecordsIngested =
        Metrics.counter(AvroReaderSplitDoFn.class, "numberOfAvroRecordsIngested");
    private final Integer splitSize;
    private final Integer keyRange;

    public AvroReaderSplitDoFn(Integer keyRange, Integer splitSize) {
        this.keyRange = keyRange;
        this.splitSize = splitSize;
    }

    @ProcessElement
    public void processElement(ProcessContext c, RestrictionTracker<OffsetRange, Long> tracker) throws IOException {
        LOG.info("Processing split from {} to {}", tracker.currentRestriction().getFrom(), tracker.currentRestriction().getTo());
        String fileName = c.element().getKey();

        AvroUtil.AvroSeekableByteChannel channel = AvroUtil.getChannel(c.element().getValue());
        DataFileReader<GenericRecord> fileReader = new DataFileReader<>(channel, new GenericDatumReader<>());
        GenericRecord record = new GenericData.Record(fileReader.getSchema());

        long start = tracker.currentRestriction().getFrom();
        long end = tracker.currentRestriction().getTo();

        // Move the first sync point after the
        fileReader.sync(Math.max(start - SYNC_SIZE, 0));

        // Claim the whole split's range
        if (tracker.tryClaim(end - 1)) {
            // Loop through all records in the split. More precisely from the first
            // sync point after the range's start position until the first sync point
            // after the range's end position.
            while(fileReader.hasNext() && !fileReader.pastSync(end - SYNC_SIZE)) {
                // Read the next Avro record in line
                fileReader.next(record);

                // Convert the Avro record to a DLP table row
                Table.Row.Builder rowBuilder = Table.Row.newBuilder();
                AvroUtil.getFlattenedValues(record, (Object value) -> {
                    if (value == null) {
                        rowBuilder.addValues(Value.newBuilder().setStringValue("").build());
                    } else {
                        rowBuilder.addValues(Value.newBuilder().setStringValue(value.toString()).build());
                    }
                });

                // Output the DLP table row
                String outputKey = String.format("%s~%d", fileName, new Random().nextInt(keyRange));
                c.outputWithTimestamp(KV.of(outputKey, rowBuilder.build()), Instant.now());
                numberOfAvroRecordsIngested.inc();
            }
        }

        fileReader.close();
        channel.close();
    }

    @GetInitialRestriction
    public OffsetRange getInitialRestriction(@Element KV<String, ReadableFile> element)
        throws IOException {
        long totalBytes = element.getValue().getMetadata().sizeBytes();
        LOG.info("Initial Restriction range from {} to {}", 0, totalBytes);
        return new OffsetRange(0, totalBytes);
    }

    @SplitRestriction
    public void splitRestriction(
        @Element KV<String, ReadableFile> file,
        @Restriction OffsetRange range,
        OutputReceiver<OffsetRange> out) {
        List<OffsetRange> splits = range.split(splitSize, splitSize);
        LOG.info("Number of splits: {}", splits.size());
        for (final OffsetRange p : splits) {
            out.output(p);
        }
    }

    @NewTracker
    public OffsetRangeTracker newTracker(@Restriction OffsetRange range) {
        return new OffsetRangeTracker(new OffsetRange(range.getFrom(), range.getTo()));
    }

}
