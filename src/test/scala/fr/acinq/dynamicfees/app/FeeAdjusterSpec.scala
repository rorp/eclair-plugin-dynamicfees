/*
 * Copyright 2019 rorp
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package fr.acinq.dynamicfees.app

import fr.acinq.bitcoin.scalacompat.{BlockHash, ByteVector32, ByteVector64}
import fr.acinq.dynamicfees.app.FeeAdjuster.newFeeProportionalForChannel
import fr.acinq.eclair.wire.protocol.ChannelUpdate.{ChannelFlags, MessageFlags}
import fr.acinq.eclair.wire.protocol.{ChannelUpdate, TlvStream}
import fr.acinq.eclair.{CltvExpiryDelta, MilliSatoshi, MilliSatoshiLong, ShortChannelId, TimestampSecond}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.slf4j.{Logger, Marker}

class FeeAdjusterSpec extends AnyFunSuite with Matchers {

  // Helper to create a dummy ChannelUpdate
  def makeChannelUpdate(feeProportional: Long): ChannelUpdate = {
    ChannelUpdate(
      signature = ByteVector64.Zeroes,
      chainHash = BlockHash(ByteVector32.Zeroes),
      shortChannelId = ShortChannelId(0L),
      timestamp = TimestampSecond(0),
      messageFlags = MessageFlags(dontForward = false),
      channelFlags = ChannelFlags(isEnabled = true, isNode1 = true),
      cltvExpiryDelta = CltvExpiryDelta(0),
      htlcMinimumMsat = MilliSatoshi(0),
      feeBaseMsat = MilliSatoshi(0),
      feeProportionalMillionths = feeProportional,
      htlcMaximumMsat = MilliSatoshi(0),
      tlvStream = TlvStream.empty
    )
  }

  val feeProportionalMillionths: Long = 1000L

  test("returns new fee using Multiplier for depleted state") {
    val depleted = FeeAdjuster.DynamicFeeRow(0.3, FeeAdjuster.Multiplier(0.5))
    val saturated = FeeAdjuster.DynamicFeeRow(0.8, FeeAdjuster.Multiplier(2.0))
    val breakdown = FeeAdjuster.DynamicFeesBreakdown(depleted, saturated, None, Nil, Nil)
    val availableBalanceForSend = 10.msat // toLocalPercentage = 0.1 < 0.3
    val availableBalanceForReceive = 90.msat // toLocalPercentage = 0.1 < 0.3
    val channelUpdate = makeChannelUpdate(1000)
    val result = newFeeProportionalForChannel(breakdown, availableBalanceForSend, availableBalanceForReceive, channelUpdate, feeProportionalMillionths, dummyLogger)
    result shouldBe Some(500L)
  }

  test("returns new fee using Multiplier for saturated state") {
    val depleted = FeeAdjuster.DynamicFeeRow(0.3, FeeAdjuster.Multiplier(0.5))
    val saturated = FeeAdjuster.DynamicFeeRow(0.8, FeeAdjuster.Multiplier(2.0))
    val breakdown = FeeAdjuster.DynamicFeesBreakdown(depleted, saturated, None, Nil, Nil)
    val availableBalanceForSend = 90.msat // toLocalPercentage = 0.9 > 0.8
    val availableBalanceForReceive = 10.msat // toLocalPercentage = 0.9 > 0.8
    val channelUpdate = makeChannelUpdate(1000)
    val result = newFeeProportionalForChannel(breakdown, availableBalanceForSend, availableBalanceForReceive, channelUpdate, feeProportionalMillionths, dummyLogger)
    result shouldBe Some(2000L)
  }

  test("returns None if Multiplier fee does not change") {
    val depleted = FeeAdjuster.DynamicFeeRow(0.3, FeeAdjuster.Multiplier(1.0))
    val saturated = FeeAdjuster.DynamicFeeRow(0.8, FeeAdjuster.Multiplier(1.0))
    val breakdown = FeeAdjuster.DynamicFeesBreakdown(depleted, saturated, None, Nil, Nil)
    val availableBalanceForSend = 50.msat // toLocalPercentage = 0.5 (balanced)
    val availableBalanceForReceive = 50.msat // toLocalPercentage = 0.5 (balanced)
    val channelUpdate = makeChannelUpdate(1000)
    val result = newFeeProportionalForChannel(breakdown, availableBalanceForSend, availableBalanceForReceive, channelUpdate, feeProportionalMillionths, dummyLogger)
    result shouldBe None
  }

  test("returns None if Amount fee does not change") {
    val depleted = FeeAdjuster.DynamicFeeRow(0.3, FeeAdjuster.Amount(1000))
    val saturated = FeeAdjuster.DynamicFeeRow(0.8, FeeAdjuster.Amount(1000))
    val breakdown = FeeAdjuster.DynamicFeesBreakdown(depleted, saturated, None, Nil, Nil)
    val availableBalanceForSend = 50.msat // toLocalPercentage = 0.5 (balanced)
    val availableBalanceForReceive = 50.msat // toLocalPercentage = 0.5 (balanced)
    val channelUpdate = makeChannelUpdate(1000)
    val result = newFeeProportionalForChannel(breakdown, availableBalanceForSend, availableBalanceForReceive, channelUpdate, feeProportionalMillionths, dummyLogger)
    result shouldBe None
  }

  test("returns new fee using Amount for depleted state") {
    val depleted = FeeAdjuster.DynamicFeeRow(0.3, FeeAdjuster.Amount(1234))
    val saturated = FeeAdjuster.DynamicFeeRow(0.8, FeeAdjuster.Multiplier(2.0))
    val breakdown = FeeAdjuster.DynamicFeesBreakdown(depleted, saturated, None, Nil, Nil)
    val availableBalanceForSend = 10.msat // toLocalPercentage = 0.1 < 0.3
    val availableBalanceForReceive = 90.msat // toLocalPercentage = 0.1 < 0.3
    val channelUpdate = makeChannelUpdate(1000)
    val result = newFeeProportionalForChannel(breakdown, availableBalanceForSend, availableBalanceForReceive, channelUpdate, feeProportionalMillionths, dummyLogger)
    result shouldBe Some(1234L)
  }

  test("returns new fee using Amount for saturated state") {
    val depleted = FeeAdjuster.DynamicFeeRow(0.3, FeeAdjuster.Multiplier(0.5))
    val saturated = FeeAdjuster.DynamicFeeRow(0.8, FeeAdjuster.Amount(4321))
    val breakdown = FeeAdjuster.DynamicFeesBreakdown(depleted, saturated, None, Nil, Nil)
    val availableBalanceForSend = 90.msat // toLocalPercentage = 0.9 > 0.8
    val availableBalanceForReceive = 10.msat // toLocalPercentage = 0.9 > 0.8
    val channelUpdate = makeChannelUpdate(1000)
    val result = newFeeProportionalForChannel(breakdown, availableBalanceForSend, availableBalanceForReceive, channelUpdate, feeProportionalMillionths, dummyLogger)
    result shouldBe Some(4321L)
  }

  test("uses feeProportionalMillionths from DynamicFeesBreakdown if set (Multiplier)") {
    val depleted = FeeAdjuster.DynamicFeeRow(0.3, FeeAdjuster.Multiplier(0.5))
    val saturated = FeeAdjuster.DynamicFeeRow(0.8, FeeAdjuster.Multiplier(2.0))
    val breakdown = FeeAdjuster.DynamicFeesBreakdown(depleted, saturated, Some(3000), Nil, Nil)
    val availableBalanceForSend = 10.msat // toLocalPercentage = 0.1 < 0.3
    val availableBalanceForReceive = 90.msat // toLocalPercentage = 0.1 < 0.3
    val channelUpdate = makeChannelUpdate(1000)
    val result = newFeeProportionalForChannel(breakdown, availableBalanceForSend, availableBalanceForReceive, channelUpdate, 1000L, dummyLogger)
    // Should use 3000 * 0.5 = 1500
    result shouldBe Some(1500L)
  }

  test("uses default feeProportionalMillionths if not set in DynamicFeesBreakdown (Multiplier)") {
    val depleted = FeeAdjuster.DynamicFeeRow(0.3, FeeAdjuster.Multiplier(0.5))
    val saturated = FeeAdjuster.DynamicFeeRow(0.8, FeeAdjuster.Multiplier(2.0))
    val breakdown = FeeAdjuster.DynamicFeesBreakdown(depleted, saturated, None, Nil, Nil)
    val availableBalanceForSend = 10.msat // toLocalPercentage = 0.1 < 0.3
    val availableBalanceForReceive = 90.msat // toLocalPercentage = 0.1 < 0.3
    val channelUpdate = makeChannelUpdate(1000)
    val result = newFeeProportionalForChannel(breakdown, availableBalanceForSend, availableBalanceForReceive, channelUpdate, 2000L, dummyLogger)
    // Should use 2000 * 0.5 = 1000
    result shouldBe None
  }

  test("uses feeProportionalMillionths from DynamicFeesBreakdown if set (Amount)") {
    val depleted = FeeAdjuster.DynamicFeeRow(0.3, FeeAdjuster.Amount(1234))
    val saturated = FeeAdjuster.DynamicFeeRow(0.8, FeeAdjuster.Amount(4321))
    val breakdown = FeeAdjuster.DynamicFeesBreakdown(depleted, saturated, Some(9999), Nil, Nil)
    val availableBalanceForSend = 10.msat // toLocalPercentage = 0.1 < 0.3
    val availableBalanceForReceive = 90.msat // toLocalPercentage = 0.1 < 0.3
    val channelUpdate = makeChannelUpdate(1000)
    val result = newFeeProportionalForChannel(breakdown, availableBalanceForSend, availableBalanceForReceive, channelUpdate, 1000L, dummyLogger)
    // Should use the fixed amount, not the base fee
    result shouldBe Some(1234L)
  }

  test("filterChannel returns true when both whitelist and blacklist are empty") {
    val breakdown = FeeAdjuster.DynamicFeesBreakdown(
      depleted = FeeAdjuster.DynamicFeeRow(0.3, FeeAdjuster.Multiplier(0.5)),
      saturated = FeeAdjuster.DynamicFeeRow(0.8, FeeAdjuster.Multiplier(2.0)),
      feeProportionalMillionths = None,
      whitelist = Nil,
      blacklist = Nil
    )
    val scid = ShortChannelId(1234L)
    FeeAdjuster.filterChannel(scid, breakdown) shouldBe true
  }

  test("filterChannel returns true if whitelist contains the channel and blacklist is empty") {
    val scid = ShortChannelId(1234L)
    val breakdown = FeeAdjuster.DynamicFeesBreakdown(
      depleted = FeeAdjuster.DynamicFeeRow(0.3, FeeAdjuster.Multiplier(0.5)),
      saturated = FeeAdjuster.DynamicFeeRow(0.8, FeeAdjuster.Multiplier(2.0)),
      feeProportionalMillionths = None,
      whitelist = List(scid),
      blacklist = Nil
    )
    FeeAdjuster.filterChannel(scid, breakdown) shouldBe true
    FeeAdjuster.filterChannel(ShortChannelId(5678L), breakdown) shouldBe false
  }

  test("filterChannel returns true if blacklist does not contain the channel and whitelist is empty") {
    val scid = ShortChannelId(1234L)
    val breakdown = FeeAdjuster.DynamicFeesBreakdown(
      depleted = FeeAdjuster.DynamicFeeRow(0.3, FeeAdjuster.Multiplier(0.5)),
      saturated = FeeAdjuster.DynamicFeeRow(0.8, FeeAdjuster.Multiplier(2.0)),
      feeProportionalMillionths = None,
      whitelist = Nil,
      blacklist = List(ShortChannelId(5678L))
    )
    FeeAdjuster.filterChannel(scid, breakdown) shouldBe true
    FeeAdjuster.filterChannel(ShortChannelId(5678L), breakdown) shouldBe false
  }

  test("filterChannel returns false if whitelist is non-empty and does not contain the channel") {
    val breakdown = FeeAdjuster.DynamicFeesBreakdown(
      depleted = FeeAdjuster.DynamicFeeRow(0.3, FeeAdjuster.Multiplier(0.5)),
      saturated = FeeAdjuster.DynamicFeeRow(0.8, FeeAdjuster.Multiplier(2.0)),
      feeProportionalMillionths = None,
      whitelist = List(ShortChannelId(1234L)),
      blacklist = Nil
    )
    FeeAdjuster.filterChannel(ShortChannelId(5678L), breakdown) shouldBe false
  }

  test("filterChannel returns false if blacklist contains the channel and whitelist is empty") {
    val breakdown = FeeAdjuster.DynamicFeesBreakdown(
      depleted = FeeAdjuster.DynamicFeeRow(0.3, FeeAdjuster.Multiplier(0.5)),
      saturated = FeeAdjuster.DynamicFeeRow(0.8, FeeAdjuster.Multiplier(2.0)),
      feeProportionalMillionths = None,
      whitelist = Nil,
      blacklist = List(ShortChannelId(1234L))
    )
    FeeAdjuster.filterChannel(ShortChannelId(1234L), breakdown) shouldBe false
  }

  // Dummy logger
  val dummyLogger: Logger = new Logger {
    override def getName: java.lang.String = ???

    override def isTraceEnabled: Boolean = ???

    override def trace(msg: java.lang.String): Unit = ???

    override def trace(format: java.lang.String, arg: Any): Unit = ???

    override def trace(format: java.lang.String, arg1: Any, arg2: Any): Unit = ???

    override def trace(msg: java.lang.String, t: java.lang.Throwable): Unit = ???

    override def isTraceEnabled(marker: org.slf4j.Marker): Boolean = ???

    override def trace(marker: org.slf4j.Marker, msg: java.lang.String): Unit = ???

    override def trace(marker: org.slf4j.Marker, format: java.lang.String, arg: Any): Unit = ???

    override def trace(marker: org.slf4j.Marker, format: java.lang.String, arg1: Any, arg2: Any): Unit = ???

    override def trace(marker: org.slf4j.Marker, msg: java.lang.String, t: java.lang.Throwable): Unit = ???

    override def isDebugEnabled: Boolean = ???

    override def debug(msg: java.lang.String): Unit = ()

    override def debug(format: java.lang.String, arg: Any): Unit = ???

    override def debug(format: java.lang.String, arg1: Any, arg2: Any): Unit = ???

    override def debug(msg: java.lang.String, t: java.lang.Throwable): Unit = ???

    override def isDebugEnabled(marker: org.slf4j.Marker): Boolean = ???

    override def debug(marker: org.slf4j.Marker, msg: java.lang.String): Unit = ???

    override def debug(marker: org.slf4j.Marker, format: java.lang.String, arg: Any): Unit = ???

    override def debug(marker: org.slf4j.Marker, format: java.lang.String, arg1: Any, arg2: Any): Unit = ???

    override def debug(marker: org.slf4j.Marker, msg: java.lang.String, t: java.lang.Throwable): Unit = ???

    override def isInfoEnabled: Boolean = ???

    override def info(msg: java.lang.String): Unit = ???

    override def info(format: java.lang.String, arg: Any): Unit = ???

    override def info(format: java.lang.String, arg1: Any, arg2: Any): Unit = ???

    override def info(msg: java.lang.String, t: java.lang.Throwable): Unit = ???

    override def isInfoEnabled(marker: org.slf4j.Marker): Boolean = ???

    override def info(marker: org.slf4j.Marker, msg: java.lang.String): Unit = ???

    override def info(marker: org.slf4j.Marker, format: java.lang.String, arg: Any): Unit = ???

    override def info(marker: org.slf4j.Marker, format: java.lang.String, arg1: Any, arg2: Any): Unit = ???

    override def info(marker: org.slf4j.Marker, msg: java.lang.String, t: java.lang.Throwable): Unit = ???

    override def isWarnEnabled: Boolean = ???

    override def warn(msg: java.lang.String): Unit = ???

    override def warn(format: java.lang.String, arg: Any): Unit = ???

    override def warn(format: java.lang.String, arg1: Any, arg2: Any): Unit = ???

    override def warn(msg: java.lang.String, t: java.lang.Throwable): Unit = ???

    override def isWarnEnabled(marker: org.slf4j.Marker): Boolean = ???

    override def trace(format: String, arguments: Any*): Unit = ???

    override def trace(marker: Marker, format: String, argArray: Any*): Unit = ???

    override def debug(format: String, arguments: Any*): Unit = ???

    override def debug(marker: Marker, format: String, arguments: Any*): Unit = ???

    override def info(format: String, arguments: Any*): Unit = ???

    override def info(marker: Marker, format: String, arguments: Any*): Unit = ???

    override def warn(format: String, arguments: Any*): Unit = ???

    override def warn(marker: Marker, msg: String): Unit = ???

    override def warn(marker: Marker, format: String, arg: Any): Unit = ???

    override def warn(marker: Marker, format: String, arg1: Any, arg2: Any): Unit = ???

    override def warn(marker: Marker, format: String, arguments: Any*): Unit = ???

    override def warn(marker: Marker, msg: String, t: Throwable): Unit = ???

    override def isErrorEnabled: Boolean = ???

    override def error(msg: String): Unit = ???

    override def error(format: String, arg: Any): Unit = ???

    override def error(format: String, arg1: Any, arg2: Any): Unit = ???

    override def error(format: String, arguments: Any*): Unit = ???

    override def error(msg: String, t: Throwable): Unit = ???

    override def isErrorEnabled(marker: Marker): Boolean = ???

    override def error(marker: Marker, msg: String): Unit = ???

    override def error(marker: Marker, format: String, arg: Any): Unit = ???

    override def error(marker: Marker, format: String, arg1: Any, arg2: Any): Unit = ???

    override def error(marker: Marker, format: String, arguments: Any*): Unit = ???

    override def error(marker: Marker, msg: String, t: Throwable): Unit = ???
  }
}