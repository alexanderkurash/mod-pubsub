package org.folio.services.publish;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.json.Json;
import io.vertx.kafka.client.producer.KafkaProducer;
import io.vertx.kafka.client.producer.impl.KafkaProducerRecordImpl;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.kafka.KafkaConfig;
import org.folio.kafka.PubSubConfig;
import org.folio.rest.jaxrs.model.AuditMessage;
import org.folio.rest.jaxrs.model.Event;
import org.folio.services.audit.AuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static org.folio.rest.RestVerticle.MODULE_SPECIFIC_ARGS;
import static org.folio.services.util.AuditUtil.constructJsonAuditMessage;

@Component
public class PublishingServiceImpl implements PublishingService {

  private static final Logger LOGGER = LogManager.getLogger();

  private static final int THREAD_POOL_SIZE =
    Integer.parseInt(MODULE_SPECIFIC_ARGS.getOrDefault("event.publishing.thread.pool.size", "20"));

  private KafkaConfig kafkaConfig;
  private WorkerExecutor executor;
  private AuditService auditService;
  private Vertx vertx;

  public PublishingServiceImpl(@Autowired Vertx vertx,
                               @Autowired KafkaConfig kafkaConfig) {
    this.kafkaConfig = kafkaConfig;
    this.auditService = AuditService.createProxy(vertx);
    this.vertx = vertx;
    this.executor = vertx.createSharedWorkerExecutor("event-publishing-thread-pool", THREAD_POOL_SIZE);
  }

  @Override
  public Future<Boolean> sendEvent(Event event, String tenantId) {
    Promise<Boolean> promise = Promise.promise();
    PubSubConfig config = new PubSubConfig(kafkaConfig.getEnvId(), tenantId, event.getEventType());
    executor.executeBlocking(future -> {
        try {
          KafkaProducer.<String, String>createShared(vertx, config.getTopicName() + "_Producer", kafkaConfig.getProducerProps()).write(new KafkaProducerRecordImpl<>(config.getTopicName(), Json.encode(event)), done -> {
            if (done.succeeded()) {
              LOGGER.info("Sent {} event with id '{}' to topic {}", event.getEventType(), event.getId(), config.getTopicName());
              auditService.saveAuditMessage(constructJsonAuditMessage(event, tenantId, AuditMessage.State.PUBLISHED));
              future.complete(true);
            } else {
              String errorMessage = "Event was not sent";
              LOGGER.error(errorMessage, done.cause());
              auditService.saveAuditMessage(constructJsonAuditMessage(event, tenantId, AuditMessage.State.REJECTED, errorMessage));
              future.fail(done.cause());
            }
          });
        } catch (Exception e) {
          String errorMessage = "Error publishing event";
          LOGGER.error(errorMessage, e);
          auditService.saveAuditMessage(constructJsonAuditMessage(event, tenantId, AuditMessage.State.REJECTED, errorMessage));
        }
      }
      , promise);

    return promise.future();
  }
}
