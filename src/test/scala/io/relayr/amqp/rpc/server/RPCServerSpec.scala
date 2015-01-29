package io.relayr.amqp.rpc.server

import java.util.concurrent.Executor

import com.rabbitmq.client.BasicProperties
import io.relayr.amqp._
import org.scalamock.scalatest.MockFactory
import org.scalatest.{ FlatSpec, Matchers }

import scala.concurrent.{ ExecutionContext, ExecutionContextExecutor, Future, Promise }

class RPCServerSpec extends FlatSpec with Matchers with MockFactory {
  val channelOwner = mock[ChannelOwner]
  val handler = mockFunction[Message, Future[Message]]
  val queue = QueuePassive("queue name")

  implicit val synchronousExecutor: ExecutionContextExecutor = ExecutionContext.fromExecutor(new Executor() {
    override def execute(command: Runnable): Unit = command.run()
  })

  "RPCServer" should "register a consumer" in {
    (channelOwner.addConsumer(_: Queue, _: Delivery ⇒ Unit)) expects (queue, *)
    new RPCServerImpl(channelOwner, queue, synchronousExecutor, handler)
  }

  it should "call the handler when a message comes in on the listener and correctly reply with the response" in {
    val replyChannel: String = "reply channel"
    val correlationId: String = "correlation id"
    var consumer: Delivery ⇒ Unit = null
    (channelOwner.addConsumer(_: Queue, _: Delivery ⇒ Unit)) expects (queue, *) onCall { (_, _consumer) ⇒
      consumer = _consumer
      mock[Closeable]
    }
    val rpcServer = new RPCServerImpl(channelOwner, queue, synchronousExecutor, handler)

    // when a message is relivered it should trigger the handler
    val delivery = mock[Delivery]
    val msg: Message = Message.String("string")
    delivery.correlationId _ expects () returning correlationId
    delivery.replyTo _ expects () returning replyChannel
    delivery.message _ expects () returning msg
    val resultPromise = Promise[Message]()
    handler expects msg returning resultPromise.future
    consumer(delivery)

    // when the handler's result is completed, the reply shouldbe sent
    channelOwner.send _ expects (Exchange.Default.route(replyChannel, DeliveryMode.NotPersistent), msg, *) onCall {
      (RoutingDescriptor, Message, props: BasicProperties) ⇒
        assert(props.getCorrelationId == correlationId)
    }
    resultPromise.success(msg)
  }

  it should "create a new queue if given a queue declare"
}
