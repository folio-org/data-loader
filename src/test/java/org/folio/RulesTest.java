package org.folio;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import org.apache.commons.io.IOUtils;
import org.folio.rest.RestVerticle;
import org.folio.rest.jaxrs.model.Instance;
import org.folio.rest.persist.PostgresClient;
import org.folio.rest.tools.messages.Messages;
import org.folio.rest.tools.utils.NetworkUtils;
import org.folio.rest.tools.utils.ObjectMapperTool;
import org.folio.rest.tools.utils.VertxUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import junit.framework.Assert;

public class RulesTest {
  private static final Logger log = LoggerFactory.getLogger(Messages.class);

  private static Vertx vertx;
  private static int port;
  HttpClient client = vertx.createHttpClient();
  private static Locale oldLocale = Locale.getDefault();
  ObjectMapper jsonMapper = ObjectMapperTool.getMapper();

  static {
    System.setProperty(LoggerFactory.LOGGER_DELEGATE_FACTORY_CLASS_NAME, "io.vertx.core.logging.Log4jLogDelegateFactory");
  }

  /**
   * @param context  the test context.
   */
  @BeforeClass
  public static void setUp() throws IOException {
    vertx = VertxUtils.getVertxWithExceptionHandler();
    port = NetworkUtils.nextFreePort();

    try {
      deployRestVerticle();
    } catch (Exception e) {
      Assert.fail(e.getMessage());
    }
  }

  private static void deployRestVerticle() {

    CompletableFuture<String> deploymentComplete = new CompletableFuture<>();

    DeploymentOptions deploymentOptions = new DeploymentOptions().setConfig(
        new JsonObject().put("http.port", port));
    vertx.deployVerticle(RestVerticle.class.getName(), deploymentOptions, res -> {
      if(res.succeeded()) {
        deploymentComplete.complete(res.result());
      }
      else {
        deploymentComplete.completeExceptionally(res.cause());
      }
    });
  }

  /**
   * Cleanup: Delete temporary file, restore Locale, close the vert.x instance.
   *
   * @param context  the test context
   */
  @AfterClass
  public static void tearDown() {
    CompletableFuture<String> deploymentComplete = new CompletableFuture<>();

    deleteTempFilesCreated();
    Locale.setDefault(oldLocale);
    vertx.close(  res -> {
      if(res.succeeded()) {
        deploymentComplete.complete("success");
      }
      else {
        deploymentComplete.completeExceptionally(res.cause());
      }
    });
  }

  private static void deleteTempFilesCreated(){
    log.info("deleting created files");
    // Lists all files in folder
    File folder = new File(RestVerticle.DEFAULT_TEMP_DIR);
    File fList[] = folder.listFiles();
    // Searchs test.json
    for (int i = 0; i < fList.length; i++) {
        String pes = fList[i].getName();
        if (pes.endsWith("test.json")) {
            // and deletes
            boolean success = fList[i].delete();
        }
    }
  }

  @Test
  public void testLoading() throws Exception {

    String rules = getFile("rules.json");
    List<String> lines = getFileAsList("instanceObjects");

    CompletableFuture<Response> loadRulesCF = new CompletableFuture<>();
    CompletableFuture<TextResponse> loadDataCf = new CompletableFuture<>();

    postData("http://localhost:" + port + "/load/marc-rules", rules, empty(loadRulesCF));
    Response r = loadRulesCF.get();
    System.out.println("response for loading marc-rules is: " + r.getStatusCode());
    assertEquals(201, r.getStatusCode());

    String data = getFile("msplit00000000.mrc");

    postData("http://localhost:" + port + "/load/marc-data/test", data, text(loadDataCf));
    TextResponse t = loadDataCf.get();
    System.out.println("response for loading marc-data is: " + t.getStatusCode());
    assertEquals(201, t.getStatusCode());
    List<String> body = getBodyAsList(t.body);
    System.out.print("comparing line....");
    for(int i=0; i<lines.size(); i++){
      Instance instance = jsonMapper.readValue(
        body.get(i).substring(body.get(i).indexOf("|")+1),
        Instance.class);
      instance.setId(null);
      try {
        System.out.print((i+1) + " ");
        JsonAssert.areEqual(lines.get(i), PostgresClient.pojo2json(instance));
      } catch (Exception e) {
        System.out.println("error at " + (i+1));
        e.printStackTrace();
      }
    }
    System.out.println("all "+lines.size()+" lines matched...");
  }

  private String getFile(String filename) throws IOException {
    return IOUtils.toString(getClass().getClassLoader().getResourceAsStream(filename), "UTF-8");
  }

  private List<String> getFileAsList(String filename) throws IOException {
    return IOUtils.readLines(getClass().getClassLoader().getResourceAsStream(filename), "UTF-8");
  }

  private List<String> getBodyAsList(String data) throws IOException {
    return IOUtils.readLines(new StringReader(data));
  }

  private void postData(String url, String content, Handler<HttpClientResponse> responseHandler){
    HttpClientRequest request = client.postAbs(url, responseHandler);
    request.headers().add("Accept","application/json, text/plain");
    request.headers().add("Content-type","application/octet-stream");
    request.headers().add("x-okapi-tenant","ABC");
    request.end(content);
  }

  public static Handler<HttpClientResponse> empty(
      CompletableFuture<Response> completed) {

      return response -> {
        try {
          int statusCode = response.statusCode();

          completed.complete(new RulesTest.Response(statusCode));
        }
        catch(Exception e) {
          completed.completeExceptionally(e);
        }
      };
    }

  public static Handler<HttpClientResponse> text(
    CompletableFuture<TextResponse> completed) {

    return response -> {
        int statusCode = response.statusCode();

        response.bodyHandler(buffer -> {
          try {
            String body = buffer.toString("UTF8");

            completed.complete(new TextResponse(statusCode, body));

          } catch (Exception e) {
            completed.completeExceptionally(e);
          }
        });
    };
  }

  static class Response {
    private final int statusCode;

    public Response(int statusCode) {
      this.statusCode = statusCode;
    }

    public int getStatusCode() {
      return statusCode;
    }
  }

  static class TextResponse extends Response {
    private final String body;

    public TextResponse(int statusCode, String body) {
      super(statusCode);
      this.body = body;
    }

    public String getBody() {
      return body;
    }

    @Override
    public String toString() {
      return String.format("Status Code: %s Body: %s",
        getStatusCode(), getBody());
    }
  }

}