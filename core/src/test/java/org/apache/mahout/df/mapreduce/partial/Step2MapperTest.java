/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.mahout.df.mapreduce.partial;

import static org.apache.mahout.df.data.Utils.double2String;
import static org.apache.mahout.df.data.Utils.randomDescriptor;
import static org.apache.mahout.df.data.Utils.randomDoubles;

import java.util.Random;

import junit.framework.TestCase;

import org.apache.commons.lang.ArrayUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.mahout.df.data.DataLoader;
import org.apache.mahout.df.data.Dataset;
import org.apache.mahout.df.data.Utils;
import org.apache.mahout.df.mapreduce.partial.InterResults;
import org.apache.mahout.df.mapreduce.partial.TreeID;
import org.apache.mahout.df.node.Leaf;
import org.apache.mahout.df.node.Node;

public class Step2MapperTest extends TestCase {

  /**
   * Special Step2Mapper that can be configured without using a Configuration
   * 
   */
  private static class MockStep2Mapper extends Step2Mapper {
    public MockStep2Mapper(int partition, Dataset dataset, TreeID[] keys,
        Node[] trees, int numInstances) {
      configure(partition, dataset, keys, trees, numInstances);
    }

  }

  /** nb attributes per generated data instance */
  protected final int nbAttributes = 4;

  /** nb generated data instances */
  protected final int nbInstances = 100;

  /** nb trees to build */
  protected final int nbTrees = 11;

  /** nb mappers to use */
  protected final int nbMappers = 5;

  @SuppressWarnings("unchecked")
  public void testMapper() throws Exception {
    Random rng = new Random();

    // prepare the data
    String descriptor = randomDescriptor(rng, nbAttributes);
    double[][] source = randomDoubles(rng, descriptor, nbInstances);
    String[] sData = double2String(source);
    Dataset dataset = DataLoader.generateDataset(descriptor, sData);
    String[][] splits = Utils.splitData(sData, nbMappers);

    // prepare first step output
    TreeID[] keys = new TreeID[nbTrees];
    Node[] trees = new Node[nbTrees];
    int[] sizes = new int[nbMappers];

    int treeIndex = 0;
    for (int partition = 0; partition < nbMappers; partition++) {
      int nbMapTrees = Step1Mapper.nbTrees(nbMappers, nbTrees, partition);

      for (int tree = 0; tree < nbMapTrees; tree++, treeIndex++) {
        keys[treeIndex] = new TreeID(partition, treeIndex);
        // put the partition in the leaf's label
        // this way we can track the outputs
        trees[treeIndex] = new Leaf(partition);
      }

      sizes[partition] = splits[partition].length;
    }

    // store the first step outputs in a file
    FileSystem fs = FileSystem.getLocal(new Configuration());
    Path forestPath = new Path("testdata/Step2MapperTest.forest");
    InterResults.store(fs, forestPath, keys, trees, sizes);

    LongWritable key = new LongWritable();
    Text value = new Text();

    for (int partition = 0; partition < nbMappers; partition++) {
      String[] split = splits[partition];

      // number of trees that will be handled by the mapper
      int nbConcerned = Step2Mapper.nbConcerned(nbMappers, nbTrees, partition);

      MockContext context = new MockContext(new Step2Mapper(),
          new Configuration(), new TaskAttemptID(), nbConcerned);

      // load the current mapper's (key, tree) pairs
      TreeID[] curKeys = new TreeID[nbConcerned];
      Node[] curTrees = new Node[nbConcerned];
      InterResults.load(fs, forestPath, nbMappers, nbTrees, partition, curKeys,
          curTrees);

      // simulate the job
      MockStep2Mapper mapper = new MockStep2Mapper(partition, dataset, curKeys,
          curTrees, split.length);

      for (int index = 0; index < split.length; index++) {
        key.set(index);
        value.set(split[index]);
        mapper.map(key, value, context);
      }

      mapper.cleanup(context);

      // make sure the mapper did not return its own trees
      assertEquals(nbConcerned, context.nbOutputs());

      // check the returned results
      int current = 0;
      for (int index = 0; index < nbTrees; index++) {
        if (keys[index].partition() == partition) {
          // should not be part of the results
          continue;
        }

        TreeID k = context.keys[current];

        // the tree should receive the partition's index
        assertEquals(partition, k.partition());

        // make sure all the trees of the other partitions are handled in the
        // correct order
        assertEquals(index, k.treeId());

        int[] predictions = context.values[current].getPredictions();

        // all the instances of the partition should be classified
        assertEquals(split.length, predictions.length);
        assertEquals(
            "at least one instance of the partition was not classified", -1,
            ArrayUtils.indexOf(predictions, -1));

        // the tree must not belong to the mapper's partition
        int treePartition = predictions[0];
        assertFalse("Step2Mapper returned a tree from its own partition",
            partition == treePartition);

        current++;
      }
    }
  }
}