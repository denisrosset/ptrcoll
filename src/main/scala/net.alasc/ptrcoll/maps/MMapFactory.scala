package net.alasc.ptrcoll
package maps

import scala.{specialized => sp}
import scala.reflect.ClassTag

import spire.syntax.cfor._

trait MMapFactory[KLB, KExtra[_], VLB] {
  type KLBEv[K] = K <:< KLB
  def empty[@sp(Int, Long) K: ClassTag: KExtra: KLBEv, V: ClassTag]: MMap[K, V]
  def ofSize[@sp(Int, Long) K: ClassTag: KExtra: KLBEv, V: ClassTag](n: Int): MMap[K, V]
  def fromMap[@sp(Int, Long) K: ClassTag: KExtra: KLBEv, V: ClassTag](map: Map[K, V]): MMap[K, V] = {
    val mmap = empty[K, V]
    val keyIt = map.keysIterator
    while (keyIt.hasNext) {
      val k = keyIt.next
      mmap(k) = map(k)
    }
    mmap
  }
  def fromArrays[@sp(Int, Long) K: ClassTag: KExtra: KLBEv, V: ClassTag](keysArray: Array[K], valuesArray: Array[V]): MMap[K, V] = {
    val mmap = empty[K, V]
    cforRange(0 until keysArray.length) { i =>
      mmap(keysArray(i)) = valuesArray(i)
    }
    mmap
  }
}

trait MMap2Factory[KLB, KExtra[_], VLB1, VLB2] {
  type KLBEv[K] = K <:< KLB
  def empty[@sp(Int, Long) K: ClassTag: KExtra: KLBEv, V1: ClassTag, V2: ClassTag]: MMap2[K, V1, V2]
  def ofSize[@sp(Int, Long) K: ClassTag: KExtra: KLBEv, V1: ClassTag, V2: ClassTag](n: Int): MMap2[K, V1, V2]
  def fromMap[@sp(Int, Long) K: ClassTag: KExtra: KLBEv, V1: ClassTag, V2: ClassTag](map: Map[K, (V1, V2)]): MMap2[K, V1, V2] = {
    val mmap = empty[K, V1, V2]
    val keyIt = map.keysIterator
    while (keyIt.hasNext) {
      val k = keyIt.next
      val (v1, v2) = map(k)
      mmap.update(k, v1, v2)
    }
    mmap
  }
  def fromArrays[@sp(Int, Long) K: ClassTag: KExtra: KLBEv, V1: ClassTag, V2: ClassTag](keysArray: Array[K], values1Array: Array[V1], values2Array: Array[V2]): MMap2[K, V1, V2] = {
    val mmap = empty[K, V1, V2]
    cforRange(0 until keysArray.length) { i =>
      mmap.update(keysArray(i), values1Array(i), values2Array(i))
    }
    mmap
  }
}
