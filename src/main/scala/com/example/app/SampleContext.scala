package com.example.app

import com.github.jeffgarratt.hl.fabric.sdk.{Context, Directory, Network, Port}
import com.github.jeffgarratt.hl.fabric.sdk.Directory.Cert

class SampleContext extends Context {
  override def getDirectory: Option[Directory] = None

  val network = new Network

  override def getNetwork: Network = network

  override def getPortHostMapping(serviceName: String, port: Int): Either[String, Port] = Left("Not Implemented")

  override def getTrustedRootsForPeerNetworkAsPEM: List[Cert] = List[Cert]()
}