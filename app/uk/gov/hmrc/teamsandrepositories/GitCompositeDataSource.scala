package uk.gov.hmrc.teamsandrepositories

import com.google.inject.{Inject, Singleton}
import play.api.Logger
import uk.gov.hmrc.githubclient.GithubApiClient
import uk.gov.hmrc.teamsandrepositories.config.GithubConfig

import scala.collection.immutable.Seq
import scala.concurrent.Future


////!@ test this
//@Singleton
//class GithubCompositeDataSourceFactory @Inject()(val githubConfig: GithubConfig,
//                                                 val persister: TeamsAndReposPersister,
//                                                 val mongoConnector: MongoConnector,
//                                                 val githubApiClientDecorator: GithubApiClientDecorator) {
//
//  def buildDataSource: CompositeRepositoryDataSource = {
//    val gitApiEnterpriseClient =
//      githubApiClientDecorator.githubApiClient(githubConfig.githubApiEnterpriseConfig.apiUrl, githubConfig.githubApiEnterpriseConfig.key)
//
//    val enterpriseTeamsRepositoryDataSource: GithubV3RepositoryDataSource =
//      new GithubV3RepositoryDataSource(githubConfig, gitApiEnterpriseClient, persister, isInternal = true)
//
//    val gitOpenClient =
//      githubApiClientDecorator.githubApiClient(githubConfig.githubApiOpenConfig.apiUrl, githubConfig.githubApiOpenConfig.key)
//
//    val openTeamsRepositoryDataSource: GithubV3RepositoryDataSource =
//      new GithubV3RepositoryDataSource(githubConfig, gitOpenClient, persister, isInternal = false)
//
//
//    new CompositeRepositoryDataSource(List(enterpriseTeamsRepositoryDataSource, openTeamsRepositoryDataSource))
//  }
//}

@Singleton
class GitCompositeDataSource @Inject()(val githubConfig: GithubConfig,
                                       val persister: TeamsAndReposPersister,
                                       val mongoConnector: MongoConnector,
                                       val githubApiClientDecorator: GithubApiClientDecorator) {

  import BlockingIOExecutionContext._

  val gitApiEnterpriseClient: GithubApiClient =
    githubApiClientDecorator.githubApiClient(githubConfig.githubApiEnterpriseConfig.apiUrl, githubConfig.githubApiEnterpriseConfig.key)

  val enterpriseTeamsRepositoryDataSource: GithubV3RepositoryDataSource =
    new GithubV3RepositoryDataSource(githubConfig, gitApiEnterpriseClient, persister, isInternal = true)

  val gitOpenClient: GithubApiClient =
    githubApiClientDecorator.githubApiClient(githubConfig.githubApiOpenConfig.apiUrl, githubConfig.githubApiOpenConfig.key)

  val openTeamsRepositoryDataSource: GithubV3RepositoryDataSource =
    new GithubV3RepositoryDataSource(githubConfig, gitOpenClient, persister, isInternal = false)

  val dataSources = List(enterpriseTeamsRepositoryDataSource, openTeamsRepositoryDataSource)


  def traverseDataSources: Future[Seq[PersistedTeamAndRepositories]] =
    Future.sequence(dataSources.map(_.persistTeamsAndReposMapping())).map { results =>
      val flattened = results.flatten
      Logger.info(s"Combining ${flattened.length} results from ${dataSources.length} sources")
      flattened.groupBy(_.teamName).map { case (name, teams) =>
        PersistedTeamAndRepositories(name, teams.flatMap(t => t.repositories).sortBy(_.name))
      }.toList

    }
}

@Singleton
case class GithubApiClientDecorator @Inject()() {
  def githubApiClient(apiUrl: String, apiToken: String) = GithubApiClient(apiUrl, apiToken)
}
