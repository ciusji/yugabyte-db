/*
 * Copyright 2019 YugaByte, Inc. and Contributors
 *
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 *     https://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */

package com.yugabyte.yw.commissioner.tasks;

import static com.yugabyte.yw.commissioner.tasks.BackupUniverse.BACKUP_ATTEMPT_COUNTER;
import static com.yugabyte.yw.commissioner.tasks.BackupUniverse.BACKUP_FAILURE_COUNTER;
import static com.yugabyte.yw.commissioner.tasks.BackupUniverse.BACKUP_SUCCESS_COUNTER;
import static com.yugabyte.yw.commissioner.tasks.BackupUniverse.SCHEDULED_BACKUP_ATTEMPT_COUNTER;
import static com.yugabyte.yw.commissioner.tasks.BackupUniverse.SCHEDULED_BACKUP_FAILURE_COUNTER;
import static com.yugabyte.yw.commissioner.tasks.BackupUniverse.SCHEDULED_BACKUP_SUCCESS_COUNTER;
import static com.yugabyte.yw.common.Util.SYSTEM_PLATFORM_DB;
import static com.yugabyte.yw.common.Util.getUUIDRepresentation;
import static com.yugabyte.yw.common.Util.lockedUpdateBackupState;
import static com.yugabyte.yw.common.metrics.MetricService.buildMetricTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.api.client.util.Throwables;
import com.yugabyte.yw.commissioner.BaseTaskDependencies;
import com.yugabyte.yw.commissioner.Commissioner;
import com.yugabyte.yw.commissioner.SubTaskGroupQueue;
import com.yugabyte.yw.commissioner.UserTaskDetails;
import com.yugabyte.yw.commissioner.ITask.Abortable;
import com.yugabyte.yw.common.customer.config.CustomerConfigService;
import com.yugabyte.yw.common.metrics.MetricLabelsBuilder;
import com.yugabyte.yw.forms.BackupRequestParams;
import com.yugabyte.yw.forms.BackupTableParams;
import com.yugabyte.yw.models.Backup;
import com.yugabyte.yw.models.Backup.BackupCategory;
import com.yugabyte.yw.models.Backup.BackupVersion;
import com.yugabyte.yw.models.Customer;
import com.yugabyte.yw.models.CustomerConfig;
import com.yugabyte.yw.models.CustomerTask;
import com.yugabyte.yw.models.Schedule;
import com.yugabyte.yw.models.ScheduleTask;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.CustomerConfig.ConfigState;
import com.yugabyte.yw.models.helpers.PlatformMetrics;
import com.yugabyte.yw.models.helpers.TaskType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.yb.CommonTypes.TableType;
import org.yb.client.GetTableSchemaResponse;
import org.yb.client.ListTablesResponse;
import org.yb.client.YBClient;
import org.yb.master.MasterDdlOuterClass.ListTablesResponsePB.TableInfo;
import org.yb.master.MasterTypes.RelationType;
import play.libs.Json;

@Slf4j
@Abortable
public class CreateBackup extends UniverseTaskBase {

  @Inject
  protected CreateBackup(BaseTaskDependencies baseTaskDependencies) {
    super(baseTaskDependencies);
  }

  protected BackupRequestParams params() {
    return (BackupRequestParams) taskParams;
  }

  @Inject CustomerConfigService customerConfigService;

