package com.traiana.nagger

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.pattern.{ask, pipe}
import akka.persistence.PersistentActor
import akka.util.Timeout

import scala.concurrent.duration._

case class JoinLeveChannel(nick: String, channel: String, joinLeav: Boolean)
case class PostMessageReq(message: String, channel: String, nick: String)
case class PostMessageResp(nicks: List[String], message: String, channel: String, nick: String)

object ChannelManagerEvents {
  case class JcoinLeveChannelEvt(nick: String, channel: String, joinLeav: Boolean)
  //case class
}

class ChannelManagerActor extends PersistentActor with ActorLogging {
  override def persistenceId = "ChannelManagerActor-ID-1"

  val channelToRef = scala.collection.mutable.Map[String, ActorRef]()

  def updateMap(nick: String, channel: String, recoveryMode: Boolean): Unit = {
    val chA = context.actorOf(ChannelActor.props(channel), channel)
    log.info(s"--> ChannelManagerActor added $nick to channel ${channel}  ")
    channelToRef += (channel -> chA)
    if (!recoveryMode) {
      chA ! JoinChannel(nick)
    }
  }

  import context.dispatcher
  implicit val timeout = Timeout(10 seconds)

  val receiveRecover: Receive = {
    case evt: ChannelManagerEvents.JcoinLeveChannelEvt => {
      updateMap(evt.nick, evt.channel, true)
      log.info(s"===> In ChannelManagerActor, JcoinLeveChannel $evt")
    }
  }

  val receiveCommand: Receive = {
    case jlc: JoinLeveChannel => {
      channelToRef.get(jlc.channel) match {
        case Some(value) => {
          if (jlc.joinLeav) {
            log.info(s"--> ChannelManagerActor ${jlc.nick} joined channel ${jlc.channel}  ")
            value ! JoinChannel(jlc.nick)
          } else {
            log.info(s"--> ChannelManagerActor ${jlc.nick} left channel ${jlc.channel}  ")
            value ! LeaveChannel(jlc.nick)
          }
        }
        case None => {
          if (jlc.joinLeav) {
            persist(ChannelManagerEvents.JcoinLeveChannelEvt(jlc.nick, jlc.channel, jlc.joinLeav)) { evt =>
              log.info(s"===> In ChannelManagerActor, persisting event: $evt")
              updateMap(jlc.nick, jlc.channel, false)
            }
          }
        }
      }
    }

    case pm: PostMessageReq => {
      channelToRef.get(pm.channel) match {
        case Some(value) => {
          log.info(s"--> ChannelManagerActor Posting messages to: ${channelToRef.toString()} ")
          (value ? GetNicksPerChannelReq)
            .mapTo[GetNicksPerChannelResp]
            .map(res => PostMessageResp(res.list, pm.message, pm.channel, pm.nick))
            .pipeTo(sender())
        }
        case None => {
          log.info(s"--> ChannelManagerActor No Users in this channel ")
          sender() ! PostMessageResp(List.empty[String], pm.message, pm.channel, pm.nick)
        }
      }
    }
  }
}
