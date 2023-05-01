/*
 * Copyright 2019 ACINQ SAS
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
import fr.acinq.dynamicfees.app.FeeAdjuster.{DynamicFeeRow, DynamicFeesBreakdown}
import fr.acinq.eclair.{Kit, Plugin, PluginParams, Setup, ShortChannelId}
import grizzled.slf4j.Logging

import scala.jdk.CollectionConverters._

class DynamicFees extends Plugin with Logging {

  val fallbackConf = ConfigFactory.parseString(
    """
      dynamicfees.whitelist = []
      dynamicfees.blacklist = []
    """
  )

  var conf: Config = _
  var dynamicFeesConfiguration: DynamicFeesBreakdown = _

  logger.info("loading DynamicFees plugin")

  override def onSetup(setup: Setup): Unit = {
    conf = setup.config
    dynamicFeesConfiguration = DynamicFeesBreakdown(
      depleted = DynamicFeeRow(conf.getDouble("dynamicfees.depleted.threshold"), conf.getDouble("dynamicfees.depleted.multiplier")),
      saturated = DynamicFeeRow(conf.getDouble("dynamicfees.saturated.threshold"), conf.getDouble("dynamicfees.saturated.multiplier")),
      whitelist = conf.withFallback(fallbackConf).getStringList("dynamicfees.whitelist").asScala.toList.map(ShortChannelId.fromCoordinates(_).get),
      blacklist = conf.withFallback(fallbackConf).getStringList("dynamicfees.blacklist").asScala.toList.map(ShortChannelId.fromCoordinates(_).get)
    )
    logger.info(s"depleted channel: threshold = ${dynamicFeesConfiguration.depleted.threshold} " +
      s"multiplier = ${dynamicFeesConfiguration.depleted.multiplier}")
    logger.info(s"saturated channel: threshold = ${dynamicFeesConfiguration.saturated.threshold} " +
      s"multiplier = ${dynamicFeesConfiguration.saturated.multiplier}")
    logger.info(s"blacklisted: ${dynamicFeesConfiguration.blacklist.size}")
    logger.info(s"whitelisted: ${dynamicFeesConfiguration.whitelist.size}")
  }

  override def onKit(kit: Kit): Unit = {
    kit.system.spawn(Behaviors.supervise(FeeAdjuster(kit, dynamicFeesConfiguration)).onFailure(SupervisorStrategy.restart), name = "fee-adjuster")
  }

  override def params: PluginParams = new PluginParams {
    override def name: String = "DynamicFees"
  }
}