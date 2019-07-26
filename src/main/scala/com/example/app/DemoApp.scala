package com.example.app

import java.io.{ByteArrayInputStream, StringReader}
import java.security.Signature
import java.security.cert.CertificateFactory
import java.time.{LocalDateTime, ZoneOffset}

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import com.github.jeffgarratt.hl.fabric.sdk.Bootstrap.ChannelId
import com.github.jeffgarratt.hl.fabric.sdk._
import com.google.protobuf.ByteString
import main.app.{AppDescriptor, AppDescriptors, Query}
import monix.eval.Task
import monix.execution.Scheduler
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.util.encoders.Hex
import org.hyperledger.fabric.protos.common.common.{ChannelHeader, Header}
import org.hyperledger.fabric.protos.msp.identities.SerializedIdentity
import org.hyperledger.fabric.protos.peer.chaincode.ChaincodeSpec
import org.hyperledger.fabric.protos.peer.proposal.Proposal
import org.hyperledger.fabric.protos.peer.proposal_response.ProposalResponse
import spray.json.DefaultJsonProtocol

import scala.concurrent.Await
import scala.concurrent.duration._


case class ScoreInput(age: Int = SampleContext.getRandInt(21, 95),
                      sex: Int = 1,
                      cp: Int = 1,
                      trestbps: Int = 156,
                      chol: Int = 132,
                      fbs: Int = 125,
                      restecg: Int = 32,
                      thalach: Int = 32,
                      exang_oldpeak: Int = 32,
                      slop: Int = 32,
                      ca: Int = 32,
                      thaldur: Int = 32,
                      num: Int = 32
                     )


// 32 1 1 156  132 125 1 95  1 0 1 1 3 1
//+ score_sex(std::stoi(medData[1])) * 0.01
//+ score_cp(std::stoi(medData[2])) * 0.21
//+ score_trestbps(std::stoi(medData[3])) * 0.05
//+ score_chol(std::stoi(medData[4])) * 0.05
//+ score_fbs(std::stoi(medData[5])) * 0.04
//+ score_restecg(std::stoi(medData[6])) * 0.19
//+ score_thalach(std::stoi(medData[7])) * 0.06
//+ score_exang_oldpeak(std::stoi(medData[8])) * 0.18
//+ score_exang_oldpeak(std::stoi(medData[9])) * 0.05
//+ score_slop(std::stoi(medData[10])) * 0.03
//+ score_ca(std::stoi(medData[11])) * 0.04
//+ score_thaldur(std::stoi(medData[12])) * 0.02
//+ score_num(std::stoi(medData[13])) * 0.04 );)

trait ScoreInputJsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val scoreInputFormat = jsonFormat13(ScoreInput)
}

class DemoApp(val projectName: String = SampleContext.conf.getString("fabric.prototype.projectName"))(implicit scheduler: Scheduler) extends ScoreInputJsonSupport {


  // Workorder input format
  val sampleInputJSON =
    """{
  "jsonrpc": "2.0",
  "method": "WorkOrderSubmit",
  "id": 14,
  "params": {
    "responseTimeoutMSecs": 6000,
    "payloadFormat": "pformat",
    "resultUri": "resulturi",
    "notifyUri": "notifyuri",
    "workOrderId": "0x1234ABCD",
    "workerId": "",
    "workloadId": "0x2345",
    "requesterId": "0x3456",
    "workerEncryptionKey": "0x6789",
    "dataEncryptionAlgorithm": "AES-GCM-256",
    "encryptedSessionKey": "sessionkey",
    "sessionKeyIv": "Iv",
    "requesterNonce": "",
    "encryptedRequestHash": "requesthash",
    "requesterSignature": "",
    "inData": [
  {
    "index": 1,
    "dataHash": "dcba4444",
    "data": "Heart disease evaluation data: 32 1 1 156  132 125 1 95  1 0 1 1 3 1",
    "encryptedDataEncryptionKey": "-",
    "iv": ""
  },
  {
    "index": 0,
    "dataHash": "abcd5555",
    "data": "heart-disease-eval:",
    "encryptedDataEncryptionKey": "-",
    "iv": ""
  }
    ],
    "outData": [
  {
    "index": 0,
    "dataHash": "mhash555",
    "data": "",
    "encryptedDataEncryptionKey": "-",
    "iv": ""
  }
    ],
    "verifyingKey": ""
  }
}"""

  val ctx = SampleContext.getContext(projectName = projectName)

  val dev0Org0 = ctx.getDirectory.get.users.find(_.name == "dev0Org0").get
  val nodeAdminTuple = Directory.natsForUser(ctx.getDirectory.get, dev0Org0)(0)
  val cchExample02 = new ChaincodeHelper(ctx, dev0Org0, nodeAdminTuple, endorsers = List("peer0"))
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

  val patientCount = SampleContext.conf.getInt("app.patients.count")
  val patients = Range(0, patientCount).map(i => f"patient_${i}%03d")
  val numBusinesses = 2
  val numHospitals = 3
  val slideVal = patientCount % numBusinesses match {
    case 0 => patientCount / numBusinesses
    case _ => patientCount / numBusinesses + 1
  }
  val randomWindowedPatients = scala.util.Random.shuffle(patients).sliding(slideVal, slideVal).toList
  val peerOrg3Employees = randomWindowedPatients(0).toList
  val peerOrg4Employees = randomWindowedPatients(1).toList


