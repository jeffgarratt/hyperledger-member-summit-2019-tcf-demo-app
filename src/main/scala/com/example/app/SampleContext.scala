package com.example.app

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

import com.github.jeffgarratt.hl.fabric.sdk.Directory.Cert
import com.github.jeffgarratt.hl.fabric.sdk._
import com.typesafe.config.ConfigFactory
import io.grpc.ManagedChannel
import io.grpc.internal.DnsNameResolverProvider

import scala.collection.concurrent.TrieMap
import scala.util.Random

class MyNetwork extends Network {

  override def getGRPCChannel(ipAddress: String, port: Int, root_certificates: List[Cert], ssl_target_name_override: String): ManagedChannel = {
    // Open a channel and get the AdminGrpc stub and check status
    import io.grpc.netty.{GrpcSslContexts, NettyChannelBuilder}
    val certsAsPEMInputStream = new ByteArrayInputStream(root_certificates.mkString("\n").getBytes(StandardCharsets.UTF_8))
    val channel = NettyChannelBuilder.forAddress(ipAddress, port).nameResolverFactory(new DnsNameResolverProvider()).overrideAuthority(ssl_target_name_override).sslContext(GrpcSslContexts.forClient.trustManager(certsAsPEMInputStream).build).build
    scribe.debug("inside MyNetwork!!!!!")
    channel
  }
}


class SampleContext(override val projectName: String, override val rootPath: Seq[String]) extends LocalDockerContext(projectName = projectName, rootPath = rootPath) {

  val myNetwork = new MyNetwork()

  override def getNetwork: Network = {
    SampleContext.conf.getString("app.context.network.selected") match{
      case (s : String) if (s == SampleContext.conf.getString("app.context.network.option.myNetwork")) => myNetwork
      case _ => network
    }
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