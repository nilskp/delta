package sampler.jdbc.mysql

import java.sql.Connection

import org.junit.Assert.assertTrue
import org.junit._

import com.mysql.jdbc.jdbc2.optional.MysqlConnectionPoolDataSource

import sampler.{ Aggr, JSON, JsonDomainEventCodec }
import sampler.aggr.DomainEvent
import sampler.jdbc._
import ulysses.jdbc._
import ulysses.jdbc.mysql.MySQLDialect
import ulysses.testing.RandomDelayExecutionContext
import ulysses.util.LocalPublishing
import org.junit.AfterClass

object TestSampler {
  val db = "ulysses_testing_sampler"
  val ds = {
    val ds = new MysqlConnectionPoolDataSource
    ds.setUser("root")
    ds setUrl s"jdbc:mysql://localhost/$db?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf-8&autoReconnect=true"
    ds
  }
  @AfterClass
  def dropDb {
    val conn = ds.getConnection
    try {
      val stm = conn.createStatement()
      try stm.execute(s"drop database $db") finally stm.close()
    } finally conn.close()
  }
}

@Ignore
final class TestSampler extends sampler.TestSampler {

  implicit object JsonColumn extends VarCharColumn(Short.MaxValue)

  override lazy val es = {
    val sql = new MySQLDialect[Int, DomainEvent, Aggr.Value, JSON]
    new JdbcEventStore(sql, RandomDelayExecutionContext) with LocalPublishing[Int, DomainEvent, Aggr.Value] {
      def publishCtx = RandomDelayExecutionContext
      protected def useConnection[R](thunk: Connection => R): R = {
        val conn = TestSampler.ds.getConnection
        try thunk(conn) finally conn.close()
      }
    }
  }

  @Test
  def mock {
    assertTrue(true)
  }
}