  @Override
  public void run() {
    List<BackupTableParams> backupParamsList = new ArrayList<>();
    BackupTableParams tableBackupParams = new BackupTableParams();
    tableBackupParams.customerUuid = params().customerUUID;
    tableBackupParams.ignoreErrors = true;
    tableBackupParams.storageConfigUUID = params().storageConfigUUID;
    tableBackupParams.universeUUID = params().universeUUID;
    tableBackupParams.sse = params().sse;
    tableBackupParams.parallelism = params().parallelism;
    tableBackupParams.timeBeforeDelete = params().timeBeforeDelete;
    tableBackupParams.backupType = params().backupType;
    Set<String> tablesToBackup = new HashSet<>();
    Universe universe = Universe.getOrBadRequest(params().universeUUID);
    MetricLabelsBuilder metricLabelsBuilder = MetricLabelsBuilder.create().appendSource(universe);
    BACKUP_ATTEMPT_COUNTER.labels(metricLabelsBuilder.getPrometheusValues()).inc();
    boolean isUniverseLocked = false;
    try {
      checkUniverseVersion();
      subTaskGroupQueue = new SubTaskGroupQueue(userTaskUUID);

      // Update the universe DB with the update to be performed and set the 'updateInProgress' flag
      // to prevent other updates from happening.
      lockUniverse(-1 /* expectedUniverseVersion */);
      isUniverseLocked = true;
      // Update universe 'backupInProgress' flag to true or throw an exception if universe is
      // already having a backup in progress.
      lockedUpdateBackupState(params().universeUUID, this, true);
      try {
        String masterAddresses = universe.getMasterAddresses(true);
        String certificate = universe.getCertificateNodetoNode();
        Boolean firstIteration = true;

        YBClient client = null;
        try {
          client = ybService.getClient(masterAddresses, certificate);
          List<TableInfo> tableInfoList = new ArrayList<TableInfo>();
          HashMap<String, BackupTableParams> keyspaceMap = new HashMap<>();
          if (params().keyspaceTableList != null) {
            for (BackupRequestParams.KeyspaceTable keyspaceTable : params().keyspaceTableList) {
              BackupTableParams backupParams =
                  createBackupParams(params().backupType, keyspaceTable.keyspace);
              Set<UUID> tableSet = new HashSet<>(keyspaceTable.tableUUIDList);
              if (tableSet.size() != 0) {
                for (UUID tableUUID : tableSet) {
                  GetTableSchemaResponse tableSchema =
                      client.getTableSchemaByUUID(tableUUID.toString().replace("-", ""));
                  // If table is not REDIS or YCQL, ignore.
                  if (tableSchema.getTableType() == TableType.PGSQL_TABLE_TYPE
                      || tableSchema.getTableType() != params().backupType
                      || tableSchema.getTableType() == TableType.TRANSACTION_STATUS_TABLE_TYPE
                      || !keyspaceTable.keyspace.equals(tableSchema.getNamespace())) {
                    log.info(
                        "Skipping backup of table with UUID: "
                            + tableUUID
                            + " and keyspace: "
                            + keyspaceTable.keyspace);
                    continue;
                  }
                  populateBackupParams(
                      backupParams,
                      tableSchema.getTableType(),
                      tableSchema.getNamespace(),
                      tableSchema.getTableName(),
                      tableUUID);
                  log.info(
                      "Queuing backup for table {}:{}",
                      tableSchema.getNamespace(),
                      tableSchema.getTableName());

                  tablesToBackup.add(
                      String.format(
                          "%s:%s", tableSchema.getNamespace(), tableSchema.getTableName()));
                }
                if (backupParams.tableUUIDList != null) {
                  backupParamsList.add(backupParams);
                }
              } else {
                if (firstIteration) {
                  ListTablesResponse response = client.getTablesList(null, true, null);
                  tableInfoList = response.getTableInfoList();
                  firstIteration = false;
                }
                for (TableInfo table : tableInfoList) {
                  TableType tableType = table.getTableType();
                  String tableKeySpace = table.getNamespace().getName();
                  String tableUUIDString = table.getId().toStringUtf8();
                  UUID tableUUID = getUUIDRepresentation(tableUUIDString);
                  if (tableType != params().backupType
                      || tableType == TableType.TRANSACTION_STATUS_TABLE_TYPE
                      || table.getRelationType() == RelationType.INDEX_TABLE_RELATION
                      || !keyspaceTable.keyspace.equals(tableKeySpace)) {
                    log.info(
                        "Skipping keyspace/universe backup of table "
                            + tableUUID
                            + ". Expected keyspace is "
                            + keyspaceTable.keyspace
                            + "; actual keyspace is "
                            + tableKeySpace);
                    continue;
                  }
                  if (tableType == TableType.PGSQL_TABLE_TYPE
                      && !keyspaceMap.containsKey(tableKeySpace)) {
                    keyspaceMap.put(tableKeySpace, backupParams);
                    backupParamsList.add(backupParams);
                    tablesToBackup.add(String.format("%s:%s", tableKeySpace, table.getName()));
                  } else if (tableType == TableType.YQL_TABLE_TYPE
                      || tableType == TableType.REDIS_TABLE_TYPE) {
                    if (!keyspaceMap.containsKey(tableKeySpace)) {
                      keyspaceMap.put(tableKeySpace, backupParams);
                      backupParamsList.add(backupParams);
                    }
                    BackupTableParams currentBackup = keyspaceMap.get(tableKeySpace);
                    populateBackupParams(
                        currentBackup, tableType, tableKeySpace, table.getName(), tableUUID);
                    tablesToBackup.add(String.format("%s:%s", tableKeySpace, table.getName()));
                  } else {
                    log.error(
                        "Unrecognized table type {} for {}:{}",
                        tableType,
                        tableKeySpace,
                        table.getName());
                  }
                  log.info("Queuing backup for table {}:{}", tableKeySpace, table.getName());
                }
              }
            }
          } else {
            ListTablesResponse response = client.getTablesList(null, true, null);
            tableInfoList = response.getTableInfoList();

            for (TableInfo table : tableInfoList) {
              TableType tableType = table.getTableType();
              String tableKeySpace = table.getNamespace().getName();
              String tableUUIDString = table.getId().toStringUtf8();
              UUID tableUUID = getUUIDRepresentation(tableUUIDString);
              if (tableType != params().backupType
                  || tableType == TableType.TRANSACTION_STATUS_TABLE_TYPE
                  || table.getRelationType() == RelationType.INDEX_TABLE_RELATION) {
                log.info("Skipping backup of table " + tableUUID);
                continue;
              }

              if (tableType == TableType.PGSQL_TABLE_TYPE
                  && SYSTEM_PLATFORM_DB.equals(tableKeySpace)) {
                log.info("Skipping " + SYSTEM_PLATFORM_DB + " database");
                continue;
              }

              if (tableType == TableType.PGSQL_TABLE_TYPE
                  && !keyspaceMap.containsKey(tableKeySpace)) {
                BackupTableParams backupParams =
                    createBackupParams(params().backupType, tableKeySpace);
                keyspaceMap.put(tableKeySpace, backupParams);
                backupParamsList.add(backupParams);
                tablesToBackup.add(String.format("%s:%s", tableKeySpace, table.getName()));
              } else if (tableType == TableType.YQL_TABLE_TYPE
                  || tableType == TableType.REDIS_TABLE_TYPE) {
                if (!keyspaceMap.containsKey(tableKeySpace)) {
                  BackupTableParams backupParams =
                      createBackupParams(params().backupType, tableKeySpace);
                  keyspaceMap.put(tableKeySpace, backupParams);
                  backupParamsList.add(backupParams);
                }
                BackupTableParams currentBackup = keyspaceMap.get(tableKeySpace);
                populateBackupParams(
                    currentBackup, tableType, tableKeySpace, table.getName(), tableUUID);
                tablesToBackup.add(String.format("%s:%s", tableKeySpace, table.getName()));
              } else {
                log.error(
                    "Unrecognized table type {} for {}:{}",
                    tableType,
                    tableKeySpace,
                    table.getName());
              }
              log.info("Queuing backup for table {}:{}", tableKeySpace, table.getName());
            }
          }
          if (!tablesToBackup.isEmpty()) {
            tableBackupParams.backupList = backupParamsList;
          } else {
            throw new RuntimeException("Invalid Keyspaces or no tables to backup");
          }
        } catch (Exception e) {
          log.error("Failed to get list of tables in universe " + params().universeUUID, e);
          unlockUniverseForUpdate();
          isUniverseLocked = false;
          // Do not lose the actual exception thrown.
          Throwables.propagate(e);
        } finally {
          ybService.closeClient(client, masterAddresses);
        }

        // Check if the storage config is in active state or not.
        CustomerConfig customerConfig =
            customerConfigService.getOrBadRequest(
                params().customerUUID, params().storageConfigUUID);
        if (!customerConfig.getState().equals(ConfigState.Active)) {
          throw new RuntimeException("Storage config cannot be used as it is not in Active state");
        }

        subTaskGroupQueue = new SubTaskGroupQueue(userTaskUUID);

        Backup backup =
            Backup.create(
                params().customerUUID,
                tableBackupParams,
                BackupCategory.YB_BACKUP_SCRIPT,
                BackupVersion.V2);
        backup.setTaskUUID(userTaskUUID);
        tableBackupParams.backupUuid = backup.backupUUID;
        log.info("Task id {} for the backup {}", backup.taskUUID, backup.backupUUID);

        for (BackupTableParams backupParams : backupParamsList) {
          createEncryptedUniverseKeyBackupTask(backupParams)
              .setSubTaskGroupType(UserTaskDetails.SubTaskGroupType.CreatingTableBackup);
        }
        createTableBackupTaskYb(tableBackupParams)
            .setSubTaskGroupType(UserTaskDetails.SubTaskGroupType.CreatingTableBackup);

        // Marks the update of this universe as a success only if all the tasks before it succeeded.
        createMarkUniverseUpdateSuccessTasks()
            .setSubTaskGroupType(UserTaskDetails.SubTaskGroupType.ConfigureUniverse);

        taskInfo = String.join(",", tablesToBackup);

        unlockUniverseForUpdate();
        isUniverseLocked = false;
        subTaskGroupQueue.run();

        BACKUP_SUCCESS_COUNTER.labels(metricLabelsBuilder.getPrometheusValues()).inc();
        metricService.setOkStatusMetric(
            buildMetricTemplate(PlatformMetrics.CREATE_BACKUP_STATUS, universe));

      } catch (Throwable t) {
        throw t;
      } finally {
        lockedUpdateBackupState(params().universeUUID, this, false);
      }
    } catch (Throwable t) {
      log.error("Error executing task {} with error='{}'.", getName(), t.getMessage(), t);

      BACKUP_FAILURE_COUNTER.labels(metricLabelsBuilder.getPrometheusValues()).inc();
      metricService.setStatusMetric(
          buildMetricTemplate(PlatformMetrics.CREATE_BACKUP_STATUS, universe), t.getMessage());
      // Run an unlock in case the task failed before getting to the unlock. It is okay if it
      // errors out.
      if (isUniverseLocked) {
        unlockUniverseForUpdate();
      }
      throw t;
    }
    log.info("Finished {} task.", getName());
  }

