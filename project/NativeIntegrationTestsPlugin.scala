import NativeIntegrationTestsPlugin.autoImport.*
import org.scalafmt.sbt.ScalafmtPlugin
import sbt.*
import sbt.Keys.*
import sbt.plugins.JUnitXmlReportPlugin
import scala.scalanative.build.Mode
import scala.scalanative.sbtplugin.{ ScalaNativePlugin, ScalaNativePluginInternal }
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport.*

object NativeIntegrationTestsPlugin extends AutoPlugin {
  override def requires: Plugins = ScalaNativePlugin && ScalafmtPlugin && JUnitXmlReportPlugin

  object autoImport {
    val It              = config("it").extend(Runtime)
    val integrationTest = taskKey[Unit]("Run all Native integration tests with managed fixtures")
  }

  override def projectConfigurations: Seq[Configuration] = Seq(It)

  override def projectSettings: Seq[Def.Setting[?]] =
    inConfig(It)(
      Defaults.testSettings ++ ScalaNativePluginInternal.scalaNativeTestSettings ++
        JUnitXmlReportPlugin.autoImport.testReportSettings
    ) ++
      ScalafmtPlugin.scalafmtConfigSettings(It) ++ Seq(
        libraryDependencies += "org.scala-native" %% "test-interface" % nativeVersion % It,
        It / crossTarget                          := (Compile / crossTarget).value / "it",
        // The crypto dependency bundles C code even when these tests do not reach MessageDigest.
        It / nativeConfig ~= (config =>
          config.withMode(Mode.releaseFast).withLinkingOptions(config.linkingOptions :+ "-lcrypto")
        ),
        integrationTest                           := Def.uncached { (It / testFull).value; () }
      )
}
