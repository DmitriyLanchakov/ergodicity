package com.ergodicity.engine.strategy

import akka.actor._
import akka.dispatch.Await
import akka.pattern.ask
import akka.pattern.pipe
import akka.util.duration._
import akka.util.{Duration, Timeout}
import com.ergodicity.core.PositionsTracking.{GetPositions, Positions}
import com.ergodicity.core.order.FillOrder
import com.ergodicity.core.order.OrderActor.OrderEvent
import com.ergodicity.core.position.Position
import com.ergodicity.core.session.{InstrumentState, Instrument}
import com.ergodicity.core.{Isin, position}
import com.ergodicity.engine.StrategyEngine
import com.ergodicity.engine.service.Trading.{Buy, Sell, ExecutionReport}
import com.ergodicity.engine.service.{Trading, Portfolio}
import com.ergodicity.engine.strategy.CloseAllPositionsState.RemainingPositions
import com.ergodicity.engine.strategy.InstrumentWatchDog.{CatchedState, Catched, WatchDogConfig}
import com.ergodicity.engine.strategy.Strategy.{Stop, Start}
import scala.collection.{immutable, mutable}

object CloseAllPositions {

  implicit case object CloseAllPositions extends StrategyId

  def apply() = new StrategiesFactory {

    def strategies = (strategy _ :: Nil)

    def strategy(engine: StrategyEngine) = Props(new CloseAllPositions(engine))
  }
}

sealed trait CloseAllPositionsState

object CloseAllPositionsState {

  case object Ready extends CloseAllPositionsState

  case object ClosingPositions extends CloseAllPositionsState

  case object PositionsClosed extends CloseAllPositionsState

  case class RemainingPositions(positions: immutable.Map[Isin, Position] = Map()) {
    def closedAll = positions.values.foldLeft(true)((b, a) => b && a == Position.flat)

    def fill(isin: Isin, amount: Int) = copy(positions = positions.transform {
      case (i, Position(pos)) if (i == isin) =>
        if (pos > 0)
          Position(pos - amount)
        else
          Position(pos + amount)
      case (_, pos) => pos
    })
  }

}

class CloseAllPositions(val engine: StrategyEngine)(implicit id: StrategyId) extends Strategy with Actor with LoggingFSM[CloseAllPositionsState, RemainingPositions] with InstrumentWatcher {

  import CloseAllPositionsState._

  val portfolio = engine.services(Portfolio.Portfolio)
  val trading = engine.services(Trading.Trading)

  // Configuration and implicits
  implicit object WatchDog extends WatchDogConfig(self, true, true)

  implicit val timeout = Timeout(1.second)

  implicit val executionContext = context.system

  // Positions that we are going to close
  val positions: Map[Isin, Position] = getOpenedPositions(5.seconds)

  val instruments = mutable.Map[Isin, Instrument]()
  val states = mutable.Map[Isin, InstrumentState]()
  val executions = mutable.Map[Isin, ExecutionReport]()

  override def preStart() {
    log.info("Started CloseAllPositions")
    log.debug("Going to close positions = " + positions)
    engine.reportReady(positions)
  }

  startWith(Ready, RemainingPositions(positions))

  when(Ready) {
    case Event(Start, _) =>
      positions.keys foreach watchInstrument
      goto(ClosingPositions)
  }

  when(ClosingPositions) {
    case Event(OrderEvent(order, FillOrder(price, amount)), remaining) if (executions.values.find(_.order == order).isDefined) =>
      val executionReport = executions.values.find(_.order == order).get
      val isin = executionReport.security.isin
      val updated = remaining.fill(isin, amount)

      if (updated.closedAll)
        goto(PositionsClosed)
      else
        stay() using updated
  }

  when(PositionsClosed) {
    case Event(Stop, _) => stop(FSM.Shutdown)
  }

  whenUnhandled {
    case Event(Catched(isin, session, instrument, ref), _) =>
      instruments(isin) = instrument
      tryClose(isin)
      stay()

    case Event(CatchedState(isin, state), _) =>
      states(isin) = state
      tryClose(isin)
      stay()

    case Event(execution: ExecutionReport, _) =>
      executions(execution.security.isin) = execution
      execution.subscribeOrderEvents(self)
      stay()
  }

  private def tryClose(isin: Isin) {
    val tuple = instruments get isin flatMap {
      case i => states get isin map ((i, _))
    }

    tuple match {
      case Some((Instrument(security, limits), InstrumentState.Online)) if (positions(isin).dir == position.Long) =>
        (trading ? Sell(security, positions(isin).pos.abs, limits.lower)) pipeTo self

      case Some((Instrument(security, limits), InstrumentState.Online)) if (positions(isin).dir == position.Short) =>
        (trading ? Buy(security, positions(isin).pos.abs, limits.upper)) pipeTo self

      case _ =>
    }
  }


  private def getOpenedPositions(atMost: Duration): Map[Isin, Position] = {
    val future = (portfolio ? GetPositions).mapTo[Positions]
    Await.result(future, atMost).positions
  }
}
