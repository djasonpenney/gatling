/**
 * Copyright 2011-2015 eBusiness Information, Groupe Excilys (www.ebusinessinformation.fr)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gatling.core.controller

import scala.concurrent.duration._
import scala.util.{ Failure, Success, Try }

import io.gatling.core.config.GatlingConfiguration
import io.gatling.core.controller.inject.Injector
import io.gatling.core.controller.throttle.Throttler
import io.gatling.core.scenario.SimulationParams
import io.gatling.core.stats.StatsEngine
import io.gatling.core.stats.message.End
import io.gatling.core.stats.writer.UserMessage

import akka.actor.Props

object Controller {

  val ControllerActorName = "gatling-controller"

  def props(statsEngine: StatsEngine, throttler: Throttler, simulationParams: SimulationParams, configuration: GatlingConfiguration) =
    Props(new Controller(statsEngine, throttler, simulationParams, configuration))
}

class Controller(statsEngine: StatsEngine, throttler: Throttler, simulationParams: SimulationParams, configuration: GatlingConfiguration)
    extends ControllerFSM {

  val maxDurationTimer = "maxDurationTimer"
  val injectionTimer = "injectionTimer"
  val injectorPeriod = 1 second

  startWith(WaitingToStart, NoData)

  // -- STEP 1 :  Waiting for Gatling to start the Controller -- //

  when(WaitingToStart) {
    case Event(Start(scenarios), NoData) =>
      val initData = InitData(sender(), scenarios)

      val injector = Injector(system, statsEngine, initData.scenarios)
      val startedData = StartedData(initData, injector, new UserCounts(0L, 0L), injectionContinue = true)

      simulationParams.maxDuration.foreach { maxDuration =>
        logger.debug("Setting up max duration")
        setTimer(maxDurationTimer, ForceStop(), maxDuration)
      }

      throttler.start()

      // inject twice: one period ahead to avoid bumps
      val state = inject(startedData, injectorPeriod * 2)

      goto(Started) using state.stateData
  }

  private def inject(startedData: StartedData, window: FiniteDuration): State = {
    val injection = startedData.injector.inject(injectorPeriod)
    startedData.userCounts.expected += injection.count
    if (injection.continue) {
      setTimer(injectionTimer, ScheduleNextInjection, injectorPeriod, repeat = false)
      stay() using startedData
    } else {
      stay() using startedData.copy(injectionContinue = false)
    }
  }
  // -- STEP 2 : The Controller is fully initialized, Simulation is now running -- //

  when(Started) {
    case Event(UserMessage(_, End, _), startedData: StartedData) =>
      processUserMessage(startedData)

    case Event(ScheduleNextInjection, startedData: StartedData) =>
      inject(startedData, injectorPeriod)

    case Event(ForceStop(exception), startedData: StartedData) =>
      stop(startedData, exception)
  }

  private def processUserMessage(startedData: StartedData): State = {

    startedData.userCounts.completed += 1

    if (startedData.userCounts.allStopped && !startedData.injectionContinue)
      stop(startedData, None)
    else
      stay()
  }

  private def stop(startedData: StartedData, exception: Option[Exception]): State = {
    cancelTimer(maxDurationTimer)
    cancelTimer(injectionTimer)
    statsEngine.stop(self)
    goto(WaitingForResourcesToStop) using EndData(startedData.initData, exception)
  }

  // -- STEP 3 : Waiting for StatsEngine to stop, discarding all other messages -- //

  when(WaitingForResourcesToStop) {
    case Event(StatsEngineStopped, endData: EndData) =>
      endData.initData.launcher ! replyToLauncher(endData)
      goto(Stopped) using NoData

    case Event(message, _) =>
      logger.debug(s"Ignore message $message while waiting for StatsEngine to stop")
      stay()
  }

  private def replyToLauncher(endData: EndData): Try[Unit] =
    endData.exception match {
      case Some(exception) => Failure(exception)
      case None            => Success(())
    }

  // -- STEP 4 : Controller has been stopped, all new messages will be discarded -- //

  when(Stopped) {
    case Event(message, NoData) =>
      logger.debug(s"Ignore message $message since Controller has been stopped")
      stay()
  }

  initialize()
}
