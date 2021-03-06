/*
 * Copyright 2013-2014 eBay Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kylinolap.job.hadoop.cube;

import java.io.IOException;

import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;

/**
 * @author ysong1
 * 
 */
public class RangeKeyDistributionMapper extends Mapper<Text, Text, Text, LongWritable> {

    private static final long ONE_MEGA_BYTES = 1L * 1024L * 1024L;

    private LongWritable outputValue = new LongWritable(0);

    private long bytesRead = 0;

    private Text lastKey;

    @Override
    public void map(Text key, Text value, Context context) throws IOException, InterruptedException {
        lastKey = key;

        int bytesLength = key.getLength() + value.getLength();
        bytesRead += bytesLength;

        if (bytesRead >= ONE_MEGA_BYTES) {
            outputValue.set(bytesRead);
            context.write(key, outputValue);

            // reset bytesRead
            bytesRead = 0;
        }

    }

    @Override
    protected void cleanup(Context context) throws IOException, InterruptedException {
        if (lastKey != null) {
            outputValue.set(bytesRead);
            context.write(lastKey, outputValue);
        }
    }

}
