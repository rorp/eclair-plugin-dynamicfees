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

import com.typesafe.config.ConfigFactory
import fr.acinq.eclair.{ShortChannelId, randomKey}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class DynamicFeesSpec extends AnyFunSuite with Matchers {
  val fallbackConf = ConfigFactory.parseString(
    """
      whitelist = []
      blacklist = []
    """
  )

  test("readConfig parses multiplier FeeType") {
    val conf = ConfigFactory.parseString(
      """
        dynamicfees.depleted.threshold = 0.2
        dynamicfees.depleted.multiplier = 0.5
        dynamicfees.saturated.threshold = 0.8
        dynamicfees.saturated.multiplier = 2.0
      """
    )
    val breakdown = DynamicFees.readConfig(conf, fallbackConf)(randomKey().publicKey)
    breakdown.depleted.threshold shouldBe 0.2
    breakdown.saturated.threshold shouldBe 0.8
    breakdown.depleted.fee shouldBe FeeAdjuster.Multiplier(0.5)
    breakdown.saturated.fee shouldBe FeeAdjuster.Multiplier(2.0)
    breakdown.whitelist shouldBe empty
    breakdown.blacklist shouldBe empty
  }

  test("readConfig parses amount FeeType") {
    val conf = ConfigFactory.parseString(
      """
        dynamicfees.depleted.threshold = 0.1
        dynamicfees.depleted.amount = 1234
        dynamicfees.saturated.threshold = 0.9
        dynamicfees.saturated.amount = 4321
      """
    )
    val breakdown = DynamicFees.readConfig(conf, fallbackConf)(randomKey().publicKey)
    breakdown.depleted.threshold shouldBe 0.1
    breakdown.saturated.threshold shouldBe 0.9
    breakdown.depleted.fee shouldBe FeeAdjuster.Amount(1234)
    breakdown.saturated.fee shouldBe FeeAdjuster.Amount(4321)
    breakdown.whitelist shouldBe empty
    breakdown.blacklist shouldBe empty
  }

  test("readConfig parses whitelist and blacklist") {
    val conf = ConfigFactory.parseString(
      """
        dynamicfees.depleted.threshold = 0.2
        dynamicfees.depleted.multiplier = 0.5
        dynamicfees.saturated.threshold = 0.8
        dynamicfees.saturated.multiplier = 2.0
        dynamicfees.whitelist = ["0x1x2"]
        dynamicfees.blacklist = []
      """
    )
    val breakdown = DynamicFees.readConfig(conf, fallbackConf)(randomKey().publicKey)
    breakdown.whitelist should have size 1
    breakdown.whitelist.head shouldBe ShortChannelId.fromCoordinates("0x1x2").get
    breakdown.blacklist shouldBe empty
  }

  test("readConfig throws if neither multiplier nor amount is set") {
    val conf = ConfigFactory.parseString(
      """
        dynamicfees.depleted.threshold = 0.2
        dynamicfees.saturated.threshold = 0.8
      """
    )
    assertThrows[IllegalArgumentException] {
      DynamicFees.readConfig(conf, fallbackConf)(randomKey().publicKey)
    }
  }

  test("readConfig throws if both multiplier and amount are set") {
    val conf = ConfigFactory.parseString(
      """
        dynamicfees.depleted.threshold = 0.2
        dynamicfees.depleted.multiplier = 0.5
        dynamicfees.depleted.amount = 1234
        dynamicfees.saturated.threshold = 0.8
        dynamicfees.saturated.multiplier = 2.0
        dynamicfees.saturated.amount = 4321
      """
    )
    assertThrows[IllegalArgumentException] {
      DynamicFees.readConfig(conf, fallbackConf)(randomKey().publicKey)
    }
  }

  test("fee calculation uses dynamicfees.fee-proportional-millionths as base with multiplier") {
    val conf = ConfigFactory.parseString(
      """
        dynamicfees.depleted.threshold = 0.2
        dynamicfees.depleted.multiplier = 0.5
        dynamicfees.saturated.threshold = 0.8
        dynamicfees.saturated.multiplier = 2.0
        dynamicfees.fee-proportional-millionths = 2000
      """
    )
    val breakdown = DynamicFees.readConfig(conf, fallbackConf)(randomKey().publicKey)
    val baseFee = breakdown.feeProportionalMillionths
    baseFee shouldBe Some(2000)
  }

  test("fee calculation uses default fee-proportional-millionths if not set in config") {
    val conf = ConfigFactory.parseString(
      """
        dynamicfees.depleted.threshold = 0.2
        dynamicfees.depleted.multiplier = 0.5
        dynamicfees.saturated.threshold = 0.8
        dynamicfees.saturated.multiplier = 2.0
      """
    )
    val breakdown = DynamicFees.readConfig(conf, fallbackConf)(randomKey().publicKey)
    val baseFee = breakdown.feeProportionalMillionths
    baseFee shouldBe None
  }

  test("readConfig returns default for all nodes if per-node is not set") {
    val conf = ConfigFactory.parseString(
      """
        dynamicfees.depleted.threshold = 0.2
        dynamicfees.depleted.multiplier = 0.5
        dynamicfees.saturated.threshold = 0.8
        dynamicfees.saturated.multiplier = 2.0
      """
    )
    val result = DynamicFees.readConfig(conf, fallbackConf)
    val anyPubKey = randomKey().publicKey
    val breakdown = result(anyPubKey)
    breakdown.depleted.threshold shouldBe 0.2
    breakdown.saturated.threshold shouldBe 0.8
    breakdown.depleted.fee shouldBe FeeAdjuster.Multiplier(0.5)
    breakdown.saturated.fee shouldBe FeeAdjuster.Multiplier(2.0)
    breakdown.whitelist shouldBe empty
    breakdown.blacklist shouldBe empty
  }

  test("readConfig parses per-node overrides and uses default for others") {
    val pubKey = randomKey().publicKey
    val conf = ConfigFactory.parseString(
      s"""
        dynamicfees.depleted.threshold = 0.2
        dynamicfees.depleted.multiplier = 0.5
        dynamicfees.saturated.threshold = 0.8
        dynamicfees.saturated.multiplier = 2.0
        dynamicfees.per-node {
          "${pubKey.toHex}" {
            depleted.threshold = 0.1
            depleted.multiplier = 0.9
            saturated.threshold = 0.7
            saturated.multiplier = 1.5
          }
        }
      """
    )
    val otherPubKey = randomKey().publicKey
    val result = DynamicFees.readConfig(conf, fallbackConf)
    val defaultBreakdown = result(otherPubKey)
    val nodeBreakdown = result(pubKey)

    nodeBreakdown shouldNot be(defaultBreakdown)

    defaultBreakdown.depleted.threshold shouldBe 0.2
    defaultBreakdown.saturated.threshold shouldBe 0.8
    defaultBreakdown.depleted.fee shouldBe FeeAdjuster.Multiplier(0.5)
    defaultBreakdown.saturated.fee shouldBe FeeAdjuster.Multiplier(2.0)
    defaultBreakdown.whitelist shouldBe empty
    defaultBreakdown.blacklist shouldBe empty

    nodeBreakdown.depleted.threshold shouldBe 0.1
    nodeBreakdown.saturated.threshold shouldBe 0.7
    nodeBreakdown.depleted.fee shouldBe FeeAdjuster.Multiplier(0.9)
    nodeBreakdown.saturated.fee shouldBe FeeAdjuster.Multiplier(1.5)
    nodeBreakdown.whitelist shouldBe empty
    nodeBreakdown.blacklist shouldBe empty
  }
}