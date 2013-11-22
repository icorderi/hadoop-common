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

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.EnumSet;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.ExtendedBlock;
import org.apache.hadoop.hdfs.protocol.HdfsConstants;
import org.apache.hadoop.hdfs.protocol.datatransfer.BlockConstructionStage;
import org.apache.hadoop.hdfs.protocol.datatransfer.DataTransferEncryptor;
import org.apache.hadoop.hdfs.protocol.datatransfer.DataTransferProtocol;
import org.apache.hadoop.hdfs.protocol.datatransfer.IOStreamPair;
import org.apache.hadoop.hdfs.protocol.datatransfer.Sender;
import org.apache.hadoop.hdfs.protocol.proto.DataTransferProtos.DNTransferAckProto;
import org.apache.hadoop.hdfs.protocol.proto.DataTransferProtos.Status;
import org.apache.hadoop.hdfs.protocolPB.PBHelper;
import org.apache.hadoop.hdfs.security.token.block.BlockTokenIdentifier;
import org.apache.hadoop.hdfs.security.token.block.BlockTokenSecretManager;
import org.apache.hadoop.hdfs.security.token.block.InvalidBlockTokenException;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants;
import org.apache.hadoop.hdfs.server.protocol.DatanodeRegistration;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.security.token.Token;

/**
 * Used for transferring a block of data.  This class
 * sends a piece of data to another DataNode.
 */
public class LegacyBlockTransferer implements BlockTransferer {
	
	public static final Log LOG = LogFactory.getLog(LegacyBlockTransferer.class);
	
    final DatanodeInfo[] targets;
    final ExtendedBlock b;
    final BlockConstructionStage stage;
    final private DatanodeRegistration bpReg;
    final String clientname;
    final CachingStrategy cachingStrategy;
	final DataNode datanode;

    /**
     * Connect to the first item in the target list.  Pass along the 
     * entire target list, the block, and the data.
     */
    public LegacyBlockTransferer(DataNode datanode, DatanodeInfo targets[], ExtendedBlock b, BlockConstructionStage stage,
        final String clientname) {
      if (DataTransferProtocol.LOG.isDebugEnabled()) {
        DataTransferProtocol.LOG.debug(getClass().getSimpleName() + ": "
            + b + " (numBytes=" + b.getNumBytes() + ")"
            + ", stage=" + stage
            + ", clientname=" + clientname
            + ", targests=" + Arrays.asList(targets));
      }
      this.datanode = datanode;
      this.targets = targets;
      this.b = b;
      this.stage = stage;
      BPOfferService bpos = this.datanode.blockPoolManager.get(b.getBlockPoolId());
      bpReg = bpos.bpRegistration;
      this.clientname = clientname;
      this.cachingStrategy =
          new CachingStrategy(true, this.datanode.getDnConf().readaheadLength);
    }

    /**
     * Do the deed, write the bytes
     */
    @Override
    public void run() {
      this.datanode.xmitsInProgress.getAndIncrement();
      Socket sock = null;
      DataOutputStream out = null;
      DataInputStream in = null;
      BlockSender blockSender = null;
      final boolean isClient = clientname.length() > 0;
      
      try {
        final String dnAddr = targets[0].getXferAddr(this.datanode.connectToDnViaHostname);
        InetSocketAddress curTarget = NetUtils.createSocketAddr(dnAddr);
        if (LOG.isDebugEnabled()) {
          LOG.debug("Connecting to datanode " + dnAddr);
        }
        sock = this.datanode.newSocket();
        NetUtils.connect(sock, curTarget, this.datanode.getDnConf().socketTimeout);
        sock.setSoTimeout(targets.length * this.datanode.getDnConf().socketTimeout);

        long writeTimeout = this.datanode.getDnConf().socketWriteTimeout + 
                            HdfsServerConstants.WRITE_TIMEOUT_EXTENSION * (targets.length-1);
        OutputStream unbufOut = NetUtils.getOutputStream(sock, writeTimeout);
        InputStream unbufIn = NetUtils.getInputStream(sock);
        if (this.datanode.getDnConf().encryptDataTransfer) {
          IOStreamPair encryptedStreams =
              DataTransferEncryptor.getEncryptedStreams(
                  unbufOut, unbufIn,
                  this.datanode.blockPoolTokenSecretManager.generateDataEncryptionKey(
                      b.getBlockPoolId()));
          unbufOut = encryptedStreams.out;
          unbufIn = encryptedStreams.in;
        }
        
        out = new DataOutputStream(new BufferedOutputStream(unbufOut,
            HdfsConstants.SMALL_BUFFER_SIZE));
        in = new DataInputStream(unbufIn);
        blockSender = new BlockSender(b, 0, b.getNumBytes(), 
            false, false, true, this.datanode, null, cachingStrategy);
        DatanodeInfo srcNode = new DatanodeInfo(bpReg);

        //
        // Header info
        //
        Token<BlockTokenIdentifier> accessToken = BlockTokenSecretManager.DUMMY_TOKEN;
        if (this.datanode.isBlockTokenEnabled) {
          accessToken = this.datanode.blockPoolTokenSecretManager.generateToken(b, 
              EnumSet.of(BlockTokenSecretManager.AccessMode.WRITE));
        }

        new Sender(out).writeBlock(b, accessToken, clientname, targets, srcNode,
            stage, 0, 0, 0, 0, blockSender.getChecksum(), cachingStrategy);

        // send data & checksum
        blockSender.sendBlock(out, unbufOut, null);

        // no response necessary
        LOG.info(getClass().getSimpleName() + ": Transmitted " + b
            + " (numBytes=" + b.getNumBytes() + ") to " + curTarget);

        // read ack
        if (isClient) {
          DNTransferAckProto closeAck = DNTransferAckProto.parseFrom(
              PBHelper.vintPrefixed(in));
          if (LOG.isDebugEnabled()) {
            LOG.debug(getClass().getSimpleName() + ": close-ack=" + closeAck);
          }
          if (closeAck.getStatus() != Status.SUCCESS) {
            if (closeAck.getStatus() == Status.ERROR_ACCESS_TOKEN) {
              throw new InvalidBlockTokenException(
                  "Got access token error for connect ack, targets="
                   + Arrays.asList(targets));
            } else {
              throw new IOException("Bad connect ack, targets="
                  + Arrays.asList(targets));
            }
          }
        }
      } catch (IOException ie) {
        LOG.warn(bpReg + ":Failed to transfer " + b + " to " +
            targets[0] + " got ", ie);
          // check if there are any disk problem
        try{
          this.datanode.checkDiskError(ie);
        } catch(IOException e) {
            LOG.warn("DataNode.checkDiskError failed in run() with: ", e);
        }
        
      } finally {
        this.datanode.xmitsInProgress.getAndDecrement();
        IOUtils.closeStream(blockSender);
        IOUtils.closeStream(out);
        IOUtils.closeStream(in);
        IOUtils.closeSocket(sock);
      }
    }
  }
