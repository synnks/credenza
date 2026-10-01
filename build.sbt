ThisBuild / scalaVersion := "3.9.0"

val CatsCoreVersion        = "2.13.0"
val CatsEffectVersion      = "3.7.1"
val CirceVersion           = "0.14.16"
val Http4sVersion          = "0.23.38"
val MUnitVersion           = "1.3.6"
val MUnitCatsEffectVersion = "2.2.1"
val ScalaJavaTimeVersion   = "2.7.0"
val NativeCryptoVersion    = "0.4.0"

root / libraryDependencies ++= Seq(
  "org.typelevel"     %% "cats-core"           % CatsCoreVersion,
  "org.typelevel"     %% "cats-effect"         % CatsEffectVersion,
  "io.circe"          %% "circe-parser"        % CirceVersion,
  "org.http4s"        %% "http4s-ember-client" % Http4sVersion,
  "org.http4s"        %% "http4s-ember-server" % Http4sVersion          % Test,
  "io.github.cquiroz" %% "scala-java-time"     % ScalaJavaTimeVersion,
  "com.github.lolgab" %% "scala-native-crypto" % NativeCryptoVersion,
  "org.scalameta"     %% "munit"               % MUnitVersion           % Test,
  "org.typelevel"     %% "munit-cats-effect"   % MUnitCatsEffectVersion % Test
)

lazy val root = rootProject
  .enablePlugins(ScalaNativePlugin)
  .settings(
    name := "credenza",
    Test / nativeConfig ~= (_.withEmbedResources(true))
  )
