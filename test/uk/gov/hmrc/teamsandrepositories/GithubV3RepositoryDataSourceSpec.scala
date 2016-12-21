/*
 * Copyright 2016 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.teamsandrepositories

import java.time.LocalDateTime
import java.util.Date

import org.mockito.ArgumentMatchers._
import org.mockito.Mockito
import org.mockito.Mockito._
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mock.MockitoSugar
import org.scalatest.time.{Minutes, Span, SpanSugar}
import org.scalatest.{BeforeAndAfterEach, Matchers, WordSpec}
import uk.gov.hmrc.githubclient
import uk.gov.hmrc.githubclient.GithubApiClient
import uk.gov.hmrc.teamsandrepositories.config.GithubConfig

import scala.concurrent.{ExecutionContext, Future}

class GithubV3RepositoryDataSourceSpec extends WordSpec with ScalaFutures with Matchers with DefaultPatienceConfig with MockitoSugar with SpanSugar with BeforeAndAfterEach {

  val now = new Date().getTime

  val testHiddenRepositories = List("hidden_repo1", "hidden_repo2")
  val testHiddenTeams = List("hidden_team1", "hidden_team2")

  val githubConfig: GithubConfig = mock[GithubConfig]

  val ec = BlockingIOExecutionContext.executionContext

  class StubTeamsAndReposPersister extends TeamsAndReposPersister(mock[MongoTeamsAndRepositoriesPersister], mock[MongoUpdateTimePersister]) {
    var captor: List[PersistedTeamAndRepositories] = Nil

    override def update(teamsAndRepositories: PersistedTeamAndRepositories): Future[PersistedTeamAndRepositories] = {
      captor +:= teamsAndRepositories
      Future(teamsAndRepositories)(ec)
    }

    override def getAllTeamAndRepos: Future[(Seq[PersistedTeamAndRepositories], Option[LocalDateTime])] =
      Future.successful((Nil, None))
  }


  "Github v3 Data Source" should {
    val githubClient = mock[GithubApiClient]

    val persister = new StubTeamsAndReposPersister

    val dataSource = new GithubV3RepositoryDataSource(githubConfig, githubClient, persister, isInternal = false)

    "Return a list of teams and repositories, filtering out forks" in {

      when(githubConfig.hiddenRepositories).thenReturn(testHiddenRepositories)
      when(githubConfig.hiddenTeams).thenReturn(testHiddenTeams)

      when(githubClient.getOrganisations(ec)).thenReturn(Future.successful(List(githubclient.GhOrganisation("HMRC", 1), githubclient.GhOrganisation("DDCN", 2))))
      when(githubClient.getTeamsForOrganisation("HMRC")(ec)).thenReturn(Future.successful(List(githubclient.GhTeam("A", 1), githubclient.GhTeam("B", 2))))
      when(githubClient.getTeamsForOrganisation("DDCN")(ec)).thenReturn(Future.successful(List(githubclient.GhTeam("C", 3), githubclient.GhTeam("D", 4))))
      when(githubClient.getReposForTeam(1)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("A_r", "some description", 1, "url_A", fork = false, now, now), githubclient.GhRepository("A_r2", "some description", 5, "url_A2", fork = true, now, now))))
      when(githubClient.getReposForTeam(2)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("B_r", "some description", 2, "url_B", fork = false, now, now))))
      when(githubClient.getReposForTeam(3)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("C_r", "some description", 3, "url_C", fork = false, now, now))))
      when(githubClient.getReposForTeam(4)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("D_r", "some description", 4, "url_D", fork = true, now, now))))
      when(githubClient.repoContainsContent(anyString(), anyString(), anyString())(any[ExecutionContext])).thenReturn(Future.successful(false))
      when(githubClient.getTags(anyString(), anyString())(any[ExecutionContext])).thenReturn(Future.successful(List.empty))



      val eventualPersistedTeamAndRepositorieses = dataSource.persistTeamsAndReposMapping()


      eventualPersistedTeamAndRepositorieses.futureValue shouldBe List(
        PersistedTeamAndRepositories("A", List(GitRepository("A_r", "some description", "url_A", now, now))),
        PersistedTeamAndRepositories("B", List(GitRepository("B_r", "some description", "url_B", now, now))),
        PersistedTeamAndRepositories("C", List(GitRepository("C_r", "some description", "url_C", now, now))),
        PersistedTeamAndRepositories("D", List()))
    }

//    "Return a list of teams and repositories, filtering out forks" in {
//
//      when(githubConfig.hiddenRepositories).thenReturn(testHiddenRepositories)
//      when(githubConfig.hiddenTeams).thenReturn(testHiddenTeams)
//
//      when(githubClient.getOrganisations(ec)).thenReturn(Future.successful(List(githubclient.GhOrganisation("HMRC", 1), githubclient.GhOrganisation("DDCN", 2))))
//      when(githubClient.getTeamsForOrganisation("HMRC")(ec)).thenReturn(Future.successful(List(githubclient.GhTeam("A", 1), githubclient.GhTeam("B", 2))))
//      when(githubClient.getTeamsForOrganisation("DDCN")(ec)).thenReturn(Future.successful(List(githubclient.GhTeam("C", 3), githubclient.GhTeam("D", 4))))
//      when(githubClient.getReposForTeam(1)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("A_r", "some description", 1, "url_A", fork = false, now, now), githubclient.GhRepository("A_r2", "some description", 5, "url_A2", fork = true, now, now))))
//      when(githubClient.getReposForTeam(2)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("B_r", "some description", 2, "url_B", fork = false, now, now))))
//      when(githubClient.getReposForTeam(3)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("C_r", "some description", 3, "url_C", fork = false, now, now))))
//      when(githubClient.getReposForTeam(4)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("D_r", "some description", 4, "url_D", fork = true, now, now))))
//      when(githubClient.repoContainsContent(anyString(), anyString(), anyString())(any[ExecutionContext])).thenReturn(Future.successful(false))
//      when(githubClient.getTags(anyString(), anyString())(any[ExecutionContext])).thenReturn(Future.successful(List.empty))
//
//
//
//      val eventualPersistedTeamAndRepositorieses = dataSource.persistTeamsAndReposMapping()
//
//
//      eventualPersistedTeamAndRepositorieses.futureValue shouldBe List(
//        PersistedTeamAndRepositories("A", List(GitRepository("A_r", "some description", "url_A", now, now))),
//        PersistedTeamAndRepositories("B", List(GitRepository("B_r", "some description", "url_B", now, now))),
//        PersistedTeamAndRepositories("C", List(GitRepository("C_r", "some description", "url_C", now, now))),
//        PersistedTeamAndRepositories("D", List()))
//    }

    "Set internal = true if the DataSource is marked as internal" in {

      when(githubConfig.hiddenRepositories).thenReturn(testHiddenRepositories)
      when(githubConfig.hiddenTeams).thenReturn(testHiddenTeams)
      val persister = new StubTeamsAndReposPersister

      val internalDataSource = new GithubV3RepositoryDataSource(githubConfig, githubClient, persister, isInternal = true)

      when(githubClient.getOrganisations(ec)).thenReturn(Future.successful(List(githubclient.GhOrganisation("HMRC", 1), githubclient.GhOrganisation("DDCN", 2))))
      when(githubClient.getTeamsForOrganisation("HMRC")(ec)).thenReturn(Future.successful(List(githubclient.GhTeam("A", 1), githubclient.GhTeam("B", 2))))
      when(githubClient.getTeamsForOrganisation("DDCN")(ec)).thenReturn(Future.successful(List(githubclient.GhTeam("C", 3), githubclient.GhTeam("D", 4))))
      when(githubClient.getReposForTeam(1)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("A_r", "some description", 1, "url_A", fork = false, now, now), githubclient.GhRepository("A_r2", "some description", 5, "url_A2", fork = true, now, now))))
      when(githubClient.getReposForTeam(2)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("B_r", "some description", 2, "url_B", fork = false, now, now))))
      when(githubClient.getReposForTeam(3)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("C_r", "some description", 3, "url_C", fork = false, now, now))))
      when(githubClient.getReposForTeam(4)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("D_r", "some description", 4, "url_D", fork = true, now, now))))
      when(githubClient.repoContainsContent(anyString(), anyString(), anyString())(any[ExecutionContext])).thenReturn(Future.successful(false))


      internalDataSource.persistTeamsAndReposMapping().futureValue shouldBe List(
        PersistedTeamAndRepositories("A", List(GitRepository("A_r", "some description", "url_A", now, now, isInternal = true))),
        PersistedTeamAndRepositories("B", List(GitRepository("B_r", "some description", "url_B", now, now, isInternal = true))),
        PersistedTeamAndRepositories("C", List(GitRepository("C_r", "some description", "url_C", now, now, isInternal = true))),
        PersistedTeamAndRepositories("D", List()))
    }

    "Filter out repositories according to the hidden config" in {

      when(githubConfig.hiddenRepositories).thenReturn(testHiddenRepositories)
      when(githubConfig.hiddenTeams).thenReturn(testHiddenTeams)


      when(githubClient.getOrganisations(ec)).thenReturn(Future.successful(List(githubclient.GhOrganisation("HMRC", 1), githubclient.GhOrganisation("DDCN", 2))))
      when(githubClient.getTeamsForOrganisation("HMRC")(ec)).thenReturn(Future.successful(List(githubclient.GhTeam("A", 1))))
      when(githubClient.getTeamsForOrganisation("DDCN")(ec)).thenReturn(Future.successful(List(githubclient.GhTeam("C", 3), githubclient.GhTeam("D", 4))))
      when(githubClient.getReposForTeam(1)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("hidden_repo1", "some description", 1, "url_A", false, now, now), githubclient.GhRepository("A_r2", "some description", 5, "url_A2", false, now, now))))
      when(githubClient.getReposForTeam(3)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("hidden_repo2", "some description", 3, "url_C", false, now, now))))
      when(githubClient.getReposForTeam(4)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("D_r", "some description", 4, "url_D", false, now, now))))
      when(githubClient.repoContainsContent(anyString(), anyString(), anyString())(any[ExecutionContext])).thenReturn(Future.successful(false))

      dataSource.persistTeamsAndReposMapping().futureValue shouldBe List(
        PersistedTeamAndRepositories("A", List(GitRepository("A_r2", "some description", "url_A2", now, now))),
        PersistedTeamAndRepositories("C", List()),
        PersistedTeamAndRepositories("D", List(GitRepository("D_r", "some description", "url_D", now, now))))
    }


    "Filter out teams according to the hidden config" in {

      when(githubConfig.hiddenRepositories).thenReturn(testHiddenRepositories)
      when(githubConfig.hiddenTeams).thenReturn(testHiddenTeams)

      when(githubClient.getOrganisations(ec)).thenReturn(Future.successful(List(githubclient.GhOrganisation("HMRC", 1), githubclient.GhOrganisation("DDCN", 2))))
      when(githubClient.getTeamsForOrganisation("HMRC")(ec)).thenReturn(Future.successful(List(githubclient.GhTeam("hidden_team1", 1))))
      when(githubClient.getTeamsForOrganisation("DDCN")(ec)).thenReturn(Future.successful(List(githubclient.GhTeam("hidden_team2", 3), githubclient.GhTeam("D", 4))))
      when(githubClient.getReposForTeam(1)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("A_r", "some description", 1, "url_A", false, now, now))))
      when(githubClient.getReposForTeam(3)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("C_r", "some description", 3, "url_C", false, now, now))))
      when(githubClient.getReposForTeam(4)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("D_r", "some description", 4, "url_D", false, now, now))))
      when(githubClient.repoContainsContent(anyString(), anyString(), anyString())(any[ExecutionContext])).thenReturn(Future.successful(false))

      val persister = new StubTeamsAndReposPersister

      dataSource.persistTeamsAndReposMapping().futureValue shouldBe List(
        PersistedTeamAndRepositories("D", List(GitRepository("D_r", "some description", "url_D", now, now))))
    }

    "Set repoType Service if the repository contains an app/application.conf folder" in {

      when(githubConfig.hiddenRepositories).thenReturn(testHiddenRepositories)
      when(githubConfig.hiddenTeams).thenReturn(testHiddenTeams)
      when(githubClient.getOrganisations(ec)).thenReturn(Future.successful(List(githubclient.GhOrganisation("HMRC", 1), githubclient.GhOrganisation("DDCN", 2))))
      when(githubClient.getTeamsForOrganisation("HMRC")(ec)).thenReturn(Future.successful(List(githubclient.GhTeam("A", 1))))
      when(githubClient.getTeamsForOrganisation("DDCN")(ec)).thenReturn(Future.successful(List(githubclient.GhTeam("D", 4))))
      when(githubClient.getReposForTeam(1)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("A_r", "some description", 1, "url_A", false, now, now))))
      when(githubClient.getReposForTeam(4)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("D_r", "some description", 4, "url_D", false, now, now))))
      when(githubClient.repoContainsContent(anyString(), anyString(), anyString())(any[ExecutionContext])).thenReturn(Future.successful(false))

      when(githubClient.repoContainsContent("conf/application.conf", "A_r", "HMRC")(ec)).thenReturn(Future.successful(true))
      when(githubClient.repoContainsContent("conf/application.conf", "D_r", "DDCN")(ec)).thenReturn(Future.successful(false))

      val persister = new StubTeamsAndReposPersister

      dataSource.persistTeamsAndReposMapping().futureValue shouldBe List(
        PersistedTeamAndRepositories("A", List(GitRepository("A_r", "some description", "url_A", now, now, repoType = RepoType.Deployable))),
        PersistedTeamAndRepositories("D", List(GitRepository("D_r", "some description", "url_D", now, now))))
    }

    "Set repoType Service if the repository contains a Procfile" in {
      when(githubConfig.hiddenRepositories).thenReturn(testHiddenRepositories)
      when(githubConfig.hiddenTeams).thenReturn(testHiddenTeams)
      when(githubClient.getOrganisations(ec)).thenReturn(Future.successful(List(githubclient.GhOrganisation("HMRC", 1), githubclient.GhOrganisation("DDCN", 2))))
      when(githubClient.getTeamsForOrganisation("HMRC")(ec)).thenReturn(Future.successful(List(githubclient.GhTeam("A", 1))))
      when(githubClient.getTeamsForOrganisation("DDCN")(ec)).thenReturn(Future.successful(List(githubclient.GhTeam("D", 4))))
      when(githubClient.getReposForTeam(1)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("A_r", "some description", 1, "url_A", false, now, now))))
      when(githubClient.getReposForTeam(4)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("D_r", "some description", 4, "url_D", false, now, now))))
      when(githubClient.repoContainsContent(anyString(), anyString(), anyString())(any[ExecutionContext])).thenReturn(Future.successful(false))

      when(githubClient.repoContainsContent("Procfile", "A_r", "HMRC")(ec)).thenReturn(Future.successful(true))
      when(githubClient.repoContainsContent("Procfile", "B_r", "HMRC")(ec)).thenReturn(Future.successful(false))

      val persister = new StubTeamsAndReposPersister

      dataSource.persistTeamsAndReposMapping().futureValue shouldBe List(
        PersistedTeamAndRepositories("A", List(GitRepository("A_r", "some description", "url_A", now, now, repoType = RepoType.Deployable))),
        PersistedTeamAndRepositories("D", List(GitRepository("D_r", "some description", "url_D", now, now, repoType = RepoType.Other))))
    }


    "Set type Service if the repository contains a deploy.properties" in {
      when(githubConfig.hiddenRepositories).thenReturn(testHiddenRepositories)
      when(githubConfig.hiddenTeams).thenReturn(testHiddenTeams)
      when(githubClient.getOrganisations(ec)).thenReturn(Future.successful(List(githubclient.GhOrganisation("HMRC", 1), githubclient.GhOrganisation("DDCN", 2))))
      when(githubClient.getTeamsForOrganisation("HMRC")(ec)).thenReturn(Future.successful(List(githubclient.GhTeam("A", 1))))
      when(githubClient.getTeamsForOrganisation("DDCN")(ec)).thenReturn(Future.successful(List(githubclient.GhTeam("D", 4))))
      when(githubClient.getReposForTeam(1)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("A_r", "some description", 1, "url_A", false, now, now))))
      when(githubClient.getReposForTeam(4)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("D_r", "some description", 4, "url_D", false, now, now))))

      when(githubClient.repoContainsContent(anyString(), anyString(), anyString())(any[ExecutionContext])).thenReturn(Future.successful(false))

      when(githubClient.repoContainsContent(same("Procfile"), same("A_r"), same("HMRC"))(same(ec))).thenReturn(Future.successful(false))

      when(githubClient.repoContainsContent(same("deploy.properties"), same("D_r"), same("DDCN"))(same(ec))).thenReturn(Future.successful(true))

      val persister = new StubTeamsAndReposPersister

      dataSource.persistTeamsAndReposMapping().futureValue should contain(PersistedTeamAndRepositories("D", List(GitRepository("D_r", "some description", "url_D", now, now, repoType = RepoType.Deployable))))
    }

    "Set type Library if not Service and has src/main/scala and has tags" in {
      when(githubConfig.hiddenRepositories).thenReturn(testHiddenRepositories)
      when(githubConfig.hiddenTeams).thenReturn(testHiddenTeams)
      when(githubClient.getOrganisations(ec)).thenReturn(Future.successful(List(githubclient.GhOrganisation("HMRC", 1), githubclient.GhOrganisation("DDCN", 2))))
      when(githubClient.getTeamsForOrganisation("HMRC")(ec)).thenReturn(Future.successful(List(githubclient.GhTeam("A", 1))))
      when(githubClient.getTeamsForOrganisation("DDCN")(ec)).thenReturn(Future.successful(List(githubclient.GhTeam("D", 4))))
      when(githubClient.getReposForTeam(1)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("A_r", "some description", 1, "url_A", false, now, now))))
      when(githubClient.getReposForTeam(4)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("D_r", "some description", 4, "url_D", false, now, now))))
      when(githubClient.getTags("HMRC", "A_r")(ec)).thenReturn(Future.successful(List.empty))
      when(githubClient.getTags("DDCN", "D_r")(ec)).thenReturn(Future.successful(List("D_r_tag")))

      when(githubClient.repoContainsContent(anyString(), anyString(), anyString())(any[ExecutionContext])).thenReturn(Future.successful(false))

      when(githubClient.repoContainsContent(same("Procfile"), same("A_r"), same("HMRC"))(same(ec))).thenReturn(Future.successful(false))

      when(githubClient.repoContainsContent(same("deploy.properties"), same("D_r"), same("DDCN"))(same(ec))).thenReturn(Future.successful(false))

      when(githubClient.repoContainsContent(same("src/main/scala"), same("D_r"), same("DDCN"))(same(ec))).thenReturn(Future.successful(true))

      val persister = new StubTeamsAndReposPersister

      val repositories: Seq[PersistedTeamAndRepositories] = dataSource.persistTeamsAndReposMapping().futureValue

      repositories should contain(PersistedTeamAndRepositories("D", List(GitRepository("D_r", "some description", "url_D", now, now, repoType = RepoType.Library))))

      repositories should contain(PersistedTeamAndRepositories("A", List(GitRepository("A_r", "some description", "url_A", now, now, repoType = RepoType.Other))))
    }


    "Set type Library if not Service and has src/main/java and has tags" in {
      when(githubConfig.hiddenRepositories).thenReturn(testHiddenRepositories)
      when(githubConfig.hiddenTeams).thenReturn(testHiddenTeams)
      when(githubClient.getOrganisations(ec)).thenReturn(Future.successful(List(githubclient.GhOrganisation("HMRC", 1), githubclient.GhOrganisation("DDCN", 2))))
      when(githubClient.getTeamsForOrganisation("HMRC")(ec)).thenReturn(Future.successful(List(githubclient.GhTeam("A", 1))))
      when(githubClient.getTeamsForOrganisation("DDCN")(ec)).thenReturn(Future.successful(List(githubclient.GhTeam("D", 4))))
      when(githubClient.getReposForTeam(1)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("A_r", "some description", 1, "url_A", false, now, now))))
      when(githubClient.getReposForTeam(4)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("D_r", "some description", 4, "url_D", false, now, now))))
      when(githubClient.getTags("HMRC", "A_r")(ec)).thenReturn(Future.successful(List.empty))
      when(githubClient.getTags("DDCN", "D_r")(ec)).thenReturn(Future.successful(List("D_r_tag")))

      when(githubClient.repoContainsContent(anyString(), anyString(), anyString())(any[ExecutionContext])).thenReturn(Future.successful(false))

      when(githubClient.repoContainsContent(same("Procfile"), same("A_r"), same("HMRC"))(same(ec))).thenReturn(Future.successful(false))

      when(githubClient.repoContainsContent(same("deploy.properties"), same("D_r"), same("DDCN"))(same(ec))).thenReturn(Future.successful(false))

      when(githubClient.repoContainsContent(same("src/main/java"), same("D_r"), same("DDCN"))(same(ec))).thenReturn(Future.successful(true))

      val persister = new StubTeamsAndReposPersister

      val repositories = dataSource.persistTeamsAndReposMapping().futureValue

      repositories should contain(PersistedTeamAndRepositories("D", List(GitRepository("D_r", "some description", "url_D", now, now, repoType = RepoType.Library))))

      repositories should contain(PersistedTeamAndRepositories("A", List(GitRepository("A_r", "some description", "url_A", now, now, repoType = RepoType.Other))))
    }


    "Set type Other if not Service and Library" in {
      when(githubConfig.hiddenRepositories).thenReturn(testHiddenRepositories)
      when(githubConfig.hiddenTeams).thenReturn(testHiddenTeams)
      when(githubClient.getOrganisations(ec)).thenReturn(Future.successful(List(githubclient.GhOrganisation("HMRC", 1), githubclient.GhOrganisation("DDCN", 2))))
      when(githubClient.getTeamsForOrganisation("HMRC")(ec)).thenReturn(Future.successful(List(githubclient.GhTeam("A", 1))))
      when(githubClient.getTeamsForOrganisation("DDCN")(ec)).thenReturn(Future.successful(List(githubclient.GhTeam("D", 4))))
      when(githubClient.getReposForTeam(1)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("A_r", "some description", 1, "url_A", false, now, now))))
      when(githubClient.getReposForTeam(4)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("D_r", "some description", 4, "url_D", false, now, now))))
      when(githubClient.getTags("HMRC", "A_r")(ec)).thenReturn(Future.successful(List.empty))
      when(githubClient.getTags("DDCN", "D_r")(ec)).thenReturn(Future.successful(List.empty))

      when(githubClient.repoContainsContent(anyString(), anyString(), anyString())(any[ExecutionContext])).thenReturn(Future.successful(false))

      when(githubClient.repoContainsContent(same("Procfile"), same("A_r"), same("HMRC"))(same(ec))).thenReturn(Future.successful(false))

      when(githubClient.repoContainsContent(same("deploy.properties"), same("D_r"), same("DDCN"))(same(ec))).thenReturn(Future.successful(false))

      val persister = new StubTeamsAndReposPersister

      val repositories = dataSource.persistTeamsAndReposMapping().futureValue

      repositories should contain(PersistedTeamAndRepositories("D", List(GitRepository("D_r", "some description", "url_D", now, now, repoType = RepoType.Other))))

      repositories should contain(PersistedTeamAndRepositories("A", List(GitRepository("A_r", "some description", "url_A", now, now, repoType = RepoType.Other))))
    }


    "Retry up to 5 times in the event of a failed api call" in {
      when(githubConfig.hiddenRepositories).thenReturn(testHiddenRepositories)
      when(githubConfig.hiddenTeams).thenReturn(testHiddenTeams)
      when(githubClient.getOrganisations(ec))
        .thenReturn(Future.failed(new RuntimeException("something went wrong")))
        .thenReturn(Future.failed(new RuntimeException("something went wrong")))
        .thenReturn(Future.failed(new RuntimeException("something went wrong")))
        .thenReturn(Future.failed(new RuntimeException("something went wrong")))
        .thenReturn(Future.successful(List(githubclient.GhOrganisation("HMRC", 1))))

      when(githubClient.getTeamsForOrganisation("HMRC")(ec))
        .thenReturn(Future.failed(new RuntimeException("something went wrong")))
        .thenReturn(Future.failed(new RuntimeException("something went wrong")))
        .thenReturn(Future.failed(new RuntimeException("something went wrong")))
        .thenReturn(Future.failed(new RuntimeException("something went wrong")))
        .thenReturn(Future.successful(List(githubclient.GhTeam("A", 1))))

      when(githubClient.getReposForTeam(1)(ec))
        .thenReturn(Future.failed(new RuntimeException("something went wrong")))
        .thenReturn(Future.failed(new RuntimeException("something went wrong")))
        .thenReturn(Future.failed(new RuntimeException("something went wrong")))
        .thenReturn(Future.failed(new RuntimeException("something went wrong")))
        .thenReturn(Future.successful(List(githubclient.GhRepository("A_r", "some description", 1, "url_A", false, now, now))))

      when(githubClient.repoContainsContent("app", "A_r", "HMRC")(ec))
        .thenReturn(Future.failed(new RuntimeException("something went wrong")))
        .thenReturn(Future.failed(new RuntimeException("something went wrong")))
        .thenReturn(Future.failed(new RuntimeException("something went wrong")))
        .thenReturn(Future.failed(new RuntimeException("something went wrong")))
        .thenReturn(Future.successful(false))

      when(githubClient.repoContainsContent("Procfile", "A_r", "HMRC")(ec))
        .thenReturn(Future.failed(new RuntimeException("something went wrong")))
        .thenReturn(Future.failed(new RuntimeException("something went wrong")))
        .thenReturn(Future.failed(new RuntimeException("something went wrong")))
        .thenReturn(Future.failed(new RuntimeException("something went wrong")))
        .thenReturn(Future.successful(false))

      val persister = new StubTeamsAndReposPersister

      dataSource.persistTeamsAndReposMapping().futureValue(Timeout(1 minute)) shouldBe List(
        PersistedTeamAndRepositories("A", List(GitRepository("A_r", "some description", "url_A", now, now))))
    }

    "Removes redundant teams" in {

      when(githubConfig.hiddenTeams).thenReturn(testHiddenTeams)
      when(githubConfig.hiddenRepositories).thenReturn(testHiddenRepositories)

      when(githubClient.getOrganisations(ec)).thenReturn(Future.successful(List(githubclient.GhOrganisation("HMRC", 1))))
      when(githubClient.getTeamsForOrganisation("HMRC")(ec)).thenReturn(Future.successful(List(githubclient.GhTeam("team1", 1))))

      when(githubClient.getReposForTeam(1)(ec)).thenReturn(Future.successful(List(githubclient.GhRepository("A_r", "some description", 1, "url_A", false, now, now))))

      when(githubClient.repoContainsContent(anyString(), anyString(), anyString())(any[ExecutionContext])).thenReturn(Future.successful(false))


      val persister2 = mock[TeamsAndReposPersister]

      val team1Repos = PersistedTeamAndRepositories("team1", Nil)
      val deletedTeamRepos = PersistedTeamAndRepositories("deletedTeam", Nil)

      when(persister2.update(any())).thenReturn(Future.successful(team1Repos))
      when(persister2.getAllTeamAndRepos).thenReturn(Future.successful((Seq(team1Repos, deletedTeamRepos), None)))
      when(persister2.deleteTeams(Set("deletedTeam"))).thenReturn(Future.successful(Set("deletedTeam")))
      when(persister2.updateTimestamp(any())).thenReturn(Future.successful(true))

      val dataSource = new GithubV3RepositoryDataSource(githubConfig, githubClient, persister2, isInternal = false)

      dataSource.persistTeamsAndReposMapping()

      verify(persister2, Mockito.timeout(1000)).deleteTeams(Set("deletedTeam"))
    }
  }
}
