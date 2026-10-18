import org.scalafmt.sbt.ScalafmtPlugin
import sbt.plugins.JUnitXmlReportPlugin

scalaVersion := "3.9.0"

val AwsSdkVersion          = "2.56.0"
val CatsCoreVersion        = "2.13.0"
val CatsEffectVersion      = "3.7.1"
val CirceVersion           = "0.14.16"
val Fs2Version             = "3.14.0"
val MUnitVersion           = "1.3.6"
val MUnitCatsEffectVersion = "2.2.1"
val TestcontainersVersion  = "2.0.5"
val WireMockVersion        = "3.13.2"
val Slf4jVersion           = "2.0.20"
val SttpModelVersion       = "1.9.0"
val SslConfigVersion       = "0.7.2"

lazy val It              = config("it").extend(Runtime)
lazy val integrationTest = taskKey[Unit]("Run all JVM integration tests with managed fixtures")

libraryDependencies ++= Seq(
  "software.amazon.awssdk"       % "profiles"             % AwsSdkVersion,
  ("software.amazon.awssdk"      % "sso"                  % AwsSdkVersion).exclude("software.amazon.awssdk", "apache5-client"),
  ("software.amazon.awssdk"      % "ssooidc"              % AwsSdkVersion).exclude("software.amazon.awssdk", "apache5-client"),
  "software.amazon.awssdk"       % "netty-nio-client"     % AwsSdkVersion,
  "org.typelevel"               %% "cats-core"            % CatsCoreVersion,
  "org.typelevel"               %% "cats-effect"          % CatsEffectVersion,
  "io.circe"                    %% "circe-parser"         % CirceVersion,
  "co.fs2"                      %% "fs2-reactive-streams" % Fs2Version,
  "org.slf4j"                    % "slf4j-api"            % Slf4jVersion,
  "co.fs2"                      %% "fs2-io"               % Fs2Version             % "it",
  "com.softwaremill.sttp.model" %% "core"                 % SttpModelVersion       % "it",
  "com.typesafe"                %% "ssl-config-core"      % SslConfigVersion       % "it",
  "org.scalameta"               %% "munit"                % MUnitVersion           % "test,it",
  "org.typelevel"               %% "munit-cats-effect"    % MUnitCatsEffectVersion % "test,it",
  "org.testcontainers"           % "testcontainers"       % TestcontainersVersion  % "it",
  "org.wiremock"                 % "wiremock-standalone"  % WireMockVersion        % "it",
  "org.slf4j"                    % "slf4j-simple"         % Slf4jVersion           % "it"
)

lazy val root = rootProject
  .configs(It)
  .settings(inConfig(It)(Defaults.testSettings ++ JUnitXmlReportPlugin.autoImport.testReportSettings))
  .settings(ScalafmtPlugin.scalafmtConfigSettings(It))
  .settings(
    name            := "credenza",
    integrationTest := Def.uncached { (It / testFull).value; () },
    It / extraTestDigests ++= Def.uncached {
      (It / resources).value.sortBy(_.getPath).flatMap { file =>
        Seq(
          sbt.util.Digest.sha256Hash(file.getPath.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
          sbt.util.Digest.sha256Hash(IO.readBytes(file))
        )
      }
    }
  )
