package org.embulk.output;

import java.util.Properties;
import java.io.IOException;
import java.sql.SQLException;

import com.google.common.base.Optional;

import org.embulk.config.Config;
import org.embulk.config.ConfigDefault;
import org.embulk.output.jdbc.AbstractJdbcOutputPlugin;
import org.embulk.output.jdbc.BatchInsert;
import org.embulk.output.jdbc.JdbcOutputConnection;
import org.embulk.output.jdbc.MergeConfig;
import org.embulk.output.jdbc.TableIdentifier;
import org.embulk.output.mysql.MySQLOutputConnector;
import org.embulk.output.mysql.MySQLBatchInsert;

public class MySQLOutputPlugin
        extends AbstractJdbcOutputPlugin
{
    public interface MySQLPluginTask
            extends PluginTask
    {
        @Config("host")
        public String getHost();

        @Config("port")
        @ConfigDefault("3306")
        public int getPort();

        @Config("user")
        public String getUser();

        @Config("password")
        @ConfigDefault("\"\"")
        public String getPassword();

        @Config("database")
        public String getDatabase();

        @Config("temp_database")
        public Optional<String> getTempDatabase();
    }

    @Override
    protected Class<? extends PluginTask> getTaskClass()
    {
        return MySQLPluginTask.class;
    }

    @Override
    protected Features getFeatures(PluginTask task)
    {
        return new Features()
            .setMaxTableNameLength(64)
            .setIgnoreMergeKeys(true);
    }

    @Override
    protected MySQLOutputConnector getConnector(PluginTask task, boolean retryableMetadataOperation)
    {
        MySQLPluginTask t = (MySQLPluginTask) task;

        String url = String.format("jdbc:mysql://%s:%d/%s",
                t.getHost(), t.getPort(), t.getDatabase());

        Properties props = new Properties();

        props.setProperty("rewriteBatchedStatements", "true");
        props.setProperty("useCompression", "true");

        props.setProperty("connectTimeout", "300000"); // milliseconds
        props.setProperty("socketTimeout", "1800000"); // smillieconds

        // Enable keepalive based on tcp_keepalive_time, tcp_keepalive_intvl and tcp_keepalive_probes kernel parameters.
        // Socket options TCP_KEEPCNT, TCP_KEEPIDLE, and TCP_KEEPINTVL are not configurable.
        props.setProperty("tcpKeepAlive", "true");

        // TODO
        //switch t.getSssl() {
        //when "disable":
        //    break;
        //when "enable":
        //    props.setProperty("useSSL", "true");
        //    props.setProperty("requireSSL", "false");
        //    props.setProperty("verifyServerCertificate", "false");
        //    break;
        //when "verify":
        //    props.setProperty("useSSL", "true");
        //    props.setProperty("requireSSL", "true");
        //    props.setProperty("verifyServerCertificate", "true");
        //    break;
        //}

        if (!retryableMetadataOperation) {
            // non-retryable batch operation uses longer timeout
            props.setProperty("connectTimeout",  "300000");  // milliseconds
            props.setProperty("socketTimeout", "2700000");   // milliseconds
        }

        props.putAll(t.getOptions());

        // TODO validate task.getMergeKeys is null

        props.setProperty("user", t.getUser());
        logger.info("Connecting to {} options {}", url, props);
        props.setProperty("password", t.getPassword());

        return new MySQLOutputConnector(url, props);
    }

    @Override
    protected TableIdentifier buildIntermediateTableId(JdbcOutputConnection con, PluginTask task, String tableName) {
        MySQLPluginTask t = (MySQLPluginTask) task;
        if (t.getTempDatabase().isPresent()) {
            return new TableIdentifier(t.getTempDatabase().get(), null, tableName);
        }
        return super.buildIntermediateTableId(con, task, tableName);
    }

    @Override
    protected BatchInsert newBatchInsert(PluginTask task, Optional<MergeConfig> mergeConfig) throws IOException, SQLException
    {
        return new MySQLBatchInsert(getConnector(task, true), mergeConfig);
    }


    @Override
    protected boolean isRetryableException(String sqlState, int errorCode)
    {
        switch (errorCode) {
            case 1213: // ER_LOCK_DEADLOCK (Message: Deadlock found when trying to get lock; try restarting transaction)
                return true;
            case 1205: // ER_LOCK_WAIT_TIMEOUT (Message: Lock wait timeout exceeded; try restarting transaction)
                return true;
            default:
                return false;
        }
    }
}
