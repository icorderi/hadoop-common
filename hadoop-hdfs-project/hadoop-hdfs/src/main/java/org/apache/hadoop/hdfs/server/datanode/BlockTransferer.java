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
