package com.traiana.nagger

import java.time.Instant

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.cluster.singleton.{ClusterSingletonProxy, ClusterSingletonProxySettings}
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import com.google.protobuf.empty.Empty
import com.google.protobuf.timestamp.Timestamp
import com.traiana.nagger.spb._
import io.grpc.stub.StreamObserver

import scala.concurrent.duration._

/**
  * Created by alexp on 1/29/18.
  */
object ApiActorReqs {
  case class RegistrationResult(origSender: ActorRef,
                                origMessage: LoginActorResp.ResponseResult,
                                regReq: RegisterRequest)
  case class StartListening(request: ListenRequest, responseObserver: StreamObserver[ListenEvent])
  case class UpdateMapN(nick: String, so: StreamObserver[ListenEvent])

}

class ApiActor extends Actor with ActorLogging {

  val proxyUserDet = context.actorOf(ClusterSingletonProxy.props(singletonManagerPath = "/user/udactor",
                                                                 settings =
                                                                   ClusterSingletonProxySettings(context.system)),
                                     name = "proxyUserDet")

  val proxyChanMan = context.actorOf(ClusterSingletonProxy.props(singletonManagerPath = "/user/chanmanactor",
                                                                 settings =
                                                                   ClusterSingletonProxySettings(context.system)),
                                     name = "proxyChanMan")

  proxyChanMan ! ChannelManagerActorReq.UpdateApiActorSet(self)

  val loginActor =
    context.actorOf(LoginActor.props(proxyUserDet), name = "loginactor")

  val user2SO = scala.collection.mutable.Map[String, StreamObserver[ListenEvent]]()

  import context.dispatcher
  implicit val timeout = Timeout(3 seconds)

  def receive = {
    case rr: RegisterRequest => {
      log.info(s"----------> In ApiActor RegisterRequest $rr")
      val oS = sender()
      (proxyUserDet ? UserDetailsActorReq.UserDetailsReq(rr.username, rr.password, rr.nickname))
        .mapTo[LoginActorResp.ResponseResult]
        .map(res => ApiActorReqs.RegistrationResult(oS, res, rr))
        .pipeTo(self)
    }

    case rr: ApiActorReqs.RegistrationResult => {
      log.info(s"----------> In ApiActor RegistrationResult $rr")
      rr.origMessage match {
        case s: LoginActorResp.LoginRegSuccessResp => {
          (loginActor ? LoginActorReq.LoginUserReq(rr.regReq.username, rr.regReq.password))
            .mapTo[LoginActorResp.ResponseResult]
            .map {
              case ls: LoginActorResp.LoginRegSuccessResp =>
                LoginRegisterResponse().withSuccess(LoginSuccess().withToken(ls.res))
              case lf: LoginActorResp.LoginRegFailureResp =>
                LoginRegisterResponse().withFailure(LoginFailure(lf.message))
            }
            .pipeTo(rr.origSender)
        }
        case f: LoginActorResp.LoginRegFailureResp => rr.origSender ! f
      }
    }

    case lr: LoginRequest => {
      log.info(s"----------> In ApiActor LoginRequest $lr")
      (loginActor ? LoginActorReq.LoginUserReq(lr.username, lr.password))
        .mapTo[LoginActorResp.ResponseResult]
        .map {
          case ls: LoginActorResp.LoginRegSuccessResp => {
            log.info(ls.res)
            LoginRegisterResponse().withSuccess(LoginSuccess().withToken(ls.res))
          }
          case lf: LoginActorResp.LoginRegFailureResp => {
            log.error(lf.message)
            LoginRegisterResponse().withFailure(LoginFailure(lf.message))
          }
        }
        .pipeTo(sender())
    }

    case jlr: JoinLeaveRequest => {
      log.info(s"----------> In ApiActor JoinLeaveRequest ${jlr.joinNotLeave} + ${jlr.channel}")
      val oS = sender()
      (loginActor ? LoginActorReq.GetUserNickByToken(jlr.token))
        .mapTo[LoginActorResp.ResponseResult]
        .map {
          case ls: LoginActorResp.LoginRegSuccessResp => {
            log.info(s"----------> In ApiActor JoinLeaveRequest ${ls.res} - ${jlr.channel} - ${jlr.joinNotLeave}")
            proxyChanMan ! ChannelManagerActorReq.JoinLeveChannel(ls.res, jlr.channel, jlr.joinNotLeave, oS)
          }
          case lf: LoginActorResp.LoginRegFailureResp => {
            log.error(lf.message)
            oS ! Empty
          }
        }
    }

    // Send to my self after join in order to get the history for a channel if exists
    case jlr: ChannelManagerActorResp.JoinLeveChannelResp => {
      log.info(s"----------> In ApiActor ChannelManagerActorResp updating history for nick : ${jlr.nick}")
      jlr.list.foreach(res => updateSO(res, jlr.channel, jlr.nick, jlr.nick))
      jlr.oS ! Empty
    }

    case mr: MessageRequest => {
      log.info(s"----------> In ApiActor MessageRequest ${mr.token} + ${mr.channel}")

      val oS = sender()
      (loginActor ? LoginActorReq.GetUserNickByToken(mr.token))
        .mapTo[LoginActorResp.ResponseResult]
        .map {
          case ls: LoginActorResp.LoginRegSuccessResp => {
            log.info(s"----------> In ApiActor MessageRequest ${ls.res}")
            proxyChanMan ! ChannelManagerActorReq.PostMessageReq(mr.message, mr.channel, ls.res, oS)
            oS ! Empty
          }
          case lf: LoginActorResp.LoginRegFailureResp => {
            log.error(lf.message)
            oS ! Empty
          }
        }
    }

    case pmr: ChannelManagerActorResp.PostMessageResp => {
      pmr.nicks.foreach(res => updateSO(pmr.message, pmr.channel, res, pmr.nick))
    }

    case lr: ApiActorReqs.StartListening => {
      (loginActor ? LoginActorReq.GetUserNickByToken(lr.request.token))
        .mapTo[LoginActorResp.ResponseResult]
        .map {
          case ls: LoginActorResp.LoginRegSuccessResp => {
            log.info(s"---------->  Adding mapping between Nick ${ls.res} and ${lr.responseObserver}")
            self ! ApiActorReqs.UpdateMapN(ls.res, lr.responseObserver)
          }
          case lf: LoginActorResp.LoginRegFailureResp => {
            log.error(lf.message)
          }
        }
    }

    case umn: ApiActorReqs.UpdateMapN => {
      user2SO += (umn.nick -> umn.so)
    }
  }

  def updateSO(message: String, channel: String, nick: String, sender: String): Unit = {
    val tt = Instant.now
    val le =
      new ListenEvent(channel, sender, message, Some(Timestamp().withSeconds(tt.getEpochSecond).withNanos(tt.getNano)))

    user2SO.get(nick) match {
      case Some(value) => value.onNext(le)
      case None        => Nil
    }
  }

}