  // Helper method to update passed in reference object
  private void populateBackupParams(
      BackupTableParams backupParams,
      TableType backupType,
      String tableKeySpace,
      String tableName,
      UUID tableUUID) {

    // backupParams.actionType = BackupTableParams.ActionType.CREATE;
    backupParams.storageConfigUUID = params().storageConfigUUID;
    backupParams.universeUUID = params().universeUUID;
    backupParams.sse = params().sse;
    backupParams.parallelism = params().parallelism;
    backupParams.timeBeforeDelete = params().timeBeforeDelete;
    backupParams.scheduleUUID = params().scheduleUUID;
    backupParams.setKeyspace(tableKeySpace);
    backupParams.backupType = backupType;

    if (tableName != null && tableUUID != null) {
      if (backupParams.tableNameList == null) {
        backupParams.tableNameList = new ArrayList<>();
        backupParams.tableUUIDList = new ArrayList<>();
        if (backupParams.getTableName() != null && backupParams.tableUUID != null) {
          // Clear singular fields and add to lists
          backupParams.tableNameList.add(backupParams.getTableName());
          backupParams.tableUUIDList.add(backupParams.tableUUID);
          backupParams.setTableName(null);
          backupParams.tableUUID = null;
        }
      }
      backupParams.tableNameList.add(tableName);
      backupParams.tableUUIDList.add(tableUUID);
    }
  }

