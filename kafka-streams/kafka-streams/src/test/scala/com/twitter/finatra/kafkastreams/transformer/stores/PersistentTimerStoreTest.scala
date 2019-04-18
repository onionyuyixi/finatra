package com.twitter.finatra.kafkastreams.transformer.stores

import com.twitter.finatra.json.JsonDiff
import com.twitter.finatra.kafkastreams.transformer.domain.{Expire, Time, TimerMetadata}
import com.twitter.finatra.kafkastreams.transformer.stores.internal.{
  FinatraKeyValueStoreImpl,
  Timer
}
import com.twitter.finatra.kafkastreams.transformer.watermarks.Watermark
import com.twitter.finatra.streams.transformer.internal.domain.TimerSerde
import com.twitter.inject.Test
import org.apache.kafka.common.metrics.Metrics
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.common.utils.LogContext
import org.apache.kafka.streams.processor.internals.MockStreamsMetrics
import org.apache.kafka.streams.state.internals.{RocksDBStoreFactory, ThreadCache}
import org.apache.kafka.test.{InternalMockProcessorContext, NoOpRecordCollector, TestUtils}
import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

class PersistentTimerStoreTest extends Test {

  private type TimerKey = String

  private val context = new InternalMockProcessorContext(
    TestUtils.tempDirectory,
    Serdes.String,
    Serdes.String,
    new NoOpRecordCollector,
    new ThreadCache(new LogContext("testCache"), 0, new MockStreamsMetrics(new Metrics()))
  )

  private val rocksDBStore = RocksDBStoreFactory.create("mystore")

  private val keyValueStore = new FinatraKeyValueStoreImpl[Timer[TimerKey], Array[Byte]](
    _rocksDBStore = rocksDBStore,
    inner = rocksDBStore,
    keySerde = TimerSerde(Serdes.String),
    valueSerde = Serdes.ByteArray()
  )

  val timerStore = new PersistentTimerStore[TimerKey](
    timersStore = keyValueStore,
    onTimer,
    maxTimerFiresPerWatermark = 2
  )

  private val onTimerCalls = new ArrayBuffer[OnTimerCall]

  override def beforeEach(): Unit = {
    keyValueStore.init(context, keyValueStore)
    timerStore.onInit()

    onTimerCalls.clear()
  }

  override def afterEach(): Unit = {
    assertEmptyOnTimerCalls()
    assert(keyValueStore.all.asScala.isEmpty)
    keyValueStore.close()
  }

  test("one timer") {
    val timerCall = OnTimerCall(Time(100), Expire, "key123")
    addTimer(timerCall)
    timerStore.onWatermark(Watermark(100))
    assertAndClearOnTimerCallbacks(timerCall)
    timerStore.onWatermark(Watermark(101))
    assertEmptyOnTimerCalls()
  }

  test("two timers same time before onWatermark") {
    val timerCall1 = OnTimerCall(Time(100), Expire, "key1")
    val timerCall2 = OnTimerCall(Time(100), Expire, "key2")

    addTimer(timerCall1)
    addTimer(timerCall2)

    timerStore.onWatermark(Watermark(100))
    assertAndClearOnTimerCallbacks(timerCall1, timerCall2)
  }

  test("add timer before current watermark") {
    timerStore.onWatermark(Watermark(100))

    val timerCall = OnTimerCall(Time(50), Expire, "key123")
    addTimer(timerCall)
    assertAndClearOnTimerCallbacks(timerCall)

    timerStore.onWatermark(Watermark(101))
    assertEmptyOnTimerCalls()
  }

  test("foundTimerAfterWatermark") {
    val timerCall1 = OnTimerCall(Time(100), Expire, "key1")
    val timerCall2 = OnTimerCall(Time(200), Expire, "key2")

    addTimer(timerCall1)
    addTimer(timerCall2)

    timerStore.onWatermark(Watermark(150))
    assertAndClearOnTimerCallbacks(timerCall1)

    timerStore.onWatermark(Watermark(250))
    assertAndClearOnTimerCallbacks(timerCall2)
  }

  test("exceededMaxTimersFired(2) with hasNext") {
    val timerCall1 = OnTimerCall(Time(100), Expire, "key1")
    val timerCall2 = OnTimerCall(Time(200), Expire, "key2")
    val timerCall3 = OnTimerCall(Time(300), Expire, "key3")

    addTimer(timerCall1)
    addTimer(timerCall2)
    addTimer(timerCall3)

    timerStore.onWatermark(Watermark(400))
    assertAndClearOnTimerCallbacks(timerCall1, timerCall2)

    timerStore.onWatermark(Watermark(401))
    assertAndClearOnTimerCallbacks(timerCall3)
  }

  test("exceededMaxTimersFired(2) with no hasNext") {
    val timerCall1 = OnTimerCall(Time(100), Expire, "key1")
    val timerCall2 = OnTimerCall(Time(200), Expire, "key2")

    addTimer(timerCall1)
    addTimer(timerCall2)

    timerStore.onWatermark(Watermark(400))
    assertAndClearOnTimerCallbacks(timerCall1, timerCall2)

    val timerCall3 = OnTimerCall(Time(300), Expire, "key3")
    addTimer(timerCall3)

    timerStore.onWatermark(Watermark(401))
    assertAndClearOnTimerCallbacks(timerCall3)
  }

  test("onWatermark when no timers") {
    timerStore.onWatermark(Watermark(100))
    timerStore.onWatermark(Watermark(200))
  }

  test("init with existing timers") {
    val timerCall1 = OnTimerCall(Time(100), Expire, "key1")
    addTimer(timerCall1)

    timerStore.onInit()

    timerStore.onWatermark(Watermark(100))
    assertAndClearOnTimerCallbacks(timerCall1)
  }

  private def addTimer(timerCall: OnTimerCall): Unit = {
    timerStore.addTimer(timerCall.time, timerCall.metadata, timerCall.timerKey)
  }

  private def assertAndClearOnTimerCallbacks(expectedTimerCalls: OnTimerCall*): Unit = {
    if (onTimerCalls != expectedTimerCalls) {
      JsonDiff.jsonDiff(onTimerCalls, expectedTimerCalls)
    }
    onTimerCalls.clear()
  }

  private def onTimer(time: Time, metadata: TimerMetadata, timerKey: TimerKey): Unit = {
    onTimerCalls += OnTimerCall(time, metadata, timerKey)
  }

  private def assertEmptyOnTimerCalls(): Unit = {
    assert(onTimerCalls.isEmpty)
  }

  private case class OnTimerCall(time: Time, metadata: TimerMetadata, timerKey: TimerKey)
}
