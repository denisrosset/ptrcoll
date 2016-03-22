## Metal - fast unboxed data structures for Scala

[![Join the chat at https://gitter.im/denisrosset/metal](https://badges.gitter.im/denisrosset/metal.svg)](https://gitter.im/denisrosset/metal?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)

Metal provides fast mutable *containers* whose performance should be close to
hand-written data structures using raw Java arrays.

In particular:

- Metal containers are themselves not specialized, but will avoid any boxing/allocation
  when accessing, storing and updating elements, thanks to macros and specialized
  *methods*;
- Metal provides higher-order methods such as `foreach`, `count`, `exists`, ...
  that are translated into `while` loops during compilation. The loop body
  is inlined, avoiding allocation of closures;
- Scala iterators are replaced by pointers, represented by value classes that can
  be manipulated as primitives;
- mutable containers can be used as builders for immutable containers without
  additional allocations.

The library is heavily inspired by [Debox](http://github.com/non/debox). Parts of the
implementation were directly lifted (for example the strategy for hash sets and maps).

For performance reasons, Metal's types are neither compatible with Scala's
collections framework, nor the Debox implementations; but the methods in
Metal's interfaces are either prefixed by `ptr`, or follow the current
conventions (e.g. `def isEmpty: Boolean`).

The set of methods available on Metal instances is limited, but guarantees
that no allocations occur except when creating or growing containers.

### Example

```
scala> import metal._; import syntax._
import metal._
import syntax._

scala> val set = MHashSet(1,2,3,4)
set: metal.MHashSet[Int] = FSet(1, 2, 3, 4)

scala> set.contains(3)
res0: Boolean = true

scala> set.foreach { k => println(k) }
1
2
3
4

scala> val map = MHashMap(1 -> "test")
map: metal.MHashMap[Int,String] = FMap(1 -> test)

scala> map.ptrFind(1).valueOrElse(sys.error(""))
res1: String = test

```

### Metal library structure

Metal is composed of two packages:

- `core` contains the definition of pointer types (see below), traits that are
  inherited by containers to provide capabilities, and macro implementations of
  the standard collection methods;
- `library` provides implementations of standard data structures.

### Available container types

All container names are prefixed by one of:

- `M`: the mutable variant,
- `I`: the immutable variant,
- `F`: the generic variant, that can be either mutable or immutable.

The following set implementations are provided (where `x = M/I/F`):

- `xHashSet[K]`: a set implemented using an open addressing scheme,
- `xSortedSet[K]`: a sorted set, using the `Order` type class from `Spire`,
- `xBitSet[Int]` a bitset implementation with (non-negative) `Int` keys.

Two variants of maps are implemented:

- `xHashMap[K,V]`: an hash map using an open addressing scheme,
- `xHashMap2[K,V1,V2]`: an hash map usin an open addressing scheme, storing
  values `(V1, V2)`; however, the values of type `V1` and `V2` are never
  stored as a tuple to avoid allocations; individual access methods are
  provided instead.

There is also a work-in-progress implementation of mutable and growable arrays
`Buffer` and an immutable wrapper for arrays `IArraySeq`.

### Pointers

Metal defines two pointer types, `Ptr` and `VPtr`. The `Ptr` pointer can either point
to an element in a container, or be null. The `VPtr` pointer is guaranteed to point
to an element.

Pointers are invalidated when the container is modified, with the exception of the
-`removeAndAdvance` methods.

A pointer has two type parameters:

- the first parameter, `T`, is a path-dependent type linking the pointer to the
  pointed container, using a tag trait member;

- the second parameter, `C`, describes the capabilities and the shape of the
  pointed container.

Pointers are implemented using value classes of a primitive `Long` value.

Most of the container methods return possibly null `Ptr` instances. To convert a `Ptr`
to a `VPtr`, the following syntax is encouraged:

```scala
import metal._
val container = MHashSet(1, 2, 3)
val p = container.ptr
p match {
  case IsVPtr(vp) =>
    // now `vp` is a `VPtr`
    println(vp.key)
  case _ =>
    println("container is empty")
}
```

Thanks to name-based extractors, the code snippet above does not perform any allocations.

Several methods are implemented on `Ptr` and `VPtr`, and can be used to access the pointed
element.

```scala
import metal._
import metal.syntax._

val m = MHashMap(1 -> 2, 3 -> 4)
// we request a pointer to the first element in the map (hash maps have an internal arbitrary order)
val p = m.ptr
assert(!p.isNull)
assert(p.nonNull)
assert(p.keyOrElse(-1) > 0)
// does not throw, `sys.error` is inlined by macros and only called when `p` is null
p.keyOrElse(sys.error("")) > 0
// same syntax for pointed values
p.valueOrElse(-1)
p match {
  case IsVPtr(vp) =>
    // vp is now a non-null `VPtr`, the pointed key is available
    assert(vp.key > 0)
    assert(vp.value % 2 == 0)
    // we can ask for a (possibly null) pointer to the next element, according
    // to the hash map internal order
    val nxt = vp.next
  case _ =>
}
// we can look for keys
val p1 = m.ptrFind(3)
// and the same methods are available on that pointer
assert(p1.nonNull)
```

### Higher-order functions

Several higher-order functions are available on containers, for example `foreach`, `count`,
`exists`, `forall`, `foldLeft` (or `/:`); however, the calling convention is slightly different
from the Scala collections to avoid allocating tuples:

```scala
val m = MHashMap(1 -> 2, 3 -> 4)
m.foreach { (k, v) => println(s"($k, $v)") }

// instead of

m.foreach { case (k, v) => println(s"($k, $v)") }
```

The methods `min`, `max`, `sum`, `product` are also available; the required algebraic
operations (orders, additive monoids, multiplicative monoids) are provided using
Spire type classes. Contrary to the standard Scala library, Spire is heavily
specialized on primitive types.

Those higher-order functions are implemented using implicit classes and macros, to
avoid polluting the container interfaces.

At compilation time, a call such as `m.foreach { (k, v) => println(k) }` is inlined,
producing code similar to:

```scala
@inline def rec(ptr: Ptr[m.Tag, m.Cap]): Unit = ptr match {
    case IsVPtr(vp) =>
      val k = m.ptrKey[Int](vp)
      val v = m.ptrValue[Int](vp)
      println(k)
      rec(m.ptrNext(vp))
    case _ =>
  }
```
