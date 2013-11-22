/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.server.datanode;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.ExtendedBlock;
import org.apache.hadoop.hdfs.protocol.datatransfer.BlockConstructionStage;
import org.apache.hadoop.util.ReflectionUtils;

public interface BlockTransferer extends Runnable {
	   		/**
		   * A factory for creating {@link BlockTransferer} objects.
		   */
		  public static abstract class Factory<D extends BlockTransferer> {
		    /** @return the configured factory. */
		    public static Factory<?> getFactory(Configuration conf) {
		      @SuppressWarnings("rawtypes")
		      final Class<? extends Factory> clazz = conf.getClass(
		          DFSConfigKeys.DFS_DATANODE_BLOCKTRANSFERER_FACTORY_KEY,
		          DefaultBlockTransfererFactory.class,
		          Factory.class);
		      return ReflectionUtils.newInstance(clazz, conf);
		    }

		    /** Create a new object. */
		    public abstract D newInstance(DataNode datanode, DatanodeInfo targets[], ExtendedBlock b, BlockConstructionStage stage,
			        final String clientname);
		  }
}
