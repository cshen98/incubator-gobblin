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

package org.apache.gobblin.runtime.mapreduce;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Singular;
import lombok.extern.slf4j.Slf4j;

import org.apache.gobblin.configuration.ConfigurationKeys;
import org.apache.gobblin.runtime.AbstractJobLauncher;
import org.apache.gobblin.source.workunit.MultiWorkUnit;
import org.apache.gobblin.source.workunit.WorkUnit;
import org.apache.gobblin.util.SerializationUtils;


/**
 * An input format for reading Gobblin inputs (work unit and multi work unit files).
 */
@Slf4j
public class GobblinWorkUnitsInputFormat extends InputFormat<LongWritable, Text> {

  private static final String MAX_MAPPERS = GobblinWorkUnitsInputFormat.class.getName() + ".maxMappers";

  private static final int MIN_LOCATION_NAMES = 3;
  private static final double LOCALITY_THRESHOLD = 0.75;

  /**
   * Set max mappers used in MR job.
   */
  public static void setMaxMappers(Job job, int maxMappers) {
    job.getConfiguration().setInt(MAX_MAPPERS, maxMappers);
  }

  public static int getMaxMapper(Configuration conf) {
    return conf.getInt(MAX_MAPPERS, Integer.MAX_VALUE);
  }

  @Override
  public List<InputSplit> getSplits(JobContext context)
      throws IOException, InterruptedException {

    Path[] inputPaths = FileInputFormat.getInputPaths(context);
    if (inputPaths == null || inputPaths.length == 0) {
      throw new IOException("No input found!");
    }

    List<String> allPaths = Lists.newArrayList();
    Map<String, Collection<String>> blockLocationNamesForWorkUnitPaths = Maps.newHashMap();

    for (Path path : inputPaths) {
      // path is a single work unit / multi work unit
      FileSystem fs = path.getFileSystem(context.getConfiguration());
      FileStatus[] inputs = fs.listStatus(path);

      if (inputs == null) {
        throw new IOException(String.format("Path %s does not exist.", path));
      }

      log.info(String.format("Found %d input files at %s: %s", inputs.length, path, Arrays.toString(inputs)));

      for (FileStatus input : inputs) {
        allPaths.add(input.getPath().toString());
        blockLocationNamesForWorkUnitPaths.put(input.getPath().toString(),
            getSplitHosts(fs, getFlattenedWorkUnitList(fs, input.getPath())));
      }
    }

    int maxMappers = getMaxMapper(context.getConfiguration());
    int numTasksPerMapper =
        allPaths.size() % maxMappers == 0 ? allPaths.size() / maxMappers : allPaths.size() / maxMappers + 1;

    List<InputSplit> splits = Lists.newArrayList();
    Iterator<String> pathsIt = allPaths.iterator();
    while (pathsIt.hasNext()) {
      Iterator<String> limitedIterator = Iterators.limit(pathsIt, numTasksPerMapper);

      List<String> splitPaths = Lists.newArrayList();
      List<String> splitBlockLocationNames = Lists.newArrayList();

      while (limitedIterator.hasNext()) {
        String path = limitedIterator.next();
        splitPaths.add(path);
        splitBlockLocationNames.addAll(blockLocationNamesForWorkUnitPaths.get(path));
      }

      splits.add(new GobblinSplit(splitPaths,
          splitBlockLocationNames.toArray(new String[splitBlockLocationNames.size()])));
    }

    return splits;
  }

  /**
   * Deserializes the work unit file from the {@link Path} and if it is a multi work unit file, flattens it into a list.
   * @param fs {@link FileSystem} where the work unit file is located
   * @param workUnitFile {@link Path} to the work unit file
   * @return list of {@link WorkUnit}s represented by the work unit file
   * @throws IOException
   */
  @VisibleForTesting
  List<WorkUnit> getFlattenedWorkUnitList(FileSystem fs, Path workUnitFile) throws IOException {
    if (fs == null || workUnitFile == null) {
      return Collections.emptyList();
    }

    WorkUnit tmpWorkUnit = (workUnitFile.toString().endsWith(AbstractJobLauncher.MULTI_WORK_UNIT_FILE_EXTENSION) ?
        MultiWorkUnit.createEmpty() : WorkUnit.createEmpty());
    SerializationUtils.deserializeState(fs, workUnitFile, tmpWorkUnit);

    List<WorkUnit> workUnits;
    if (tmpWorkUnit instanceof MultiWorkUnit) {
      workUnits = ((MultiWorkUnit) tmpWorkUnit).getWorkUnits();
    } else {
      workUnits = Lists.newArrayList(tmpWorkUnit);
    }
    return workUnits;
  }

