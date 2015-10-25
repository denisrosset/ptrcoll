package metal
package impl

import scala.annotation.{switch, tailrec}
import scala.{specialized => sp}
import scala.reflect.ClassTag

import spire.algebra.Order
import spire.syntax.cfor._
import spire.util.Opt

/** Mutable hash map where values are pairs (V1, V2). */
class HashMap2Impl[K, V1, V2](
  /** Slots for keys. */
  var keys: Array[K],
  /** Slots for values of type 1. */
  var values1: Array[V1],
  /** Slots for values of type 2. */
  var values2: Array[V2],
  /** Status of the slots in the hash table.
    * 
    * 0 = unused
    * 2 = once used, now empty but not yet overwritten
    * 3 = used
    */ 
  var buckets: Array[Byte],
  /** Number of defined slots. */
  var len: Int,
  /** Number of used slots (used >= len). */
  var used: Int,
  // hashing internals
  /** size - 1, used for hashing. */
  var mask: Int,
  /** Point at which we should grow. */
  var limit: Int)(implicit val K: Methods[K], val V1: Methods[V1], val V2: Methods[V2]) extends IHashMap2[K, V1, V2] with MHashMap2[K, V1, V2] {

  final def size: Long = len

  final override def isEmpty: Boolean = len == 0

  final override def nonEmpty: Boolean = len > 0

  def result(): IHashMap2[K, V1, V2] = this

  def mutableCopy(): MHashMap2[K, V1, V2] = new HashMap2Impl[K, V1, V2](
    keys = keys.clone,
    values1 = values1.clone,
    values2 = values2.clone,
    buckets = buckets.clone,
    len = len,
    used = used,
    mask = mask,
    limit = limit)

  def keyArray(ptr: VPtr[Tag]): Array[K] = keys
  def keyIndex(ptr: VPtr[Tag]): Int = ptr.v.toInt
  def value1Array(ptr: VPtr[Tag]): Array[V1] = values1
  def value1Index(ptr: VPtr[Tag]): Int = ptr.v.toInt
  def value2Array(ptr: VPtr[Tag]): Array[V2] = values2
  def value2Index(ptr: VPtr[Tag]): Int = ptr.v.toInt


  final def ptrAddKey[@specialized L](key: L): VPtr[Tag] = {
    val keysL = keys.asInstanceOf[Array[L]]
    @inline @tailrec def loop(i: Int, perturbation: Int): VPtr[Tag] = {
      val j = i & mask
      val status = buckets(j)
      if (status == 0) {
        keysL(j) = key
        buckets(j) = 3
        len += 1
        used += 1
        if (used > limit) {
          grow()
          val VPtr(vp) = ptrFind[L](key)
          vp
        } else VPtr[Tag](j)
      } else if (status == 2 && ptrFind[L](key).isNull) {
        keysL(j) = key
        buckets(j) = 3
        len += 1
        VPtr[Tag](j)
      } else if (keysL(j) == key) {
        VPtr[Tag](j)
      } else {
        loop((i << 2) + i + perturbation + 1, perturbation >> 5)
      }
    }
    val i = key.## & 0x7fffffff
    loop(i, i)
  }

  final def ptrRemoveAndAdvance(ptr: VPtr[Tag]): Ptr[Tag] = {
    val next = ptrNext(ptr)
    ptrRemove(ptr)
    next
  }

  final def ptrRemove(ptr: VPtr[Tag]): Unit = {
    val j = ptr.v.toInt
    buckets(j) = 2
    values1(j) = null.asInstanceOf[V1]
    values2(j) = null.asInstanceOf[V2]
    len -= 1
  }

  final def ptrFind[@specialized L](key: L): Ptr[Tag] = {
    val keysL = keys.asInstanceOf[Array[L]]
    @inline @tailrec def loop(i: Int, perturbation: Int): Ptr[Tag] = {
      val j = i & mask
      val status = buckets(j)
      if (status == 0) Ptr.Null[Tag]
      else if (status == 3 && keysL(j) == key) VPtr[Tag](j)
      else loop((i << 2) + i + perturbation + 1, perturbation >> 5)
    }
    val i = key.## & 0x7fffffff
    loop(i, i)
  }

  /** Absorbs the given map's contents into this map.
    * 
    * This method does not copy the other map's contents. Thus, this
    * should only be used when there are no saved references to the
    * other map. It is private, and exists primarily to simplify the
    * implementation of certain methods.
    * 
    * This is an O(1) operation, although it can potentially generate a
    * lot of garbage (if the map was previously large).
    */
  private[this] def absorb(rhs: MHashMap2[K, V1, V2]): Unit = {
    keys = rhs.keys
    values1 = rhs.values1
    values2 = rhs.values2
    buckets = rhs.buckets
    len = rhs.len
    used = rhs.used
    mask = rhs.mask
    limit = rhs.limit
  }

  /**
    * Grow the underlying array to best accomodate the map's size.
    * 
    * To preserve hashing access speed, the map's size should never be
    * more than 66% of the underlying array's size. When this size is
    * reached, the map needs to be updated (using this method) to have a
    * larger array.
    * 
    * The underlying array's size must always be a multiple of 2, which
    * means this method grows the array's size by 2x (or 4x if the map
    * is very small). This doubling helps amortize the cost of
    * resizing, since as the map gets larger growth will happen less
    * frequently. This method returns a null of type Unit1[A] to
    * trigger specialization without allocating an actual instance.
    * 
    * Growing is an O(n) operation, where n is the map's size.
    */
  final def grow(): Unit = {
    val next = keys.length * (if (keys.length < 10000) 4 else 2)
    val map = MHashMap2.ofSize[K, V1, V2](next)
    cfor(0)(_ < buckets.length, _ + 1) { i =>
      if (buckets(i) == 3) {
        val vp = map.ptrAddKeyFromArray(keys, i)
        map.ptrUpdate1FromArray(vp, values1, i)
        map.ptrUpdate2FromArray(vp, values2, i)
      }
    }
    absorb(map)
  }

  final def ptr: Ptr[Tag] = {
    var i = 0
    while (i < buckets.length && buckets(i) != 3) i += 1
    if (i < buckets.length) VPtr[Tag](i) else Ptr.Null[Tag]
  }

  final def ptrNext(ptr: VPtr[Tag]): Ptr[Tag] = {
    var i = ptr.v.toInt + 1
    while (i < buckets.length && buckets(i) != 3) i += 1
    if (i < buckets.length) VPtr[Tag](i) else Ptr.Null[Tag]
  }

  final def ptrKey[@specialized L](ptr: VPtr[Tag]): L = keys.asInstanceOf[Array[L]](ptr.v.toInt)

  final def ptrValue1[@specialized W1](ptr: VPtr[Tag]): W1 = values1.asInstanceOf[Array[W1]](ptr.v.toInt)

  final def ptrValue2[@specialized W2](ptr: VPtr[Tag]): W2 = values2.asInstanceOf[Array[W2]](ptr.v.toInt)

  final def ptrUpdate1[@specialized W1](ptr: VPtr[Tag], v: W1): Unit = {
    values1.asInstanceOf[Array[W1]](ptr.v.toInt) = v
  }

  final def ptrUpdate2[@specialized W2](ptr: VPtr[Tag], v: W2): Unit = {
    values2.asInstanceOf[Array[W2]](ptr.v.toInt) = v
  }

}

object HashMap2Impl {

  /** Allocates an empty HashMap2, with underlying storage of size n.
    * 
    * This method is useful if you know exactly how big you want the
    * underlying array to be. In most cases ofSize() is probably what
    * you want instead.
    */
  private[metal] def ofAllocatedSize[K, V1, V2](n: Int)(implicit K: Methods[K], V1: Methods[V1], V2: Methods[V2]) = {
    import K.{classTag => ctK}
    import V1.{classTag => ctV1}
    import V2.{classTag => ctV2}
    val sz = Util.nextPowerOfTwo(n) match {
      case n if n < 0 => sys.error(s"Bad allocated size $n for collection")
      case 0 => 8
      case n => n
    }
    new HashMap2Impl[K, V1, V2](
      keys = K.newArray(sz),
      values1 = V1.newArray(sz),
      values2 = V2.newArray(sz),
      buckets = new Array[Byte](sz),
      len = 0,
      used = 0,
      mask = sz - 1,
      limit = (sz * 0.65).toInt)
  }

}