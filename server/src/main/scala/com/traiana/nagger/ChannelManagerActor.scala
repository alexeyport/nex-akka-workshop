package com.traiana.nagger

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings, ShardRegion}
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import com.traiana.nagger.ChannelActorReqs.EntityEnvelope

import scala.concurrent.duration._

object ChannelManagerActorReq {
  case class JoinLeveChannel(nick: String, channel: String, joinLeav: Boolean, oS: ActorRef)
  case class PostMessageReq(message: String, channel: String, nick: String, oS: ActorRef)
  case class UpdateApiActorSet(actorR: ActorRef)
  case object End
}

object ChannelManagerActorResp {
  case class PostMessageResp(nicks: List[String], message: String, channel: String, nick: String)
  case class JoinLeveChannelResp(nick: String, list: List[String], oS: ActorRef, channel: String)
}

object ChannelManagerEvents {
  case class JcoinLeveChannelEvt(nick: String, channel: String, joinLeav: Boolean)
}

class ChannelManagerActor extends Actor with ActorLogging {
  import context.dispatcher
  implicit val timeout = Timeout(3 seconds)

  val apiActorSet = scala.collection.mutable.Set[ActorRef]()

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case EntityEnvelope(id, payload) => (id.toString, payload)
  }

  val numberOfShards = 10

  val extractShardId: ShardRegion.ExtractShardId = {
    case EntityEnvelope(id, _) => (id.size % numberOfShards).toString
    case ShardRegion.StartEntity(id) =>
      (id.size % numberOfShards).toString
  }

  val channelActorRegion: ActorRef = ClusterSharding(context.system).start(
    typeName = "ChannelActor",
    entityProps = Props[ChannelActor],
    settings = ClusterShardingSettings(context.system),
    extractEntityId = extractEntityId,
    extractShardId = extractShardId)

  //val channelActorRegion: ActorRef = ClusterSharding(context.system).shardRegion("ChannelActor")

  def receive: Receive = {
    case jlc: ChannelManagerActorReq.JoinLeveChannel => {
      if (jlc.joinLeav) {
        log.info(s"---------->  ChannelManagerActor ${jlc.nick} joined channel ${jlc.channel}  ")
        (channelActorRegion ? EntityEnvelope(jlc.channel, ChannelActorReqs.JoinChannel(jlc.nick)))
          .mapTo[ChannelActorResps.GetMessageListResp]
          .map(res => ChannelManagerActorResp.JoinLeveChannelResp(jlc.nick, res.list, jlc.oS, jlc.channel)).pipeTo(
            sender())
      } else {
        log.info(s"---------->  ChannelManagerActor ${jlc.nick} left channel ${jlc.channel}  ")
        channelActorRegion ! EntityEnvelope(jlc.channel, ChannelActorReqs.LeaveChannel(jlc.nick))
        jlc.oS ! ChannelManagerActorResp.JoinLeveChannelResp(jlc.nick, List.empty[String], jlc.oS, jlc.channel)
      }
    }

    case pm: ChannelManagerActorReq.PostMessageReq => {
      val oS = sender()
      channelActorRegion ! EntityEnvelope(pm.channel, ChannelActorReqs.GetNicksPerChannelReq(oS, pm.message, pm.nick))
    }

    case gnpcl: ChannelActorResps.GetNicksPerChannelResp => {
      log.info(s"---------->  ChannelManagerActor Posting messages to: ${gnpcl.oS.toString()} ")
      apiActorSet.foreach(_ ! ChannelManagerActorResp.PostMessageResp(gnpcl.list, gnpcl.message, gnpcl.channel, gnpcl.nick))
      //gnpcl.oS ! ChannelManagerActorResp.PostMessageResp(gnpcl.list, gnpcl.message, gnpcl.channel, gnpcl.nick)
    }

    case uapim: ChannelManagerActorReq.UpdateApiActorSet => {
      log.info(s"---------->  ChannelManagerActor UpdateApiActorSet: ${uapim.actorR} ")
      apiActorSet += uapim.actorR
    }

  }
}
