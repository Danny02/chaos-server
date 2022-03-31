name := "chaos-service"
version := "0.1.0-SNAPSHOT"

scalaVersion := "3.1.1"

enablePlugins(JavaAppPackaging)
enablePlugins(DockerPlugin)

val http4sVersion = "0.23.11"

libraryDependencies ++= Seq(
  "org.http4s" %% "http4s-dsl"          % http4sVersion,
  "org.http4s" %% "http4s-ember-server" % http4sVersion,
  "com.github.pureconfig" %% "pureconfig-core" % "0.17.1",
  "ch.qos.logback" % "logback-classic" % "1.2.11" % Runtime
)

Docker / packageName := sys.env.getOrElse("IMAGE_NAME", name.value)
dockerRepository := sys.env.get("REGISTRY")
dockerAlias := DockerAlias(dockerRepository.value, None, (Docker/packageName).value, Some(version.value.toLowerCase))
dockerExposedPorts ++= Seq(8080)
