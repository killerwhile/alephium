// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.protocol.model

import org.alephium.protocol.{ALF, BlockHash, Hash}
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.mining.PoW
import org.alephium.serde.{u256Serde => _, _}
import org.alephium.util.{AVector, TimeStamp, U256}

final case class BlockHeader(
    blockDeps: BlockDeps,
    txsHash: Hash,
    timestamp: TimeStamp,
    target: Target,
    nonce: U256
) extends FlowData {
  lazy val hash: BlockHash = PoW.hash(this)

  lazy val chainIndex: ChainIndex = {
    val groups = (blockDeps.length + 1) / 2
    ChainIndex.from(hash, groups)
  }

  def isGenesis: Boolean = timestamp == ALF.GenesisTimestamp

  def parentHash(implicit config: GroupConfig): BlockHash = {
    assume(!isGenesis)
    blockDeps.uncleHash(chainIndex.to)
  }

  def uncleHash(toIndex: GroupIndex)(implicit config: GroupConfig): BlockHash = {
    assume(!isGenesis)
    blockDeps.uncleHash(toIndex)
  }

  def inDeps(implicit config: GroupConfig): AVector[BlockHash] = {
    assume(!isGenesis)
    blockDeps.inDeps
  }

  def outDeps(implicit config: GroupConfig): AVector[BlockHash] = {
    assume(!isGenesis)
    blockDeps.outDeps
  }

  def intraDep(implicit config: GroupConfig): BlockHash = {
    assume(!isGenesis)
    blockDeps.intraDep(chainIndex)
  }

  def outTips(implicit config: GroupConfig): AVector[BlockHash] = {
    assume(!isGenesis)
    blockDeps.outDeps.replace(chainIndex.to.value, hash)
  }
}

object BlockHeader {
  // use fixed width bytes for U256 serialization
  private implicit val nonceSerde: Serde[U256] = Serde.bytesSerde(32).xmap(U256.unsafe, _.toBytes)

  implicit val serde: Serde[BlockHeader] =
    Serde.forProduct5(apply, bh => (bh.blockDeps, bh.txsHash, bh.timestamp, bh.target, bh.nonce))

  def genesis(txsHash: Hash, target: Target, nonce: U256)(
      implicit config: GroupConfig): BlockHeader = {
    val deps = BlockDeps.build(AVector.fill(config.depsNum)(BlockHash.zero))
    BlockHeader(deps, txsHash, ALF.GenesisTimestamp, target, nonce)
  }

  def unsafe(deps: AVector[BlockHash],
             txsHash: Hash,
             timestamp: TimeStamp,
             target: Target,
             nonce: U256)(implicit config: GroupConfig): BlockHeader = {
    val blockDeps = BlockDeps.build(deps)
    BlockHeader(blockDeps, txsHash, timestamp, target, nonce)
  }
}
