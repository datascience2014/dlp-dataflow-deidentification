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

import com.google.privacy.dlp.v2.Table;
import com.google.privacy.dlp.v2.Value;
import org.apache.avro.Schema;
import org.apache.avro.file.DataFileReader;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.DatumReader;
import org.apache.beam.sdk.io.FileIO.ReadableFile;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.values.KV;
import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads all records in the given split (i.e. a group of avro data blocks) and then converts
 * those records to DLP table rows.
 */
public class ReadSplitDoFn extends DoFn<KV<String, ReadableFile>, KV<String, Table.Row>> {

    public static final Logger LOG = LoggerFactory.getLogger(ReadSplitDoFn.class);

    private final Integer keyRange;

    public ReadSplitDoFn(Integer keyRange) {
        this.keyRange = keyRange;
    }

    @ProcessElement
    public void processElement(ProcessContext c) throws IOException {
        String[] inputKey = c.element().getKey().split("~");
        String fileName = inputKey[0];
        long fromPosition = Long.parseLong(inputKey[1]);
        long toPosition = Long.parseLong(inputKey[2]);
        ReadableFile file = c.element().getValue();

        try (AvroUtil.AvroSeekableByteChannel channel = AvroUtil.getChannel(file)) {
            DatumReader<GenericRecord> datumReader = new GenericDatumReader<>();
            DataFileReader<GenericRecord> fileReader = new DataFileReader<>(channel, datumReader);
            GenericRecord record = new GenericData.Record(fileReader.getSchema());

            // Move to the beginning of the split
            fileReader.sync(fromPosition);

            // Loop through all records within the split
            while(fileReader.hasNext() && !fileReader.pastSync(toPosition)) {
                // Read next record
                fileReader.next(record);

                // Create a new DLP table row
                Table.Row.Builder rowBuilder = Table.Row.newBuilder();

                // Loop through all of the record's fields
                List<Schema.Field> fields = fileReader.getSchema().getFields();
                for (Schema.Field field : fields) {
                    Object value = record.get(field.name());
                    // Insert current record field's value into the DLP table row
                    if (value != null) {
                        rowBuilder.addValues(Value.newBuilder().setStringValue(value.toString()).build());
                    } else {
                        rowBuilder.addValues(Value.newBuilder().setStringValue("").build());
                    }
                }

                // Output the DLP table row
                String outputKey = String.format("%s~%d", fileName, new Random().nextInt(keyRange));
                c.outputWithTimestamp(KV.of(outputKey, rowBuilder.build()), Instant.now());
            }
        }
    }

}
