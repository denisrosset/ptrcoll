package net.alasc.ptrcoll
package seqs

import scala.reflect.ClassTag
import scala.{specialized => sp}

import spire.algebra._
import spire.math.QuickSort
import spire.syntax.all._

trait Buffer[@sp(Int, Long) V] extends MSeq[V] {

  def copy: Buffer[V]

}

final class BufferImpl[@sp(Int, Long) V](initialLength: Int, initialArray: Array[V])(implicit val ct: ClassTag[V]) extends Buffer[V] {

  private[this] var _length: Int = initialLength
  private[this] var _array: Array[V] = initialArray

  def copy: Buffer[V] = new BufferImpl(_length, _array.clone)

  /** Grow the underlying array to accomodate n elements. */
  private[this] def grow(n: Int): Dummy[V] = {
    val newArray = new Array[V](n)
    System.arraycopy(_array, 0, newArray, 0, _length)
    _array = newArray
    null
  }

  @inline final def isEmpty: Boolean = _length == 0

  @inline final def nonEmpty: Boolean = _length > 0

  def length: Int = _length

  def apply(i: Int): V = _array(i)

  def update(i: Int, v: V): Unit = { _array(i) = v }

  def +=(v: V): this.type = {
    val n = _length
    if (n >= _array.length) grow(Util.nextPowerOfTwo(n + 1))
    _array(n) = v
    _length = n + 1
    this
  }

/*  def remove(i: Int): V = {
    val last = _length - 1
    if (i < 0) {
      throw new IndexOutOfBoundsException(i.toString)
    } else if (i < last) {
      System.arraycopy(_array, i + 1, _array, i, last - i)
      val v = _array(last)
      _array(last) = null.asInstanceOf[V]
      _length = last
      v
    } else if (i == last) {
      pop
    } else {
      throw new IndexOutOfBoundsException(i.toString)
    }
  }*/

  def remove(i: Int): Boolean = {
    val last = _length - 1
    if (i < 0) false
    else if (i < last) {
      System.arraycopy(_array, i + 1, _array, i, last - i)
      _array(last) = null.asInstanceOf[V]
      _length = last
      true
    } else if (i == last) {
      _array(last) = null.asInstanceOf[V]
      _length = last
      true
    } else false
  }

  def -=(i: Int): this.type = {
    remove(i)
    this
  }

  def contains(i: Int): Boolean = (i >= 0 && i < _length)

  def ptrFind(i: Int): Ptr[Tag] =
    if (contains(i)) VPtr[Tag](i) else Ptr.Null[Tag]

  def ptrKey(ptr: VPtr[Tag]): Int = ptr.v.toInt

  def ptrValue(ptr: VPtr[Tag]): V = _array(ptr.v.toInt)

  def ptrRemove(ptr: VPtr[Tag]): Unit = remove(ptr.v.toInt)

  def ptrRemoveAndAdvance(ptr: VPtr[Tag]): Ptr[Tag] = {
    val nextPtr = ptrNext(ptr)
    remove(ptr.v.toInt)
    nextPtr
  }

  def ptrStart: Ptr[Tag] =
    if (_length == 0) Ptr.Null[Tag] else VPtr[Tag](0L)

  @inline final def ptrNext(ptr: VPtr[Tag]): Ptr[Tag] = if (ptr.v == _length - 1) Ptr.Null[Tag] else VPtr[Tag](ptr.v + 1)

}