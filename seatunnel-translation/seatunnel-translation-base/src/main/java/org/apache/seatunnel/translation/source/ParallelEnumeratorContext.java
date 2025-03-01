/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.seatunnel.translation.source;

import org.apache.seatunnel.api.source.SourceEvent;
import org.apache.seatunnel.api.source.SourceSplit;
import org.apache.seatunnel.api.source.SourceSplitEnumerator;

import java.util.Collections;
import java.util.List;
import java.util.Set;

public class ParallelEnumeratorContext<SplitT extends SourceSplit> implements SourceSplitEnumerator.Context<SplitT> {

    protected final ParallelSource<?, SplitT, ?> parallelSource;
    protected final Integer parallelism;
    protected final Integer subtaskId;
    protected volatile boolean running = false;

    public ParallelEnumeratorContext(ParallelSource<?, SplitT, ?> parallelSource,
                                     int parallelism,
                                     int subtaskId) {
        this.parallelSource = parallelSource;
        this.parallelism = parallelism;
        this.subtaskId = subtaskId;
    }

    @Override
    public int currentParallelism() {
        return running ? parallelism : 0;
    }

    @Override
    public Set<Integer> registeredReaders() {
        return running ? Collections.singleton(subtaskId) : Collections.emptySet();
    }

    public void register() {
        running = true;
    }

    @Override
    public void assignSplit(int subtaskId, List<SplitT> splits) {
        if (this.subtaskId == subtaskId) {
            parallelSource.addSplits(splits);
        }
    }

    @Override
    public void signalNoMoreSplits(int subtaskId) {
        if (this.subtaskId == subtaskId) {
            parallelSource.handleNoMoreSplits();
        }
    }

    @Override
    public void sendEventToSourceReader(int subtaskId, SourceEvent event) {
        // TODO: exception
        throw new RuntimeException("");
    }

}
