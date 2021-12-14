package com.example.cloudrun;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.cloud.spanner.*;
import com.google.events.cloud.pubsub.v1.MessagePublishedData;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.cloud.storage.BlobId;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.channels.Channels;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import java.util.List;
import java.util.Map;
import java.lang.StringBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EventController {

  private static final List<String> requiredFields =
      Arrays.asList("ce-id", "ce-source", "ce-type", "ce-specversion");

  @RequestMapping(value = "/", method = RequestMethod.POST)
  public ResponseEntity<String> receiveMessage(
      @RequestBody StorageObjectData body, @RequestHeader Map<String, String> headers) {
    for (String field : requiredFields) {
      if (headers.get(field) == null) {
        String msg = String.format("Missing expected header: %s.", field);
        System.out.println(msg);
        return new ResponseEntity<String>(msg, HttpStatus.BAD_REQUEST);
      }
    }
    if (headers.get("ce-subject") == null) {
      String msg = "Missing expected header: ce-subject.";
      System.out.println(msg);
      return new ResponseEntity<String>(msg, HttpStatus.BAD_REQUEST);
    }

    // Retrieve data from Google Cloud Storage
    Storage storage = StorageOptions.getDefaultInstance().getService();
    BlobId blobId = BlobId.of(body.getBucket(), body.getName());

    // Parse a CSV file
    InputStream stream = Channels.newInputStream(storage.reader(blobId));
    StringBuilder sql = new StringBuilder("INSERT INTO app (id, value) VALUES ");
    try (CSVReader reader = new CSVReader(new InputStreamReader(stream, UTF_8))) {
      String[] cells;
      int index = -1;
      while ((cells = reader.readNext()) != null) {
        index++;
        if (index < 1) continue;
        sql.append("(").append(ThreadLocalRandom.current().nextInt());
        sql.append(",").append(Integer.parseInt(cells[0])).append("),");
      }
      if (index > 0) {
        sql.deleteCharAt(sql.length()-1);
      }
    } catch (CsvException e) {
      String msg = "The object is not CSV formatted.";
      System.out.println(msg);
      return new ResponseEntity<String>(msg, HttpStatus.BAD_REQUEST);
    } catch (IOException e) {
      String msg = "The object is not a readable file.";
      System.out.println(msg);
      return new ResponseEntity<String>(msg, HttpStatus.BAD_REQUEST);
    }

    // Save records to Cloud Spanner
    SpannerOptions.Builder builder = SpannerOptions.newBuilder();
    if (SpannerOptions.getDefaultProjectId() == null) {
      builder.setProjectId("YOUR_PROJECT_ID");
    }
    SpannerOptions options = builder.build();
    try (Spanner spanner = options.getService()) {
      DatabaseId db = DatabaseId.of(options.getProjectId(),
              System.getenv("SPANNER_INSTANCE"),
              System.getenv("SPANNER_TABLE"));
      DatabaseClient client = spanner.getDatabaseClient(db);
      client.readWriteTransaction().run(transaction -> {
        transaction.executeUpdate(Statement.of(sql.toString()));
        return null;
      });
    }
    return new ResponseEntity<>("done successfully", HttpStatus.OK);
  }
}
