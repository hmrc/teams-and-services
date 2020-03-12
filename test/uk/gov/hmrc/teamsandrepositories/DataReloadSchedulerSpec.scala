/*
 * Copyright 2020 HM Revenue & Customs
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

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.mockito.Mockito._
import org.scalatest.concurrent.Eventually
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, OptionValues}
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.ApplicationLifecycle
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.mvc.Results
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.DB
import uk.gov.hmrc.teamsandrepositories.config.SchedulerConfigs
import uk.gov.hmrc.teamsandrepositories.persitence.{LockKeeper, MongoLocks}
import uk.gov.hmrc.teamsandrepositories.services.PersistingService

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

class DataReloadSchedulerSpec
    extends PlaySpec
    with MockitoSugar
    with Results
    with OptionValues
    with GuiceOneServerPerSuite
    with Eventually
    with BeforeAndAfterAll {

  implicit override lazy val app: Application =
    new GuiceApplicationBuilder()
      .disable(classOf[com.kenshoo.play.metrics.PlayModule], classOf[Module])
      .configure("metrics.jvm" -> false)
      .build()

  def mockDB: () => DB = () => mock[DB]

  val testMongoLock: LockKeeper = new LockKeeper(mockDB, "testLock") {
    override def tryLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] =
      body.map(t => Some(t))(ec)
  }

  val testMongoLocks: MongoLocks = new MongoLocks(mock[ReactiveMongoComponent](RETURNS_DEEP_STUBS)) {
    override val dataReloadLock: LockKeeper = testMongoLock
  }

  "reload the cache and remove orphan teams at the configured intervals" in {

    val mockSchedulerConfigs  = mock[SchedulerConfigs](RETURNS_DEEP_STUBS)
    val mockPersistingService = mock[PersistingService]

    when(mockPersistingService.persistTeamRepoMapping(any())).thenReturn(Future(Nil))
    when(mockPersistingService.removeOrphanTeamsFromMongo(any())(any())).thenReturn(Future(Set.empty[String]))

    when(mockSchedulerConfigs.dataReloadScheduler.initialDelay).thenReturn(100 millisecond)
    when(mockSchedulerConfigs.dataReloadScheduler.interval).thenReturn(100 millisecond)
    when(mockSchedulerConfigs.dataReloadScheduler.enabled).thenReturn(true)

    new DataReloadScheduler(
      persistingService    = mockPersistingService,
      config               = mockSchedulerConfigs,
      mongoLocks           = testMongoLocks
    )(actorSystem          = app.actorSystem,
      applicationLifecycle = app.injector.instanceOf[ApplicationLifecycle]
    )

    verify(mockPersistingService, Mockito.timeout(500).atLeast(2)).persistTeamRepoMapping(any())
    verify(mockPersistingService, Mockito.timeout(500).atLeast(2)).removeOrphanTeamsFromMongo(any())(any())
  }

  "reloading the cache" should {
    "be disabled" in {
      val mockSchedulerConfigs  = mock[SchedulerConfigs](RETURNS_DEEP_STUBS)
      val mockPersistingService = mock[PersistingService]

      when(mockPersistingService.persistTeamRepoMapping(any())).thenReturn(Future(Nil))
      when(mockPersistingService.removeOrphanTeamsFromMongo(any())(any())).thenReturn(Future(Set.empty[String]))

      when(mockSchedulerConfigs.dataReloadScheduler.initialDelay).thenReturn(100 millisecond)
      when(mockSchedulerConfigs.dataReloadScheduler.interval).thenReturn(100 millisecond)
      when(mockSchedulerConfigs.dataReloadScheduler.enabled).thenReturn(false)

      new DataReloadScheduler(
        persistingService    = mockPersistingService,
        config               = mockSchedulerConfigs,
        mongoLocks           = testMongoLocks
      )(actorSystem          = app.actorSystem,
        applicationLifecycle = app.injector.instanceOf[ApplicationLifecycle])

      verify(mockPersistingService, Mockito.timeout(500).times(0)).persistTeamRepoMapping(any())
      verify(mockPersistingService, Mockito.timeout(500).times(0)).removeOrphanTeamsFromMongo(any())(any())
    }
  }
}
