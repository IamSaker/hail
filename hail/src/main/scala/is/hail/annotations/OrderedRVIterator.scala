package is.hail.annotations

import is.hail.expr.types.physical.PInterval
import is.hail.rvd.{RVDContext, RVDType}
import is.hail.utils._

import scala.collection.generic.Growable
import scala.collection.mutable

object OrderedRVIterator {
  def multiZipJoin(
    its: IndexedSeq[OrderedRVIterator]
  ): Iterator[ArrayBuilder[(RegionValue, Int)]] = {
    require(its.length > 0)
    val first = its(0)
    val flipbooks = its.map(_.iterator.toFlipbookIterator)
    FlipbookIterator.multiZipJoin(
      flipbooks.toArray,
      first.t.joinComp(first.t).compare
    )
  }
}

case class OrderedRVIterator(
  t: RVDType,
  iterator: Iterator[RegionValue],
  ctx: RVDContext
) {

  def staircase: StagingIterator[FlipbookIterator[RegionValue]] =
    iterator.toFlipbookIterator.staircased(t.kRowOrdView(ctx.freshRegion))

  def cogroup(other: OrderedRVIterator):
      FlipbookIterator[Muple[FlipbookIterator[RegionValue], FlipbookIterator[RegionValue]]] =
    this.iterator.toFlipbookIterator.cogroup(
      other.iterator.toFlipbookIterator,
      this.t.kRowOrdView(ctx.freshRegion),
      other.t.kRowOrdView(ctx.freshRegion),
      this.t.kComp(other.t).compare
    )

  def zipJoin(other: OrderedRVIterator): FlipbookIterator[JoinedRegionValue] =
    iterator.toFlipbookIterator.orderedZipJoin(
      other.iterator.toFlipbookIterator,
      leftDefault = null,
      rightDefault = null,
      RVDType.selectUnsafeOrdering(
        t.rowType, t.kFieldIdx, other.t.rowType, other.t.kFieldIdx)
        .compare
    )

  def leftJoinDistinct(other: OrderedRVIterator): Iterator[JoinedRegionValue] =
    iterator.toFlipbookIterator.leftJoinDistinct(
      other.iterator.toFlipbookIterator,
      null,
      null,
      this.t.joinComp(other.t).compare
    )

  def leftIntervalJoinDistinct(other: OrderedRVIterator): Iterator[JoinedRegionValue] =
    iterator.toFlipbookIterator.leftJoinDistinct(
      other.iterator.toFlipbookIterator,
      null,
      null,
      this.t.intervalJoinComp(other.t).compare
    )

  def leftIntervalJoin(other: OrderedRVIterator): Iterator[Muple[RegionValue, Iterable[RegionValue]]] = {
    val left = iterator.toFlipbookIterator
    val right = other.iterator.toFlipbookIterator
    val rightEndpointOrdering: Ordering[RegionValue] = RVDType.selectUnsafeOrdering(
      other.t.rowType,
      other.t.kFieldIdx,
      other.t.rowType,
      other.t.kFieldIdx,
      Array(other.t.kType.types(0).asInstanceOf[PInterval].endPrimaryUnsafeOrdering()),
      missingEqual = true
    ).reverse
    val mixedOrd: (RegionValue, RegionValue) => Int = this.t.intervalJoinComp(other.t).compare

    val sm = new StateMachine[Muple[RegionValue, Iterable[RegionValue]]] {
      val buffer = new RegionValuePriorityQueue(other.t.rowType, ctx, rightEndpointOrdering)

      val value: Muple[RegionValue, Iterable[RegionValue]] = Muple(null, buffer)

      var isValid: Boolean = true

      def setValue() {
        if (left.isValid) {
          while (buffer.nonEmpty && mixedOrd(left.value, buffer.head) > 0)
            buffer.dequeue()
          while (right.isValid && mixedOrd(left.value, right.value) >= 0) {
            if (mixedOrd(left.value, right.value) == 0) {
              buffer.enqueue(right.value)
            }
            right.advance()
          }
          value.set(left.value, buffer)
        } else {
          isValid = false
        }
      }

      def advance() {
        left.advance()
        setValue()
      }

      setValue()
    }

    FlipbookIterator(sm)
  }

  def innerJoin(
    other: OrderedRVIterator,
    rightBuffer: Iterable[RegionValue] with Growable[RegionValue]
  ): Iterator[JoinedRegionValue] = {
    iterator.toFlipbookIterator.innerJoin(
      other.iterator.toFlipbookIterator,
      this.t.kRowOrdView(ctx.freshRegion),
      other.t.kRowOrdView(ctx.freshRegion),
      null,
      null,
      rightBuffer,
      this.t.joinComp(other.t).compare
    )
  }

  def leftJoin(
    other: OrderedRVIterator,
    rightBuffer: Iterable[RegionValue] with Growable[RegionValue]
  ): Iterator[JoinedRegionValue] = {
    iterator.toFlipbookIterator.leftJoin(
      other.iterator.toFlipbookIterator,
      this.t.kRowOrdView(ctx.freshRegion),
      other.t.kRowOrdView(ctx.freshRegion),
      null,
      null,
      rightBuffer,
      this.t.joinComp(other.t).compare
    )
  }

  def rightJoin(
    other: OrderedRVIterator,
    rightBuffer: Iterable[RegionValue] with Growable[RegionValue]
  ): Iterator[JoinedRegionValue] = {
    iterator.toFlipbookIterator.rightJoin(
      other.iterator.toFlipbookIterator,
      this.t.kRowOrdView(ctx.freshRegion),
      other.t.kRowOrdView(ctx.freshRegion),
      null,
      null,
      rightBuffer,
      this.t.joinComp(other.t).compare
    )
  }

  def outerJoin(
    other: OrderedRVIterator,
    rightBuffer: Iterable[RegionValue] with Growable[RegionValue]
  ): Iterator[JoinedRegionValue] = {
    iterator.toFlipbookIterator.outerJoin(
      other.iterator.toFlipbookIterator,
      this.t.kRowOrdView(ctx.freshRegion),
      other.t.kRowOrdView(ctx.freshRegion),
      null,
      null,
      rightBuffer,
      this.t.joinComp(other.t).compare
    )
  }

  def merge(other: OrderedRVIterator): Iterator[RegionValue] = {
    iterator.toFlipbookIterator.merge(
      other.iterator.toFlipbookIterator,
      this.t.kComp(other.t).compare
    )
  }

  def localKeySort(
    newKey: IndexedSeq[String]
  ): Iterator[RegionValue] = {
    require(newKey startsWith t.key)
    require(newKey.forall(t.rowType.fieldNames.contains))

    val consumerRegion = ctx.region

    new Iterator[RegionValue] {
      private val bit = iterator.buffered

      private val q = new mutable.PriorityQueue[RegionValue]()(
        t.copy(key = newKey).kInRowOrd.reverse)

      private val rvb = new RegionValueBuilder(consumerRegion)
      private val rv = RegionValue()

      def hasNext: Boolean = bit.hasNext || q.nonEmpty

      def next(): RegionValue = {
        if (q.isEmpty) {
          do {
            val rv = bit.next()
            val r = ctx.freshRegion
            rvb.set(r)
            rvb.start(t.rowType)
            rvb.addRegionValue(t.rowType, rv)
            q.enqueue(RegionValue(rvb.region, rvb.end()))
          } while (bit.hasNext && t.kInRowOrd.compare(q.head, bit.head) == 0)
        }

        rvb.set(consumerRegion)
        rvb.start(t.rowType)
        val fromQueue = q.dequeue()
        rvb.addRegionValue(t.rowType, fromQueue)
        ctx.closeChild(fromQueue.region)
        rv.set(consumerRegion, rvb.end())
        rv
      }
    }
  }
}
