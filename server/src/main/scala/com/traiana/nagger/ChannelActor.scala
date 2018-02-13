package com.traiana.nagger

import akka.actor.SupervisorStrategy.Stop
import akka.actor.{ActorLogging, ActorRef, Props, ReceiveTimeout}
import akka.persistence.PersistentActor

import scala.concurrent.duration._

object ChannelActorReqs {
  final case class EntityEnvelope(id: String, payload: Any)
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

class ChannelActor extends PersistentActor with ActorLogging {
  import akka.cluster.sharding.ShardRegion.Passivate

  //context.setReceiveTimeout(120.seconds)

  override def persistenceId = s"ChannelActor-ID-${self.path.name}"
  log.info(s"************************ ChannelActor persistenceId: $persistenceId")

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
                                                            self.path.name)
      }
    }

    case ChannelActorReqs.GetMesasgeList => {
      sender() ! ChannelActorResps.GetMessageListResp(mesagesPerChannel.toList)
    }

    case ReceiveTimeout ⇒ context.parent ! Passivate(stopMessage = Stop)
    case Stop           ⇒ context.stop(self)
  }
}
