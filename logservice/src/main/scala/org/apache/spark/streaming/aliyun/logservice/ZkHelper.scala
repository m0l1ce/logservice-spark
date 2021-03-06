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
package org.apache.spark.streaming.aliyun.logservice

import java.nio.charset.StandardCharsets
import java.util

import org.I0Itec.zkclient.ZkClient
import org.I0Itec.zkclient.exception.{ZkNoNodeException, ZkNodeExistsException}
import org.I0Itec.zkclient.serialize.ZkSerializer
import org.apache.spark.internal.Logging

import scala.collection.JavaConversions._


class ZkHelper(zkParams: Map[String, String],
               checkpointDir: String,
               project: String,
               logstore: String) extends Logging {

  private val zkDir = s"$checkpointDir/commit/$project/$logstore"

  @transient private var zkClient: ZkClient = _

  def initialize(): Unit = synchronized {
    if (zkClient != null) {
      // already initialized
      return
    }
    val zkConnect = zkParams.getOrElse("zookeeper.connect", "localhost:2181")
    val zkSessionTimeoutMs = zkParams.getOrElse("zookeeper.session.timeout.ms", "6000").toInt
    val zkConnectionTimeoutMs =
      zkParams.getOrElse("zookeeper.connection.timeout.ms", zkSessionTimeoutMs.toString).toInt
    logInfo(s"zkDir = $zkDir")

    zkClient = new ZkClient(zkConnect, zkSessionTimeoutMs, zkConnectionTimeoutMs)
    zkClient.setZkSerializer(new ZkSerializer() {
      override def serialize(data: scala.Any): Array[Byte] = {
        data.asInstanceOf[String].getBytes(StandardCharsets.UTF_8)
      }

      override def deserialize(bytes: Array[Byte]): AnyRef = {
        if (bytes == null) {
          return null
        }
        new String(bytes, StandardCharsets.UTF_8)
      }
    })
  }

  def mkdir(): Unit = {
    initialize()
    try {
      // Check if zookeeper is usable. Direct loghub api depends on zookeeper.
      if (!zkClient.exists(zkDir)) {
        zkClient.createPersistent(zkDir, true)
        return
      }
    } catch {
      case e: Exception =>
        throw new RuntimeException("Loghub direct api depends on zookeeper. Make sure that " +
          "zookeeper is on active service.", e)
    }
    try {
      zkClient.getChildren(zkDir).foreach(child => {
        zkClient.delete(s"$zkDir/$child")
      })
    } catch {
      case _: ZkNoNodeException =>
        logDebug("If this is the first time to run, it is fine to not find any commit data in " +
          "zookeeper.")
    }
  }

  def readOffset(shardId: Int): String = {
    initialize()
    zkClient.readData(s"$zkDir/$shardId.shard")
  }

  def saveOffset(shard: Int, cursor: String): Unit = {
    initialize()
    val cursorFile = s"$zkDir/$shard.shard"
    logDebug(s"Save $cursor to $cursorFile")
    if (!zkClient.exists(cursorFile)) {
      zkClient.createPersistent(cursorFile, true)
    }
    zkClient.writeData(cursorFile, cursor)
  }

  def tryLock(shard: Int): Boolean = {
    initialize()
    val lockFile = s"$zkDir/$shard.lock"
    try {
      zkClient.createPersistent(lockFile, false)
      true
    } catch {
      case _: ZkNodeExistsException =>
        logWarning(s"$shard already locked")
        false
    }
  }

  def unlock(shard: Int): Unit = {
    initialize()
    try {
      zkClient.delete(s"$zkDir/$shard.lock")
    } catch {
      case _: ZkNoNodeException =>
      // ignore
    }
  }

  def close(): Unit = synchronized {
    if (zkClient != null) {
      zkClient.close()
      zkClient = null
    }
  }
}

object ZkHelper extends Logging {

  private case class CacheKey(zkParams: Map[String, String],
                              checkpointDir: String,
                              project: String,
                              logstore: String)

  private var cache: util.HashMap[CacheKey, ZkHelper] = _

  def getOrCreate(zkParams: Map[String, String],
                  checkpointDir: String,
                  project: String,
                  logstore: String): ZkHelper = synchronized {
    if (cache == null) {
      cache = new util.HashMap[CacheKey, ZkHelper]()
    }
    val k = CacheKey(zkParams, checkpointDir, project, logstore)
    var zkHelper = cache.get(k)
    if (zkHelper == null) {
      zkHelper = new ZkHelper(zkParams, checkpointDir, project, logstore)
      zkHelper.initialize()
      cache.put(k, zkHelper)
    }
    zkHelper
  }
}