/*
 * Copyright 2019 ACINQ SAS, rorp
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

import akka.actor.typed.SupervisorStrategy
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import com.typesafe.config.{Config, ConfigFactory}
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.dynamicfees.app.FeeAdjuster.DynamicFeesConfig
import fr.acinq.eclair.{Kit, Plugin, PluginParams, Setup, ShortChannelId}
import grizzled.slf4j.Logging
import scodec.bits.ByteVector

import scala.jdk.CollectionConverters._

class DynamicFees extends Plugin with Logging {

  val fallbackConf = ConfigFactory.parseString(
    """
      whitelist = []
      blacklist = []
    """
  )

  var dynamicFeesConfiguration: DynamicFeesConfig = _

  logger.info("loading DynamicFees plugin")

  override def onSetup(setup: Setup): Unit = {
    dynamicFeesConfiguration = DynamicFees.readConfig(setup.config, fallbackConf)
    logger.info(s"default depleted channel: threshold = ${dynamicFeesConfiguration.defaultBreakdown.depleted.threshold} fee = ${dynamicFeesConfiguration.defaultBreakdown.depleted.fee}")
    logger.info(s"default saturated channel: threshold = ${dynamicFeesConfiguration.defaultBreakdown.saturated.threshold} fee = ${dynamicFeesConfiguration.defaultBreakdown.saturated.fee}")
    logger.info(s"default fee proportional = ${dynamicFeesConfiguration.defaultBreakdown.feeProportionalMillionths.getOrElse(setup.nodeParams.relayParams.publicChannelFees.feeProportionalMillionths)}")
    dynamicFeesConfiguration.perNodeBreakdowns.foreach { case (pubkey, breakdown) =>
      logger.info(s"${pubkey.toHex} depleted channel: threshold = ${breakdown.depleted.threshold} fee = ${breakdown.depleted.fee}")
      logger.info(s"${pubkey.toHex} saturated channel: threshold = ${breakdown.saturated.threshold} fee = ${breakdown.saturated.fee}")
      logger.info(s"${pubkey.toHex} fee proportional = ${breakdown.feeProportionalMillionths.getOrElse(setup.nodeParams.relayParams.publicChannelFees.feeProportionalMillionths)}")
    }
    logger.info(s"blacklisted: ${dynamicFeesConfiguration.defaultBreakdown.blacklist.size}")
    logger.info(s"whitelisted: ${dynamicFeesConfiguration.defaultBreakdown.whitelist.size}")
  }

  override def onKit(kit: Kit): Unit = {
    kit.system.spawn(Behaviors.supervise(FeeAdjuster(kit, dynamicFeesConfiguration)).onFailure(SupervisorStrategy.restart), name = "fee-adjuster")
  }

  override def params: PluginParams = new PluginParams {
    override def name: String = "DynamicFees"
  }

}

object DynamicFees {
  /**
   * Reads a FeeType from the config for the given prefix (e.g., "dynamicfees.depleted").
   * Supports either a multiplier (Double) or an amount (Long), but not both.
   * Throws an exception if neither nor both are set.
   */
  private def getFeeType(conf: Config, prefix: String): FeeAdjuster.FeeType = {
    val hasMultiplier = conf.hasPath(s"$prefix.multiplier")
    val hasAmount = conf.hasPath(s"$prefix.amount")
    (hasMultiplier, hasAmount) match {
      case (true, false) => FeeAdjuster.Multiplier(conf.getDouble(s"$prefix.multiplier"))
      case (false, true) => FeeAdjuster.Amount(conf.getLong(s"$prefix.amount"))
      case (false, false) =>
        throw new IllegalArgumentException(s"Config for $prefix must specify either 'multiplier' or 'amount'")
      case (true, true) =>
        throw new IllegalArgumentException(s"Config for $prefix must not specify both 'multiplier' and 'amount'")
    }
  }

  private def readDynamicFeesBreakdown(conf: Config, fallbackConf: Config): FeeAdjuster.DynamicFeesBreakdown = {
    FeeAdjuster.DynamicFeesBreakdown(
      depleted = FeeAdjuster.DynamicFeeRow(
        conf.getDouble("depleted.threshold"),
        getFeeType(conf, "depleted")
      ),
      saturated = FeeAdjuster.DynamicFeeRow(
        conf.getDouble("saturated.threshold"),
        getFeeType(conf, "saturated")
      ),
      whitelist = conf.withFallback(fallbackConf).getStringList("whitelist").asScala.toList.map(ShortChannelId.fromCoordinates(_).get),
      blacklist = conf.withFallback(fallbackConf).getStringList("blacklist").asScala.toList.map(ShortChannelId.fromCoordinates(_).get),
      feeProportionalMillionths = if (conf.hasPath("fee-proportional-millionths")) Some(conf.getLong("fee-proportional-millionths")) else None
    )
  }

  def readConfig(conf: Config, fallbackConf: Config): FeeAdjuster.DynamicFeesConfig = {
    // Parse the top-level config as the default
    val defaultFeesBreakdown = readDynamicFeesBreakdown(conf.getConfig("dynamicfees"), fallbackConf)

    // Parse per-node configs if present
    val perNodeMap =
      if (conf.hasPath("dynamicfees.per-node")) {
        val perNodeConfig = conf.getConfig("dynamicfees.per-node")
        perNodeConfig.root().keySet().asScala.map { key =>
          val nodeConf = perNodeConfig.getConfig(key).withFallback(conf)
          val pubKey = PublicKey.fromBin(ByteVector.fromHex(key).get, true)
          pubKey -> readDynamicFeesBreakdown(nodeConf, fallbackConf)
        }.toMap
      } else Map.empty[PublicKey, FeeAdjuster.DynamicFeesBreakdown]
    // Return the map, with the default under None (or use a special key if needed)
    FeeAdjuster.DynamicFeesConfig(defaultFeesBreakdown, perNodeMap)
  }
}