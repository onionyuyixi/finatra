package com.twitter.finatra.kafkastreams.query

import com.twitter.conversions.DurationOps._
import com.twitter.finatra.kafkastreams.transformer.FinatraTransformer.{DateTimeMillis, WindowStartTime}
import com.twitter.finatra.kafkastreams.transformer.aggregation.TimeWindowed
import com.twitter.finatra.kafkastreams.transformer.domain.Time
import com.twitter.finatra.kafkastreams.transformer.stores.FinatraKeyValueStore
import com.twitter.finatra.kafkastreams.transformer.stores.internal.FinatraStoresGlobalManager
import com.twitter.finatra.streams.queryable.thrift.domain.ServiceShardId
import com.twitter.finatra.streams.queryable.thrift.partitioning.{KafkaPartitioner, StaticServiceShardPartitioner}
import com.twitter.inject.Logging
import com.twitter.util.Duration
import org.apache.kafka.common.serialization.{Serde, Serializer}
import org.apache.kafka.streams.errors.InvalidStateStoreException
import org.joda.time.DateTimeUtils
import scala.collection.JavaConverters._

object QueryableFinatraWindowStore {

  /**
   * Calculate the default range of a window query which can be used
   * when a query doesn't specify an explicit start and end time
   *
   * @param windowSize      Window size configuration
   * @param allowedLateness Window allowed lateness configuration
   * @param queryableAfterClose Window queryable after close configuration
   *
   * @return Duration in millis of how long the default query range should be
   */
  def defaultQueryRange(
    windowSize: Duration,
    allowedLateness: Duration,
    queryableAfterClose: Duration
  ): Duration = {
    val windowLifetime = windowSize + allowedLateness + queryableAfterClose
    (math.ceil(windowLifetime.inMillis.toDouble / windowSize.inMillis.toDouble).toLong * windowSize.inMillis).millis
  }

  /**
   * Calculate the default range of a window query which can be used
   * when the query doesn't specify an explicit start and end time
   *
   * @param windowSize              Window's size configuration
   * @param defaultQueryRange       Duration in millis of the query range (end - start)
   * @param queryParamStartTime     Query start time
   * @param queryParamEndTime       Query end time
   *
   * @return Start and end times to be queried snapped to window boundaries
   */
  def queryStartAndEndTime(
    windowSize: Duration,
    defaultQueryRange: Duration,
    queryParamStartTime: Option[DateTimeMillis],
    queryParamEndTime: Option[DateTimeMillis]
  ): (DateTimeMillis, DateTimeMillis) = {
    val endTime = queryParamEndTime.getOrElse(DateTimeUtils.currentTimeMillis)

    val windowSnappedEndTime = TimeWindowed.windowEnd(
        messageTime = Time(endTime),
        size = windowSize)

    val startTimeMillis = queryParamStartTime.getOrElse(windowSnappedEndTime.millis - defaultQueryRange.inMillis)

    val windowSnappedStartTime = TimeWindowed.windowStart(
      messageTime = Time(startTimeMillis),
      size = windowSize)

    (windowSnappedStartTime.millis, windowSnappedEndTime.millis)
  }
}

/**
 * A queryable Finatra window store for use by endpoints exposing queryable state
 */
class QueryableFinatraWindowStore[K, V](
  storeName: String,
  windowSize: Duration,
  allowedLateness: Duration,
  queryableAfterClose: Duration,
  keySerde: Serde[K],
  numShards: Int,
  numQueryablePartitions: Int,
  currentShardId: Int)
    extends Logging {

  private val keySerializer = keySerde.serializer()

  private val currentServiceShardId = ServiceShardId(currentShardId)

  private val partitioner = new KafkaPartitioner(
    StaticServiceShardPartitioner(numShards = numShards),
    numPartitions = numQueryablePartitions)

  private val defaultQueryRange = QueryableFinatraWindowStore
    .defaultQueryRange(
      windowSize,
      allowedLateness,
      queryableAfterClose)

  /**
   * Get the windowed values associated with this key and return them combined in a map. If the
   * key is non-local to this Kafka Streams instance, return an exception indicating
   * which instance is hosting this key
   *
   * @param key The key to fetch
   * @param startTime Optional start time of the range of windows to query
   * @param endTime Optional end time of the range of windows to query
   *
   * @return A map of windowed values for the key being queried
   *
   * @throws NullPointerException       If null is used for key.
   * @throws InvalidStateStoreException if the store is not initialized
   */
  @throws[InvalidStateStoreException]
  def get(
    key: K,
    startTime: Option[Long] = None,
    endTime: Option[Long] = None
  ): Map[WindowStartTime, V] = {
    throwIfNonLocalKey(key, keySerializer)

    val (startWindowRange, endWindowRange) = QueryableFinatraWindowStore.queryStartAndEndTime(windowSize, defaultQueryRange, startTime, endTime)

    val windowedMap = new java.util.TreeMap[DateTimeMillis, V]

    var currentWindowStart = Time(startWindowRange)
    while (currentWindowStart.millis <= endWindowRange) {
      val windowedKey = TimeWindowed.forSize(currentWindowStart, windowSize, key)

      //TODO: Use store.taskId to find exact store where the key is assigned
      for (store <- stores) {
        val result = store.get(windowedKey)
        if (result != null) {
          windowedMap.put(currentWindowStart.millis, result)
        }
      }

      currentWindowStart = currentWindowStart + windowSize
    }

    windowedMap.asScala.toMap
  }

  private def throwIfNonLocalKey(key: K, keySerializer: Serializer[K]): Unit = {
    val keyBytes = keySerializer.serialize("", key)
    val partitionsToQuery = partitioner.shardIds(keyBytes)
    if (partitionsToQuery.head != currentServiceShardId) {
      throw new Exception(s"Non local key. Query $partitionsToQuery")
    }
  }

  private def stores: Iterable[FinatraKeyValueStore[TimeWindowed[K], V]] = {
    FinatraStoresGlobalManager.getWindowedStores(storeName)
  }
}
