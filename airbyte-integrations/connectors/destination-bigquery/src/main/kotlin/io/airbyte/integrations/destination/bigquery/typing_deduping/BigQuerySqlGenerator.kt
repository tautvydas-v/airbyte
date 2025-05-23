/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */
package io.airbyte.integrations.destination.bigquery.typing_deduping

import com.google.cloud.bigquery.StandardSQLTypeName
import com.google.common.annotations.VisibleForTesting
import io.airbyte.integrations.base.destination.typing_deduping.*
import io.airbyte.integrations.base.destination.typing_deduping.Array
import io.airbyte.integrations.destination.bigquery.BigQuerySQLNameTransformer
import java.time.Instant
import java.util.*
import java.util.stream.Collectors
import java.util.stream.Stream
import org.apache.commons.lang3.StringUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class BigQuerySqlGenerator
/**
 * @param projectId
 * @param datasetLocation This is technically redundant with [BigQueryDestinationHandler] setting
 * the query execution location, but let's be explicit since this is typically a compliance
 * requirement.
 */
(private val projectId: String?, private val datasetLocation: String?) : SqlGenerator {
    private val CDC_DELETED_AT_COLUMN = buildColumnId("_ab_cdc_deleted_at")

    private val LOGGER: Logger = LoggerFactory.getLogger(BigQuerySqlGenerator::class.java)

    override fun buildStreamId(
        namespace: String,
        name: String,
        rawNamespaceOverride: String
    ): StreamId {
        return StreamId(
            nameTransformer.getNamespace(namespace),
            nameTransformer.convertStreamName(name),
            nameTransformer.getNamespace(rawNamespaceOverride),
            nameTransformer.convertStreamName(StreamId.concatenateRawTableName(namespace, name)),
            namespace,
            name
        )
    }

    override fun buildColumnId(name: String, suffix: String?): ColumnId {
        val nameWithSuffix = name + suffix
        return ColumnId(
            nameTransformer.getIdentifier(nameWithSuffix),
            name, // Bigquery columns are case-insensitive, so do all our validation on the
            // lowercased name
            nameTransformer.getIdentifier(nameWithSuffix.lowercase(Locale.getDefault()))
        )
    }

    private fun extractAndCast(
        column: ColumnId,
        airbyteType: AirbyteType,
        forceSafeCast: Boolean
    ): String {
        if (airbyteType is Union) {
            // This is guaranteed to not be a Union, so we won't recurse infinitely
            val chosenType: AirbyteType = airbyteType.chooseType()
            return extractAndCast(column, chosenType, forceSafeCast)
        }
        val columnName = escapeColumnNameForJsonPath(column.originalName)

        if (airbyteType is Struct) {
            // We need to validate that the struct is actually a struct.
            // Note that struct columns are actually nullable in two ways. For a column `foo`:
            // {foo: null} and {} are both valid, and are both written to the final table as a SQL
            // NULL (_not_ a
            // JSON null).
            // JSON_QUERY(JSON'{}', '$."foo"') returns a SQL null.
            // JSON_QUERY(JSON'{"foo": null}', '$."foo"') returns a JSON null.
            return """
                   PARSE_JSON(CASE
                     WHEN JSON_QUERY(`_airbyte_data`, '${'$'}."$columnName"') IS NULL
                       OR JSON_TYPE(PARSE_JSON(JSON_QUERY(`_airbyte_data`, '${'$'}."$columnName"'), wide_number_mode=>'round')) != 'object'
                       THEN NULL
                     ELSE JSON_QUERY(`_airbyte_data`, '${'$'}."$columnName"')
                   END, wide_number_mode=>'round')
                   """.trimIndent()
        }

        if (airbyteType is Array) {
            // Much like the Struct case above, arrays need special handling.
            return """
                   PARSE_JSON(CASE
                     WHEN JSON_QUERY(`_airbyte_data`, '${'$'}."$columnName"') IS NULL
                       OR JSON_TYPE(PARSE_JSON(JSON_QUERY(`_airbyte_data`, '${'$'}."$columnName"'), wide_number_mode=>'round')) != 'array'
                       THEN NULL
                     ELSE JSON_QUERY(`_airbyte_data`, '${'$'}."$columnName"')
                   END, wide_number_mode=>'round')
                   """.trimIndent()
        }

        if (airbyteType is UnsupportedOneOf || airbyteType === AirbyteProtocolType.UNKNOWN) {
            // JSON_QUERY returns a SQL null if the field contains a JSON null, so we actually parse
            // the
            // airbyte_data to json
            // and json_query it directly (which preserves nulls correctly).
            return """JSON_QUERY(PARSE_JSON(`_airbyte_data`, wide_number_mode=>'round'), '${'$'}."$columnName"')"""
        }

        if (airbyteType === AirbyteProtocolType.STRING) {
            // Special case String to only use json value for type string and parse the json for
            // others
            // Naive json_value returns NULL for object/array values and json_query adds escaped
            // quotes to the
            // string.
            return """
                   (CASE
                     WHEN JSON_QUERY(`_airbyte_data`, '${'$'}."$columnName"') IS NULL
                       OR JSON_TYPE(PARSE_JSON(JSON_QUERY(`_airbyte_data`, '${'$'}."$columnName"'), wide_number_mode=>'round')) != 'string'
                       THEN JSON_QUERY(`_airbyte_data`, '${'$'}."$columnName"')
                     ELSE
                     JSON_VALUE(`_airbyte_data`, '${'$'}."$columnName"')
                   END)
                   """.trimIndent()
        }

        val dialectType = toDialectType(airbyteType)
        val baseTyping = """JSON_VALUE(`_airbyte_data`, '$."$columnName"')"""
        return if (dialectType == StandardSQLTypeName.STRING) {
            // json_value implicitly returns a string, so we don't need to cast it.
            baseTyping
        } else {
            // SAFE_CAST is actually a massive performance hit, so we should skip it if we can.
            cast(baseTyping, dialectType.name, forceSafeCast)
        }
    }

    override fun createTable(stream: StreamConfig, suffix: String, force: Boolean): Sql {
        val columnDeclarations = columnsAndTypes(stream)
        val clusterConfig =
            clusteringColumns(stream)
                .stream()
                .map { c: String? -> StringUtils.wrap(c, QUOTE) }
                .collect(Collectors.joining(", "))
        val forceCreateTable = if (force) "OR REPLACE" else ""
        val finalTableId = stream.id.finalTableId(QUOTE, suffix)
        return Sql.of(
            """
            CREATE $forceCreateTable TABLE `$projectId`.$finalTableId (
              _airbyte_raw_id STRING NOT NULL,
              _airbyte_extracted_at TIMESTAMP NOT NULL,
              _airbyte_meta JSON NOT NULL,
              _airbyte_generation_id INTEGER,
              $columnDeclarations
            )
            PARTITION BY (DATE_TRUNC(_airbyte_extracted_at, DAY))
            CLUSTER BY $clusterConfig;
            """.trimIndent()
        )
    }

    private fun columnsAndTypes(stream: StreamConfig): String {
        return stream.columns.entries
            .stream()
            .map { column: Map.Entry<ColumnId, AirbyteType> ->
                java.lang.String.join(" ", column.key.name(QUOTE), toDialectType(column.value).name)
            }
            .collect(Collectors.joining(",\n"))
    }

    override fun prepareTablesForSoftReset(stream: StreamConfig): Sql {
        // Bigquery can't run DDL in a transaction, so these are separate transactions.
        return Sql
            .concat( // If a previous sync failed to delete the soft reset temp table (unclear why
                // this happens),
                // AND this sync is trying to change the clustering config, then we need to manually
                // drop the soft
                // reset temp table.
                // Even though we're using CREATE OR REPLACE TABLE, bigquery will still complain
                // about the
                // clustering config being changed.
                // So we explicitly drop the soft reset temp table first.
                dropTableIfExists(stream, TyperDeduperUtil.SOFT_RESET_SUFFIX),
                createTable(stream, TyperDeduperUtil.SOFT_RESET_SUFFIX, true),
                clearLoadedAt(stream.id)
            )
    }

    private fun dropTableIfExists(stream: StreamConfig, suffix: String): Sql {
        val tableId = stream.id.finalTableId(QUOTE, suffix)
        return Sql.of("""DROP TABLE IF EXISTS `$projectId`.$tableId;""")
    }

    override fun clearLoadedAt(streamId: StreamId): Sql {
        val rawTableId = streamId.rawTableId(QUOTE)
        return Sql.of(
            """UPDATE `$projectId`.$rawTableId SET _airbyte_loaded_at = NULL WHERE 1=1;"""
        )
    }

    override fun updateTable(
        stream: StreamConfig,
        finalSuffix: String,
        minRawTimestamp: Optional<Instant>,
        useExpensiveSaferCasting: Boolean
    ): Sql {
        val handleNewRecords =
            if (stream.postImportAction == ImportType.DEDUPE) {
                upsertNewRecords(stream, finalSuffix, useExpensiveSaferCasting, minRawTimestamp)
            } else {
                insertNewRecords(stream, finalSuffix, useExpensiveSaferCasting, minRawTimestamp)
            }
        val commitRawTable = commitRawTable(stream.id, minRawTimestamp)

        return Sql.transactionally(handleNewRecords, commitRawTable)
    }

    private fun insertNewRecords(
        stream: StreamConfig,
        finalSuffix: String,
        forceSafeCasting: Boolean,
        minRawTimestamp: Optional<Instant>
    ): String {
        val columnList: String =
            stream.columns.keys
                .stream()
                .map { quotedColumnId: ColumnId -> quotedColumnId.name(QUOTE) + "," }
                .collect(Collectors.joining("\n"))
        val extractNewRawRecords = extractNewRawRecords(stream, forceSafeCasting, minRawTimestamp)
        val finalTableId = stream.id.finalTableId(QUOTE, finalSuffix)

        return """
               INSERT INTO `$projectId`.$finalTableId
               (
               $columnList
                 _airbyte_meta,
                 _airbyte_raw_id,
                 _airbyte_extracted_at,
                 _airbyte_generation_id
               )
               $extractNewRawRecords;
               """.trimIndent()
    }

    private fun upsertNewRecords(
        stream: StreamConfig,
        finalSuffix: String,
        forceSafeCasting: Boolean,
        minRawTimestamp: Optional<Instant>
    ): String {
        val pkEquivalent =
            stream.primaryKey
                .stream()
                .map { pk: ColumnId ->
                    val quotedPk = pk.name(QUOTE)
                    ("""(target_table.$quotedPk = new_record.$quotedPk OR (target_table.$quotedPk IS NULL AND new_record.$quotedPk IS NULL))""")
                }
                .collect(Collectors.joining(" AND "))

        val columnList: String =
            stream.columns.keys
                .stream()
                .map { quotedColumnId: ColumnId -> quotedColumnId.name(QUOTE) + "," }
                .collect(Collectors.joining("\n"))
        val newRecordColumnList: String =
            stream.columns.keys
                .stream()
                .map { quotedColumnId: ColumnId ->
                    "new_record." + quotedColumnId.name(QUOTE) + ","
                }
                .collect(Collectors.joining("\n"))
        val extractNewRawRecords = extractNewRawRecords(stream, forceSafeCasting, minRawTimestamp)

        val cursorComparison: String
        if (stream.cursor.isPresent) {
            val cursor = stream.cursor.get().name(QUOTE)
            // Build a condition for "new_record is more recent than target_table":
            cursorComparison = // First, compare the cursors.
            ("""
             (
               target_table.$cursor < new_record.$cursor
               OR (target_table.$cursor = new_record.$cursor AND target_table._airbyte_extracted_at < new_record._airbyte_extracted_at)
               OR (target_table.$cursor IS NULL AND new_record.$cursor IS NULL AND target_table._airbyte_extracted_at < new_record._airbyte_extracted_at)
               OR (target_table.$cursor IS NULL AND new_record.$cursor IS NOT NULL)
             )
             """.trimIndent())
        } else {
            // If there's no cursor, then we just take the most-recently-emitted record
            cursorComparison =
                "target_table._airbyte_extracted_at < new_record._airbyte_extracted_at"
        }

        val cdcDeleteClause: String
        val cdcSkipInsertClause: String
        if (stream.columns.containsKey(CDC_DELETED_AT_COLUMN)) {
            // Execute CDC deletions if there's already a record
            cdcDeleteClause =
                "WHEN MATCHED AND new_record._ab_cdc_deleted_at IS NOT NULL AND $cursorComparison THEN DELETE"
            // And skip insertion entirely if there's no matching record.
            // (This is possible if a single T+D batch contains both an insertion and deletion for
            // the same PK)
            cdcSkipInsertClause = "AND new_record._ab_cdc_deleted_at IS NULL"
        } else {
            cdcDeleteClause = ""
            cdcSkipInsertClause = ""
        }

        val columnAssignments: String =
            stream.columns.keys
                .stream()
                .map { airbyteType: ColumnId ->
                    val column = airbyteType.name(QUOTE)
                    "$column = new_record.$column,"
                }
                .collect(Collectors.joining("\n"))
        val finalTableId = stream.id.finalTableId(QUOTE, finalSuffix)

        return """
               MERGE `$projectId`.$finalTableId target_table
               USING (
                 $extractNewRawRecords
               ) new_record
               ON $pkEquivalent
               $cdcDeleteClause
               WHEN MATCHED AND $cursorComparison THEN UPDATE SET
                 $columnAssignments
                 _airbyte_meta = new_record._airbyte_meta,
                 _airbyte_raw_id = new_record._airbyte_raw_id,
                 _airbyte_extracted_at = new_record._airbyte_extracted_at,
                 _airbyte_generation_id = new_record._airbyte_generation_id
               WHEN NOT MATCHED $cdcSkipInsertClause THEN INSERT (
                 $columnList
                 _airbyte_meta,
                 _airbyte_raw_id,
                 _airbyte_extracted_at,
                 _airbyte_generation_id
               ) VALUES (
                 $newRecordColumnList
                 new_record._airbyte_meta,
                 new_record._airbyte_raw_id,
                 new_record._airbyte_extracted_at,
                 new_record._airbyte_generation_id
               );
               """.trimIndent()
    }

    /**
     * A SQL SELECT statement that extracts new records from the raw table, casts their columns, and
     * builds their airbyte_meta column.
     *
     * In dedup mode: Also extracts all raw CDC deletion records (for tombstoning purposes) and
     * dedupes the records (since we only need the most-recent record to upsert).
     */
    private fun extractNewRawRecords(
        stream: StreamConfig,
        forceSafeCasting: Boolean,
        minRawTimestamp: Optional<Instant>
    ): String {
        val columnCasts: String =
            stream.columns.entries
                .stream()
                .map { col: Map.Entry<ColumnId, AirbyteType> ->
                    val extractAndCast = extractAndCast(col.key, col.value, forceSafeCasting)
                    val columnName = col.key.name(QUOTE)
                    """$extractAndCast as $columnName,"""
                }
                .collect(Collectors.joining("\n"))
        val columnErrors =
            if (forceSafeCasting) {
                "[" +
                    stream.columns.entries
                        .stream()
                        .map { col: Map.Entry<ColumnId, AirbyteType> ->
                            val rawColName = escapeColumnNameForJsonPath(col.key.originalName)
                            val jsonExtract = extractAndCast(col.key, col.value, true)
                            // Explicitly parse json here. This is safe because
                            // we're not using the actual value anywhere,
                            // and necessary because json_query
                            """
                            CASE
                              WHEN (JSON_QUERY(PARSE_JSON(`_airbyte_data`, wide_number_mode=>'round'), '${'$'}."$rawColName"') IS NOT NULL)
                                AND (JSON_TYPE(JSON_QUERY(PARSE_JSON(`_airbyte_data`, wide_number_mode=>'round'), '${'$'}."$rawColName"')) != 'null')
                                AND ($jsonExtract IS NULL)
                                THEN JSON '{"field":"$rawColName","change":"NULLED","reason":"DESTINATION_TYPECAST_ERROR"}'
                              ELSE NULL
                            END
                            """.trimIndent()
                        }
                        .collect(Collectors.joining(",\n")) +
                    "]"
            } else {
                // We're not safe casting, so any error should throw an exception and trigger the
                // safe cast logic
                "[]"
            }

        val columnList: String =
            stream.columns.keys
                .stream()
                .map { quotedColumnId: ColumnId -> quotedColumnId.name(QUOTE) + "," }
                .collect(Collectors.joining("\n"))
        val extractedAtCondition = buildExtractedAtCondition(minRawTimestamp)

        val rawTableId = stream.id.rawTableId(QUOTE)
        if (stream.postImportAction == ImportType.DEDUPE) {
            // When deduping, we need to dedup the raw records. Note the row_number() invocation in
            // the SQL
            // statement. Do the same extract+cast CTE + airbyte_meta construction as in non-dedup
            // mode, but
            // then add a row_number column so that we only take the most-recent raw record for each
            // PK.

            // We also explicitly include old CDC deletion records, which act as tombstones to
            // correctly delete
            // out-of-order records.

            var cdcConditionalOrIncludeStatement = ""
            if (stream.columns.containsKey(CDC_DELETED_AT_COLUMN)) {
                cdcConditionalOrIncludeStatement =
                    """
                    OR (
                      _airbyte_loaded_at IS NOT NULL
                      AND JSON_VALUE(`_airbyte_data`, '${'$'}._ab_cdc_deleted_at') IS NOT NULL
                    )
                    """.trimIndent()
            }

            val pkList =
                stream.primaryKey
                    .stream()
                    .map { columnId: ColumnId -> columnId.name(QUOTE) }
                    .collect(Collectors.joining(","))
            val cursorOrderClause =
                stream.cursor
                    .map { cursorId: ColumnId -> cursorId.name(QUOTE) + " DESC NULLS LAST," }
                    .orElse("")

            return """
                   WITH intermediate_data AS (
                     SELECT
                   $columnCasts
                     $columnErrors AS column_errors,
                     _airbyte_raw_id,
                     _airbyte_extracted_at,
                     _airbyte_meta,
                     _airbyte_generation_id
                     FROM `$projectId`.$rawTableId
                     WHERE (
                         _airbyte_loaded_at IS NULL
                         $cdcConditionalOrIncludeStatement
                       ) $extractedAtCondition
                   ), new_records AS (
                     SELECT
                     $columnList
                       to_json(json_set(
                         coalesce(parse_json(_airbyte_meta), JSON'{}'),
                         '${'$'}.changes',
                         json_array_append(
                           coalesce(json_query(parse_json(_airbyte_meta), '${'$'}.changes'), JSON'[]'),
                           '${'$'}',
                           COALESCE((SELECT ARRAY_AGG(unnested_column_errors IGNORE NULLS) FROM UNNEST(column_errors) unnested_column_errors), [])
                          )
                       )) as _airbyte_meta,
                       _airbyte_raw_id,
                       _airbyte_extracted_at,
                       _airbyte_generation_id
                     FROM intermediate_data
                   ), numbered_rows AS (
                     SELECT *, row_number() OVER (
                       PARTITION BY $pkList ORDER BY $cursorOrderClause `_airbyte_extracted_at` DESC
                     ) AS row_number
                     FROM new_records
                   )
                   SELECT $columnList _airbyte_meta, _airbyte_raw_id, _airbyte_extracted_at, _airbyte_generation_id
                   FROM numbered_rows
                   WHERE row_number = 1
                   """.trimIndent()
        } else {
            // When not deduplicating, we just need to handle type casting.
            // Extract+cast the not-yet-loaded records in a CTE, then select that CTE and build
            // airbyte_meta.

            return """
                   WITH intermediate_data AS (
                     SELECT
                   $columnCasts
                     $columnErrors AS column_errors,
                     _airbyte_raw_id,
                     _airbyte_extracted_at,
                     _airbyte_meta,
                     _airbyte_generation_id
                     FROM `$projectId`.$rawTableId
                     WHERE
                       _airbyte_loaded_at IS NULL
                       $extractedAtCondition
                   )
                   SELECT
                   $columnList
                     to_json(json_set(
                         coalesce(parse_json(_airbyte_meta), JSON'{}'),
                         '${'$'}.changes',
                         json_array_append(
                           coalesce(json_query(parse_json(_airbyte_meta), '${'$'}.changes'), JSON'[]'),
                           '${'$'}',
                           COALESCE((SELECT ARRAY_AGG(unnested_column_errors IGNORE NULLS) FROM UNNEST(column_errors) unnested_column_errors), [])
                          )
                       )) as _airbyte_meta,
                     _airbyte_raw_id,
                     _airbyte_extracted_at,
                     _airbyte_generation_id
                   FROM intermediate_data
                   """.trimIndent()
        }
    }

    @VisibleForTesting
    fun commitRawTable(id: StreamId, minRawTimestamp: Optional<Instant>): String {
        val rawTableId = id.rawTableId(QUOTE)
        val extractedAtCondition = buildExtractedAtCondition(minRawTimestamp)
        return """
               UPDATE `$projectId`.$rawTableId
               SET `_airbyte_loaded_at` = CURRENT_TIMESTAMP()
               WHERE `_airbyte_loaded_at` IS NULL
                 $extractedAtCondition
               ;
               """.trimIndent()
    }

    override fun overwriteFinalTable(stream: StreamId, finalSuffix: String): Sql {
        val finalTableId = stream.finalTableId(QUOTE)
        val tempFinalTableId = stream.finalTableId(QUOTE, finalSuffix)
        val realFinalTableName = stream.finalName(QUOTE)
        return Sql.separately(
            "DROP TABLE IF EXISTS `$projectId`.$finalTableId;",
            "ALTER TABLE `$projectId`.$tempFinalTableId RENAME TO $realFinalTableName;"
        )
    }

    private fun wrapAndQuote(namespace: String, tableName: String): String {
        return Stream.of(namespace, tableName)
            .map { part: String? -> StringUtils.wrap(part, QUOTE) }
            .collect(Collectors.joining("."))
    }

    override fun createSchema(schema: String): Sql {
        val projectId = StringUtils.wrap(projectId, QUOTE)
        val quotedSchema = StringUtils.wrap(schema, QUOTE)
        return Sql.of(
            """CREATE SCHEMA IF NOT EXISTS $projectId.$quotedSchema OPTIONS(location="$datasetLocation");"""
        )
    }

    override fun migrateFromV1toV2(streamId: StreamId, namespace: String, tableName: String): Sql {
        val v2RawTable = streamId.rawTableId(QUOTE)
        val v1RawTable = wrapAndQuote(namespace, tableName)
        return Sql.of(
            """
            CREATE OR REPLACE TABLE `$projectId`.$v2RawTable (
              _airbyte_raw_id STRING,
              _airbyte_data STRING,
              _airbyte_extracted_at TIMESTAMP,
              _airbyte_loaded_at TIMESTAMP,
              _airbyte_meta STRING,
              _airbyte_generation_id INTEGER
            )
            PARTITION BY DATE(_airbyte_extracted_at)
            CLUSTER BY _airbyte_extracted_at
            AS (
                SELECT
                    _airbyte_ab_id AS _airbyte_raw_id,
                    _airbyte_data AS _airbyte_data,
                    _airbyte_emitted_at AS _airbyte_extracted_at,
                    CAST(NULL AS TIMESTAMP) AS _airbyte_loaded_at,
                    '{"sync_id": 0, "changes": []}' AS _airbyte_meta,
                    0 as _airbyte_generation_id
                FROM `$projectId`.$v1RawTable
            );
            """.trimIndent()
        )
    }

    /**
     * Does two things: escape single quotes (for use inside sql string literals),and escape double
     * quotes (for use inside JSON paths). For example, if a column name is foo'bar"baz, then we
     * want to end up with something like `SELECT JSON_QUERY(..., '$."foo\'bar\\"baz"')`. Note the
     * single-backslash for single-quotes (needed for SQL) and the double-backslash for
     * double-quotes (needed for JSON path).
     */
    private fun escapeColumnNameForJsonPath(stringContents: String): String {
        // This is not a place of honor.
        return stringContents // Consider the JSON blob {"foo\\bar": 42}.
            // This is an object with key foo\bar.
            // The JSONPath for this is $."foo\\bar" (i.e. 2 backslashes to represent the single
            // backslash in the key).
            // When we represent that path as a SQL string, the backslashes are doubled (to 4):
            // '$."foo\\\\bar"'
            // And we're writing that in a Java string, so we have to type out 8 backslashes:
            // "'$.\"foo\\\\\\\\bar\"'"
            .replace("\\", "\\\\\\\\") // Similar situation here:
            // a literal " needs to be \" in a JSONPath: $."foo\"bar"
            // which is \\" in a SQL string: '$."foo\\"bar"'
            // The backslashes become \\\\ in java, and the quote becomes \": "'$.\"foo\\\\\"bar\"'"
            .replace(
                "\"",
                "\\\\\""
            ) // Here we're escaping a SQL string, so we only need a single backslash (which is 2,
            // because Java).
            .replace("'", "\\'")
    }

    companion object {
        const val QUOTE: String = "`"
        private val nameTransformer = BigQuerySQLNameTransformer()

        @JvmStatic
        fun toDialectType(type: AirbyteType): StandardSQLTypeName {
            // switch pattern-matching is still in preview at language level 17 :(
            if (type is AirbyteProtocolType) {
                return toDialectType(type)
            } else if (type is Struct) {
                return StandardSQLTypeName.JSON
            } else if (type is Array) {
                return StandardSQLTypeName.JSON
            } else if (type is UnsupportedOneOf) {
                return StandardSQLTypeName.JSON
            } else if (type is Union) {
                val typeWithPrecedence: AirbyteType = type.chooseType()
                val dialectType: StandardSQLTypeName
                if ((typeWithPrecedence is Struct) || (typeWithPrecedence is Array)) {
                    dialectType = StandardSQLTypeName.JSON
                } else {
                    dialectType = toDialectType(typeWithPrecedence as AirbyteProtocolType)
                }
                return dialectType
            }

            // Literally impossible; AirbyteType is a sealed interface.
            throw IllegalArgumentException("Unsupported AirbyteType: $type")
        }

        // TODO maybe make this a BiMap and elevate this method and its inverse
        // (toDestinationSQLType?) to
        // the SQLGenerator?
        fun toDialectType(airbyteProtocolType: AirbyteProtocolType): StandardSQLTypeName {
            return when (airbyteProtocolType) {
                AirbyteProtocolType.STRING,
                AirbyteProtocolType.TIME_WITH_TIMEZONE -> StandardSQLTypeName.STRING
                AirbyteProtocolType.NUMBER -> StandardSQLTypeName.NUMERIC
                AirbyteProtocolType.INTEGER -> StandardSQLTypeName.INT64
                AirbyteProtocolType.BOOLEAN -> StandardSQLTypeName.BOOL
                AirbyteProtocolType.TIMESTAMP_WITH_TIMEZONE -> StandardSQLTypeName.TIMESTAMP
                AirbyteProtocolType.TIMESTAMP_WITHOUT_TIMEZONE -> StandardSQLTypeName.DATETIME
                AirbyteProtocolType.TIME_WITHOUT_TIMEZONE -> StandardSQLTypeName.TIME
                AirbyteProtocolType.DATE -> StandardSQLTypeName.DATE
                AirbyteProtocolType.UNKNOWN -> StandardSQLTypeName.JSON
            }
        }

        fun clusteringColumns(stream: StreamConfig): List<String> {
            val clusterColumns: MutableList<String> = ArrayList()
            if (stream.postImportAction == ImportType.DEDUPE) {
                // We're doing de-duping, therefore we have a primary key.
                // Cluster on the first 3 PK columns since BigQuery only allows up to 4 clustering
                // columns,
                // and we're always clustering on _airbyte_extracted_at
                stream.primaryKey.stream().limit(3).forEach { columnId: ColumnId ->
                    clusterColumns.add(columnId.name)
                }
            }
            clusterColumns.add("_airbyte_extracted_at")
            return clusterColumns
        }

        private fun buildExtractedAtCondition(minRawTimestamp: Optional<Instant>): String {
            return minRawTimestamp
                .map { ts: Instant -> " AND _airbyte_extracted_at > '$ts'" }
                .orElse("")
        }

        private fun cast(content: String, asType: String, useSafeCast: Boolean): String {
            val open = if (useSafeCast) "SAFE_CAST(" else "CAST("
            return wrap(open, "$content as $asType", ")")
        }

        private fun wrap(open: String, content: String, close: String): String {
            return open + content + close
        }
    }
}
