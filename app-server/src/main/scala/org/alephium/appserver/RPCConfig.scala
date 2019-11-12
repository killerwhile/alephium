package org.alephium.appserver

import java.net.InetAddress

import com.typesafe.config.Config

import org.alephium.util.Duration

case class RPCConfig(
    networkInterface: InetAddress,
    blockflowFetchMaxAge: Duration,
    askTimeout: Duration
)

object RPCConfig {
  def load(implicit config: Config): RPCConfig = {
    val rpc = config.getConfig("rpc")
    RPCConfig(
      InetAddress.getByName(rpc.getString("network.interface")),
      Duration.from(rpc.getDuration("blockflowFetch.maxAge")),
      Duration.from(rpc.getDuration("ask.timeout"))
    )
  }
}