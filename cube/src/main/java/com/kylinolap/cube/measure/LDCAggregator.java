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

package com.kylinolap.cube.measure;

import org.apache.hadoop.io.LongWritable;

/**
 * Long Distinct Count
 * 
 * @author xjiang
 * 
 */
public class LDCAggregator extends MeasureAggregator<LongWritable> {

    private HLLCAggregator hllAgg = null;
    private LongWritable state = new LongWritable(0);

    @SuppressWarnings("rawtypes")
    public void setDependentAggregator(MeasureAggregator agg) {
        this.hllAgg = (HLLCAggregator) agg;
    }

    @Override
    public void reset() {
    }

    @Override
    public void aggregate(LongWritable value) {
        if (state.get() == 0)
            state.set(value.get());
        else
            throw new IllegalStateException();
    }

    @Override
    public LongWritable getState() {
        if (hllAgg != null) {
            state.set(hllAgg.getState().getCountEstimate());
        }
        return state;
    }

    @Override
    public int getMemBytes() {
        return guessLongMemBytes();
    }

}
