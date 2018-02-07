package com.traiana.nagger

import java.util.UUID

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.duration._

/**
  * Created by alexp on 1/29/18.
  */
object LoginActorReq{
  case class LoginUserReq(user: String, password: String)
  case class GetUserNickByToken(token: String)

}

object LoginActorResp {
  trait ResponseResult
  case class LoginRegSuccessResp(res: String)     extends ResponseResult
  case class LoginRegFailureResp(message: String) extends ResponseResult
}

case class UpdateMap(originalSender: ActorRef, nick: String)

object LoginActor {
  def props(udActor: ActorRef): Props = Props(new LoginActor(udActor))
}
class LoginActor(udActor: ActorRef) extends Actor with ActorLogging {
  val userDets = scala.collection.mutable.Map[String, String]()

  import context.dispatcher
  implicit val timeout = Timeout(10 seconds)

  def receive = {
    case lin: LoginActorReq.LoginUserReq =>
      log.info(s"---------->  In LoginActor $lin")
      val oS = sender()
      (udActor ? UserDetailsActorReq.CheckUserReq(lin.user, lin.password))
        .mapTo[LoginActorResp.ResponseResult]
        .map {
          case ls: LoginActorResp.LoginRegSuccessResp => self ! UpdateMap(oS, ls.res)
          case lf: LoginActorResp.LoginRegFailureResp => oS ! lf
        }

    case um: UpdateMap => {
      val token = UUID.randomUUID().toString
      userDets += (token -> um.nick) //in this case res is a token
      um.originalSender ! LoginActorResp.LoginRegSuccessResp(token)
    }

    case gunbt: LoginActorReq.GetUserNickByToken =>
      userDets.get(gunbt.token) match {
        case Some(value) => sender() ! LoginActorResp.LoginRegSuccessResp(value)
        case None        => sender() ! LoginActorResp.LoginRegFailureResp("No user for this token")
      }

  }
}
