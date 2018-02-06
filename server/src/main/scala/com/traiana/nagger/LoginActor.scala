package com.traiana.nagger

import java.util.UUID

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.duration._

/**
  * Created by alexp on 1/29/18.
  */
case class LoginUserReq(user: String, password: String)
case class GetUserNickByToken(token: String)

trait ResponseResult
case class LoginRegSuccessResp(res: String)     extends ResponseResult
case class LoginRegFailureResp(message: String) extends ResponseResult

case class UpdateMap(originalSender: ActorRef, nick: String)

object LoginActor {
  def props(udActor: ActorRef): Props = Props(new LoginActor(udActor))
}
class LoginActor(udActor: ActorRef) extends Actor with ActorLogging {
  val userDets = scala.collection.mutable.Map[String, String]()

  import context.dispatcher
  implicit val timeout = Timeout(10 seconds)

  def receive = {
    case lin: LoginUserReq =>
      log.info(s"----> In LoginActor $lin")
      val oS = sender()
      (udActor ? CheckUserReq(lin.user, lin.password))
        .mapTo[ResponseResult]
        .map {
          case ls: LoginRegSuccessResp => self ! UpdateMap(oS, ls.res)
          case lf: LoginRegFailureResp => oS ! lf
        }

    case um: UpdateMap => {
      val token = UUID.randomUUID().toString
      userDets += (token -> um.nick) //in this case res is a token
      um.originalSender ! LoginRegSuccessResp(token)
    }

    case gunbt: GetUserNickByToken =>
      userDets.get(gunbt.token) match {
        case Some(value) => sender() ! LoginRegSuccessResp(value)
        case None        => sender() ! LoginRegFailureResp("No user for this token")
      }

  }
}