  val peerOrg0 = ctx.getDirectory.get.orgs.find(_.name == "peerOrg0").get
  val peerOrg1 = ctx.getDirectory.get.orgs.find(_.name == "peerOrg1").get
  val peerOrg2 = ctx.getDirectory.get.orgs.find(_.name == "peerOrg2").get
  val peerOrg7 = ctx.getDirectory.get.orgs.find(_.name == "peerOrg7").get

  val dev0Org1 = ctx.getDirectory.get.users.find(_.name == "dev0Org1").get
  val natDev0Org1 = Directory.natsForUser(ctx.getDirectory.get, dev0Org1)(0)
  val dev0Org2 = ctx.getDirectory.get.users.find(_.name == "dev0Org2").get
  val natDev0Org2 = Directory.natsForUser(ctx.getDirectory.get, dev0Org2)(0)

  val dev0Org7 = ctx.getDirectory.get.users.find(_.name == "dev0Org7").get
  val natDev0Org7 = Directory.natsForUser(ctx.getDirectory.get, dev0Org7)(0)
  val cc = cchExample02.copy(user = dev0Org7, node_admin_tuple = natDev0Org7, endorsers = List("peer0"))
  val queryPeer0 = getQuery(Query(), cc.copy(endorsers = List("peer0")), Some(getMedicalChannelForOrg(peerOrg0)))
  val queryPeer1 = getQuery(Query(), cc.copy(endorsers = List("peer1")), Some(getMedicalChannelForOrg(peerOrg1)))
  val queryPeer2 = getQuery(Query(), cc.copy(endorsers = List("peer2")), Some(getMedicalChannelForOrg(peerOrg2)))
  val queryAllMedical = Task.gatherUnordered(List(queryPeer0, queryPeer1, queryPeer2))

  val queryPeer7 = getQuery(Query(), cc.copy(endorsers = List("peer7")), Some("com.peerorg7.blockchain.channel.worker"))


  def getLocalDateTime(proposal: Proposal) = {
    val header = Header.parseFrom(proposal.header.toByteArray)
    val channelHeader = ChannelHeader.parseFrom(header.channelHeader.toByteArray)
    LocalDateTime.ofEpochSecond(channelHeader.timestamp.get.seconds, channelHeader.timestamp.get.nanos, ZoneOffset.UTC)
  }

  /*
  * Validates the proposalResponse that represents the ability of an insurer to get medical info.
  *
  * */
  def validateSig(pr: ProposalResponse) = {
    // First verify the signature on the proposalResponse
    val certificateFactory = CertificateFactory.getInstance("X509")
    val si = SerializedIdentity.parseFrom(pr.endorsement.get.endorser.toByteArray)
    val certAsString = new String(si.idBytes.toByteArray)
    val pp = new PEMParser(new StringReader(certAsString))
    val x509DataSigner = pp.readPemObject.getContent
    val certPeerSigner = certificateFactory.generateCertificate(new ByteArrayInputStream(x509DataSigner))
    val ecdsaSign = Signature.getInstance("SHA256withECDSA", "BC")
    ecdsaSign.initVerify(certPeerSigner.getPublicKey)
    // Signature is across Payload + SerializedIdentity
    val buffer = pr.payload.toByteArray ++ si.toByteArray
    ecdsaSign.update(buffer)
    val verified = ecdsaSign.verify(pr.endorsement.get.signature.toByteArray)

    // Now get the MSP org, and verify they signed the public Key of signer
    ctx.getDirectory.get.orgs.find(o => o.name == si.mspid) match {
      case Some(mspOrg) => {
        val ppMspOrg = new PEMParser(new StringReader(mspOrg.selfSignedCert.get))
        val x509DataMspOrg = ppMspOrg.readPemObject.getContent
        val certForMspOrg = certificateFactory.generateCertificate(new ByteArrayInputStream(x509DataMspOrg))
        try {
          certPeerSigner.verify(certForMspOrg.getPublicKey)
          Right(verified)
        } catch {
          case e: Exception => {
            Left(s"The result of signature verification was ${verified}, but could NOT verify the MSP signed the signatory's certificate: ${e}")
          }
        }
      }
      case None =>
        Left(s"The result of signature verification was ${verified}, but could NOT validate proposalResponse, MSP org not found for mspID = ${si.mspid}")
    }
  }

  // Query for a value, use a factory to create query tasks
  def getQuery(query: Query, chaincodeHelper: ChaincodeHelper, channelName: Option[String] = Some(defaultChannelName)) = {
    val getInvocationSpec = Endorser.InvocationSpec(_: ChaincodeSpec, channelName = channelName, proposalResponseHandler = Some(Endorser.getHandler(AppDescriptors)))
    Task.eval({
      val irSet = Await.result(ChaincodeHelper.getTask(chaincodeHelper.send(getInvocationSpec(getChaincodeSpec(List(ByteString.copyFromUtf8("getAppDescriptors"), query.toByteString))))).runToFuture, 1.seconds)
      irSet.map(ir => (ir.interaction.endorser, ir.extractedResponse.getOrElse(AppDescriptors(descriptors = Map("UNEXPECTED RESPONSE" -> AppDescriptor(description = ir.interaction.getProposalResponse.toString)))))).toMap
    })
  }

