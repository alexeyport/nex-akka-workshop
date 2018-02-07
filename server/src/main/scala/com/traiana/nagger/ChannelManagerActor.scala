package com.traiana.nagger

import akka.actor.{ActorLogging, ActorRef}
import akka.persistence.PersistentActor
import akka.util.Timeout
import akka.pattern.{ask, pipe}

import scala.concurrent.duration._

object ChannelManagerActorReq {
  case class JoinLeveChannel(nick: String, channel: String, joinLeav: Boolean, oS: ActorRef)
  case class PostMessageReq(message: String, channel: String, nick: String, oS: ActorRef)
}

object ChannelManagerActorResp {
  case class PostMessageResp(nicks: List[String], message: String, channel: String, nick: String)
  case class JoinLeveChannelResp(nick: String, list: List[String], oS: ActorRef, channel: String)
}

object ChannelManagerEvents {
  case class JcoinLeveChannelEvt(nick: String, channel: String, joinLeav: Boolean)
  //case class
}

class ChannelManagerActor extends PersistentActor with ActorLogging {
  override def persistenceId = "ChannelManagerActor-ID-1"

  import context.dispatcher
  implicit val timeout = Timeout(3 seconds)

  val channelToRef = scala.collection.mutable.Map[String, ActorRef]()

  def updateMap(nick: String, channel: String, recoveryMode: Boolean): Unit = {
    val chA = context.actorOf(ChannelActor.props(channel), channel)
    log.info(s"----------> ChannelManagerActor added $nick to channel ${channel}  ")
    channelToRef += (channel -> chA)
    if (!recoveryMode) {
      chA ! ChannelActorReqs.JoinChannel(nick)
    }
  }

  val receiveRecover: Receive = {
    case evt: ChannelManagerEvents.JcoinLeveChannelEvt => {
      updateMap(evt.nick, evt.channel, true)
      log.info(s"===> In ChannelManagerActor, JcoinLeveChannel $evt")
    }
  }

  val receiveCommand: Receive = {
    case jlc: ChannelManagerActorReq.JoinLeveChannel => {
      channelToRef.get(jlc.channel) match {
        case Some(value) => {
          if (jlc.joinLeav) {
            log.info(s"---------->  ChannelManagerActor ${jlc.nick} joined channel ${jlc.channel}  ")
            (value ? ChannelActorReqs.JoinChannel(jlc.nick))
              .mapTo[ChannelActorResps.GetMessageListResp]
              .map(res => ChannelManagerActorResp.JoinLeveChannelResp(jlc.nick, res.list, jlc.oS, jlc.channel)).pipeTo(sender())
          } else {
            log.info(s"---------->  ChannelManagerActor ${jlc.nick} left channel ${jlc.channel}  ")
            value ! ChannelActorReqs.LeaveChannel(jlc.nick)
            jlc.oS ! ChannelManagerActorResp.JoinLeveChannelResp(jlc.nick, List.empty[String], jlc.oS, jlc.channel)
          }
        }
        case None => {
          if (jlc.joinLeav) {
            persist(ChannelManagerEvents.JcoinLeveChannelEvt(jlc.nick, jlc.channel, jlc.joinLeav)) { evt =>
              log.info(s"===> In ChannelManagerActor, persisting event: $evt")
              updateMap(jlc.nick, jlc.channel, false)
              jlc.oS ! ChannelManagerActorResp.JoinLeveChannelResp(jlc.nick, List.empty[String], jlc.oS, jlc.channel)
            }
          }
        }
      }
    }

    case pm: ChannelManagerActorReq.PostMessageReq => {
      channelToRef.get(pm.channel) match {
        case Some(value) => {
          val oS = sender()
          log.info(s"---------->  ChannelManagerActor Requesting to post message ")
          value ! ChannelActorReqs.GetNicksPerChannelReq(oS, pm.message, pm.nick)
        }
        case None => {
          log.info(s"---------->  ChannelManagerActor No Users in this channel ")
          sender() ! ChannelManagerActorResp.PostMessageResp(List.empty[String], pm.message, pm.channel, pm.nick)
        }
      }
    }

    case gnpcl: ChannelActorResps.GetNicksPerChannelResp => {
      log.info(s"---------->  ChannelManagerActor Posting messages to: ${gnpcl.oS.toString()} ")
      gnpcl.oS ! ChannelManagerActorResp.PostMessageResp(gnpcl.list, gnpcl.message, gnpcl.channel, gnpcl.nick)
    }

  }
}
