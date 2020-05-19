import ReleaseTransformations._

lazy val commonSettings = Seq(
  organization := "org.parsertongue",
  scalaVersion := "2.12.4",
  crossScalaVersions := Seq("2.11.11", "2.12.4"),
  scalacOptions ++= Seq("-feature", "-unchecked", "-deprecation"),
  parallelExecution in Test := false,
  scalacOptions in (Compile, doc) += "-no-link-warnings", // suppresses problems with scaladoc @throws links

  //
  // publishing settings
  //
  // publish to a maven repo
  publishMavenStyle := true
)

lazy val odin = project
  .settings(commonSettings: _*)
  .dependsOn(main % "test->test;compile->compile")