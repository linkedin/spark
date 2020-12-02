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

package org.apache.spark.shuffle

import java.io.File
import java.net.ConnectException
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService

import scala.collection.mutable.{ArrayBuffer, HashMap, HashSet, Queue}

import com.google.common.base.Throwables

import org.apache.spark.{ShuffleDependency, SparkConf, SparkEnv}
import org.apache.spark.annotation.Since
import org.apache.spark.internal.Logging
import org.apache.spark.internal.config._
import org.apache.spark.launcher.SparkLauncher
import org.apache.spark.network.buffer.{FileSegmentManagedBuffer, ManagedBuffer, NioManagedBuffer}
import org.apache.spark.network.netty.SparkTransportConf
import org.apache.spark.network.shuffle.BlockFetchingListener
import org.apache.spark.network.shuffle.ErrorHandler.BlockPushErrorHandler
import org.apache.spark.network.util.TransportConf
import org.apache.spark.shuffle.ShuffleBlockPusher._
import org.apache.spark.storage.{BlockId, BlockManagerId, ShufflePushBlockId}
import org.apache.spark.util.{ThreadUtils, Utils}

/**
 * Used for pushing shuffle blocks to remote shuffle services when push shuffle is enabled.
 * When push shuffle is enabled, it is created after the shuffle writer finishes writing the shuffle
 * file and initiates the block push process.
 *
 * @param conf spark configuration
 */
