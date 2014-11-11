package com.google.anviltop.sample.util;

import com.google.anviltop.sample.DebugUtil;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configurable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.mapreduce.TableMapReduceUtil;
import org.apache.hadoop.hbase.mapreduce.TableOutputCommitter;
import org.apache.hadoop.hbase.zookeeper.ZKUtil;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.OutputCommitter;
import org.apache.hadoop.mapreduce.OutputFormat;
import org.apache.hadoop.mapreduce.RecordWriter;
import org.apache.hadoop.mapreduce.TaskAttemptContext;

import java.io.IOException;

/*
 * This was mostly copied from the hbase code.  There is a debug statement added here as well as
 * a removal of an explicit cast to HTable.
 */

/**
 * Convert Map/Reduce output and write it to an HBase table. The KEY is ignored
 * while the output value <u>must</u> be either a {@link Put} or a
 * {@link Delete} instance.
 *
 * <p><KEY> is the type of the key. Ignored in this class.
 */
public class TableOutputFormat<KEY> extends OutputFormat<KEY, Mutation>
implements Configurable {

  private static final Log LOG = LogFactory.getLog(TableOutputFormat.class);

  /** Job parameter that specifies the output table. */
  public static final String OUTPUT_TABLE = "hbase.mapred.outputtable";

  /**
   * Optional job parameter to specify a peer cluster.
   * Used specifying remote cluster when copying between hbase clusters (the
   * source is picked up from <code>hbase-site.xml</code>).
   * @see TableMapReduceUtil#initTableReducerJob(String, Class, org.apache.hadoop.mapreduce.Job, Class, String, String, String)
   */
  public static final String QUORUM_ADDRESS = "hbase.mapred.output.quorum";

  /** Optional job parameter to specify peer cluster's ZK client port */
  public static final String QUORUM_PORT = "hbase.mapred.output.quorum.port";

  /** Optional specification of the rs class name of the peer cluster */
  public static final String
      REGION_SERVER_CLASS = "hbase.mapred.output.rs.class";
  /** Optional specification of the rs impl name of the peer cluster */
  public static final String
      REGION_SERVER_IMPL = "hbase.mapred.output.rs.impl";

  /** The configuration. */
  private Configuration conf = null;

  private Table table;
  private Connection connection;

  /**
   * Writes the reducer output to an HBase table.
   *
   * @param <KEY>  The type of the key.
   */
  protected class TableRecordWriter
  extends RecordWriter<KEY, Mutation> {

    /**
     * Closes the writer, in this case flush table commits.
     *
     * @param context  The context.
     * @throws IOException When closing the writer fails.
     * @see org.apache.hadoop.mapreduce.RecordWriter#close(org.apache.hadoop.mapreduce.TaskAttemptContext)
     */
    @Override
    public void close(TaskAttemptContext context)
    throws IOException {
      table.close();
      connection.close();
    }

    /**
     * Writes a key/value pair into the table.
     *
     * @param key  The key.
     * @param value  The value.
     * @throws IOException When writing fails.
     * @see org.apache.hadoop.mapreduce.RecordWriter#write(java.lang.Object, java.lang.Object)
     */
    @Override
    public void write(KEY key, Mutation value)
    throws IOException {
      // TODO (sduskis): Do we want to batch these?
      if (value instanceof Put) table.put(new Put((Put)value));
      else if (value instanceof Delete) table.delete(new Delete((Delete)value));
      else throw new IOException("Pass a Delete or a Put");
    }
  }

  /**
   * Creates a new record writer.
   *
   * @param context  The current task context.
   * @return The newly created writer instance.
   * @throws IOException When creating the writer fails.
   * @throws InterruptedException When the jobs is cancelled.
   * @see org.apache.hadoop.mapreduce.lib.output.FileOutputFormat#getRecordWriter(org.apache.hadoop.mapreduce.TaskAttemptContext)
   */
  @Override
  public RecordWriter<KEY, Mutation> getRecordWriter(
    TaskAttemptContext context)
  throws IOException, InterruptedException {
    return new TableRecordWriter();
  }

  /**
   * Checks if the output target exists.
   *
   * @param context  The current context.
   * @throws IOException When the check fails.
   * @throws InterruptedException When the job is aborted.
   * @see org.apache.hadoop.mapreduce.OutputFormat#checkOutputSpecs(org.apache.hadoop.mapreduce.JobContext)
   */
  @Override
  public void checkOutputSpecs(JobContext context) throws IOException,
      InterruptedException {
    // TODO Check if the table exists?

  }

  /**
   * Returns the output committer.
   *
   * @param context  The current context.
   * @return The committer.
   * @throws IOException When creating the committer fails.
   * @throws InterruptedException When the job is aborted.
   * @see org.apache.hadoop.mapreduce.OutputFormat#getOutputCommitter(org.apache.hadoop.mapreduce.TaskAttemptContext)
   */
  @Override
  public OutputCommitter getOutputCommitter(TaskAttemptContext context)
  throws IOException, InterruptedException {
    return new TableOutputCommitter();
  }

  @Override
  public Configuration getConf() {
    return conf;
  }

  @Override
  public void setConf(Configuration otherConf) {
    DebugUtil.printSystemProperties();
    this.conf = HBaseConfiguration.create(otherConf);

    String tableName = this.conf.get(OUTPUT_TABLE);
    if(tableName == null || tableName.length() <= 0) {
      throw new IllegalArgumentException("Must specify table name");
    }

    String address = this.conf.get(QUORUM_ADDRESS);
    int zkClientPort = this.conf.getInt(QUORUM_PORT, 0);
    String serverClass = this.conf.get(REGION_SERVER_CLASS);
    String serverImpl = this.conf.get(REGION_SERVER_IMPL);

    try {
      if (address != null) {
        ZKUtil.applyClusterKeyToConf(this.conf, address);
      }
      if (serverClass != null) {
        this.conf.set(HConstants.REGION_SERVER_IMPL, serverImpl);
      }
      if (zkClientPort != 0) {
        this.conf.setInt(HConstants.ZOOKEEPER_CLIENT_PORT, zkClientPort);
      }
      this.connection = ConnectionFactory.createConnection(this.conf);
      this.table = connection.getTable(TableName.valueOf(tableName));
      
      // NOTE: We removed some code from here that did an explicit cast to HTable.

      LOG.info("Created table instance for "  + tableName);
    } catch(IOException e) {
      LOG.error(e);
      throw new RuntimeException(e);
    }
  }
}

