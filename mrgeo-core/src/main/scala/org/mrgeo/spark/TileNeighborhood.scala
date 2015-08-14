/*
 * Copyright 2009-2015 DigitalGlobe, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package org.mrgeo.spark

import java.io.{Externalizable, ObjectInput, ObjectOutput}

import org.apache.spark.{Logging, SparkContext}
import org.apache.spark.SparkContext._
import org.apache.spark.graphx.{Edge, EdgeContext, EdgeDirection, Graph}
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel
import org.mrgeo.data.raster.{RasterUtils, RasterWritable}
import org.mrgeo.data.tile.TileIdWritable
import org.mrgeo.utils.{SparkUtils, TMSUtils}

import scala.collection.mutable.ListBuffer


object TileNeighborhood extends Logging {
  def createNeighborhood(tiles:RDD[(TileIdWritable, RasterWritable)],
      offsetX:Int, offsetY:Int, width:Int, height:Int, tilebounds: TMSUtils.TileBounds,
      zoom:Int, tilesize:Int, nodata:Double, context:SparkContext):RDD[(Long, TileNeighborhood)] = {

    def buildEdges(tiles: RDD[(TileIdWritable, RasterWritable)],
        offsetX: Int, offsetY: Int, zoom: Int): RDD[Edge[EdgeDirection]] = {

      tiles.flatMap(tile => {
        val edges = ListBuffer[Edge[EdgeDirection]]()

        val from = TMSUtils.tileid(tile._1.get(), zoom)
        for (y <- (from.ty + offsetY) to (from.ty - offsetY)) {
          for (x <- (from.tx + offsetX) to (from.tx - offsetX)) {
            if (tilebounds.contains(x, y)) {
              val to = TMSUtils.tileid(x, y, zoom)
              edges.append(new Edge(to, tile._1.get, EdgeDirection.In))
            }
          }
        }

        edges.iterator
      })

    }

    def buildNeighborhood(ec: EdgeContext[RasterWritable, EdgeDirection, TileNeighborhood]) = {

      val src = TMSUtils.tileid(ec.srcId, zoom)
      val dst = TMSUtils.tileid(ec.dstId, zoom)

      val x = (src.tx - dst.tx).toInt - offsetX // left to right
      val y = (dst.ty - src.ty).toInt - offsetY // bottom to top

      try {
        ec.sendToDst(new TileNeighborhood(offsetX, offsetY, width, height, x, y,
          (ec.srcId, ec.srcAttr)))
      }
      catch {
        case e: ArrayIndexOutOfBoundsException =>
          logError("src: id: " + ec.srcId + " tx: " + src.tx + " ty: " + src.ty)
          logError("dst: id: " + ec.dstId + " tx: " + dst.tx + " ty: " + dst.ty)
          logError("offset: x: " + offsetX + " y: " + offsetY)
          logError("x: " + x + " y: " + y)

          throw e
      }
    }


    def mergeNeighborhood(a: TileNeighborhood, b: TileNeighborhood):TileNeighborhood = {
      for (y <- a.neighborhood.indices) {
        for (x <- a.neighborhood(y).indices) {
          if (b.neighborhood(y)(x) != null) {
            assert(a.neighborhood(y)(x) == null)
            a.neighborhood(y)(x) = b.neighborhood(y)(x)
          }
        }
      }
      a
    }

    val edges = buildEdges(tiles, offsetX, offsetY, zoom)

    // map the tiles so the key is the tileid as a long
    val vertices = tiles.map(tile => {
      // NOTE:  Some record readers reuse key/value objects, so we make these unique here
      (tile._1.get(), new RasterWritable(tile._2))
    })

    val sample = RasterWritable.toRaster(tiles.first()._2)

    val defaultVertex =
      RasterWritable.toWritable(
        RasterUtils.createEmptyRaster(tilesize, tilesize, 1, sample.getTransferType, nodata), zoom)
    val graph = Graph(vertices, edges, defaultVertex,
      edgeStorageLevel = StorageLevel.MEMORY_AND_DISK_SER,
      vertexStorageLevel = StorageLevel.MEMORY_AND_DISK_SER)

    val neighborhoods = graph.aggregateMessages[TileNeighborhood](
      sendMsg = buildNeighborhood,
      mergeMsg = mergeNeighborhood)

    //    println("***: " + neighborhoods.count() + " ***")
    //    neighborhoods.foreach(n => {
    //      println("id: " + n._1)
    //      val neighborhood = n._2.neighborhood
    //      for (y <- neighborhood.indices) {
    //        for (x <- neighborhood(y).indices) {
    //          if (neighborhood(y)(x) == null){
    //            print(" null    ")
    //          }
    //          else {
    //            print(neighborhood(y)(x)._1 + "(" + SparkUtils.address(neighborhood(y)(x)._2) + ")  ")
    //          }
    //        }
    //        println()
    //      }
    //    })

    neighborhoods

  }


}

class TileNeighborhood() extends Externalizable {

  var neighborhood:Array[Array[(Long, RasterWritable)]] = null
  var offsetX:Int = Int.MinValue
  var offsetY:Int = Int.MinValue
  var width:Int = Int.MinValue
  var height:Int = Int.MinValue

  def this(offsetX: Int, offsetY: Int, width: Int, height:Int) {
    this()
    this.offsetX = offsetX
    this.offsetY = offsetY
    this.width = width
    this.height = height

    neighborhood = Array.ofDim[(Long, RasterWritable)](height, width)
  }

  def this(offsetX: Int, offsetY: Int, width: Int, height: Int, x: Int, y: Int,
      tile: (Long, RasterWritable))  {
    this(offsetX, offsetY, width, height)

    neighborhood(y)(x) = tile
  }


  override def readExternal(in: ObjectInput): Unit = {
    offsetX = in.readInt()
    offsetY = in.readInt()
    width = in.readInt()
    height = in.readInt()

    neighborhood = Array.ofDim[(Long, RasterWritable)](height, width)
    for (y <- neighborhood.indices) {
      for (x <- neighborhood(y).indices) {
        if (in.readBoolean()) {
          neighborhood(y)(x) =
              (in.readLong(), in.readObject().asInstanceOf[RasterWritable])
        }
      }
    }
  }

  override def writeExternal(out: ObjectOutput): Unit = {
    out.writeInt(offsetX)
    out.writeInt(offsetY)
    out.writeInt(width)
    out.writeInt(height)
    for (y <- neighborhood.indices) {
      for (x <- neighborhood(y).indices) {
        if (neighborhood(y)(x) == null) {
          out.writeBoolean(false)
        }
        else {
          out.writeBoolean(true)
          out.writeLong(neighborhood(y)(x)._1)
          out.writeObject(neighborhood(y)(x)._2)
        }
      }
    }
  }

  def anchor:RasterWritable = {
    neighborhood(-offsetY)(-offsetX)._2
  }

  def anchorId:TileIdWritable = {
    new TileIdWritable(neighborhood(-offsetY)(-offsetX)._1)
  }

  def neighbor(offsetX:Int, offsetY:Int):RasterWritable = {
    neighborhood(-this.offsetY + offsetY)(-this.offsetX + offsetX)._2
  }

  def neighborId(offsetX:Int, offsetY:Int):TileIdWritable = {
    new TileIdWritable(neighborhood(-this.offsetY + offsetY)(-this.offsetX + offsetX)._1)
  }

  def neighborTile(offsetX:Int, offsetY:Int):(TileIdWritable, RasterWritable) = {
    (new TileIdWritable(neighborhood(-this.offsetY + offsetY)(-this.offsetX + offsetX)._1),
        neighborhood(-this.offsetY + offsetY)(-this.offsetX + offsetX)._2)
  }

  def neighborAbsolute(x:Int, y:Int):RasterWritable = {
    neighborhood(y)(x)._2
  }

  def neighborIdAbsolute(x:Int, y:Int):TileIdWritable = {
    new TileIdWritable(neighborhood(y)(x)._1)
  }

  def neighborTileAbsolute(x:Int, y:Int):(TileIdWritable, RasterWritable) = {
    (new TileIdWritable(neighborhood(y)(x)._1), neighborhood(y)(x)._2)
  }

  def anchorX():Int = {
    -offsetX
  }
  def anchorY():Int = {
    -offsetY
  }

  def sizeof() = {
    println("Approximate neighborhood size: ")

    val size = (neighborhood(0)(0)._2.getBytes.length * width * height) + 16 + 8
    println(" class vars: 16b")
    println(neighborhood(0)(0)._2.getBytes.length + "b per raster tile (value)")
    println(" 8b per tileid (key)")
    println(" " + (width * height) + " neighbors")
    println("  total: " + size + "b (" + SparkUtils.kbtohuman(size / 1024) + ")")
  }
}