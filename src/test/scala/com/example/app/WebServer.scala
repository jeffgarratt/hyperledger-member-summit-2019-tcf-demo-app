package com.example.app

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.directives.ContentTypeResolver.Default
import akka.stream.ActorMaterializer
import com.github.jeffgarratt.hl.fabric.sdk.{Organization, User}
import com.typesafe.config.ConfigFactory
import monix.eval.Task
import spray.json.DefaultJsonProtocol

import scala.io.StdIn
import scala.util.{Failure, Success}



case class Resp(key: String, value: String)

case class Req(key: String, value: String, resp: Option[Resp])


// collect your json format instances into a support trait:
trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val userFormat = jsonFormat3(User)
  implicit val orgFormat = jsonFormat4(Organization)
  implicit val respFormat = jsonFormat2(Resp)
  implicit val reqFormat = jsonFormat3(Req)
}


object WebServer extends JsonSupport {
  // Load our own config values from the default location, application.conf
  val conf = ConfigFactory.load()
  val prototypeProjectName = conf.getString("fabric.prototype.projectName")
  val bootstrapSpec = new BootstrapSpec(prototypeProjectName)


  def getRequestFromRecords(records: Map[String, String]) = {
    val allRequestKeys = records.keys.filter(x => x.startsWith("REQ"))
    val results = allRequestKeys.map(reqKey => {
      val respKey = "RESP" + reqKey.substring(3)
      records.get(respKey) match {
        case Some(resp) => {
          Req(reqKey, records.get(reqKey).get, Some(Resp(respKey, resp)))
        }
        case None => {
          Req(reqKey, records.get(reqKey).get, None)
        }
      }
    })
    results.toList
  }

  def main(args: Array[String]) {

    implicit val system = ActorSystem("my-system")
    implicit val materializer = ActorMaterializer()
    // needed for the future flatMap/onComplete in the end
    implicit val executionContext = system.dispatcher

    val route =
      concat(
        path("vue") {
          getFromFile("./src/main/web/index.html") // uses implicit ContentTypeResolver
        },
        path("network") {
          getFromFile(conf.getString("fabric.prototype.network.jsonFile")) // uses implicit ContentTypeResolver
        },
        pathPrefix("web") {
          getFromDirectory("./src/main/web") // uses implicit ContentTypeResolver
        }
        , path("users") {
          get {
            complete(bootstrapSpec.ctx.getDirectory.get.users)
          }
        }, path("orgs") {
          get {
            val orgs = bootstrapSpec.ctx.getDirectory.get.orgs
            complete(orgs)
          }
        }, path("medical") {
          get {
            val result = bootstrapSpec.queryAllMedical.runToFuture(monix.execution.Scheduler.Implicits.global)
            onComplete(result) {
              case Success(value) => {
                val result = value.map(m => m.keys.head -> m.values.head.descriptors.map { case (a, b) => (a, b.description) })
                complete(result.sortBy(x => x._1))
              }
              case Failure(ex) => complete((InternalServerError, s"An error occurred: ${ex.getMessage}"))
            }
          }
        }, path("worker") {
          get {
            val result = bootstrapSpec.queryPeer7.runToFuture(monix.execution.Scheduler.Implicits.global)
            onComplete(result) {
              case Success(value) => {
                val r = value.map { case (k, v) => (k, v.descriptors.map { case (k1, v1) => (k1, v1.description) }) }
                complete(r)
              }
              case Failure(ex) => complete((InternalServerError, s"An error occurred: ${ex.getMessage}"))
            }
          }
        }, path("worker2") {
          get {
            val result = bootstrapSpec.queryPeer7.flatMap(x => Task.eval(x.values.head.descriptors.map { case (a, b) => (a, b.description) })).runToFuture(monix.execution.Scheduler.Implicits.global)
            onComplete(result) {
              case Success(value) => {
                // Now get array of Req objects.
                complete(getRequestFromRecords(value))
              }
              case Failure(ex) => complete((InternalServerError, s"An error occurred: ${ex.getMessage}"))
            }
          }
        }
      )

    val bindingFuture = Http().bindAndHandle(route, "0.0.0.0", 7051)

    println(s"Server online at http://localhost:7051/\nPress RETURN to stop...")
    StdIn.readLine() // let it run until user presses return
    bindingFuture
      .flatMap(_.unbind()) // trigger unbinding from the port
      .onComplete(_ => system.terminate()) // and shutdown when done
  }
}