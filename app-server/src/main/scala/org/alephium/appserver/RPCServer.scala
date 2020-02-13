package org.alephium.appserver

import scala.concurrent._

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.TextMessage
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.{ByteString, Timeout}
import com.typesafe.scalalogging.StrictLogging
import io.circe._
import io.circe.syntax._

import org.alephium.appserver.RPCModel._
import org.alephium.crypto.{ED25519PrivateKey, ED25519PublicKey}
import org.alephium.flow.client.{FairMiner, Miner}
import org.alephium.flow.core.{BlockFlow, MultiChain}
import org.alephium.flow.network.DiscoveryServer
import org.alephium.flow.platform.{Mode, PlatformProfile}
import org.alephium.protocol.config.ConsensusConfig
import org.alephium.protocol.model.{BlockHeader, CliqueInfo, GroupIndex, Transaction}
import org.alephium.protocol.script.PubScript
import org.alephium.rpc.{CORSHandler, JsonRPCHandler}
import org.alephium.rpc.model.JsonRPC._
import org.alephium.rpc.util.AVectorJson._
import org.alephium.util.{AVector, EventBus, Hex, TimeStamp}

class RPCServer(mode: Mode) extends RPCServerAbstract {
  import RPCServer._

  implicit val system: ActorSystem                = mode.node.system
  implicit val materializer: ActorMaterializer    = ActorMaterializer()
  implicit val executionContext: ExecutionContext = system.dispatcher
  implicit val config: PlatformProfile            = mode.profile
  implicit val rpcConfig: RPCConfig               = RPCConfig.load(config.aleph)
  implicit val askTimeout: Timeout                = Timeout(rpcConfig.askTimeout.asScala)

  def doBlockflowFetch(req: Request): Future[Response] =
    Future.successful(blockflowFetch(mode.node.blockFlow, req))

  def doCliqueInfo(req: Request): Future[Response] =
    mode.node.discoveryServer.ask(DiscoveryServer.GetPeerCliques).map { result =>
      val cliques = result.asInstanceOf[DiscoveryServer.PeerCliques]
      Response.successful(req, cliques.peers)
    }

  def doGetBalance(req: Request): Future[Response] =
    Future.successful(getBalance(mode.node.blockFlow, req))

  def doTransfer(req: Request): Future[Response] =
    Future.successful(transfer(mode.node.blockFlow, req))

  def runServer(): Future[Unit] = {
    val miner = {
      val props = FairMiner.props(mode.node).withDispatcher("akka.actor.mining-dispatcher")
      system.actorOf(props, s"FairMiner")
    }

    Http()
      .bindAndHandle(routeHttp(miner), rpcConfig.networkInterface.getHostAddress, mode.rpcHttpPort)
      .map(_ => ())
    Http()
      .bindAndHandle(routeWs(mode.node.eventBus),
                     rpcConfig.networkInterface.getHostAddress,
                     mode.rpcWsPort)
      .map(_ => ())
  }
}

trait RPCServerAbstract extends StrictLogging {
  import RPCServer._

  implicit def system: ActorSystem
  implicit def materializer: ActorMaterializer
  implicit def executionContext: ExecutionContext
  implicit def config: PlatformProfile
  implicit def rpcConfig: RPCConfig
  implicit def askTimeout: Timeout

  def doBlockflowFetch(req: Request): Future[Response]
  def doCliqueInfo(req: Request): Future[Response]
  def doGetBalance(req: Request): Future[Response]
  def doTransfer(req: Request): Future[Response]
  def doStartMining(miner: ActorRef, req: Request): Future[Response] =
    execute(miner ! Miner.Start, req)
  def doStopMining(miner: ActorRef, req: Request): Future[Response] =
    execute(miner ! Miner.Stop, req)

  def runServer(): Future[Unit]

  def handleEvent(event: EventBus.Event): TextMessage = {
    // TODO Replace with concrete implementation.
    event match {
      case _ =>
        val ts     = System.currentTimeMillis()
        val result = Notification("events_fake", Some(ts.asJson))
        TextMessage(result.asJson.noSpaces)
    }
  }

  def handlerRPC(miner: ActorRef): Handler = Map.apply(
    "blockflow_fetch" -> (req => doBlockflowFetch(req)),
    "clique_info"     -> (req => doCliqueInfo(req)),
    "get_balance"     -> (req => doGetBalance(req)),
    "transfer"        -> (req => doTransfer(req)),
    "mining_start"    -> (req => doStartMining(miner, req)),
    "mining_stop"     -> (req => doStopMining(miner, req))
  )

  def routeHttp(miner: ActorRef): Route =
    CORSHandler(JsonRPCHandler.routeHttp(handlerRPC(miner)))

  def routeWs(eventBus: ActorRef): Route = {
    path("events") {
      CORSHandler(get {
        extractUpgradeToWebSocket { upgrade =>
          val (actor, source) =
            Source.actorRef(bufferSize, OverflowStrategy.fail).preMaterialize()
          eventBus.tell(EventBus.Subscribe, actor)
          val response = upgrade.handleMessages(wsFlow(eventBus, actor, source))
          complete(response)
        }
      })
    }
  }

  def wsFlow(eventBus: ActorRef,
             actor: ActorRef,
             source: Source[Nothing, NotUsed]): Flow[Any, TextMessage, Unit] = {
    Flow
      .fromSinkAndSourceCoupled(Sink.ignore, source.map(handleEvent))
      .watchTermination() { (_, termination) =>
        termination.onComplete(_ => eventBus.tell(EventBus.Unsubscribe, actor))
      }
  }
}

object RPCServer extends StrictLogging {
  import Response.Failure
  type Try[T] = Either[Failure, T]

  val bufferSize = 64

