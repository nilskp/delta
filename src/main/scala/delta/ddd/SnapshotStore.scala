package delta.ddd

import scala.concurrent.Future

/**
  * Snapshot storage implementation.
  */
trait SnapshotStore[ID, S >: Null] {
  type Snapshot = SnapshotStore.Snapshot[S]

  /** Load snapshot, if exists. */
  def load(id: ID): Future[Option[Snapshot]]
  /**
    *  Save snapshot.
    *  This method should not throw an exception,
    *  but handle/report it internally.
    */
  def save(id: ID, snapshot: Snapshot): Unit

  /**
    * Should a snapshot be considered current? Defaults to `false`.
    * Basically this should only return `true` if all snapshots
    * are saved (in non-local location if distributed environment).
    * Assuming current snapshots (`true`) will prevent a query to event store.
    * If not true (due to race conditions or snapshots not always stored),
    * the event store will still be queried after detecting non-currency.
    * In other words, having this be `true` will still work, but will be
    * slightly less efficient if not actually true most of the time.
    */
  def assumeSnapshotCurrent = false

}

object SnapshotStore {
  /**
    * @param state Snapshot state
    * @param revision Snapshot revision
    * @param tick Transaction clock
    */
  case class Snapshot[S >: Null](state: S, revision: Int, tick: Long)

  private[this] val Empty = new SnapshotStore[Any, AnyRef] {
    private[this] val NoSnapshot = Future.successful(None)
    def load(id: Any): Future[Option[Snapshot]] = NoSnapshot
    def save(id: Any, snapshot: Snapshot): Unit = ()
  }

  def Disabled[ID, S >: Null] = Empty.asInstanceOf[SnapshotStore[ID, S]]
}