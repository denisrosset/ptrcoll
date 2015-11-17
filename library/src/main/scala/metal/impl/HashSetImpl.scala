package metal
package impl

import scala.annotation.{switch, tailrec}
import scala.reflect.ClassTag

import spire.algebra.Order
import spire.syntax.cfor._

import syntax._

class HashSetImpl[K](
  /** Slots for items. */
  var items: Array[K],
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
  var limit: Int)(implicit val K: Methods[K]) extends IHashSet[K] with MHashSet[K] { self =>

  type Cap = Nextable with Removable with Elements[K] with Keys[K] with Searchable[K]
  // Set implementation

  @inline final def isEmpty = len == 0

  @inline final def nonEmpty = !isEmpty

  def keyArray(ptr: MyVPtr): Array[K] = items
  def keyIndex(ptr: MyVPtr): Int = ptr.raw.toInt

  def result(): IHashSet[K] = this

  def mutableCopy: MHashSet[K] = new HashSetImpl[K](
    items = items.clone,
    buckets = buckets.clone,
    len = len,
    used = len,
    mask = mask,
    limit = limit)

  @inline final def size = len

  /**
    * Return whether the item is found in the HashSet or not.
    * 
    * On average, this is an O(1) operation; the (unlikely) worst-case
    * is O(n).
    */
  final def ptrFind[@specialized L](key: L): MyPtr = {
    val itemsL = items.asInstanceOf[Array[L]]
    @inline @tailrec def loop(i: Int, perturbation: Int): MyPtr = {
      val j = i & mask
      val status = buckets(j)
      if (status == 0) Ptr.`null`[Tag, Cap]
      else if (status == 3 && itemsL(j) == key) VPtr[Tag, Cap](j)
      else loop((i << 2) + i + perturbation + 1, perturbation >> 5)
    }
    val i = key.## & 0x7fffffff
    loop(i, i)
  }

  final def ptrRemoveAndAdvance(ptr: MyVPtr): MyPtr = {
    val next = ptrNext(ptr)
    ptrRemove(ptr)
    next
  }

  final def ptrRemove(ptr: MyVPtr): Unit = {
    val j = ptr.raw.toInt
    buckets(j) = 2
    items(j) = null.asInstanceOf[K]
    len -= 1
  }

  final def ptrAddKey[@specialized L](key: L): MyVPtr = {
    val itemsL = items.asInstanceOf[Array[L]]
    @inline def addHere(j: Int, oldStatus: Int): MyVPtr = {
      itemsL(j) = key
      buckets(j) = 3
      len += 1
      if (oldStatus == 0) {
        used += 1
        if (used > limit) {
          grow()
          ptrFind[L](key).get
        } else VPtr[Tag, Cap](j)
      } else VPtr[Tag, Cap](j)
    }

    @inline @tailrec def loop(i: Int, perturbation: Int): MyVPtr = {
      val j = i & mask
      val status = buckets(j)
      if (status == 3) {
        if (itemsL(j) == key)
          VPtr[Tag, Cap](j)
        else
          loop((i << 2) + i + perturbation + 1, perturbation >> 5)
      } else if (status == 2) ptrFind[L](key) match {
        case IsVPtr(vp) => vp
        case _ => addHere(j, status)
      } else addHere(j, status)
    }
    val i = key.## & 0x7fffffff
    loop(i, i)
  }

  /**
    * Grow the underlying array to best accomodate the set's size.
    * 
    * To preserve hashing access speed, the set's size should never be
    * more than 66% of the underlying array's size. When this size is
    * reached, the set needs to be updated (using this method) to have a
    * larger array.
    * 
    * The underlying array's size must always be a multiple of 2, which
    * means this method grows the array's size by 2x (or 4x if the set
    * is very small). This doubling helps amortize the cost of
    * resizing, since as the set gets larger growth will happen less
    * frequently. This method returns a null of type Unit1[A] to
    * trigger specialization without allocating an actual instance.
    * 
    * Growing is an O(n) operation, where n is the set's size.
    */
  final def grow(): Unit = {
    val next = buckets.length * (if (buckets.length < 10000) 4 else 2)
    val set = HashSetImpl.ofAllocatedSize[K](next)
    cfor(0)(_ < buckets.length, _ + 1) { i =>
      if (buckets(i) == 3) {
        set.ptrAddKeyFromArray(items, i)
      }
    }
    absorb(set)
  }

  /**
    * Aborb the given set's contents into this set.
    * 
    * This method does not copy the other set's contents. Thus, this
    * should only be used when there are no saved references to the
    * other set. It is private, and exists primarily to simplify the
    * implementation of certain methods.
    * 
    * This is an O(1) operation, although it can potentially generate a
    * lot of garbage (if the set was previously large).
    */
  private[this] def absorb(that: MHashSet[K]): Unit = {
    items = that.items
    buckets = that.buckets
    len = that.len
    used = that.used
    mask = that.mask
    limit = that.limit
  }

  final def ptr: MyPtr = {
    var i = 0
    while (i < buckets.length && buckets(i) != 3) i += 1
    if (i < buckets.length) VPtr[Tag, Cap](i) else Ptr.`null`[Tag, Cap]
  }

  final def ptrNext(ptr: MyVPtr): MyPtr = {
    var i = ptr.raw.toInt + 1
    while (i < buckets.length && buckets(i) != 3) i += 1
    if (i < buckets.length) VPtr[Tag, Cap](i) else Ptr.`null`[Tag, Cap]
  }

  final def ptrKey[@specialized L](ptr: MyVPtr): L =
    items.asInstanceOf[Array[L]](ptr.raw.toInt)

  final def ptrElement[@specialized E](ptr: MyVPtr): E =
    items.asInstanceOf[Array[E]](ptr.raw.toInt)

}

object HashSetImpl {

  /**
    * Allocate an empty HashSet, with underlying storage of size n.
    * 
    * This method is useful if you know exactly how big you want the
    * underlying array to be. In most cases ofSize() is probably what
    * you want instead.
    */
  private[metal] def ofAllocatedSize[K](n: Int)(implicit K: Methods[K]) = {
    import K.classTag
    val sz = Util.nextPowerOfTwo(n) match {
      case n if n < 0 => sys.error(s"Bad allocated size $n for collection")
      case 0 => 8
      case n => n
    }
    new HashSetImpl[K](
      items = K.newArray(sz),
      buckets = new Array[Byte](sz),
      len = 0,
      used = 0,
      mask = sz - 1,
      limit = (sz * 0.65).toInt)
  }

}