@Since("3.1.0")
private[spark] class ShuffleBlockPusher(
    conf: SparkConf) extends Logging {
  private[this] var maxBlockSizeToPush = 0L
  private[this] var maxBlockBatchSize = 0L
  private[this] var maxBytesInFlight = 0L
  private[this] var maxReqsInFlight = 0
  private[this] var maxBlocksInFlightPerAddress = 0
  private[this] var bytesInFlight = 0L
  private[this] var reqsInFlight = 0
  private[this] val numBlocksInFlightPerAddress = new HashMap[BlockManagerId, Int]()
  private[this] val deferredPushRequests = new HashMap[BlockManagerId, Queue[PushRequest]]()
  private[this] val pushRequests = new Queue[PushRequest]
  private[this] val errorHandler = createErrorHandler()
  // VisibleForTesting
  private[shuffle] val unreachableBlockMgrs = new HashSet[BlockManagerId]()
  private[this] var stopPushing = false

  initialize()

  private def initialize(): Unit = {
    maxBlockSizeToPush = conf.get(SHUFFLE_MAX_BLOCK_SIZE_TO_PUSH) * 1024
    maxBlockBatchSize = conf.get(SHUFFLE_MAX_BLOCK_BATCH_SIZE_FOR_PUSH) * 1024 * 1024
    maxBytesInFlight = conf.getSizeAsMb("spark.reducer.maxSizeInFlight", "48m") * 1024 * 1024
    maxReqsInFlight = conf.getInt("spark.reducer.maxReqsInFlight", Int.MaxValue)
    maxBlocksInFlightPerAddress = conf.get(REDUCER_MAX_BLOCKS_IN_FLIGHT_PER_ADDRESS)
  }

  // VisibleForTesting
  private[shuffle] def createErrorHandler(): BlockPushErrorHandler = {
    new BlockPushErrorHandler() {
      // For a connection exception against a particular host, we will stop pushing any
      // blocks to just that host and continue push blocks to other hosts. So, here push of
      // all blocks will only stop when it is "Too Late". Also see updateStateAndCheckIfPushMore.
      override def shouldRetryError(t: Throwable): Boolean = {
        // If the block is too late, there is no need to retry it
        !Throwables.getStackTraceAsString(t).contains(BlockPushErrorHandler.TOO_LATE_MESSAGE_SUFFIX)
      }
    }
  }

  /**
   * Initiates the block push.
   *
   * @param dataFile         mapper generated shuffle data file
   * @param partitionLengths array of shuffle block size so we can tell shuffle block
   * @param dep              shuffle dependency to get shuffle ID and the location of remote shuffle
   *                         services to push local shuffle blocks
   * @param partitionId      map index of the shuffle map task
   */
  private[shuffle] def initiateBlockPush(
      dataFile: File,
      partitionLengths: Array[Long],
      dep: ShuffleDependency[_, _, _],
      partitionId: Int): Unit = {
    val numPartitions = dep.partitioner.numPartitions
    val mergerLocs = dep.getMergerLocs.map(loc => BlockManagerId("", loc.host, loc.port))
    val transportConf = SparkTransportConf.fromSparkConf(conf, "shuffle")
    val requests = prepareBlockPushRequests(numPartitions, partitionId, dep.shuffleId, dataFile,
      partitionLengths, mergerLocs, transportConf)
    // Randomize the orders of the PushRequest, so different mappers pushing blocks at the same
    // time won't be pushing the same ranges of shuffle partitions.
    pushRequests ++= Utils.randomize(requests)

    submitTask(() => {
      pushUpToMax()
    })
  }

  /**
   * Triggers the push. It's a separate method for testing.
   * VisibleForTesting
   */
  protected def submitTask(task: Runnable): Unit = {
    if (BLOCK_PUSHER_POOL != null) {
      BLOCK_PUSHER_POOL.execute(task)
    }
  }

  /**
   * Since multiple netty client threads could potentially be calling pushUpToMax for the same
   * mapper, we synchronize access to this method so that only one thread can push blocks for
   * a given mapper. This helps to simplify access to the shared states. The down side of this
   * is that we could unnecessarily block other mappers' block pushes if all netty client threads
   * are occupied by block pushes from the same mapper.
   *
   * This code is similar to ShuffleBlockFetcherIterator#fetchUpToMaxBytes in how it throttles
   * the data transfer between shuffle client/server.
   */
  private def pushUpToMax(): Unit = synchronized {
    // Process any outstanding deferred push requests if possible.
    if (deferredPushRequests.nonEmpty) {
      for ((remoteAddress, defReqQueue) <- deferredPushRequests) {
        while (isRemoteBlockPushable(defReqQueue) &&
          !isRemoteAddressMaxedOut(remoteAddress, defReqQueue.front) && !stopPushing) {
          val request = defReqQueue.dequeue()
          logDebug(s"Processing deferred push request for $remoteAddress with "
            + s"${request.blocks.length} blocks")
          sendRequest(request)
          if (defReqQueue.isEmpty) {
            deferredPushRequests -= remoteAddress
          }
        }
      }
    }

    // Process any regular push requests if possible.
    while (isRemoteBlockPushable(pushRequests) && !stopPushing) {
      val request = pushRequests.dequeue()
      val remoteAddress = request.address
      if (isRemoteAddressMaxedOut(remoteAddress, request)) {
        logDebug(s"Deferring push request for $remoteAddress with ${request.blocks.size} blocks")
        val defReqQueue = deferredPushRequests.getOrElse(remoteAddress, new Queue[PushRequest]())
        defReqQueue.enqueue(request)
        deferredPushRequests(remoteAddress) = defReqQueue
      } else {
        sendRequest(request)
      }
    }

    def isRemoteBlockPushable(pushReqQueue: Queue[PushRequest]): Boolean = {
      pushReqQueue.nonEmpty &&
        (bytesInFlight == 0 ||
          (reqsInFlight + 1 <= maxReqsInFlight &&
            bytesInFlight + pushReqQueue.front.size <= maxBytesInFlight))
    }

    // Checks if sending a new push request will exceed the max no. of blocks being pushed to a
    // given remote address.
    def isRemoteAddressMaxedOut(remoteAddress: BlockManagerId, request: PushRequest): Boolean = {
      (numBlocksInFlightPerAddress.getOrElse(remoteAddress, 0)
        + request.blocks.size) > maxBlocksInFlightPerAddress
    }
  }

  /**
   * Push blocks to remote shuffle server. The callback listener will invoke #pushUpToMax again
   * to trigger pushing the next batch of blocks once some block transfer is done in the current
   * batch. This way, we decouple the map task from the block push process, since it is netty
   * client thread instead of task execution thread which takes care of majority of the block
   * pushes.
   */
  private def sendRequest(request: PushRequest): Unit = {
    bytesInFlight = bytesInFlight + request.size
    reqsInFlight = reqsInFlight + 1
    numBlocksInFlightPerAddress(request.address) = numBlocksInFlightPerAddress.getOrElseUpdate(
      request.address, 0) + request.blocks.length

    val sizeMap = request.blocks.map { case (blockId, size) => (blockId.toString, size) }.toMap
    val address = request.address
    val blockIds = request.blocks.map(_._1.toString)
    val remainingBlocks = new HashSet[String]() ++= blockIds

    val blockPushListener = new BlockFetchingListener {
      // Initiating a connection and pushing blocks to a remote shuffle service is always handled by
      // the block-push-threads. We should not initiate the connection creation in the
      // blockPushListener callbacks which are invoked by the netty eventloop because:
      // 1. TrasportClient.createConnection(...) blocks for connection to be established and it's
      // recommended to avoid any blocking operations in the eventloop;
      // 2. The actual connection creation is a task that gets added to the task queue of another
      // eventloop which could have eventloops eventually blocking each other.
      // Once the blockPushListener is notified of the block push success or failure, we
      // just delegate it to block-push-threads.
      def handleResult(result: PushResult): Unit = {
        submitTask(() => {
          if (updateStateAndCheckIfPushMore(
            sizeMap(result.blockId), address, remainingBlocks, result)) {
            pushUpToMax()
          }
        })
      }

      override def onBlockFetchSuccess(blockId: String, data: ManagedBuffer): Unit = {
        logTrace(s"Push for block $blockId to $address successful.")
        handleResult(PushResult(blockId, null))
      }

      override def onBlockFetchFailure(blockId: String, exception: Throwable): Unit = {
        // check the message or it's cause to see it needs to be logged.
        if (!errorHandler.shouldLogError(exception)) {
          logTrace(s"Pushing block $blockId to $address failed.", exception)
        } else {
          logWarning(s"Pushing block $blockId to $address failed.", exception)
        }
        handleResult(PushResult(blockId, exception))
      }
    }
    SparkEnv.get.blockManager.blockStoreClient.pushBlocks(
      address.host, address.port, blockIds.toArray,
      sliceReqBufferIntoBlockBuffers(request.reqBuffer, request.blocks.map(_._2)),
      blockPushListener)
  }

  /**
   * Given the ManagedBuffer representing all the continuous blocks inside the shuffle data file
   * for a PushRequest and an array of individual block sizes, load the buffer from disk into
   * memory and slice it into multiple smaller buffers representing each block.
   *
   * With nio ByteBuffer, the individual block buffers share data with the initial in memory
   * buffer loaded from disk. Thus only one copy of the block data is kept in memory.
   * @param reqBuffer A {{FileSegmentManagedBuffer}} representing all the continuous blocks in
   *                  the shuffle data file for a PushRequest
   * @param blockSizes Array of block sizes
   * @return Array of in memory buffer for each individual block
   */
  private def sliceReqBufferIntoBlockBuffers(
      reqBuffer: ManagedBuffer,
      blockSizes: Seq[Long]): Array[ManagedBuffer] = {
    if (blockSizes.size == 1) {
      Array(reqBuffer)
    } else {
      val inMemoryBuffer = reqBuffer.nioByteBuffer()
      val blockOffsets = new Array[Long](blockSizes.size)
      var offset = 0L
      for (index <- blockSizes.indices) {
        blockOffsets(index) = offset
        offset += blockSizes(index)
      }
      blockOffsets.zip(blockSizes).map {
        case (offset, size) =>
          new NioManagedBuffer(inMemoryBuffer.duplicate()
            .position(offset.toInt)
            .limit((offset + size).toInt).asInstanceOf[ByteBuffer].slice())
      }.toArray
    }
  }

  /**
   * Updates the stats and based on the previous push result decides whether to push more blocks
   * or stop.
   *
   * @param bytesPushed     number of bytes pushed.
   * @param address         address of the remote service
   * @param remainingBlocks remaining blocks
   * @param pushResult      result of the last push
   * @return true if more blocks should be pushed; false otherwise.
   */
  private def updateStateAndCheckIfPushMore(
      bytesPushed: Long,
      address: BlockManagerId,
      remainingBlocks: HashSet[String],
      pushResult: PushResult): Boolean = synchronized {
    remainingBlocks -= pushResult.blockId
    bytesInFlight = bytesInFlight - bytesPushed
    numBlocksInFlightPerAddress(address) = numBlocksInFlightPerAddress(address) - 1
    if (remainingBlocks.isEmpty) {
      reqsInFlight = reqsInFlight - 1
    }
    if (pushResult.failure != null && pushResult.failure.getCause != null &&
      pushResult.failure.getCause.isInstanceOf[ConnectException]) {
      // Remove all the blocks for this address just once because removing from pushRequests
      // is expensive. If there is a ConnectException for the first block, all the subsequent
      // blocks to that address will fail, so should avoid removing multiple times.
      if (!unreachableBlockMgrs.contains(address)) {
        var removed = 0
        unreachableBlockMgrs.add(address)
        removed += pushRequests.dequeueAll(req => req.address == address).length
        val droppedReq = deferredPushRequests.remove(address)
        if (droppedReq.isDefined) {
          removed += droppedReq.get.length
        }
        logWarning(s"Received a ConnectException from $address. " +
          s"Dropping $removed push-requests and " +
          s"not pushing any more blocks to this address.")
      }
    }
    if (pushResult.failure != null && !errorHandler.shouldRetryError(pushResult.failure)) {
      logDebug(s"Received after merge is finalized from $address. Not pushing any more blocks.")
      stopPushing = true
      return false
    } else {
      remainingBlocks.isEmpty && (pushRequests.nonEmpty || deferredPushRequests.nonEmpty)
    }
  }

  /**
   * Convert the shuffle data file of the current mapper into a list of PushRequest. Basically,
   * continuous blocks in the shuffle file are grouped into a single request to allow more
   * efficient read of the block data. Each mapper for a given shuffle will receive the same
   * list of BlockManagerIds as the target location to push the blocks to. All mappers in the
   * same shuffle will map shuffle partition ranges to individual target locations in a consistent
   * manner to make sure each target location receives shuffle blocks belonging to the same set
   * of partition ranges. 0-length blocks and blocks that are large enough will be skipped.
   *
   * @param numPartitions sumber of shuffle partitions in the shuffle file
   * @param partitionId map index of the current mapper
   * @param shuffleId shuffleId of current shuffle
   * @param dataFile shuffle data file
   * @param partitionLengths array of sizes of blocks in the shuffle data file
   * @param mergerLocs target locations to push blocks to
   * @param transportConf transportConf used to create FileSegmentManagedBuffer
   * @return List of the PushRequest, randomly shuffled.
   *
   * VisibleForTesting
   */
  private[shuffle] def prepareBlockPushRequests(
      numPartitions: Int,
      partitionId: Int,
      shuffleId: Int,
      dataFile: File,
      partitionLengths: Array[Long],
      mergerLocs: Seq[BlockManagerId],
      transportConf: TransportConf): Seq[PushRequest] = {
    var offset = 0L
    var currentReqSize = 0L
    var currentReqOffset = 0L
    var currentMergerId = 0
    val numMergers = mergerLocs.length
    val requests = new ArrayBuffer[PushRequest]
    var blocks = new ArrayBuffer[(BlockId, Long)]
    for (reduceId <- 0 until numPartitions) {
      val blockSize = partitionLengths(reduceId)
      logDebug(
        s"Block ${ShufflePushBlockId(shuffleId, partitionId, reduceId)} is of size $blockSize")
      // Skip 0-length blocks and blocks that are large enough
      if (blockSize > 0) {
        val mergerId = math.min(math.floor(reduceId * 1.0 / numPartitions * numMergers),
          numMergers - 1).asInstanceOf[Int]
        // Start a new PushRequest if the current request goes beyond the max batch size,
        // or the number of blocks in the current request goes beyond the limit per destination,
        // or the next block push location is for a different shuffle service, or the next block
        // exceeds the max block size to push limit. This guarantees that each PushRequest
        // represents continuous blocks in the shuffle file to be pushed to the same shuffle
        // service, and does not go beyond existing limitations.
        if (currentReqSize + blockSize <= maxBlockBatchSize
          && blocks.size < maxBlocksInFlightPerAddress
          && mergerId == currentMergerId && blockSize <= maxBlockSizeToPush) {
          // Add current block to current batch
          currentReqSize += blockSize
        } else {
          if (blocks.nonEmpty) {
            // Convert the previous batch into a PushRequest
            requests += PushRequest(mergerLocs(currentMergerId), blocks.toSeq,
              createRequestBuffer(transportConf, dataFile, currentReqOffset, currentReqSize))
          }
          // Start a new batch
          currentReqSize = 0
          // Set currentReqOffset to -1 so we are able to distinguish between the initial value
          // of currentReqOffset and when we are about to start a new batch
          currentReqOffset = -1
          currentMergerId = mergerId
          blocks = new ArrayBuffer[(BlockId, Long)]
        }
        // Only push blocks under the size limit
        if (blockSize <= maxBlockSizeToPush) {
          blocks += ((ShufflePushBlockId(shuffleId, partitionId, reduceId), blockSize))
          // Only update currentReqOffset if the current block is the first in the request
          if (currentReqOffset == -1) {
            currentReqOffset = offset
          }
          if (currentReqSize == 0) {
            currentReqSize += blockSize
          }
        }
      }
      offset += blockSize
    }
    // Add in the final request
    if (blocks.nonEmpty) {
      requests += PushRequest(mergerLocs(currentMergerId), blocks.toSeq,
        createRequestBuffer(transportConf, dataFile, currentReqOffset, currentReqSize))
    }
    requests.toSeq
  }

  // Visible for testing
  protected def createRequestBuffer(
      conf: TransportConf,
      dataFile: File,
      offset: Long,
      length: Long): ManagedBuffer = {
    new FileSegmentManagedBuffer(conf, dataFile, offset, length)
  }
}