  /**
   * To allow for data locality in the MR framework, the InputSplits created need to provide a String[] of block
   * location names in the getLocations method. This can be done by passing in the locations when creating the split.
   * This method calculates the block location names to use from the list of {@link WorkUnit}s to be processed by a split.
   * We identify and return a collection containing the top block location names that contribute the most to the split.
   * @param fs {@link FileSystem} where the files to be read by the work units in workUnits are located.
   * @param workUnits {@link WorkUnit}s to be processed by a split
   * @return Collection of location names to be used to create the split for the list of work units passed in.
   * @throws IOException
   */
  @VisibleForTesting
  Collection<String> getSplitHosts(FileSystem fs, List<WorkUnit> workUnits) throws IOException {
    long totalWorkUnitLength = 0L;
    HashMap<String, Long> lengthsForBlkLocationNames = Maps.newHashMap();

    for (WorkUnit workUnit : workUnits) {
      if (!workUnit.contains(ConfigurationKeys.GOBBLIN_SPLIT_FILE_PATH)) {
        log.warn(String.format("Skipping block location retrieval for work unit with missing split file path - %s",
            workUnit.toString()));
        continue;
      }

      Path filePath = new Path(workUnit.getProp(ConfigurationKeys.GOBBLIN_SPLIT_FILE_PATH));
      long splitOffset = workUnit.getPropAsLong(ConfigurationKeys.GOBBLIN_SPLIT_FILE_LOW_POSITION, 0);
      long splitLength = (workUnit.contains(ConfigurationKeys.GOBBLIN_SPLIT_FILE_HIGH_POSITION) ?
          workUnit.getPropAsLong(ConfigurationKeys.GOBBLIN_SPLIT_FILE_HIGH_POSITION) :
          fs.getFileStatus(filePath).getLen()) - splitOffset;

      totalWorkUnitLength += splitLength;
      BlockLocation[] blkLocations = fs.getFileBlockLocations(filePath, splitOffset, splitLength);

      for (int i = 0; i < blkLocations.length; ++i) {
        long bytesInThisBlock = blkLocations[i].getLength();
        if (i == 0) {
          bytesInThisBlock += blkLocations[i].getOffset() - splitOffset;
        } else if (i == blkLocations.length - 1) {
          bytesInThisBlock = splitOffset + splitLength - blkLocations[i].getOffset();
        }

        for (String blkLocationName : blkLocations[i].getHosts()) {
          lengthsForBlkLocationNames.put(blkLocationName,
              lengthsForBlkLocationNames.getOrDefault(blkLocationName, 0L) + bytesInThisBlock);
        }
      }
    }

    List<Map.Entry<String, Long>> blkLocationNamesOrderedByLength =
        Lists.newArrayList(lengthsForBlkLocationNames.entrySet());
    blkLocationNamesOrderedByLength.sort((e1, e2) -> {
      long test = e2.getValue() - e1.getValue();
      return (test != 0 ? (test > 0 ? 1 : -1) : 0);
    });

    long minLength = Long.MAX_VALUE;
    int endIdx = 0;

    while (endIdx < blkLocationNamesOrderedByLength.size() && (endIdx < MIN_LOCATION_NAMES ||
        blkLocationNamesOrderedByLength.get(endIdx).getValue() == minLength ||
        (double) blkLocationNamesOrderedByLength.get(endIdx).getValue() / totalWorkUnitLength >= LOCALITY_THRESHOLD)) {
      minLength = blkLocationNamesOrderedByLength.get(endIdx).getValue();
      endIdx += 1;
    }

    return blkLocationNamesOrderedByLength.subList(0, endIdx).stream()
        .map(Map.Entry::getKey).collect(Collectors.toList());
  }

  @Override
  public RecordReader<LongWritable, Text> createRecordReader(InputSplit split, TaskAttemptContext context)
      throws IOException, InterruptedException {
    return new GobblinRecordReader((GobblinSplit) split);
  }

  /**
   * {@link InputSplit} that just contain the work unit / multi work unit files that each mapper should process.
   */
  @AllArgsConstructor
  @NoArgsConstructor
  @Builder
  @EqualsAndHashCode
  public static class GobblinSplit extends InputSplit implements Writable {

    /**
     * A list of {@link Path}s containing work unit / multi work unit.
     */
    @Getter
    @Singular
    private List<String> paths;

    /**
     * Locations (names of nodes) of blocks of files specified in the split's work unit files defined by paths
     */
    private String[] blockLocationNames;

    @Override
    public void write(DataOutput out)
        throws IOException {
      out.writeInt(this.paths.size());
      for (String path : this.paths) {
        out.writeUTF(path);
      }
      out.writeInt(this.blockLocationNames.length);
      for (String blockLocationName : this.blockLocationNames) {
        out.writeUTF(blockLocationName);
      }
    }

    @Override
    public void readFields(DataInput in)
        throws IOException {
      int numPaths = in.readInt();
      this.paths = Lists.newArrayListWithExpectedSize(numPaths);
      for (int i = 0; i < numPaths; i++) {
        this.paths.add(in.readUTF());
      }
      int numBlockLocationNames = in.readInt();
      this.blockLocationNames = new String[numBlockLocationNames];
      for (int i = 0; i < numBlockLocationNames; ++i) {
        this.blockLocationNames[i] = in.readUTF();
      }
    }

    @Override
    public long getLength()
        throws IOException, InterruptedException {
      return 0;
    }

    @Override
    public String[] getLocations()
        throws IOException, InterruptedException {
      if (blockLocationNames == null) {
        return new String[0];
      } else {
        return blockLocationNames;
      }
    }
  }

  /**
   * Returns records containing the name of the work unit / multi work unit files to process.
   */
  public static class GobblinRecordReader extends RecordReader<LongWritable, Text> {
    private int currentIdx = -1;
    private final List<String> paths;
    private final int totalPaths;

    public GobblinRecordReader(GobblinSplit split) {
      this.paths = split.getPaths();
      this.totalPaths = this.paths.size();
    }

    @Override
    public void initialize(InputSplit split, TaskAttemptContext context)
        throws IOException, InterruptedException {
    }

    @Override
    public boolean nextKeyValue()
        throws IOException, InterruptedException {
      this.currentIdx++;
      return this.currentIdx < this.totalPaths;
    }

    @Override
    public LongWritable getCurrentKey()
        throws IOException, InterruptedException {
      return new LongWritable(this.currentIdx);
    }

    @Override
    public Text getCurrentValue()
        throws IOException, InterruptedException {
      return new Text(this.paths.get(this.currentIdx));
    }

    @Override
    public float getProgress()
        throws IOException, InterruptedException {
      return (float) this.currentIdx / (float) this.totalPaths;
    }

    @Override
    public void close()
        throws IOException {
    }
  }
}
