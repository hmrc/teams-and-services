package uk.gov.hmrc.teamsandrepositories

import java.time.LocalDateTime

import com.google.inject.name.Names
import com.google.inject.{AbstractModule, Inject, TypeLiteral}
import play.api.Configuration
import uk.gov.hmrc.githubclient.GithubApiClient
import uk.gov.hmrc.teamsandrepositories.config.{CacheConfig, GithubConfig}

class Module (environment: play.api.Environment, configuration: Configuration) extends AbstractModule {

  override def configure(): Unit = {
    val useMemoryDataCache = configuration.getBoolean("github.integration.enabled").getOrElse(false)
    if (useMemoryDataCache) {
      val githubConfig = new GithubConfig(configuration)

      val url = githubConfig.githubApiEnterpriseConfig.apiUrl

      val gitApiEnterpriseClient = GithubApiClient(url, githubConfig.githubApiEnterpriseConfig.key)

      val enterpriseTeamsRepositoryDataSource: RepositoryDataSource =
        new GithubV3RepositoryDataSource(githubConfig, gitApiEnterpriseClient, isInternal = true)

      val gitOpenClient = GithubApiClient(githubConfig.githubApiOpenConfig.apiUrl, githubConfig.githubApiOpenConfig.key)
      val openTeamsRepositoryDataSource: RepositoryDataSource =
        new GithubV3RepositoryDataSource(githubConfig, gitOpenClient, isInternal = false)

      val mem = new MemoryCachedRepositoryDataSource[Seq[TeamRepositories]](
        new CacheConfig(configuration),
        new CompositeRepositoryDataSource(List(enterpriseTeamsRepositoryDataSource, openTeamsRepositoryDataSource)).getTeamRepoMapping _,
        LocalDateTime.now
      )

      bind(new TypeLiteral[CachedRepositoryDataSource[Seq[TeamRepositories]]]{}).toInstance(mem)
    } else {
      val cacheFilename = configuration.getString("cacheFilename").getOrElse(throw new RuntimeException("cacheFilename is not specified for off-line (dev) usage"))
      val file = new FileCachedRepositoryDataSource(cacheFilename)
      bind(new TypeLiteral[CachedRepositoryDataSource[Seq[TeamRepositories]]]{})
        .toInstance(file)
    }
  }
}
