package com.traiana.nagger

import akka.actor.{Actor, ActorLogging}
import akka.persistence.PersistentActor

/**
  * Created by alexp on 1/29/18.
  */
case class UserDetailsReq(user: String, password: String, nickName: String)
case class UserDetailsEvt(user: String, password: String, nickName: String)
case class CheckUserReq(user: String, password: String)

class UserDetailsActor extends PersistentActor with ActorLogging {
  override def persistenceId = "UserDetailsActor-ID-1"

  val userDets = scala.collection.mutable.Map[String, UserDetailsReq]()

  def updateMap(evt: UserDetailsEvt): Unit = {
    userDets += (evt.user -> UserDetailsReq(evt.user, evt.password, evt.nickName))
  }

  val receiveRecover: Receive = {
    case evt: UserDetailsEvt => {
      updateMap(evt)
      log.info(s"===> In UserDetailsActor, Updating state $evt")
    }
  }

  val receiveCommand: Receive = {
    case ud: UserDetailsReq => {
      log.info(s"----> In UserDetailsActor UserDetailsReq $ud")
      userDets.get(ud.user) match {
        case Some(_) =>
          sender() ! LoginRegFailureResp(s"${ud.user} already exists")
        case None => {
          persist(UserDetailsEvt(ud.user, ud.password, ud.nickName)) { evt =>
            log.info(s"===> In UserDetailsActor, persisting event: $evt")
            updateMap(evt)
          }
          sender() ! LoginRegSuccessResp(ud.nickName)
        }
      }
    }
    case lu: CheckUserReq => {
      log.info(s"----> In ApiActor CheckUserReq $lu")
      userDets.get(lu.user) match {
        case Some(value) =>
          if (lu.password.equals(value.password)) {
            sender() ! LoginRegSuccessResp(value.nickName)
          } else {
            sender() ! LoginRegFailureResp(s"Invalid credentials for user: ${lu.user}")
          }
        case None => sender() ! LoginRegFailureResp(s"No such user: ${lu.user}")
      }
    }
  }
}
