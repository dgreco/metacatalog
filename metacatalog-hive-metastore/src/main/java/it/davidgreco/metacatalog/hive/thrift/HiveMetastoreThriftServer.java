package it.davidgreco.metacatalog.hive.thrift;

import it.davidgreco.metacatalog.hive.HiveMetastoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.hadoop.hive.metastore.api.ThriftHiveMetastore;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.server.TServer;
import org.apache.thrift.server.TThreadPoolServer;
import org.apache.thrift.transport.TServerSocket;
import org.apache.thrift.transport.TTransportException;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Runs the metastore's Thrift server for as long as the application context is running.
 *
 * <p>A {@link SmartLifecycle} rather than a {@code @PostConstruct}, for two reasons that both
 * matter. It starts after the context has refreshed, so the registry it dispatches to is fully
 * wired and {@code ImmutableModelInstaller} has created the model — a client connecting to a
 * half-built application would get failures that look like data problems. And it never runs during
 * the image build: the {@code Dockerfile}'s AOT training run boots the application with {@code
 * spring.context.exit=onRefresh}, which stops before lifecycle beans start, so a server bound in a
 * constructor or {@code @PostConstruct} would try to take port 9083 inside the builder.
 *
 * <p>Started late and stopped early: the phase puts it after the web server, so readiness only
 * flips once this socket is accepting, and shutdown stops taking new calls before the rest of the
 * context comes apart underneath them.
 */
@Slf4j
@Component
public class HiveMetastoreThriftServer implements SmartLifecycle {

  private final HiveMetastoreProperties properties;
  private final ThriftHiveMetastore.Iface handler;

  private volatile TServer server;
  private volatile Thread serverThread;
  private volatile boolean running;

  public HiveMetastoreThriftServer(
      HiveMetastoreProperties properties, MetacatalogHmsHandler handler) {
    this.properties = properties;
    this.handler = HmsIfaceProxy.wrap(handler);
  }

  @Override
  public synchronized void start() {
    if (running) return;
    try {
      var transport = new TServerSocket(properties.thriftPort());
      server =
          new TThreadPoolServer(
              new TThreadPoolServer.Args(transport)
                  .processor(new ThriftHiveMetastore.Processor<>(handler))
                  .protocolFactory(new TBinaryProtocol.Factory())
                  .minWorkerThreads(properties.minWorkerThreads())
                  .maxWorkerThreads(properties.maxWorkerThreads()));
    } catch (TTransportException e) {
      throw new IllegalStateException(
          "Could not bind the Hive metastore Thrift port " + properties.thriftPort(), e);
    }
    serverThread = new Thread(server::serve, "hive-metastore-thrift");
    serverThread.setDaemon(true);
    serverThread.start();
    running = true;
    log.info("Hive metastore listening on thrift://0.0.0.0:{}", properties.thriftPort());
  }

  @Override
  public synchronized void stop() {
    if (!running) return;
    running = false;
    if (server != null) server.stop();
    if (serverThread != null) serverThread.interrupt();
    log.info("Hive metastore stopped");
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /**
   * After the web server, so the readiness probe cannot report ready while the protocol port is
   * still closed — Compose gates its dependents on exactly that.
   */
  @Override
  public int getPhase() {
    return Integer.MAX_VALUE - 1024;
  }
}
