// give the user a nice default project!
ThisBuild / organization := "com.example"
ThisBuild / scalaVersion := "2.12.8"

lazy val root = (project in file(".")).
  settings(
    name := "My Fabric App"
  )

resolvers +=
  "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"

libraryDependencies += "com.github.jeffgarratt" %% "fabric-sdk-scala" % "0.1.0-SNAPSHOT"

libraryDependencies += "org.scalactic" %% "scalactic" % "3.0.5"
libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.5" % "test"


//// Section for ScalaPB protobuf support
PB.protoSources in Compile := Seq(file("/opt/gopath/src/github.com/hyperledger/fabric/examples/chaincode/go/marketplace/app_mgr/protos"))

PB.targets in Compile := Seq(
  scalapb.gen() -> (sourceManaged in Compile).value
)

//// (optional) If you need scalapb/scalapb.proto or anything from
//// google/protobuf/*.proto
libraryDependencies += "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf"


libraryDependencies += "com.typesafe.akka" %% "akka-http"   % "10.1.9"
libraryDependencies +="com.typesafe.akka" %% "akka-stream" % "2.5.23" // or whatever the latest version is
libraryDependencies += "com.typesafe.akka" %% "akka-http-spray-json" % "10.1.9"