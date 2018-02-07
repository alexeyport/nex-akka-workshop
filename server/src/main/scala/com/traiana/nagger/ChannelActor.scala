package com.traiana.nagger

import akka.actor.{ActorLogging, ActorRef, Props}
import akka.persistence.PersistentActor

object ChannelActorReqs {
  case class JoinChannel(nick: String)
  case class LeaveChannel(nick: String)
  case class GetNicksPerChannelReq(oS: ActorRef, message: String, nick: String)
  case object GetMesasgeList
}

object ChannelActorEvts {
  trait JoinLeaveEvntInt
  case class JoinChannelEvt(nick: String)     extends JoinLeaveEvntInt
  case class LeaveChannelEvt(nick: String)    extends JoinLeaveEvntInt
  case class StoreMessageEvt(message: String) extends JoinLeaveEvntInt
}

object ChannelActorResps {
  case class GetNicksPerChannelResp(list: List[String], oS: ActorRef, message: String, nick: String, channel: String)
  case class GetMessageListResp(list: List[String])
}

object ChannelActor {
  def props(acName: String): Props = Props(new ChannelActor(acName))
}
class ChannelActor(acName: String) extends PersistentActor with ActorLogging {
  override def persistenceId = s"ChannelActor-ID-$acName"

  val usersPerChannel   = scala.collection.mutable.ListBuffer[String]()
  val mesagesPerChannel = scala.collection.mutable.ListBuffer[String]()

  def updateState(jlE: ChannelActorEvts.JoinLeaveEvntInt): Unit = {
    jlE match {
      case ChannelActorEvts.JoinChannelEvt(nick)     => usersPerChannel += nick
      case ChannelActorEvts.LeaveChannelEvt(nick)    => usersPerChannel -= nick
      case ChannelActorEvts.StoreMessageEvt(message) => mesagesPerChannel += message
      case _                                         =>
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
    case evt: ChannelActorEvts.StoreMessageEvt => {
      updateState(evt)
      log.info(s"===> In ChannelActor, StoreMessageEvt $evt")
    }
  }

  val receiveCommand: Receive = {
    case jc: ChannelActorReqs.JoinChannel => {
      persist(ChannelActorEvts.JoinChannelEvt(jc.nick)) { evt =>
        log.info(s"===> In ChannelActor, persisting event: $evt")
        updateState(evt)
        sender() ! ChannelActorResps.GetMessageListResp(mesagesPerChannel.toList)
      }
    }

    case lc: ChannelActorReqs.LeaveChannel => {
      persist(ChannelActorEvts.LeaveChannelEvt(lc.nick)) { evt =>
        log.info(s"===> In ChannelActor, persisting event: $evt")
        updateState(evt)
      }
    }

    case jnpc: ChannelActorReqs.GetNicksPerChannelReq => {
      persist(ChannelActorEvts.StoreMessageEvt(jnpc.message)) { evt =>
        log.info(s"==> ChannelActor persisting and adding message to the queue ${jnpc.message} ")
        mesagesPerChannel += jnpc.message
        sender() ! ChannelActorResps.GetNicksPerChannelResp(usersPerChannel.toList,
                                                            jnpc.oS,
                                                            jnpc.message,
                                                            jnpc.nick,
                                                            acName)
      }
    }

    case ChannelActorReqs.GetMesasgeList =>{
      sender() ! ChannelActorResps.GetMessageListResp(mesagesPerChannel.toList)
    }

  }
}
