package com.traiana.nagger

import akka.actor.{Actor, ActorLogging}
import akka.persistence.PersistentActor

/**
  * Created by alexp on 1/29/18.
  */
object UserDetailsActorReq{
  case class UserDetailsReq(user: String, password: String, nickName: String)
  case class CheckUserReq(user: String, password: String)

}

object UserDetailsActorEvt {
  case class UserDetailsEvt(user: String, password: String, nickName: String)
}

class UserDetailsActor extends PersistentActor with ActorLogging {
  override def persistenceId = "UserDetailsActor-ID-1"

  val userDets = scala.collection.mutable.Map[String, UserDetailsActorReq.UserDetailsReq]()

  def updateMap(evt: UserDetailsActorEvt.UserDetailsEvt): Unit = {
    userDets += (evt.user -> UserDetailsActorReq.UserDetailsReq(evt.user, evt.password, evt.nickName))
  }

  val receiveRecover: Receive = {
    case evt: UserDetailsActorEvt.UserDetailsEvt => {
      updateMap(evt)
      log.info(s"===> In UserDetailsActor, Updating state $evt")
    }
  }

  val receiveCommand: Receive = {
    case ud: UserDetailsActorReq.UserDetailsReq => {
      log.info(s"---------->  In UserDetailsActor UserDetailsReq $ud")
      userDets.get(ud.user) match {
        case Some(_) =>
          sender() ! LoginActorResp.LoginRegFailureResp(s"${ud.user} already exists")
        case None => {
          persist(UserDetailsActorEvt.UserDetailsEvt(ud.user, ud.password, ud.nickName)) { evt =>
            log.info(s"===> In UserDetailsActor, persisting event: $evt")
            updateMap(evt)
          }
          sender() ! LoginActorResp.LoginRegSuccessResp(ud.nickName)
        }
      }
    }
    case lu: UserDetailsActorReq.CheckUserReq => {
      log.info(s"---------->  In ApiActor CheckUserReq $lu")
      userDets.get(lu.user) match {
        case Some(value) =>
          if (lu.password.equals(value.password)) {
            sender() ! LoginActorResp.LoginRegSuccessResp(value.nickName)
          } else {
            sender() ! LoginActorResp.LoginRegFailureResp(s"Invalid credentials for user: ${lu.user}")
          }
        case None => sender() ! LoginActorResp.LoginRegFailureResp(s"No such user: ${lu.user}")
      }
    }
  }
}
