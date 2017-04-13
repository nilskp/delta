package delta.jdbc

import java.sql.{ Connection, SQLWarning }

import scala.concurrent.blocking
import scala.util.Try

import javax.sql.{ DataSource, ConnectionPoolDataSource }
import scuff.concurrent.ResourcePool
import java.sql.SQLException
import java.sql.SQLTransientException
import java.sql.SQLRecoverableException

/**
  * Generic trait for providing a JDBC connection.
  * By default, any warnings will get thrown. This
  * behavior can be modified by either fixing the
  * cause of the warning(s), which is the recommended
  * approach, or, if a fix is infeasible, overriding the
  * `processWarnings` method.
  */
trait ConnectionProvider {
  protected def getConnection: Connection
  protected def getConnection(readOnly: Boolean): Connection = {
    val conn = getConnection
    conn.setReadOnly(readOnly)
    conn.setAutoCommit(readOnly)
    conn
  }
  protected def processWarnings(warnings: SQLWarning): Unit = throw warnings
  protected def useConnection[R](readOnly: Boolean)(thunk: Connection => R): R = blocking {
    val conn = getConnection(readOnly)
    try {
      val r = thunk(conn)
      Option(conn.getWarnings).foreach(processWarnings)
      r
    } finally Try(conn.close)
  }
  protected def forUpdate[R](thunk: Connection => R): R = useConnection(readOnly = false) { conn =>
    try {
      val r = thunk(conn)
      conn.commit()
      r
    } catch {
      case t: Throwable =>
        Try(conn.rollback())
        throw t
    }
  }
  protected def forQuery[R](thunk: Connection => R): R = useConnection(readOnly = true)(thunk)
}

trait DataSourceConnection extends ConnectionProvider {
  protected def dataSource: DataSource
  protected def getConnection: Connection = dataSource.getConnection
}

trait ConnectionPoolDataSourceConnection extends ConnectionProvider {
  protected def dataSource: ConnectionPoolDataSource
  protected def getConnection: Connection = dataSource.getPooledConnection.getConnection
}

trait ResourcePoolConnection extends ConnectionProvider {
  protected def newResourcePool(init: => Connection): ResourcePool[Connection]
  private[this] val readPool = newResourcePool(super.getConnection(readOnly = true))
  private[this] val writePool = newResourcePool(super.getConnection(readOnly = false))
  override protected def useConnection[R](readOnly: Boolean)(thunk: Connection => R): R = {
    val pool = if (readOnly) readPool else writePool
    pool.use(thunk)
  }
}

trait Retry extends ConnectionProvider {

  protected def retryCount: Int

  protected def shouldRetry(e: SQLException): Boolean =
    e.isInstanceOf[SQLTransientException] ||
      e.isInstanceOf[SQLRecoverableException]

  final override protected def useConnection[R](readOnly: Boolean)(thunk: Connection => R): R = {
    tryThunk(readOnly, retryCount, thunk)
  }
  private def tryThunk[R](readOnly: Boolean, retriesLeft: Int, thunk: Connection => R): R = {
    try {
      super.useConnection(readOnly)(thunk)
    } catch {
      case e: SQLException if retriesLeft > 0 && shouldRetry(e) =>
        tryThunk(readOnly, retriesLeft - 1, thunk)
    }
  }
}