  // Query for a value, use a factory to create query tasks
  def getQueryRaw(query: Query, chaincodeHelper: ChaincodeHelper, channelName: Option[String] = Some(defaultChannelName)) = {
    val getInvocationSpec = Endorser.InvocationSpec(_: ChaincodeSpec, channelName = channelName, proposalResponseHandler = Some(Endorser.getHandler(AppDescriptors)))
    Task.eval({
      val irSet = Await.result(ChaincodeHelper.getTask(chaincodeHelper.send(getInvocationSpec(getChaincodeSpec(List(ByteString.copyFromUtf8("getAppDescriptors"), query.toByteString))))).runToFuture, 1.seconds)
      irSet
    })
  }


  val createRequestIdTask = Task.eval {
    s"REQ-${new String(Hex.encode(Bootstrap.getNonce.toByteArray))}"
  }

  def getTask[A](ccFunc: => Either[String, List[Either[String, Endorser.Interaction[A]]]], timeout: Duration = 1 seconds) = {
    val task = Task.eval({
      val resultOfSend = ccFunc
      val interactions = resultOfSend.right.get.map(_.right.get)
      val r = Task.gather(interactions.map(i => Task.fromFuture(i.proposalResponseFuture))).runToFuture
      Await.result(r, timeout)
      interactions
    })
    task
  }

  // Invoker tasks
  def getCreateAppDescriptor(chaincodeHelper: ChaincodeHelper, user: User, channelName: ChannelId, appDescriptorKey: String, appDescriptor: AppDescriptor) = {
    val gis = Endorser.InvocationSpec(_: ChaincodeSpec, channelName = Some(channelName), proposalResponseHandler = Some(Endorser.getHandler(AppDescriptor)))
    Task.eval({
      val taskInvoker = getTask(chaincodeHelper.send(gis(getChaincodeSpec(List(ByteString.copyFromUtf8("createAppDescriptor"), ByteString.copyFromUtf8(appDescriptorKey), appDescriptor.toByteString)))))
      val interactions = Await.result(taskInvoker.runToFuture, 1 seconds)
      val irSet = interactions.map(_.getResult()).map(_.right.get)
      val signedTx = Bootstrap.createSignedTransaction(user, irSet)
      val results = (signedTx, irSet)
      results
      //      val signedTx = Bootstrap.createSignedTransaction(user, irSet).right.get
      //      signedTx
    })
  }

  def getCreateRecordInteraction(nat: NodeAdminTuple, targetPeer: String, channelId: ChannelId, key: String, value: AppDescriptor) = {
    Task.eval({
      val result = Await.result(getCreateAppDescriptor(cchExample02.copy(user = nat.user, node_admin_tuple = nat, endorsers = List(targetPeer)), nat.user, channelId, key, value).runToFuture, 1.seconds)
      result._1 match {
        case Right(signedTx) => {
          val interaction = ctx.Interaction(Task.eval(signedTx), broadcastSpecOrderer0, deliverSpecPeer0.copy(nodeName = targetPeer, signer = nat.user, nodeAdminTuple = nat, channelId = channelId))
          Right(interaction, result._2)
        }
        case Left(msg) => Left(msg, result._2)
      }
    })
  }

  def getRandomPatientGroups(patients : IndexedSeq[String] = patients, numGroups : Int = numHospitals) = {
    val slideVal = patients.size % numGroups match {
      case 0 => patients.size / numGroups
      case _ => patients.size / numGroups + 1
    }
    val randomWindowedPatients = scala.util.Random.shuffle(patients).sliding(slideVal, slideVal).toList
    randomWindowedPatients
  }

  def getSampleDataForHospitals() = {
    val randWindowedPatients = getRandomPatientGroups(patients, numHospitals)
    val result = randWindowedPatients.map(set => set.map(entry => entry -> ScoreInput()).toMap)
    result
  }

  def getAllCreateRecordInteractions() = {
    import spray.json._
    val patientToAppDescriptor = getSampleDataForHospitals().zipWithIndex.map{ case (m,index) =>
      m.map {case (k,v) => {
        val user = ctx.getDirectory.get.users.find(_.name == s"dev0Org${index}").get
        val nat = Directory.natsForUser(ctx.getDirectory.get, user)(0)
        val peerId = s"peer${index}"
        val peerOrg = ctx.getDirectory.get.orgs.find(_.name == s"peerOrg${index}").get
        val channelId = getMedicalChannelForOrg(peerOrg)
        k -> getCreateRecordInteraction(nat, peerId, channelId, k, AppDescriptor(description=v.toJson.toString))}
      }
    }
    patientToAppDescriptor
    //patientToAppDescriptor.map(m => m.map())
    // .map(m => m.map {case (k,v) => k -> AppDescriptor(description=v.toJson.toString)})

  }

}
