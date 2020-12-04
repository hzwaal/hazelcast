import sbt._
import sbt.Keys._

val settings = Defaults.coreDefaultSettings ++ Seq(
  scalaVersion := "2.13.4",

  // fork in new JVM with access to JVM internals for improved consistency
  run / fork := true,
  run / connectInput := true,
  run / outputStrategy := Some(StdoutOutput),
  run / javaOptions ++= Seq(
    "--add-modules", "java.se",
    "--add-exports", "java.base/jdk.internal.ref=ALL-UNNAMED",
    "--add-opens", "java.base/java.lang=ALL-UNNAMED",
    "--add-opens", "java.base/java.nio=ALL-UNNAMED",
    "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
    "--add-opens", "java.management/sun.management=ALL-UNNAMED",
    "--add-opens", "jdk.management/com.sun.management.internal=ALL-UNNAMED"
  )
)

resolvers += Resolver.jcenterRepo

val dependencies = Seq(
  "com.hazelcast" % "hazelcast" % "4.1",
  //"com.hazelcast" %% "hazelcast-scala" % "3.12.1" withSources(),
  "com.google.code.findbugs" % "jsr305" % "3.0.2" % "compile" // prevent errors on missing Nullable dependency
)
  
lazy val hazelcast = (project in file("."))
  .settings(name := "Hazelcast")
  .settings(settings)
  .settings(libraryDependencies ++= dependencies)
