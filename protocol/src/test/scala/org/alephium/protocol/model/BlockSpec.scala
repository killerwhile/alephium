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

import scala.util.Random

import org.scalacheck.Gen

import org.alephium.crypto.{Blake2b, Blake3, MerkleHashable}
import org.alephium.protocol.{ALF, BlockHash, Hash, PublicKey, Signature}
import org.alephium.protocol.config.NetworkConfigFixture
import org.alephium.protocol.vm.{GasPrice, LockupScript, StatefulScript}
import org.alephium.serde._
import org.alephium.util.{AlephiumSpec, AVector, TimeStamp, U256}

class BlockSpec extends AlephiumSpec with NoIndexModelGenerators with NetworkConfigFixture.Default {
  it should "serde" in {
    forAll(blockGen) { block =>
      val bytes  = serialize[Block](block)
      val output = deserialize[Block](bytes).toOption.get
      output is block
    }
  }

  it should "hash" in {
    forAll(blockGen) { block =>
      val expected = Blake3.hash(Blake3.hash(serialize(block.header)).bytes)
      block.hash is block.header.hash
      block.hash is expected
    }
  }

  it should "hash transactions" in {
    forAll(blockGen) { block =>
      val expected = MerkleHashable.rootHash(Blake2b, block.transactions)
      block.header.txsHash is expected
    }
  }

  it should "calculate chain index" in {
    forAll(chainIndexGen) { chainIndex =>
      val block = blockGen(chainIndex).sample.get
      block.chainIndex is chainIndex
    }
  }

  it should "calculate proper execution order" in {
    forAll(blockGen) { block =>
      val order = block.getNonCoinbaseExecutionOrder
      order.length is block.transactions.length - 1
      order.sorted is AVector.tabulate(block.transactions.length - 1)(identity)
    }
  }

  it should "be random" in {
    def gen(): Block = {
      val header: BlockHeader =
        BlockHeader.unsafeWithRawDeps(
          AVector.fill(groupConfig.depsNum)(BlockHash.zero),
          Hash.zero,
          Hash.zero,
          TimeStamp.now(),
          Target.Max,
          Nonce.zero
        )
      val txs: AVector[Transaction] =
        AVector.fill(3)(
          Transaction.from(
            UnsignedTransaction(
              Some(StatefulScript.unsafe(AVector.empty)),
              minimalGas,
              GasPrice(U256.unsafe(Random.nextLong(Long.MaxValue))),
              AVector.empty,
              AVector.empty
            ),
            AVector.empty[Signature]
          )
        )
      Block(header, txs :+ txs.head) // add a fake coinbase tx
    }
    val blockGen = Gen.const(()).map(_ => gen())

    Seq(0, 1, 2).permutations.foreach { orders =>
      val blockGen0 = blockGen.retryUntil(_.getScriptExecutionOrder equals AVector.from(orders))
      blockGen0.sample.nonEmpty is true
    }
  }

  it should "put non-script txs in the last" in {
    forAll(posLongGen, posLongGen) { (gasPrice0: Long, gasPrice1: Long) =>
      val header: BlockHeader =
        BlockHeader.unsafeWithRawDeps(
          AVector.fill(groupConfig.depsNum)(BlockHash.zero),
          Hash.zero,
          Hash.zero,
          TimeStamp.now(),
          Target.Max,
          Nonce.zero
        )
      val tx0 = Transaction.from(
        UnsignedTransaction(
          Some(StatefulScript.unsafe(AVector.empty)),
          minimalGas,
          GasPrice(U256.unsafe(gasPrice0)),
          AVector.empty,
          AVector.empty
        ),
        AVector.empty[Signature]
      )
      val tx1 = Transaction.from(
        UnsignedTransaction(
          None,
          minimalGas,
          GasPrice(U256.unsafe(gasPrice1)),
          AVector.empty,
          AVector.empty
        ),
        AVector.empty[Signature]
      )
      val coinbase = Transaction.coinbase(
        ChainIndex.unsafe(0, 0),
        U256.Zero,
        LockupScript.p2pkh(PublicKey.generate),
        Target.Max,
        ALF.LaunchTimestamp
      )

      val block0 = Block(header, AVector(tx0, tx1, coinbase))
      Block.scriptIndexes(block0.nonCoinbase).toSeq is Seq(0)
      block0.getNonCoinbaseExecutionOrder.last is 1
      val block1 = Block(header, AVector(tx1, tx0, coinbase))
      Block.scriptIndexes(block1.nonCoinbase).toSeq is Seq(1)
      block1.getNonCoinbaseExecutionOrder.last is 0
    }
  }
}