  private void populateBackupParams(
      BackupTableParams backupParams, TableType backupType, String tableKeySpace) {
    populateBackupParams(backupParams, backupType, tableKeySpace, null, null);
  }

  private BackupTableParams createBackupParams(TableType backupType, String tableKeySpace) {
    return createBackupParams(backupType, tableKeySpace, null, null);
  }

  private BackupTableParams createBackupParams(
      TableType backupType, String tableKeySpace, String tableName, UUID tableUUID) {
    BackupTableParams backupParams = new BackupTableParams();
    backupParams.setKeyspace(tableKeySpace);
    backupParams.backupType = backupType;
    if (tableUUID != null && tableName != null) {
      backupParams.tableUUID = tableUUID;
      backupParams.setTableName(tableName);
    }
    // backupParams.actionType = BackupTableParams.ActionType.CREATE;
    backupParams.storageConfigUUID = params().storageConfigUUID;
    backupParams.universeUUID = params().universeUUID;
    backupParams.sse = params().sse;
    backupParams.parallelism = params().parallelism;
    backupParams.timeBeforeDelete = params().timeBeforeDelete;
    backupParams.scheduleUUID = params().scheduleUUID;
    return backupParams;
  }

  public void runScheduledBackup(
      Schedule schedule, Commissioner commissioner, boolean alreadyRunning) {
    UUID customerUUID = schedule.getCustomerUUID();
    Customer customer = Customer.get(customerUUID);
    JsonNode params = schedule.getTaskParams();
    BackupTableParams taskParams = Json.fromJson(params, BackupTableParams.class);
    taskParams.scheduleUUID = schedule.scheduleUUID;
    Universe universe;
    try {
      universe = Universe.getOrBadRequest(taskParams.universeUUID);
    } catch (Exception e) {
      schedule.stopSchedule();
      return;
    }
    MetricLabelsBuilder metricLabelsBuilder = MetricLabelsBuilder.create().appendSource(universe);
    SCHEDULED_BACKUP_ATTEMPT_COUNTER.labels(metricLabelsBuilder.getPrometheusValues()).inc();
    Map<String, String> config = universe.getConfig();
    boolean shouldTakeBackup =
        !universe.getUniverseDetails().universePaused
            && config.get(Universe.TAKE_BACKUPS).equals("true");
    if (alreadyRunning
        || !shouldTakeBackup
        || universe.getUniverseDetails().backupInProgress
        || universe.getUniverseDetails().updateInProgress) {

      if (shouldTakeBackup) {
        SCHEDULED_BACKUP_FAILURE_COUNTER.labels(metricLabelsBuilder.getPrometheusValues()).inc();
        metricService.setFailureStatusMetric(
            buildMetricTemplate(PlatformMetrics.SCHEDULE_BACKUP_STATUS, universe));
      }

      log.warn(
          "Cannot run MultiTableBackup task since the universe {} is currently {}",
          taskParams.universeUUID.toString(),
          "in a locked/paused state or has backup running");
      return;
    }
    UUID taskUUID = commissioner.submit(TaskType.CreateBackup, taskParams);
    ScheduleTask.create(taskUUID, schedule.getScheduleUUID());
    log.info(
        "Submitted backup for universe: {}, task uuid = {}.", taskParams.universeUUID, taskUUID);
    CustomerTask.create(
        customer,
        taskParams.universeUUID,
        taskUUID,
        CustomerTask.TargetType.Backup,
        CustomerTask.TaskType.Create,
        universe.name);
    log.info(
        "Saved task uuid {} in customer tasks table for universe {}:{}",
        taskUUID,
        taskParams.universeUUID,
        universe.name);
    SCHEDULED_BACKUP_SUCCESS_COUNTER.labels(metricLabelsBuilder.getPrometheusValues()).inc();
    metricService.setOkStatusMetric(
        buildMetricTemplate(PlatformMetrics.SCHEDULE_BACKUP_STATUS, universe));
  }
}