  implicit val encodeCliqueInfo: Encoder[CliqueInfo] = new Encoder[CliqueInfo] {
    final def apply(ci: CliqueInfo): Json = {
      Json.obj(("id", Json.fromString(ci.id.toString)),
               ("peers", encodeAVector[String].apply(ci.peers.map(_.toString))))
    }
  }

  implicit val cliquesEncoder: Encoder[AVector[CliqueInfo]] = encodeAVector[CliqueInfo]

  def withReq[T: Decoder](req: Request)(f: T => Response): Response = {
    req.paramsAs[T] match {
      case Right(query)  => f(query)
      case Left(failure) => failure
    }
  }

  def blockflowFetch(blockFlow: BlockFlow, req: Request)(implicit rpc: RPCConfig,
                                                         cfg: ConsensusConfig): Response = {
    withReq[FetchRequest](req) { query =>
      val now        = TimeStamp.now()
      val lowerBound = now - rpc.blockflowFetchMaxAge
      val from = query.from match {
        case Some(ts) => if (ts > lowerBound) ts else lowerBound
        case None     => lowerBound
      }

      val headers  = blockFlow.getHeadersUnsafe(header => header.timestamp > from)
      val response = FetchResponse(headers.map(wrapBlockHeader(blockFlow, _)))
      Response.successful(req, response)
    }
  }

  def getBalance(blockFlow: BlockFlow, req: Request): Response = {
    withReq[GetBalance](req) { query =>
      if (query.`type` == GetBalance.pkh) {
        val result = for {
          address <- decodeAddress(query.address)
          _       <- checkGroup(blockFlow, address)
          balance <- getP2pkhBalance(blockFlow, address)
        } yield balance
        result match {
          case Right(balance) => Response.successful(req, balance)
          case Left(error)    => error
        }
      } else {
        Response.failed(s"Invalid address type ${query.`type`}")
      }
    }
  }

  def decodeAddress(raw: String): Try[ED25519PublicKey] = {
    val addressOpt = for {
      bytes   <- Hex.from(raw)
      address <- ED25519PublicKey.from(bytes)
    } yield address

    addressOpt match {
      case Some(address) => Right(address)
      case None          => Left(Response.failed("Failed in decoding address"))
    }
  }

  def decodePublicKey(raw: String): Try[ED25519PublicKey] =
    decodeRandomBytes(raw, ED25519PublicKey.from, "public key")

  def decodePrivateKey(raw: String): Try[ED25519PrivateKey] =
    decodeRandomBytes(raw, ED25519PrivateKey.from, "private key")

  def decodeRandomBytes[T](raw: String, from: ByteString => Option[T], name: String): Try[T] = {
    val addressOpt = for {
      bytes   <- Hex.from(raw)
      address <- from(bytes)
    } yield address

    addressOpt match {
      case Some(address) => Right(address)
      case None          => Left(Response.failed(s"Failed in decoding $name"))
    }
  }

  def getP2pkhBalance(blockFlow: BlockFlow, address: ED25519PublicKey): Try[Balance] = {
    blockFlow.getP2pkhUtxos(address) match {
      case Right(utxos) => Right(Balance(utxos.sumBy(_._2.value), utxos.length))
      case Left(_)      => failedInIO
    }
  }

  def transfer(blockFlow: BlockFlow, req: Request): Response = {
    withReq[Transfer](req) { query =>
      if (query.fromType == GetBalance.pkh && query.toType == GetBalance.pkh) {
        val result = for {
          fromAddress    <- decodePublicKey(query.fromAddress)
          _              <- checkGroup(blockFlow, fromAddress)
          toAddress      <- decodePublicKey(query.toAddress)
          fromPrivateKey <- decodePrivateKey(query.fromPrivateKey)
          transaction <- prepareTransaction(blockFlow,
                                            fromAddress,
                                            toAddress,
                                            query.value,
                                            fromPrivateKey)
        } yield {
          // publish transaction
          Response.successful(req, transaction.shortHex)
        }
        result match {
          case Right(response) => response
          case Left(error)     => error
        }
      } else {
        Response.failed(s"Invalid address types: ${query.fromType} or ${query.toType}")
      }
    }
  }

  def prepareTransaction(blockFlow: BlockFlow,
                         fromAddress: ED25519PublicKey,
                         toAddress: ED25519PublicKey,
                         value: BigInt,
                         fromPrivateKey: ED25519PrivateKey): Try[Transaction] = {
    blockFlow.prepareP2pkhTx(fromAddress, toAddress, value, fromPrivateKey) match {
      case Right(Some(transaction)) => Right(transaction)
      case Right(None)              => Left(Response.failed("Not enough balance"))
      case Left(_)                  => failedInIO
    }
  }

  def checkGroup(blockFlow: BlockFlow, address: ED25519PublicKey): Try[Unit] = {
    val pubScript  = PubScript.p2pkh(address)
    val groupIndex = GroupIndex.from(pubScript)(blockFlow.config)
    if (blockFlow.config.brokerInfo.contains(groupIndex)) Right(())
    else Left(Response.failed(s"Address ${address.shortHex} belongs to other groups"))
  }

  def wrapBlockHeader(chain: MultiChain, header: BlockHeader)(
      implicit config: ConsensusConfig): FetchEntry = {
    val index = header.chainIndex

    FetchEntry(
      hash      = header.shortHex,
      timestamp = header.timestamp,
      chainFrom = index.from.value,
      chainTo   = index.to.value,
      height    = chain.getHeight(header),
      deps      = header.blockDeps.toIterable.map(_.shortHex).toList
    )
  }

  def execute(f: => Unit, req: Request)(implicit ec: ExecutionContext): Future[Response] = Future {
    f
    Response.successful(req)
  }

  def failedInIO[T]: Try[T] = Left(Response.failed("Failed in IO"))
}
