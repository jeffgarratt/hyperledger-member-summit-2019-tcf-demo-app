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
  implicit val scoreInputFormat = jsonFormat13(ScoreInput)
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
        },
        pathPrefix("employees") {
          concat(
            path("peerOrg3") {
              get {
                complete(bootstrapSpec.peerOrg3Employees.sorted)
              }
            },
            path("peerOrg4") {
              get {
                complete(bootstrapSpec.peerOrg4Employees.sorted)
              }
            }
          )
        },
        path("users") {
          get {
            complete(bootstrapSpec.ctx.getDirectory.get.users)
          }
        },
        path("orgs") {
          get {
            val orgs = bootstrapSpec.ctx.getDirectory.get.orgs
            complete(orgs)
          }
        },
        path("medical") {
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
          concat(get {
            val result = bootstrapSpec.queryPeer7.runToFuture(monix.execution.Scheduler.Implicits.global)
            onComplete(result) {
              case Success(value) => {
                val r = value.map { case (k, v) => (k, v.descriptors.map { case (k1, v1) => (k1, v1.description) }) }
                complete(r)
              }
              case Failure(ex) => complete((InternalServerError, s"An error occurred: ${ex.getMessage}"))
            }
          },
            post {
              decodeRequest {
                entity(as[Resp]) { resp =>
                  (resp.key match {
                    case "peerOrg3" => {
                      Right(bootstrapSpec.peerOrg3Employees)
                    }
                    case "peerOrg4" => {
                      Right(bootstrapSpec.peerOrg3Employees)
                    }
                    case _ => Left("Expected either 'peerOrg3' or 'peerOrg4' for value")
                  }) match {
                    case Right(employeesToScore) => {
                      val result = bootstrapSpec.queryAllMedical.runToFuture(monix.execution.Scheduler.Implicits.global)
                      onComplete(result) {
                        case Success(value) => {
                          val result = value.map(m => m.keys.head -> m.values.head.descriptors.map { case (a, b) => (a, b.description) })
                          val allMedicalRecords = result.map(t2 => t2._2).flatten
                          scribe.debug(s"Request received for organization ${resp.key}")
                          scribe.debug(s"Returning result =>  ${allMedicalRecords.sortBy(x => x._1)}")
                          complete(allMedicalRecords.sortBy(x => x._1))
                        }
                        case Failure(ex) => complete((InternalServerError, s"An error occurred: ${ex.getMessage}"))
                      }

                    }
                    case Left(msg) => {
                      scribe.debug(s"An error occurred: ${msg}")
                      complete((BadRequest, s"An error occurred: ${msg}"))
                    }
                  }
//                  scribe.debug(s"Request received for organization ${resp.key}")
//                  complete(s"Request received with resp => ${resp.key}")
                }
              }
            })
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