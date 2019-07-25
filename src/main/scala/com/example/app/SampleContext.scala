package com.example.app

import com.github.jeffgarratt.hl.fabric.sdk._
import com.github.jeffgarratt.hl.fabric.sdk.Directory.Cert
import com.google.protobuf.ByteString
import com.typesafe.config.ConfigFactory
import main.app.AppDescriptors
import org.hyperledger.fabric.protos.peer.chaincode.ChaincodeSpec

import scala.collection.concurrent.TrieMap
import scala.util.Random

class SampleContext(override val projectName: String, override val rootPath: Seq[String]) extends LocalDockerContext(projectName = projectName, rootPath = rootPath) {


  val dev0Org0 = getDirectory.get.users.find(_.name == "dev0Org0").get
  val nodeAdminTuple = Directory.natsForUser(getDirectory.get, dev0Org0)(0)
  val cchExample02 = new ChaincodeHelper(this, dev0Org0, nodeAdminTuple, endorsers = List("peer0"))
  // partial func helpers for building chaincode specs and invocation specs
  val defaultChannelName = "com.peerorg0.blockchain.channel.medical"
  val getChaincodeSpec = Endorser.getChaincodeSpec(chaincodeType = ChaincodeSpec.Type.GOLANG, path = "github.com/hyperledger/fabric/examples/chaincode/go/marketplace/app_mgr", name = "appmgr", _: List[ByteString], version = "1.0")
  val getInvocationSpec = Endorser.InvocationSpec(_: ChaincodeSpec, channelName = Some(defaultChannelName), proposalResponseHandler = Some(Endorser.getHandler(AppDescriptors)))

  val deliverSpecOrderer0 = Deliver.DeliverSpec(dev0Org0, nodeAdminTuple, defaultChannelName, "orderer0", port = 7050, seekInfo = Deliver.seekInfoAllAndWait, timeout = 10 minutes)
  val deliverSpecPeer0 = Deliver.DeliverSpec(dev0Org0, nodeAdminTuple, defaultChannelName, "peer0", port = 7051, seekInfo = Deliver.seekInfoAllAndWait, timeout = 10 minutes)
  val broadcastSpecOrderer0 = Orderer.BroadcastSpec(nodeName = "orderer0", timeout = 10 minutes)

  def getMedicalChannelForOrg(medicalOrg: Organization) = {
    s"com.${medicalOrg.name.toLowerCase()}.blockchain.channel.medical"
  }

}


object SampleContext {

  val conf = ConfigFactory.load()

  private val contextMap = TrieMap.empty[String, Context]

  def getContext(projectName: String) = {
    contextMap.getOrElseUpdate(projectName, {
      val fabricPrototypePath = conf.getString("fabric.prototype.path")
      new SampleContext(projectName = projectName, rootPath = fabricPrototypePath.split("/") ++ Seq("tmp"))
    })
  }

  val rand = Random

  def getRandInt(min: Int, max: Int) = {
    var num = -1
    while (num < min) {
      num = rand.nextInt(max)
    }
    num
  }

}