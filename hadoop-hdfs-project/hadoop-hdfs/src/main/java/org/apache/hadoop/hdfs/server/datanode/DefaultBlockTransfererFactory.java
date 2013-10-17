package org.apache.hadoop.hdfs.server.datanode;

import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.ExtendedBlock;
import org.apache.hadoop.hdfs.protocol.datatransfer.BlockConstructionStage;


/**
 * A factory for creating {@link BlockTransferer} objects.
 */
public class DefaultBlockTransfererFactory extends BlockTransferer.Factory<BlockTransferer> {

	  @Override
	  public BlockTransferer newInstance(DataNode datanode, DatanodeInfo targets[], ExtendedBlock b, BlockConstructionStage stage,
		        final String clientname) {
	    return new LegacyBlockTransferer(datanode, targets, b, stage, clientname);
	  }
}
