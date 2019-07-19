package com.example.app

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import com.github.jeffgarratt.hl.fabric.sdk.User
import spray.json.DefaultJsonProtocol
import akka.http.scaladsl.server.directives.ContentTypeResolver.Default

import scala.io.StdIn


// collect your json format instances into a support trait:
trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val userFormat = jsonFormat3(User)
}


object WebServer extends JsonSupport{

  val bootstrapSpec = new BootstrapSpec("33fce552a84511e98d7502d158fa0198")


  def main(args: Array[String]) {

    implicit val system = ActorSystem("my-system")
    implicit val materializer = ActorMaterializer()
    // needed for the future flatMap/onComplete in the end
    implicit val executionContext = system.dispatcher

    val route =
      concat(
      path("vue" ) {
        getFromFile("./src/main/web/index.html") // uses implicit ContentTypeResolver
      }
      ,path("users") {
        get {
          val users = bootstrapSpec.ctx.getDirectory.get.users
          //          complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Say hello to akka-http</h1>"))
          complete(users)
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