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

import akka.actor.typed.Behavior
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.util.Timeout
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.dynamicfees.app.FeeAdjuster.{DynamicFeesBreakdown, WrappedPaymentEvent}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.payment._
import fr.acinq.eclair.wire.protocol.ChannelUpdate
import fr.acinq.eclair.{EclairImpl, Kit, ShortChannelId}
import org.slf4j.Logger

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object FeeAdjuster {

  trait Command

  case class WrappedPaymentEvent(event: PaymentEvent) extends Command

  case class DynamicFeeRow(threshold: Double, multiplier: Double)

  case class DynamicFeesBreakdown(
    depleted: DynamicFeeRow,
    saturated: DynamicFeeRow,
    whitelist: List[ShortChannelId],
    blacklist: List[ShortChannelId]
  ) {
    require(!(whitelist.nonEmpty && blacklist.nonEmpty), "cannot use both whitelist and blacklist in dynamicfees plugin configuration")
    require(depleted.threshold > 0 && depleted.threshold < 1, "invalid values for depleted threshold")
    require(saturated.threshold > 0 && saturated.threshold < 1, "invalid values for saturated threshold")
    require(depleted.threshold < saturated.threshold, "depleted threshold must be smaller than saturated threshold")
    require(depleted.multiplier > 0 && saturated.multiplier > 0)
  }

  def apply(kit: Kit, dynamicFees: DynamicFeesBreakdown): Behavior[Command] = {
    Behaviors.setup { context =>
      new FeeAdjuster(kit, dynamicFees, context).waiting
    }
  }

}

class FeeAdjuster private(kit: Kit, dynamicFees: DynamicFeesBreakdown, context: ActorContext[FeeAdjuster.Command]) {

  private implicit val ec: ExecutionContext = context.executionContext
  private implicit val askTimeout: Timeout = Timeout(30 seconds)
  private val eclair = new EclairImpl(kit)

  context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[PaymentEvent](e => WrappedPaymentEvent(e)))


  def waiting: Behavior[FeeAdjuster.Command] = {
    val logMessage = "computing new dynamic fees for trampoline payment_hash="
    Behaviors.receiveMessagePartial {
      case WrappedPaymentEvent(PaymentSent(_, paymentHash, _, _, _, parts)) =>
        context.log.debug(s"$logMessage$paymentHash")
        val channels = parts.map(_.toChannelId)
        updateFees(channels)
        Behaviors.same
      case WrappedPaymentEvent(PaymentReceived(paymentHash, parts)) =>
        context.log.debug(s"$logMessage$paymentHash")
        val channels = parts.map(_.fromChannelId)
        updateFees(channels)
        Behaviors.same
      case WrappedPaymentEvent(TrampolinePaymentRelayed(paymentHash, incoming, outgoing, _, _, _)) =>
        context.log.debug(s"$logMessage$paymentHash")
        val channels = incoming.map(_.channelId) ++ outgoing.map(_.channelId)
        updateFees(channels)
        Behaviors.same
      case WrappedPaymentEvent(ChannelPaymentRelayed(_, _, paymentHash, fromChannelId, toChannelId, _)) =>
        context.log.debug(s"$logMessage$paymentHash")
        val channels = fromChannelId :: toChannelId :: Nil
        updateFees(channels)
        Behaviors.same
    }
  }

  /**
   * Will update the relay fee of the given channels if their current balance falls into depleted/saturated
   * category
   */
  def updateFees(channels: Seq[ByteVector32]): Future[Unit] = {
    val log = context.log
    Future.sequence(channels.map(getChannelData)).map(_.flatten).map { channelData =>
      channelData
        .filter(filterChannel)
        .foreach { channel =>
          newFeeProportionalForChannel(channel.commitments, channel.channelUpdate, log) match {
            case None =>
              log.debug(s"skipping fees update for channelId=${channel.commitments.channelId}")
            case Some(feeProp) =>
              log.info(s"updating feeProportional for channelId=${channel.commitments.channelId} oldFee=${channel.channelUpdate.feeProportionalMillionths} newFee=$feeProp")
              eclair.updateRelayFee(List(channel.commitments.localNodeId, channel.commitments.remoteNodeId),
                kit.nodeParams.relayParams.publicChannelFees.feeBase,
                feeProp)
          }
        }
    }
  }

  def filterChannel(channel: DATA_NORMAL): Boolean = {
    channel.shortIds.real.toOption match {
      case Some(shortChannelId) =>
        (dynamicFees.whitelist.isEmpty && dynamicFees.blacklist.isEmpty) ||
          (dynamicFees.whitelist.nonEmpty && dynamicFees.whitelist.contains(shortChannelId)) ||
          (dynamicFees.blacklist.nonEmpty && !dynamicFees.blacklist.contains(shortChannelId))
      case None => false
    }
  }

  def getChannelData(channelId: ByteVector32): Future[Option[DATA_NORMAL]] = {
    eclair.channelInfo(Left(channelId)).map {
      case RES_GET_CHANNEL_INFO(_, _, _, NORMAL, data: DATA_NORMAL) => Some(data)
      case _ => None
    }
  }

  /**
   * Computes the updated fee proportional for this channel, the new value is returned only if it's necessary
   * to update.
   *
   * @return
   */
  def newFeeProportionalForChannel(commitments: Commitments, channelUpdate: ChannelUpdate, log: Logger): Option[Long] = {
    val toLocal = commitments.availableBalanceForSend
    val toRemote = commitments.availableBalanceForReceive
    val toLocalPercentage = toLocal.toLong.toDouble / (toLocal.toLong + toRemote.toLong)

    val multiplier = if (toLocalPercentage < dynamicFees.depleted.threshold) {
      // do depleted update
      dynamicFees.depleted.multiplier
    } else if (toLocalPercentage > dynamicFees.saturated.threshold) {
      // do saturated update
      dynamicFees.saturated.multiplier
    } else {
      // it's balanced, do not apply multiplier
      1.0
    }

    val newFeeProportional = (kit.nodeParams.relayParams.publicChannelFees.feeProportionalMillionths * multiplier).toLong
    log.debug(s"prevFeeProportional=${channelUpdate.feeProportionalMillionths} newFeeProportional=$newFeeProportional")

    if (channelUpdate.feeProportionalMillionths == newFeeProportional) {
      None
    } else {
      Some(newFeeProportional)
    }
  }

}
