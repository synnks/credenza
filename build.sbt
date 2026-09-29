scalaVersion := "3.9.0"

val AwsSdkVersion   = "2.55.7"
val CatsCoreVersion = "2.13.0"
val MUnitVersion    = "1.3.6"

libraryDependencies ++= Seq(
  "software.amazon.awssdk" % "profiles"  % AwsSdkVersion,
  "org.typelevel"         %% "cats-core" % CatsCoreVersion,
  "org.scalameta"         %% "munit"     % MUnitVersion % Test
)

lazy val root = rootProject
  .settings(
    name := "credenza"
  )
