import sbt._

object AppDependencies {

  import play.core.PlayVersion.current
  import play.sbt.PlayImport._

  val bootstrapPlayVersion = "0.46.0"

  val compile = Seq(
    ws,
    "uk.gov.hmrc"            %% "bootstrap-play-26" % bootstrapPlayVersion,
    "uk.gov.hmrc"            %% "github-client"     % "2.12.0",
    "org.yaml"               % "snakeyaml"          % "1.17",
    "uk.gov.hmrc"            %% "mongo-lock"        % "6.10.0-play-26",
    "org.typelevel"          %% "cats-core"         % "2.0.0"
  )
  val test = Seq(
    "uk.gov.hmrc"            %% "bootstrap-play-26"  % bootstrapPlayVersion % Test classifier "tests",
    "uk.gov.hmrc"            %% "hmrctest"           % "3.3.0"              % Test,
    "org.scalatest"          %% "scalatest"          % "3.0.5"              % Test,
    "org.scalatestplus.play" %% "scalatestplus-play" % "3.1.2"              % Test,
    "org.pegdown"            % "pegdown"             % "1.5.0"              % Test,
    "com.typesafe.play"      %% "play-test"          % current              % Test,
    "uk.gov.hmrc"            %% "reactivemongo-test" % "4.8.0-play-26"      % Test,
    "com.github.tomakehurst" % "wiremock"            % "1.58"               % Test,
    "org.mockito"            % "mockito-core"        % "2.3.5"              % Test
  )
}
