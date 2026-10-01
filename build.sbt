scalaVersion := "3.9.0"

val CatsCoreVersion        = "2.13.0"
val CatsEffectVersion      = "3.7.1"
val CirceVersion           = "0.14.16"
val Http4sVersion          = "0.23.38"
val MUnitVersion           = "1.3.6"
val MUnitCatsEffectVersion = "2.2.1"

libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-core"           % CatsCoreVersion,
  "org.typelevel" %% "cats-effect"         % CatsEffectVersion,
  "io.circe"      %% "circe-parser"        % CirceVersion,
  "org.http4s"    %% "http4s-ember-client" % Http4sVersion,
  "org.http4s"    %% "http4s-ember-server" % Http4sVersion          % Test,
  "org.scalameta" %% "munit"               % MUnitVersion           % Test,
  "org.typelevel" %% "munit-cats-effect"   % MUnitCatsEffectVersion % Test
)

lazy val root = rootProject
  .settings(
    name := "credenza"
  )
