package com.traiana.nagger

import akka.actor.{Actor, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import com.google.protobuf.empty.Empty
import com.traiana.kit.boot.grpc.GrpcService
import com.traiana.nagger.spb._
import io.grpc.stub.StreamObserver
import io.grpc.{BindableService, ServerServiceDefinition}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

/**
  * Created by alexp on 1/29/18.
  */
@GrpcService
class NaggerService extends NaggerGrpc.Nagger with BindableService {
  val system   = ActorSystem("NaggerActor")
  val apiActor = system.actorOf(Props[ApiActor], "apiactor")

  implicit val timeout = Timeout(10 seconds)

  override def bindService(): ServerServiceDefinition = NaggerGrpc.bindService(this, ExecutionContext.global)

  override def register(request: RegisterRequest): Future[LoginRegisterResponse] =
    (apiActor ? request).mapTo[LoginRegisterResponse]

  override def login(request: LoginRequest): Future[LoginRegisterResponse] =
    (apiActor ? request).mapTo[LoginRegisterResponse]

  override def joinLeave(request: JoinLeaveRequest): Future[Empty] = (apiActor ? request).mapTo[Empty]

  override def sendMessage(request: MessageRequest): Future[Empty] = (apiActor ? request).mapTo[Empty]

  override def listen(request: ListenRequest, responseObserver: StreamObserver[ListenEvent]): Unit =
    apiActor ! StartListening(request, responseObserver)
}
