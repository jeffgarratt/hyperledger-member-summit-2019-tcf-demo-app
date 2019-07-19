package com.example.app

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.directives.ContentTypeResolver.Default
import akka.stream.ActorMaterializer
import com.github.jeffgarratt.hl.fabric.sdk.{Organization, User}
import spray.json.DefaultJsonProtocol

import scala.io.StdIn
import scala.util.{Failure, Success}


// collect your json format instances into a support trait:
trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val userFormat = jsonFormat3(User)
  implicit val orgFormat = jsonFormat4(Organization)
}

object WebServer extends JsonSupport {

  val bootstrapSpec = new BootstrapSpec("33fce552a84511e98d7502d158fa0198")

  def main(args: Array[String]) {

    implicit val system = ActorSystem("my-system")
    implicit val materializer = ActorMaterializer()
    // needed for the future flatMap/onComplete in the end
    implicit val executionContext = system.dispatcher

    val route =
      concat(
        path("vue") {
          getFromFile("./src/main/web/index.html") // uses implicit ContentTypeResolver
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