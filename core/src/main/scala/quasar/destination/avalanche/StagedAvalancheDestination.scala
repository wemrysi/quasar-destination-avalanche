/*
 * Copyright 2020 Precog Data
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

package quasar.destination.avalanche

import scala.{io => _, _}, Predef._

import cats.~>
import cats.data.NonEmptyList
import cats.effect.{Sync, Timer}
import cats.implicits._

import doobie._
import doobie.implicits._

import fs2.Stream

import java.util.UUID
import java.net.URI

import io.chrisdavenport.log4cats.slf4j.Slf4jLogger

import org.slf4s.Logger

import pathy.Path.FileName

import quasar.api.destination.DestinationType
import quasar.blobstore.services.{DeleteService, PutService}
import quasar.blobstore.paths.{BlobPath, PathElem}
import quasar.connector.MonadResourceErr
import quasar.plugin.jdbc.Slf4sLogHandler

final class StagedAvalancheDestination[F[_]: Sync: MonadResourceErr: Timer](
    val destinationType: DestinationType,
    putService: PutService[F],
    deleteService: DeleteService[F],
    stagedUri: FileName => URI,
    vwloadAuthParams: Map[String, String],
    writeMode: WriteMode,
    xa: Transactor[F],
    logger: Logger)
    extends AvalancheDestination[F](logger) {

  def loadGzippedCsv(
      tableName: TableName,
      columns: NonEmptyList[Fragment],
      gzippedCsv: Stream[F, Byte])
      : Stream[F, Unit] =
    stageBytes(gzippedCsv) evalMap { uri =>
      val prepare = prepareTable(tableName, columns, writeMode, logHandler)
      val vwload = copyVWLoad(tableName, NonEmptyList.one(uri), vwloadAuthParams, logHandler)

      (prepare >> vwload).void.transact(xa)
    }

  ////

  private val log =
    Slf4jLogger.getLoggerFromSlf4j[F](logger.underlying)
      .mapK(λ[F ~> Stream[F, ?]](Stream.eval(_)))

  private val logHandler = Slf4sLogHandler(logger)

  private def stageBytes(bytes: Stream[F, Byte]): Stream[F, URI] =
    for {
      fileName <- Stream.eval(Sync[F].delay(s"precog-${UUID.randomUUID}.csv.gz"))
      blobPath = BlobPath(List(PathElem(fileName)))
      uri = stagedUri(FileName(fileName))

      staging = Stream.bracket(putService(blobPath -> bytes))(_ => deleteService(blobPath).void)

      _ <- log.debug(s"Starting staging upload to $uri")

      _ <- staging handleErrorWith { t =>
        log.debug(t)(s"Failed staging upload to $uri: ${t.getMessage}") >> Stream.raiseError[F](t)
      }

      _ <- log.debug(s"Finished staging upload to $uri")
    } yield uri
}
