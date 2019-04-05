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

package uk.gov.hmrc.teamsandrepositories.config

import com.google.inject.{Inject, Singleton}
import play.api.Configuration
import play.api.libs.json.{Json, OFormat}

import scala.collection.immutable.ListMap

case class UrlTemplates(environments: ListMap[String, Seq[UrlTemplate]])

case class UrlTemplate(name: String, displayName: String, template: String) {
  def url(serviceName: String) = template.replace("$name", serviceName)
}

object UrlTemplate {
  implicit val formats: OFormat[UrlTemplate] = Json.format[UrlTemplate]
}

@Singleton
class UrlTemplatesProvider @Inject()(configuration: Configuration) {

  val ciUrlTemplates: UrlTemplates = UrlTemplates(getTemplatesForEnvironments())

  private def urlTemplates =
    configuration.getConfig("url-templates").getOrElse(throw new RuntimeException("no url-templates config found"))

  private def getTemplatesForEnvironments(): ListMap[String, Seq[UrlTemplate]] = {
    val configs = urlTemplates
      .getConfigSeq("envrionments")
      .getOrElse(throw new RuntimeException("incorrect environment configuration"))

    configs
      .map { cf =>
        val envName = cf
          .getString("name")
          .getOrElse(throw new RuntimeException("incorrect environment configuration"))

        val envTemplates = cf
          .getConfigSeq("services")
          .getOrElse(throw new RuntimeException("incorrect environment configuration"))
          .map { s =>
            readLink(s)
          }
        envName -> envTemplates.flatten
      }
      .foldLeft(ListMap.empty[String, Seq[UrlTemplate]])((acc, v) => acc + (v._1 -> v._2))

  }

  private def readLink(config: Configuration): Option[UrlTemplate] =
    for {
      name        <- config.getString("name")
      displayName <- config.getString("display-name")
      url         <- config.getString("url")
    } yield UrlTemplate(name, displayName, url)
}