private[spark] object ShuffleBlockPusher {

  /**
   * A request to push blocks to a remote shuffle service
   * @param address remote shuffle service location to push blocks to
   * @param blocks list of block IDs and their sizes
   * @param reqBuffer a chunk of data in the shuffle data file corresponding to the continuous
   *                  blocks represented in this request
   */
  private[spark] case class PushRequest(
    address: BlockManagerId,
    blocks: Seq[(BlockId, Long)],
    reqBuffer: ManagedBuffer) {
    val size = blocks.map(_._2).sum
  }

  /**
   * Result of the block push.
   * @param blockId blockId
   * @param failure exception if the push was unsuccessful; null otherwise;
   */
  private case class PushResult(blockId: String, failure: Throwable)

  private val BLOCK_PUSHER_POOL: ExecutorService = {
    val conf = SparkEnv.get.conf
    if (Utils.isPushBasedShuffleEnabled(conf)) {
      val numThreads = conf.get(SHUFFLE_NUM_PUSH_THREADS)
        .getOrElse(conf.getInt(SparkLauncher.EXECUTOR_CORES, 1))
      ThreadUtils.newDaemonFixedThreadPool(numThreads, "shuffle-block-push-thread")
    } else {
      null
    }
  }

  /**
   * Stop the shuffle pusher pool if it isn't null.
   */
  private[spark] def stop(): Unit = {
    if (BLOCK_PUSHER_POOL != null) {
      BLOCK_PUSHER_POOL.shutdown()
    }
  }
}
