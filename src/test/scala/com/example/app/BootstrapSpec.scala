package com.example.app

import com.github.jeffgarratt.hl.fabric.sdk._
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.{AppendedClues, FunSpec, GivenWhenThen}

import scala.concurrent.Await
import scala.concurrent.duration._

class BootstrapSpec(projectName: String) extends FunSpec with GivenWhenThen with AppendedClues {

  val demoApp = new DemoApp(projectName = projectName)
  val ctx = demoApp.ctx

  def retryWithDelay[A](t: Task[A], delay: FiniteDuration, restarts: Int) =
    t.onErrorFallbackTo(t.delayExecution(delay).onErrorRestart(restarts))

  describe("Query and deliver on a sample blockchain") {

    it("should support basic querying") {

      Given(s"I have a user ${demoApp.dev0Org0.name}")

      When("user queries for 'a'")
      val queryAResult = retryWithDelay(demoApp.queryPeer0,.2 seconds, 5).runToFuture
      Await.result(queryAResult, 3 seconds)

      Then("the result should be success")
      assert(queryAResult.value.get.isSuccess) withClue (queryAResult.value.get)

    }

    it("should support deliver on orderer0") {

      Given(s"I have a user ${demoApp.dev0Org0.name}")

      When(s"user ${demoApp.dev0Org0.name} requests deliver")
      val deliveryFromOrderer0 = ctx.getDeliveryFromOrderer(demoApp.deliverSpecOrderer0)

      Then("the last block should be available")
      val observable = deliveryFromOrderer0.getEnvelopeWrappers(10)
      Await.result(retryWithDelay(observable.lastL,.2 seconds, 5).runToFuture, 1 seconds)
    }

    it("should support deliver on peer0") {

      Given(s"I have a user ${demoApp.dev0Org0.name}")

      When(s"user ${demoApp.dev0Org0.name} requests deliver on Peer0")
      val delivery = ctx.getDeliveryFromPeer(demoApp.deliverSpecPeer0)

      Then("the last block should be available")
      val observable = delivery.getEnvelopeWrappers(10)
      Await.result(retryWithDelay(observable.lastL,.2 seconds, 5).runToFuture, 1 seconds)
    }

  }

//  describe("Invocation on sample blockchain") {
//
//    it("should support non-memoized interaction") {
//
//      Given(s"I have a user ${demoApp.dev0Org0.name}")
//
//      When("user invokes the transfer operation from a to b of value 10")
//
//      val fullTxTask = ctx.getFullTxTask(getInvoker(cchExample02, dev0Org0, "a", "b", "10"), broadcastSpecOrderer0, deliverSpecPeer0)
//      val result = fullTxTask.runToFuture
//      Await.result(result, 3 seconds)
//
//      Then("the result should be success")
//      assert(result.value.get.isSuccess) withClue (result.value.get)
//
//      And("the committed transaction should be valid")
//      val envWrapper = result.value.get.get._3
//      assert(envWrapper.validationCode.get.isValid) withClue (envWrapper.validationCode.get)
//
//    }

//    it("should support memoized interaction") {
//
//      Given(s"I have a user ${dev0Org0.name}")
//
//      When("user invokes the transfer operation from b to a of value 10")
//      val requestId = Await.result(demoApp.createRequestIdTask.runToFuture, 1.seconds)
//      val createAppDescriptorTask = getCreateAppDescriptor(cchExample02, dev0Org0, getMedicalChannelForOrg(peerOrg0), requestId, AppDescriptor(description = "105"))
//      val interaction = ctx.Interaction(getInvoker(cchExample02, dev0Org0, "b", "a", "10"), broadcastSpecOrderer0, deliverSpecPeer0)
//      val result = interaction.fullTxTask.runToFuture
//      Await.result(result, 3 seconds)
//
//      Then("the result should be success")
//      assert(result.value.get.isSuccess) withClue (result.value.get)
//
//      And("the committed transaction should be valid")
//      val envWrapper = result.value.get.get._3
//      assert(envWrapper.validationCode.get.isValid) withClue (envWrapper.validationCode.get)
//
//    }
//   }

  describe("CSSC System chaincode interaction") {

    it("should support invocation of channel list from peer0 for user dev0Org0") {
      Given(s"I have a user ${demoApp.dev0Org0.name}")
      val channelId = demoApp.getMedicalChannelForOrg(demoApp.peerOrg0)
      val csscHelper = new CsccHelper(ctx, demoApp.dev0Org0, demoApp.nodeAdminTuple, targetChannelName = channelId, endorsers = List("peer0"))

      When("user invokes the channel list operation")
      val irSet = Await.result(ChaincodeHelper.getTask(csscHelper.getChannelList).runToFuture, 1.seconds)
      val results = irSet.map(ir => (ir.interaction.endorser, ir.extractedResponse.get)).toMap

      Then(s"peer0 should contain the channel -> '${channelId}'")
      assert(results("peer0").channels.find(_.channelId == channelId).size > 0) withClue (results("peer0").channels.size)

    }
  }


}

