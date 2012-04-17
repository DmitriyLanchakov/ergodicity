package integration.ergodicity.engine.core

import org.slf4j.LoggerFactory
import java.io.File
import org.scalatest.WordSpec
import plaza2.RequestType.CombinedDynamic
import plaza2.{TableSet, Connection => P2Connection, DataStream => P2DataStream}
import java.util.concurrent.TimeUnit
import akka.actor.{Actor, Props, ActorSystem}
import akka.actor.FSM.{Transition, SubscribeTransitionCallBack}
import com.ergodicity.engine.plaza2.DataStream.{JoinTable, SetLifeNumToIni, Open}
import com.ergodicity.engine.plaza2.Repository.{Snapshot, SubscribeSnapshots}
import com.ergodicity.engine.plaza2.Connection.{ProcessMessages, Connect}
import com.ergodicity.engine.plaza2.scheme.OptInfo.SessContentsRecord
import com.ergodicity.engine.plaza2._
import AkkaIntegrationConfigurations._
import scheme.{Deserializer, OptInfo}
import com.ergodicity.engine.core.model._
import akka.testkit.{ImplicitSender, TestActorRef, TestFSMRef, TestKit}

class OptionsDataStreamIntegrationSpec extends TestKit(ActorSystem("FuturesDataStreamIntegrationSpec", ConfigWithDetailedLogging)) with ImplicitSender with WordSpec {
  val log = LoggerFactory.getLogger(classOf[ConnectionSpec])


  val Host = "localhost"
  val Port = 4001
  val AppName = "OptionsDataStreamIntegrationSpec"

  "DataStream" must {
    "do some stuff" in {
      val underlyingConnection = P2Connection()
      val connection = system.actorOf(Props(Connection(underlyingConnection)), "Connection")
      connection ! Connect(Host, Port, AppName)

      connection ! SubscribeTransitionCallBack(system.actorOf(Props(new Actor {
        protected def receive = {
          case Transition(_, _, ConnectionState.Connected) => connection ! ProcessMessages(100);
        }
      })))

      val ini = new File("core/scheme/OptInfo.ini")
      val tableSet = TableSet(ini)
      val underlyingStream = P2DataStream("FORTS_OPTINFO_REPL", CombinedDynamic, tableSet)

      val dataStream = TestFSMRef(new DataStream(underlyingStream), "OptionsInfo")
      dataStream ! SetLifeNumToIni(ini)

      // Handle options data
      val repository = TestFSMRef(new Repository[SessContentsRecord](), "SessContentRepo")

      dataStream ! JoinTable(repository, "opt_sess_contents", implicitly[Deserializer[OptInfo.SessContentsRecord]])
      dataStream ! Open(underlyingConnection)

      val options = TestActorRef(new StatelessSessionContents[OptionContract, OptInfo.SessContentsRecord](SessionState.Online), "Options")
      options ! JoinSession(self)

      repository ! SubscribeSnapshots(TestActorRef(new Actor {
        protected def receive = {
          case snapshot@Snapshot(_, data: Iterable[SessContentsRecord]) =>
            log.info("Got snapshot")
            data.foreach(sessionContents =>
              log.info("SessContents: " + sessionContents)
            )
          options ! snapshot
        }
      }))

      Thread.sleep(TimeUnit.DAYS.toMillis(10))
    }
  }
}