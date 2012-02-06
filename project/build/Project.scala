import sbt._

class MyProject(info: ProjectInfo) extends DefaultProject(info) {
    // External dependencies

  val scalaToolsRepo = "Scala-Tools Maven Repository" at
    "http://nexus.scala-tools.org/content/repositories/snapshots/"

  val scalatest = "org.scalatest" % "scalatest" %
    "1.0.1-for-scala-2.8.0.Beta1-RC7-with-test-interfaces-0.3-SNAPSHOT" % "test"
}

