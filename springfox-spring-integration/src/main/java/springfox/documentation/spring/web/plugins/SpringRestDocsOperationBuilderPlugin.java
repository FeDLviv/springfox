package springfox.documentation.spring.web.plugins;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import rawhttp.core.RawHttp;
import rawhttp.core.RawHttpHeaders;
import rawhttp.core.RawHttpResponse;
import rawhttp.core.body.BodyReader;
import springfox.documentation.builders.ResponseMessageBuilder;
import springfox.documentation.schema.Example;
import springfox.documentation.schema.ModelRef;
import springfox.documentation.service.Header;
import springfox.documentation.service.ResponseMessage;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spi.service.OperationBuilderPlugin;
import springfox.documentation.spi.service.contexts.OperationContext;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.*;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toMap;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1000)
public class SpringRestDocsOperationBuilderPlugin implements OperationBuilderPlugin {

  Logger LOG = LoggerFactory.getLogger(SpringRestDocsOperationBuilderPlugin.class);

  @Override
  public void apply(OperationContext context) {
    context.operationBuilder()
      .responseMessages(read(context));
  }

  @Override
  public boolean supports(DocumentationType documentationType) {
    return DocumentationType.SWAGGER_12.equals(documentationType)
      || DocumentationType.SWAGGER_2.equals(documentationType);
  }

  /**
   * Provides response messages with examples for given single operation context.
   *
   * @param context representing an operation
   * @return response messages.
   */
  protected Set<ResponseMessage> read(OperationContext context) {
    Set<ResponseMessage> ret;
    try {
      PathMatchingResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver();
      Resource[] resources = resourceResolver.getResources(
        "classpath*:"
          + context.getName()
          + "*/http-response.springfox");
      // TODO: restdocs in safe package name, not directly under restdocs

      ret = Arrays.stream(resources)
        .map(toRawHttpResponse())
        .filter(Objects::nonNull)
        .collect(Collectors.collectingAndThen(
          toMap(
            RawHttpResponse::getStatusCode,
            mappingResponseToResponseMessageBuilder(),
            mergingExamples()
          ),
          responseMessagesMap -> responseMessagesMap.values()
            .stream()
            .map(ResponseMessageBuilder::build)
            .collect(Collectors.toSet())));
    } catch (
      Exception e) {
      LOG.warn("Failed to read restdocs example for {} " + context.getName() + " caused by: " + e.toString());
      ret = Collections.emptySet();
    }
    return ret;

  }

  private Function<Resource, RawHttpResponse<Void>> toRawHttpResponse() {
    return resource -> {
      try (InputStream resourceAsStream = new FileInputStream(resource.getFile())) {
        RawHttp rawHttp = new RawHttp();
        // TODO must extract the body before the stream is closed
        RawHttpResponse<Void> rawHttpResponse = rawHttp.parseResponse(resourceAsStream).eagerly();
        return rawHttpResponse;
      } catch (IOException e) {
        LOG.warn("Failed to read restdocs example for {} "
          + resource.getFilename() + " caused by: " + e.toString());
        return null;
      }
    };
  }

  private BinaryOperator<ResponseMessageBuilder> mergingExamples() {
    return (leftWithSameStatusCode, rightWithSameStatusCode) ->
      leftWithSameStatusCode.examples(rightWithSameStatusCode.build()
        .getExamples());
  }

  private Function<RawHttpResponse<Void>, ResponseMessageBuilder> mappingResponseToResponseMessageBuilder() {
    return parsedResponse -> {
      return new ResponseMessageBuilder()
        .code(parsedResponse.getStatusCode())
        .examples(toExamples(parsedResponse))
        .headersWithDescription(toHeaders(parsedResponse));
    };
  }

  private Map<String, Header> toHeaders(RawHttpResponse<Void> parsedResponse) {
    return parsedResponse.getHeaders()
      .asMap()
      .entrySet()
      .stream()
      .collect(toMap(
        Map.Entry::getKey,
        o -> new Header(o.getKey(), "", new ModelRef("string"))));
  }

  private ArrayList<Example> toExamples(RawHttpResponse<Void> parsedResponse) {
    return new ArrayList<>(singletonList(new Example(getContentType(parsedResponse),
      getBody(parsedResponse))));
  }

  private String getBody(RawHttpResponse<Void> parsedResponse) {
    return parsedResponse.getBody()
      .map(bodyReader -> {
        String ret = null;
        try {
          ret = bodyReader.asRawString(Charset.forName("utf-8"));
        } catch (IOException e) {
          LOG.error("failed to read response body", e);
        }
        return ret;
      })
      .orElse(null);
  }

  private String getContentType(RawHttpResponse<Void> parsedResponse) {
    return parsedResponse.getHeaders()
      .get("Content-Type")
      .stream()
      .findFirst()
      .orElse(null);
  }
}
