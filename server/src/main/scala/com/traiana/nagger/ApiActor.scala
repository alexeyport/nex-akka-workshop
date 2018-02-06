package com.traiana.nagger

import java.time.Instant

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
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
case class RegistrationResult(origSender: ActorRef, origMessage: ResponseResult, regReq: RegisterRequest)
case class StartListening(request: ListenRequest, responseObserver: StreamObserver[ListenEvent])
case class UpdateMapN(nick: String, so: StreamObserver[ListenEvent])

class ApiActor extends Actor with ActorLogging {
  val udActor = context.actorOf(Props[UserDetailsActor], "udactor")
  val loginActor =
    context.actorOf(LoginActor.props(udActor), name = "loginactor")
  val chanManActor = context.actorOf(Props[ChannelManagerActor], "chanmanactor")

  val user2SO = scala.collection.mutable.Map[String, StreamObserver[ListenEvent]]()

  import context.dispatcher
  implicit val timeout = Timeout(3 seconds)

  def receive = {
    case rr: RegisterRequest => {
      log.info(s"----> In ApiActor RegisterRequest $rr")
      val oS = sender()
      (udActor ? UserDetailsReq(rr.username, rr.password, rr.nickname))
        .mapTo[ResponseResult]
        .map(res => RegistrationResult(oS, res, rr))
        .pipeTo(self)
    }

    case rr: RegistrationResult => {
      log.info(s"----> In ApiActor RegistrationResult $rr")
      rr.origMessage match {
        case s: LoginRegSuccessResp => {
          (loginActor ? LoginUserReq(rr.regReq.username, rr.regReq.password))
            .mapTo[ResponseResult]
            .map {
              case ls: LoginRegSuccessResp =>
                LoginRegisterResponse().withSuccess(LoginSuccess().withToken(ls.res))
              case lf: LoginRegFailureResp =>
                LoginRegisterResponse().withFailure(LoginFailure(lf.message))
            }
            .pipeTo(rr.origSender)
        }
        case f: LoginRegFailureResp => rr.origSender ! f
      }
    }

    case lr: LoginRequest => {
      log.info(s"----> In ApiActor LoginRequest $lr")
      (loginActor ? LoginUserReq(lr.username, lr.password))
        .mapTo[ResponseResult]
        .map {
          case ls: LoginRegSuccessResp => {
            log.info(ls.res)
            LoginRegisterResponse().withSuccess(LoginSuccess().withToken(ls.res))
          }
          case lf: LoginRegFailureResp => {
            log.error(lf.message)
            LoginRegisterResponse().withFailure(LoginFailure(lf.message))
          }
        }
        .pipeTo(sender())
    }

    case jlr: JoinLeaveRequest => {
      log.info(s"----> In ApiActor JoinLeaveRequest ${jlr.joinNotLeave} + ${jlr.channel}")
      val oS = sender()
      (loginActor ? GetUserNickByToken(jlr.token))
        .mapTo[ResponseResult]
        .map {
          case ls: LoginRegSuccessResp => {
            log.info(s"----> In ApiActor JoinLeaveRequest ${ls.res} - ${jlr.channel} - ${jlr.joinNotLeave}")
            chanManActor ! JoinLeveChannel(ls.res, jlr.channel, jlr.joinNotLeave)
            oS ! Empty
          }
          case lf: LoginRegFailureResp => {
            log.error(lf.message)
            oS ! Empty
          }
        }
    }

    case mr: MessageRequest => {
      log.info(s"----> In ApiActor MessageRequest ${mr.token} + ${mr.channel}")

      val oS = sender()
      (loginActor ? GetUserNickByToken(mr.token))
        .mapTo[ResponseResult]
        .map {
          case ls: LoginRegSuccessResp => {
            log.info(s"----> In ApiActor MessageRequest ${ls.res}")
            chanManActor ! PostMessageReq(mr.message, mr.channel, ls.res)
            oS ! Empty
          }
          case lf: LoginRegFailureResp => {
            log.error(lf.message)
            oS ! Empty
          }
        }
    }

    case pmr: PostMessageResp => {
      pmr.nicks.foreach { res =>
        val tt = Instant.now
        val le = new ListenEvent(pmr.channel,
                                 pmr.nick,
                                 pmr.message,
                                 Some(Timestamp().withSeconds(tt.getEpochSecond).withNanos(tt.getNano)))
        user2SO.get(res) match {
          case Some(value) => value.onNext(le)
          case None        => Nil
        }
      }
    }

    case lr: StartListening => {
      (loginActor ? GetUserNickByToken(lr.request.token))
        .mapTo[ResponseResult]
        .map {
          case ls: LoginRegSuccessResp => {
            log.info(s"--> Adding mapping between Nick ${ls.res} and ${lr.responseObserver}")
            self ! UpdateMapN(ls.res, lr.responseObserver)
          }
          case lf: LoginRegFailureResp => {
            log.error(lf.message)
          }
        }
    }

    case umn: UpdateMapN => {
      user2SO += (umn.nick -> umn.so)
    }
  }

}
