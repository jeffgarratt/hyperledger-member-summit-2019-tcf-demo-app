package com.example.app

import com.github.jeffgarratt.hl.fabric.sdk._
import com.typesafe.config.ConfigFactory

import scala.collection.concurrent.TrieMap
import scala.util.Random

class SampleContext(override val projectName: String, override val rootPath: Seq[String]) extends LocalDockerContext(projectName = projectName, rootPath = rootPath) {

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