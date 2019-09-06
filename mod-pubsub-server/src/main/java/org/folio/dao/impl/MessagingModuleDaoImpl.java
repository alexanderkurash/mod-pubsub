package org.folio.dao.impl;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.sql.ResultSet;
import io.vertx.ext.sql.UpdateResult;
import javassist.NotFoundException;
import org.folio.dao.MessagingModuleDao;
import org.folio.dao.PostgresClientFactory;
import org.folio.rest.jaxrs.model.MessagingModule;
import org.folio.rest.jaxrs.model.MessagingModule.ModuleRole;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static org.apache.commons.lang3.StringUtils.EMPTY;

/**
 * Implementation for the MessagingModuleDao, works with PostgresClient to access data.
 *
 * @see MessagingModuleDao
 */
@Repository
public class MessagingModuleDaoImpl implements MessagingModuleDao {

  private static final Logger LOGGER = LoggerFactory.getLogger(MessagingModuleDaoImpl.class);

  private static final String TABLE_NAME = "messaging_module";
  private static final String MODULE_SCHEMA = "pubsub_config";
  private static final String GET_BY_SQL = "SELECT * FROM %s.%s %s";
  private static final String GET_BY_ID_SQL = "SELECT * FROM %s.%s WHERE id = ?";
  private static final String INSERT_SQL = "INSERT INTO %s.%s (id, event_type_id, module_id, tenant_id, role, is_applied, subscriber_callback) VALUES (?, ?, ?, ?, ?, ?, ?)";
  private static final String UPDATE_BY_ID_SQL = "UPDATE %s.%s SET event_type_id = ?, module_id = ?, tenant_id = ?, role = ?, is_applied = ?, subscriber_callback = ? WHERE id = ?";
  private static final String DELETE_BY_ID_SQL = "DELETE FROM %s.%s WHERE id = ?";

  @Autowired
  private PostgresClientFactory pgClientFactory;

  @Override
  public Future<List<MessagingModule>> get(MessagingModuleFilter filter) {
    Future<ResultSet> future = Future.future();
    String preparedQuery = format(GET_BY_SQL, MODULE_SCHEMA, TABLE_NAME, buildWhereClause(filter));
    pgClientFactory.getInstance().select(preparedQuery, future.completer());
    return future.map(this::mapResultSetToMessagingModuleList);
  }

  @Override
  public Future<Optional<MessagingModule>> getById(String id) {
    Future<ResultSet> future = Future.future();
    String preparedQuery = format(GET_BY_ID_SQL, MODULE_SCHEMA, TABLE_NAME);
    JsonArray params = new JsonArray().add(id);
    pgClientFactory.getInstance().select(preparedQuery, params, future.completer());
    return future.map(resultSet -> resultSet.getResults().isEmpty()
      ? Optional.empty() : Optional.of(mapRowJsonToMessagingModule(resultSet.getRows().get(0))));
  }

  @Override
  public Future<String> save(MessagingModule messagingModule) {
    Future<UpdateResult> future = Future.future();
    try {
      String query = format(INSERT_SQL, MODULE_SCHEMA, TABLE_NAME);
      JsonArray params = new JsonArray()
        .add(messagingModule.getId())
        .add(messagingModule.getEventType())
        .add(messagingModule.getModuleId())
        .add(messagingModule.getTenantId())
        .add(messagingModule.getModuleRole().value())
        .add(messagingModule.getApplied());
      String subscriberCallback = messagingModule.getSubscriberCallback();
      params.add(subscriberCallback != null ? subscriberCallback : EMPTY);
      pgClientFactory.getInstance().execute(query, params, future.completer());
    } catch (Exception e) {
      LOGGER.error("Error saving MessagingModule", e);
      future.fail(e);
    }
    return future.map(updateResult -> messagingModule.getId());
  }

  @Override
  public Future<MessagingModule> update(String id, MessagingModule messagingModule) {
    Future<UpdateResult> future = Future.future();
    try {
      String query = format(UPDATE_BY_ID_SQL, MODULE_SCHEMA, TABLE_NAME);
      String subscriberCallback = messagingModule.getSubscriberCallback();
      JsonArray params = new JsonArray()
        .add(messagingModule.getEventType())
        .add(messagingModule.getModuleId())
        .add(messagingModule.getTenantId())
        .add(messagingModule.getModuleRole())
        .add(messagingModule.getApplied())
        .add(subscriberCallback != null ? subscriberCallback : EMPTY)
        .add(id);
      pgClientFactory.getInstance().execute(query, params, future.completer());
    } catch (Exception e) {
      LOGGER.error("Error updating MessagingModule by id '{}'", e, id);
      future.fail(e);
    }
    return future.compose(updateResult -> updateResult.getUpdated() == 1
      ? Future.succeededFuture(messagingModule)
      : Future.failedFuture(new NotFoundException(format("MessagingModule by id '%s' was not found", id))));
  }

  @Override
  public Future<Boolean> delete(String id) {
    Future<UpdateResult> future = Future.future();
    String query = format(DELETE_BY_ID_SQL, MODULE_SCHEMA, TABLE_NAME);
    JsonArray params = new JsonArray().add(id);
    pgClientFactory.getInstance().execute(query, params, future.completer());
    return future.map(updateResult -> updateResult.getUpdated() == 1);
  }

  private MessagingModule mapRowJsonToMessagingModule(JsonObject rowAsJson) {
    return new MessagingModule()
      .withId(rowAsJson.getString("id"))
      .withEventType(rowAsJson.getString("event_type_id"))
      .withModuleId(rowAsJson.getString("module_id"))
      .withTenantId(rowAsJson.getString("tenant_id"))
      .withModuleRole(ModuleRole.valueOf(rowAsJson.getString("role")))
      .withApplied(rowAsJson.getBoolean("is_applied"))
      .withSubscriberCallback(rowAsJson.getString("subscriber_callback"));
  }

  private List<MessagingModule> mapResultSetToMessagingModuleList(ResultSet resultSet) {
    return resultSet.getRows().stream()
      .map(this::mapRowJsonToMessagingModule)
      .collect(Collectors.toList());
  }

  private String buildWhereClause(MessagingModuleFilter filter) {
    StringBuilder conditionBuilder = new StringBuilder("WHERE TRUE");
    if (filter.getEventType() != null) {
      conditionBuilder.append(" AND ")
        .append("event_type_id = '").append(filter.getEventType()).append("'");
    }
    if (filter.getModuleId() != null) {
      conditionBuilder.append(" AND ")
        .append("module_id = '").append(filter.getModuleId()).append("'");
    }
    if (filter.getTenantId() != null) {
      conditionBuilder.append(" AND ")
        .append("tenant_id = '").append(filter.getTenantId()).append("'");
    }
    if (filter.getModuleRole() != null) {
      conditionBuilder.append(" AND ")
        .append("role = '").append(filter.getModuleRole()).append("'");
    }
    if (filter.getApplied() != null) {
      conditionBuilder.append(" AND ")
        .append("is_applied = '").append(filter.getApplied()).append("'");
    }
    if (filter.getSubscriberCallback() != null) {
      conditionBuilder.append(" AND ")
        .append("subscriber_callback = '").append(filter.getSubscriberCallback()).append("'");
    }
    return conditionBuilder.append(';').toString();
  }
}
