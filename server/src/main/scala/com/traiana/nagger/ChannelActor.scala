package com.traiana.nagger

import java.util.UUID

import akka.actor.{ActorLogging, Props}
import akka.persistence.PersistentActor

case class JoinChannel(nick: String)
case class LeaveChannel(nick: String)
case object GetNicksPerChannelReq

object ChannelActorEvts {
  trait JoinLeaveEvntInt
  case class JoinChannelEvt(nick: String)  extends JoinLeaveEvntInt
  case class LeaveChannelEvt(nick: String) extends JoinLeaveEvntInt
}

case class GetNicksPerChannelResp(list: List[String])

object ChannelActor {
  def props(acName: String): Props = Props(new ChannelActor(acName))
}
class ChannelActor(acName: String) extends PersistentActor with ActorLogging {
  override def persistenceId = s"ChannelActor-ID-$acName"

  val usersPerChannel = scala.collection.mutable.ListBuffer[String]()

  def updateState(jlE: ChannelActorEvts.JoinLeaveEvntInt): Unit = {
    jlE match {
      case ChannelActorEvts.JoinChannelEvt(nick)  => usersPerChannel += nick
      case ChannelActorEvts.LeaveChannelEvt(nick) => usersPerChannel -= nick
      case _                                      =>
    }
  }

  val receiveRecover: Receive = {
    case evt: ChannelActorEvts.JoinChannelEvt => {
      updateState(evt)
      log.info(s"===> In ChannelActor, JoinChannelEvt $evt")
    }
    case evt: ChannelActorEvts.LeaveChannelEvt => {
      updateState(evt)
      log.info(s"===> In ChannelActor, LeaveChannelEvt $evt")
    }
  }

  val receiveCommand: Receive = {
    case jc: JoinChannel => {
      persist(ChannelActorEvts.JoinChannelEvt(jc.nick)) { evt =>
        log.info(s"===> In ChannelActor, persisting event: $evt")
        updateState(evt)
      }
    }
    case lc: LeaveChannel => {
      persist(ChannelActorEvts.LeaveChannelEvt(lc.nick)) { evt =>
        log.info(s"===> In ChannelActor, persisting event: $evt")
        updateState(evt)
      }
    }

    case GetNicksPerChannelReq => sender() ! GetNicksPerChannelResp(usersPerChannel.toList)
  }
}
