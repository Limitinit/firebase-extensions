package com.pipeline;

import com.google.api.services.bigquery.model.TableFieldSchema;
import com.google.api.services.bigquery.model.TableRow;
import com.google.api.services.bigquery.model.TableSchema;
import com.google.cloud.firestore.FirestoreOptions;
import com.google.firestore.v1.Document;
import com.google.firestore.v1.Value;
import com.google.firestore.v1.Write;
import com.google.protobuf.ByteString;
import com.google.protobuf.NullValue;
import com.google.protobuf.Timestamp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO;
import org.apache.beam.sdk.io.gcp.bigquery.SchemaAndRecord;
import org.apache.beam.sdk.io.gcp.firestore.FirestoreIO;
import org.apache.beam.sdk.io.gcp.firestore.RpcQosOptions;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.PipelineResult;
import org.apache.avro.generic.GenericRecord;
import org.apache.beam.runners.dataflow.options.DataflowPipelineOptions;

public class ExportPipeline {
  private static final FirestoreOptions FIRESTORE_OPTIONS = FirestoreOptions.getDefaultInstance();

  public interface MyOptions extends PipelineOptions {
    ValueProvider<String> getQuery();

    void setQuery(ValueProvider<String> value);

    ValueProvider<String> getFirestoreCollection();

    void setFirestoreCollection(ValueProvider<String> value);

    ValueProvider<String> getRunId();

    void setRunId(ValueProvider<String> value);

    ValueProvider<String> getFirestoreDatabaseId();

    void setFirestoreDatabaseId(ValueProvider<String> value);
  }

  static class TransformToFirestoreDocument extends DoFn<Map<String, Value>, Write> {

    private ValueProvider<String> firestoreCollection;
    private ValueProvider<String> databaseId;
    private ValueProvider<String> runId;

    public TransformToFirestoreDocument(ValueProvider<String> firestoreCollection,
        ValueProvider<String> firestoreDatabaseId, ValueProvider<String> runId) {
      this.firestoreCollection = firestoreCollection;
      this.databaseId = firestoreDatabaseId;
      this.runId = runId;
    }

    @ProcessElement
    public void processElement(ProcessContext context) {

      Map<String, Value> firestoreMap = context.element();

      // Convert the TableRow to a Map<String, Value> for Firestore

      String collection = this.firestoreCollection + "/" + this.runId + "/" + "output";
      String path = createDocumentName(collection + "/" + UUID.randomUUID().toString(), this.databaseId);

      // Create a Firestore Write object from the map
      try {
        Write write = Write.newBuilder()
            .setUpdate(Document.newBuilder().putAllFields(firestoreMap).setName(path).build())
            .build();

        context.output(write);
      } catch (Exception e) {
        // Log error and continue with the next records
        System.err.println("Error while processing record: " + firestoreMap + " ," + e.getMessage());

      }
    }
  }

  public static void main(String[] args) {
    MyOptions options = PipelineOptionsFactory.fromArgs(args).as(MyOptions.class);
    Pipeline pipeline = Pipeline.create(options);
    RpcQosOptions rpcQosOptions = RpcQosOptions.newBuilder()
        .build();

    pipeline
        .apply("ReadFromBigQuery",
            BigQueryIO.read(new SerializableFunction<SchemaAndRecord, Map<String, Value>>() {
              public Map<String, Value> apply(SchemaAndRecord schemaAndRecord) {
                return convertToFirestoreValue(schemaAndRecord);
              }
            }).withoutValidation().fromQuery(options.getQuery()).usingStandardSql()
                .withTemplateCompatibility())
        .apply("TransformToFirestoreDocument",
            ParDo.of(new TransformToFirestoreDocument(options.getFirestoreCollection(),
                options.getFirestoreDatabaseId(), options.getRunId())))
        .apply("WriteToFirestore", FirestoreIO.v1().write().batchWrite().withRpcQosOptions(rpcQosOptions).build());

    PipelineResult result = pipeline.run(); // Capture the result instead of running waitUntilFinish here

    // We try to identify if the pipeline is being run or a template is being
    // created
    if (options.as(DataflowPipelineOptions.class).getTemplateLocation() == null) {
      // If template location is null, then, pipeline is being run, so we can wait
      // until finish
      result.waitUntilFinish();
    }
  }

  private static String createDocumentName(String path, ValueProvider<String> customDatabaseId) {

    ValueProvider<String> databaseId = customDatabaseId;

    String documentPath = String.format(
        "projects/%s/databases/%s/documents",
        FIRESTORE_OPTIONS.getProjectId(),
        databaseId);

    return documentPath + "/" + path;
  }

  private static Map<String, Value> convertToFirestoreValue(SchemaAndRecord schemaAndRecord) {
    Map<String, Value> firestoreDocument = new HashMap<>();

    GenericRecord record = schemaAndRecord.getRecord();

    record.getSchema().getFields().forEach(field -> {
      Object fieldValue = record.get(field.pos());

      // from the BQ source code comments:
      // * [Required] The field data type. Possible values include STRING, BYTES,
      // INTEGER, INT64 (same as
      // * INTEGER), FLOAT, FLOAT64 (same as FLOAT), NUMERIC, BIGNUMERIC, BOOLEAN,
      // BOOL (same as BOOLEAN),
      // * TIMESTAMP, DATE, TIME, DATETIME, INTERVAL, RECORD (where RECORD indicates
      // that the field
      // * contains a nested schema) or STRUCT (same as RECORD).
      String fieldType = field.schema().getType().getName();

      Value.Builder valueBuilder = Value.newBuilder();

      switch (fieldType) {
        case "STRING":
          fieldValue = fieldValue.toString();
          valueBuilder.setStringValue(fieldValue.toString());
          break;
        case "BYTES":
          ByteString byteString = ByteString.copyFrom((byte[]) fieldValue);
          valueBuilder.setBytesValue(byteString);
          break;
        case "INTEGER":
        case "INT64":
          valueBuilder.setIntegerValue(Long.parseLong(fieldValue.toString()));
          break;
        case "FLOAT":
        case "FLOAT64":
          valueBuilder.setDoubleValue(Double.parseDouble(fieldValue.toString()));
          break;
        // case "NUMERIC":
        // case "BIGNUMERIC":
        // break;
        case "BOOLEAN":
        case "BOOL":
          fieldValue = Boolean.parseBoolean(fieldValue.toString());
          break;
        // case "TIMESTAMP":
        // TODO: handle timestamps
        // Timestamp timestamp =
        // Timestamp.newBuilder().setSeconds(Long.parseLong(fieldValue.toString())).build();
        // valueBuilder.setTimestampValue(fieldValue.toString());
        // break;
        case "DATE":
        case "TIME":
        case "DATETIME":
          fieldValue = fieldValue.toString();
          break;
        case "INTERVAL":
          fieldValue = fieldValue.toString();
          break;
        case "RECORD":
        case "STRUCT":
        // TODO: We need to recursively convert the nested records
          fieldValue = fieldValue.toString();
          break;
        default:
          valueBuilder.setStringValue(fieldValue.toString());
          break;

      }
      firestoreDocument.put(field.name(), valueBuilder.build());

    });

    return firestoreDocument;
  }
}